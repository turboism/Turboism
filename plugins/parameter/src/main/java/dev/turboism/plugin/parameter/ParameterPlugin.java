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
                ignored -> transferSelectedBindings()
            );
            registerMenu("Parameter Tools/Invert Bindings", INVERT_BINDINGS_ACTION_ID, 100);
            registerMenu("Parameter Tools/Transfer Bindings", TRANSFER_BINDINGS_ACTION_ID, 110);
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
        model.parameterBindingBatch().invert(selectedTargets(model, snapshot));
    }

    private void transferSelectedBindings() {
        final var model = context.cubism().model().active();
        final var snapshot = context.cubism().runtime().selection();
        final ParameterId source = new ParameterId(snapshot.activeParameterId().orElseThrow(
            () -> new IllegalStateException("A source parameter must be active.")
        ));
        final String destination = snapshot.selectedObjectIds().stream()
            .filter(id -> id.startsWith("parameter:"))
            .map(id -> id.substring("parameter:".length()))
            .filter(id -> !id.equals(source.value()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("A destination parameter must be selected."));
        final boolean invert = context.uiHost().confirmDialog(new DialogRequest(
            "parameter.bindings.transfer.confirm",
            "Transfer Parameter Bindings",
            "Transfer selected object bindings from " + source.value() + " to " + destination
                + " and invert the result?"
        ));
        model.parameterBindingBatch().transfer(new ParameterBindingTransferPlan(
            source,
            new ParameterId(destination),
            selectedTargets(model, snapshot),
            invert
        ));
    }

    private static java.util.List<ParameterBindingTarget> selectedTargets(
        final dev.turboism.sdk.cubism.model.CubismModel model,
        final dev.turboism.sdk.cubism.SelectionSnapshot snapshot
    ) {
        final java.util.ArrayList<ParameterBindingTarget> targets = new java.util.ArrayList<>();
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
        if (targets.isEmpty()) {
            throw new IllegalStateException("An ArtMesh or Deformer target must be active.");
        }
        return java.util.List.copyOf(targets);
    }

    private void closeDisposableScopeQuietly() {
        try {
            context.disposableScope().close();
        } catch (Exception closeFailure) {
            logger.warn("ParameterPlugin enable rollback close failed: " + closeFailure.getMessage());
        }
    }
}
