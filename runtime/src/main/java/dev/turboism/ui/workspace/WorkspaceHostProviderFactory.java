package dev.turboism.ui.workspace;

import dev.turboism.mapping.verification.VerifiedMemberResolver;

/**
 * Chooses the workspace provider matching the admitted host version. Not
 * instantiable.
 */
public final class WorkspaceHostProviderFactory {
    /**
     * Builds the provider for whichever host version the resolver is admitted
     * for. Admission is checked here rather than trusted, so an unrecognized
     * or unverified host yields no provider at all.
     *
     * @param resolver verified member resolver bound to the running host
     * @return a provider for Cubism 5.2.03 or 5.3.02, whichever the resolver is
     *     admitted for
     * @throws IllegalArgumentException if the resolver is admitted for neither
     */
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
