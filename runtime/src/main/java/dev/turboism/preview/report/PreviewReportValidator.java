package dev.turboism.preview.report;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static dev.turboism.preview.report.PreviewReportValidationSupport.boundedText;
import static dev.turboism.preview.report.PreviewReportValidationSupport.enumValue;
import static dev.turboism.preview.report.PreviewReportValidationSupport.exact;
import static dev.turboism.preview.report.PreviewReportValidationSupport.exactInteger;
import static dev.turboism.preview.report.PreviewReportValidationSupport.failure;
import static dev.turboism.preview.report.PreviewReportValidationSupport.object;
import static dev.turboism.preview.report.PreviewReportValidationSupport.textEquals;
import static dev.turboism.preview.report.PreviewReportValidationSupport.validateBounds;
import static dev.turboism.preview.report.PreviewReportValidationSupport.validateTimestamp;
import static dev.turboism.preview.report.PreviewReportValidationSupport.validateTruncation;

/** Strict parser-backed validator for the frozen preview report v1 contract. */
public final class PreviewReportValidator {

    public static final int MAX_REPORT_BYTES = 1024 * 1024;
    private static final String FORMAT = "turboism.preview.report";
    private static final Set<String> ENVELOPE_FIELDS = Set.of(
        "format", "schemaVersion", "reportType", "runtimeId",
        "createdAt", "truncation", "payload"
    );
    private static final ObjectMapper JSON = new ObjectMapper(
        JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build()
    ).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private PreviewReportValidator() {
    }

    public static ValidatedReport validate(final byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length == 0 || bytes.length > MAX_REPORT_BYTES) {
            throw failure("REPORT_SIZE", "Preview report size is outside the runtime bound.");
        }
        if (hasBom(bytes)) {
            throw failure("UTF8_BOM", "Preview report must be UTF-8 without BOM.");
        }
        final ObjectNode root = parse(bytes);
        exact(root, ENVELOPE_FIELDS, Set.of(), "UNKNOWN_FIELD", "report");
        textEquals(root, "format", FORMAT, "BAD_FORMAT");
        exactInteger(root, "schemaVersion", 1, "BAD_SCHEMA_VERSION");
        final PreviewReportType reportType = enumValue(
            root,
            "reportType",
            PreviewReportType.class,
            "BAD_REPORT_TYPE"
        );
        final String runtimeId = boundedText(
            root,
            "runtimeId",
            PreviewReportValidationSupport.MAX_RUNTIME_ID_LENGTH,
            "BAD_RUNTIME_ID"
        );
        validateTimestamp(root.get("createdAt"));
        validateTruncation(root.get("truncation"));
        final ObjectNode payload = object(root.get("payload"), "BAD_PAYLOAD", "payload");
        PreviewReportVariantValidator.validate(reportType, payload);
        validateBounds(root, 0);
        return new ValidatedReport(reportType, runtimeId, root);
    }

    public static Map<PreviewReportType, ValidatedReport> validateSet(
        final Map<PreviewReportType, byte[]> reports
    ) {
        Objects.requireNonNull(reports, "reports");
        if (!reports.keySet().equals(Set.of(PreviewReportType.values()))) {
            throw failure(
                "INCOMPLETE_REPORT_SET",
                "Preview report set must contain exactly the four report types."
            );
        }
        final EnumMap<PreviewReportType, ValidatedReport> validated =
            new EnumMap<>(PreviewReportType.class);
        String runtimeId = null;
        for (PreviewReportType expected : PreviewReportType.values()) {
            final ValidatedReport report = validate(
                Objects.requireNonNull(reports.get(expected), expected.name())
            );
            if (report.reportType() != expected) {
                throw failure(
                    "REPORT_TYPE_MISMATCH",
                    "Preview report file/type mapping is inconsistent."
                );
            }
            if (runtimeId == null) {
                runtimeId = report.runtimeId();
            } else if (!runtimeId.equals(report.runtimeId())) {
                throw failure(
                    "MIXED_RUNTIME_ID",
                    "Preview reports from different runtime sessions cannot be correlated."
                );
            }
            validated.put(expected, report);
        }
        return Map.copyOf(validated);
    }

    private static ObjectNode parse(final byte[] bytes) {
        final JsonNode parsed;
        try {
            parsed = JSON.readTree(bytes);
        } catch (JsonProcessingException exception) {
            throw new PreviewReportValidationException(
                "MALFORMED_JSON",
                "Preview report JSON is malformed.",
                exception
            );
        } catch (java.io.IOException exception) {
            throw new PreviewReportValidationException(
                "MALFORMED_JSON",
                "Preview report JSON could not be read.",
                exception
            );
        }
        return object(parsed, "BAD_ENVELOPE", "report");
    }

    private static boolean hasBom(final byte[] bytes) {
        return bytes.length >= 3
            && bytes[0] == (byte) 0xEF
            && bytes[1] == (byte) 0xBB
            && bytes[2] == (byte) 0xBF;
    }

    public static boolean isRelativePath(final String path) {
        return PreviewReportValidationSupport.isRelativePath(path);
    }

    public record ValidatedReport(
        PreviewReportType reportType,
        String runtimeId,
        ObjectNode document
    ) {
        public ValidatedReport {
            reportType = Objects.requireNonNull(reportType, "reportType");
            runtimeId = Objects.requireNonNull(runtimeId, "runtimeId");
            document = Objects.requireNonNull(document, "document").deepCopy();
        }

        @Override
        public ObjectNode document() {
            return document.deepCopy();
        }
    }
}
