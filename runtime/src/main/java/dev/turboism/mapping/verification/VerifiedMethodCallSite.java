package dev.turboism.mapping.verification;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Objects;

/**
 * Pre-resolved public method guarded by one verified selector.
 *
 * <p>The call site exposes no reflection object and can be closed to release its defining-class
 * reference. Invocation failures remain sanitized and fail closed.</p>
 */
public final class VerifiedMethodCallSite implements AutoCloseable {

    private final String alias;
    private final boolean staticMethod;
    private volatile Method method;

    VerifiedMethodCallSite(final StaticSelector selector, final Method method) {
        this.alias = Objects.requireNonNull(selector, "selector").alias();
        this.method = Objects.requireNonNull(method, "method");
        this.staticMethod = Modifier.isStatic(method.getModifiers());
    }

    /**
     * Invokes the verified method as a static call.
     *
     * @param arguments arguments to pass; a {@code null} array is treated as no
     *     arguments
     * @return whatever the host method returned
     * @throws VerifiedAccessException if the call site is an instance method or
     *     has been closed ({@code RESOLUTION}), if the invocation cannot be
     *     resolved safely, or if the host method threw ({@code INVOCATION});
     *     the host throwable is deliberately not attached
     */
    public Object invokeStatic(final Object... arguments) {
        if (!staticMethod) {
            throw resolutionFailure("Verified call site is not static.");
        }
        return invokeResolved(null, arguments);
    }

    /**
     * Invokes the verified method on a receiver, whose type is checked against
     * the declaring class before the call.
     *
     * @param target receiver; must be non-null and an instance of the verified
     *     declaring class
     * @param arguments arguments to pass; a {@code null} array is treated as no
     *     arguments
     * @return whatever the host method returned
     * @throws VerifiedAccessException if the call site is static, the target is
     *     null or of the wrong type, the site is closed, or the host method
     *     threw; failures stay sanitized either way
     */
    public Object invoke(final Object target, final Object... arguments) {
        if (staticMethod) {
            throw resolutionFailure("Verified call site is not an instance method.");
        }
        if (target == null) {
            throw resolutionFailure("Verified call-site target is unavailable.");
        }
        return invokeResolved(target, arguments);
    }

    /**
     * @return whether {@link #close()} has released the underlying reflection
     *     object; a closed site rejects every further invocation rather than
     *     re-resolving
     */
    public boolean isClosed() {
        return method == null;
    }

    @Override
    public void close() {
        method = null;
    }

    private Object invokeResolved(final Object target, final Object[] arguments) {
        final Method resolved = method;
        if (resolved == null) {
            throw resolutionFailure("Verified call site is closed.");
        }
        if (target != null && !resolved.getDeclaringClass().isInstance(target)) {
            throw resolutionFailure("Verified call-site target type does not match.");
        }
        try {
            return resolved.invoke(target, arguments == null ? new Object[0] : arguments);
        } catch (InvocationTargetException exception) {
            throw new VerifiedAccessException(
                alias,
                VerifiedAccessException.FailureKind.INVOCATION,
                "Verified host method execution failed safely.",
                null
            );
        } catch (IllegalAccessException | IllegalArgumentException | LinkageError
                 | SecurityException exception) {
            throw resolutionFailure("Verified call-site invocation could not be resolved safely.");
        }
    }

    private VerifiedAccessException resolutionFailure(final String message) {
        return new VerifiedAccessException(
            alias,
            VerifiedAccessException.FailureKind.RESOLUTION,
            message,
            null
        );
    }
}
