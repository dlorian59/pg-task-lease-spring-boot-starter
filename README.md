# pg-task-lease-spring-boot-starter

<details name="readme-language">
<summary><picture><source media="(prefers-color-scheme: dark)" srcset="assets/lang-en-dark.svg"><img src="assets/lang-en-light.svg" width="42" height="26" alt="EN"></picture></summary>

**Version:** [0.1.0 guide](docs/0.1.0/en.md) · [Versions](docs/index.md) · Java 17+ · Spring Boot 4.1.2

Background reports, exports and notifications in your existing Spring application. Submit a task, add a business handler, and let the starter manage processing, retries and recovery after a restart.

## Get started in your existing project

The example below uses **Spring WebFlux**, PostgreSQL and your existing Boot 4.1.2 dependency management. Core 0.3.0 is included transitively. Artifacts are not published to Maven Central yet; use locally prepared artifacts described in [development](CONTRIBUTING.md). Java 17 remains the baseline; newer JVMs do not require a different API.

### 1. Dependencies

Maven (inside dependencies):
```xml
<dependency>
  <groupId>io.github.59alex</groupId>
  <artifactId>pg-task-lease-spring-boot-starter</artifactId>
  <version>0.1.0</version>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-webflux</artifactId>
</dependency>
<dependency>
  <groupId>org.postgresql</groupId>
  <artifactId>r2dbc-postgresql</artifactId>
  <scope>runtime</scope>
</dependency>
```

Gradle Kotlin DSL (with your existing Boot BOM/plugin):
```kotlin
dependencies {
    implementation("io.github.59alex:pg-task-lease-spring-boot-starter:0.1.0")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    runtimeOnly("org.postgresql:r2dbc-postgresql")
}
```


### 2. Configuration and migration

Copy [the migration](examples/report-worker/schema.sql) into your normal migration workflow and apply it before enabling the worker. Supply database credentials through your existing secret configuration. The starter does not create tables.

```yaml
spring:
  r2dbc:
    url: ${TASKS_R2DBC_URL}
    username: ${TASKS_DB_USER}
    password: ${TASKS_DB_PASSWORD}
    pool:
      max-size: 8
      initial-size: 0
  sql:
    init:
      # Migrate explicitly with the application's migration tool before enabling workers.
      # До включения worker примените миграцию явно инструментом вашего приложения.
      mode: never
pg-task-lease:
  worker:
    enabled: true
reports:
  directory: ${REPORTS_DIRECTORY:reports}
```


### 3. Add application files

Keep your existing @SpringBootApplication. Put these classes below its scan package, changing package names to match your project:

```text
src/main/java/example/
  ReportController.java  # HTTP
  ReportService.java     # submission / status
  ReportJobs.java        # CSV generation
src/main/resources/
  application.yml
  db/migration/Vxxx__pg_task_lease.sql
```


The ready controller delegates to a business service; WebFlux owns subscription. It returns HTTP 202 when submission is accepted, and a textual state on GET. A completed report is written as reports/&lt;task-id&gt;.csv. Apply your application's authentication and report ownership checks before exposing these endpoints; queue names and UUIDs do not grant access.

```java
package example;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

// WebFlux subscribes to the returned publisher; controllers must not call subscribe() or block().
// WebFlux подписывается на результат: контроллеру не нужны subscribe() или block().
@RestController
@RequestMapping("/reports")
public class ReportController {
    private final ReportService reports;
    public ReportController(ReportService reports) { this.reports = reports; }

    @PutMapping("/{id}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<Void> create(@PathVariable UUID id, @RequestParam String quarter) {
        return reports.request(id, quarter);
    }

    @GetMapping(value = "/{id}", produces = "text/plain")
    public Mono<String> status(@PathVariable UUID id) { return reports.status(id); }
}
```


The service keeps submission retries under the same UUID and preserves the first submission when the same ID is sent again:

