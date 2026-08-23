package dev.turboism.core.event;

import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.failure.ExceptionAdvice;
import dev.turboism.sdk.failure.FailureContext;
import dev.turboism.sdk.failure.HandlesException;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Validates and invokes plugin-local exception advice for event subscribers. */
final class EventFailureInterceptor {

    private final List<AdviceHandler> handlers;

    EventFailureInterceptor(final List<?> entrypoints) {
        final List<AdviceHandler> discovered = new ArrayList<>();
        for (Object entrypoint : List.copyOf(Objects.requireNonNull(
            entrypoints,
            "entrypoints"
        ))) {
            final Object value = Objects.requireNonNull(entrypoint, "entrypoint");
            if (!value.getClass().isAnnotationPresent(ExceptionAdvice.class)) {
                continue;
            }
            for (Method method : Arrays.stream(value.getClass().getMethods())
                .filter(candidate -> candidate.isAnnotationPresent(HandlesException.class))
                .sorted(Comparator.comparing(EventFailureInterceptor::signature))
                .toList()) {
                discovered.add(advice(value, method));
            }
        }
        handlers = List.copyOf(discovered);
    }

    boolean intercept(
        final String pluginId,
        final EventSubscriberDescriptor subscriber,
        final EventBus.TurboismEvent event,
        final Throwable failure
    ) {
        if (subscriber.noFailureInterception()) {
            return false;
        }
        final String operationId = subscriber.failureBoundary();
        final FailureContext context = new FailureContext(
            pluginId,
            operationId,
            event.getClass().getName(),
            failure.getClass().getName()
        );
        for (AdviceHandler handler : handlers) {
            if (!handler.handles(failure)) {
                continue;
            }
            try {
                handler.invoke(failure, context);
                return true;
            } catch (ThreadDeath | VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable ignored) {
                // Advice failure falls through to Runtime structured containment.
            }
        }
        return false;
    }

    private static AdviceHandler advice(final Object entrypoint, final Method method) {
        final int modifiers = method.getModifiers();
        if (!Modifier.isPublic(modifiers) || Modifier.isStatic(modifiers)) {
            throw invalid(method, "advice must be a public instance method");
        }
        if (method.getReturnType() != void.class) {
            throw invalid(method, "advice must return void");
        }
        final Class<?>[] parameters = method.getParameterTypes();
        if (parameters.length < 1 || parameters.length > 2
            || !Throwable.class.isAssignableFrom(parameters[0])
            || (parameters.length == 2 && parameters[1] != FailureContext.class)) {
            throw invalid(
                method,
                "advice parameters must be (Throwable) or (Throwable, FailureContext)"
            );
        }
        final List<Class<? extends Throwable>> handled = List.of(
            method.getAnnotation(HandlesException.class).value()
        );
        if (handled.isEmpty()) {
            throw invalid(method, "advice must declare at least one exception type");
        }
        if (handled.stream().anyMatch(type -> !parameters[0].isAssignableFrom(type))) {
            throw invalid(method, "declared exception type is incompatible with parameter");
        }
        return new AdviceHandler(entrypoint, method, handled, parameters.length == 2);
    }

    private static IllegalArgumentException invalid(
        final Method method,
        final String message
    ) {
        return new IllegalArgumentException(
            "Invalid @HandlesException method " + signature(method) + ": " + message
        );
    }

    private static String signature(final Method method) {
        return method.getDeclaringClass().getName()
            + "#" + method.getName()
            + Arrays.stream(method.getParameterTypes())
                .map(Class::getName)
                .collect(java.util.stream.Collectors.joining(",", "(", ")"))
            + ":" + method.getReturnType().getName();
    }

    private static String requireText(final String value, final String name) {
        final String text = Objects.requireNonNull(value, name);
        if (text.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return text;
    }

    private record AdviceHandler(
        Object entrypoint,
        Method method,
        List<Class<? extends Throwable>> handled,
        boolean acceptsContext
    ) {
        private boolean handles(final Throwable failure) {
            return handled.stream().anyMatch(type -> type.isInstance(failure));
        }

        private void invoke(
            final Throwable failure,
            final FailureContext context
        ) throws Throwable {
            try {
                if (acceptsContext) {
                    method.invoke(entrypoint, failure, context);
                } else {
                    method.invoke(entrypoint, failure);
                }
            } catch (InvocationTargetException invocationFailure) {
                throw invocationFailure.getCause();
            } catch (IllegalAccessException accessFailure) {
                throw new IllegalStateException(
                    "Validated exception advice became inaccessible: " + signature(method),
                    accessFailure
                );
            }
        }
    }
}
