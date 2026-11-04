package io.github.alex59.pgtasklease.spring;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Registers a public, nonstatic, nonfinal {@code Mono<Void> method(TaskLease)} on an eagerly created
 * singleton Spring bean. Invocation uses the final bean/proxy. This annotation is registration metadata,
 * not an aspect and not a trigger for direct/self-invocations.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PgTaskHandler {
    /** @return logical queue name, 1..120 nonblank characters */
    String value();
}
