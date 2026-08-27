package dev.turboism.ui.workspace;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.ui.workspace.WorkspaceId;
import dev.turboism.sdk.ui.workspace.WorkspaceOperationResult;
import dev.turboism.sdk.ui.workspace.WorkspaceStatus;

final class Cubism53WorkspaceHostProvider implements WorkspaceHostProvider {
    private final WorkspaceReflectionEngine engine;

    Cubism53WorkspaceHostProvider(final VerifiedMemberResolver resolver) {
        if (!WorkspaceControlAdmission.authorizes5302(resolver)
            && !WorkspaceControlAdmission.authorizes5303(resolver)) {
            throw new IllegalArgumentException("resolver is not admitted for exact Cubism 5.3 workspace control");
        }
        engine = new WorkspaceReflectionEngine(resolver);
    }

    @Override public WorkspaceStatus readStatus() { return engine.readStatus(); }
    @Override public WorkspaceOperationResult.Outcome switchTo(WorkspaceId id) { return engine.switchTo(id); }
    @Override public WorkspaceOperationResult.Outcome updateDefault() { return engine.updateDefault(); }
    @Override public WorkspaceOperationResult.Outcome resetToDefault() { return engine.resetToDefault(); }
}
