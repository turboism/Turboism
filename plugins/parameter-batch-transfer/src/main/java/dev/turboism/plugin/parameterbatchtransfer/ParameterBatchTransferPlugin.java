package dev.turboism.plugin.parameterbatchtransfer;

import dev.turboism.plugin.parameterbatchtransfer.b1.domain.BatchTransferOutcome;
import dev.turboism.plugin.parameterbatchtransfer.b1.domain.BatchTransferRow;
import dev.turboism.plugin.parameterbatchtransfer.service.ParameterBatchTransferService;
import dev.turboism.plugin.parameterbatchtransfer.ui.BatchTransferDialog;
import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.cubism.CubismPlugin;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.ParameterBindingTarget;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.StatusNotification;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import dev.turboism.sdk.ui.context.ContextMenuSelection;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Official plugin shell for batch parameter-binding transfer.
 *
 * <p>enable() registers one action and three context-menu entries (Deformer tab,
 * Part tab, workspace objects) restricted to ART_MESH / WARP_DEFORMER /
 * ROTATION_DEFORMER selections with exactly one item. The action opens a modal
 * Swing dialog listing the owner's bound parameters; confirmed rows are applied
 * one transfer each (no undo grouping).</p>
 */
public final class ParameterBatchTransferPlugin implements CubismPlugin {

    public static final String ACTION_ID = "parameter.batchTransfer.open";
    public static final String CONTEXT_MENU_DEFORMER_ID = "parameter.batchTransfer.deformer";
    public static final String CONTEXT_MENU_PART_ID = "parameter.batchTransfer.part";
    public static final String CONTEXT_MENU_WORKSPACE_ID = "parameter.batchTransfer.workspace";

    static final Set<ContextMenuRegistry.ObjectKind> OBJECT_KINDS = Set.of(
        ContextMenuRegistry.ObjectKind.ART_MESH,
        ContextMenuRegistry.ObjectKind.WARP_DEFORMER,
        ContextMenuRegistry.ObjectKind.ROTATION_DEFORMER
    );

    /** The batch-transfer entry appears only when exactly one object is selected. */
    public static final Predicate<ContextMenuSelection> SINGLE_SELECTION =
        selection -> selection.items().size() == 1;

    private ParameterBatchTransferService service;
    private PluginContext context;
    private PluginLocalization localization;
    private PluginLogger logger;

    public ParameterBatchTransferPlugin() {
        // service is created in init() once the plugin logger is available
    }

    ParameterBatchTransferPlugin(final ParameterBatchTransferService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override
    public void init(final PluginContext context) {
        this.context = Objects.requireNonNull(context, "context");
        this.localization = context.localization();
        this.logger = context.logger();
        if (service == null) {
            service = new ParameterBatchTransferService(logger);
        }
        logger.info("ParameterBatchTransferPlugin initialized");
    }

    @Override
    public void enable() {
        try {
            registerAction();
            context.disposableScope().register(contribute(
                CONTEXT_MENU_DEFORMER_ID, ContextMenuRegistry.Location.DEFORMER_TAB
            ));
            context.disposableScope().register(contribute(
                CONTEXT_MENU_PART_ID, ContextMenuRegistry.Location.PART_TAB
            ));
            context.disposableScope().register(contribute(
                CONTEXT_MENU_WORKSPACE_ID, ContextMenuRegistry.Location.WORKSPACE_OBJECT
            ));
        } catch (RuntimeException failure) {
            closeScopeQuietly();
            throw failure;
        }
        logger.info(
            "ParameterBatchTransferPlugin enabled: batch-transfer action and context-menu entries enrolled in disposable scope"
        );
    }

    @Override
    public void disable() {
        logger.info("ParameterBatchTransferPlugin disabled");
    }

    @Override
    public void shutdown() {
        logger.info("ParameterBatchTransferPlugin shutdown");
    }

    private void registerAction() {
        final Registration registration = context.actions().register(
            ACTION_ID,
            new ActionRegistry.Action() {
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
                    return actionContext ->
                        open(actionContext.contextMenuSelection().orElse(null));
                }
            }
        );
        context.disposableScope().register(registration);
    }

    private Registration contribute(
        final String id,
        final ContextMenuRegistry.Location location
    ) {
        return context.contextMenu().contribute(new ContextMenuRegistry.ContextMenuContribution(
            id,
            ACTION_ID,
            localization.text("menu.batchTransfer"),
            null,
            location,
            OBJECT_KINDS,
            110,
            SINGLE_SELECTION
        ));
    }

