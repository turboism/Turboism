package dev.turboism.mapping.verification;

import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Function;

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

    /** Checks whether one additive feature is fully covered by this verified plan. */
    public boolean authorizesFeature(
        final String adapterSliceId,
        final String capabilityId,
        final java.util.Set<String> aliases
    ) {
        Objects.requireNonNull(capabilityId, "capabilityId");
        return accessPlan.authorizesFeature(
            Objects.requireNonNull(adapterSliceId, "adapterSliceId"),
            capabilityId,
            java.util.Set.copyOf(Objects.requireNonNull(aliases, "aliases"))
        );
    }

    public boolean isExactCubismVersion(final String expectedVersion) {
        return accessPlan.cubismVersion().equals(expectedVersion);
    }

    public String cubismVersion() {
        return accessPlan.cubismVersion();
    }

    /** Returns the defining classloader attested by this resolver without exposing host members. */
    public ClassLoader hostClassLoader() {
        return hostClassLoader;
    }

    /** Returns the exact verified selector tuple for runtime-owned instrumentation. */
    public StaticSelector verifiedSelector(final String alias) {
        return selector(alias);
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

    public Object readStaticField(final String alias) {
        final StaticSelector selector = fieldSelector(alias);
        if ((selector.requiredAccessFlags() & StaticSelector.ACCESS_STATIC) == 0) {
            throw resolutionFailure(alias, "Verified alias is not a static field.");
        }
        try {
            final Class<?> owner = Class.forName(
                selector.ownerInternalName().replace('/', '.'),
                false,
                hostClassLoader
            );
            if (owner.getClassLoader() != hostClassLoader) {
                throw resolutionFailure(
                    alias,
                    "Verified host classloader attestation no longer matches."
                );
            }
            final Class<?> fieldType = MethodType.fromMethodDescriptorString(
                "()" + selector.descriptor(),
                hostClassLoader
            ).returnType();
            final Field field = owner.getDeclaredField(selector.memberName());
            if (!field.getDeclaringClass().equals(owner)
                || !field.getType().equals(fieldType)
                || !matchesAccess(field.getModifiers(), selector)) {
                throw resolutionFailure(alias, "Verified host field no longer matches.");
            }
            return field.get(null);
        } catch (VerifiedAccessException exception) {
            throw exception;
        } catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException
                 | IllegalArgumentException | LinkageError | SecurityException exception) {
            throw resolutionFailure(alias, "Verified host field resolution failed safely.");
        }
    }

    /** Reads one exact verified instance field without exposing the reflective member. */
    public Object readField(final String alias, final Object target) {
        final StaticSelector selector = fieldSelector(alias);
        if ((selector.requiredAccessFlags() & StaticSelector.ACCESS_STATIC) != 0) {
            throw resolutionFailure(alias, "Verified alias is not an instance field.");
        }
        if (target == null) {
            throw resolutionFailure(alias, "Verified instance field target is unavailable.");
        }
        try {
            final Class<?> owner = Class.forName(
                selector.ownerInternalName().replace('/', '.'), false, hostClassLoader
            );
            final Class<?> fieldType = MethodType.fromMethodDescriptorString(
                "()" + selector.descriptor(), hostClassLoader
            ).returnType();
            final Field field = owner.getDeclaredField(selector.memberName());
            if (owner.getClassLoader() != hostClassLoader
                || !owner.isInstance(target)
                || !field.getDeclaringClass().equals(owner)
                || !field.getType().equals(fieldType)
                || !matchesAccess(field.getModifiers(), selector)
                || (!field.canAccess(target) && !field.trySetAccessible())) {
                throw resolutionFailure(alias, "Verified host field no longer matches.");
            }
            return field.get(target);
        } catch (VerifiedAccessException exception) {
            throw exception;
        } catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException
                 | IllegalArgumentException | LinkageError | SecurityException exception) {
            throw resolutionFailure(alias, "Verified host field resolution failed safely.");
        }
    }

    public Object construct(final String alias, final Object... arguments) {
        final StaticSelector selector = constructorSelector(alias);
        try {
            final Class<?> owner = Class.forName(
                selector.ownerInternalName().replace('/', '.'),
                false,
                hostClassLoader
            );
            if (owner.getClassLoader() != hostClassLoader) {
                throw resolutionFailure(alias, "Verified host classloader attestation no longer matches.");
            }
            final MethodType type = MethodType.fromMethodDescriptorString(
                selector.descriptor(),
                hostClassLoader
            );
            if (!type.returnType().equals(void.class)) {
                throw resolutionFailure(alias, "Verified constructor descriptor must return void.");
            }
            final Constructor<?> constructor = owner.getDeclaredConstructor(type.parameterArray());
            if (!constructor.getDeclaringClass().equals(owner)
                || !matchesAccess(constructor.getModifiers(), selector)) {
                throw resolutionFailure(alias, "Verified host constructor no longer matches.");
            }
            if (!constructor.canAccess(null)) {
                final int constructorModifiers = constructor.getModifiers();
                final boolean packagePrivateConstructor = !Modifier.isPublic(constructorModifiers)
                    && !Modifier.isProtected(constructorModifiers)
                    && !Modifier.isPrivate(constructorModifiers);
                if (!packagePrivateConstructor || !constructor.trySetAccessible()) {
                    throw resolutionFailure(alias, "Verified host constructor is not accessible.");
                }
            }
            return constructor.newInstance(arguments == null ? new Object[0] : arguments);
        } catch (VerifiedAccessException exception) {
            throw exception;
        } catch (InvocationTargetException exception) {
            throw new VerifiedAccessException(
                alias,
                VerifiedAccessException.FailureKind.INVOCATION,
                "Verified host constructor execution failed safely.",
                null
            );
        } catch (ClassNotFoundException | NoSuchMethodException | InstantiationException
                 | IllegalAccessException | IllegalArgumentException | LinkageError
                 | SecurityException exception) {
            throw resolutionFailure(alias, "Verified host constructor resolution failed safely.");
        }
    }

    /**
     * Creates a host-classloader proxy for one exact parameter type in a verified method.
     */
    public Object createFunctionalArgumentProxy(
        final String methodAlias,
        final int parameterIndex,
        final Function<Object, Object> callback
    ) {
        return createFunctionalArgumentProxy(
            methodAlias,
            methodSelector(methodAlias),
            parameterIndex,
            callback
        );
    }

    /**
     * Creates a host-classloader proxy for one exact parameter type in a verified constructor.
     */
    public Object createFunctionalConstructorArgumentProxy(
        final String constructorAlias,
        final int parameterIndex,
        final Function<Object, Object> callback
    ) {
        return createFunctionalArgumentProxy(
            constructorAlias,
            constructorSelector(constructorAlias),
            parameterIndex,
            callback
        );
    }

    private Object createFunctionalArgumentProxy(
        final String alias,
        final StaticSelector selector,
        final int parameterIndex,
        final Function<Object, Object> callback
    ) {
        Objects.requireNonNull(callback, "callback");
        try {
            final MethodType type = MethodType.fromMethodDescriptorString(
                selector.descriptor(),
                hostClassLoader
            );
            final Class<?>[] parameterTypes = type.parameterArray();
            if (parameterIndex < 0 || parameterIndex >= parameterTypes.length) {
                throw resolutionFailure(
                    alias,
                    "Verified host callback parameter index is out of range."
                );
            }
            return createFunctionalProxy(
                alias,
                parameterTypes[parameterIndex],
                callback
            );
        } catch (VerifiedAccessException exception) {
            throw exception;
        } catch (IllegalArgumentException | LinkageError exception) {
            throw resolutionFailure(
                alias,
                "Verified host callback parameter resolution failed safely."
            );
        }
    }

    /**
     * Creates a host-classloader proxy for one exactly verified single-abstract-method interface.
     *
     * <p>The callback receives the interface invocation's sole argument, or {@code null} for a
     * zero-argument method. Host interfaces with more than one abstract method or more than one
     * callback argument fail closed.</p>
     */
    public Object createFunctionalProxy(
        final String alias,
        final Function<Object, Object> callback
    ) {
        Objects.requireNonNull(callback, "callback");
        final StaticSelector selector = classSelector(alias);
        try {
            final Class<?> type = Class.forName(
                selector.ownerInternalName().replace('/', '.'),
                false,
                hostClassLoader
            );
            if (type.getClassLoader() != hostClassLoader) {
                throw resolutionFailure(
                    alias,
                    "Verified host classloader attestation no longer matches."
                );
            }
            return createFunctionalProxy(alias, type, callback);
        } catch (VerifiedAccessException exception) {
            throw exception;
        } catch (ClassNotFoundException | IllegalArgumentException | LinkageError
                 | SecurityException exception) {
            throw resolutionFailure(
                alias,
                "Verified host callback proxy creation failed safely."
            );
        }
    }

    private Object createFunctionalProxy(
        final String alias,
        final Class<?> type,
        final Function<Object, Object> callback
    ) {
        if (type.getClassLoader() != hostClassLoader) {
            throw resolutionFailure(
                alias,
                "Verified host classloader attestation no longer matches."
            );
        }
        if (!type.isInterface()) {
            throw resolutionFailure(alias, "Verified host callback type is not an interface.");
        }
        final Method[] abstractMethods = Arrays.stream(type.getMethods())
            .filter(method -> Modifier.isAbstract(method.getModifiers()))
            .filter(method -> method.getDeclaringClass() != Object.class)
            .toArray(Method[]::new);
        if (abstractMethods.length != 1) {
            throw resolutionFailure(
                alias,
                "Verified host callback type is not a single-abstract-method interface."
            );
        }
        final Method functionalMethod = abstractMethods[0];
        if (functionalMethod.getParameterCount() > 1) {
            throw resolutionFailure(
                alias,
                "Verified host callback method accepts too many arguments."
            );
        }
        return Proxy.newProxyInstance(
            hostClassLoader,
            new Class<?>[] {type},
            (proxy, method, arguments) -> {
                if (method.getDeclaringClass() == Object.class) {
                    return switch (method.getName()) {
                        case "toString" -> "VerifiedFunctionalProxy[" + alias + ']';
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == (arguments == null ? null : arguments[0]);
                        default -> throw resolutionFailure(
                            alias,
                            "Unsupported Object method on verified host callback proxy."
                        );
                    };
                }
                if (!method.equals(functionalMethod)) {
                    throw resolutionFailure(
                        alias,
                        "Verified host callback invoked an unexpected method."
                    );
                }
                final Object argument = arguments == null || arguments.length == 0
                    ? null
                    : arguments[0];
                final Object result = callback.apply(argument);
                return method.getReturnType() == void.class ? null : result;
            }
        );
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

    private StaticSelector fieldSelector(final String alias) {
        final StaticSelector selector = selector(alias);
        if (selector.kind() != StaticSelector.Kind.FIELD) {
            throw resolutionFailure(alias, "Verified host selector is not a field.");
        }
        return selector;
    }

    private StaticSelector constructorSelector(final String alias) {
        final StaticSelector selector = selector(alias);
        if (selector.kind() != StaticSelector.Kind.CONSTRUCTOR) {
            throw resolutionFailure(alias, "Verified host selector is not a constructor.");
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
            if (!method.canAccess(target)) {
                final boolean publicMethodOnNonPublicOwner = Modifier.isPublic(method.getModifiers())
                    && !Modifier.isPublic(owner.getModifiers());
                if (!publicMethodOnNonPublicOwner || !method.trySetAccessible()) {
                    throw resolutionFailure(
                        selector.alias(),
                        "Verified host method is not accessible."
                    );
                }
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
        return matchesAccess(method.getModifiers(), selector);
    }

    private static boolean matchesAccess(final int modifiers, final StaticSelector selector) {
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
