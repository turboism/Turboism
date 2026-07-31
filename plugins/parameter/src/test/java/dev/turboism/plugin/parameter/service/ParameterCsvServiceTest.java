package dev.turboism.plugin.parameter.service;

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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParameterCsvServiceTest {

    @Test
    void exportCsvBuildsRowsAndNotifiesInfo() {
        RecordingUiHost uiHost = new RecordingUiHost();
        ParameterCsvService service = new ParameterCsvService(
            new RecordingFacade(),
            new MinimalPluginContext(),
            uiHost
        );

        service.exportCsv();

        assertEquals("id,value\np1,0.5\np2,0.25\n", service.lastExportCsv());
        assertEquals(
            List.of(new StatusNotification(
                "parameter.csv.export.completed",
                "INFO",
                "Exported 2 parameter(s) to CSV."
            )),
            uiHost.notifications()
        );
    }

    @Test
    void exportCsvUsesUnifiedModelParametersInsteadOfLegacySnapshots() {
        RecordingUiHost uiHost = new RecordingUiHost();
        ParameterCsvService service = new ParameterCsvService(
            new RecordingFacade(),
            new MinimalPluginContext(),
            uiHost
        );

        service.exportCsv();

        assertEquals("id,value\np1,0.5\np2,0.25\n", service.lastExportCsv());
        assertEquals(ParameterCsvService.EXPORT_COMPLETED, uiHost.notifications().get(0).id());
    }

    @Test
    void exportCsvRejectsNonFiniteUnifiedModelValues() {
        RecordingFacade facade = new RecordingFacade();
        facade.model.parameter("p1").value = Float.NaN;
        ParameterCsvService service = new ParameterCsvService(
            facade,
            new MinimalPluginContext(),
            new RecordingUiHost()
        );

        assertThrows(IllegalArgumentException.class, service::exportCsv);
    }

    @Test
    void exportCsvWarnsWhenEmpty() {
        RecordingUiHost uiHost = new RecordingUiHost();
        RecordingFacade facade = new RecordingFacade();
        facade.model.parameters.clear();
        ParameterCsvService service = new ParameterCsvService(
            facade,
            new MinimalPluginContext(),
            uiHost
        );

        service.exportCsv();

        assertEquals("", service.lastExportCsv());
        assertEquals(
            List.of(new StatusNotification(
                "parameter.csv.export.unavailable",
                "WARNING",
                "No parameters are available for CSV export."
            )),
            uiHost.notifications()
        );
    }

    @Test
    void importCsvCancelledWhenChooserEmpty() {
        RecordingUiHost uiHost = new RecordingUiHost();
        uiHost.chosenFile = Optional.empty();
        ParameterCsvService service = new ParameterCsvService(
            new RecordingFacade(),
            new MinimalPluginContext(),
            uiHost
        );

        service.importCsv();

        assertEquals(
            List.of(new StatusNotification(
                "parameter.csv.import.cancelled",
                "WARNING",
                "Parameter CSV import was cancelled."
            )),
            uiHost.notifications()
        );
    }

    @Test
    void importCsvUsesUnifiedModelParametersWithoutOpeningLegacyTransaction() {
        RecordingUiHost uiHost = new RecordingUiHost();
        uiHost.chosenFile = Optional.of("/tmp/params.csv");
        RecordingFacade facade = new RecordingFacade();
        ParameterCsvService service = new ParameterCsvService(
            facade,
            new MinimalPluginContext(),
            uiHost,
            path -> Optional.of("id,value\np1,0.75\np2,0.1\n")
        );

        service.importCsv();

        CubismModel model = facade.model().active();
        assertEquals(0.75f, model.parameters().find(new ParameterId("p1")).getValue());
        assertEquals(0.1f, model.parameters().find(new ParameterId("p2")).getValue());
        assertEquals(List.of("p1=0.75", "p2=0.1"), facade.parameterWrites());
        assertEquals(0, facade.transactionOpenCount());
        assertEquals(
            List.of(new StatusNotification(
                "parameter.csv.import.completed",
                "INFO",
                "Imported 2 parameter value(s) from CSV."
            )),
            uiHost.notifications()
        );
    }

    @Test
    void importCsvUnavailableWithoutUnifiedActiveModel() {
        RecordingUiHost uiHost = new RecordingUiHost();
        uiHost.chosenFile = Optional.of("/tmp/params.csv");
        RecordingFacade facade = new RecordingFacade();
        facade.modelAvailable = false;
        ParameterCsvService service = new ParameterCsvService(
            facade,
            new MinimalPluginContext(),
            uiHost,
            path -> Optional.of("id,value\np1,0.5\n")
        );

        service.importCsv();

        assertEquals("parameter.csv.import.unavailable", uiHost.notifications().get(0).id());
    }

    @Test
    void importCsvFailsOnInvalidRows() {
        RecordingUiHost uiHost = new RecordingUiHost();
        uiHost.chosenFile = Optional.of("/tmp/params.csv");
        ParameterCsvService service = new ParameterCsvService(
            new RecordingFacade(),
            new MinimalPluginContext(),
            uiHost,
            path -> Optional.of("id,value\np1,not-a-number\n")
        );

        service.importCsv();

        assertEquals("parameter.csv.import.failed", uiHost.notifications().get(0).id());
    }

    @Test
    void importCsvWarnsWhenUnifiedSetterFails() {
        RecordingUiHost uiHost = new RecordingUiHost();
        uiHost.chosenFile = Optional.of("/tmp/params.csv");
        RecordingFacade facade = new RecordingFacade();
        facade.failOnParameterId = "p1";
        ParameterCsvService service = new ParameterCsvService(
            facade,
            new MinimalPluginContext(),
            uiHost,
            path -> Optional.of("id,value\np1,0.5\n")
        );

        service.importCsv();

        assertEquals(0, facade.transactionOpenCount());
        assertEquals("parameter.csv.import.failed", uiHost.notifications().get(0).id());
        assertEquals("WARNING", uiHost.notifications().get(0).severity());
    }

    @Test
    void defaultContentProviderFailsClosedWithoutFilesystemIo() {
        RecordingUiHost uiHost = new RecordingUiHost();
        uiHost.chosenFile = Optional.of("imports/params.csv");
        ParameterCsvService service = new ParameterCsvService(
            new RecordingFacade(),
            new MinimalPluginContext(),
            uiHost
        );

        service.importCsv();

        assertEquals("parameter.csv.import.unavailable", uiHost.notifications().get(0).id());
    }

    @Test
    void importCsvValidatesAgainstUnifiedModelMetadataInsteadOfLegacySnapshots() {
        RecordingUiHost uiHost = new RecordingUiHost();
        uiHost.chosenFile = Optional.of("imports/params.csv");
        RecordingFacade facade = new RecordingFacade();
        ParameterCsvService service = new ParameterCsvService(
            facade,
            new MinimalPluginContext(),
            uiHost,
            ignored -> Optional.of("id,value\np1,0.75\n")
        );

        service.importCsv();

        assertEquals(List.of("p1=0.75"), facade.parameterWrites());
        assertEquals(ParameterCsvService.IMPORT_COMPLETED, uiHost.notifications().get(0).id());
    }

    @Test
    void importCsvRejectsUnknownAndOutOfRangeParametersBeforeWrite() {
        for (String csv : List.of(
            "id,value\nmissing,0.5\n",
            "id,value\np1,2.0\n"
        )) {
            RecordingUiHost uiHost = new RecordingUiHost();
            uiHost.chosenFile = Optional.of("imports/params.csv");
            RecordingFacade facade = new RecordingFacade();
            ParameterCsvService service = new ParameterCsvService(
                facade,
                new MinimalPluginContext(),
                uiHost,
                ignored -> Optional.of(csv)
            );

            service.importCsv();

            assertTrue(facade.parameterWrites().isEmpty());
            assertEquals("parameter.csv.import.failed", uiHost.notifications().get(0).id());
        }
    }

    @Test
    void parseCsvStrictRejectsNonFiniteDuplicateAndOversizedInput() {
        assertTrue(ParameterCsvService.parseCsvStrict("id,value\np1,NaN\n").rows().isEmpty());
        assertTrue(ParameterCsvService.parseCsvStrict("id,value\np1,Infinity\n").rows().isEmpty());
        assertTrue(ParameterCsvService.parseCsvStrict("id,value\np1,0.1\np1,0.2\n").errors()
            .stream().anyMatch(message -> message.contains("duplicate")));

        String oversized = "x".repeat(ParameterCsvService.MAX_CSV_CHARACTERS + 1);
        assertTrue(ParameterCsvService.parseCsvStrict(oversized).errors()
            .stream().anyMatch(message -> message.contains("maximum size")));
    }

    @Test
    void unifiedSetterFailureUsesSafeUserMessage() {
        RecordingUiHost uiHost = new RecordingUiHost();
        uiHost.chosenFile = Optional.of("imports/params.csv");
        RecordingFacade facade = new RecordingFacade();
        facade.failOnParameterId = "p1";
        ParameterCsvService service = new ParameterCsvService(
            facade,
            new MinimalPluginContext(),
            uiHost,
            ignored -> Optional.of("id,value\np1,0.5\n")
        );

        service.importCsv();

        assertEquals(
            "Parameter CSV import failed. Some values may require Undo in Cubism.",
            uiHost.notifications().get(0).message()
        );
    }

    @Test
    void parseCsvStrictSkipsHeaderAndComments() {
        ParameterCsvService.ParseResult parsed = ParameterCsvService.parseCsvStrict(
            "# comment\nid,value\np1,1.0\n\np2,2.5\n"
        );
        assertTrue(parsed.errors().isEmpty());
        assertEquals(2, parsed.rows().size());
        assertEquals("p1", parsed.rows().get(0).id());
        assertEquals(1.0f, parsed.rows().get(0).value());
    }

    private static final class RecordingFacade implements CubismFacade {
        private final RecordingModel model = new RecordingModel();
        private boolean modelAvailable = true;
        private String failOnParameterId;
        private int transactionOpenCount;

        List<String> parameterWrites() { return model.parameterWrites(); }
        int transactionOpenCount() { return transactionOpenCount; }

        @Override public CubismRuntimeSnapshot runtime() { throw new UnsupportedOperationException(); }
        @Override public Optional<ProjectSnapshot> activeProject() { return Optional.empty(); }
        @Override public Optional<DocumentSnapshot> activeDocument() { return Optional.empty(); }
        @Override public Optional<ModelSnapshot> activeModel() { return Optional.empty(); }
        @Override public boolean isHostPresent() { return false; }
        @Override public dev.turboism.sdk.cubism.model.CubismModelAccess model() {
            return () -> {
                if (!modelAvailable) {
                    throw new IllegalStateException("no active model");
                }
                model.failOnParameterId = failOnParameterId;
                return model;
            };
        }

        @Override
        public TransactionManager transactionManager() {
            return (ctx, docId) -> {
                transactionOpenCount++;
                throw new AssertionError("legacy transaction manager must not be used");
            };
        }
    }

    private static final class RecordingModel implements CubismModel {
        private final List<RecordingParameter> parameters = new ArrayList<>(List.of(
            new RecordingParameter("p1", 0.5f, -1.0f, 1.0f),
            new RecordingParameter("p2", 0.25f, -1.0f, 1.0f)
        ));
        private final List<String> writes = new ArrayList<>();
        private String failOnParameterId;

        List<String> parameterWrites() { return List.copyOf(writes); }
        RecordingParameter parameter(String id) {
            return parameters.stream()
                .filter(parameter -> parameter.id.value().equals(id))
                .findFirst()
                .orElseThrow();
        }

        @Override public ModelId id() { return new ModelId("model-1"); }
        @Override public dev.turboism.sdk.cubism.model.Parameters parameters() {
            return new dev.turboism.sdk.cubism.model.Parameters() {
                @Override public List<Parameter> all() { return List.copyOf(parameters); }
                @Override public Parameter find(ParameterId id) {
                    return parameters.stream()
                        .filter(parameter -> parameter.id().equals(id))
                        .findFirst()
                        .orElseThrow();
                }
            };
        }
        @Override public dev.turboism.sdk.cubism.model.Parts parts() { throw unsupported(); }
        @Override public dev.turboism.sdk.cubism.model.Drawables drawables() { throw unsupported(); }
        @Override public dev.turboism.sdk.cubism.model.Deformers deformers() { throw unsupported(); }
        @Override public dev.turboism.sdk.cubism.model.Glues glues() { throw unsupported(); }
        @Override public void update() { throw unsupported(); }

        private final class RecordingParameter implements Parameter {
            private final ParameterId id;
            private final float minimum;
            private final float maximum;
            private float value;

            private RecordingParameter(String id, float value, float minimum, float maximum) {
                this.id = new ParameterId(id);
                this.value = value;
                this.minimum = minimum;
                this.maximum = maximum;
            }

            @Override public ParameterId id() { return id; }
            @Override public float getValue() { return value; }
            @Override public float getMinimumValue() { return minimum; }
            @Override public float getMaximumValue() { return maximum; }
            @Override public float getDefaultValue() { return 0.0f; }
            @Override public void setValue(float value) {
                if (id.value().equals(failOnParameterId)) {
                    throw new IllegalStateException("write failed");
                }
                this.value = value;
                writes.add(id.value() + "=" + value);
            }
        }

        private static UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("not used");
        }
    }

    private static final class MinimalPluginContext implements PluginContext {
        private final DisposableScope scope = new DisposableScope();

        @Override public PluginDescriptor descriptor() {
            return new PluginDescriptor() {
                @Override public String id() { return "dev.turboism.plugin.parameter"; }
                @Override public String name() { return "Parameter"; }
                @Override public String version() { return "0.1.0"; }
                @Override public String description() { return "test"; }
                @Override public List<String> entrypoints() { return List.of("dev.turboism.plugin.parameter.ParameterPlugin"); }
                @Override public String turboismApi() { return "[0.1.0,0.2.0)"; }
                @Override public List<Author> authors() { return List.of(); }
                @Override public String license() { return "Project License"; }
                @Override public Optional<String> website() { return Optional.of("https://turboism.dev"); }
                @Override public List<String> resources() { return List.of(); }
                @Override public I18n i18n() {
                    return new I18n() {
                        @Override public String baseName() { return "META-INF/turboism/i18n/messages"; }
                        @Override public List<String> locales() { return List.of(); }
                    };
                }
                @Override public List<DependencyRef> dependencies() { return List.of(); }
                @Override public List<PermissionRef> permissions() { return List.of(); }
                @Override public List<String> capabilities() { return List.of(); }
                @Override public Environment environment() {
                    return new Environment() {
                        @Override public boolean requiresCubism() { return false; }
                        @Override public String ui() { return "none"; }
                    };
                }
            };
        }
        @Override public PluginLogger logger() { return new PluginLogger() {
            @Override public void debug(String message) {}
            @Override public void info(String message) {}
            @Override public void warn(String message) {}
            @Override public void error(String message) {}
            @Override public void error(String message, Throwable throwable) {}
        }; }
        @Override public PluginPaths paths() { throw new UnsupportedOperationException(); }
        @Override public CubismFacade cubism() { throw new UnsupportedOperationException(); }
        @Override public List<PluginPermission> permissions() { return List.of(); }
        @Override public dev.turboism.sdk.event.EventBus eventBus() { throw new UnsupportedOperationException(); }
        @Override public dev.turboism.sdk.action.ActionRegistry actions() { throw new UnsupportedOperationException(); }
        @Override public dev.turboism.sdk.menu.MenuRegistry menus() { throw new UnsupportedOperationException(); }
        @Override public dev.turboism.sdk.ui.UiScheduler uiScheduler() { throw new UnsupportedOperationException(); }
        @Override public dev.turboism.sdk.diagnostics.DiagnosticReport diagnostics() { throw new UnsupportedOperationException(); }
        @Override public DisposableScope disposableScope() { return scope; }
    }

    private static final class RecordingUiHost implements UiHostCapabilityService {
        private final List<StatusNotification> notifications = new ArrayList<>();
        private Optional<String> chosenFile = Optional.empty();

        List<StatusNotification> notifications() { return notifications; }

        @Override public Registration contributeOverlay(OverlayContribution contribution) { throw unsupported(); }
        @Override public Registration contributeBoundingBoxOverlayButton(dev.turboism.sdk.ui.BoundingBoxOverlayButton contribution) { throw unsupported(); }
        @Override public ContextSourceSnapshot contextSource() { throw unsupported(); }
        @Override public ViewportSnapshot viewport() { throw unsupported(); }
        @Override public Registration openDialog(DialogRequest request) { throw unsupported(); }
        @Override public boolean confirmDialog(DialogRequest request) { throw unsupported(); }
        @Override public Registration contributeEmbeddedPanel(EmbeddedPanelContribution contribution) { throw unsupported(); }

        @Override
        public Optional<String> requestFile(FileChooserRequest request) {
            return chosenFile;
        }

        @Override
        public Registration notifyStatus(StatusNotification notification) {
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
}
