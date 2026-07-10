package dev.turboism.plugin.parameter.service;

import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.ModelSnapshot;
import dev.turboism.sdk.cubism.ParameterSnapshot;
import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.service.read.CubismReadCapabilityService;
import dev.turboism.sdk.cubism.transaction.DocumentId;
import dev.turboism.sdk.cubism.transaction.ModelTransaction;
import dev.turboism.sdk.cubism.transaction.TransactionException;
import dev.turboism.sdk.cubism.transaction.TransactionStatus;
import dev.turboism.sdk.cubism.write.WriteParameterCommand;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.ui.FileChooserRequest;
import dev.turboism.sdk.ui.StatusNotification;
import dev.turboism.sdk.ui.UiHostCapabilityService;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * SDK-only fake-ready parameter CSV import/export service.
 * Export is pure read+status. Import uses file chooser + ModelTransaction writes.
 */
public final class ParameterCsvService {

    public static final String EXPORT_ACTION_ID = "parameter.csv.export";
    public static final String IMPORT_ACTION_ID = "parameter.csv.import";
    public static final String EXPORT_COMPLETED = "parameter.csv.export.completed";
    public static final String EXPORT_UNAVAILABLE = "parameter.csv.export.unavailable";
    public static final String IMPORT_COMPLETED = "parameter.csv.import.completed";
    public static final String IMPORT_CANCELLED = "parameter.csv.import.cancelled";
    public static final String IMPORT_UNAVAILABLE = "parameter.csv.import.unavailable";
    public static final String IMPORT_FAILED = "parameter.csv.import.failed";

    static final int MAX_CSV_CHARACTERS = 1_000_000;
    static final int MAX_CSV_ROWS = 10_000;

    private final CubismReadCapabilityService cubismRead;
    private final CubismFacade cubism;
    private final PluginContext pluginContext;
    private final UiHostCapabilityService uiHost;
    private final CsvContentProvider csvContentProvider;
    private final PluginLogger logger;

    private String lastExportCsv = "";

    public ParameterCsvService(
        final CubismReadCapabilityService cubismRead,
        final CubismFacade cubism,
        final PluginContext pluginContext,
        final UiHostCapabilityService uiHost
    ) {
        this(cubismRead, cubism, pluginContext, uiHost, CsvContentProvider.unavailable());
    }

    public ParameterCsvService(
        final CubismReadCapabilityService cubismRead,
        final CubismFacade cubism,
        final PluginContext pluginContext,
        final UiHostCapabilityService uiHost,
        final CsvContentProvider csvContentProvider
    ) {
        this.cubismRead = Objects.requireNonNull(cubismRead, "cubismRead");
        this.cubism = Objects.requireNonNull(cubism, "cubism");
        this.pluginContext = Objects.requireNonNull(pluginContext, "pluginContext");
        this.uiHost = Objects.requireNonNull(uiHost, "uiHost");
        this.csvContentProvider = Objects.requireNonNull(csvContentProvider, "csvContentProvider");
        this.logger = Objects.requireNonNull(pluginContext.logger(), "pluginContext.logger()");
    }

    public String lastExportCsv() {
        return lastExportCsv;
    }

    public void exportCsv() {
        final List<ParameterSnapshot> parameters = cubismRead.parameters();
        if (parameters.isEmpty()) {
            lastExportCsv = "";
            uiHost.notifyStatus(new StatusNotification(
                EXPORT_UNAVAILABLE,
                "WARNING",
                "No parameters are available for CSV export."
            ));
            return;
        }
        lastExportCsv = toCsv(parameters);
        uiHost.notifyStatus(new StatusNotification(
            EXPORT_COMPLETED,
            "INFO",
            "Exported " + parameters.size() + " parameter(s) to CSV."
        ));
    }

