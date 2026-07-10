package dev.turboism.plugin.parameter.service;

import dev.turboism.sdk.cubism.ArtMeshSnapshot;
import dev.turboism.sdk.cubism.ClipMaskSnapshot;
import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.CubismRuntimeSnapshot;
import dev.turboism.sdk.cubism.DeformerSnapshot;
import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.ModelObjectSnapshot;
import dev.turboism.sdk.cubism.ModelSnapshot;
import dev.turboism.sdk.cubism.ParameterSnapshot;
import dev.turboism.sdk.cubism.ProjectSnapshot;
import dev.turboism.sdk.cubism.PsdDocumentSnapshot;
import dev.turboism.sdk.cubism.RenderStatusSnapshot;
import dev.turboism.sdk.cubism.SelectionSnapshot;
import dev.turboism.sdk.cubism.TextureAtlasSnapshot;
import dev.turboism.sdk.cubism.WorkspaceSnapshot;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.service.read.CubismReadCapabilityService;
import dev.turboism.sdk.cubism.transaction.DocumentId;
import dev.turboism.sdk.cubism.transaction.ModelTransaction;
import dev.turboism.sdk.cubism.transaction.TransactionException;
import dev.turboism.sdk.cubism.transaction.TransactionManager;
import dev.turboism.sdk.cubism.transaction.TransactionStatus;
import dev.turboism.sdk.cubism.write.CubismWriteCommand;
import dev.turboism.sdk.cubism.write.WriteParameterCommand;
import dev.turboism.sdk.permission.PluginPermission;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.PluginPaths;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.theme.ThemeStatusSnapshot;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParameterCsvServiceTest {

    private static final List<ParameterSnapshot> SAMPLE_PARAMS = List.of(
        new ParameterSnapshot("p1", "P1", 0.5, 0.0, -1.0, 1.0, true, true),
        new ParameterSnapshot("p2", "P2", 0.25, 0.0, -1.0, 1.0, true, true)
    );

    @Test
    void exportCsvBuildsRowsAndNotifiesInfo() {
        RecordingUiHost uiHost = new RecordingUiHost();
        ParameterCsvService service = new ParameterCsvService(
            new FixedCubismRead(SAMPLE_PARAMS, presentDoc(), presentModel()),
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
    void exportCsvWarnsWhenEmpty() {
        RecordingUiHost uiHost = new RecordingUiHost();
        ParameterCsvService service = new ParameterCsvService(
            new FixedCubismRead(List.of(), Optional.empty(), Optional.empty()),
            new RecordingFacade(),
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
            new FixedCubismRead(List.of(), presentDoc(), presentModel()),
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
    void importCsvCommitsWriteCommandsUsingActiveDocumentAndModel() {
        RecordingUiHost uiHost = new RecordingUiHost();
        uiHost.chosenFile = Optional.of("/tmp/params.csv");
        RecordingFacade facade = new RecordingFacade();
        ParameterCsvService service = new ParameterCsvService(
            new FixedCubismRead(SAMPLE_PARAMS, presentDoc(), presentModel()),
            facade,
            new MinimalPluginContext(),
            uiHost,
            path -> Optional.of("id,value\np1,0.75\np2,0.1\n")
        );

        service.importCsv();

        assertEquals(2, facade.transaction().enqueued().size());
        assertTrue(facade.transaction().committed());
        assertEquals(new DocumentId("document-1"), facade.transaction().documentId());
        WriteParameterCommand first = (WriteParameterCommand) facade.transaction().enqueued().get(0);
        assertEquals(new ModelId("model-1"), first.modelId());
        assertEquals(new ParameterId("p1"), first.parameterId());
        assertEquals(0.75f, first.value());
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
    void importCsvUnavailableWithoutActiveDocument() {
        RecordingUiHost uiHost = new RecordingUiHost();
        uiHost.chosenFile = Optional.of("/tmp/params.csv");
        ParameterCsvService service = new ParameterCsvService(
            new FixedCubismRead(SAMPLE_PARAMS, Optional.empty(), presentModel()),
            new RecordingFacade(),
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
            new FixedCubismRead(SAMPLE_PARAMS, presentDoc(), presentModel()),
            new RecordingFacade(),
            new MinimalPluginContext(),
            uiHost,
            path -> Optional.of("id,value\np1,not-a-number\n")
        );

        service.importCsv();

        assertEquals("parameter.csv.import.failed", uiHost.notifications().get(0).id());
    }

    @Test
    void importCsvRollsBackAndWarnsOnFailure() {
        RecordingUiHost uiHost = new RecordingUiHost();
        uiHost.chosenFile = Optional.of("/tmp/params.csv");
        RecordingFacade facade = new RecordingFacade();
        facade.failOnCommit = true;
        ParameterCsvService service = new ParameterCsvService(
            new FixedCubismRead(SAMPLE_PARAMS, presentDoc(), presentModel()),
            facade,
            new MinimalPluginContext(),
            uiHost,
            path -> Optional.of("id,value\np1,0.5\n")
        );

        service.importCsv();

        assertTrue(facade.transaction().rolledBack());
        assertEquals("parameter.csv.import.failed", uiHost.notifications().get(0).id());
        assertEquals("WARNING", uiHost.notifications().get(0).severity());
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

    private static Optional<DocumentSnapshot> presentDoc() {
        return Optional.of(new DocumentSnapshot(
            "document-1",
            "Doc",
            "models/demo.cmo3",
            Optional.empty(),
            Optional.empty()
        ));
    }

    private static Optional<ModelSnapshot> presentModel() {
        return Optional.of(new ModelSnapshot(
            "model-1",
            "Model",
            List.of(),
            List.of(),
            List.of(),
            List.of()
        ));
    }

    private record FixedCubismRead(
        List<ParameterSnapshot> parameters,
        Optional<DocumentSnapshot> document,
        Optional<ModelSnapshot> model
    ) implements CubismReadCapabilityService {
        @Override public Optional<ProjectSnapshot> activeProject() { throw unsupported(); }
        @Override public Optional<DocumentSnapshot> activeDocument() { return document; }
        @Override public Optional<ModelSnapshot> activeModel() { return model; }
        @Override public SelectionSnapshot selection() { throw unsupported(); }
        @Override public List<ParameterSnapshot> parameters() { return parameters; }
        @Override public List<ModelObjectSnapshot> modelObjects() { throw unsupported(); }
        @Override public List<ArtMeshSnapshot> meshes() { throw unsupported(); }
        @Override public List<DeformerSnapshot> deformers() { throw unsupported(); }
        @Override public List<PsdDocumentSnapshot> psdDocuments() { throw unsupported(); }
        @Override public List<ClipMaskSnapshot> clipMasks() { throw unsupported(); }
        @Override public List<TextureAtlasSnapshot> textureAtlases() { throw unsupported(); }
        @Override public Optional<RenderStatusSnapshot> renderStatus() { throw unsupported(); }
        @Override public Optional<WorkspaceSnapshot> workspace() { throw unsupported(); }
        @Override public Optional<ThemeStatusSnapshot> themeStatus() { throw unsupported(); }

        private static UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("not used");
        }
    }

    private static final class RecordingFacade implements CubismFacade {
        private final RecordingTransaction transaction = new RecordingTransaction();
        private boolean failOnCommit;

        RecordingTransaction transaction() { return transaction; }

        @Override public CubismRuntimeSnapshot runtime() { throw new UnsupportedOperationException(); }
        @Override public Optional<ProjectSnapshot> activeProject() { return Optional.empty(); }
        @Override public Optional<DocumentSnapshot> activeDocument() { return Optional.empty(); }
        @Override public Optional<ModelSnapshot> activeModel() { return Optional.empty(); }
        @Override public boolean isHostPresent() { return false; }

        @Override
        public TransactionManager transactionManager() {
            return (ctx, docId) -> {
                transaction.documentId = docId;
                if (failOnCommit) {
                    transaction.failOnCommit = true;
                }
                return transaction;
            };
        }
    }

    private static final class RecordingTransaction implements ModelTransaction {
        private final List<CubismWriteCommand> enqueued = new ArrayList<>();
        private TransactionStatus status = TransactionStatus.OPEN;
        private boolean committed;
        private boolean rolledBack;
        private boolean failOnCommit;
        private DocumentId documentId;

        List<CubismWriteCommand> enqueued() { return enqueued; }
        boolean committed() { return committed; }
        boolean rolledBack() { return rolledBack; }
        DocumentId documentId() { return documentId; }

        @Override public TransactionStatus status() { return status; }

        @Override
        public void enqueue(CubismWriteCommand command) {
            enqueued.add(command);
        }

        @Override
        public void commit() throws TransactionException {
            if (failOnCommit) {
                status = TransactionStatus.FAILED;
                throw new TransactionException("tx-test", 1, "ERROR", "commit failed");
            }
            committed = true;
            status = TransactionStatus.COMMITTED;
        }

        @Override
        public void rollback() {
            rolledBack = true;
            status = TransactionStatus.ROLLED_BACK;
        }

        @Override public String transactionId() { return "tx-test"; }
    }

    private static final class MinimalPluginContext implements PluginContext {
        private final DisposableScope scope = new DisposableScope();

        @Override public PluginDescriptor descriptor() {
            return new PluginDescriptor() {
                @Override public String id() { return "dev.turboism.plugin.parameter"; }
                @Override public String name() { return "Parameter"; }
                @Override public String version() { return "0.1.0"; }
                @Override public String description() { return "test"; }
                @Override public java.util.Map<String, String> entrypoints() { return java.util.Map.of(); }
                @Override public String turboismApi() { return "[0.1.0,0.2.0)"; }
                @Override public List<Author> authors() { return List.of(); }
                @Override public String license() { return "Project License"; }
                @Override public Optional<String> homepage() { return Optional.empty(); }
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
