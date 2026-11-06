package dev.turboism.compatibility.policy;

import dev.turboism.sdk.CubismEditor;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Host-independent {@code @CubismEditor} admission rules.
 *
 * <p>The policy validates and expands already-collected annotation declarations and decides
 * whether a host-declared version satisfies them. It never discovers annotations by reflection;
 * the runtime passes the {@link CubismEditor} instances it collected from SDK interfaces. That
 * keeps every version decision offline, deterministic and testable without a Cubism host while
 * the adapter keeps any reflection over loaded interface types.</p>
 *
 * <p>Exact {@code value} lists must name reviewed versions. {@code from}/{@code to} compare
 * numerically against any well-formed {@code x.y.z} version, so an open range can cover an
 * unreviewed future release. {@code exclude} always removes the named exact version. Type,
 * inherited-type and method declarations are intersected: one denial fails the method.</p>
 */
public final class CubismEditorAvailabilityPolicy {

    private static final List<String> REVIEWED_VERSIONS = ReviewedReleaseTable.versions();
    private static final Set<String> REVIEWED_VERSION_SET = Set.copyOf(REVIEWED_VERSIONS);

    private CubismEditorAvailabilityPolicy() {}

    /**
     * Returns every exact reviewed Editor version, oldest first.
     *
     * @return the immutable reviewed version list
     */
    public static List<String> reviewedVersions() {
        return REVIEWED_VERSIONS;
    }

    /**
     * Whether a declaration imposes any restriction.
     *
     * <p>A bare {@code @CubismEditor} (no {@code value}, {@code from}, {@code to} or
     * {@code exclude}) documents that the surface was audited for all reviewed versions
     * without imposing a version constraint or requiring a verified host on carrier methods.</p>
     *
     * @param declaration the annotation declaration to inspect
     * @return {@code true} only when a version constraint is declared
     */
    public static boolean isRestrictive(final CubismEditor declaration) {
        return declaration != null
                && (declaration.value().length > 0
                        || !declaration.from().isEmpty()
                        || !declaration.to().isEmpty()
                        || declaration.exclude().length > 0);
    }

    /**
     * Validates and resolves the collected declarations of one SDK surface.
     *
     * <p>Non-restrictive declarations are ignored. A surface without any restrictive
     * declaration resolves to unrestricted with no supported-version diagnostics. The returned
     * supported versions are the reviewed versions permitted by every declaration, in reviewed
     * order.</p>
     *
     * @param declarations the collected declaration instances, in hierarchy order
     * @param apiId the API identity used in validation diagnostics
     * @return the immutable resolution for that surface
     * @throws NullPointerException when {@code declarations}, an element or {@code apiId} is
     *     {@code null}
     * @throws IllegalStateException when a declaration is malformed or names an unreviewed
     *     exact version
     */
    public static Resolution resolve(final List<CubismEditor> declarations, final String apiId) {
        Objects.requireNonNull(declarations, "declarations");
        Objects.requireNonNull(apiId, "apiId");
        final ArrayList<CubismEditor> restrictive = new ArrayList<>();
        for (final CubismEditor declaration : declarations) {
            Objects.requireNonNull(declaration, "declaration");
            if (isRestrictive(declaration)) {
                restrictive.add(declaration);
            }
        }
        if (restrictive.isEmpty()) {
            return new Resolution(false, List.of(), List.of());
        }
        final LinkedHashSet<String> supported = new LinkedHashSet<>(REVIEWED_VERSIONS);
        for (final CubismEditor declaration : restrictive) {
            supported.retainAll(expand(declaration, apiId));
        }
        return new Resolution(
                true, REVIEWED_VERSIONS.stream().filter(supported::contains).toList(), restrictive);
    }

