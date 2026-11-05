package io.github.alex59.pgtasklease.spring;

import static org.junit.jupiter.api.Assertions.*;
import io.github.alex59.pgtasklease.PgTaskQueue;
import io.github.alex59.pgtasklease.TaskLease;
import io.r2dbc.spi.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

class HandlerMethodsTest {
    interface Job { Mono<Void> handle(TaskLease lease); }

    @Test void jdkAndClassProxyAdviceRemainInTheAsyncInvocationPath() {
        for (boolean classProxy : List.of(false, true)) {
            var target = new PendingJob();
            var advice = new AtomicInteger(); var completedAdvice = new AtomicInteger();
            var proxy = new ProxyFactory(target);
            proxy.setProxyTargetClass(classProxy);
            proxy.addAdvice((MethodInterceptor) invocation -> {
                advice.incrementAndGet();
                return ((Mono<?>) invocation.proceed()).doOnSuccess(value -> completedAdvice.incrementAndGet());
            });
            var registration = registration(proxy.getProxy());
            var operation = registration.handler().apply(lease());
            assertEquals(0, advice.get(), "Registration and assembly must not call business methods");
            var pending = operation.toFuture();
            assertFalse(pending.isDone());
            assertEquals(1, advice.get());
            assertEquals(0, completedAdvice.get());
            target.result.tryEmitEmpty();
            pending.join();
            assertEquals(1, completedAdvice.get());
        }
    }

    @Test void annotatedPublisherPropagatesCancellationAndOriginalSyncOrAsyncErrors() {
        var target = new PendingJob();
        var subscription = registration(target).handler().apply(lease()).subscribe();
        subscription.dispose();
        assertEquals(1, target.cancelled.get());
        var errors = new FailingJob();
        assertSame(errors.failure, assertThrows(IllegalArgumentException.class,
                () -> registration(errors).handler().apply(lease()).block()));
        errors.synchronous = false;
        assertSame(errors.failure, assertThrows(IllegalArgumentException.class,
                () -> registration(errors).handler().apply(lease()).block()));
    }

    @Test void rejectsUnsupportedSignaturesProxyExposureAndMixedDuplicateQueues() {
        for (Object invalid : List.of(new FinalJob(), new PrivateJob(), new WrongResult(), new StaticJob())) {
            assertThrows(IllegalArgumentException.class, () -> registration(invalid));
        }
        var proxy = new ProxyFactory(new UnexposedJob());
        assertThrows(IllegalArgumentException.class, () -> registration(proxy.getProxy()));
        var dynamic = new ProxyFactory(new PendingJob());
        dynamic.setTargetSource(new org.springframework.aop.target.HotSwappableTargetSource(new PendingJob()));
        assertThrows(IllegalArgumentException.class, () -> registration(dynamic.getProxy()));
        var registrations = List.of(registration(new PendingJob()), new TaskHandlerRegistration("reports", lease -> Mono.empty()));
        var properties = WorkerIntegrationTest.settings(1, java.time.Duration.ofSeconds(2));
        var factory = new ConnectionFactory() {
            public Mono<? extends Connection> create() { return Mono.error(new AssertionError("Validation must not connect")); }
            public ConnectionFactoryMetadata getMetadata() { return () -> "PostgreSQL"; }
        };
        assertThrows(IllegalArgumentException.class, () -> new PgTaskWorker(new PgTaskQueue(factory), properties, registrations));
    }

    @Test void blockingHandlerRunsLazilyThroughProxyOffCallerThreadAndSupportsInterruption() throws Exception {
        var target = new BlockingJob();
        var advice = new AtomicInteger();
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice((MethodInterceptor) invocation -> { advice.incrementAndGet(); return invocation.proceed(); });
        var publisher = registration(proxy.getProxy()).handler().apply(lease());
        assertEquals(0, advice.get());
        var subscription = publisher.subscribe();
        assertTrue(target.started.await(2, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(1, advice.get());
        assertNotSame(Thread.currentThread(), target.thread);
        subscription.dispose();
        assertTrue(target.interrupted.await(2, java.util.concurrent.TimeUnit.SECONDS));
        var failing = new BlockingFailure();
        assertSame(failing.failure, assertThrows(IllegalArgumentException.class,
                () -> registration(failing).handler().apply(lease()).block(java.time.Duration.ofSeconds(2))));
        assertThrows(IllegalArgumentException.class, () -> registration(new WrongBlockingResult()));
        assertThrows(IllegalArgumentException.class, () -> registration(new UnmarkedVoid()));
    }

    public static class BlockingJob {
        final java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch interrupted = new java.util.concurrent.CountDownLatch(1);
        volatile Thread thread;
        @PgTaskHandler(value = "reports", blocking = true) public void handle(TaskLease lease) {
            thread = Thread.currentThread(); started.countDown();
            try { new java.util.concurrent.CountDownLatch(1).await(); }
            catch (InterruptedException expected) { Thread.currentThread().interrupt(); interrupted.countDown(); }
        }
    }
    public static class BlockingFailure {
        final IllegalArgumentException failure = new IllegalArgumentException("business failure");
        @PgTaskHandler(value = "reports", blocking = true) public void handle(TaskLease lease) { throw failure; }
    }
    public static class WrongBlockingResult {
        @PgTaskHandler(value = "reports", blocking = true) public Mono<Void> handle(TaskLease lease) { return Mono.empty(); }
    }
    public static class UnmarkedVoid {
        @PgTaskHandler("reports") public void handle(TaskLease lease) { }
    }

    private static TaskHandlerRegistration registration(Object bean) {
        var factory = new DefaultListableBeanFactory();
        factory.registerSingleton("job", bean);
        var registrations = TaskHandlerMethods.discover(factory, List.of());
        assertEquals(1, registrations.size());
        return registrations.get(0);
    }
    private static TaskLease lease() {
        return new TaskLease(UUID.randomUUID(), "reports", "2026-Q4", 0, 1, 3, UUID.randomUUID(), 1L,
                "test", Instant.now().plusSeconds(30));
    }
    public static class PendingJob implements Job {
        final Sinks.Empty<Void> result = Sinks.empty();
        final AtomicInteger cancelled = new AtomicInteger();
        @PgTaskHandler("reports") public Mono<Void> handle(TaskLease lease) {
            return result.asMono().doOnCancel(cancelled::incrementAndGet);
        }
    }
    public static class FailingJob implements Job {
        final IllegalArgumentException failure = new IllegalArgumentException("business error");
        boolean synchronous = true;
        @PgTaskHandler("reports") public Mono<Void> handle(TaskLease lease) {
            if (synchronous) throw failure;
            return Mono.error(failure);
        }
    }
    public static class FinalJob {
        @PgTaskHandler("reports") public final Mono<Void> handle(TaskLease lease) { return Mono.empty(); }
    }
    public static class PrivateJob {
        @PgTaskHandler("reports") private Mono<Void> handle(TaskLease lease) { return Mono.empty(); }
    }
    public static class WrongResult {
        @PgTaskHandler("reports") public Mono<String> handle(TaskLease lease) { return Mono.just("wrong"); }
    }
    public static class StaticJob {
        @PgTaskHandler("reports") public static Mono<Void> handle(TaskLease lease) { return Mono.empty(); }
    }
    public static class UnexposedJob implements Runnable {
        @Override public void run() { }
        @PgTaskHandler("reports") public Mono<Void> handle(TaskLease lease) { return Mono.empty(); }
    }
}
