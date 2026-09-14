package dev.turboism.ui.toolbar;

import dev.turboism.sdk.plugin.Registration;

import java.util.function.Consumer;

/** Native-independent operations required by the horizontal-toolbar provider. */
public interface HorizontalToolbarHostOperations {

    /**
     * Attaches the horizontal toolbar contribution and wires its buttons.
     *
     * @param descriptor the contribution to install
     * @param click receives a button's action id on activation
     * @return a registration that removes the toolbar when disposed
     */
    Registration attach(
        HorizontalToolbarContributionDescriptor descriptor,
        Consumer<String> click
    );

    /**
     * Registers a callback fired when the host rebuilds the toolbar area.
     *
     * @param reconcile re-attaches the contribution after a rebuild
     * @return a registration removing the callback when disposed; the default is a no-op
     *         registration for hosts that never rebuild
     */
    default Registration onRebuild(final Runnable reconcile) {
        return () -> { };
    }
}
