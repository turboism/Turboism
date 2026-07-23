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
import dev.turboism.sdk.ui.context.ContextSourceSnapshot;
import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;
import dev.turboism.sdk.ui.toolbar.PaletteToolbarRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
            List.of("parameter.csv.export", "parameter.csv.import"),
            context.actions().actions().stream().map(ActionRegistry.Action::id).toList()
        );
        context.actions().execute("parameter.csv.export");
        assertEquals(
            List.of(new StatusNotification(
                "parameter.csv.export.completed",
                "INFO",
                "Exported 1 parameter(s) to CSV."
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
        private final RecordingUiHost uiHost = new RecordingUiHost();
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
        @Override public MenuRegistry menus() { throw new UnsupportedOperationException(); }
        @Override public UiScheduler uiScheduler() { throw new UnsupportedOperationException(); }
        @Override public DiagnosticReport diagnostics() { throw new UnsupportedOperationException(); }
        @Override public DisposableScope disposableScope() { return disposableScope; }
        @Override public RecordingUiHost uiHost() { return uiHost; }
    }

    private static final class FixedCubismFacade implements CubismFacade {
        private final List<String> writes = new ArrayList<>();
        private float parameterValue = 0.5f;

        float parameterValue() { return parameterValue; }
        List<String> writes() { return List.copyOf(writes); }

        @Override public CubismRuntimeSnapshot runtime() { throw new UnsupportedOperationException(); }
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

                @Override public ModelId id() { return new ModelId("model-1"); }
                @Override public dev.turboism.sdk.cubism.model.Parameters parameters() {
                    return new dev.turboism.sdk.cubism.model.Parameters() {
                        @Override public List<Parameter> all() { return List.of(parameter); }
                        @Override public Parameter find(ParameterId id) { return parameter; }
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
            actions.stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow()
                .handler().accept(new ActionContext() {});
        }
    }

    private static final class RecordingUiHost implements UiHostCapabilityService {
        private final List<StatusNotification> notifications = new ArrayList<>();
        private Optional<String> chosenFile = Optional.empty();
        List<StatusNotification> notifications() { return notifications; }
        @Override public Registration contributeOverlay(OverlayContribution contribution) { throw unsupported(); }
        @Override public ContextSourceSnapshot contextSource() { throw unsupported(); }
        @Override public ViewportSnapshot viewport() { throw unsupported(); }
        @Override public Registration openDialog(DialogRequest request) { throw unsupported(); }
        @Override public boolean confirmDialog(DialogRequest request) { throw unsupported(); }
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
