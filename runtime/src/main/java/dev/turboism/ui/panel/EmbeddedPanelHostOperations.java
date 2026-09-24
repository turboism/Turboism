package dev.turboism.ui.panel;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.action.UiActionEvent;

import java.util.Optional;
import java.util.function.BiConsumer;

/** Version-specific native operations required by the embedded-panel provider. */
public interface EmbeddedPanelHostOperations {

    /**
     * Installs one embedded panel natively.
     *
     * @param contribution the resolved panel contribution
     * @param action receives the action id and optional UI event when a panel action fires
     * @return a handle controlling the installed panel; disposing it removes the panel
     */
    PanelHandle addPanel(
        EmbeddedPanelContributionDescriptor contribution,
        BiConsumer<String, Optional<UiActionEvent>> action
    );

    /**
     * Registers a callback fired whenever the host rebuilds its panel area.
     *
     * @param reconcile re-installs the runtime-owned panels after a rebuild
     * @return a registration that removes the callback when disposed
     */
    Registration onRebuild(Runnable reconcile);

    /**
     * Binds the verified host generation these operations belong to; later generations must
     * re-bind before queued work runs. The default ignores the generation for hosts that do
     * not track it.
     */
    default void bindHostGeneration(final long generation) {
    }

    /** Marks the host binding as no longer usable; queued host operations must abort. */
    default void invalidateHost() {
    }

    /**
     * Connects the panel tab-menu coordinator to the host's tab menus.
     *
     * @param coordinator the coordinator to wire in
     * @return a registration that disconnects the coordinator when disposed; the default is
     *         a no-op registration for hosts without panel tab menus
     */
    default Registration bindPanelTabMenus(final PanelTabMenuCoordinator coordinator) {
        return () -> { };
    }

    /** Control handle for one installed embedded panel. */
    interface PanelHandle extends Registration {
        /** Brings the panel's tab to the front. */
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
