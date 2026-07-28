package dev.turboism.ui.panel;

import dev.turboism.sdk.plugin.Registration;

/** Version-specific native operations required by the embedded-panel provider. */
public interface EmbeddedPanelHostOperations {

    PanelHandle addPanel(EmbeddedPanelContributionDescriptor contribution);

    Registration onRebuild(Runnable reconcile);

    interface PanelHandle extends Registration {
        void activate();
    }
}
