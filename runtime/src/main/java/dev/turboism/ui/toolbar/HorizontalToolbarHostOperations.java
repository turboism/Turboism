package dev.turboism.ui.toolbar;

import dev.turboism.sdk.plugin.Registration;

import java.util.function.Consumer;

/** Native-independent operations required by the horizontal-toolbar provider. */
public interface HorizontalToolbarHostOperations {

    Registration attach(
        HorizontalToolbarContributionDescriptor descriptor,
        Consumer<String> click
    );

    default Registration onRebuild(final Runnable reconcile) {
        return () -> { };
    }
}