    public void importCsv() {
        final Optional<String> chosen = uiHost.requestFile(new FileChooserRequest(
            "parameter.csv.import.file",
            "Import parameter CSV",
            List.of("csv")
        ));
        if (chosen.isEmpty()) {
            uiHost.notifyStatus(new StatusNotification(
                IMPORT_CANCELLED,
                "WARNING",
                "Parameter CSV import was cancelled."
            ));
            return;
        }
        final Optional<String> content = csvContentProvider.read(chosen.orElseThrow());
        if (content.isEmpty() || content.orElseThrow().isBlank()) {
            uiHost.notifyStatus(new StatusNotification(
                IMPORT_UNAVAILABLE,
                "WARNING",
                "Parameter CSV content is unavailable."
            ));
            return;
        }
        applyCsv(content.orElseThrow());
    }

    void applyCsv(final String csvText) {
        final ParseResult parsed = parseCsvStrict(csvText);
        if (!parsed.errors().isEmpty()) {
            uiHost.notifyStatus(new StatusNotification(
                IMPORT_FAILED,
                "WARNING",
                "Parameter CSV import failed: " + parsed.errors().get(0)
            ));
            return;
        }
        if (parsed.rows().isEmpty()) {
            uiHost.notifyStatus(new StatusNotification(
                IMPORT_UNAVAILABLE,
                "WARNING",
                "Parameter CSV content is unavailable."
            ));
            return;
        }

        final Map<String, ParameterSnapshot> parametersById = new LinkedHashMap<>();
        for (final ParameterSnapshot parameter : cubismRead.parameters()) {
            parametersById.put(parameter.id(), parameter);
        }
        final Optional<String> validationFailure = validateRows(parsed.rows(), parametersById);
        if (validationFailure.isPresent()) {
            uiHost.notifyStatus(new StatusNotification(
                IMPORT_FAILED,
                "WARNING",
                "Parameter CSV import failed: " + validationFailure.orElseThrow()
            ));
            return;
        }

        final Optional<DocumentSnapshot> document = cubismRead.activeDocument();
        final Optional<ModelSnapshot> model = cubismRead.activeModel();
        if (document.isEmpty() || model.isEmpty()) {
            uiHost.notifyStatus(new StatusNotification(
                IMPORT_UNAVAILABLE,
                "WARNING",
                "Active document/model is unavailable for parameter CSV import."
            ));
            return;
        }

        final DocumentId documentId = new DocumentId(document.orElseThrow().documentId());
        final ModelId modelId = new ModelId(model.orElseThrow().modelId());
        ModelTransaction transaction = null;
        try {
            transaction = cubism.transactionManager().openTransaction(pluginContext, documentId);
            int index = 0;
            for (final CsvRow row : parsed.rows()) {
                index++;
                transaction.enqueue(new WriteParameterCommand(
                    "parameter.csv.import." + index,
                    modelId,
                    new ParameterId(row.id()),
                    row.value()
                ));
            }
            transaction.commit();
            uiHost.notifyStatus(new StatusNotification(
                IMPORT_COMPLETED,
                "INFO",
                "Imported " + parsed.rows().size() + " parameter value(s) from CSV."
            ));
        } catch (RuntimeException | TransactionException failure) {
            if (transaction != null) {
                try {
                    final TransactionStatus status = transaction.status();
                    if (status == TransactionStatus.OPEN || status == TransactionStatus.FAILED) {
                        transaction.rollback();
                    }
                } catch (Exception rollbackFailure) {
                    logger.warn("Parameter CSV rollback failed safely: "
                        + rollbackFailure.getClass().getSimpleName());
                }
            }
            logger.warn("Parameter CSV import failed safely: " + failure.getClass().getSimpleName());
            uiHost.notifyStatus(new StatusNotification(
                IMPORT_FAILED,
                "WARNING",
                "Parameter CSV import failed. No changes were retained."
            ));
        }
    }

