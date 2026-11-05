package io.github.alex59.pgtasklease.spring;

import io.github.alex59.pgtasklease.TaskLease;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotatedElementUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Inspects existing singleton instances only; never unwraps a target for invocation. */
final class TaskHandlerMethods {
    private TaskHandlerMethods() { }

    static List<TaskHandlerRegistration> discover(ConfigurableListableBeanFactory beans,
                                                   List<TaskHandlerRegistration> explicit) {
        var registrations = new ArrayList<>(explicit);
        for (String name : beans.getSingletonNames()) {
            Object bean = beans.getSingleton(name);
            if (bean == null) continue;
            Class<?> target = AopProxyUtils.ultimateTargetClass(bean);
            var methods = MethodIntrospector.selectMethods(target,
                    (MethodIntrospector.MetadataLookup<PgTaskHandler>) method ->
                            AnnotatedElementUtils.findMergedAnnotation(method, PgTaskHandler.class));
            if (!methods.isEmpty() && bean instanceof Advised advised && !advised.getTargetSource().isStatic())
                throw new IllegalArgumentException("@PgTaskHandler requires a singleton proxy target: " + name);
            methods.forEach((method, annotation) -> {
                validate(method, target, annotation.blocking());
                Method invocable;
                try { invocable = AopUtils.selectInvocableMethod(method, bean.getClass()); }
                catch (IllegalStateException error) {
                    throw new IllegalArgumentException("@PgTaskHandler method must be exposed by the Spring proxy: " + method, error);
                }
                if (!invocable.canAccess(bean) && !invocable.trySetAccessible())
                    throw new IllegalArgumentException("@PgTaskHandler method is not accessible: " + method);
                registrations.add(new TaskHandlerRegistration(annotation.value(), lease -> invoke(bean, invocable, lease, annotation.blocking())));
            });
        }
        return List.copyOf(registrations);
    }

    private static void validate(Method method, Class<?> target, boolean blocking) {
        int modifiers = method.getModifiers();
        if (!Modifier.isPublic(modifiers) || Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers)
                || method.getParameterCount() != 1 || method.getParameterTypes()[0] != TaskLease.class
                || (blocking ? method.getReturnType() != void.class
                    : method.getReturnType() != Mono.class
                      || ResolvableType.forMethodReturnType(method, target).getGeneric(0).resolve() != Void.class)) {
            throw new IllegalArgumentException("@PgTaskHandler requires public nonstatic nonfinal " + (blocking ? "void" : "Mono<Void>") + " method(TaskLease): " + method);
        }
    }

    private static Mono<Void> invoke(Object bean, Method method, TaskLease lease, boolean blocking) {
        Mono<Void> invocation = Mono.defer(() -> {
            try {
                Object result = method.invoke(bean, lease);
                if (blocking) return Mono.empty();
                return result instanceof Mono<?> mono ? mono.then()
                        : Mono.error(new IllegalStateException("@PgTaskHandler returned null instead of Mono<Void>"));
            } catch (InvocationTargetException error) { return Mono.error(error.getCause()); }
            catch (ReflectiveOperationException error) { return Mono.error(error); }
        });
        return blocking ? invocation.subscribeOn(Schedulers.boundedElastic()) : invocation;
    }
}
