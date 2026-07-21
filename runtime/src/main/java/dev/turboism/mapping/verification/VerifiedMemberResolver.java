package dev.turboism.mapping.verification;

import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Objects;

/** Runtime reflection gateway restricted to exact aliases in a verified access plan. */
public final class VerifiedMemberResolver {

    private final VerifiedAccessPlan accessPlan;
    private final ClassLoader hostClassLoader;

    VerifiedMemberResolver(
        final VerifiedAccessPlan accessPlan,
        final ClassLoader hostClassLoader
    ) {
        this.accessPlan = Objects.requireNonNull(accessPlan, "accessPlan");
        this.hostClassLoader = Objects.requireNonNull(hostClassLoader, "hostClassLoader");
    }

    public boolean authorizes(
        final String adapterSliceId,
        final java.util.Set<String> capabilityIds,
        final java.util.Set<String> aliases
    ) {
        return accessPlan.authorizes(adapterSliceId, capabilityIds, aliases);
    }

    public boolean isExactCubismVersion(final String expectedVersion) {
        return accessPlan.cubismVersion().equals(expectedVersion);
    }

    public String cubismVersion() {
        return accessPlan.cubismVersion();
    }

    public VerifiedMethodCallSite bindStatic(final String alias) {
        final StaticSelector selector = methodSelector(alias);
        if ((selector.requiredAccessFlags() & StaticSelector.ACCESS_STATIC) == 0) {
            throw resolutionFailure(alias, "Verified alias is not a static method.");
        }
        return bindResolved(selector);
    }

    public VerifiedMethodCallSite bind(final String alias) {
        final StaticSelector selector = methodSelector(alias);
        if ((selector.forbiddenAccessFlags() & StaticSelector.ACCESS_STATIC) == 0) {
            throw resolutionFailure(alias, "Verified alias is not an instance method.");
        }
        return bindResolved(selector);
    }

    public Object invokeStatic(final String alias, final Object... arguments) {
        final StaticSelector selector = methodSelector(alias);
        if ((selector.requiredAccessFlags() & StaticSelector.ACCESS_STATIC) == 0) {
            throw resolutionFailure(alias, "Verified alias is not a static method.");
        }
        return invokeResolved(selector, null, arguments);
    }

    /** Checks a value only against the exact owner named by a verified class alias. */
    public boolean isInstance(final String alias, final Object value) {
        final StaticSelector selector = classSelector(alias);
        if (value == null) {
            return false;
        }
        try {
            final Class<?> owner = Class.forName(
                selector.ownerInternalName().replace('/', '.'),
                false,
                hostClassLoader
            );
            if (owner.getClassLoader() != hostClassLoader) {
                throw resolutionFailure(alias, "Verified host classloader attestation no longer matches.");
            }
            return owner.isInstance(value);
        } catch (VerifiedAccessException exception) {
            throw exception;
        } catch (ClassNotFoundException | LinkageError | SecurityException exception) {
            throw resolutionFailure(alias, "Verified host class resolution failed safely.");
        }
    }

    public Object invoke(final String alias, final Object target, final Object... arguments) {
        final StaticSelector selector = methodSelector(alias);
        if ((selector.forbiddenAccessFlags() & StaticSelector.ACCESS_STATIC) == 0) {
            throw resolutionFailure(alias, "Verified alias is not an instance method.");
        }
        if (target == null) {
            throw resolutionFailure(alias, "Verified instance selector target is unavailable.");
        }
        return invokeResolved(selector, target, arguments);
    }

    private StaticSelector methodSelector(final String alias) {
        final StaticSelector selector = selector(alias);
        if (selector.kind() != StaticSelector.Kind.METHOD) {
            throw resolutionFailure(alias, "Verified host selector is not an invocable method.");
        }
        return selector;
    }

    private StaticSelector classSelector(final String alias) {
        final StaticSelector selector = selector(alias);
        if (selector.kind() != StaticSelector.Kind.CLASS) {
            throw resolutionFailure(alias, "Verified host selector is not a class.");
        }
        return selector;
    }

