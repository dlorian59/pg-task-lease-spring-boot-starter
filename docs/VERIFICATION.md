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
