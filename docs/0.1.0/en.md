# 0.1.0 — run background reports with Spring Boot

[Versions](../index.md) · [English](en.md) · [Русский](ru.md) · [README](../../README.md)

## The problem and what the starter removes

A report can outlive an HTTP request or the process that accepted it. Store a task with a stable UUID, let a worker claim it, and produce an idempotent result. The core library supplies the PostgreSQL ownership protocol. This starter adds Spring configuration and the application lifecycle: polling, bounded batches, heartbeat, recovery, handler timeout and fenced acknowledgment.

Your application supplies a PostgreSQL connection configuration and explicit TaskHandlerRegistration beans. There is no annotation scanner or hidden business serializer. Adding the starter alone creates a PgTaskQueue bean when Boot has one unambiguous ConnectionFactory; background processing stays disabled until you opt in. Your own PgTaskQueue and PgTaskWorker beans take precedence.

## Install locally

Requires JDK 17+, Maven 3.8+, and the two source checkouts side by side. Spring Boot 4.1.2 is selected; the core dependency is pg-task-lease 0.3.0.

```bash
cd pg-task-lease
./gradlew publishToMavenLocal
cd ../pg-task-lease-spring-boot-starter
./gradlew build publishToMavenLocal
```

Coordinates for a Boot application:

```xml
<dependency>
  <groupId>io.github.59alex</groupId>
  <artifactId>pg-task-lease-spring-boot-starter</artifactId>
  <version>0.1.0</version>
</dependency>
<dependency>
  <groupId>org.postgresql</groupId>
  <artifactId>r2dbc-postgresql</artifactId>
  <scope>runtime</scope>
</dependency>
```

The Boot BOM manages the driver version. This is a local Maven installation; registry publication is not claimed.

## Configure the database and enable processing

Apply [schema.sql](../../examples/report-worker/schema.sql) once through your migration tool before enabling workers. It creates the core queue table and indexes. The starter does not execute DDL, drop tables or automatically initialize a database. The application role needs SELECT, INSERT and UPDATE on public.pg_task_lease. Queue names are logical selection scopes, not database access controls.

```properties
spring.r2dbc.url=${TASKS_R2DBC_URL}
spring.r2dbc.username=${TASKS_DB_USER}
spring.r2dbc.password=${TASKS_DB_PASSWORD}
spring.r2dbc.pool.max-size=8
spring.r2dbc.pool.initial-size=0
spring.sql.init.mode=never
pg-task-lease.worker.enabled=true
pg-task-lease.worker.concurrency=2
```

## Register business work

A plain @Bean associates a queue with a reactive handler:

```java
@Bean
TaskHandlerRegistration reportHandler(ReportService reports) {
    return new TaskHandlerRegistration("reports", lease ->
        reports.generateIdempotently(lease.id(), lease.payload()));
}
```

ReportService above is your application's service returning Mono<Void>. The complete runnable implementation is [ReportApplication.java](../../examples/report-worker/src/main/java/example/ReportApplication.java): it writes a deterministic CSV to a file keyed by task UUID, uses a temporary file plus atomic rename, and schedules file I/O on boundedElastic. This makes a retry replace the same report rather than create a second output. Replace the clearly labelled sample totals with a read of your own data.

Submit through the injected PgTaskQueue:

```java
Mono<Boolean> accepted = queue.submit(new TaskRequest(
    stableRequestId, "reports", "2026-Q4", 0, 3, Duration.ZERO));
```

Persist stableRequestId in your producer workflow and reuse it on submission retry. Subscribe through your request or application lifecycle. A generated new ID on every transport retry creates separate tasks. In the sample, ApplicationRunner submits once and waits at the command-line boundary for DONE plus the expected file contents.

## Run the actual example

After the local installations above, with Docker available:

```bash
cd examples/report-worker
./run-demo.sh
```

The script creates a fresh PostgreSQL instance on a dynamic loopback port, explicitly applies the migration, builds the Boot application using Maven Local, runs it, and stops its own container on exit. Demo credentials only belong to that disposable instance.

Expected final application output:

```text
REPORT_DONE task=<uuid> attempts=1 path=reports/<uuid>.csv
```

Expected file contents:

```csv
quarter,invoices,total
2026-Q4,3,450.00
```

The application verifies the durable DONE state and exact CSV contents before exiting. The sample is finite; in a server application Spring's context remains open and the same worker continues polling. If Docker is unavailable, prepare an isolated PostgreSQL database yourself, apply schema.sql, set TASKS_R2DBC_URL, TASKS_DB_USER and TASKS_DB_PASSWORD, then run `mvn package` and `java -jar target/report-worker-0.1.0.jar` from the example directory.

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

Durations must use whole milliseconds, be positive (retry-delay may be zero), and not exceed 365 days. heartbeat-interval + operation-timeout must be less than lease-time. Invalid values and duplicate queue registrations fail startup. With worker.enabled=true, at least one handler is required. Set pg-task-lease.enabled=false to disable all starter auto-configuration.

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
