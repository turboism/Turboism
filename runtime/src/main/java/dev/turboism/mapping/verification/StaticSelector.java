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
        if (kind == Kind.CONSTRUCTOR && !"<init>".equals(memberName)) {
            throw new IllegalArgumentException("constructor selector memberName must be <init>");
        }
        if (requiredAccessFlags < 0 || forbiddenAccessFlags < 0) {
            throw new IllegalArgumentException("access flags must not be negative");
        }
        if ((requiredAccessFlags & forbiddenAccessFlags) != 0) {
            throw new IllegalArgumentException("required and forbidden access flags must not overlap");
        }
    }

    /**
     * Selector for a class, using the alias as its mapping id.
     *
     * @param alias stable alias callers resolve this selector by
     * @param ownerInternalName JVM internal name of the declaring class
     * @return the exact selector
     */
    public static StaticSelector classSelector(final String alias, final String ownerInternalName) {
        return classSelector(alias, alias, ownerInternalName);
    }

    /**
     * Selector for a class.
     *
     * @param mappingId mapping-pack identity this selector came from
     * @param alias stable alias callers resolve this selector by
     * @param ownerInternalName JVM internal name of the declaring class
     * @return the exact selector
     */
    public static StaticSelector classSelector(
        final String mappingId,
        final String alias,
        final String ownerInternalName
    ) {
        return new StaticSelector(mappingId, alias, Kind.CLASS, ownerInternalName, "", "", 0, 0);
    }

    /**
     * Selector for an instance method, with no required access flags.
     *
     * @param alias stable alias callers resolve this selector by
     * @param ownerInternalName JVM internal name of the declaring class
     * @param memberName the member's exact name in the host artifact
     * @param descriptor the member's exact JVM descriptor
     * @return the exact selector
     */
    public static StaticSelector method(
        final String alias,
        final String ownerInternalName,
        final String memberName,
        final String descriptor
    ) {
        return method(alias, alias, ownerInternalName, memberName, descriptor, 0);
    }

    /**
     * Selector for an instance method. Static members are rejected.
     *
     * @param alias stable alias callers resolve this selector by
     * @param ownerInternalName JVM internal name of the declaring class
     * @param memberName the member's exact name in the host artifact
     * @param descriptor the member's exact JVM descriptor
     * @param requiredAccessFlags access flags the member must carry
     * @return the exact selector
     */
    public static StaticSelector method(
        final String alias,
        final String ownerInternalName,
        final String memberName,
        final String descriptor,
        final int requiredAccessFlags
    ) {
        return method(alias, alias, ownerInternalName, memberName, descriptor, requiredAccessFlags);
    }

    /**
     * Selector for an instance method with an explicit mapping id. Static members are
     * rejected, so an instance selector can never silently bind a static member.
     *
     * @param mappingId mapping-pack identity this selector came from
     * @param alias stable alias callers resolve this selector by
     * @param ownerInternalName JVM internal name of the declaring class
     * @param memberName the member's exact name in the host artifact
     * @param descriptor the member's exact JVM descriptor
     * @param requiredAccessFlags access flags the member must carry
     * @return the exact selector
     */
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

    /**
     * Selector for a static method, using the alias as its mapping id.
     *
     * @param alias stable alias callers resolve this selector by
     * @param ownerInternalName JVM internal name of the declaring class
     * @param memberName the member's exact name in the host artifact
     * @param descriptor the member's exact JVM descriptor
     * @param requiredAccessFlags access flags the member must carry
     * @return the exact selector
     */
    public static StaticSelector staticMethod(
        final String alias,
        final String ownerInternalName,
        final String memberName,
        final String descriptor,
        final int requiredAccessFlags
    ) {
        return staticMethod(alias, alias, ownerInternalName, memberName, descriptor, requiredAccessFlags);
    }

    /**
     * Selector for a static method. {@code ACC_STATIC} is required, not merely allowed.
     *
     * @param mappingId mapping-pack identity this selector came from
     * @param alias stable alias callers resolve this selector by
     * @param ownerInternalName JVM internal name of the declaring class
     * @param memberName the member's exact name in the host artifact
     * @param descriptor the member's exact JVM descriptor
     * @param requiredAccessFlags access flags the member must carry
     * @return the exact selector
     */
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

    /**
     * Selector for a constructor.
     *
     * @param alias stable alias callers resolve this selector by
     * @param ownerInternalName JVM internal name of the declaring class
     * @param descriptor the member's exact JVM descriptor
     * @param requiredAccessFlags access flags the member must carry
     * @return the exact selector
     */
    public static StaticSelector constructor(
        final String alias,
        final String ownerInternalName,
        final String descriptor,
        final int requiredAccessFlags
    ) {
        return new StaticSelector(
            alias,
            alias,
            Kind.CONSTRUCTOR,
            ownerInternalName,
            "<init>",
            descriptor,
            requiredAccessFlags,
            ACCESS_STATIC
        );
    }

    /**
     * Selector for a field.
     *
     * @param alias stable alias callers resolve this selector by
     * @param ownerInternalName JVM internal name of the declaring class
     * @param memberName the member's exact name in the host artifact
     * @param descriptor the member's exact JVM descriptor
     * @param requiredAccessFlags access flags the member must carry
     * @return the exact selector
     */
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
        CONSTRUCTOR,
        METHOD,
        FIELD
    }
}
