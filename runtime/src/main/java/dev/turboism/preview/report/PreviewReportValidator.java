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

    /**
     * Validates one preview report document against the frozen v1 contract.
     *
     * <p>The check is strict and total: the bytes must be non-empty, at most
     * {@link #MAX_REPORT_BYTES}, and UTF-8 without a byte-order mark; the JSON must parse with
     * duplicate-key detection and no trailing tokens; the envelope must carry exactly the seven
     * known fields with no extras; and the payload must satisfy the rules for its declared report
     * type. Nothing is repaired or defaulted — the first violation throws.
     *
     * @param bytes the raw report document
     * @return the validated report, holding a defensive copy of the parsed document
     * @throws NullPointerException if {@code bytes} is {@code null}
     * @throws PreviewReportValidationException if any contract rule is violated, carrying a code
     *     such as {@code REPORT_SIZE}, {@code UTF8_BOM}, {@code MALFORMED_JSON},
     *     {@code UNKNOWN_FIELD}, or {@code BAD_REPORT_TYPE}
     */
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

    /**
     * Validates a complete report set and the cross-report invariants a single document cannot
     * establish.
     *
     * <p>Beyond validating each document, this requires that the map holds exactly the four
     * {@link PreviewReportType} keys, that each document's declared type matches the key it was
     * filed under, and that every report carries the same runtime id — so a set cannot silently
     * mix documents from two different preview sessions.
     *
     * @param reports the four report documents keyed by type
     * @return an unmodifiable map of validated reports, one per type
     * @throws NullPointerException if the map or any of its four values is {@code null}
     * @throws PreviewReportValidationException with code {@code INCOMPLETE_REPORT_SET},
     *     {@code REPORT_TYPE_MISMATCH}, or {@code MIXED_RUNTIME_ID}, or any code raised by
     *     {@link #validate(byte[])}
     */
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

    /**
     * Applies the same path rule the validator enforces on report path fields, exposed so
     * producers can check a path before writing it.
     *
     * @param path the candidate path string
     * @return whether the path is acceptable inside a report — reports never carry absolute or
     *     escaping filesystem paths
     */
    public static boolean isRelativePath(final String path) {
        return PreviewReportValidationSupport.isRelativePath(path);
    }

    /**
     * A report document that has passed the full v1 contract check, together with the two envelope
     * facts callers usually need without re-reading the tree.
     *
     * <p>The document is defensively copied on the way in and again on every
     * {@link #document()} call, so neither the producer nor a consumer can mutate validated
     * state after the fact.
     *
     * @param reportType the type declared in the envelope, already checked against the payload
     * @param runtimeId the runtime session the report belongs to
     * @param document the validated report tree; copied in and copied out
     */
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