    private StaticSelector selector(final String alias) {
        try {
            return accessPlan.selector(alias);
        } catch (IllegalArgumentException exception) {
            throw resolutionFailure(alias, "Verified host selector is unavailable in the access plan.");
        }
    }

    private VerifiedMethodCallSite bindResolved(final StaticSelector selector) {
        try {
            final Class<?> owner = Class.forName(
                selector.ownerInternalName().replace('/', '.'),
                false,
                hostClassLoader
            );
            if (owner.getClassLoader() != hostClassLoader) {
                throw resolutionFailure(
                    selector.alias(),
                    "Verified host classloader attestation no longer matches."
                );
            }
            final MethodType type = MethodType.fromMethodDescriptorString(
                selector.descriptor(),
                hostClassLoader
            );
            final Method method = owner.getDeclaredMethod(
                selector.memberName(),
                type.parameterArray()
            );
            if (!method.getDeclaringClass().equals(owner)
                || !method.getReturnType().equals(type.returnType())
                || !matchesAccess(method, selector)) {
                throw resolutionFailure(
                    selector.alias(),
                    "Verified host selector no longer matches its runtime member."
                );
            }
            return new VerifiedMethodCallSite(selector, method);
        } catch (VerifiedAccessException exception) {
            throw exception;
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalArgumentException
                 | LinkageError | SecurityException exception) {
            throw resolutionFailure(
                selector.alias(),
                "Verified host selector binding failed safely."
            );
        }
    }

    private Object invokeResolved(
        final StaticSelector selector,
        final Object target,
        final Object[] arguments
    ) {
        try {
            final Class<?> owner = Class.forName(
                selector.ownerInternalName().replace('/', '.'),
                false,
                hostClassLoader
            );
            final MethodType type = MethodType.fromMethodDescriptorString(
                selector.descriptor(),
                hostClassLoader
            );
            final Method method = owner.getDeclaredMethod(selector.memberName(), type.parameterArray());
            if (!method.getDeclaringClass().equals(owner)
                || !method.getReturnType().equals(type.returnType())
                || !matchesAccess(method, selector)) {
                throw new VerifiedAccessException(
                    selector.alias(),
                    VerifiedAccessException.FailureKind.RESOLUTION,
                    "Verified host selector no longer matches its runtime member.",
                    null
                );
            }
            if (target != null && !owner.isInstance(target)) {
                throw new VerifiedAccessException(
                    selector.alias(),
                    VerifiedAccessException.FailureKind.RESOLUTION,
                    "Verified host selector target type does not match.",
                    null
                );
            }
            return method.invoke(target, arguments == null ? new Object[0] : arguments);
        } catch (VerifiedAccessException exception) {
            throw exception;
        } catch (InvocationTargetException exception) {
            throw new VerifiedAccessException(
                selector.alias(),
                VerifiedAccessException.FailureKind.INVOCATION,
                "Verified host method execution failed safely.",
                null
            );
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException
                 | IllegalArgumentException | LinkageError exception) {
            throw new VerifiedAccessException(
                selector.alias(),
                VerifiedAccessException.FailureKind.RESOLUTION,
                "Verified host selector resolution failed safely.",
                null
            );
        }
    }

    private static VerifiedAccessException resolutionFailure(final String alias, final String message) {
        return new VerifiedAccessException(
            alias,
            VerifiedAccessException.FailureKind.RESOLUTION,
            message,
            null
        );
    }

    private static boolean matchesAccess(final Method method, final StaticSelector selector) {
        final int modifiers = method.getModifiers();
        return (!requires(selector, StaticSelector.ACCESS_PUBLIC) || Modifier.isPublic(modifiers))
            && (!requires(selector, StaticSelector.ACCESS_STATIC) || Modifier.isStatic(modifiers))
            && (!forbids(selector, StaticSelector.ACCESS_STATIC) || !Modifier.isStatic(modifiers));
    }

    private static boolean requires(final StaticSelector selector, final int flag) {
        return (selector.requiredAccessFlags() & flag) == flag;
    }

    private static boolean forbids(final StaticSelector selector, final int flag) {
        return (selector.forbiddenAccessFlags() & flag) == flag;
    }
}
