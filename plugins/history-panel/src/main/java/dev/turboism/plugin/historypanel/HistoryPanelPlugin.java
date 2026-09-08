package dev.turboism.plugin.historypanel;

import dev.turboism.plugin.historypanel.service.HistoryPanelService;
import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.TurboismPlugin;
import dev.turboism.sdk.cubism.history.CubismHistory;
import dev.turboism.sdk.cubism.history.HistoryMoveResult;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.ui.EmbeddedPanelContribution;
import dev.turboism.sdk.ui.HorizontalToolbarContribution;
import dev.turboism.sdk.ui.StatusNotification;
import dev.turboism.sdk.ui.VerticalToolbarContribution;

import java.util.List;
import java.util.function.Consumer;

/**
 * Photoshop-style History pane plugin driven by a vertical tool-strip button.
 *
 * <p>Enable attaches a vertical History icon button to the Cubism main frame.
 * Clicking it toggles a small history pane beside the strip (embedded dock
 * panel); clicking again closes it. Entry actions use snapshot-bound Undo/Redo
 * operations and remain unavailable when the reviewed host history service is absent.</p>
 */
public final class HistoryPanelPlugin implements TurboismPlugin {

    static final String TOGGLE_ACTION_ID = "history.panel.toggle";
    static final String STRIP_ID = "history.toolstrip";
    static final String STRIP_BUTTON_ID = "history";
    static final String PANEL_ID = "history.panel";

    private PluginContext context;
    private PluginLogger logger;
    private dev.turboism.sdk.i18n.PluginLocalization localization;
    private Registration panelRegistration;
    private boolean panelVisible;

    @Override
    public void init(final PluginContext context) {
        this.context = context;
        this.logger = context.logger();
        this.localization = context.localization();
        logger.info("HistoryPanelPlugin initialized");
    }

    @Override
    public void enable() {
        try {
            context.disposableScope().register(registerAction(TOGGLE_ACTION_ID, "History", ignored -> toggle()));
            context.disposableScope().register(context.uiHost().contributeVerticalToolbar(
                new VerticalToolbarContribution(
                    STRIP_ID,
                    List.of(new VerticalToolbarContribution.ToolButton(
                        STRIP_BUTTON_ID,
                        "icons/history.png",
                        localization.text("history.button.tooltip"),
                        TOGGLE_ACTION_ID
                    )),
                    VerticalToolbarContribution.VerticalSide.RIGHT
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
        panelVisible = true;
        if (panelRegistration == null) {
            try {
                final HistoryPanelService service = new HistoryPanelService(
                    context.cubism().history(),
                    context.uiHost(),
                    taskScheduler(),
                    logger,
                    localization,
                    this::registerMoveActions
                );
                panelRegistration = service.enable();
            } catch (RuntimeException failure) {
                // A previous incomplete close may have left the panel contributed;
                // fall through to activation so the toggle still works.
                logger.warn("History panel contribution retried safely: " + failure.getMessage());
            }
        }
        registerMoveActions();
        // The contribution installs already floating (floatingByDefault), so no
        // separate activation is needed and the pane never shows a docked state.
        logger.info("History panel toggled on (floating)");
    }

    /**
     * Resolves the bounded task scheduler seam; null (safe mode) leaves the pane
     * usable with the initial refresh and no polling.
     */
    private dev.turboism.sdk.task.PluginTaskScheduler taskScheduler() {
        try {
            return context.tasks();
        } catch (RuntimeException unavailable) {
            logger.warn("History panel task scheduler unavailable; pane shows without polling");
            return null;
        }
    }

    private void dismissPanel() {
        if (!panelVisible) {
            return;
        }
        panelVisible = false;
        unregisterMoveActions();
        final Registration current = panelRegistration;
        panelRegistration = null;
        if (current != null) {
            try {
                current.close();
            } catch (RuntimeException failure) {
                // Host teardown of a floating frame can fail partially; the pane
                // is still dismissed from the toggle state.
                logger.warn("History panel close failed safely: " + failure.getMessage());
            }
        }
        logger.info("History panel toggled off");
    }

    private final java.util.List<Registration> moveActions = new java.util.ArrayList<>();

    private void registerMoveActions() {
        unregisterMoveActions();
        final CubismHistory history = context.cubism().history();
        final HistorySnapshot snapshot = history.snapshot();
        if (snapshot.availability() != HistorySnapshot.Availability.AVAILABLE) {
            return;
        }
        final List<String> expectedSequence = snapshot.entries().stream()
            .map(entry -> entry.entryId().map(id -> id.value()).orElse(null))
            .toList();
        if (expectedSequence.stream().anyMatch(java.util.Objects::isNull)) {
            logger.warn("History navigation disabled: stable entry identity unavailable");
            return;
        }
        for (final dev.turboism.sdk.cubism.history.HistoryEntry entry : snapshot.entries()) {
            final String entryId = entry.entryId().orElseThrow().value();
            final String actionId = HistoryPanelService.moveActionId(entryId);
            moveActions.add(registerAction(actionId, entry.label(), ignored -> {
                final HistorySnapshot current = history.snapshot();
                if (!matchesBinding(snapshot, expectedSequence, current)) {
                    logger.warn("History navigation rejected: snapshot binding changed");
                    return;
                }
                final int entryIndex = expectedSequence.indexOf(entryId);
                if (entryIndex < 0) {
                    logger.warn("History navigation rejected: entry identity missing");
                    return;
                }
                final int targetPosition = entryIndex < current.position()
                    ? entryIndex
                    : entryIndex + 1;
                final HistoryMoveResult result = history.moveTo(
                    snapshot.generation(),
                    snapshot.revision(),
                    targetPosition
                );
                if (result.outcome() != HistoryMoveResult.Outcome.MOVED) {
                    logger.warn("History navigation failed safely: " + result.diagnosticId().orElse("unknown"));
                }
            }));
        }
    }

    private static boolean matchesBinding(
        final HistorySnapshot expected,
        final List<String> expectedSequence,
        final HistorySnapshot current
    ) {
        if (current.availability() != HistorySnapshot.Availability.AVAILABLE
            || current.generation() != expected.generation()
            || current.revision() != expected.revision()
            || current.entries().size() != expectedSequence.size()) {
            return false;
        }
        for (int index = 0; index < expectedSequence.size(); index++) {
            final String currentId = current.entries().get(index).entryId()
                .map(id -> id.value())
                .orElse(null);
            if (!expectedSequence.get(index).equals(currentId)) {
                return false;
            }
        }
        return true;
    }

    private void unregisterMoveActions() {
        for (final Registration registration : moveActions) {
            registration.close();
        }
        moveActions.clear();
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

    private Registration registerAction(
        final String id,
        final String label,
        final Consumer<ActionRegistry.ActionContext> handler
    ) {
        return context.actions().register(id, new ActionRegistry.Action() {
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
    }
}
