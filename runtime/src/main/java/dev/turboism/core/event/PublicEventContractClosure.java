package dev.turboism.core.event;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Recursive payload-closure validator for public event contract types.
 *
 * <p>{@link PublicEventAbi} computes a digest over member <em>signatures</em> and only
 * recurses through superclasses and interfaces — it proves structural equality but not
 * visibility. This validator walks the full generic type graph reachable from a
 * contract-bound event type's API surface (record components, public/protected
 * constructors, methods, fields, throws clauses, superclasses, interfaces and permitted
 * subclasses) and proves every referenced type classifies into the published closure:</p>
 *
 * <ul>
 *   <li>JDK platform/bootstrap types — allowed, not traversed;</li>
 *   <li>{@code dev.turboism.sdk.*} types — allowed, not traversed (the SDK is trusted
 *       surface shared by every plugin);</li>
 *   <li>types defined by the same bound contract class loader — allowed and traversed, so
 *       {@code Event → Payload → private Dto} rejects deterministically when {@code Dto} is
 *       absent from the contract artifact.</li>
 * </ul>
 *
 * <p>Anything else — framework internals, provider implementation DTOs, host or
 * third-party-library types — fails admission with the offending type name before any
 * plugin code is constructed.</p>
 */
final class PublicEventContractClosure {

    private PublicEventContractClosure() {
    }

    /**
     * Verifies that the whole API surface of {@code contractType} resolves inside the
     * published payload closure.
     *
     * @param contractType an event or payload type defined by a bound contract loader
     * @throws IllegalArgumentException when a referenced type is outside the closure
     */
    static void verify(final Class<?> contractType) {
        final ClassLoader contractLoader = contractType.getClassLoader();
        final Set<Class<?>> visited = new HashSet<>();
        final Deque<Class<?>> pending = new ArrayDeque<>();
        pending.add(contractType);
        while (!pending.isEmpty()) {
            final Class<?> type = pending.poll();
            if (!visited.add(type)) {
                continue;
            }
            if (type.getClassLoader() != contractLoader) {
                throw new IllegalArgumentException(
                    "public event contract type " + type.getName()
                        + " is not defined by the bound contract class loader"
                );
            }
            try {
                verifyReferences(type, contractLoader, pending, new HashSet<>());
            } catch (StackOverflowError overflow) {
                throw new IllegalArgumentException(
                    "public event contract type " + type.getName()
                        + " has a generic signature too deep to verify safely",
                    overflow
                );
            }
        }
    }

    private static void verifyReferences(
        final Class<?> type,
        final ClassLoader contractLoader,
        final Deque<Class<?>> pending,
        final Set<Type> visitedTypes
    ) {
        final Set<Type> referenced = new HashSet<>();
        try {
            final Type superclass = type.getGenericSuperclass();
            if (superclass != null) {
                referenced.add(superclass);
            }
            referenced.addAll(List.of(type.getGenericInterfaces()));
            if (type.isSealed()) {
                referenced.addAll(List.of(type.getPermittedSubclasses()));
            }
            for (final TypeVariable<?> parameter : type.getTypeParameters()) {
                referenced.addAll(List.of(parameter.getBounds()));
            }
            if (type.isRecord()) {
                for (final RecordComponent component : type.getRecordComponents()) {
                    referenced.add(component.getGenericType());
                }
            }
            for (final Constructor<?> constructor : type.getDeclaredConstructors()) {
                if (ContractClosurePolicy.isApiMember(constructor.getModifiers(), true)) {
                    referenced.addAll(List.of(constructor.getGenericParameterTypes()));
                    referenced.addAll(List.of(constructor.getGenericExceptionTypes()));
                    for (final TypeVariable<?> parameter : constructor.getTypeParameters()) {
                        referenced.addAll(List.of(parameter.getBounds()));
                    }
                }
            }
            for (final Method method : type.getDeclaredMethods()) {
                if (ContractClosurePolicy.isApiMember(method.getModifiers(), false)) {
                    referenced.add(method.getGenericReturnType());
                    referenced.addAll(List.of(method.getGenericParameterTypes()));
                    referenced.addAll(List.of(method.getGenericExceptionTypes()));
                    for (final TypeVariable<?> parameter : method.getTypeParameters()) {
                        referenced.addAll(List.of(parameter.getBounds()));
                    }
                }
            }
            for (final Field field : type.getDeclaredFields()) {
                if (ContractClosurePolicy.isApiMember(field.getModifiers(), false)) {
                    referenced.add(field.getGenericType());
                }
            }
            for (final Type reference : referenced) {
                classify(reference, type, contractLoader, pending, visitedTypes);
            }
        } catch (TypeNotPresentException | NoClassDefFoundError failure) {
            throw new IllegalArgumentException(
                "public event contract type " + type.getName()
                    + " has a payload type that cannot be resolved within the contract"
                    + " closure: " + failure.getMessage(),
                failure
            );
        }
    }

