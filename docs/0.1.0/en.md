# 0.1.0 — Background reports in an existing Spring application

[Versions](../index.md) · [English](en.md) · [Русский](ru.md) · [README](../../README.md)

A report request should return promptly while the report survives a process restart. The starter lets your application submit a task and write one business method. Start with the [complete dependency, YAML, controller and service integration](../../README.md). This guide explains which decisions belong to the application and how to adapt the working example.

## Choose this integration

Use the starter when your Spring application already uses PostgreSQL and needs bounded background work: reports, exports or notifications. No separate broker is required. It targets Java 17+ and Boot 4.1.2; the HTTP example is WebFlux. A headless Boot application can omit the WebFlux dependency and controller. The queue still uses an R2DBC ConnectionFactory, even if the business handler uses JDBC. A JDBC DataSource alone is insufficient.

The starter does not move an arbitrary annotated call into a queue. Producers explicitly submit; the worker invokes the registered method later. The baseline works on Java 17, 21 and 25 LTS; optional Java 27 does not change the API or require preview features. Local artifact preparation and runnable verification are in [development](../../CONTRIBUTING.md).

<a id="http-to-file-walkthrough"></a>
## HTTP to file walkthrough

The [complete consumer](../../examples/report-worker) includes Maven and Gradle builds. Copy the application classes beneath your existing application scan package; do not add a second main class to your service.

1. Apply [schema.sql](../../examples/report-worker/schema.sql) once through your migration tool. Configure the R2DBC URL and credentials, with SELECT/INSERT/UPDATE access on public.pg_task_lease. Leave schema initialization disabled.
2. Add [application.yml](../../examples/report-worker/src/main/resources/application.yml). Only worker.enabled is needed beyond database configuration: defaults already supply polling, concurrency, heartbeat, retry and timeout budgets.
3. Add [ReportService](../../examples/report-worker/src/main/java/example/ReportService.java) and [ReportController](../../examples/report-worker/src/main/java/example/ReportController.java). PUT /reports/{uuid}?quarter=2026-Q4 returns 202 after the database accepts the task. Repeating that UUID and quarter returns 202 without another row; changing the quarter also returns 202 without changing the original task. Invalid quarters return 400.
4. Add [ReportJobs](../../examples/report-worker/src/main/java/example/ReportJobs.java). The handler writes reports/{uuid}.csv. GET /reports/{uuid} returns a state; DONE means the handler returned and queue acknowledgment succeeded. Missing tasks return 404.
5. Adapt sample totals, destination and access checks. The sample uses a local directory and fixed data to make the result verifiable. Multi-host workers need an appropriate shared or external output destination. A mutable report dataset needs a persisted snapshot/version if retries must reproduce identical bytes.

[ReportVerification](../../examples/report-worker/src/main/java/example/ReportVerification.java) is a separate, opt-in finite check. It performs actual HTTP submission, duplicate requests, including a changed duplicate, waits for DONE, then checks exact CSV content and one attempt. Normal hosting stays running. This check is not needed in your application's source.

The file rename is atomic on a supported local filesystem; it is not a durability promise after storage failure. File success followed by a lost acknowledgment may cause a repeat. Keying output by task UUID avoids a second name; the application must also make contents and downstream effects safe to repeat.

<a id="submission-and-duplicates"></a>
## Submission and duplicates

```java
queue.submit(TaskRequest.ready(id, "reports", "2026-Q4"));
```

TaskRequest.ready keeps the caller's UUID, priority 0, maxAttempts 5 and delay zero. submit returns true for a new row or false for an existing UUID. The original row is not overwritten and the UUID is global across queues. The [service](../../examples/report-worker/src/main/java/example/ReportService.java) checks that a duplicate belongs to reports. Core preserves the first content and does not compare business content; the caller must keep each UUID bound to one immutable request.

Retain the UUID before the first attempt. If a response is lost, retry with the same UUID and content. A new random UUID for each network attempt creates new tasks. Concurrent retention can remove deduplication history; keep terminal rows for at least your required retry window. The sample's duplicate lookup can return 404 if a task was removed between submission and lookup.

Returning a Mono from the controller lets WebFlux subscribe. Calling submit without subscribing does nothing. Do not hide a fire-and-forget subscribe in your service: it disconnects acceptance from the HTTP result. A request cancellation can occur after database commit, so an HTTP failure is not proof the task was not stored.

<a id="task-choices"></a>
## Task choices

```java
var request = TaskRequest.ready(id, "reports", quarter)
        .withPriority(10)
        .withMaxAttempts(2)
        .withDelay(Duration.ofMinutes(5));
```

Each override returns a new validated request. The original stays unchanged. Priority is a numeric preference at claim time, not a total execution ordering across workers. Higher numbers are preferred; sustained high-priority traffic can starve other work. Delay uses PostgreSQL time. maxAttempts limits claims, including attempts interrupted by a crash; it is not a count of successful handler invocations.

Use a delay for scheduled exports and a smaller attempt budget for nontransient failures. Error classification and a new administrative retry after DEAD remain application policy. Five attempts are a starting default, not an availability guarantee. Request delay is initial availability; worker.retry-delay applies after failure or recovery.

<a id="register-business-work"></a>
## Register business work

For ordinary blocking file/JDBC code, use the full [CSV handler](../../examples/report-worker/src/main/java/example/ReportJobs.java):

```java
@PgTaskHandler(value = "reports", blocking = true)
public void generate(TaskLease lease) throws IOException
```