    private static Set<String> expand(final CubismEditor declaration, final String apiId) {
        final List<String> exact = List.of(declaration.value());
        final List<String> excluded = List.of(declaration.exclude());
        final String from = declaration.from();
        final String to = declaration.to();
        final boolean hasRange = !from.isEmpty() || !to.isEmpty();
        if ((!exact.isEmpty() && hasRange)
                || hasDuplicates(exact)
                || hasDuplicates(excluded)
                || exact.stream().anyMatch(version -> !REVIEWED_VERSION_SET.contains(version))
                || exact.stream().anyMatch(version -> !isExactVersion(version))
                || excluded.stream().anyMatch(version -> !isExactVersion(version))
                || (!from.isEmpty() && !isExactVersion(from))
                || (!to.isEmpty() && !isExactVersion(to))
                || (!from.isEmpty() && !to.isEmpty() && compareVersions(from, to) > 0)) {
            throw new IllegalStateException("Invalid @CubismEditor declaration on " + apiId);
        }
        final LinkedHashSet<String> expanded =
                exact.isEmpty() ? new LinkedHashSet<>(REVIEWED_VERSIONS) : new LinkedHashSet<>(exact);
        if (hasRange) {
            expanded.removeIf(version -> (!from.isEmpty() && compareVersions(version, from) < 0)
                    || (!to.isEmpty() && compareVersions(version, to) > 0));
        }
        expanded.removeAll(excluded);
        return Set.copyOf(expanded);
    }

    private static boolean hasDuplicates(final List<String> versions) {
        return new LinkedHashSet<>(versions).size() != versions.size();
    }

    private static boolean isExactVersion(final String version) {
        if (version == null || version.isEmpty()) {
            return false;
        }
        final String[] components = version.split("\\.", -1);
        if (components.length != 3) {
            return false;
        }
        for (final String component : components) {
            if (component.isEmpty()) {
                return false;
            }
            for (int index = 0; index < component.length(); index++) {
                if (!Character.isDigit(component.charAt(index))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static int compareVersions(final String left, final String right) {
        final String[] leftComponents = left.split("\\.", -1);
        final String[] rightComponents = right.split("\\.", -1);
        for (int index = 0; index < 3; index++) {
            final int compared =
                    new BigInteger(leftComponents[index]).compareTo(new BigInteger(rightComponents[index]));
            if (compared != 0) {
                return compared;
            }
        }
        return 0;
    }

    /**
     * One surface's resolved availability.
     *
     * @param restricted whether any type, inherited-type or method declaration applies
     * @param supportedVersions the reviewed-version expansion for diagnostics
     * @param declarations the collected restrictive declarations in hierarchy order
     */
    public record Resolution(boolean restricted, List<String> supportedVersions, List<CubismEditor> declarations) {
        /**
         * Freezes the resolution lists.
         *
         * @throws NullPointerException when a component is {@code null}
         */
        public Resolution {
            supportedVersions = List.copyOf(Objects.requireNonNull(supportedVersions, "supportedVersions"));
            declarations = List.copyOf(Objects.requireNonNull(declarations, "declarations"));
        }

        /**
         * Evaluates every collected declaration against the host's own declared version.
         *
         * @param realVersion the version the host declared at probe time
         * @return {@code true} when the declaration set permits that version
         */
        public boolean permits(final String realVersion) {
            if (!restricted || realVersion == null || !isExactVersion(realVersion)) {
                return !restricted;
            }
            for (final CubismEditor declaration : declarations) {
                if (!permits(declaration, realVersion)) {
                    return false;
                }
            }
            return true;
        }

        private static boolean permits(final CubismEditor declaration, final String realVersion) {
            if (List.of(declaration.exclude()).contains(realVersion)) {
                return false;
            }
            final List<String> exact = List.of(declaration.value());
            if (!exact.isEmpty()) {
                return exact.contains(realVersion);
            }
            final String from = declaration.from();
            final String to = declaration.to();
            if (!from.isEmpty() && compareVersions(realVersion, from) < 0) {
                return false;
            }
            return to.isEmpty() || compareVersions(realVersion, to) <= 0;
        }
    }
}