    private static void classify(
        final Type reference,
        final Class<?> owner,
        final ClassLoader contractLoader,
        final Deque<Class<?>> pending,
        final Set<Type> visitedTypes
    ) {
        if (!visitedTypes.add(reference)) {
            return;
        }
        if (reference instanceof Class<?> clazz) {
            classifyClass(clazz, owner, contractLoader, pending);
            return;
        }
        if (reference instanceof ParameterizedType parameterized) {
            classify(parameterized.getRawType(), owner, contractLoader, pending, visitedTypes);
            // A non-static inner type's owner graph carries its own type arguments:
            // Outer<PrivateDto>.Inner would leak PrivateDto if the owner were skipped.
            final Type ownerType = parameterized.getOwnerType();
            if (ownerType != null) {
                classify(ownerType, owner, contractLoader, pending, visitedTypes);
            }
            for (final Type argument : parameterized.getActualTypeArguments()) {
                classify(argument, owner, contractLoader, pending, visitedTypes);
            }
            return;
        }
        if (reference instanceof GenericArrayType array) {
            classify(array.getGenericComponentType(), owner, contractLoader, pending, visitedTypes);
            return;
        }
        if (reference instanceof TypeVariable<?> variable) {
            for (final Type bound : variable.getBounds()) {
                classify(bound, owner, contractLoader, pending, visitedTypes);
            }
            return;
        }
        if (reference instanceof WildcardType wildcard) {
            for (final Type bound : wildcard.getUpperBounds()) {
                classify(bound, owner, contractLoader, pending, visitedTypes);
            }
            for (final Type bound : wildcard.getLowerBounds()) {
                classify(bound, owner, contractLoader, pending, visitedTypes);
            }
        }
    }

    private static void classifyClass(
        final Class<?> clazz,
        final Class<?> owner,
        final ClassLoader contractLoader,
        final Deque<Class<?>> pending
    ) {
        Class<?> subject = clazz;
        while (subject.isArray()) {
            subject = subject.getComponentType();
        }
        if (subject.isPrimitive()) {
            return;
        }
        final String name = subject.getName();
        if (name.startsWith(ContractClosurePolicy.SDK_PACKAGE_PREFIX)) {
            return;
        }
        final ClassLoader loader = subject.getClassLoader();
        if (loader == contractLoader) {
            pending.add(subject);
            return;
        }
        final boolean platform = loader == null
            || loader == ClassLoader.getPlatformClassLoader();
        if (platform && !name.startsWith("dev.turboism.") && !name.startsWith("com.live2d.")) {
            return;
        }
        throw new IllegalArgumentException(
            "public event contract type " + owner.getName() + " references " + name
                + ", which is not part of the contract payload closure (the contract"
                + " artifact, dev.turboism.sdk.*, or JDK platform classes)"
        );
    }
}