    static String toCsv(final List<ParameterSnapshot> parameters) {
        final StringBuilder builder = new StringBuilder("id,value\n");
        for (final ParameterSnapshot parameter : parameters) {
            if (parameter.id().contains(",") || parameter.id().contains("\"") || parameter.id().contains("\n")) {
                throw new IllegalArgumentException("parameter id must not contain comma, quote, or newline: " + parameter.id());
            }
            if (!Double.isFinite(parameter.value())) {
                throw new IllegalArgumentException("parameter value must be finite: " + parameter.id());
            }
            builder.append(parameter.id())
                .append(',')
                .append(parameter.value())
                .append('\n');
        }
        return builder.toString();
    }

    static ParseResult parseCsvStrict(final String csvText) {
        Objects.requireNonNull(csvText, "csvText");
        final List<CsvRow> rows = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        if (csvText.length() > MAX_CSV_CHARACTERS) {
            return new ParseResult(List.of(), List.of(
                "CSV exceeds maximum size of " + MAX_CSV_CHARACTERS + " characters"
            ));
        }

        final Map<String, Integer> firstLineById = new LinkedHashMap<>();
        int lineNo = 0;
        final java.util.Iterator<String> lines = csvText.lines().iterator();
        while (lines.hasNext()) {
            lineNo++;
            final String line = lines.next().trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            final int comma = line.indexOf(',');
            if (comma <= 0 || comma == line.length() - 1 || comma != line.lastIndexOf(',')) {
                errors.add("line " + lineNo + ": expected exactly id,value");
                continue;
            }
            final String id = line.substring(0, comma).trim();
            final String valueText = line.substring(comma + 1).trim();
            if (id.contains("\"") || id.isEmpty()) {
                errors.add("line " + lineNo + ": invalid parameter id");
                continue;
            }
            if ("id".equalsIgnoreCase(id) && "value".equalsIgnoreCase(valueText)) {
                continue;
            }
            if (firstLineById.containsKey(id)) {
                errors.add("line " + lineNo + ": duplicate parameter id from line " + firstLineById.get(id));
                continue;
            }
            if (rows.size() >= MAX_CSV_ROWS) {
                errors.add("CSV exceeds maximum row count of " + MAX_CSV_ROWS);
                break;
            }
            try {
                final float value = Float.parseFloat(valueText);
                if (!Float.isFinite(value)) {
                    errors.add("line " + lineNo + ": numeric value must be finite");
                    continue;
                }
                rows.add(new CsvRow(id, value));
                firstLineById.put(id, lineNo);
            } catch (NumberFormatException ex) {
                errors.add("line " + lineNo + ": invalid numeric value");
            }
        }
        return new ParseResult(List.copyOf(rows), List.copyOf(errors));
    }

    /** @deprecated use {@link #parseCsvStrict(String)} */
    @Deprecated
    static List<CsvRow> parseCsv(final String csvText) {
        return parseCsvStrict(csvText).rows();
    }

    private static Optional<String> validateRows(
        final List<CsvRow> rows,
        final Map<String, ParameterSnapshot> parametersById
    ) {
        for (final CsvRow row : rows) {
            final ParameterSnapshot parameter = parametersById.get(row.id());
            if (parameter == null) {
                return Optional.of("unknown parameter " + row.id());
            }
            if (!parameter.editable()) {
                return Optional.of("parameter " + row.id() + " is not editable");
            }
            if (row.value() < parameter.minValue() || row.value() > parameter.maxValue()) {
                return Optional.of("parameter " + row.id() + " is outside ["
                    + parameter.minValue() + "," + parameter.maxValue() + "]");
            }
        }
        return Optional.empty();
    }

    @FunctionalInterface
    public interface CsvContentProvider {
        Optional<String> read(String relativePath);

        static CsvContentProvider unavailable() {
            return ignored -> Optional.empty();
        }
    }

    record CsvRow(String id, float value) {
    }

    record ParseResult(List<CsvRow> rows, List<String> errors) {
    }
}