    /**
     * Context-menu entry point. Prepares the session on the host thread (fast
     * reads only), then opens the modal dialog asynchronously: the action
     * handler runs on the plugin work thread under a bounded budget, and a
     * blocking modal dialog would time the action out.
     */
    void open(final ContextMenuSelection selection) {
        if (selection == null || selection.items().size() != 1) {
            notify("parameter.batchTransfer.status.noSelection", "INFO", localization.text("status.noSelection"));
            return;
        }
        final Prepared prepared = prepare(selection);
        if (prepared == null) {
            return; // precondition notification already emitted
        }
        logger.info("PBT_OPEN items=" + selection.items()
            + " owner=" + prepared.owner.type() + ":" + prepared.owner.id()
            + " bound=" + prepared.session.bound().stream()
                .map(snapshot -> snapshot.parameterId().value()
                    + ":" + (snapshot.family() == null ? "?" : snapshot.family()))
                .toList()
            + " candidates=" + prepared.session.candidates().size());
        if (GraphicsEnvironment.isHeadless()) {
            logger.warn("Parameter batch transfer cannot open because the JVM is headless");
            return;
        }
        SwingUtilities.invokeLater(() -> {
            final List<BatchTransferRow> rows =
                new BatchTransferDialog(localization, service, prepared.session).showDialog();
            if (rows == null) {
                return; // cancelled
            }
            final BatchTransferOutcome outcome = service.apply(prepared.model, prepared.owner, rows);
            switch (outcome.status()) {
                case APPLIED -> notify(
                    "parameter.batchTransfer.status.applied",
                    "INFO",
                    localization.format("status.applied", outcome.applied())
                );
                case PARTIAL -> notify(
                    "parameter.batchTransfer.status.partial",
                    "WARNING",
                    localization.format("status.partial", outcome.applied(), outcome.failed())
                );
                case NO_CHANGES -> notify(
                    "parameter.batchTransfer.status.noChanges",
                    "INFO",
                    localization.text("status.noChanges")
                );
                default -> { }
            }
        });
    }

    /** Host-thread session preparation; emits precondition notifications. */
    private Prepared prepare(final ContextMenuSelection selection) {
        final java.util.concurrent.atomic.AtomicReference<Prepared> prepared = new java.util.concurrent.atomic.AtomicReference<>();
        try {
            runOnHostThread(() -> {
                final CubismModel model = context.cubism().model().active();
                logger.info("PBT_PREPARE items=" + selection.items()
                    + " drawables=" + model.drawables().all().size()
                    + " deformers=" + model.deformers().all().size());
                final ParameterBindingTarget owner = resolveOwner(selection.items().get(0));
                if (owner == null) {
                    notify("parameter.batchTransfer.status.noSelection", "INFO", localization.text("status.noSelection"));
                    return;
                }
                final ParameterBatchTransferService.Session session = service.sessionFor(model, owner);
                if (session.bound().isEmpty()) {
                    notify(
                        "parameter.batchTransfer.status.noBoundParameters",
                        "INFO",
                        localization.text("status.noBoundParameters")
                    );
                    return;
                }
                prepared.set(new Prepared(model, owner, session));
            });
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (java.lang.reflect.InvocationTargetException failure) {
            logger.warn("Parameter batch transfer preparation failed safely: " + failure.getCause());
        }
        return prepared.get();
    }

    private static void runOnHostThread(final Runnable action)
        throws InterruptedException, java.lang.reflect.InvocationTargetException {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
        } else {
            SwingUtilities.invokeAndWait(action);
        }
    }

    private record Prepared(
        CubismModel model,
        ParameterBindingTarget owner,
        ParameterBatchTransferService.Session session
    ) {
    }

    private static ParameterBindingTarget resolveOwner(final ContextMenuSelection.Item item) {
        return switch (item.kind()) {
            case ART_MESH -> ParameterBindingTarget.artMesh(new ArtMeshId(item.id()));
            case WARP_DEFORMER -> ParameterBindingTarget.warpDeformer(new DeformerId(item.id()));
            case ROTATION_DEFORMER -> ParameterBindingTarget.rotationDeformer(new DeformerId(item.id()));
            default -> null;
        };
    }

    private void notify(final String id, final String severity, final String message) {
        context.uiHost().notifyStatus(new StatusNotification(id, severity, message));
    }

    private void closeScopeQuietly() {
        try {
            context.disposableScope().close();
        } catch (Exception closeFailure) {
            logger.warn(
                "ParameterBatchTransferPlugin enable rollback close failed: " + closeFailure.getMessage()
            );
        }
    }
}
