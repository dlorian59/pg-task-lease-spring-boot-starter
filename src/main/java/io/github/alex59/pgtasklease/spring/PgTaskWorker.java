package io.github.alex59.pgtasklease.spring;

import io.github.alex59.pgtasklease.PgTaskQueue;
import io.github.alex59.pgtasklease.TaskLease;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Opt-in bounded worker. Uses Reactor's shared timer resources; owns only its subscriptions.
 * Stop cancels processing; unacknowledged leases expire and can be recovered. External effects require idempotency.
 */
public class PgTaskWorker implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(PgTaskWorker.class);
    private final PgTaskQueue queue;
    private final PgTaskLeaseProperties.Worker settings;
    private final List<TaskHandlerRegistration> handlers;
    private volatile Session current;

    /**
     * Creates an explicitly configured worker; construction does not connect or start polling.
     * @param queue existing queue client
     * @param settings validated worker budgets
     * @param handlers unique explicit queue registrations
     */
    public PgTaskWorker(PgTaskQueue queue, PgTaskLeaseProperties.Worker settings, List<TaskHandlerRegistration> handlers) {
        this.queue = Objects.requireNonNull(queue, "queue");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.handlers = List.copyOf(handlers);
        if (this.handlers.isEmpty()) throw new IllegalArgumentException("An enabled worker requires at least one TaskHandlerRegistration bean");
        var names = new HashSet<String>();
        for (var handler : this.handlers) if (!names.add(handler.queue()))
            throw new IllegalArgumentException("Duplicate handler for queue " + handler.queue());
    }

    @Override public synchronized void start() {
        if (isRunning()) return;
        var session = new Session();
        current = session;
        session.subscriptions.add(Mono.defer(() -> poll(session))
                .repeatWhen(completed -> completed.delayElements(settings.pollInterval()))
                .subscribe(ignored -> { }, error -> report("poll-loop", error)));
        session.subscriptions.add(Mono.defer(() -> recover(session))
                .repeatWhen(completed -> completed.delayElements(settings.recoveryInterval()))
                .subscribe(ignored -> { }, error -> report("recovery-loop", error)));
    }

    @Override public synchronized void stop() {
        var session = current;
        current = null;
        if (session != null) session.subscriptions.dispose();
    }
    @Override public void stop(Runnable callback) { stop(); callback.run(); }
    @Override public boolean isRunning() { return current != null; }
    @Override public boolean isAutoStartup() { return settings.enabled(); }
    @Override public int getPhase() { return Integer.MAX_VALUE - 100; }

    private boolean active(Session session) { return current == session && !session.subscriptions.isDisposed(); }

    private Mono<Void> poll(Session session) {
        if (!active(session)) return Mono.empty();
        var registration = handlers.get(session.nextQueue++ % handlers.size());
        if (session.nextQueue == Integer.MAX_VALUE) session.nextQueue = 0;
        return queue.claim(registration.queue(), settings.owner(), settings.concurrency(), settings.leaseTime())
                .timeout(settings.operationTimeout())
                .flatMapMany(Flux::fromIterable)
                .flatMap(lease -> execute(session, registration, lease), settings.concurrency())
                .then().onErrorResume(error -> { report("claim", error); return Mono.empty(); });
    }

    private Mono<Void> execute(Session session, TaskHandlerRegistration registration, TaskLease lease) {
        if (!active(session)) return Mono.empty();
        Mono<Void> processing = Mono.defer(() -> active(session) ? Objects.requireNonNull(registration.handler().apply(lease), "handler publisher") : Mono.empty())
                .timeout(settings.handlerTimeout())
                .thenReturn(true)
                .onErrorResume(error -> {
                    if (!active(session)) return Mono.just(false);
                    String diagnostic = "Handler failed: " + error.getClass().getSimpleName();
                    return queue.fail(lease, settings.retryDelay(), diagnostic.substring(0, Math.min(2048, diagnostic.length())))
                            .timeout(settings.operationTimeout()).flatMap(changed -> changed ? Mono.just(false) : Mono.error(new LostLease()));
                })
                .flatMap(success -> {
                    if (!success || !active(session)) return Mono.empty();
                    return queue.complete(lease).timeout(settings.operationTimeout())
                            .flatMap(changed -> changed ? Mono.empty() : Mono.error(new LostLease()));
                });
        Mono<Void> heartbeat = Mono.defer(() -> {
                    if (!active(session)) return Mono.error(new LostLease());
                    return queue.heartbeat(lease, settings.leaseTime()).timeout(settings.operationTimeout())
                            .flatMap(changed -> changed ? Mono.<Void>empty() : Mono.error(new LostLease()));
                }).delaySubscription(settings.heartbeatInterval()).repeat().then();
        return Mono.firstWithSignal(processing, heartbeat)
                .onErrorResume(error -> { report("task", error); return Mono.empty(); });
    }

    private Mono<Void> recover(Session session) {
        return Flux.fromIterable(handlers).concatMap(registration -> {
            if (!active(session)) return Mono.empty();
            return queue.recoverExpired(registration.queue(), settings.recoveryBatchSize(), settings.retryDelay())
                    .timeout(settings.operationTimeout())
                    .onErrorResume(error -> { report("recovery", error); return Mono.empty(); });
        }).then();
    }

    private static void report(String operation, Throwable error) {
        // Do not log payloads or exception messages, which may contain application secrets.
        log.warn("pg-task-lease {} failed ({})", operation, error.getClass().getSimpleName());
    }
    private static final class Session {
        final Disposable.Composite subscriptions = Disposables.composite();
        int nextQueue;
    }
    private static final class LostLease extends IllegalStateException { }
}
