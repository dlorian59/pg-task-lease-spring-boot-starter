# pg-task-lease-spring-boot-starter

<details name="readme-language">
<summary><picture><source media="(prefers-color-scheme: dark)" srcset="assets/lang-en-dark.svg"><img src="assets/lang-en-light.svg" width="42" height="26" alt="EN"></picture></summary>

**Version:** [0.1.0 guide](docs/0.1.0/en.md) · [Versions](docs/index.md) · Java 17+ · Spring Boot 4.1.2

Run PostgreSQL-backed background jobs with an explicit business handler and Spring configuration. The starter wires pg-task-lease 0.3.0 to Boot's ConnectionFactory and optionally manages the worker lifecycle.

- PgTaskQueue bean with user-bean backoff; no schema changes.
- Register methods with @PgTaskHandler or explicit TaskHandlerRegistration beans; existing Spring AOP proxies are preserved.
- Opt-in worker with bounded concurrency, polling, heartbeat, expiry recovery and timeouts.
- Fenced completion after handler success; bounded task retries after handler failure.
- Cancellation on context shutdown, with ownership and duplicate-effect limits documented.

## Quick start

From adjacent core and starter checkouts, using JDK 17+, Maven 3.8+ and Docker for the isolated demo:

```bash
cd pg-task-lease
./gradlew publishToMavenLocal
cd ../pg-task-lease-spring-boot-starter
./gradlew build publishToMavenLocal
cd examples/report-worker
./run-demo.sh
```

The [report example](examples/report-worker/src/main/java/example/ReportApplication.java) submits a quarterly CSV report, processes it with an annotated Spring handler, verifies PostgreSQL DONE plus the file contents, then closes the application. Expected: `REPORT_DONE task=<uuid> attempts=1 path=reports/<uuid>.csv`. Source comments are paired EN/RU.

[Configure and adapt the example →](docs/0.1.0/en.md) · [API and capability map](docs/0.1.0/reference.en.md)

Library coordinates: `io.github.59alex:pg-task-lease-spring-boot-starter:0.1.0`. Local source/Maven Local only; registry and GitHub publication are not completed. [Verification](docs/VERIFICATION.md).

The worker is disabled by default. Apply the core SQL migration explicitly before enabling it. A lease fences task state, not external side effects: handlers still need idempotency. No production migration or process configuration is changed by adding this dependency.

</details>

<details name="readme-language" open>
<summary><picture><source media="(prefers-color-scheme: dark)" srcset="assets/lang-ru-dark.svg"><img src="assets/lang-ru-light.svg" width="42" height="26" alt="RU"></picture></summary>

**Версия:** [руководство 0.1.0](docs/0.1.0/ru.md) · [Версии](docs/index.md) · Java 17+ · Spring Boot 4.1.2

Фоновые задачи в PostgreSQL с явным бизнес-обработчиком и конфигурацией Spring. Стартер подключает pg-task-lease 0.3.0 к ConnectionFactory из Boot и при включении управляет жизненным циклом работника.

- Bean PgTaskQueue с приоритетом пользовательского bean; схема не изменяется.
- Регистрация методов через @PgTaskHandler или beans TaskHandlerRegistration; существующие AOP-прокси Spring сохраняются.
- Worker включается отдельно: ограниченные партии, опрос, heartbeat, восстановление и тайм-ауты.
- Подтверждение с проверкой аренды после успеха; ограниченные повторы после ошибки обработчика.
- Отмена при закрытии контекста с описанными границами владения и повторных эффектов.

## Быстрый запуск

Из соседних рабочих копий core и starter, с JDK 17+, Maven 3.8+ и Docker для отдельной демонстрационной базы:

```bash
cd pg-task-lease
./gradlew publishToMavenLocal
cd ../pg-task-lease-spring-boot-starter
./gradlew build publishToMavenLocal
cd examples/report-worker
./run-demo.sh
```

[Пример отчёта](examples/report-worker/src/main/java/example/ReportApplication.java) отправляет квартальную CSV-задачу, обрабатывает её через аннотированный Spring-метод, проверяет DONE в PostgreSQL и содержимое файла, затем закрывает приложение. Результат: `REPORT_DONE task=<uuid> attempts=1 path=reports/<uuid>.csv`. Комментарии в исходниках идут парами EN/RU.

[Настройка и применение примера →](docs/0.1.0/ru.md) · [API и карта возможностей](docs/0.1.0/reference.ru.md)

Координаты: `io.github.59alex:pg-task-lease-spring-boot-starter:0.1.0`. Пока доступны локальные исходники и установка в Maven Local; публикация в реестре и GitHub не завершена. [Проверки](docs/VERIFICATION.md).

По умолчанию worker отключён. До включения явно примените SQL-миграцию core. Проверка аренды защищает состояние задачи, но не внешние эффекты: обработчику нужна идемпотентность. Добавление зависимости не меняет рабочие миграции или конфигурацию процессов.

</details>

[MIT](LICENSE), copyright 2026 Aleksandr Kashevarov. Gradle wrapper retains its [Apache-2.0 license](gradle/licenses/LICENSE) and [notice](gradle/licenses/NOTICE).
