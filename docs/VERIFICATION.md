# Verification — 0.1.0

[README EN/RU](../README.md) · [Versions](index.md) · [English guide](0.1.0/en.md) · [Русское руководство](0.1.0/ru.md)

Local checks on 2026-11-04 used Spring Boot 4.1.2, pg-task-lease 0.3.0 and PostgreSQL 17.6. No GitHub or registry publication is claimed.

| Check | Result |
|---|---|
| Auto-configuration on Java 17 and 25 | Four tests passed: actual Boot R2DBC pool configuration, supplied factory, bean backoff, disabled worker/starter, invalid settings, missing/duplicate handlers. |
| Managed worker on Java 17 and 25 | Four real PostgreSQL scenarios passed: retry to DONE/DEAD, bounded concurrency and stop cancellation, heartbeat through long work and restart recovery, lost-heartbeat cancellation and handler timeout. |
| Maven consumer | The report application was copied into a separate consumer directory, built from the locally installed starter/core artifacts with Maven, and executed as a packaged Boot JAR on Java 17. It checks DONE and exact CSV output before exiting. |
| Core-only practical example | ReportExample compiled with Java 17 and produced REPORT_DONE; a second invocation with the same UUID produced ALREADY_SUBMITTED state=DONE. CSV contents were checked. |
| API/runtime baseline | Java compilation uses --release 17; source/target compatibility remain 17. testJavaVersion can select the launcher/compiler without raising the baseline. |
| Documentation | EN/RU Markdown guides, README SVG/details controls, links to executable source and paired EN/RU comments. No guide website. |

The packaged-consumer check found and fixed two integration issues before completion: the starter needed an explicit runtime dependency on r2dbc-pool, and the example's bounded pool needed initial-size=0 below max-size=8. The actual Boot pool auto-configuration test now protects dependency/configuration wiring.

## Reproduce

Install core 0.3.0 into Maven Local, then run from this checkout:

```bash
./gradlew build publishToMavenLocal
cd examples/report-worker
./run-demo.sh
```

To run the dedicated worker tests, provide a fresh disposable PostgreSQL database whose name ends in _test. The test creates its table and refuses an existing one; it does not drop data.

```bash
PG_TASK_TEST_URL='r2dbc:postgresql://test-user:test-password@localhost:55432/starter_test' \
  ./gradlew integrationTest
```

The normal build runs configuration tests without a database. integrationTest is a separate, explicit task and fails if its fixture is unavailable. CI is configured with an isolated PostgreSQL service and installs the core artifact locally first; remote CI was not run.

## Limits of this verification

The existing Docker image store was unavailable, so actual database checks used an isolated repository-local PostgreSQL process with fresh databases. Only that owned process was started and stopped. The Docker demo wrapper was checked for shell syntax, but its container startup path was not executed here. The same migration, packaged application and output checks were executed against the isolated native fixture.

Java 27's configured local toolchain link points to a missing binary. Java 21 and 27 were not exercised for this starter; no toolchains were downloaded or repaired. Java 17 and 25 were exercised. Java 25 emitted an upstream Netty native-access warning without failing the checks.

This is functional verification, not a throughput/HA/network-partition benchmark. Handler cancellation cannot undo external effects; stop does not await uncooperative work. A queue operation can commit despite a timeout or lost response. Idempotency, domain/outbox transactions, database durability and terminal-row retention remain application responsibilities.

## OSS-009 — annotation registration and documentation audit

Local verification on 2026-11-05 used Java 17.0.21, Spring Boot 4.1.2 and an isolated PostgreSQL 17.6 process. The new registration path was checked on Java 17; the earlier Java 25 results above predate this addition.

| Check | Result |
|---|---|
| Build, Javadoc and local artifact installation | Passed with --release 17; sources/Javadoc/POM/module artifacts installed into an isolated Maven Local directory. |
| Seven unit tests | Existing four configuration tests plus three registration scenarios passed. New checks exercise JDK and CGLIB advice, deferred invocation, awaited asynchronous completion, original synchronous/asynchronous errors, cancellation, invalid signatures/final methods, inaccessible proxy methods, dynamic targets and duplicate queues. |
| Four worker integration scenarios | Passed again on a fresh PostgreSQL database after the worker gained deferred registration discovery. Polling, heartbeat, recovery, retry, bounded concurrency and stop behavior remain covered. |
| Independent Maven consumer | A fresh copy of the report application was packaged against the installed artifacts. Its @PgTaskHandler component was discovered by actual Boot startup; REPORT_DONE, durable DONE state and exact CSV content were checked before successful exit. |
| Documentation coverage | EN/RU API-to-code-to-limits tables cover all public core operations/records and starter registration, properties, lifecycle, schema, retention and reliability. Existing guides link to these references. |

