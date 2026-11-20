package dev.turboism.ui.workspace;

import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import java.util.Set;

/**
 * Admission gate for the Cubism workspace-control slice.
 *
 * <p>A resolver is admitted only when it targets a reviewed Cubism version and authorizes one
 * complete workspace-control adapter slice with its full capability and alias set.</p>
 */
final class WorkspaceControlAdmission {

    /** Adapter slice identity for the legacy v5_2 workspace-control verification record. */
    static final String ADAPTER_SLICE_ID_V5_2 = "adapter.workspace.control.v5_2";

    /** Adapter slice identity for standard workspace-control verification records. */
    static final String ADAPTER_SLICE_ID_V5_3 = "adapter.workspace.control.v5_3";

    private static final Set<String> ADAPTER_SLICE_IDS = Set.of(ADAPTER_SLICE_ID_V5_2, ADAPTER_SLICE_ID_V5_3);

    /** Capability both workspace-control slices expose. */
    static final String CAPABILITY_ID = "cubism.workspace.control";

    /** Selector aliases an admitted workspace slice must authorize in full. */
    static final Set<String> REQUIRED_ALIASES = WorkspaceReflectionEngine.REQUIRED_ALIASES;

    /**
     * Returns whether a resolver is admitted for any reviewed workspace-control slice.
     *
     * @param resolver the resolver to test, may be null
     * @return {@code true} only when one reviewed version authorizes a complete workspace slice
     */
    static boolean authorizes(final VerifiedMemberResolver resolver) {
        if (resolver == null) {
            return false;
        }
        if (!ReviewedHostArtifacts.reviewedCubismVersions().contains(resolver.admittedCubismVersion())) {
            return false;
        }
        final Set<String> capabilities = Set.of(CAPABILITY_ID);
        return ADAPTER_SLICE_IDS.stream()
                .anyMatch(sliceId -> resolver.authorizes(sliceId, capabilities, REQUIRED_ALIASES));
    }

    private WorkspaceControlAdmission() {}
}
