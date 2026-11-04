package io.github.alex59.pgtasklease.spring;

import static org.assertj.core.api.Assertions.assertThat;
import io.github.alex59.pgtasklease.PgTaskQueue;
import io.r2dbc.spi.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import reactor.core.publisher.Mono;

class AutoConfigurationTest {
    private final ConnectionFactory factory = new ConnectionFactory() {
        public Mono<? extends Connection> create() { return Mono.error(new AssertionError("Configuration must not connect")); }
        public ConnectionFactoryMetadata getMetadata() { return () -> "PostgreSQL"; }
    };
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PgTaskLeaseAutoConfiguration.class));

    @Test void bootsActualR2dbcPoolWithoutOpeningAConnectionDuringConfiguration() {
        runner.withConfiguration(AutoConfigurations.of(org.springframework.boot.r2dbc.autoconfigure.R2dbcAutoConfiguration.class))
                .withPropertyValues("spring.r2dbc.url=r2dbc:postgresql://localhost:1/configuration_only",
                        "spring.r2dbc.username=configuration-test", "spring.r2dbc.pool.enabled=true", "spring.r2dbc.pool.max-size=2", "spring.r2dbc.pool.initial-size=0")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ConnectionFactory.class).hasSingleBean(PgTaskQueue.class);
                    assertThat(context.getBean(ConnectionFactory.class).getClass().getName()).isEqualTo("io.r2dbc.pool.ConnectionPool");
                });
    }

    @Test void queueUsesSuppliedFactoryAndWorkerRemainsOptIn() {
        runner.run(context -> assertThat(context).doesNotHaveBean(PgTaskQueue.class));
        runner.withBean(ConnectionFactory.class, () -> factory).run(context -> {
            assertThat(context).hasSingleBean(PgTaskQueue.class).doesNotHaveBean(PgTaskWorker.class);
            assertThat(context.getBean(PgTaskLeaseProperties.class).worker().concurrency()).isEqualTo(4);
        });
        runner.withBean(ConnectionFactory.class, () -> factory).withPropertyValues("pg-task-lease.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(PgTaskQueue.class));
    }

    @Test void explicitBeansWinAndInvalidConfigurationFailsBeforePolling() {
        var custom = new PgTaskQueue(factory);
        runner.withBean(ConnectionFactory.class, () -> factory).withBean(PgTaskQueue.class, () -> custom)
                .run(context -> assertThat(context.getBean(PgTaskQueue.class)).isSameAs(custom));
        runner.withBean(ConnectionFactory.class, () -> factory)
                .withPropertyValues("pg-task-lease.worker.concurrency=0")
                .run(context -> assertThat(context).hasFailed());
        runner.withBean(ConnectionFactory.class, () -> factory)
                .withPropertyValues("pg-task-lease.worker.heartbeat-interval=30s")
                .run(context -> assertThat(context).hasFailed());
        runner.withBean(ConnectionFactory.class, () -> factory)
                .withPropertyValues("pg-task-lease.worker.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test void customWorkerBacksOffAndDuplicateQueuesAreRejected() {
        var queue = new PgTaskQueue(factory);
        var handler = new TaskHandlerRegistration("reports", lease -> Mono.empty());
        runner.withBean(ConnectionFactory.class, () -> factory).run(context -> {
            var properties = context.getBean(PgTaskLeaseProperties.class).worker();
            var custom = new PgTaskWorker(queue, properties, java.util.List.of(handler)) {
                @Override public void start() { }
            };
            runner.withBean(ConnectionFactory.class, () -> factory)
                    .withBean(PgTaskWorker.class, () -> custom)
                    .withPropertyValues("pg-task-lease.worker.enabled=true")
                    .run(other -> assertThat(other.getBean(PgTaskWorker.class)).isSameAs(custom));
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> new PgTaskWorker(queue, properties, java.util.List.of(handler, handler)));
        });
    }
}
