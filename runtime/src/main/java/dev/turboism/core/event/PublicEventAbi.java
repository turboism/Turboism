package dev.turboism.core.event;

import dev.turboism.sdk.event.EventBus;

import java.lang.reflect.Member;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.stream.Stream;

/**
 * Computes a deterministic structural ABI digest for one shared public event payload type.
 * Payload types are either SDK-owned (resolved through the shared SDK loader) or
 * contract-owned (resolved through the session-bound contract loader for that type).
 */
final class PublicEventAbi {

    private PublicEventAbi() {
    }

    static Class<? extends EventBus.TurboismEvent> resolve(
        final String eventType,
        final String expectedSha256
    ) {
        return resolve(eventType, expectedSha256, null);
    }

    static Class<? extends EventBus.TurboismEvent> resolve(
        final String eventType,
        final String expectedSha256,
        final PublicEventContractCatalog contracts
    ) {
        final ClassLoader sdkLoader = EventBus.class.getClassLoader();
        final ClassLoader contractLoader = contracts == null
            ? null
            : contracts.contractLoaderFor(eventType);
        final ClassLoader resolvingLoader = contractLoader != null
            ? contractLoader
            : sdkLoader;
        final Class<?> type;
        try {
            type = Class.forName(eventType, false, resolvingLoader);
        } catch (ClassNotFoundException failure) {
            throw new IllegalArgumentException(
                "Public event payload type is not available from the shared SDK or a"
                    + " declared event contract: " + eventType,
                failure
            );
        }
        if (contractLoader != null && type.getClassLoader() != contractLoader) {
            throw new IllegalArgumentException(
                "Public event payload type " + eventType
                    + " was not defined by the bound contract class loader"
            );
        }
        if (!EventBus.TurboismEvent.class.isAssignableFrom(type)
            || !type.isRecord()
            || !Modifier.isFinal(type.getModifiers())) {
            throw new IllegalArgumentException(
                "Public event payload type must be a final shared SDK or contract event"
                    + " record: " + eventType
            );
        }
        if (contractLoader == null && type.getClassLoader() != sdkLoader) {
            throw new IllegalArgumentException(
                "Public event payload type must be loaded by the shared SDK loader: "
                    + eventType
            );
        }
        if (contractLoader != null) {
            PublicEventContractClosure.verify(type);
        }
        final String actual = sha256(type);
        if (!actual.equals(expectedSha256)) {
            throw new IllegalArgumentException(
                "Public event payload ABI digest does not match " + eventType
            );
        }
        @SuppressWarnings("unchecked")
        final Class<? extends EventBus.TurboismEvent> eventClass =
            (Class<? extends EventBus.TurboismEvent>) type;
        return eventClass;
    }

    static String sha256(final Class<?> type) {
        final StringBuilder contract = new StringBuilder();
        appendTypeContract(type, contract, new java.util.HashSet<>());
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                    contract.toString().getBytes(StandardCharsets.UTF_8)
                )
            );
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void appendTypeContract(
        final Class<?> type,
        final StringBuilder contract,
        final java.util.Set<Class<?>> visited
    ) {
        if (type == null || type == Object.class || !visited.add(type)) {
            return;
        }
        contract.append("type ").append(type.getName())
            .append(' ').append(apiModifiers(type.getModifiers())).append('\n');
        if (type.getSuperclass() != null) {
            contract.append("extends ").append(type.getSuperclass().getTypeName()).append('\n');
        }
        Arrays.stream(type.getGenericInterfaces())
            .map(java.lang.reflect.Type::getTypeName)
            .sorted()
            .forEach(value -> contract.append("implements ").append(value).append('\n'));
        if (type.isSealed()) {
            Arrays.stream(type.getPermittedSubclasses())
                .map(Class::getName)
                .sorted()
                .forEach(value -> contract.append("permits ").append(value).append('\n'));
        }
        Stream.concat(
            Stream.concat(
                Arrays.stream(type.getDeclaredConstructors()),
                Arrays.stream(type.getDeclaredMethods())
            ),
            Arrays.stream(type.getDeclaredFields())
        )
            .filter(PublicEventAbi::isApiMember)
            .filter(member -> !member.isSynthetic())
            .map(PublicEventAbi::signature)
            .sorted()
            .forEach(value -> contract.append(value).append('\n'));
        appendTypeContract(type.getSuperclass(), contract, visited);
        Arrays.stream(type.getInterfaces())
            .sorted(Comparator.comparing(Class::getName))
            .forEach(parent -> appendTypeContract(parent, contract, visited));
    }

    private static boolean isApiMember(final Member member) {
        return Modifier.isPublic(member.getModifiers())
            || Modifier.isProtected(member.getModifiers());
    }

    private static String signature(final Member member) {
        if (member instanceof java.lang.reflect.Constructor<?> constructor) {
            return "constructor " + apiModifiers(constructor.getModifiers()) + ' '
                + parameters(constructor.getGenericParameterTypes())
                + throwsTypes(constructor.getGenericExceptionTypes());
        }
        if (member instanceof java.lang.reflect.Method method) {
            return "method " + apiModifiers(method.getModifiers()) + ' '
                + method.getGenericReturnType().getTypeName() + ' ' + method.getName()
                + parameters(method.getGenericParameterTypes())
                + throwsTypes(method.getGenericExceptionTypes());
        }
        final java.lang.reflect.Field field = (java.lang.reflect.Field) member;
        return "field " + apiModifiers(field.getModifiers()) + ' '
            + field.getGenericType().getTypeName() + ' ' + field.getName();
    }

    private static String parameters(final java.lang.reflect.Type[] types) {
        return Arrays.stream(types)
            .map(java.lang.reflect.Type::getTypeName)
            .collect(java.util.stream.Collectors.joining(",", "(", ")"));
    }

    private static String throwsTypes(final java.lang.reflect.Type[] types) {
        if (types.length == 0) {
            return "";
        }
        return Arrays.stream(types)
            .map(java.lang.reflect.Type::getTypeName)
            .sorted(Comparator.naturalOrder())
            .collect(java.util.stream.Collectors.joining(",", " throws ", ""));
    }

    private static int apiModifiers(final int modifiers) {
        return modifiers & (Modifier.PUBLIC | Modifier.PROTECTED | Modifier.STATIC
            | Modifier.FINAL | Modifier.ABSTRACT | Modifier.INTERFACE);
    }
}
