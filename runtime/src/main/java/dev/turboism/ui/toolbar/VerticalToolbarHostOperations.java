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

    default Registration onRebuild(final Runnable reconcile) {
        return () -> { };
    }

    default Optional<String> unavailableDiagnostic() {
        return Optional.empty();
    }
}
