package dev.turboism.ui.toolbar;

import dev.turboism.sdk.plugin.Registration;

import java.util.Optional;
import java.util.function.Consumer;

/** Native-independent operations required by the vertical-toolbar provider. */
public interface VerticalToolbarHostOperations {

    /**
     * Attaches a vertical icon strip to the main frame and wires each button.
     *
     * @param descriptor contribution descriptor
     * @param click       button action-id -> click callback
     */
    Registration attach(
        VerticalToolbarContributionDescriptor descriptor,
        Consumer<String> click
    );

    /**
     * Registers a callback fired when the host rebuilds the toolbar area.
     *
     * @param reconcile re-attaches the strip after a rebuild
     * @return a registration removing the callback when disposed; the default is a no-op
     *         registration for hosts that never rebuild
     */
    default Registration onRebuild(final Runnable reconcile) {
        return () -> { };
    }

    /**
     * @return a human-readable reason the vertical toolbar is unavailable on this host, or
     *         empty when it is available
     */
    default Optional<String> unavailableDiagnostic() {
        return Optional.empty();
    }
}
