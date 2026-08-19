package dev.turboism.ui.workspace;

import dev.turboism.mapping.verification.VerifiedMemberResolver;

public final class WorkspaceHostProviderFactory {
    public static WorkspaceHostProvider create(final VerifiedMemberResolver resolver) {
        if (WorkspaceControlAdmission.authorizes5203(resolver)) {
            return new Cubism52WorkspaceHostProvider(resolver);
        }
        if (WorkspaceControlAdmission.authorizes5302(resolver)) {
            return new Cubism53WorkspaceHostProvider(resolver);
        }
        throw new IllegalArgumentException("resolver is not admitted for workspace control");
    }

    private WorkspaceHostProviderFactory() { }
}
