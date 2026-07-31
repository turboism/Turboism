package dev.turboism.plugin.historypanel;

import dev.turboism.plugin.historypanel.service.HistoryPanelService;
import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.TurboismPlugin;
import dev.turboism.sdk.ui.EmbeddedPanelContribution;
import dev.turboism.sdk.ui.EmbeddedPanelId;
import dev.turboism.sdk.ui.VerticalToolbarContribution;

import java.util.List;
import java.util.function.Consumer;

/**
 * Photoshop-style History pane plugin driven by a vertical tool-strip button.
 *
 * <p>Enable attaches a vertical History icon button to the Cubism main frame.
 * Clicking it toggles a small history pane beside the strip (embedded dock
 * panel); clicking again closes it. The pane is read-only; production move-to
 * is a fail-closed boundary.</p>
 */
public final class HistoryPanelPlugin implements TurboismPlugin {

    static final String TOGGLE_ACTION_ID = "history.panel.toggle";
    static final String STRIP_ID = "history.toolstrip";
    static final String STRIP_BUTTON_ID = "history";
    static final String PANEL_ID = "history.panel";

    private PluginContext context;
    private PluginLogger logger;
    private Registration panelRegistration;
    private boolean panelVisible;

    @Override
    public void init(final PluginContext context) {
        this.context = context;
        this.logger = context.logger();
        logger.info("HistoryPanelPlugin initialized");
    }

    @Override
    public void enable() {
        try {
            registerAction(TOGGLE_ACTION_ID, "History", ignored -> toggle());
            context.disposableScope().register(context.uiHost().contributeVerticalToolbar(
                new VerticalToolbarContribution(
                    STRIP_ID,
                    List.of(new VerticalToolbarContribution.ToolButton(
                        STRIP_BUTTON_ID,
                        "icons/history.png",
                        "History",
                        TOGGLE_ACTION_ID
                    ))
                )
            ));
        } catch (RuntimeException failure) {
            closeDisposableScopeQuietly();
            throw failure;
        }
        logger.info("HistoryPanelPlugin enabled: vertical History tool-strip enrolled in disposable scope");
    }

    private void toggle() {
        if (panelVisible) {
            dismissPanel();
        } else {
            showPanel();
        }
    }

    private void showPanel() {
        if (panelVisible) {
            return;
        }
        final HistoryPanelService service = new HistoryPanelService(
            context.cubism().history(),
            context.uiHost(),
            logger
        );
        panelRegistration = service.enable();
        // Present the pane as a Photoshop-style floating window next to the strip.
        context.uiHost().activateEmbeddedPanelFloating(EmbeddedPanelId.of(PANEL_ID));
        panelVisible = true;
        logger.info("History panel toggled on (floating)");
    }

    private void dismissPanel() {
        if (!panelVisible) {
            return;
        }
        panelVisible = false;
        final Registration current = panelRegistration;
        panelRegistration = null;
        if (current != null) {
            current.close();
        }
        logger.info("History panel toggled off");
    }

    private void closeDisposableScopeQuietly() {
        try {
            context.disposableScope().close();
        } catch (Exception closeFailure) {
            logger.warn("HistoryPanelPlugin enable rollback close failed: " + closeFailure.getMessage());
        }
    }

    @Override
    public void disable() {
        dismissPanel();
        logger.info("HistoryPanelPlugin disabled");
    }

    @Override
    public void shutdown() {
        logger.info("HistoryPanelPlugin shutdown");
    }

    private void registerAction(
        final String id,
        final String label,
        final Consumer<ActionRegistry.ActionContext> handler
    ) {
        final Registration registration = context.actions().register(id, new ActionRegistry.Action() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String label() {
                return label;
            }

            @Override
            public Consumer<ActionRegistry.ActionContext> handler() {
                return handler;
            }
        });
        context.disposableScope().register(registration);
    }
}
