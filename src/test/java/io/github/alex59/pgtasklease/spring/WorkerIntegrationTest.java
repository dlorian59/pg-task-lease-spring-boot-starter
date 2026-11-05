package io.github.alex59.pgtasklease.spring;

import static org.junit.jupiter.api.Assertions.*;
import io.github.alex59.pgtasklease.*;
import io.r2dbc.spi.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

@Tag("postgres")
@Timeout(15)
class WorkerIntegrationTest {
    static final Duration WAIT = Duration.ofSeconds(5);
    static ConnectionFactory connections;
    static PgTaskQueue queue;
    PgTaskWorker worker;

    @BeforeAll static void schema() throws Exception {
        String url = System.getenv("PG_TASK_TEST_URL");
        if (url == null) throw new IllegalArgumentException("Set PG_TASK_TEST_URL to a fresh disposable PostgreSQL database ending in _test");
        var options = ConnectionFactoryOptions.parse(url);
        if (!options.getRequiredValue(ConnectionFactoryOptions.DATABASE).toString().endsWith("_test"))
            throw new IllegalArgumentException("Disposable test database must end in _test");
        connections = ConnectionFactories.get(options);
        queue = new PgTaskQueue(connections);
        try (var stream = PgTaskQueue.class.getResourceAsStream("/pg-task-lease.sql")) {
            assertNotNull(stream);
            for (String sql : new String(stream.readAllBytes(), StandardCharsets.UTF_8).split(";")) {
                if (!sql.isBlank()) Mono.usingWhen(Mono.from(connections.create()), connection ->
                        Flux.from(connection.createStatement(sql).execute()).concatMap(Result::getRowsUpdated).then(),
                        connection -> Mono.from(connection.close())).block(WAIT);
            }
        }
    }
    @AfterEach void stop() { if (worker != null) worker.stop(); }

    @Test void handlerFailuresRetryWithinAttemptBudgetAndStoreNoExceptionMessage() {
        String name = "retry-" + UUID.randomUUID();
        var success = task(name, "eventually succeeds", 2);
        var poison = task(name, "always fails", 2);
        worker = new PgTaskWorker(queue, settings(2, Duration.ofSeconds(2)), List.of(new TaskHandlerRegistration(name,
                lease -> lease.attempt() == 1 || lease.payload().equals("always fails")
                        ? Mono.error(new IllegalStateException("secret must not be stored")) : Mono.empty())));
        worker.start();
        await(() -> state(success) == TaskState.DONE && state(poison) == TaskState.DEAD);
        assertEquals(2, queue.find(success.id()).block(WAIT).attempts());
        var dead = queue.find(poison.id()).block(WAIT);
        assertEquals(2, dead.attempts());
        assertEquals("Handler failed: IllegalStateException", dead.lastError());
    }

    @Test void concurrencyIsBoundedAndStopCancelsOnlyOwnedSubscriptions() {
        String name = "bounded-" + UUID.randomUUID();
        for (int i = 0; i < 5; i++) task(name, "pending", 2);
        var active = new AtomicInteger(); var peak = new AtomicInteger(); var cancelled = new AtomicInteger();
        worker = new PgTaskWorker(queue, settings(2, Duration.ofSeconds(5)), List.of(new TaskHandlerRegistration(name,
                lease -> Mono.<Void>never().doOnSubscribe(s -> peak.accumulateAndGet(active.incrementAndGet(), Math::max))
                        .doOnCancel(() -> { active.decrementAndGet(); cancelled.incrementAndGet(); }))));
        worker.start();
        await(() -> active.get() == 2);
        Mono.delay(Duration.ofMillis(250)).block(WAIT);
        assertEquals(2, peak.get());
        worker.stop();
        await(() -> cancelled.get() == 2);
        assertFalse(worker.isRunning());
        assertEquals(3, queue.claim(name, "inspection", 5, Duration.ofSeconds(2)).block(WAIT).size());
    }

