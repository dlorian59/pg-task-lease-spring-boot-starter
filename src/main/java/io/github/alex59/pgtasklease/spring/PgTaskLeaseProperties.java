package io.github.alex59.pgtasklease.spring;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Validated typed configuration. The worker is disabled unless explicitly enabled.
 * @param enabled whether starter auto-configuration is enabled
 * @param worker validated background processing settings
 */
@ConfigurationProperties("pg-task-lease")
public record PgTaskLeaseProperties(@DefaultValue("true") boolean enabled, @DefaultValue Worker worker) {
    /**
     * Rejects missing worker configuration.
     * @param enabled whether starter auto-configuration is enabled
     * @param worker validated background processing settings
     */
    public PgTaskLeaseProperties { Objects.requireNonNull(worker, "worker"); }

    /**
     * Polling, processing and ownership budgets; all durations are validated at binding time.
     * @param enabled whether Spring starts the worker automatically
     * @param owner diagnostic worker name
     * @param concurrency maximum active batch size
     * @param pollInterval delay between completed batches
     * @param leaseTime database lease lifetime
     * @param heartbeatInterval delay between completed renewals
     * @param recoveryInterval delay between recovery passes
     * @param recoveryBatchSize maximum expired rows per queue/pass
     * @param retryDelay delay after handler failure or expiry recovery
     * @param operationTimeout queue operation budget
     * @param handlerTimeout business handler budget
     */
    public record Worker(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("spring-worker") String owner,
            @DefaultValue("4") int concurrency,
            @DefaultValue("1s") Duration pollInterval,
            @DefaultValue("30s") Duration leaseTime,
            @DefaultValue("10s") Duration heartbeatInterval,
            @DefaultValue("10s") Duration recoveryInterval,
            @DefaultValue("100") int recoveryBatchSize,
            @DefaultValue("5s") Duration retryDelay,
            @DefaultValue("5s") Duration operationTimeout,
            @DefaultValue("5m") Duration handlerTimeout) {
        /**
         * Validates bounds and the heartbeat safety margin before any worker starts.
         * @param enabled whether Spring starts the worker automatically
         * @param owner diagnostic worker name
         * @param concurrency maximum active batch size
         * @param pollInterval delay between completed batches
         * @param leaseTime database lease lifetime
         * @param heartbeatInterval delay between completed renewals
         * @param recoveryInterval delay between recovery passes
         * @param recoveryBatchSize maximum expired rows per queue/pass
         * @param retryDelay delay after handler failure or expiry recovery
         * @param operationTimeout queue operation budget
         * @param handlerTimeout business handler budget
         */
        public Worker {
            if (owner == null || owner.isBlank() || owner.length() > 200) throw new IllegalArgumentException("owner must contain 1..200 nonblank characters");
            if (concurrency < 1 || concurrency > 1000) throw new IllegalArgumentException("concurrency must be 1..1000");
            if (recoveryBatchSize < 1 || recoveryBatchSize > 1000) throw new IllegalArgumentException("recoveryBatchSize must be 1..1000");
            duration(pollInterval, "pollInterval", false);
            duration(leaseTime, "leaseTime", false);
            duration(heartbeatInterval, "heartbeatInterval", false);
            duration(recoveryInterval, "recoveryInterval", false);
            duration(retryDelay, "retryDelay", true);
            duration(operationTimeout, "operationTimeout", false);
            duration(handlerTimeout, "handlerTimeout", false);
            if (heartbeatInterval.plus(operationTimeout).compareTo(leaseTime) >= 0)
                throw new IllegalArgumentException("heartbeatInterval + operationTimeout must be less than leaseTime");
        }
        private static void duration(Duration value, String name, boolean zeroAllowed) {
            Objects.requireNonNull(value, name);
            if (value.isNegative() || (!zeroAllowed && value.isZero()) || value.getNano() % 1_000_000 != 0
                    || value.compareTo(Duration.ofDays(365)) > 0)
                throw new IllegalArgumentException(name + " must be whole milliseconds, "
                        + (zeroAllowed ? "nonnegative" : "positive") + ", at most 365 days");
        }
    }
}
