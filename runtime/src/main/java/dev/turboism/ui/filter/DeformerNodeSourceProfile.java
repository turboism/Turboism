package dev.turboism.ui.filter;

import dev.turboism.internal.ReviewedVersions;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import java.util.Objects;
import java.util.Optional;

/**
 * Exact-version node→source accessor profile for the deformer tree filter.
 *
 * <p>The generic tree-table node class {@code com.live2d.ui.treeTable.c} exposes its
 * parameter-controllable source through a version-specific zero-argument accessor, all
 * pinned with descriptor {@code ()Ljava/lang/Object;} by the exact-host
 * {@code ui-control-appearance} verification records, alias
 * {@code cubism.ui-control-appearance.part.node-source}:</p>
 *
 * <ul>
 *   <li>Cubism 5.0.00–5.1.01 and 5.2.x → {@code h()} — ui-control-appearance records, alias
 *       {@code cubism.ui-control-appearance.part.node-source};</li>
 *   <li>Cubism 5.1.02–5.1.03 → {@code j()} — same alias on the 5.1.02–5.1.03 records;</li>
 *   <li>Cubism 5.3.x → {@code i()} — same alias on the 5.3 records.</li>
 * </ul>
 *
 * <p>The version is taken from the bound {@link VerifiedMemberResolver#cubismVersion()}.
 * Versions without a record-pinned accessor fail closed (no profile, deformer filtering disabled).</p>
 */
record DeformerNodeSourceProfile(String cubismVersion, String accessorName) {

    /** Exact generic tree-table node class pinned by the ui-control-appearance records. */
    static final String OWNER_BINARY_NAME = "com.live2d.ui.treeTable.c";

    /** Pinned accessor descriptor on {@link #OWNER_BINARY_NAME}. */
    static final String ACCESSOR_DESCRIPTOR = "()Ljava/lang/Object;";

    private static final String ACCESSOR_52 = "h";
    private static final String ACCESSOR_5102 = "j";
    private static final String ACCESSOR_53 = "i";

    private static final java.util.Set<String> VERSIONS_50_5101 = ReviewedVersions.between("5.0.00", "5.1.01");
    private static final java.util.Set<String> VERSIONS_5102_5103 = ReviewedVersions.between("5.1.02", "5.1.03");
    private static final java.util.Set<String> VERSIONS_52 = ReviewedVersions.line("5.2");
    private static final java.util.Set<String> VERSIONS_53 = ReviewedVersions.line("5.3");

    DeformerNodeSourceProfile {
        Objects.requireNonNull(cubismVersion, "cubismVersion");
        Objects.requireNonNull(accessorName, "accessorName");
    }

    /** Resolves the profile from the attested exact version of a bound resolver. */
    static Optional<DeformerNodeSourceProfile> forResolver(final VerifiedMemberResolver resolver) {
        return forVersion(resolver.cubismVersion());
    }

    /** Exact-version routing; unknown versions yield no profile (fail closed). */
    static Optional<DeformerNodeSourceProfile> forVersion(final String cubismVersion) {
        if (cubismVersion == null) {
            return Optional.empty();
        }
        if (VERSIONS_50_5101.contains(cubismVersion) || VERSIONS_52.contains(cubismVersion)) {
            return Optional.of(new DeformerNodeSourceProfile(cubismVersion, ACCESSOR_52));
        }
        if (VERSIONS_5102_5103.contains(cubismVersion)) {
            return Optional.of(new DeformerNodeSourceProfile(cubismVersion, ACCESSOR_5102));
        }
        if (VERSIONS_53.contains(cubismVersion)) {
            return Optional.of(new DeformerNodeSourceProfile(cubismVersion, ACCESSOR_53));
        }
        return Optional.empty();
    }
}
