package dev.turboism.core.schema.version;

import com.fasterxml.jackson.databind.JsonNode;
import dev.turboism.core.schema.JsonSchemaValidator;
import dev.turboism.core.schema.SchemaValidationError;
import dev.turboism.core.version.PluginVersion;
import dev.turboism.core.version.VersionRange;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Validator for version-range-v1 string syntax.
 */
public final class VersionRangeValidator implements JsonSchemaValidator {

    @Override
    public List<SchemaValidationError> validate(JsonNode node) {
        return validate(node, "");
    }

    @Override
    public List<SchemaValidationError> validate(JsonNode node, String source) {
        List<SchemaValidationError> errors = new ArrayList<>();
        if (node == null || !node.isObject() || !node.has("range")) {
            errors.add(error("VERSION_RANGE_EMPTY", "Missing range field", "range", source));
            return errors;
        }
        JsonNode rangeNode = node.get("range");
        if (!rangeNode.isTextual() || rangeNode.asText().isBlank()) {
            errors.add(error("VERSION_RANGE_EMPTY", "range must be a non-empty string", "range", source));
            return errors;
        }
        String range = rangeNode.asText().trim();
        if (range.equals("latest") || range.equals("*") || range.startsWith("^") || range.startsWith("~") || range.startsWith(">=") || range.startsWith(">") || range.startsWith("<=") || range.endsWith("]")) {
            errors.add(error("VERSION_RANGE_UNSUPPORTED", "Version range syntax is not supported in v1: " + range, "range", source));
            return errors;
        }
        try {
            VersionRange.parse(range);
        } catch (IllegalArgumentException e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("interval")) {
                errors.add(error("VERSION_RANGE_BAD_INTERVAL", msg, "range", source));
            } else if (msg != null && msg.contains("version")) {
                errors.add(error("VERSION_RANGE_BAD_VERSION", msg, "range", source));
            } else if (msg != null && msg.contains("must not be empty")) {
                errors.add(error("VERSION_RANGE_EMPTY", msg, "range", source));
            } else {
                errors.add(error("VERSION_RANGE_UNSUPPORTED", msg != null ? msg : "Unsupported range", "range", source));
            }
        }
        return errors;
    }

    private SchemaValidationError error(String code, String message, String path, String source) {
        return new SchemaValidationError(code, SchemaValidationError.Severity.ERROR, message, path, source);
    }
}
