# 0.1.0 — API и карта возможностей

[Версии](../index.md) · [Руководство](ru.md) · [English](reference.en.md) · [Русский](reference.ru.md) · [README](../../README.md)

## Возможность → код → ограничения

| Возможность / публичный API | Исполняемый код или реализация | Ограничения |
|---|---|---|
| Внедрение очереди / PgTaskLeaseAutoConfiguration | [ReportApplication](../../examples/report-worker/src/main/java/example/ReportApplication.java), [конфигурация](../../src/main/java/io/github/alex59/pgtasklease/spring/PgTaskLeaseAutoConfiguration.java) | Нужен однозначный ConnectionFactory; пользовательские queue/worker имеют приоритет; DDL нет. |
| Регистрация аннотацией / @PgTaskHandler(value) | [ReportJobs.generate](../../examples/report-worker/src/main/java/example/ReportJobs.java) | Имя очереди — литерал; public nonstatic nonfinal Mono<Void>(TaskLease); один обработчик на очередь. |
| Ручная регистрация / TaskHandlerRegistration(queue, handler) | [Альтернатива](ru.md#регистрация-бизнес-обработчика), [record](../../src/main/java/io/github/alex59/pgtasklease/spring/TaskHandlerRegistration.java) | Конструктор проверяет имя и функцию; выбирайте её ИЛИ аннотацию для каждой очереди. |
| Настройки / PgTaskLeaseProperties(enabled, worker) | [Полная таблица](ru.md#контракт-и-настройки), [properties](../../src/main/java/io/github/alex59/pgtasklease/spring/PgTaskLeaseProperties.java) | Глобальный enabled по умолчанию true, worker.enabled — false. Проверяются все длительности и лимиты. |
| Lifecycle / PgTaskWorker | [Worker](../../src/main/java/io/github/alex59/pgtasklease/spring/PgTaskWorker.java), [main приложения](../../examples/report-worker/src/main/java/example/ReportApplication.java) | Worker владеет подписками Reactor; внешние эффекты и потоки принудительно не останавливает. |
| Ожидание результата, повторы и fencing | [Проверки PostgreSQL](../../src/test/java/io/github/alex59/pgtasklease/spring/WorkerIntegrationTest.java) | Обработчик завершается до ack; неизвестный исход SQL не означает rollback; maxAttempts считает захваты. |
| Существующий AOP | [Проверки JDK/CGLIB](../../src/test/java/io/github/alex59/pgtasklease/spring/HandlerMethodsTest.java) | Вызывается proxy, а не raw target; аспекты и transaction manager настраивает приложение. |
| Схема и хранение | [Миграция](../../examples/report-worker/schema.sql), [Core API](https://github.com/59Alex/pg-task-lease) | Миграция явная; очистка терминальных задач принадлежит приложению. Удаление сбрасывает историю дедупликации UUID. |

## Граница регистрации и AOP

При lifecycle-старте worker находит методы уже созданных singleton instances текущего контекста. Он объединяет их с явными registration beans и отклоняет повтор имени очереди до опроса базы. Сканирования classpath, выполнения методов при регистрации и собственного аспекта нет. Адаптер холодный: метод вызывается при подписке работника, асинхронные завершение/ошибка и отмена передаются дальше.

JDK-прокси должен предоставлять метод через интерфейс. Class proxy требует допускающий наследование класс. Final/static/nonpublic методы, неверные параметры, raw Mono и Mono<T> с типом не Void отклоняются. Возврат null считается ошибкой обработчика. Продукты FactoryBean, prototype/lazy beans, не созданные при старте, и обработчики родительского контекста автоматически не обнаруживаются. Scoped/dynamic proxy targets отклоняются. Для особого lifetime используйте явную регистрацию, не аннотируя одновременно ту же очередь.

Адаптер вызывает конечный bean/proxy Spring, поэтому настроенные @Around и @Transactional остаются в цепочке. Эта аннотация их не устанавливает. Реактивному обработчику нужен подходящий reactive transaction manager; императивная транзакция вокруг создания publisher не покрывает дальнейшие асинхронные эффекты. Операции очереди остаются отдельными auto-commit операциями: стартер не объединяет доменные изменения, отправку и ack в одну транзакцию.

Прямой вызов аннотированного метода не отправляет и не захватывает задачу. Вызов через this обходит AOP-прокси Spring, как и без этой библиотеки. См. [правила проксирования Spring](https://docs.spring.io/spring-framework/reference/core/aop/proxying.html). Регистрация намеренно запрещает final методы единообразно, даже когда interface proxy мог бы обернуть final-реализацию.

## Все настройки worker

[Таблица руководства](ru.md#контракт-и-настройки) покрывает каждое поле Worker: enabled, owner, concurrency, pollInterval, leaseTime, heartbeatInterval, recoveryInterval, recoveryBatchSize, retryDelay, operationTimeout, handlerTimeout. Длительности задаются целыми миллисекундами до 365 дней; ноль допустим только для retryDelay. Сумма heartbeatInterval + operationTimeout должна быть меньше leaseTime. JAR включает configuration metadata для IDE.

TaskHandlerRegistration принимает непустую очередь до 120 символов и ненулевую функцию. Функция должна возвращать холодный Mono<Void>; синхронное исключение или null завершает попытку ошибкой. Конструктор worker требует ненулевые входы, копирует и сразу проверяет явный список. Список автоконфигурации с аннотациями копируется и проверяется при lifecycle-старте, после создания singleton proxies.

## Полный lifecycle API

- PgTaskWorker(queue, settings, handlers) создаёт worker без запуска.
- start() идемпотентен во время работы. Запускает ограниченный цикл партий и независимое восстановление; после stop создаёт новую сессию.
- stop() сразу отменяет свои подписки; stop(callback) вызывает callback после запроса отмены. Ожидания внешней работы и подтверждения remote rollback нет.
- isRunning() показывает локальный lifecycle-флаг, а не здоровье базы или успешность доставки.
- isAutoStartup() соответствует settings.enabled. Явный start() остаётся явным ручным запуском.
- getPhase() равен Integer.MAX_VALUE - 100: Spring останавливает worker в lifecycle shutdown до уничтожения beans. Владельцем factory/pool остаётся приложение.

Очереди обходятся по кругу: worker ждёт все задачи партии и делает паузу до следующего опроса. Медленная партия задерживает другие очереди. Heartbeat и восстановление идут отдельно. Отмена и проверка сессии препятствуют новой работе старой сессии; уже отправленный SQL или внешний запрос всё ещё может зафиксироваться.

At-least-once попытки зависят от сохранённых строк, работников/восстановления и долговечности базы. Бюджет повторов конечен. Fencing защищает состояние очереди, а не внешние эффекты. Храните компактные ссылки в payload, делайте обработчики идемпотентными и поддерживающими отмену; явно управляйте миграциями, лимитами пула, наблюдением и хранением.

[Руководство](ru.md) · [Проверки](../VERIFICATION.md) · [README](../../README.md)
