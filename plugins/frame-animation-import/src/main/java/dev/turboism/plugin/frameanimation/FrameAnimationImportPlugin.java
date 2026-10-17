package dev.turboism.plugin.frameanimation;

import dev.turboism.plugin.frameanimation.domain.FrameAnimationImportPlan;
import dev.turboism.plugin.frameanimation.domain.FrameAnimationImportResult;
import dev.turboism.plugin.frameanimation.service.FrameAnimationImportService;
import dev.turboism.plugin.frameanimation.ui.FrameAnimationDialog;
import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.cubism.CubismPlugin;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOutcome;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionResult;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionService;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.StatusNotification;
import dev.turboism.sdk.ui.UiHostCapabilityService;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import java.awt.GraphicsEnvironment;
import java.util.Objects;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;

/**
 * Official plugin entrypoint for importing sequence frame animations and binding keyform opacities.
 */
public final class FrameAnimationImportPlugin implements CubismPlugin {

    public static final String ACTION_ID = "frameAnimation.import.open";
    public static final String CONTEXT_MENU_PART_ID = "frameAnimation.import.part";
    public static final String CONTEXT_MENU_DEFORMER_ID = "frameAnimation.import.deformer";
    private static final String MENU_ROOT = "Turboism";
    private static final int MENU_ORDER = 150;

    private FrameAnimationImportService service;
    private PluginContext context;
    private PluginLocalization localization;
    private PluginLogger logger;

    public FrameAnimationImportPlugin() {}

    FrameAnimationImportPlugin(final FrameAnimationImportService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override
    public void init(final PluginContext context) {
        this.context = Objects.requireNonNull(context, "context");
        this.localization = context.localization();
        this.logger = context.logger();
        if (service == null) {
            service = new FrameAnimationImportService(logger);
        }
        logger.info("FrameAnimationImportPlugin initialized");
    }

    @Override
    public void enable() {
        try {
            registerAction();
            context.disposableScope().register(contribute(CONTEXT_MENU_PART_ID, ContextMenuRegistry.Location.PART_TAB));
            context.disposableScope()
                    .register(contribute(CONTEXT_MENU_DEFORMER_ID, ContextMenuRegistry.Location.DEFORMER_TAB));
            context.disposableScope().register(contributeTopMenu());
        } catch (RuntimeException failure) {
            closeScopeQuietly();
            throw failure;
        }
        logger.info("FrameAnimationImportPlugin enabled: registered import action, context menus, and top-level menu");
    }

    @Override
    public void disable() {
        logger.info("FrameAnimationImportPlugin disabled");
    }

    @Override
    public void shutdown() {
        logger.info("FrameAnimationImportPlugin shutdown");
    }

    private void registerAction() {
        final Registration registration = context.actions().register(ACTION_ID, new ActionRegistry.Action() {
            @Override
            public String id() {
                return ACTION_ID;
            }

            @Override
            public String label() {
                return localization.text("action.label");
            }

            @Override
            public Consumer<ActionRegistry.ActionContext> handler() {
                return actionContext -> openDialog();
            }
        });
        context.disposableScope().register(registration);
    }

    private Registration contribute(final String id, final ContextMenuRegistry.Location location) {
        return context.services()
                .require(ContextMenuRegistry.class)
                .contribute(new ContextMenuRegistry.ContextMenuContribution(
                        id,
                        ACTION_ID,
                        localization.text("menu.import"),
                        null,
                        location,
                        location.supportedKinds(),
                        120,
                        selection -> true));
    }

    private Registration contributeTopMenu() {
        final String menuPath =
                MENU_ROOT + "/" + localization.text("menu.plugins") + "/" + localization.text("menu.import");
        return context.menus().contribute(MenuRegistry.MenuContribution.of(menuPath, ACTION_ID, MENU_ORDER));
    }

    void openDialog() {
        if (!context.cubism().model().isAvailable()) {
            notify("frameAnimation.status.noModel", "WARNING", localization.text("status.noModel"));
            return;
        }

        final CubismModel model;
        try {
            model = context.cubism().model().active();
        } catch (IllegalStateException e) {
            notify("frameAnimation.status.noModel", "WARNING", localization.text("status.noModel"));
            return;
        }

        if (GraphicsEnvironment.isHeadless()) {
            logger.warn("Frame animation import cannot open in headless environment");
            return;
        }

        SwingUtilities.invokeLater(() -> {
            final FrameAnimationDialog dialog = new FrameAnimationDialog(null, localization);
            final FrameAnimationImportPlan plan = dialog.showDialog();
            if (plan == null) {
                return; // User cancelled
            }

            try {
                final AuthoringTransactionService tx = context.cubism().authoringTransactions();
                final AuthoringTransactionResult<FrameAnimationImportResult> result = service.execute(model, plan, tx);

                if (result.outcome() == AuthoringTransactionOutcome.COMMITTED
                        && result.value().isPresent()) {
                    final FrameAnimationImportResult res = result.value().get();
                    notify(
                            "frameAnimation.status.success",
                            "INFO",
                            localization.format(
                                    "status.success",
                                    res.frameCount(),
                                    res.parameterId().value()));
                } else {
                    notify(
                            "frameAnimation.status.failed",
                            "ERROR",
                            localization.format(
                                    "status.failed", result.diagnosticId().orElse("transaction aborted")));
                }
            } catch (Exception ex) {
                logger.error("Failed to execute frame animation import", ex);
                notify("frameAnimation.status.failed", "ERROR", localization.format("status.failed", ex.getMessage()));
            }
        });
    }

    private void notify(final String id, final String severity, final String message) {
        try {
            context.services()
                    .require(UiHostCapabilityService.class)
                    .notifyStatus(new StatusNotification(id, severity, message));
        } catch (Exception e) {
            if (logger != null) {
                logger.warn("Status notification failed: " + message);
            }
        }
    }

    private void closeScopeQuietly() {
        try {
            context.disposableScope().close();
        } catch (Exception closeFailure) {
            logger.warn("FrameAnimationImportPlugin close scope failed: " + closeFailure.getMessage());
        }
    }
}
