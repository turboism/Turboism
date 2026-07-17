package dev.turboism.preview.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.regex.Pattern;

/** Shared strict primitives for the closed preview-report validator. */
final class PreviewReportValidationSupport {

    static final int MAX_ARRAY_ENTRIES = 4096;
    static final int MAX_STRING_LENGTH = 4096;
    static final int MAX_RUNTIME_ID_LENGTH = 256;

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern DRIVE_OR_URI = Pattern.compile(
        "^[A-Za-z][A-Za-z0-9+.-]*:.*"
    );
    private static final Set<String> TRUNCATION_REASONS = Set.of(
        "NONE", "ENTRY_LIMIT", "BYTE_LIMIT", "REDACTION_LIMIT", "WRITER_LIMIT"
    );
    private static final Set<String> SEVERITIES = Set.of("INFO", "WARNING", "ERROR");
    private static final Set<String> EVIDENCE_KINDS = Set.of(
        "DECLARED", "STATIC_VERIFIED", "SYNTHETIC", "RUNTIME_OBSERVED", "MANUAL"
    );
    private static final Set<String> EVIDENCE_STATES = Set.of(
        "AVAILABLE", "UNAVAILABLE", "DEGRADED", "UNKNOWN"
    );

    private PreviewReportValidationSupport() {
    }

    static void validateTimestamp(final JsonNode value) {
        if (value == null || !value.isTextual()) {
            throw failure("BAD_TIMESTAMP", "createdAt must be a UTC instant string.");
        }
        final String text = value.textValue();
        if (!text.endsWith("Z")) {
            throw failure("BAD_TIMESTAMP", "createdAt must use the UTC Z suffix.");
        }
        try {
            Instant.parse(text);
        } catch (DateTimeParseException exception) {
            throw new PreviewReportValidationException(
                "BAD_TIMESTAMP",
                "createdAt is not a valid UTC instant.",
                exception
            );
        }
    }

    static void validateTruncation(final JsonNode value) {
        final ObjectNode truncation = object(value, "BAD_TRUNCATION", "truncation");
        exact(
            truncation,
            Set.of("truncated", "droppedEntries", "reason"),
            Set.of(),
            "UNKNOWN_FIELD",
            "truncation"
        );
        final boolean truncated = bool(truncation, "truncated", "BAD_TRUNCATION");
        final long dropped = nonnegative(truncation, "droppedEntries", "BAD_TRUNCATION");
        final String reason = enumText(
            truncation,
            "reason",
            TRUNCATION_REASONS,
            "BAD_TRUNCATION"
        );
        if ((!truncated && (dropped != 0 || !reason.equals("NONE")))
            || (truncated && reason.equals("NONE"))) {
            throw failure("BAD_TRUNCATION", "Truncation fields are inconsistent.");
        }
    }

    static void validateFailureArray(
        final JsonNode value,
        final String label
    ) {
        final JsonNode failures = array(value, "BAD_FAILURE", label);
        boundedArray(failures, label);
        for (JsonNode item : failures) {
            final ObjectNode failure = object(item, "BAD_FAILURE", label + " entry");
            exact(
                failure,
                Set.of("code", "severity", "phase", "message", "count"),
                Set.of("pluginId", "operationId", "permissionId", "relativePath"),
                "UNKNOWN_FIELD",
                "failure"
            );
            boundedText(failure, "code", 256, "BAD_FAILURE");
            enumText(failure, "severity", SEVERITIES, "BAD_FAILURE");
            boundedText(failure, "phase", 256, "BAD_FAILURE");
            optionalText(failure, "pluginId", 256, "BAD_FAILURE");
            optionalText(failure, "operationId", 256, "BAD_FAILURE");
            optionalText(failure, "permissionId", 256, "BAD_FAILURE");
            boundedText(failure, "message", 1024, "BAD_FAILURE");
            optionalPath(failure, "relativePath");
            positive(failure, "count", "BAD_FAILURE");
        }
    }

    static void validateShutdownCounts(final JsonNode value) {
        final ObjectNode counts = object(
            value,
            "BAD_SHUTDOWN_COUNTS",
            "shutdownCounts"
        );
        exact(
            counts,
            Set.of("attempted", "succeeded", "failed", "timedOut"),
            Set.of(),
            "UNKNOWN_FIELD",
            "shutdownCounts"
        );
        final long attempted = nonnegative(counts, "attempted", "BAD_SHUTDOWN_COUNTS");
        final long succeeded = nonnegative(counts, "succeeded", "BAD_SHUTDOWN_COUNTS");
        final long failed = nonnegative(counts, "failed", "BAD_SHUTDOWN_COUNTS");
        final long timedOut = nonnegative(counts, "timedOut", "BAD_SHUTDOWN_COUNTS");
        if (attempted != succeeded + failed + timedOut) {
            throw failure(
                "BAD_SHUTDOWN_COUNTS",
                "Shutdown attempted count must equal all outcomes."
            );
        }
    }

