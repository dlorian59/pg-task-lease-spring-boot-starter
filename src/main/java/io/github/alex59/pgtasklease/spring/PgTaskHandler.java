package io.github.alex59.pgtasklease.spring;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Registers a public, nonstatic, nonfinal {@code Mono<Void> method(TaskLease)} on an eagerly created
 * singleton Spring bean, or an explicitly blocking {@code void method(TaskLease)}. Invocation uses the final bean/proxy. This annotation is registration metadata,
 * not an aspect and not a trigger for direct/self-invocations.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PgTaskHandler {
    /** @return logical queue name, 1..120 nonblank characters */
    String value();

    /**
     * Runs a synchronous void handler on Reactor's shared boundedElastic scheduler.
     * Cancellation requests interruption but cannot undo effects or force noncooperative code to stop.
     * @return false for the default Mono&lt;Void&gt; contract; true for void handlers
     */
    boolean blocking() default false;
}
