package dev.turboism.plugin.parameter;

import dev.turboism.plugin.parameter.service.ParameterCsvService;
import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.cubism.CubismPlugin;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.ParameterBindingTarget;
import dev.turboism.sdk.cubism.model.ParameterBindingTransferPlan;
import dev.turboism.sdk.ui.DialogRequest;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import dev.turboism.sdk.ui.context.ContextMenuSelection;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;

import java.util.function.Consumer;

/**
 * Official SDK-only plugin shell for parameter CSV import/export behavior.
 */
public final class ParameterPlugin implements CubismPlugin {

    public static final String INVERT_BINDINGS_ACTION_ID = "parameter.bindings.invert";
    public static final String TRANSFER_BINDINGS_ACTION_ID = "parameter.bindings.transfer";
    private static final String TRANSFER_PARAMETER_CONTEXT_MENU_ID = "parameter.bindings.transfer.parameter";
    private static final String TRANSFER_DEFORMER_CONTEXT_MENU_ID = "parameter.bindings.transfer.deformer";
    private static final String TRANSFER_PART_CONTEXT_MENU_ID = "parameter.bindings.transfer.part";
    private static final String TRANSFER_WORKSPACE_CONTEXT_MENU_ID = "parameter.bindings.transfer.workspace";

    private final ParameterCsvService.CsvContentProvider csvContentProvider;
    private PluginContext context;
    private PluginLogger logger;
    private ParameterCsvService csvService;

    public ParameterPlugin() {
        this(ParameterCsvService.CsvContentProvider.unavailable());
    }

    public ParameterPlugin(final ParameterCsvService.CsvContentProvider csvContentProvider) {
        this.csvContentProvider = java.util.Objects.requireNonNull(csvContentProvider, "csvContentProvider");
    }

    @Override
    public void init(final PluginContext context) {
        this.context = context;
        this.logger = context.logger();
        this.csvService = new ParameterCsvService(
            context.cubism(),
            context,
            context.uiHost(),
            csvContentProvider
        );
        logger.info("ParameterPlugin initialized");
    }

    @Override
    public void enable() {
        try {
            registerAction(
                ParameterCsvService.EXPORT_ACTION_ID,
                "Export Parameters CSV",
                ignored -> csvService.exportCsv()
            );
            registerAction(
                ParameterCsvService.IMPORT_ACTION_ID,
                "Import Parameters CSV",
                ignored -> csvService.importCsv()
            );
            registerAction(
                INVERT_BINDINGS_ACTION_ID,
                "Invert Parameter Bindings",
                ignored -> invertSelectedBindings()
            );
            registerAction(
                TRANSFER_BINDINGS_ACTION_ID,
                "Transfer Parameter Bindings",
                actionContext -> transferSelectedBindings(actionContext.contextMenuSelection().orElse(null))
            );
            registerMenu("Parameter Tools/Invert Bindings", INVERT_BINDINGS_ACTION_ID, 100);
            registerMenu("Parameter Tools/Transfer Bindings", TRANSFER_BINDINGS_ACTION_ID, 110);
            registerContextMenu(
                TRANSFER_PARAMETER_CONTEXT_MENU_ID,
                "Transfer Parameter Bindings",
                ContextMenuRegistry.Location.PARAMETER_TAB,
                java.util.Set.of(ContextMenuRegistry.ObjectKind.PARAMETER),
                110
            );
            registerContextMenu(
                TRANSFER_DEFORMER_CONTEXT_MENU_ID,
                "Transfer Parameter Bindings",
                ContextMenuRegistry.Location.DEFORMER_TAB,
                java.util.Set.of(
                    ContextMenuRegistry.ObjectKind.ART_MESH,
                    ContextMenuRegistry.ObjectKind.WARP_DEFORMER,
                    ContextMenuRegistry.ObjectKind.ROTATION_DEFORMER
                ),
                110
            );
            registerContextMenu(
                TRANSFER_PART_CONTEXT_MENU_ID,
                "Transfer Parameter Bindings",
                ContextMenuRegistry.Location.PART_TAB,
                java.util.Set.of(
                    ContextMenuRegistry.ObjectKind.ART_MESH,
                    ContextMenuRegistry.ObjectKind.WARP_DEFORMER,
                    ContextMenuRegistry.ObjectKind.ROTATION_DEFORMER
                ),
                110
            );
            registerContextMenu(
                TRANSFER_WORKSPACE_CONTEXT_MENU_ID,
                "Transfer Parameter Bindings",
                ContextMenuRegistry.Location.WORKSPACE_OBJECT,
                java.util.Set.of(
                    ContextMenuRegistry.ObjectKind.ART_MESH,
                    ContextMenuRegistry.ObjectKind.WARP_DEFORMER,
                    ContextMenuRegistry.ObjectKind.ROTATION_DEFORMER
                ),
                110
            );
        } catch (RuntimeException failure) {
            closeDisposableScopeQuietly();
            throw failure;
        }
        logger.info("ParameterPlugin enabled: parameter CSV export/import actions enrolled in disposable scope");
    }

    @Override
    public void disable() {
        logger.info("ParameterPlugin disabled");
    }