    static void validateCleanupCounts(final JsonNode value) {
        final ObjectNode counts = object(value, "BAD_CLEANUP_COUNTS", "cleanupCounts");
        exact(
            counts,
            Set.of(
                "taskHandlesCanceled", "taskCompletionsSettled",
                "pluginContinuationsDrained", "userFileHandlesRevoked",
                "configSchemasUnregistered", "temporaryFilesDeleted", "scopesClosed",
                "classloadersClosed", "failures"
            ),
            Set.of(),
            "UNKNOWN_FIELD",
            "cleanupCounts"
        );
        for (String field : fields(counts)) {
            nonnegative(counts, field, "BAD_CLEANUP_COUNTS");
        }
    }

    static void validateRegistrationCounts(final JsonNode value) {
        final ObjectNode counts = object(
            value,
            "BAD_REGISTRATION_COUNTS",
            "registrationCounts"
        );
        final Set<String> categories = Set.of(
            "actions", "events", "menus", "toolbars", "contextMenus", "overlays",
            "dialogs", "panels", "status", "tasks", "configSchemas", "userFileHandles"
        );
        final Set<String> required = new HashSet<>(categories);
        required.add("total");
        exact(
            counts,
            required,
            Set.of(),
            "UNKNOWN_FIELD",
            "registrationCounts"
        );
        long sum = 0;
        for (String category : categories) {
            sum += nonnegative(counts, category, "BAD_REGISTRATION_COUNTS");
        }
        final long total = nonnegative(counts, "total", "BAD_REGISTRATION_COUNTS");
        if (sum != total) {
            throw failure(
                "BAD_REGISTRATION_COUNTS",
                "Registration total does not equal category counts."
            );
        }
    }

    static void validateEvidenceArray(final JsonNode value) {
        final JsonNode evidence = array(value, "BAD_EVIDENCE", "evidence");
        boundedArray(evidence, "evidence");
        for (JsonNode item : evidence) {
            final ObjectNode entry = object(item, "BAD_EVIDENCE", "evidence entry");
            exact(
                entry,
                Set.of("kind", "state", "summary"),
                Set.of("relativeRecordPath", "digestSha256"),
                "UNKNOWN_FIELD",
                "evidence"
            );
            enumText(entry, "kind", EVIDENCE_KINDS, "BAD_EVIDENCE");
            enumText(entry, "state", EVIDENCE_STATES, "BAD_EVIDENCE");
            boundedText(entry, "summary", 1024, "BAD_EVIDENCE");
            optionalPath(entry, "relativeRecordPath");
            optionalDigest(entry, "digestSha256");
        }
    }

    static void validateBounds(final JsonNode node, final int depth) {
        if (depth > 32) {
            throw failure("REPORT_DEPTH", "Preview report nesting exceeds the runtime bound.");
        }
        if (node.isTextual() && node.textValue().length() > MAX_STRING_LENGTH) {
            throw failure("STRING_LIMIT", "Preview report string exceeds the runtime bound.");
        }
        if (node.isArray() && node.size() > MAX_ARRAY_ENTRIES) {
            throw failure("ENTRY_LIMIT", "Preview report array exceeds the runtime bound.");
        }
        if (node.isContainerNode()) {
            for (JsonNode child : node) {
                validateBounds(child, depth + 1);
            }
        }
    }

    static ObjectNode object(
        final JsonNode value,
        final String code,
        final String label
    ) {
        if (!(value instanceof ObjectNode object)) {
            throw failure(code, label + " must be an object.");
        }
        return object;
    }

    static JsonNode array(
        final JsonNode value,
        final String code,
        final String label
    ) {
        if (value == null || !value.isArray()) {
            throw failure(code, label + " must be an array.");
        }
        return value;
    }

    static void boundedArray(final JsonNode value, final String label) {
        if (value.size() > MAX_ARRAY_ENTRIES) {
            throw failure("ENTRY_LIMIT", label + " exceeds the entry limit.");
        }
    }

    static void exact(
        final ObjectNode object,
        final Set<String> required,
        final Set<String> optional,
        final String unknownCode,
        final String label
    ) {
        final Set<String> actual = fields(object);
        if (!actual.containsAll(required)) {
            final Set<String> missing = new HashSet<>(required);
            missing.removeAll(actual);
            throw failure("MISSING_FIELD", label + " is missing " + missing + ".");
        }
        final Set<String> allowed = new HashSet<>(required);
        allowed.addAll(optional);
        final Set<String> unknown = new HashSet<>(actual);
        unknown.removeAll(allowed);
        if (!unknown.isEmpty()) {
            throw failure(unknownCode, label + " contains unknown fields " + unknown + ".");
        }
    }

    static Set<String> fields(final ObjectNode object) {
        final Set<String> fields = new HashSet<>();
        final Iterator<String> iterator = object.fieldNames();
        while (iterator.hasNext()) {
            fields.add(iterator.next());
        }
        return fields;
    }

