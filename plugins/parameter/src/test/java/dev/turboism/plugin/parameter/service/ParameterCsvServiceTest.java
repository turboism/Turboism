package dev.turboism.plugin.parameter.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.CubismRuntimeSnapshot;
import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.ModelSnapshot;
import dev.turboism.sdk.cubism.ProjectSnapshot;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.ui.StatusNotification;
import dev.turboism.test.fake.FakeModel;
import dev.turboism.test.fake.FakeParameter;
import dev.turboism.test.plugin.FakePluginContext;
import dev.turboism.test.ui.RecordingUiHost;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ParameterCsvServiceTest {

    @Test
    void exportCsvBuildsRowsAndNotifiesInfo() {
        RecordingUiHost uiHost = new RecordingUiHost();
        ParameterCsvService service =
                new ParameterCsvService(new RecordingFacade(), new MinimalPluginContext(), uiHost);

        service.exportCsv();

        assertEquals("id,value\np1,0.5\np2,0.25\n", service.lastExportCsv());
        assertEquals(
                List.of(new StatusNotification(
                        "parameter.csv.export.completed", "INFO", "Exported 2 parameter(s) to CSV.")),
                uiHost.notifications());
    }

    @Test
    void exportCsvUsesUnifiedModelParametersInsteadOfLegacySnapshots() {
        RecordingUiHost uiHost = new RecordingUiHost();
        ParameterCsvService service =
                new ParameterCsvService(new RecordingFacade(), new MinimalPluginContext(), uiHost);

        service.exportCsv();

        assertEquals("id,value\np1,0.5\np2,0.25\n", service.lastExportCsv());
        assertEquals(
                ParameterCsvService.EXPORT_COMPLETED,
                uiHost.notifications().get(0).id());
    }

    @Test
    void exportCsvRejectsNonFiniteUnifiedModelValues() {
        RecordingFacade facade = new RecordingFacade();
        facade.model.parameter("p1").value = Float.NaN;
        ParameterCsvService service =
                new ParameterCsvService(facade, new MinimalPluginContext(), new RecordingUiHost());

        assertThrows(IllegalArgumentException.class, service::exportCsv);
    }

    @Test
    void exportCsvWarnsWhenEmpty() {
        RecordingUiHost uiHost = new RecordingUiHost();
        RecordingFacade facade = new RecordingFacade();
        facade.model.parameters.clear();
        ParameterCsvService service = new ParameterCsvService(facade, new MinimalPluginContext(), uiHost);

        service.exportCsv();

        assertEquals("", service.lastExportCsv());
        assertEquals(
                List.of(new StatusNotification(
                        "parameter.csv.export.unavailable", "WARNING", "No parameters are available for CSV export.")),
                uiHost.notifications());
    }

    @Test
    void importCsvCancelledWhenChooserEmpty() {
        RecordingUiHost uiHost = new RecordingUiHost();
        uiHost.chosenFile = Optional.empty();
        ParameterCsvService service =
                new ParameterCsvService(new RecordingFacade(), new MinimalPluginContext(), uiHost);

        service.importCsv();

        assertEquals(
                List.of(new StatusNotification(
                        "parameter.csv.import.cancelled", "WARNING", "Parameter CSV import was cancelled.")),
                uiHost.notifications());
    }

    @Test
    void importCsvUsesUnifiedModelParameters() {
        RecordingUiHost uiHost = new RecordingUiHost();
        uiHost.chosenFile = Optional.of("/tmp/params.csv");
        RecordingFacade facade = new RecordingFacade();
        ParameterCsvService service = new ParameterCsvService(
                facade, new MinimalPluginContext(), uiHost, path -> Optional.of("id,value\np1,0.75\np2,0.1\n"));

        service.importCsv();

        CubismModel model = facade.model().active();
        assertEquals(0.75f, model.parameters().find(new ParameterId("p1")).getValue());
        assertEquals(0.1f, model.parameters().find(new ParameterId("p2")).getValue());
        assertEquals(List.of("p1=0.75", "p2=0.1"), facade.parameterWrites());
        assertEquals(
                List.of(new StatusNotification(
                        "parameter.csv.import.completed", "INFO", "Imported 2 parameter value(s) from CSV.")),
                uiHost.notifications());
    }

    @Test
    void importCsvUnavailableWithoutUnifiedActiveModel() {
        RecordingUiHost uiHost = new RecordingUiHost();
        uiHost.chosenFile = Optional.of("/tmp/params.csv");
        RecordingFacade facade = new RecordingFacade();
        facade.modelAvailable = false;
        ParameterCsvService service = new ParameterCsvService(
                facade, new MinimalPluginContext(), uiHost, path -> Optional.of("id,value\np1,0.5\n"));

        service.importCsv();

        assertEquals(
                "parameter.csv.import.unavailable",
                uiHost.notifications().get(0).id());
    }

    @Test
    void importCsvFailsOnInvalidRows() {
        RecordingUiHost uiHost = new RecordingUiHost();
        uiHost.chosenFile = Optional.of("/tmp/params.csv");
        ParameterCsvService service = new ParameterCsvService(
                new RecordingFacade(),
                new MinimalPluginContext(),
                uiHost,
                path -> Optional.of("id,value\np1,not-a-number\n"));

        service.importCsv();

        assertEquals(
                "parameter.csv.import.failed", uiHost.notifications().get(0).id());
    }

    @Test
    void importCsvWarnsWhenUnifiedSetterFails() {
        RecordingUiHost uiHost = new RecordingUiHost();
        uiHost.chosenFile = Optional.of("/tmp/params.csv");
        RecordingFacade facade = new RecordingFacade();
        facade.failOnParameterId = "p1";
        ParameterCsvService service = new ParameterCsvService(
                facade, new MinimalPluginContext(), uiHost, path -> Optional.of("id,value\np1,0.5\n"));

        service.importCsv();

        assertEquals(
                "parameter.csv.import.failed", uiHost.notifications().get(0).id());
        assertEquals("WARNING", uiHost.notifications().get(0).severity());
    }

    @Test
    void defaultContentProviderFailsClosedWithoutFilesystemIo() {
        RecordingUiHost uiHost = new RecordingUiHost();
        uiHost.chosenFile = Optional.of("imports/params.csv");
        ParameterCsvService service =
                new ParameterCsvService(new RecordingFacade(), new MinimalPluginContext(), uiHost);

        service.importCsv();

        assertEquals(
                "parameter.csv.import.unavailable",
                uiHost.notifications().get(0).id());
    }

    @Test
    void importCsvValidatesAgainstUnifiedModelMetadataInsteadOfLegacySnapshots() {
        RecordingUiHost uiHost = new RecordingUiHost();
        uiHost.chosenFile = Optional.of("imports/params.csv");
        RecordingFacade facade = new RecordingFacade();
        ParameterCsvService service = new ParameterCsvService(
                facade, new MinimalPluginContext(), uiHost, ignored -> Optional.of("id,value\np1,0.75\n"));

        service.importCsv();

        assertEquals(List.of("p1=0.75"), facade.parameterWrites());
        assertEquals(
                ParameterCsvService.IMPORT_COMPLETED,
                uiHost.notifications().get(0).id());
    }

    @Test
    void importCsvRejectsUnknownAndOutOfRangeParametersBeforeWrite() {
        for (String csv : List.of("id,value\nmissing,0.5\n", "id,value\np1,2.0\n")) {
            RecordingUiHost uiHost = new RecordingUiHost();
            uiHost.chosenFile = Optional.of("imports/params.csv");
            RecordingFacade facade = new RecordingFacade();
            ParameterCsvService service =
                    new ParameterCsvService(facade, new MinimalPluginContext(), uiHost, ignored -> Optional.of(csv));

            service.importCsv();

            assertTrue(facade.parameterWrites().isEmpty());
            assertEquals(
                    "parameter.csv.import.failed", uiHost.notifications().get(0).id());
        }
    }

    @Test
    void parseCsvStrictRejectsNonFiniteDuplicateAndOversizedInput() {
        assertTrue(
                ParameterCsvService.parseCsvStrict("id,value\np1,NaN\n").rows().isEmpty());
        assertTrue(ParameterCsvService.parseCsvStrict("id,value\np1,Infinity\n")
                .rows()
                .isEmpty());
        assertTrue(ParameterCsvService.parseCsvStrict("id,value\np1,0.1\np1,0.2\n").errors().stream()
                .anyMatch(message -> message.contains("duplicate")));

        String oversized = "x".repeat(ParameterCsvService.MAX_CSV_CHARACTERS + 1);
        assertTrue(ParameterCsvService.parseCsvStrict(oversized).errors().stream()
                .anyMatch(message -> message.contains("maximum size")));
    }

    @Test
    void unifiedSetterFailureUsesSafeUserMessage() {
        RecordingUiHost uiHost = new RecordingUiHost();
        uiHost.chosenFile = Optional.of("imports/params.csv");
        RecordingFacade facade = new RecordingFacade();
        facade.failOnParameterId = "p1";
        ParameterCsvService service = new ParameterCsvService(
                facade, new MinimalPluginContext(), uiHost, ignored -> Optional.of("id,value\np1,0.5\n"));

        service.importCsv();

        assertEquals(
                "Parameter CSV import failed. Some values may require Undo in Cubism.",
                uiHost.notifications().get(0).message());
    }

    @Test
    void importCsvRunsAllWritesInsideOneAuthoringTransaction() {
        RecordingUiHost uiHost = new RecordingUiHost();
        uiHost.chosenFile = Optional.of("import.csv");
        RecordingFacade facade = new RecordingFacade();
        RecordingTransactions transactions = new RecordingTransactions();
        facade.transactions = transactions;
        ParameterCsvService service = new ParameterCsvService(
                facade, new MinimalPluginContext(), uiHost, ignored -> Optional.of("id,value\np1,0.75\np2,0.125\n"));

        service.importCsv();

        assertEquals(1, transactions.executeCount);
        assertEquals(List.of("p1=0.75", "p2=0.125"), facade.parameterWrites());
        assertEquals(
                ParameterCsvService.IMPORT_COMPLETED,
                uiHost.notifications().get(0).id());
    }

    @Test
    void importCsvFallsBackToSequentialWritesWhenTransactionsUnavailable() {
        RecordingUiHost uiHost = new RecordingUiHost();
        uiHost.chosenFile = Optional.of("import.csv");
        RecordingFacade facade = new RecordingFacade();
        ParameterCsvService service = new ParameterCsvService(
                facade, new MinimalPluginContext(), uiHost, ignored -> Optional.of("id,value\np1,0.75\n"));

        service.importCsv();

        assertEquals(List.of("p1=0.75"), facade.parameterWrites());
        assertEquals(
                ParameterCsvService.IMPORT_COMPLETED,
                uiHost.notifications().get(0).id());
    }

    @Test
    void importCsvFallsBackToSequentialWritesWhenScopeRejected() {
        RecordingUiHost uiHost = new RecordingUiHost();
        uiHost.chosenFile = Optional.of("import.csv");
        RecordingFacade facade = new RecordingFacade();
        RecordingTransactions transactions = new RecordingTransactions();
        transactions.rejectScope = true;
        facade.transactions = transactions;
        ParameterCsvService service = new ParameterCsvService(
                facade, new MinimalPluginContext(), uiHost, ignored -> Optional.of("id,value\np1,0.75\n"));

        service.importCsv();

        assertEquals(List.of("p1=0.75"), facade.parameterWrites());
        assertEquals(
                ParameterCsvService.IMPORT_COMPLETED,
                uiHost.notifications().get(0).id());
    }

    @Test
    void importCsvReportsFailureWhenTransactionAborts() {
        RecordingUiHost uiHost = new RecordingUiHost();
        uiHost.chosenFile = Optional.of("import.csv");
        RecordingFacade facade = new RecordingFacade();
        RecordingTransactions transactions = new RecordingTransactions();
        transactions.abortNext = true;
        facade.transactions = transactions;
        ParameterCsvService service = new ParameterCsvService(
                facade, new MinimalPluginContext(), uiHost, ignored -> Optional.of("id,value\np1,0.75\n"));

        service.importCsv();

        assertEquals(
                ParameterCsvService.IMPORT_FAILED, uiHost.notifications().get(0).id());
    }

    private static final class RecordingTransactions
            implements dev.turboism.sdk.cubism.transaction.AuthoringTransactionService {
        private int executeCount;
        private boolean abortNext;
        private boolean rejectScope;

        @Override
        public <T> dev.turboism.sdk.cubism.transaction.AuthoringTransactionResult<T> execute(
                final dev.turboism.sdk.cubism.transaction.AuthoringTransactionOptions options,
                final dev.turboism.sdk.cubism.transaction.AuthoringTransactionWork<T> work) {
            executeCount++;
            if (abortNext) {
                return dev.turboism.sdk.cubism.transaction.AuthoringTransactionResult.rolledBack(receipt(), "aborted");
            }
            if (rejectScope) {
                return dev.turboism.sdk.cubism.transaction.AuthoringTransactionResult.rejectedScope(
                        Optional.empty(), "ambient-scope");
            }
            try {
                final T value = work.run();
                return dev.turboism.sdk.cubism.transaction.AuthoringTransactionResult.committed(value, receipt());
            } catch (Exception failure) {
                throw new AssertionError("work failed", failure);
            }
        }

        private static dev.turboism.sdk.cubism.transaction.AuthoringTransactionReceipt receipt() {
            return new dev.turboism.sdk.cubism.transaction.AuthoringTransactionReceipt(
                    "tx-test",
                    "test",
                    dev.turboism.sdk.cubism.history.HistorySnapshot.unavailable(),
                    dev.turboism.sdk.cubism.history.HistorySnapshot.unavailable(),
                    Optional.of("entry-1"));
        }
    }

    @Test
    void parseCsvStrictSkipsHeaderAndComments() {
        ParameterCsvService.ParseResult parsed =
                ParameterCsvService.parseCsvStrict("# comment\nid,value\np1,1.0\n\np2,2.5\n");
        assertTrue(parsed.errors().isEmpty());
        assertEquals(2, parsed.rows().size());
        assertEquals("p1", parsed.rows().get(0).id());
        assertEquals(1.0f, parsed.rows().get(0).value());
    }

    private static final class RecordingFacade implements CubismFacade {
        private final List<String> writes = new ArrayList<>();
        private final FakeModel model = new FakeModel()
                .addParameter(new FakeParameter("p1", "p1")
                        .withRange(-1.0f, 1.0f)
                        .withValue(0.5f)
                        .beforeSetValue(value -> recordWrite("p1", value)))
                .addParameter(new FakeParameter("p2", "p2")
                        .withRange(-1.0f, 1.0f)
                        .withValue(0.25f)
                        .beforeSetValue(value -> recordWrite("p2", value)));
        private boolean modelAvailable = true;
        private String failOnParameterId;
        private dev.turboism.sdk.cubism.transaction.AuthoringTransactionService transactions =
                dev.turboism.sdk.cubism.transaction.AuthoringTransactionService.unavailable();

        List<String> parameterWrites() {
            return List.copyOf(writes);
        }

        private void recordWrite(final String id, final float value) {
            if (id.equals(failOnParameterId)) {
                throw new IllegalStateException("write failed");
            }
            writes.add(id + "=" + value);
        }

        @Override
        public CubismRuntimeSnapshot runtime() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ProjectSnapshot> activeProject() {
            return Optional.empty();
        }

        @Override
        public Optional<DocumentSnapshot> activeDocument() {
            return Optional.empty();
        }

        @Override
        public Optional<ModelSnapshot> activeModel() {
            return Optional.empty();
        }

        @Override
        public boolean isHostPresent() {
            return false;
        }

        @Override
        public dev.turboism.sdk.cubism.model.CubismModelAccess model() {
            return () -> {
                if (!modelAvailable) {
                    throw new IllegalStateException("no active model");
                }
                return model;
            };
        }

        @Override
        public dev.turboism.sdk.cubism.transaction.AuthoringTransactionService authoringTransactions() {
            return transactions;
        }
    }

    private static final class MinimalPluginContext extends FakePluginContext {}
}
