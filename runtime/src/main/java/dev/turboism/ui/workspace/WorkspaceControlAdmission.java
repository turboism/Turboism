package dev.turboism.ui.workspace;

import dev.turboism.mapping.verification.VerifiedMemberResolver;

import java.util.Set;

/**
 * Exact-version admission for the Cubism workspace-control slice.
 *
 * <p>All supported Cubism versions are declared here as data. A resolver is admitted only when
 * it reports one exact reviewed version <em>and</em> authorises that version's adapter slice,
 * capability and full alias set; anything else fails closed.</p>
 */
final class WorkspaceControlAdmission {

    /** Cubism version reported by a resolver admitted for the 5.2.03 workspace slice. */
    static final String CUBISM_VERSION_5_2_03 = "5.2.03";

    /** Cubism version reported by a resolver admitted for the 5.3.02 workspace slice. */
    static final String CUBISM_VERSION_5_3_02 = "5.3.02";

    /** Cubism version reported by a resolver admitted for the 5.3.03 workspace slice. */
    static final String CUBISM_VERSION_5_3_03 = "5.3.03";

    /** Adapter slice identity reviewed for Cubism 5.2.03 workspace control. */
    static final String ADAPTER_SLICE_ID_5_2_03 = "adapter.workspace.control.v5_2";

    /** Adapter slice identity reviewed for Cubism 5.3.02 workspace control. */
    static final String ADAPTER_SLICE_ID_5_3_02 = "adapter.workspace.control.v5_3";

    /** Capability both reviewed versions expose. */
    static final String CAPABILITY_ID = "cubism.workspace.control";

    /** Selector aliases both reviewed versions must authorise in full. */
    static final Set<String> REQUIRED_ALIASES = WorkspaceReflectionEngine.REQUIRED_ALIASES;

    /**
     * Returns whether a resolver is admitted for the reviewed Cubism 5.2.03 workspace slice.
     *
     * @param resolver the resolver to test, may be null
     * @return {@code true} only on an exact version and full alias authorisation match
     */
    static boolean authorizes5203(final VerifiedMemberResolver resolver) {
        return authorizes(resolver, CUBISM_VERSION_5_2_03, ADAPTER_SLICE_ID_5_2_03);
    }

    /**
     * Returns whether a resolver is admitted for the reviewed Cubism 5.3.02 workspace slice.
     *
     * @param resolver the resolver to test, may be null
     * @return {@code true} only on an exact version and full alias authorisation match
     */
    static boolean authorizes5302(final VerifiedMemberResolver resolver) {
        return authorizes(resolver, CUBISM_VERSION_5_3_02, ADAPTER_SLICE_ID_5_3_02);
    }

    /**
     * Returns whether a resolver is admitted for the reviewed Cubism 5.3.03 workspace slice.
     *
     * @param resolver the resolver to test, may be null
     * @return {@code true} only on an exact version and full alias authorisation match
     */
    static boolean authorizes5303(final VerifiedMemberResolver resolver) {
        return authorizes(resolver, CUBISM_VERSION_5_3_03, ADAPTER_SLICE_ID_5_3_02);
    }

    private static boolean authorizes(
        final VerifiedMemberResolver resolver,
        final String cubismVersion,
        final String adapterSliceId
    ) {
        return resolver != null
            && resolver.isExactCubismVersion(cubismVersion)
            && resolver.authorizes(adapterSliceId, Set.of(CAPABILITY_ID), REQUIRED_ALIASES);
    }

    private WorkspaceControlAdmission() { }
}
