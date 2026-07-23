package dev.turboism.plugin.parameter.service;

import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.ui.FileChooserRequest;
import dev.turboism.sdk.ui.StatusNotification;
import dev.turboism.sdk.ui.UiHostCapabilityService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * SDK-only parameter CSV import/export service.
 * Both export and import use the unified model object graph.
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

    private final CubismFacade cubism;
    private final UiHostCapabilityService uiHost;
    private final CsvContentProvider csvContentProvider;
    private final PluginLogger logger;

    private String lastExportCsv = "";

    public ParameterCsvService(
        final CubismFacade cubism,
        final PluginContext pluginContext,
        final UiHostCapabilityService uiHost
    ) {
        this(cubism, pluginContext, uiHost, CsvContentProvider.unavailable());
    }

    public ParameterCsvService(
        final CubismFacade cubism,
        final PluginContext pluginContext,
        final UiHostCapabilityService uiHost,
        final CsvContentProvider csvContentProvider
    ) {
        this.cubism = Objects.requireNonNull(cubism, "cubism");
        Objects.requireNonNull(pluginContext, "pluginContext");
        this.uiHost = Objects.requireNonNull(uiHost, "uiHost");
        this.csvContentProvider = Objects.requireNonNull(csvContentProvider, "csvContentProvider");
        this.logger = Objects.requireNonNull(pluginContext.logger(), "pluginContext.logger()");
    }

    public String lastExportCsv() {
        return lastExportCsv;
    }

    public void exportCsv() {
        final List<Parameter> parameters;
        try {
            parameters = cubism.model().active().parameters().all();
        } catch (IllegalStateException | UnsupportedOperationException unavailable) {
            lastExportCsv = "";
            uiHost.notifyStatus(new StatusNotification(
                EXPORT_UNAVAILABLE,
                "WARNING",
                "No parameters are available for CSV export."
            ));
            return;
        }
        if (parameters.isEmpty()) {
            lastExportCsv = "";
            uiHost.notifyStatus(new StatusNotification(
                EXPORT_UNAVAILABLE,
                "WARNING",
                "No parameters are available for CSV export."
            ));
            return;
        }
        lastExportCsv = toModelCsv(parameters);
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

        final CubismModel model;
        try {
            model = cubism.model().active();
        } catch (IllegalStateException | UnsupportedOperationException unavailable) {
            uiHost.notifyStatus(new StatusNotification(
                IMPORT_UNAVAILABLE,
                "WARNING",
                "Active model is unavailable for parameter CSV import."
            ));
            return;
        }

        final List<ResolvedWrite> writes = new ArrayList<>();
        try {
            for (final CsvRow row : parsed.rows()) {
                final Parameter parameter = model.parameters().find(new ParameterId(row.id()));
                if (row.value() < parameter.getMinimumValue() || row.value() > parameter.getMaximumValue()) {
                    throw new IllegalArgumentException("parameter " + row.id() + " is outside ["
                        + parameter.getMinimumValue() + "," + parameter.getMaximumValue() + "]");
                }
                writes.add(new ResolvedWrite(parameter, row.value()));
            }
        } catch (RuntimeException validationFailure) {
            uiHost.notifyStatus(new StatusNotification(
                IMPORT_FAILED,
                "WARNING",
                "Parameter CSV import failed: " + safeMessage(validationFailure)
            ));
            return;
        }

        try {
            for (final ResolvedWrite write : writes) {
                write.parameter().setValue(write.value());
            }
            uiHost.notifyStatus(new StatusNotification(
                IMPORT_COMPLETED,
                "INFO",
                "Imported " + parsed.rows().size() + " parameter value(s) from CSV."
            ));
        } catch (RuntimeException failure) {
            logger.warn("Parameter CSV import failed safely: " + failure.getClass().getSimpleName());
            uiHost.notifyStatus(new StatusNotification(
                IMPORT_FAILED,
                "WARNING",
                "Parameter CSV import failed. Some values may require Undo in Cubism."
            ));
        }
    }

    private static String safeMessage(final RuntimeException failure) {
        final String message = failure.getMessage();
        return message == null || message.isBlank()
            ? failure.getClass().getSimpleName()
            : message;
    }

    private static String toModelCsv(final List<Parameter> parameters) {
        final StringBuilder builder = new StringBuilder("id,value\n");
        for (final Parameter parameter : parameters) {
            final String id = parameter.id().value();
            final float value = parameter.getValue();
            appendCsvRow(builder, id, value);
        }
        return builder.toString();
    }

    private static void appendCsvRow(final StringBuilder builder, final String id, final double value) {
        if (id.contains(",") || id.contains("\"") || id.contains("\n")) {
            throw new IllegalArgumentException("parameter id must not contain comma, quote, or newline: " + id);
        }
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("parameter value must be finite: " + id);
        }
        builder.append(id)
            .append(',')
            .append(value)
            .append('\n');
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

    @FunctionalInterface
    public interface CsvContentProvider {
        Optional<String> read(String relativePath);

        static CsvContentProvider unavailable() {
            return ignored -> Optional.empty();
        }
    }

    record CsvRow(String id, float value) {
    }

    private record ResolvedWrite(Parameter parameter, float value) {
    }

    record ParseResult(List<CsvRow> rows, List<String> errors) {
    }
}