This is a method declaration excerpt; its complete body and imports are in the linked source. The starter wraps invocation in a cold publisher and schedules it on shared boundedElastic. Registration does not run the method. The worker awaits normal return before acknowledgment, handles a thrown exception as an attempt failure, and renews the lease independently. Shared scheduler saturation can consume handler timeout while waiting; bound business concurrency and size dependent resources appropriately. The starter does not own or dispose Reactor's shared scheduler.

Cancellation requests interruption; Java code, filesystem calls or remote clients may ignore it. Such work may finish after shutdown or lease loss. Never treat interruption as rollback, and configure downstream timeouts/idempotency. The worker's concurrency bounds subscribed attempts, not indefinitely continuing noncooperative external work.

For reactive business work, keep the original default contract:

```java
@PgTaskHandler("reports")
public Mono<Void> generate(TaskLease lease) {
    return reports.generateIdempotently(lease.id(), lease.payload());
}
```

Here reports is your application's reactive collaborator. Return completion only after the effect finishes; do not perform blocking work while constructing a publisher. The starter does not change the scheduler of a reactive handler. An explicit TaskHandlerRegistration("reports", lease -> ...) bean remains available for programmatic registration; choose it OR an annotation per queue.

Methods must be public, nonstatic and nonfinal on eager singleton beans. blocking=true requires void; default mode requires Mono<Void>. Both take one TaskLease. Discovery occurs when the worker starts, after proxies exist. Proxy invocation preserves configured Spring advice. A JDBC @Transactional void handler can finish its business transaction before queue acknowledgment; a reactive handler needs an appropriate reactive transaction manager. Neither mode combines domain changes and queue operations into one transaction. Direct/self-invocation does not acquire a lease. [Proxy limits and registration alternatives](reference.en.md).

## Operational contract and settings

All worker settings have the prefix pg-task-lease.worker.

| Setting | Default | Meaning |
|---|---|---|
| enabled | false | Explicitly starts managed processing. |
| concurrency | 4 | Global active batch limit across registered queues; range 1–1000. |
| owner | spring-worker | Diagnostic name; token and generation enforce ownership. |
| poll-interval | 1s | Delay after the previous batch finishes, including failures. |
| lease-time | 30s | Ownership lifetime using PostgreSQL time. |
| heartbeat-interval | 10s | Delay between completed heartbeat calls for each active task. |
| recovery-interval | 10s | Delay after a recovery pass across registered queues. |
| recovery-batch-size | 100 | Maximum expired rows recovered per queue/pass; range 1–1000. |
| retry-delay | 5s | Delay after handler failure or expired-lease recovery. |
| operation-timeout | 5s | Bounds each queue operation from subscription, including pool wait. |
| handler-timeout | 5m | Maximum duration of one handler attempt. |

Durations must use whole milliseconds, be positive (retry-delay may be zero), and not exceed 365 days. heartbeat-interval + operation-timeout must be less than lease-time. Invalid values and duplicate queue registrations fail startup. With worker.enabled=true, at least one annotated method or explicit registration is required. Set pg-task-lease.enabled=false to disable all starter auto-configuration.

One batch runs at a time; registrations are visited round-robin. The worker waits for that batch to finish before its next poll. Slow tasks can delay other queues, though independent heartbeat and recovery continue. There is no unbounded tick backlog or local queue of leases awaiting a free processing slot. Size the connection pool for concurrent work, heartbeats and recovery.

Handler success is followed by complete. Handler failure/timeout invokes fail with a diagnostic containing only the exception class; claim attempts are bounded by each task's maxAttempts. A failed/false heartbeat cancels the handler publisher. A failed/false acknowledgment does not turn a previously successful handler into a new business failure; an unknown database outcome is left for inspection/recovery.

Stopping the Spring context cancels worker-owned subscriptions and checks session identity before another acknowledgment starts. An already-issued database operation can still commit. It does not wait for uncooperative external work to finish, undo an effect, or kill blocking Java code. Unacknowledged leases remain for expiry recovery. Handlers must cooperate with reactive cancellation and keep blocking work off event loops/timers.

At-least-once attempts require retained task rows, live workers/recovery and PostgreSQL durability. Task-state fences do not protect external APIs automatically. Use an idempotency key or transactional deduplication at the effect destination. Polling is not a replacement for an outbox when submission must be atomic with domain writes. Retention of DONE/DEAD rows remains application policy.

## Troubleshooting and verification

- Missing artifact: install core 0.3.0 and starter 0.1.0 into the same Maven Local repository used by the consumer.
- Missing table: apply the migration to the database selected by spring.r2dbc.url; the starter does not create it.
- No processing: check worker.enabled, handler queue name, READY/delay state and claim budget.
- ConnectionFactory ambiguity: configure one candidate or mark the intended bean @Primary.
- Repeated task/claim warnings: check database availability, pool/operation budgets and handler duration. Payloads and exception messages are deliberately omitted from worker logs.
- DEAD task: inspect attempts and lastError, fix the business cause, then submit a deliberately new task under your own retry policy.

[Verification](../VERIFICATION.md) · [Version index](../index.md) · [README](../../README.md)

## What to retain

A stable task ID prevents duplicate submissions while its row exists. A current lease permits queue transitions; it does not authorize or deduplicate another system. The worker owns polling and cancellation, while the application owns business data, migrations, authorization and output idempotency. Defaults remove setup code; tune them only against actual handler and dependency budgets.

[Reactor scheduling](https://projectreactor.io/docs/core/release/reference/coreFeatures/schedulers.html) explains boundedElastic and subscription scheduling. [Boot R2DBC configuration](https://docs.spring.io/spring-boot/reference/data/sql.html#data.sql.r2dbc) defines the ConnectionFactory configuration reused here.
