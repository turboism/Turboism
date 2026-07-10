package dev.turboism.mapping.verification;

import java.util.Objects;

/** A single exact class/member selector to verify against JAR metadata. */
public record StaticSelector(
    String mappingId,
    String alias,
    Kind kind,
    String ownerInternalName,
    String memberName,
    String descriptor,
    int requiredAccessFlags,
    int forbiddenAccessFlags
) {

    public static final int ACCESS_PUBLIC = 0x0001;
    public static final int ACCESS_STATIC = 0x0008;

    public StaticSelector {
        mappingId = requireText(mappingId, "mappingId");
        alias = requireText(alias, "alias");
        kind = Objects.requireNonNull(kind, "kind");
        ownerInternalName = requireInternalName(ownerInternalName);
        memberName = kind == Kind.CLASS ? "" : requireText(memberName, "memberName");
        descriptor = kind == Kind.CLASS ? "" : requireText(descriptor, "descriptor");
        if (requiredAccessFlags < 0 || forbiddenAccessFlags < 0) {
            throw new IllegalArgumentException("access flags must not be negative");
        }
        if ((requiredAccessFlags & forbiddenAccessFlags) != 0) {
            throw new IllegalArgumentException("required and forbidden access flags must not overlap");
        }
    }

    public static StaticSelector classSelector(final String alias, final String ownerInternalName) {
        return classSelector(alias, alias, ownerInternalName);
    }

    public static StaticSelector classSelector(
        final String mappingId,
        final String alias,
        final String ownerInternalName
    ) {
        return new StaticSelector(mappingId, alias, Kind.CLASS, ownerInternalName, "", "", 0, 0);
    }

    public static StaticSelector method(
        final String alias,
        final String ownerInternalName,
        final String memberName,
        final String descriptor
    ) {
        return method(alias, alias, ownerInternalName, memberName, descriptor, 0);
    }

    public static StaticSelector method(
        final String alias,
        final String ownerInternalName,
        final String memberName,
        final String descriptor,
        final int requiredAccessFlags
    ) {
        return method(alias, alias, ownerInternalName, memberName, descriptor, requiredAccessFlags);
    }

    public static StaticSelector method(
        final String mappingId,
        final String alias,
        final String ownerInternalName,
        final String memberName,
        final String descriptor,
        final int requiredAccessFlags
    ) {
        return new StaticSelector(
            mappingId,
            alias,
            Kind.METHOD,
            ownerInternalName,
            memberName,
            descriptor,
            requiredAccessFlags,
            ACCESS_STATIC
        );
    }

    public static StaticSelector staticMethod(
        final String alias,
        final String ownerInternalName,
        final String memberName,
        final String descriptor,
        final int requiredAccessFlags
    ) {
        return staticMethod(alias, alias, ownerInternalName, memberName, descriptor, requiredAccessFlags);
    }

    public static StaticSelector staticMethod(
        final String mappingId,
        final String alias,
        final String ownerInternalName,
        final String memberName,
        final String descriptor,
        final int requiredAccessFlags
    ) {
        return new StaticSelector(
            mappingId,
            alias,
            Kind.METHOD,
            ownerInternalName,
            memberName,
            descriptor,
            requiredAccessFlags | ACCESS_STATIC,
            0
        );
    }

    public static StaticSelector field(
        final String alias,
        final String ownerInternalName,
        final String memberName,
        final String descriptor,
        final int requiredAccessFlags
    ) {
        return new StaticSelector(
            alias,
            alias,
            Kind.FIELD,
            ownerInternalName,
            memberName,
            descriptor,
            requiredAccessFlags,
            0
        );
    }

    private static String requireInternalName(final String value) {
        final String internalName = requireText(value, "ownerInternalName");
        if (internalName.startsWith("/") || internalName.endsWith("/") || internalName.contains(".")
            || internalName.contains("..")) {
            throw new IllegalArgumentException("ownerInternalName must be a JVM internal name");
        }
        return internalName;
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public enum Kind {
        CLASS,
        METHOD,
        FIELD
    }
}
