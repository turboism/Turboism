package dev.turboism.adapter.cubism.edit;

import dev.turboism.sdk.cubism.edit.EditSessionException;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Objects;

/**
 * Admission gate in front of every operation-family delegate: a session that left {@code OPEN}
 * fails typed before the delegate is ever invoked — cancelled sessions raise {@code
 * EditCancelledException}, closed sessions {@code EditUnavailableException}.
 */
final class SessionOpsGate {

    static <T> T bind(
        final Class<T> family,
        final T delegate,
        final RuntimeEditSession session
    ) {
        Objects.requireNonNull(family, "family");
        Objects.requireNonNull(delegate, "delegate");
        Objects.requireNonNull(session, "session");
        return family.cast(Proxy.newProxyInstance(
            family.getClassLoader(),
            new Class<?>[]{family},
            (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) {
                    return method.invoke(delegate, args);
                }
                session.requireAdmitting();
                try {
                    return method.invoke(delegate, args);
                } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                }
            }
        ));
    }

    private SessionOpsGate() {
    }
}
