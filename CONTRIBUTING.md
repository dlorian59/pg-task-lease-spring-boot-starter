# Development / Разработка

[README](README.md) · [English guide](docs/0.1.0/en.md) · [Русское руководство](docs/0.1.0/ru.md)

## English

JDK 17+ and a fresh PostgreSQL database are required for integration tests. Maven 3.8+ builds the HTTP example; Gradle is an alternative. Artifacts are not published to Central: prepare the core and starter locally from adjacent checkouts first.

```bash
cd ../pg-task-lease
./gradlew publishToMavenLocal
cd ../pg-task-lease-spring-boot-starter
./gradlew build publishToMavenLocal
cd examples/report-worker
./run-demo.sh
```

run-demo.sh uses a disposable PostgreSQL 17 container, applies schema.sql, builds with Maven and runs finite HTTP verification. It only stops its own container. It requires working Docker; it never uses a production database. Expected final output:

```text
REPORT_HTTP_DONE duplicate=202 changedDuplicate=202 attempts=1 path=reports/<uuid>.csv
```

The verification sends real HTTP requests, checks duplicate submission without replacement (even when the duplicate specifies another quarter), waits for DONE and validates the original CSV and one claim. HTTP 202 does not promise that a duplicate body matches the stored body. Keep UUIDs associated with immutable requests; add application-level conflict validation if required.

### Existing isolated database

Apply examples/report-worker/schema.sql to your fresh demo database through your normal SQL tooling. Set TASKS_R2DBC_URL, TASKS_DB_USER and TASKS_DB_PASSWORD. Then, in examples/report-worker:

```bash
mvn -q package
java -jar target/report-worker-0.1.0.jar --server.port=0 --reports.verify=true
```

Or use the root wrapper for the independent Gradle consumer:

```bash
../../gradlew run --args='--server.port=0 --reports.verify=true'
```

For ordinary server hosting omit reports.verify and choose your server port. ReportVerification is optional test scaffolding, not a production requirement. The demo has no authentication; expose it only on your local machine until your application authorization is implemented.

### Library verification

```bash
./gradlew test
PG_TASK_TEST_URL='r2dbc:postgresql://demo_user:demo_only@127.0.0.1:5432/worker_test' ./gradlew integrationTest
```

The URL must identify a fresh disposable database ending in _test. Integration tests create the queue table and refuse an existing one. Credentials above are placeholders for a test database only. Missing PostgreSQL fails integrationTest; the ordinary test task intentionally excludes postgres-tagged tests. See [verification](docs/VERIFICATION.md) for actual runs. Java 17 is compiled with release17; -PtestJavaVersion=21, 25 or 27 can select an installed newer JDK without changing the baseline.

## Русский

Нужны JDK 17+ и новая PostgreSQL для интеграционных тестов. HTTP-пример собирается Maven 3.8+ либо Gradle. Пакетов ещё нет в Central: сначала подготовьте core и starter из соседних рабочих копий.

```bash
cd ../pg-task-lease
./gradlew publishToMavenLocal
cd ../pg-task-lease-spring-boot-starter
./gradlew build publishToMavenLocal
cd examples/report-worker
./run-demo.sh
```

run-demo.sh создаёт отдельный контейнер PostgreSQL 17, применяет schema.sql, собирает Maven-приложение и запускает конечную HTTP-проверку. Останавливает только свой контейнер. Нужен исправный Docker; рабочая база не используется. Результат:

```text
REPORT_HTTP_DONE duplicate=202 changedDuplicate=202 attempts=1 path=reports/<uuid>.csv
```

Проверка отправляет реальные HTTP-запросы, проверяет дубликат без перезаписи (включая другой квартал), ждёт DONE, сверяет исходный CSV и один захват. HTTP 202 не обещает совпадения тела дубликата с сохранённым. Закрепляйте UUID за неизменяемым запросом; при необходимости добавьте бизнес-проверку конфликтов.

### Своя отдельная база

Примените examples/report-worker/schema.sql к новой демонстрационной базе обычным SQL-инструментом. Задайте TASKS_R2DBC_URL, TASKS_DB_USER и TASKS_DB_PASSWORD. В examples/report-worker:

```bash
mvn -q package
java -jar target/report-worker-0.1.0.jar --server.port=0 --reports.verify=true
```

Альтернатива — независимый Gradle-потребитель через корневой wrapper:

```bash
../../gradlew run --args='--server.port=0 --reports.verify=true'
```

Для обычного сервера уберите reports.verify и выберите порт. ReportVerification — необязательная проверочная обвязка. В примере нет аутентификации: используйте его только локально до добавления авторизации приложения.

### Проверка библиотеки

```bash
./gradlew test
PG_TASK_TEST_URL='r2dbc:postgresql://demo_user:demo_only@127.0.0.1:5432/worker_test' ./gradlew integrationTest
```

URL должен указывать на новую отдельную базу с суффиксом _test. Тесты создают таблицу очереди и не работают поверх существующей. Учётные данные выше — заполнители только для тестовой базы. Отсутствие PostgreSQL приводит к ошибке integrationTest; обычная задача test намеренно исключает postgres-тесты. Фактические прогоны — в [отчёте](docs/VERIFICATION.md). Компиляция release17 сохраняет Java 17; -PtestJavaVersion=21, 25 или 27 выбирает установленный новый JDK, не повышая минимум.
