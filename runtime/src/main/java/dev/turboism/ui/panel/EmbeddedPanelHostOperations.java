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

    default void bindHostGeneration(final long generation) {
    }

    /** Marks the host binding as no longer usable; queued host operations must abort. */
    default void invalidateHost() {
    }

    default Registration bindPanelTabMenus(final PanelTabMenuCoordinator coordinator) {
        return () -> { };
    }

    interface PanelHandle extends Registration {
        void activate();

        /** Hosts that support it float the panel into a small window. */
        default void floatPanel() {
        }

        /**
         * Replaces the panel content in place, keeping the installed palette
         * (and any floating window) alive. No-op on hosts without in-place
         * content updates; the provider then rebuilds the panel instead.
         */
        default void updateContent(final EmbeddedPanelContributionDescriptor descriptor) {
        }
    }
}