    @Test void heartbeatKeepsLongWorkAliveAndRestartRecoversCancelledWork() {
        String name = "heartbeat-" + UUID.randomUUID();
        var request = task(name, "long job", 2);
        var started = new AtomicReference<TaskLease>();
        var first = Sinks.<Void>empty(); var calls = new AtomicInteger(); var cancelled = new AtomicInteger();
        worker = new PgTaskWorker(queue, settings(1, Duration.ofSeconds(5)), List.of(new TaskHandlerRegistration(name, lease -> {
            started.set(lease);
            return calls.incrementAndGet() == 1 ? first.asMono().doOnCancel(cancelled::incrementAndGet) : Mono.empty();
        })));
        worker.start();
        await(() -> started.get() != null);
        var acquired = started.get();
        Mono.delay(Duration.ofMillis(750)).block(WAIT);
        assertEquals(TaskState.LEASED, state(request));
        assertTrue(queue.find(request.id()).block(WAIT).expiresAt().isAfter(acquired.expiresAt()));
        worker.stop();
        await(() -> cancelled.get() == 1);
        Mono.delay(Duration.ofMillis(700)).block(WAIT);
        worker.start();
        await(() -> state(request) == TaskState.DONE);
        first.tryEmitEmpty();
        assertEquals(2, calls.get());
        assertEquals(2, queue.find(request.id()).block(WAIT).attempts());
    }

    @Test void lostHeartbeatCancelsHandlerAndHandlerTimeoutConsumesTheAttempt() {
        String name = "lost-" + UUID.randomUUID();
        var request = task(name, "lost externally", 1);
        var captured = new AtomicReference<TaskLease>(); var cancelled = new AtomicInteger();
        worker = new PgTaskWorker(queue, settings(1, Duration.ofSeconds(3)), List.of(new TaskHandlerRegistration(name, lease -> {
            captured.set(lease);
            return Mono.<Void>never().doOnCancel(cancelled::incrementAndGet);
        })));
        worker.start();
        await(() -> captured.get() != null);
        assertTrue(queue.complete(captured.get()).block(WAIT));
        await(() -> cancelled.get() == 1);
        assertEquals(TaskState.DONE, state(request));
        assertNull(queue.find(request.id()).block(WAIT).lastError());
        worker.stop();
        String timed = "timeout-" + UUID.randomUUID();
        var timeout = task(timed, "never completes", 1);
        worker = new PgTaskWorker(queue, settings(1, Duration.ofMillis(100)),
                List.of(new TaskHandlerRegistration(timed, lease -> Mono.never())));
        worker.start();
        await(() -> state(timeout) == TaskState.DEAD);
        assertEquals("Handler failed: TimeoutException", queue.find(timeout.id()).block(WAIT).lastError());
    }

    @Test void annotatedBlockingWorkKeepsHeartbeatWhileRunningAndAcknowledgesOnlyAfterReturn() {
        var request = TaskRequest.ready(UUID.randomUUID(), "blocking-report", "2026-Q4").withMaxAttempts(2);
        assertTrue(queue.submit(request).block(WAIT));
        assertFalse(queue.submit(request.withPriority(99)).block(WAIT));
        var handler = new BlockingReport();
        var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        beans.registerSingleton("report", handler);
        worker = new PgTaskWorker(queue, settings(1, Duration.ofSeconds(4)), TaskHandlerMethods.discover(beans, List.of()));
        worker.start();
        await(() -> handler.started.get() != null);
        var acquired = handler.started.get();
        Mono.delay(Duration.ofMillis(850)).block(WAIT);
        var pending = queue.find(request.id()).block(WAIT);
        assertEquals(TaskState.LEASED, pending.state());
        assertEquals(0, acquired.priority(), "Duplicate submission must not overwrite the original");
        assertTrue(pending.expiresAt().isAfter(acquired.expiresAt()));
        handler.release.countDown();
        await(() -> state(request) == TaskState.DONE);
        assertEquals(1, queue.find(request.id()).block(WAIT).attempts());
    }
    public static class BlockingReport {
        final AtomicReference<TaskLease> started = new AtomicReference<>();
        final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        @PgTaskHandler(value = "blocking-report", blocking = true)
        public void create(TaskLease lease) throws InterruptedException {
            started.set(lease);
            if (!release.await(3, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Test did not release handler");
        }
    }

    static PgTaskLeaseProperties.Worker settings(int concurrency, Duration handlerTimeout) {
        return new PgTaskLeaseProperties.Worker(true, "integration-worker", concurrency, Duration.ofMillis(20),
                Duration.ofMillis(600), Duration.ofMillis(80), Duration.ofMillis(50), 10, Duration.ZERO,
                Duration.ofMillis(200), handlerTimeout);
    }
    static TaskRequest task(String name, String payload, int attempts) {
        var request = new TaskRequest(UUID.randomUUID(), name, payload, 0, attempts, Duration.ZERO);
        assertTrue(queue.submit(request).block(WAIT));
        return request;
    }
    static TaskState state(TaskRequest task) { return queue.find(task.id()).block(WAIT).state(); }
    static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) fail("Condition did not become true within " + WAIT);
            try { Thread.sleep(10); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
        }
    }
}
