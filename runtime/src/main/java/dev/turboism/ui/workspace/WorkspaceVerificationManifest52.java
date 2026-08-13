package dev.turboism.ui.workspace;

import dev.turboism.mapping.verification.VerifiedMemberResolver;

import java.util.Set;

final class WorkspaceVerificationManifest52 {
    static final String CUBISM_VERSION = "5.2.03";
    static final String ADAPTER_SLICE_ID = "adapter.workspace.control.v5_2";
    static final String CAPABILITY_ID = "cubism.workspace.control";
    static final Set<String> REQUIRED_ALIASES = WorkspaceReflectionEngine.REQUIRED_ALIASES;

    static boolean authorizes(final VerifiedMemberResolver resolver) {
        return resolver != null
            && resolver.isExactCubismVersion(CUBISM_VERSION)
            && resolver.authorizes(ADAPTER_SLICE_ID, Set.of(CAPABILITY_ID), REQUIRED_ALIASES);
    }

    private WorkspaceVerificationManifest52() { }
}