    @Override
    public void shutdown() {
        logger.info("ParameterPlugin shutdown");
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

    private void registerContextMenu(
        final String id,
        final String label,
        final ContextMenuRegistry.Location location,
        final java.util.Set<ContextMenuRegistry.ObjectKind> objectKinds,
        final int priority
    ) {
        context.disposableScope().register(context.contextMenu().contribute(
            new ContextMenuRegistry.ContextMenuContribution(
                id,
                TRANSFER_BINDINGS_ACTION_ID,
                label,
                null,
                location,
                objectKinds,
                priority
            )
        ));
    }

    private void registerMenu(final String path, final String actionId, final int order) {
        context.disposableScope().register(context.menus().contribute(new MenuRegistry.MenuContribution() {
            @Override public String menuPath() { return path; }
            @Override public String actionId() { return actionId; }
            @Override public int order() { return order; }
        }));
    }

    private void invertSelectedBindings() {
        final var model = context.cubism().model().active();
        final var snapshot = context.cubism().runtime().selection();
        model.parameterBindingBatch().invert(
            selectedTargets(model, snapshot, null)
        );
    }

    private void transferSelectedBindings() {
        transferSelectedBindings(null);
    }

    private void transferSelectedBindings(final ContextMenuSelection contextMenuSelection) {
        final var model = context.cubism().model().active();
        final var snapshot = context.cubism().runtime().selection();
        final ParameterId source = resolveSourceParameter(snapshot, contextMenuSelection);
        final ParameterId destination = resolveDestinationParameter(source, snapshot, contextMenuSelection);
        if (contextMenuSelection != null && !context.uiHost().confirmDialog(new DialogRequest(
            "parameter.bindings.transfer.confirm",
            "Transfer Parameter Bindings",
            "Transfer selected object bindings from " + source.value() + " to " + destination.value()
                + "?"
        ))) {
            return;
        }
        final boolean invert = contextMenuSelection == null || context.uiHost().confirmDialog(new DialogRequest(
            "parameter.bindings.transfer.invert.confirm",
            "Transfer Parameter Bindings",
            "Invert the transferred bindings?"
        ));
        model.parameterBindingBatch().transfer(new ParameterBindingTransferPlan(
            source,
            destination,
            selectedTargets(model, snapshot, contextMenuSelection),
            invert
        ));
    }

    private static ParameterId resolveSourceParameter(
        final dev.turboism.sdk.cubism.SelectionSnapshot snapshot,
        final ContextMenuSelection contextMenuSelection
    ) {
        if (contextMenuSelection != null && contextMenuSelection.location() == ContextMenuRegistry.Location.PARAMETER_TAB) {
            final String id = contextMenuSelection.items().stream()
                .filter(item -> item.kind() == ContextMenuRegistry.ObjectKind.PARAMETER)
                .map(ContextMenuSelection.Item::id)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("A source parameter must be selected."));
            return new ParameterId(id);
        }
        return new ParameterId(snapshot.activeParameterId().orElseThrow(
            () -> new IllegalStateException("A source parameter must be active.")
        ));
    }

    private static ParameterId resolveDestinationParameter(
        final ParameterId source,
        final dev.turboism.sdk.cubism.SelectionSnapshot snapshot,
        final ContextMenuSelection contextMenuSelection
    ) {
        if (contextMenuSelection != null && contextMenuSelection.location() == ContextMenuRegistry.Location.PARAMETER_TAB) {
            final String destination = contextMenuSelection.items().stream()
                .filter(item -> item.kind() == ContextMenuRegistry.ObjectKind.PARAMETER)
                .map(ContextMenuSelection.Item::id)
                .filter(id -> !id.equals(source.value()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("A destination parameter must be selected."));
            return new ParameterId(destination);
        }
        final String destination = snapshot.selectedObjectIds().stream()
            .map(ParameterPlugin::parameterIdText)
            .filter(java.util.Objects::nonNull)
            .filter(id -> !id.equals(source.value()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("A destination parameter must be selected."));
        return new ParameterId(destination);
    }

    private static String parameterIdText(final String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return id.startsWith("parameter:") ? id.substring("parameter:".length()) : id;
    }


    private static java.util.List<ParameterBindingTarget> selectedTargets(
        final dev.turboism.sdk.cubism.model.CubismModel model,
        final dev.turboism.sdk.cubism.SelectionSnapshot snapshot,
        final ContextMenuSelection contextMenuSelection
    ) {
        final java.util.ArrayList<ParameterBindingTarget> targets = new java.util.ArrayList<>();
        if (contextMenuSelection != null) {
            for (ContextMenuSelection.Item item : contextMenuSelection.items()) {
                switch (item.kind()) {
                    case ART_MESH -> targets.add(ParameterBindingTarget.artMesh(new ArtMeshId(item.id())));
                    case WARP_DEFORMER -> targets.add(ParameterBindingTarget.warpDeformer(new DeformerId(item.id())));
                    case ROTATION_DEFORMER -> targets.add(ParameterBindingTarget.rotationDeformer(new DeformerId(item.id())));
                    default -> { }
                }
            }
        }
        if (targets.isEmpty()) {
            snapshot.activeArtMeshId().ifPresent(id -> targets.add(ParameterBindingTarget.artMesh(new ArtMeshId(id))));
            snapshot.activeDeformerId().ifPresent(id -> {
                final DeformerId deformerId = new DeformerId(id);
                final boolean warp = model.warpDeformers().all().stream().anyMatch(value -> value.id().equals(deformerId));
                final boolean rotation = model.rotationDeformers().all().stream().anyMatch(value -> value.id().equals(deformerId));
                if (warp == rotation) {
                    throw new IllegalStateException("The selected deformer family is unavailable or ambiguous.");
                }
                targets.add(warp
                    ? ParameterBindingTarget.warpDeformer(deformerId)
                    : ParameterBindingTarget.rotationDeformer(deformerId));
            });
        }
        if (targets.isEmpty()) {
            throw new IllegalStateException("An ArtMesh or Deformer target must be active.");
        }
        return targets.stream().distinct().toList();
    }

    private void closeDisposableScopeQuietly() {
        try {
            context.disposableScope().close();
        } catch (Exception closeFailure) {
            logger.warn("ParameterPlugin enable rollback close failed: " + closeFailure.getMessage());
        }
    }
}
