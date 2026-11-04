package io.github.alex59.pgtasklease.spring;

import io.github.alex59.pgtasklease.PgTaskQueue;
import io.r2dbc.spi.ConnectionFactory;
import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** Uses the application's R2DBC connection factory; never creates or modifies database tables. */
@AutoConfiguration(afterName = "org.springframework.boot.r2dbc.autoconfigure.R2dbcAutoConfiguration")
@ConditionalOnClass({ConnectionFactory.class, PgTaskQueue.class})
@ConditionalOnProperty(prefix = "pg-task-lease", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(PgTaskLeaseProperties.class)
public class PgTaskLeaseAutoConfiguration {
    /** Creates the declarative auto-configuration; bean conditions control activation. */
    public PgTaskLeaseAutoConfiguration() { }
    @Bean
    @ConditionalOnMissingBean(PgTaskQueue.class)
    @ConditionalOnSingleCandidate(ConnectionFactory.class)
    PgTaskQueue pgTaskQueue(ConnectionFactory connectionFactory) { return new PgTaskQueue(connectionFactory); }

    @Bean
    @ConditionalOnBean(PgTaskQueue.class)
    @ConditionalOnMissingBean(PgTaskWorker.class)
    @ConditionalOnProperty(prefix = "pg-task-lease.worker", name = "enabled", havingValue = "true")
    PgTaskWorker pgTaskWorker(PgTaskQueue queue, PgTaskLeaseProperties properties, List<TaskHandlerRegistration> handlers) {
        return new PgTaskWorker(queue, properties.worker(), handlers);
    }
}
