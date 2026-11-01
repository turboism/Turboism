package dev.turboism.mapping.verification;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
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

    private static final String INVOCATION_FAILURE_MESSAGE = "Verified host method execution failed safely.";
    private static final Object[] NO_ARGUMENTS = new Object[0];
    private static final MethodHandle HOST_FAILURE_WRAPPER = hostFailureWrapper();

    private final String alias;
    private final String resolutionFailureMessage;
    private final String targetMismatchMessage;
    private final String inaccessibleMessage;
    private final boolean staticMethod;
    private final int parameterCount;
    private volatile Method method;
    private volatile MethodHandle methodHandle;

    VerifiedMethodCallSite(final StaticSelector selector, final Method method) {
        this(
                selector,
                method,
                "Verified call-site invocation could not be resolved safely.",
                "Verified call-site target type does not match.",
                "Verified call-site invocation could not be resolved safely.");
    }

    private VerifiedMethodCallSite(
            final StaticSelector selector,
            final Method method,
            final String resolutionFailureMessage,
            final String targetMismatchMessage,
            final String inaccessibleMessage) {
        this.alias = Objects.requireNonNull(selector, "selector").alias();
        this.method = Objects.requireNonNull(method, "method");
        this.staticMethod = Modifier.isStatic(method.getModifiers());
        this.parameterCount = method.getParameterCount();
        this.resolutionFailureMessage = Objects.requireNonNull(resolutionFailureMessage, "resolutionFailureMessage");
        this.targetMismatchMessage = Objects.requireNonNull(targetMismatchMessage, "targetMismatchMessage");
        this.inaccessibleMessage = Objects.requireNonNull(inaccessibleMessage, "inaccessibleMessage");
    }

    /**
     * Binds a call site that keeps the resolver's established diagnostic messages.
     *
     * @param selector the verified method selector
     * @param method the exactly resolved host method
     * @return a call site using resolver-facing failure messages
     */
    static VerifiedMethodCallSite forResolver(final StaticSelector selector, final Method method) {
        return new VerifiedMethodCallSite(
                selector,
                method,
                "Verified host selector resolution failed safely.",
                "Verified host selector target type does not match.",
                "Verified host method is not accessible.");
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
     * @return whether {@code target} could be passed to {@link #invoke(Object, Object...)}
     *     without a receiver-type resolution failure — the site is open, non-static, and
     *     {@code target} is a non-null instance of the verified declaring class
     */
    public boolean acceptsReceiver(final Object target) {
        final Method resolved = method;
        return resolved != null
                && !staticMethod
                && target != null
                && resolved.getDeclaringClass().isInstance(target);
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
    public synchronized void close() {
        method = null;
        methodHandle = null;
    }

    private Object invokeResolved(final Object target, final Object[] arguments) {
        final Method resolved = method;
        if (resolved == null) {
            throw resolutionFailure("Verified call site is closed.");
        }
        if (target != null && !resolved.getDeclaringClass().isInstance(target)) {
            throw resolutionFailure(targetMismatchMessage);
        }
        final Object[] invocationArguments = arguments == null ? NO_ARGUMENTS : arguments;
        if (invocationArguments.length != parameterCount) {
            throw resolutionFailure(resolutionFailureMessage);
        }
        final MethodHandle handle = invocationHandle(resolved, target);
        try {
            return staticMethod
                    ? (Object) handle.invoke(invocationArguments)
                    : (Object) handle.invoke(target, invocationArguments);
        } catch (InvocationTargetException exception) {
            final Throwable failure = exception.getCause();
            dev.turboism.core.runtime.work.FatalErrors.rethrowIfFatal(failure);
            throw VerifiedAccessException.invocationFailure(alias, INVOCATION_FAILURE_MESSAGE, failure);
        } catch (Throwable failure) {
            // The method-handle guard above wraps every host throwable, so a direct failure here
            // comes from receiver/argument conversion or handle setup, never from the host body.
            dev.turboism.core.runtime.work.FatalErrors.rethrowIfFatal(failure);
            throw resolutionFailure(resolutionFailureMessage);
        }
    }

    private MethodHandle invocationHandle(final Method resolved, final Object target) {
        MethodHandle handle = methodHandle;
        if (handle != null) {
            return handle;
        }
        synchronized (this) {
            if (method == null) {
                throw resolutionFailure("Verified call site is closed.");
            }
            handle = methodHandle;
            if (handle == null) {
                handle = createInvocationHandle(resolved, target);
                methodHandle = handle;
            }
            return handle;
        }
    }

    private MethodHandle createInvocationHandle(final Method resolved, final Object target) {
        if (!resolved.canAccess(target)) {
            // Only a public method declared by a non-public owner may be opened. Non-public
            // methods keep failing closed even if reflection would allow setAccessible().
            final boolean publicMethodOnNonPublicOwner = Modifier.isPublic(resolved.getModifiers())
                    && !Modifier.isPublic(resolved.getDeclaringClass().getModifiers());
            if (!publicMethodOnNonPublicOwner || !resolved.trySetAccessible()) {
                throw resolutionFailure(inaccessibleMessage);
            }
        }
        try {
            // The access check above has already succeeded, so lookup is allowed to unreflect the
            // member, including a public method whose declaring class is not public.
            final MethodHandle unreflected = MethodHandles.lookup().unreflect(resolved);
            // Wrap host-body failures in InvocationTargetException before spreading. The spreader
            // and its receiver/argument conversions stay outside the catch boundary, so conversion
            // failures remain distinguishable from host throwables.
            final MethodHandle guarded = MethodHandles.catchException(
                    unreflected,
                    Throwable.class,
                    HOST_FAILURE_WRAPPER.asType(
                            MethodType.methodType(unreflected.type().returnType(), Throwable.class)));
            final MethodHandle spreader = guarded.asSpreader(Object[].class, parameterCount);
            return spreader.asType(
                    staticMethod
                            ? MethodType.methodType(Object.class, Object[].class)
                            : MethodType.methodType(Object.class, Object.class, Object[].class));
        } catch (IllegalAccessException | LinkageError | RuntimeException failure) {
            throw resolutionFailure(resolutionFailureMessage);
        }
    }

    private static MethodHandle hostFailureWrapper() {
        try {
            return MethodHandles.lookup()
                    .findStatic(
                            VerifiedMethodCallSite.class,
                            "wrapHostFailure",
                            MethodType.methodType(Object.class, Throwable.class));
        } catch (NoSuchMethodException | IllegalAccessException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static Object wrapHostFailure(final Throwable failure) throws InvocationTargetException {
        throw new InvocationTargetException(failure);
    }

    private VerifiedAccessException resolutionFailure(final String message) {
        return new VerifiedAccessException(alias, VerifiedAccessException.FailureKind.RESOLUTION, message, null);
    }
}
