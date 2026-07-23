package dev.turboism.ui.toolbar;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;

import java.util.Optional;

/** Native-independent operations required by the main-toolbar provider. */
public interface MainToolbarHostOperations {

    Optional<AnchorHandle> anchor(MainToolbarRegistry.Anchor anchor);

    Registration addButton(
        MainToolbarContributionDescriptor contribution,
        Optional<AnchorHandle> anchor,
        Runnable action
    );

    default Registration onRebuild(final Runnable reconcile) {
        return () -> { };
    }

    default Registration onAppearanceChanged(final Runnable refresh) {
        return () -> { };
    }

    interface AnchorHandle {
    }
}
