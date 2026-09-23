package dev.turboism.ui.toolbar;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;

import java.util.Optional;

/** Native-independent operations required by the main-toolbar provider. */
public interface MainToolbarHostOperations {

    /**
     * Resolves a declared anchor to a host-side position.
     *
     * @param anchor the anchor the contribution declared
     * @return the resolved host position, or empty when the host has no such anchor —
     *         callers then install without one
     */
    Optional<AnchorHandle> anchor(MainToolbarRegistry.Anchor anchor);

    /**
     * Installs one toolbar button.
     *
     * @param contribution the contribution to install
     * @param anchor the resolved anchor position, or empty to use the default placement
     * @param action runs when the button is activated
     * @return a registration that removes the button when disposed
     */
    Registration addButton(
        MainToolbarContributionDescriptor contribution,
        Optional<AnchorHandle> anchor,
        Runnable action
    );

    /**
     * Registers a callback fired when the host rebuilds the main toolbar.
     *
     * @param reconcile re-installs the buttons after a rebuild
     * @return a registration removing the callback when disposed; the default is a no-op
     *         registration for hosts that never rebuild
     */
    default Registration onRebuild(final Runnable reconcile) {
        return () -> { };
    }

    /**
     * Registers a callback fired when the host appearance changes.
     *
     * @param refresh re-applies button styling after a theme change
     * @return a registration removing the callback when disposed; the default is a no-op
     *         registration for hosts that never change appearance
     */
    default Registration onAppearanceChanged(final Runnable refresh) {
        return () -> { };
    }

    /** Opaque host-side anchor position resolved by {@link #anchor}. */
    interface AnchorHandle {
    }
}