    static void textEquals(
        final ObjectNode object,
        final String field,
        final String expected,
        final String code
    ) {
        final JsonNode value = object.get(field);
        if (value == null || !value.isTextual() || !expected.equals(value.textValue())) {
            throw failure(code, field + " has an invalid value.");
        }
    }

    static void exactInteger(
        final ObjectNode object,
        final String field,
        final long expected,
        final String code
    ) {
        final JsonNode value = object.get(field);
        if (value == null || !value.isIntegralNumber()
            || !value.canConvertToLong() || value.longValue() != expected) {
            throw failure(code, field + " must be exact integer " + expected + ".");
        }
    }

    static String boundedText(
        final ObjectNode object,
        final String field,
        final int maximum,
        final String code
    ) {
        final JsonNode value = object.get(field);
        if (value == null || !value.isTextual()) {
            throw failure(code, field + " must be a string.");
        }
        final String text = value.textValue();
        if (text.isBlank() || text.length() > maximum || containsControl(text)) {
            throw failure(code, field + " is blank, oversized, or contains controls.");
        }
        return text;
    }

    static void optionalText(
        final ObjectNode object,
        final String field,
        final int maximum,
        final String code
    ) {
        if (object.has(field)) {
            boundedText(object, field, maximum, code);
        }
    }

    static String enumText(
        final ObjectNode object,
        final String field,
        final Set<String> allowed,
        final String code
    ) {
        final String value = boundedText(object, field, 128, code);
        if (!allowed.contains(value)) {
            throw failure(code, field + " has an unsupported enum value.");
        }
        return value;
    }

    static <E extends Enum<E>> E enumValue(
        final ObjectNode object,
        final String field,
        final Class<E> enumType,
        final String code
    ) {
        final String text = boundedText(object, field, 128, code);
        try {
            return Enum.valueOf(enumType, text);
        } catch (IllegalArgumentException exception) {
            throw new PreviewReportValidationException(
                code,
                field + " has an unsupported enum value.",
                exception
            );
        }
    }

    static boolean bool(
        final ObjectNode object,
        final String field,
        final String code
    ) {
        final JsonNode value = object.get(field);
        if (value == null || !value.isBoolean()) {
            throw failure(code, field + " must be boolean.");
        }
        return value.booleanValue();
    }

    static long nonnegative(
        final ObjectNode object,
        final String field,
        final String code
    ) {
        final JsonNode value = object.get(field);
        if (value == null || !value.isIntegralNumber()
            || !value.canConvertToLong() || value.longValue() < 0) {
            throw failure(code, field + " must be a bounded nonnegative integer.");
        }
        return value.longValue();
    }

    static long positive(
        final ObjectNode object,
        final String field,
        final String code
    ) {
        final long value = nonnegative(object, field, code);
        if (value == 0) {
            throw failure(code, field + " must be positive.");
        }
        return value;
    }

    static void optionalNonnegative(
        final ObjectNode object,
        final String field,
        final String code
    ) {
        if (object.has(field)) {
            nonnegative(object, field, code);
        }
    }

    static void optionalDigest(final ObjectNode object, final String field) {
        if (!object.has(field)) {
            return;
        }
        final String digest = boundedText(object, field, 64, "BAD_DIGEST");
        if (!SHA256.matcher(digest).matches()) {
            throw failure("BAD_DIGEST", field + " must be lowercase SHA-256.");
        }
    }

    static void optionalPath(final ObjectNode object, final String field) {
        if (!object.has(field)) {
            return;
        }
        final String path = boundedText(object, field, 1024, "BAD_PATH");
        if (!isRelativePath(path)) {
            throw failure("BAD_PATH", field + " must be a normalized relative path.");
        }
    }

    static boolean isRelativePath(final String path) {
        if (path == null || path.isBlank() || path.startsWith("/")
            || path.startsWith("~") || path.indexOf('\\') >= 0
            || DRIVE_OR_URI.matcher(path).matches()) {
            return false;
        }
        final String[] segments = path.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")
                || containsControl(segment)) {
                return false;
            }
        }
        return true;
    }

    static void validateTextArray(
        final JsonNode value,
        final String code,
        final boolean nonempty
    ) {
        final JsonNode values = array(value, code, "string array");
        boundedArray(values, "string array");
        if (nonempty && values.isEmpty()) {
            throw failure(code, "String array must not be empty.");
        }
        for (JsonNode item : values) {
            if (!item.isTextual() || item.textValue().isBlank()
                || item.textValue().length() > MAX_STRING_LENGTH) {
                throw failure(code, "String array contains an invalid value.");
            }
        }
    }

    private static boolean containsControl(final String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                return true;
            }
        }
        return false;
    }

    static PreviewReportValidationException failure(
        final String code,
        final String message
    ) {
        return new PreviewReportValidationException(code, message);
    }
}
