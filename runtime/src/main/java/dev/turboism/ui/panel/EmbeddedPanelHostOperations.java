package dev.turboism.ui.panel;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.action.UiActionEvent;

import java.util.Optional;
import java.util.function.BiConsumer;

/** Version-specific native operations required by the embedded-panel provider. */
public interface EmbeddedPanelHostOperations {

    PanelHandle addPanel(
        EmbeddedPanelContributionDescriptor contribution,
        BiConsumer<String, Optional<UiActionEvent>> action
    );

    Registration onRebuild(Runnable reconcile);

    default Registration bindPanelTabMenus(final PanelTabMenuCoordinator coordinator) {
        return () -> { };
    }

    interface PanelHandle extends Registration {
        void activate();
    }
}
