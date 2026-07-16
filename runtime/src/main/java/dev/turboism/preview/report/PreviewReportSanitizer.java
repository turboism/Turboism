package dev.turboism.preview.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Sanitizes every textual value without weakening structural report validation. */
final class PreviewReportSanitizer {

    private static final String REDACTED_URI = "<redacted-uri>";
    private static final String REDACTED_PATH = "<redacted-path>";
    private static final String REDACTED_SECRET = "<redacted-secret>";
    private static final String REDACTED_OPAQUE_ID = "<redacted-opaque-id>";
    private static final String REDACTED_EXCEPTION = "<redacted-private-exception>";
    private static final String SAFE_STRUCTURAL_VALUE = "redacted";

    private static final Pattern URI = Pattern.compile(
        "(?i)\\b(?:https?|file)://[^\\s\\\"']+"
    );
    private static final Pattern UNC_PATH = Pattern.compile(
        "\\\\\\\\[^\\s\\\"'<>]+(?:\\\\[^\\s\\\"'<>]+)+"
    );
    private static final Pattern WINDOWS_PATH = Pattern.compile(
        "(?i)(?<![A-Za-z0-9])(?:[A-Z]:[\\\\/])[^\\s\\\"'<>]+"
    );
    private static final Pattern HOME_PATH = Pattern.compile(
        "(?<![A-Za-z0-9_])~(?:[/\\\\])[^\\s\\\"'<>]+"
    );
    private static final Pattern UNIX_PATH = Pattern.compile(
        "(?<![A-Za-z0-9._~:/-])/(?:[^/\\s\\\"'<>]+/)*[^\\s\\\"'<>]+"
    );
    private static final Pattern AUTHORIZATION = Pattern.compile(
        "(?i)\\b(?:authorization\\s*[:=]\\s*|bearer\\s+)"
            + "[A-Za-z0-9._~+/=-]+"
    );
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
        "(?i)\\b(?:token|secret|password)\\s*[:=]\\s*[A-Za-z0-9._~+/=-]+"
    );
    private static final Pattern OPAQUE_ID = Pattern.compile(
        "(?i)\\b(?:grant|handle|token)[-_:][A-Za-z0-9._~+/=-]{4,}"
    );
    private static final Pattern PRIVATE_EXCEPTION = Pattern.compile(
        "(?:[A-Za-z_$][A-Za-z0-9_$]*\\.){2,}"
            + "[A-Za-z_$][A-Za-z0-9_$]*(?:Exception|Error)"
            + "(?::[^\\r\\n]*)?"
    );

    private static final Map<String, Integer> FREE_TEXT_LIMITS = Map.of(
        "message", 1024,
        "summary", 1024,
        "marker", 1024,
        "product", 256
    );
    private static final Set<String> OPTIONAL_PATH_FIELDS = Set.of(
        "artifactRelativePath",
        "relativeRecordPath",
        "relativePath"
    );

    void sanitize(final ObjectNode document) {
        sanitizeNode(document);
    }

    private static void sanitizeNode(final JsonNode node) {
        if (node instanceof ObjectNode object) {
            sanitizeObject(object);
            return;
        }
        if (node instanceof ArrayNode array) {
            for (int index = 0; index < array.size(); index++) {
                final JsonNode value = array.get(index);
                if (value.isTextual()) {
                    array.set(index, TextNode.valueOf(sanitizeStructural(value.textValue())));
                } else if (value.isContainerNode()) {
                    sanitizeNode(value);
                }
            }
        }
    }

    private static void sanitizeObject(final ObjectNode object) {
        final List<FieldChange> changes = new ArrayList<>();
        final List<String> removals = new ArrayList<>();
        object.fields().forEachRemaining(entry -> {
            final String field = entry.getKey();
            final JsonNode value = entry.getValue();
            if (value.isTextual()) {
                final String text = value.textValue();
                if (OPTIONAL_PATH_FIELDS.contains(field) && containsLocation(text)) {
                    removals.add(field);
                } else {
                    final Integer limit = FREE_TEXT_LIMITS.get(field);
                    changes.add(new FieldChange(
                        field,
                        limit == null ? sanitizeStructural(text) : sanitizeFreeText(text, limit)
                    ));
                }
            } else if (value.isContainerNode()) {
                sanitizeNode(value);
            }
        });
        removals.forEach(object::remove);
        changes.forEach(change -> object.set(
            change.field(), TextNode.valueOf(change.value())
        ));
    }

    private static String sanitizeFreeText(final String value, final int limit) {
        final String bounded = value.length() <= limit ? value : value.substring(0, limit);
        return replaceSensitive(bounded);
    }

    private static String sanitizeStructural(final String value) {
        return containsSensitive(value) ? SAFE_STRUCTURAL_VALUE : value;
    }

    private static boolean containsLocation(final String value) {
        return (value.contains("://") && URI.matcher(value).find())
            || (value.contains("\\\\") && UNC_PATH.matcher(value).find())
            || ((value.contains(":/") || value.contains(":\\"))
                && WINDOWS_PATH.matcher(value).find())
            || ((value.contains("~/") || value.contains("~\\"))
                && HOME_PATH.matcher(value).find())
            || (value.contains("/") && UNIX_PATH.matcher(value).find());
    }

    private static boolean containsSensitive(final String value) {
        final String lower = value.toLowerCase(java.util.Locale.ROOT);
        return containsSensitive(value, lower);
    }

    private static boolean containsSensitive(final String value, final String lower) {
        return containsLocation(value)
            || ((lower.contains("authorization") || lower.contains("bearer"))
                && AUTHORIZATION.matcher(value).find())
            || ((lower.contains("token") || lower.contains("secret")
                || lower.contains("password")) && SECRET_ASSIGNMENT.matcher(value).find())
            || ((lower.contains("grant") || lower.contains("handle")
                || lower.contains("token")) && OPAQUE_ID.matcher(value).find())
            || (value.contains(".") && (value.contains("Exception") || value.contains("Error"))
                && PRIVATE_EXCEPTION.matcher(value).find());
    }

    private static String replaceSensitive(final String value) {
        String sanitized = value;
        final String lower = value.toLowerCase(java.util.Locale.ROOT);
        if (sanitized.contains("://")) {
            sanitized = URI.matcher(sanitized).replaceAll(REDACTED_URI);
        }
        if (sanitized.contains("\\\\")) {
            sanitized = UNC_PATH.matcher(sanitized).replaceAll(REDACTED_PATH);
        }
        if (sanitized.contains(":/") || sanitized.contains(":\\")) {
            sanitized = WINDOWS_PATH.matcher(sanitized).replaceAll(REDACTED_PATH);
        }
        if (sanitized.contains("~/") || sanitized.contains("~\\")) {
            sanitized = HOME_PATH.matcher(sanitized).replaceAll(REDACTED_PATH);
        }
        if (sanitized.contains("/")) {
            sanitized = UNIX_PATH.matcher(sanitized).replaceAll(REDACTED_PATH);
        }
        if (lower.contains("authorization") || lower.contains("bearer")) {
            sanitized = AUTHORIZATION.matcher(sanitized).replaceAll(REDACTED_SECRET);
        }
        if (lower.contains("token") || lower.contains("secret") || lower.contains("password")) {
            sanitized = SECRET_ASSIGNMENT.matcher(sanitized).replaceAll(REDACTED_SECRET);
        }
        if (lower.contains("grant") || lower.contains("handle") || lower.contains("token")) {
            sanitized = OPAQUE_ID.matcher(sanitized).replaceAll(REDACTED_OPAQUE_ID);
        }
        if (sanitized.contains(".")
            && (sanitized.contains("Exception") || sanitized.contains("Error"))) {
            sanitized = PRIVATE_EXCEPTION.matcher(sanitized).replaceAll(REDACTED_EXCEPTION);
        }
        return sanitized;
    }

    private record FieldChange(String field, String value) {
    }
}
