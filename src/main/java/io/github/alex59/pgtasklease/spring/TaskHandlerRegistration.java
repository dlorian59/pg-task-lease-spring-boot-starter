package io.github.alex59.pgtasklease.spring;

import io.github.alex59.pgtasklease.TaskLease;
import java.util.Objects;
import java.util.function.Function;
import reactor.core.publisher.Mono;

/**
 * Explicit queue-to-handler binding. Return a cold, cancellation-cooperative publisher; effects must be idempotent.
 * @param queue logical queue name
 * @param handler cold reactive business handler
 */
public record TaskHandlerRegistration(String queue, Function<TaskLease, Mono<Void>> handler) {
    /**
     * Validates the queue name and handler before lifecycle startup.
     * @param queue logical queue name
     * @param handler cold reactive business handler
     */
    public TaskHandlerRegistration {
        Objects.requireNonNull(queue, "queue");
        if (queue.isBlank() || queue.length() > 120) throw new IllegalArgumentException("queue must contain 1..120 nonblank characters");
        Objects.requireNonNull(handler, "handler");
    }
}
