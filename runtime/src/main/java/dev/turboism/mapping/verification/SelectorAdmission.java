package dev.turboism.mapping.verification;

import dev.turboism.mapping.selector.MeshToolSessionSelectorContract;
import dev.turboism.mapping.selector.ModelingSelectionSelectorContract;

/**
 * Admission checks that need a resolved host and therefore cannot live with the selector contracts.
 *
 * <p>Selector contracts are version/capability data in
 * {@code dev.turboism.mapping.verification.selector}; a check that asks a
 * {@link VerifiedMemberResolver} whether the running host satisfies one of them needs the resolver
 * type, which lives here. Keeping these two methods here is what lets the selector package stay a
 * pure leaf instead of depending upward on the resolver.</p>
 */
public final class SelectorAdmission {

    private SelectorAdmission() {}

    /**
     * Returns true only when the resolver admits the complete mesh-tool session contract.
     *
     * @param resolver verified member resolver for the active connection
     * @return true when the exact version and every required alias are admitted
     */
    public static boolean authorizesMeshToolSession(final VerifiedMemberResolver resolver) {
        return resolver != null
                && MeshToolSessionSelectorContract.SUPPORTED_VERSIONS.contains(resolver.cubismVersion())
                && resolver.authorizesFeature(
                        MeshToolSessionSelectorContract.ADAPTER_SLICE_ID,
                        MeshToolSessionSelectorContract.CAPABILITY_ID,
                        MeshToolSessionSelectorContract.REQUIRED_ALIASES);
    }

    /**
     * Returns true only when the resolver admits the independent modeling point-selection contract.
     *
     * @param resolver verified member resolver for the active connection
     * @return true when the exact version and every required alias are admitted
     */
    public static boolean authorizesModelingSelection(final VerifiedMemberResolver resolver) {
        return resolver != null
                && ModelingSelectionSelectorContract.SUPPORTED_VERSIONS.contains(resolver.cubismVersion())
                && resolver.authorizesFeature(
                        ModelingSelectionSelectorContract.ADAPTER_SLICE_ID,
                        ModelingSelectionSelectorContract.CAPABILITY_ID,
                        ModelingSelectionSelectorContract.REQUIRED_ALIASES);
    }
}
