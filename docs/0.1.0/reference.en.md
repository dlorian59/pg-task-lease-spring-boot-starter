# 0.1.0 — API and capability map

[Versions](../index.md) · [Guide](en.md) · [English](reference.en.md) · [Русский](reference.ru.md) · [README](../../README.md)

## Capability → code → limits

| Capability / public API | Runnable code or implementation | Limits |
|---|---|---|
| Queue injection / PgTaskLeaseAutoConfiguration | [ReportApplication](../../examples/report-worker/src/main/java/example/ReportApplication.java), [configuration](../../src/main/java/io/github/alex59/pgtasklease/spring/PgTaskLeaseAutoConfiguration.java) | Requires an unambiguous ConnectionFactory; user queue/worker beans win; no schema DDL. |
| Annotated registration / @PgTaskHandler(value, blocking=false) | [ReportJobs.generate](../../examples/report-worker/src/main/java/example/ReportJobs.java) | Literal queue name; public nonstatic nonfinal Mono<Void>(TaskLease), or void(TaskLease) with blocking=true; one handler per queue. |
| Manual registration / TaskHandlerRegistration(queue, handler) | [manual alternative](en.md#register-business-work), [record](../../src/main/java/io/github/alex59/pgtasklease/spring/TaskHandlerRegistration.java) | Constructor validates queue and function; choose this OR an annotation for each queue. |
| Typed configuration / PgTaskLeaseProperties(enabled, worker) | [complete settings table](en.md#operational-contract-and-settings), [properties](../../src/main/java/io/github/alex59/pgtasklease/spring/PgTaskLeaseProperties.java) | Global enabled defaults true; worker enabled defaults false. Binding validates every duration and count. |
| Lifecycle / PgTaskWorker | [worker](../../src/main/java/io/github/alex59/pgtasklease/spring/PgTaskWorker.java), [application main](../../examples/report-worker/src/main/java/example/ReportApplication.java) | Reactor subscriptions are owned; external effects/threads are not forcibly stopped. |
| Awaited result, retry and fencing | [PostgreSQL worker checks](../../src/test/java/io/github/alex59/pgtasklease/spring/WorkerIntegrationTest.java) | Handler completion precedes ack; unknown DB outcome is not rollback; maxAttempts counts claims. |
| Existing AOP advice | [JDK/CGLIB proxy checks](../../src/test/java/io/github/alex59/pgtasklease/spring/HandlerMethodsTest.java) | Invokes proxy, not raw target; caller configures advice/transaction manager. |
| Schema and retention | [migration](../../examples/report-worker/schema.sql), [core API](https://github.com/dlorian59/pg-task-lease) | Apply migration explicitly; own terminal-row cleanup. Deletion removes UUID deduplication history. |

## Registration and AOP boundary

The worker discovers methods on already initialized singleton instances in the current context when its lifecycle starts. It combines these methods with explicit registration beans and rejects duplicate queue names before polling. There is no classpath scan, method execution at registration, or custom aspect. The invocation adapter is cold: it invokes the method when the worker subscribes, forwards its asynchronous error/completion and propagates cancellation.

JDK proxy methods must be exposed by an interface. Class-based proxies need an inheritable class. Final/static/nonpublic methods, wrong parameters, raw Mono and Mono<T> other than Void are rejected in reactive mode. Explicit blocking=true accepts only void(TaskLease), schedules proxy invocation on shared boundedElastic and propagates thrown exceptions as attempt failures. Cancellation requests interruption but does not force external code to stop. A null publisher is a handler failure. FactoryBean products, prototype/lazy beans that are not initialized at startup, and parent-context handlers are not automatically discovered. Scoped/dynamic proxy targets are rejected. Use explicit registrations for advanced lifetimes; do not simultaneously annotate the same queue.

The adapter uses the final Spring bean/proxy, so configured @Around or @Transactional interceptors stay in the invocation chain. This annotation does not install them. Reactive handler transactions need an appropriate reactive transaction manager; an imperative transaction around publisher construction does not cover later asynchronous effects. Queue operations remain separate auto-commit operations: the starter does not combine domain work, submission and acknowledgment into one transaction.

Calling the annotated method directly does not submit or claim a task. Calling another method through this bypasses Spring proxy advice, just as without this library. See Spring's [proxying rules](https://docs.spring.io/spring-framework/reference/core/aop/proxying.html). Annotation registration intentionally rejects final methods consistently, even where an interface proxy could advise a final implementation method.

## Complete worker configuration

The [guide table](en.md#operational-contract-and-settings) covers every Worker field: enabled, owner, concurrency, pollInterval, leaseTime, heartbeatInterval, recoveryInterval, recoveryBatchSize, retryDelay, operationTimeout and handlerTimeout. Durations use whole milliseconds and cannot exceed 365 days; only retryDelay allows zero. heartbeatInterval + operationTimeout must be less than leaseTime. Configuration metadata is included in the JAR for IDE support.

TaskHandlerRegistration accepts a nonblank queue up to 120 characters and a nonnull function. The function must return a cold Mono<Void>; a synchronous exception or null return fails that attempt. Worker constructor inputs are nonnull and explicit registration lists are copied/validated immediately. Auto-configured annotation lists are copied/validated at lifecycle start, after singleton proxies exist.

## Complete lifecycle API

- PgTaskWorker(queue, settings, handlers) creates a worker without starting it.
- start() is idempotent while running. It starts one bounded batch loop and an independent recovery loop; a new session is created after stop.
- stop() cancels owned subscriptions immediately; stop(callback) invokes the callback after issuing cancellation. Neither waits for external work or proves a remote rollback.
- isRunning() reports the local lifecycle flag, not database health or successful delivery.
- isAutoStartup() follows settings.enabled. An explicit start() is still an explicit manual start.
- getPhase() is Integer.MAX_VALUE - 100, so Spring stops the worker during lifecycle shutdown before bean destruction. The factory/pool remains application-owned.

Batches visit queues round-robin, await all active handlers and then delay before the next poll. A slow batch can delay other queues. Heartbeat/recovery continue separately. Cancellation and session identity prevent new work by an old session; an already-issued SQL statement or external request can still commit.

At-least-once attempts depend on persisted rows, active workers/recovery and database durability. Retry budget is finite. Fencing protects queue state, not external effects. Store compact payload references, keep handlers cancellation-cooperative and idempotent, and operate schema migration, pool limits, monitoring and retention explicitly.

[Guide](en.md) · [Verification](../VERIFICATION.md) · [README](../../README.md)

## Explicit blocking handlers

blocking defaults to false. Set blocking=true only for void(TaskLease). The starter schedules the final proxy invocation on shared boundedElastic; return means business work finished. Interruption is cooperative, not rollback. A Mono return with blocking=true or void without it fails registration. See [handler guide](en.md#register-business-work) and [full source](../../examples/report-worker/src/main/java/example/ReportJobs.java).