Annotation registration preserves configured Spring proxy advice; no new aspect or automatic transaction is installed. Tests verify the interceptor path, not a claim of domain-write/queue-ack transaction atomicity. Lazy/prototype beans, FactoryBean products and parent-context handlers are outside automatic discovery; explicit registration remains available. The native fixture was stopped after verification. No core SQL, production database, remote repository or registry was changed.

Проверка OSS-009: на Java 17 повторно прошли семь модульных тестов, четыре сценария с изолированным PostgreSQL и отдельное Maven-приложение с @PgTaskHandler. Подтверждены вызов через JDK/CGLIB-прокси, ожидание завершения, ошибки, отмена, валидация регистрации и точный результат CSV. Это не проверка атомарной транзакции между доменной записью и подтверждением очереди. Результаты Java 25 выше относятся к предыдущей реализации. Справочники API и ограничений доступны на обоих языках.

## OSS-013 — simpler requests and blocking handlers (2026-11-05)

Local checks used Java 17.0.21, Spring Boot 4.1.2, core 0.3.0 and PostgreSQL **16.15**. The cached Docker image could not start because containerd snapshot data was missing. PostgreSQL 16.15 was extracted from the official Ubuntu package into a temporary directory; no system package/service was installed or reconfigured. Three fresh databases isolated core tests, worker tests and the HTTP example. The owned server was stopped after verification. The existing PostgreSQL 17 CI target was not exercised remotely in this task.

- Core build/local artifacts: **29 tests passed**, including 18 PostgreSQL scenarios, six resource lifecycle checks, two compiled documentation checks and three validation checks. No skipped tests.
- Starter build/local artifacts: **8 unit tests passed**. New coverage checks blocking invocation laziness, existing class-proxy advice, execution away from the caller, cancellation interruption, original thrown errors and rejecting mismatched blocking/reactive signatures.
- Worker integration: **5 PostgreSQL scenarios passed**. The added annotated blocking handler runs beyond the original lease interval; heartbeat extends ownership, the task stays LEASED until return and then becomes DONE with one attempt. Existing retry limits, lost-heartbeat cancellation, timeout, concurrency, shutdown and restart recovery remain covered.
- The actual **Maven-packaged Boot JAR** and the independent **Gradle consumer** both passed HTTP submission → annotated void handler → exact CSV → DONE. Duplicate submission and a changed duplicate both return 202 without overwriting the original report. Each run observed attempts=1, then closed its context.
- Core-only ReportExample compiled as a Maven consumer, printed REPORT_DONE on first submission and ALREADY_SUBMITTED state=DONE on the second with the same UUID.
- Both JARs retain class-file major **61** (Java 17). Java 21/25/27 runtime checks were not repeated for these changes. No benchmark, exactly-once effect or production migration claim is made.
- README EN/RU contains dependency declarations, application configuration and source integration; shell/build commands live in CONTRIBUTING. Markdown links/anchors and git diff whitespace were checked. The Docker demo script was syntax-checked; its Docker startup could not be executed in the damaged local Docker environment.

Проверка OSS-013 выполнена на Java 17.0.21 и отдельной PostgreSQL **16.15**, извлечённой из официального Ubuntu-пакета без установки или изменения системных служб. Docker не запускал cached image из-за отсутствующего snapshot containerd. Core: **29 тестов**, стартер: **8 модульных + 5 PostgreSQL-сценариев**, без пропусков. Новый блокирующий обработчик прошёл проверку прокси, отложенного вызова, исключения, прерывания и продления аренды во время работы. Оба реальных потребителя (Maven JAR и Gradle) прошли HTTP → задача → CSV → DONE, дубликаты без перезаписи и одну попытку. Отдельный core-пример подтвердил повтор без новой задачи. Оба JAR сохраняют bytecode Java 17; JVM 21/25/27 и удалённая CI с PostgreSQL 17 в этом поручении повторно не запускались. Временная PostgreSQL остановлена.
