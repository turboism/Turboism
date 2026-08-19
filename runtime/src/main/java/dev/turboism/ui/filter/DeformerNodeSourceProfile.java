package dev.turboism.ui.filter;

import dev.turboism.mapping.verification.VerifiedMemberResolver;

import java.util.Objects;
import java.util.Optional;

/**
 * Exact-version node→source accessor profile for the deformer tree filter.
 *
 * <p>The generic tree-table node class {@code com.live2d.ui.treeTable.c} exposes its
 * parameter-controllable source through a version-specific zero-argument accessor, both
 * pinned with descriptor {@code ()Ljava/lang/Object;} by the exact-host
 * {@code ui-control-appearance} verification records, alias
 * {@code cubism.ui-control-appearance.part.node-source}:</p>
 *
 * <ul>
 *   <li>Cubism 5.2.03 → {@code h()} — {@code cubism-ref/verification/cubism-5.2-ui-control-appearance.json},
 *       mappingId {@code cubism.mapping.5_2.ui_control_appearance.method.node_source};</li>
 *   <li>Cubism 5.3.02 → {@code i()} — {@code cubism-ref/verification/cubism-5.3.02-ui-control-appearance.json},
 *       mappingId {@code cubism.mapping.5_3_02.ui_control_appearance.method.node_source}.</li>
 * </ul>
 *
 * <p>The version is taken from the bound {@link VerifiedMemberResolver#cubismVersion()}.
 * The production Editor-model resolver reports {@code 5.2.0} for the exact 5.2.03 patch
 * artifact ({@code EditorModelVerificationManifest.CUBISM_VERSION_5_2_03}) and {@code 5.3.02} for
 * the 5.3.02 artifact; the 5.2 control-appearance record spells the same artifact
 * {@code 5.2.03}. Both spellings route to accessor {@code h}; every other version fails
 * closed (no profile, deformer filtering disabled).</p>
 */
record DeformerNodeSourceProfile(String cubismVersion, String accessorName) {

    /** Exact generic tree-table node class pinned by the ui-control-appearance records. */
    static final String OWNER_BINARY_NAME = "com.live2d.ui.treeTable.c";

    /** Pinned accessor descriptor on {@link #OWNER_BINARY_NAME}. */
    static final String ACCESSOR_DESCRIPTOR = "()Ljava/lang/Object;";

    private static final String ACCESSOR_52 = "h";
    private static final String ACCESSOR_53 = "i";

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
        if ("5.2.0".equals(cubismVersion) || "5.2.03".equals(cubismVersion)) {
            return Optional.of(new DeformerNodeSourceProfile(cubismVersion, ACCESSOR_52));
        }
        if ("5.3.02".equals(cubismVersion)) {
            return Optional.of(new DeformerNodeSourceProfile(cubismVersion, ACCESSOR_53));
        }
        return Optional.empty();
    }
}
