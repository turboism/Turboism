package dev.turboism.ui.workspace;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.ui.workspace.WorkspaceId;
import dev.turboism.sdk.ui.workspace.WorkspaceOperationResult;
import dev.turboism.sdk.ui.workspace.WorkspaceStatus;

/**
 * Workspace host provider admitted by exact-version data in {@link WorkspaceControlAdmission}
 * rather than by a version-bearing type name.
 */
final class VerifiedWorkspaceHostProvider implements WorkspaceHostProvider {
    private final WorkspaceReflectionEngine engine;

    VerifiedWorkspaceHostProvider(final VerifiedMemberResolver resolver) {
        if (!WorkspaceControlAdmission.authorizes(resolver)) {
            throw new IllegalArgumentException("resolver is not admitted for exact Cubism workspace control");
        }
        engine = new WorkspaceReflectionEngine(resolver);
    }

    @Override public WorkspaceStatus readStatus() { return engine.readStatus(); }
    @Override public WorkspaceOperationResult.Outcome switchTo(WorkspaceId id) { return engine.switchTo(id); }
    @Override public WorkspaceOperationResult.Outcome updateDefault() { return engine.updateDefault(); }
    @Override public WorkspaceOperationResult.Outcome resetToDefault() { return engine.resetToDefault(); }
}