```java
package example;

import io.github.alex59.pgtasklease.PgTaskQueue;
import io.github.alex59.pgtasklease.TaskRequest;
import io.github.alex59.pgtasklease.TaskSnapshot;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@Service
public class ReportService {
    public static final String QUEUE = "reports";
    private final PgTaskQueue queue;
    public ReportService(PgTaskQueue queue) { this.queue = queue; }

    public Mono<Void> request(UUID id, String quarter) {
        if (!quarter.matches("[0-9]{4}-Q[1-4]"))
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Expected YYYY-Q1..Q4"));
        // The caller retains this UUID across network retries; five attempts, normal priority, no delay.
        // Клиент сохраняет UUID при сетевых повторах: пять попыток, обычный приоритет, без задержки.
        return queue.submit(TaskRequest.ready(id, QUEUE, quarter)).flatMap(inserted -> inserted ? Mono.empty()
                : find(id).then());
    }

    public Mono<String> status(UUID id) { return find(id).map(task -> task.state().name()); }

    private Mono<TaskSnapshot> find(UUID id) {
        // In a multi-user application, enforce ownership here before returning status or accepting a duplicate.
        // В многопользовательском приложении здесь проверяйте владельца до выдачи статуса или приёма дубликата.
        return queue.find(id).filter(task -> QUEUE.equals(task.queue()))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }
}
```


Add the complete [ReportJobs.java](examples/report-worker/src/main/java/example/ReportJobs.java) handler. It validates the quarter, writes a deterministic CSV via temporary file and atomic rename, and uses this method declaration:

```java
@PgTaskHandler(value = ReportService.QUEUE, blocking = true)
public void generate(TaskLease lease) throws IOException
```

The annotation schedules this blocking handler safely away from reactive event loops. No scheduler, polling loop, heartbeat or acknowledgment code belongs in the application handler. Replace the sample totals with your own report query. For a reactive business service, keep the default public Mono&lt;Void&gt; method(TaskLease) contract instead.

## Capabilities in use

