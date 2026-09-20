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
     * @return a provider for Cubism 5.2.03, 5.3.02, or 5.3.03, whichever the resolver is
     *     admitted for
     * @throws IllegalArgumentException if the resolver is admitted for neither
     */
    public static WorkspaceHostProvider create(final VerifiedMemberResolver resolver) {
        if (WorkspaceControlAdmission.authorizes5203(resolver)) {
            return versioned5203Provider(resolver);
        }
        if (WorkspaceControlAdmission.authorizes5302(resolver)
            || WorkspaceControlAdmission.authorizes5303(resolver)) {
            return new Cubism53WorkspaceHostProvider(resolver);
        }
        throw new IllegalArgumentException("resolver is not admitted for workspace control");
    }

    /*
     * The 5.2.03 provider compiles in the cubism5203 source set, so the factory
     * reaches it by name rather than importing it. Resolution still happens
     * only after the 5.2.03 admission check above.
     */
    private static WorkspaceHostProvider versioned5203Provider(
        final VerifiedMemberResolver resolver
    ) {
        try {
            final var constructor = Class
                .forName("dev.turboism.ui.workspace.Cubism52WorkspaceHostProvider")
                .getDeclaredConstructor(VerifiedMemberResolver.class);
            constructor.setAccessible(true);
            return (WorkspaceHostProvider) constructor.newInstance(resolver);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(
                "Cubism 5.2.03 workspace provider is unavailable",
                failure
            );
        }
    }

    private WorkspaceHostProviderFactory() { }
}
