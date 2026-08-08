package dev.turboism.plugin.parameter;

import dev.turboism.plugin.parameter.service.ParameterCsvService;
import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.CubismRuntimeSnapshot;
import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.ModelSnapshot;
import dev.turboism.sdk.cubism.ProjectSnapshot;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.ParameterBindingBatchOperations;
import dev.turboism.sdk.cubism.model.ParameterBindingTarget;
import dev.turboism.sdk.cubism.model.ParameterBindingTransferPlan;
import dev.turboism.sdk.cubism.transaction.TransactionManager;
import dev.turboism.sdk.diagnostics.DiagnosticReport;
import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.permission.PluginPermission;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.PluginPaths;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.DialogRequest;
import dev.turboism.sdk.ui.EmbeddedPanelContribution;
import dev.turboism.sdk.ui.FileChooserRequest;
import dev.turboism.sdk.ui.OverlayContribution;
import dev.turboism.sdk.ui.StatusNotification;
import dev.turboism.sdk.ui.UiHostCapabilityService;
import dev.turboism.sdk.ui.UiScheduler;
import dev.turboism.sdk.ui.ViewportSnapshot;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import dev.turboism.sdk.ui.context.ContextMenuSelection;
import dev.turboism.sdk.ui.context.ContextSourceSnapshot;
import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;
import dev.turboism.sdk.ui.toolbar.PaletteToolbarRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParameterPluginTest {

    @Test
    void enableRegistersExportAndImportActions() {
        TestPluginLogger logger = new TestPluginLogger();
        RecordingPluginContext context = new RecordingPluginContext(logger);
        ParameterPlugin plugin = new ParameterPlugin();

        plugin.init(context);
        plugin.enable();

        assertEquals(
            List.of("parameter.csv.export", "parameter.csv.import", "parameter.bindings.invert", "parameter.bindings.transfer"),
            context.actions().actions().stream().map(ActionRegistry.Action::id).toList()
        );
        assertEquals(
            List.of("Parameter Tools/Invert Bindings", "Parameter Tools/Transfer Bindings"),
            context.menus().contributions().stream().map(MenuRegistry.MenuContribution::menuPath).toList()
        );
        context.actions().execute("parameter.csv.export");
        assertEquals(
            List.of(new StatusNotification(
                "parameter.csv.export.completed",
                "INFO",
                "Exported 2 parameter(s) to CSV."
            )),
            context.uiHost().notifications()
        );
        assertEquals(
            List.of(
                "INFO: ParameterPlugin initialized",
                "INFO: ParameterPlugin enabled: parameter CSV export/import actions enrolled in disposable scope"
            ),
            logger.messages()
        );
    }

    @Test
    void enableRegistersContextMenuContributionsForSupportedBatchWorkflows() {
        RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        ParameterPlugin plugin = new ParameterPlugin();

        plugin.init(context);
        plugin.enable();

        assertEquals(
            Set.of(
                "parameter.bindings.transfer.parameter",
                "parameter.bindings.transfer.deformer",
                "parameter.bindings.transfer.part",
                "parameter.bindings.transfer.workspace"
            ),
            Set.copyOf(context.contextMenu().contributions().stream()
                .map(ContextMenuRegistry.ContextMenuContribution::id)
                .toList())
        );
        assertEquals(
            Set.of(ContextMenuRegistry.Location.PARAMETER_TAB),
            Set.copyOf(context.contextMenu().contributions().stream()
                .filter(value -> value.id().equals("parameter.bindings.transfer.parameter"))
                .map(ContextMenuRegistry.ContextMenuContribution::location)
                .toList())
        );
        assertEquals(
            Set.of(ContextMenuRegistry.ObjectKind.ART_MESH,
                ContextMenuRegistry.ObjectKind.WARP_DEFORMER,
                ContextMenuRegistry.ObjectKind.ROTATION_DEFORMER),
            context.contextMenu().contributions().stream()
                .filter(value -> value.id().contains("transfer") && !value.id().endsWith(".parameter"))
                .findFirst()
                .orElseThrow()
                .objectKinds()
        );
    }

    @Test
    void parameterContextTransferUsesCapturedParameterIds() {
        RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        ParameterPlugin plugin = new ParameterPlugin();

        plugin.init(context);
        plugin.enable();
        context.actions().execute(
            ParameterPlugin.TRANSFER_BINDINGS_ACTION_ID,
            new ContextMenuSelection(
                1L,
                "document-1",
                ContextMenuRegistry.Location.PARAMETER_TAB,
                List.of(
                    new ContextMenuSelection.Item(ContextMenuRegistry.ObjectKind.PARAMETER, "p1"),
                    new ContextMenuSelection.Item(ContextMenuRegistry.ObjectKind.PARAMETER, "p2")
                )
            )
        );

        assertEquals(
            List.of("transfer:p1->p2:ArtMeshFace:true"),
            context.cubism().batchWrites()
        );
    }

    @Test
    void fallbackTransferIgnoresObjectIdBeforeRealParameter() {
        RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        context.cubism().selectedObjectIds(List.of("object:ArtMeshFace", "parameter:p2"));
        ParameterPlugin plugin = new ParameterPlugin();

        plugin.init(context);
        plugin.enable();
        context.actions().execute(ParameterPlugin.TRANSFER_BINDINGS_ACTION_ID);

        assertEquals(
            List.of("transfer:p1->p2:ArtMeshFace:true"),
            context.cubism().batchWrites()
        );
    }

    @Test
    void objectOnlyFallbackTransferFailsWithoutBatchWrite() {
        RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        context.cubism().selectedObjectIds(List.of("object:ArtMeshFace"));
        ParameterPlugin plugin = new ParameterPlugin();

        plugin.init(context);
        plugin.enable();
        final IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            () -> context.actions().execute(ParameterPlugin.TRANSFER_BINDINGS_ACTION_ID)
        );

        assertEquals("A destination parameter must be selected.", failure.getMessage());
        assertTrue(context.cubism().batchWrites().isEmpty());
    }

    @Test
    void contextMenuTransferUsesCapturedObjectSelectionAndTypedPlan() {
        RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        ParameterPlugin plugin = new ParameterPlugin();

        plugin.init(context);
        plugin.enable();
        context.actions().execute(
            ParameterPlugin.TRANSFER_BINDINGS_ACTION_ID,
            new ContextMenuSelection(
                1L,
                "document-1",
                ContextMenuRegistry.Location.PART_TAB,
                List.of(new ContextMenuSelection.Item(
                    ContextMenuRegistry.ObjectKind.ART_MESH,
                    "ArtMeshFace"
                ))
            )
        );

        assertEquals(
            List.of("transfer:p1->p2:ArtMeshFace:true"),
            context.cubism().batchWrites()
        );
    }

    @Test
    void canceledContextMenuTransferDoesNotWrite() {
        RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        context.uiHost().confirmResult = false;
        ParameterPlugin plugin = new ParameterPlugin();

        plugin.init(context);
        plugin.enable();
        context.actions().execute(
            ParameterPlugin.TRANSFER_BINDINGS_ACTION_ID,
            new ContextMenuSelection(
                1L,
                "document-1",
                ContextMenuRegistry.Location.PART_TAB,
                List.of(new ContextMenuSelection.Item(
                    ContextMenuRegistry.ObjectKind.ART_MESH,
                    "ArtMeshFace"
                ))
            )
        );

        assertTrue(context.cubism().batchWrites().isEmpty());
    }


    @Test
    void batchActionsUseTypedModelBindingOperations() {
        RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        ParameterPlugin plugin = new ParameterPlugin();
        plugin.init(context);
        plugin.enable();

        assertEquals(
            List.of(
                "parameter.csv.export",
                "parameter.csv.import",
                "parameter.bindings.invert",
                "parameter.bindings.transfer"
            ),
            context.actions().actions().stream().map(ActionRegistry.Action::id).toList()
        );

        context.actions().execute("parameter.bindings.invert");
        context.actions().execute("parameter.bindings.transfer");

        assertEquals(List.of("invert:ArtMeshFace", "transfer:p1->p2:ArtMeshFace:true"), context.cubism().batchWrites());
    }

    @Test
    void importActionWritesThroughUnifiedParameterSetter() {
        RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        context.uiHost().chosenFile = Optional.of("imports/params.csv");
        ParameterPlugin plugin = new ParameterPlugin(
            ignored -> Optional.of("id,value\np1,0.75\n")
        );

        plugin.init(context);
        plugin.enable();
        context.actions().execute(ParameterCsvService.IMPORT_ACTION_ID);

        assertEquals(0.75f, context.cubism().parameterValue());
        assertEquals(List.of("p1=0.75"), context.cubism().writes());
        assertEquals(ParameterCsvService.IMPORT_COMPLETED, context.uiHost().notifications().get(0).id());
    }

    @Test
    void disposableScopeClosesActions() throws Exception {
        RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        ParameterPlugin plugin = new ParameterPlugin();
        plugin.init(context);
        plugin.enable();
        context.disposableScope().close();
        assertTrue(context.actions().actions().isEmpty());
    }

    private static final class RecordingPluginContext implements PluginContext {
        private final DisposableScope disposableScope = new DisposableScope();
        private final RecordingActionRegistry actions = new RecordingActionRegistry();
        private final RecordingMenuRegistry menus = new RecordingMenuRegistry();
        private final RecordingUiHost uiHost = new RecordingUiHost();
        private final RecordingContextMenuRegistry contextMenu = new RecordingContextMenuRegistry();
        private final PluginLogger logger;
        private final FixedCubismFacade cubism = new FixedCubismFacade();

        RecordingPluginContext(PluginLogger logger) {
            this.logger = logger;
        }

        @Override public PluginDescriptor descriptor() { throw new UnsupportedOperationException(); }
        @Override public PluginLogger logger() { return logger; }
        @Override public PluginPaths paths() { throw new UnsupportedOperationException(); }
        @Override public FixedCubismFacade cubism() { return cubism; }
        @Override public List<PluginPermission> permissions() { return List.of(); }
        @Override public EventBus eventBus() { throw new UnsupportedOperationException(); }
        @Override public RecordingActionRegistry actions() { return actions; }
        @Override public RecordingMenuRegistry menus() { return menus; }
        @Override public RecordingContextMenuRegistry contextMenu() { return contextMenu; }
        @Override public UiScheduler uiScheduler() { throw new UnsupportedOperationException(); }
        @Override public DiagnosticReport diagnostics() { throw new UnsupportedOperationException(); }
        @Override public DisposableScope disposableScope() { return disposableScope; }
        @Override public RecordingUiHost uiHost() { return uiHost; }
    }

    private static final class FixedCubismFacade implements CubismFacade {
        private final List<String> writes = new ArrayList<>();
        private final List<String> batchWrites = new ArrayList<>();
        private float parameterValue = 0.5f;
        private List<String> selectedObjectIds = List.of("parameter:p2");

        float parameterValue() { return parameterValue; }
        List<String> writes() { return List.copyOf(writes); }
        List<String> batchWrites() { return List.copyOf(batchWrites); }

        void selectedObjectIds(final List<String> ids) {
            selectedObjectIds = List.copyOf(ids);
        }

        @Override public CubismRuntimeSnapshot runtime() {
            return new CubismRuntimeSnapshot(
                Optional.empty(), Optional.empty(), Optional.empty(),
                new dev.turboism.sdk.cubism.SelectionSnapshot(
                    selectedObjectIds, Optional.of("p1"), Optional.of("ArtMeshFace"), Optional.empty()
                ),
                List.of(), List.of(), List.of(), List.of()
            );
        }
        @Override public Optional<ProjectSnapshot> activeProject() { return Optional.empty(); }
        @Override public Optional<DocumentSnapshot> activeDocument() { return Optional.empty(); }
        @Override public Optional<ModelSnapshot> activeModel() { return Optional.empty(); }
        @Override public boolean isHostPresent() { return false; }
        @Override public dev.turboism.sdk.cubism.model.CubismModelAccess model() {
            return () -> new CubismModel() {
                private final Parameter parameter = new Parameter() {
                    @Override public ParameterId id() { return new ParameterId("p1"); }
                    @Override public float getValue() { return parameterValue; }
                    @Override public float getMinimumValue() { return -1.0f; }
                    @Override public float getMaximumValue() { return 1.0f; }
                    @Override public float getDefaultValue() { return 0.0f; }
                    @Override public void setValue(float value) {
                        parameterValue = value;
                        writes.add("p1=" + value);
                    }
                };
                private final Parameter destinationParameter = new Parameter() {
                    @Override public ParameterId id() { return new ParameterId("p2"); }
                    @Override public float getValue() { return 0.0f; }
                    @Override public float getMinimumValue() { return -1.0f; }
                    @Override public float getMaximumValue() { return 1.0f; }
                    @Override public float getDefaultValue() { return 0.0f; }
                    @Override public void setValue(float value) { }
                };

                @Override public ModelId id() { return new ModelId("model-1"); }
                @Override public ParameterBindingBatchOperations parameterBindingBatch() {
                    return new ParameterBindingBatchOperations() {
                        @Override public void invert(List<ParameterBindingTarget> targets) {
                            batchWrites.add("invert:" + targets.get(0).id());
                        }
                        @Override public void transfer(ParameterBindingTransferPlan plan) {
                            batchWrites.add(
                                "transfer:" + plan.sourceParameterId().value() + "->"
                                    + plan.targetParameterId().value() + ":" + plan.targets().get(0).id()
                                    + ":" + plan.invertAfterTransfer()
                            );
                        }
                    };
                }
                @Override public dev.turboism.sdk.cubism.model.Parameters parameters() {
                    return new dev.turboism.sdk.cubism.model.Parameters() {
                        @Override public List<Parameter> all() { return List.of(parameter, destinationParameter); }
                        @Override public Parameter find(ParameterId id) {
                            if (parameter.id().equals(id)) return parameter;
                            if (destinationParameter.id().equals(id)) return destinationParameter;
                            throw new java.util.NoSuchElementException(id.value());
                        }
                    };
                }
                @Override public dev.turboism.sdk.cubism.model.Parts parts() { throw unsupported(); }
                @Override public dev.turboism.sdk.cubism.model.Drawables drawables() { throw unsupported(); }
                @Override public dev.turboism.sdk.cubism.model.Deformers deformers() { throw unsupported(); }
                @Override public dev.turboism.sdk.cubism.model.Glues glues() { throw unsupported(); }
                @Override public void update() { throw unsupported(); }
            };
        }
        @Override public TransactionManager transactionManager() {
            return (ctx, docId) -> {
                throw new AssertionError("legacy transaction manager must not be used");
            };
        }

        private static UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("not used");
        }
    }

    private static final class RecordingActionRegistry implements ActionRegistry {
        private final List<Action> actions = new ArrayList<>();
        List<Action> actions() { return actions; }
        @Override public Registration register(String id, Action action) {
            actions.add(action);
            return () -> actions.remove(action);
        }
        void execute(String id) {
            execute(id, new ActionContext() {});
        }

        void execute(String id, ContextMenuSelection selection) {
            execute(id, new ActionContext() {
                @Override public Optional<ContextMenuSelection> contextMenuSelection() {
                    return Optional.of(selection);
                }
            });
        }

        private void execute(String id, ActionContext context) {
            actions.stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow()
                .handler().accept(context);
        }
    }

    private static final class RecordingMenuRegistry implements MenuRegistry {
        private final List<MenuContribution> contributions = new ArrayList<>();
        List<MenuContribution> contributions() { return List.copyOf(contributions); }
        @Override public Registration contribute(final MenuContribution contribution) {
            contributions.add(contribution);
            return () -> contributions.remove(contribution);
        }
    }

    private static final class RecordingContextMenuRegistry implements ContextMenuRegistry {
        private final List<ContextMenuContribution> contributions = new ArrayList<>();

        List<ContextMenuContribution> contributions() {
            return List.copyOf(contributions);
        }

        @Override public Registration contribute(ContextMenuContribution contribution) {
            contributions.add(contribution);
            return () -> contributions.remove(contribution);
        }
    }

    private static final class RecordingUiHost implements UiHostCapabilityService {
        private final List<StatusNotification> notifications = new ArrayList<>();
        private Optional<String> chosenFile = Optional.empty();
        private boolean confirmResult = true;
        List<StatusNotification> notifications() { return notifications; }
        @Override public Registration contributeOverlay(OverlayContribution contribution) { throw unsupported(); }
        @Override public Registration contributeBoundingBoxOverlayButton(dev.turboism.sdk.ui.BoundingBoxOverlayButton contribution) { throw unsupported(); }
        @Override public ContextSourceSnapshot contextSource() { throw unsupported(); }
        @Override public ViewportSnapshot viewport() { throw unsupported(); }
        @Override public Registration openDialog(DialogRequest request) { throw unsupported(); }
        @Override public boolean confirmDialog(DialogRequest request) { return confirmResult; }
        @Override public Registration contributeEmbeddedPanel(EmbeddedPanelContribution contribution) { throw unsupported(); }
        @Override public Optional<String> requestFile(FileChooserRequest request) { return chosenFile; }
        @Override public Registration notifyStatus(StatusNotification notification) {
            notifications.add(notification);
            return () -> notifications.remove(notification);
        }
        @Override public Registration contributeContextMenu(ContextMenuRegistry.ContextMenuContribution contribution) { throw unsupported(); }
        @Override public Registration contributeMainToolbar(MainToolbarRegistry.MainToolbarContribution contribution) { throw unsupported(); }
        @Override public Registration contributePaletteToolbar(PaletteToolbarRegistry.PaletteToolbarContribution contribution) { throw unsupported(); }
        private static UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("not used");
        }
    }

    private static final class TestPluginLogger implements PluginLogger {
        private final List<String> messages = new ArrayList<>();
        @Override public void debug(String message) { messages.add("DEBUG: " + message); }
        @Override public void info(String message) { messages.add("INFO: " + message); }
        @Override public void warn(String message) { messages.add("WARN: " + message); }
        @Override public void error(String message) { messages.add("ERROR: " + message); }
        @Override public void error(String message, Throwable throwable) {
            messages.add("ERROR: " + message + ": " + throwable.getMessage());
        }
        List<String> messages() { return List.copyOf(messages); }
    }
}