1. **Submit without tuning every field:** `queue.submit(TaskRequest.ready(id, "reports", "2026-Q4"))` → normal priority, immediate availability, up to five claims. The caller owns id. [Service and walkthrough](docs/0.1.0/en.md#submission-and-duplicates).
2. **Tune just the exceptional task:** `TaskRequest.ready(id, "reports", quarter).withPriority(10).withMaxAttempts(2)` → a new immutable request; the original is unchanged. [Task choices](docs/0.1.0/en.md#task-choices).
3. **Run ordinary file/JDBC business code:** `@PgTaskHandler(value = "reports", blocking = true)` → void handler executes on boundedElastic while the starter renews its lease. [Handler and cancellation](docs/0.1.0/en.md#register-business-work).
4. **Observe completion:** `reports.status(id)` → READY, LEASED, DONE or DEAD. Success is acknowledged after the handler returns. [Full HTTP example](examples/report-worker/src/main/java/example/ReportController.java), [walkthrough](docs/0.1.0/en.md#http-to-file-walkthrough).
5. **Recover and limit retries:** `pg-task-lease.worker.enabled: true` → managed heartbeat, bounded retries, expiry recovery and shutdown cancellation using documented defaults. [Settings and limits](docs/0.1.0/en.md#operational-contract-and-settings).

A report may run again after a crash. Use task id as an idempotency key; neither the queue nor a lease makes external effects exactly once. Domain work and queue acknowledgment are separate transactions.

[Full guide](docs/0.1.0/en.md) · [API reference](docs/0.1.0/reference.en.md) · [Complete source](examples/report-worker/src/main/java/example) · [Development and runnable verification](CONTRIBUTING.md)

</details>

<details name="readme-language" open>
<summary><picture><source media="(prefers-color-scheme: dark)" srcset="assets/lang-ru-dark.svg"><img src="assets/lang-ru-light.svg" width="42" height="26" alt="RU"></picture></summary>

**Версия:** [руководство 0.1.0](docs/0.1.0/ru.md) · [Версии](docs/index.md) · Java 17+ · Spring Boot 4.1.2

Фоновые отчёты, экспорты и уведомления в существующем Spring-приложении. Отправьте задачу и добавьте бизнес-обработчик: стартер управляет исполнением, повторами и восстановлением после перезапуска.

## Подключение к существующему проекту

Ниже пример для **Spring WebFlux**, PostgreSQL и существующего управления зависимостями Boot 4.1.2. Core 0.3.0 подключается транзитивно. Артефакты пока не опубликованы в Maven Central: локальная подготовка описана в [документе разработки](CONTRIBUTING.md). Минимальная версия Java — 17; на новых JVM API менять не нужно.

### 1. Зависимости

Maven (внутри dependencies):
```xml
<dependency>
  <groupId>io.github.59alex</groupId>
  <artifactId>pg-task-lease-spring-boot-starter</artifactId>
  <version>0.1.0</version>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-webflux</artifactId>
</dependency>
<dependency>
  <groupId>org.postgresql</groupId>
  <artifactId>r2dbc-postgresql</artifactId>
  <scope>runtime</scope>
</dependency>
```

Gradle Kotlin DSL (с вашим Boot BOM/plugin):
```kotlin
dependencies {
    implementation("io.github.59alex:pg-task-lease-spring-boot-starter:0.1.0")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    runtimeOnly("org.postgresql:r2dbc-postgresql")
}
```


### 2. Конфигурация и миграция

Добавьте [миграцию](examples/report-worker/schema.sql) в обычный процесс миграций приложения и примените до включения worker. Учётные данные передайте через существующую конфигурацию секретов. Стартер не создаёт таблицы.

```yaml
spring:
  r2dbc:
    url: ${TASKS_R2DBC_URL}
    username: ${TASKS_DB_USER}
    password: ${TASKS_DB_PASSWORD}
    pool:
      max-size: 8
      initial-size: 0
  sql:
    init:
      # Migrate explicitly with the application's migration tool before enabling workers.
      # До включения worker примените миграцию явно инструментом вашего приложения.
      mode: never
pg-task-lease:
  worker:
    enabled: true
reports:
  directory: ${REPORTS_DIRECTORY:reports}
```


### 3. Файлы приложения

Сохраните существующий @SpringBootApplication. Разместите классы под его пакетом сканирования, заменив package на пакет своего проекта:

```text
src/main/java/example/
  ReportController.java  # HTTP
  ReportService.java     # submission / status
  ReportJobs.java        # CSV generation
src/main/resources/
  application.yml
  db/migration/Vxxx__pg_task_lease.sql
```


Готовый контроллер вызывает бизнес-сервис; подпиской управляет WebFlux. При принятой отправке возвращается HTTP 202, GET выдаёт текстовое состояние. Готовый отчёт записывается в reports/&lt;task-id&gt;.csv. До открытия endpoints добавьте проверку аутентификации и владельца отчёта: имя очереди и UUID не предоставляют доступ.

```java
package example;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

// WebFlux subscribes to the returned publisher; controllers must not call subscribe() or block().
// WebFlux подписывается на результат: контроллеру не нужны subscribe() или block().
@RestController
@RequestMapping("/reports")
public class ReportController {
    private final ReportService reports;
    public ReportController(ReportService reports) { this.reports = reports; }

    @PutMapping("/{id}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<Void> create(@PathVariable UUID id, @RequestParam String quarter) {
        return reports.request(id, quarter);
    }

    @GetMapping(value = "/{id}", produces = "text/plain")
    public Mono<String> status(@PathVariable UUID id) { return reports.status(id); }
}
```


При повторной отправке с тем же UUID сервис сохраняет первую отправку при повторном использовании ID:

```java
package example;

import io.github.alex59.pgtasklease.PgTaskQueue;
import io.github.alex59.pgtasklease.TaskRequest;
import io.github.alex59.pgtasklease.TaskSnapshot;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@Service
public class ReportService {
    public static final String QUEUE = "reports";
    private final PgTaskQueue queue;
    public ReportService(PgTaskQueue queue) { this.queue = queue; }

    public Mono<Void> request(UUID id, String quarter) {
        if (!quarter.matches("[0-9]{4}-Q[1-4]"))
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Expected YYYY-Q1..Q4"));
        // The caller retains this UUID across network retries; five attempts, normal priority, no delay.
        // Клиент сохраняет UUID при сетевых повторах: пять попыток, обычный приоритет, без задержки.
        return queue.submit(TaskRequest.ready(id, QUEUE, quarter)).flatMap(inserted -> inserted ? Mono.empty()
                : find(id).then());
    }

    public Mono<String> status(UUID id) { return find(id).map(task -> task.state().name()); }

    private Mono<TaskSnapshot> find(UUID id) {
        // In a multi-user application, enforce ownership here before returning status or accepting a duplicate.
        // В многопользовательском приложении здесь проверяйте владельца до выдачи статуса или приёма дубликата.
        return queue.find(id).filter(task -> QUEUE.equals(task.queue()))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }
}
```


Добавьте полный обработчик [ReportJobs.java](examples/report-worker/src/main/java/example/ReportJobs.java). Он проверяет квартал, пишет детерминированный CSV через временный файл и атомарное переименование и объявляет метод так:

```java
@PgTaskHandler(value = ReportService.QUEUE, blocking = true)
public void generate(TaskLease lease) throws IOException
```

Аннотация выносит этот блокирующий обработчик с реактивных event loop. Планировщик, опрос, heartbeat и подтверждение не нужно писать в бизнес-методе. Замените демонстрационные итоги своей выборкой данных. Для реактивного бизнес-сервиса оставьте основной контракт public Mono&lt;Void&gt; method(TaskLease).

## Возможности на практике

1. **Отправить без настройки каждого поля:** `queue.submit(TaskRequest.ready(id, "reports", "2026-Q4"))` → обычный приоритет, сразу доступна, до пяти захватов. ID задаёт приложение. [Сервис и разбор](docs/0.1.0/ru.md#submission-and-duplicates).
2. **Настроить только особую задачу:** `TaskRequest.ready(id, "reports", quarter).withPriority(10).withMaxAttempts(2)` → новый неизменяемый запрос; исходный сохранён. [Параметры задачи](docs/0.1.0/ru.md#task-choices).
3. **Использовать обычный файловый/JDBC-код:** `@PgTaskHandler(value = "reports", blocking = true)` → void-метод выполняется на boundedElastic, стартер продлевает аренду. [Обработчик и отмена](docs/0.1.0/ru.md#register-business-work).
4. **Узнать состояние:** `reports.status(id)` → READY, LEASED, DONE либо DEAD. Успех подтверждается после возврата обработчика. [HTTP-пример](examples/report-worker/src/main/java/example/ReportController.java), [разбор](docs/0.1.0/ru.md#http-to-file-walkthrough).
5. **Восстановить работу и ограничить повторы:** `pg-task-lease.worker.enabled: true` → heartbeat, ограниченные повторы, возврат просроченных задач и отмена при остановке с описанными defaults. [Настройки и границы](docs/0.1.0/ru.md#operational-contract-and-settings).

После сбоя отчёт может выполниться повторно. Используйте ID задачи как ключ идемпотентности: очередь и аренда не дают exactly-once внешним эффектам. Бизнес-операция и подтверждение очереди остаются отдельными транзакциями.

[Полное руководство](docs/0.1.0/ru.md) · [Справочник API](docs/0.1.0/reference.ru.md) · [Полный исходный код](examples/report-worker/src/main/java/example) · [Разработка и исполняемая проверка](CONTRIBUTING.md)

</details>

[MIT](LICENSE). Gradle wrapper: [Apache-2.0](gradle/licenses/LICENSE), [notice](gradle/licenses/NOTICE).
