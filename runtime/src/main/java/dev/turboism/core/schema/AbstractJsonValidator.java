package dev.turboism.core.schema;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reusable helpers for JSON schema validators.
 */
public abstract class AbstractJsonValidator implements JsonSchemaValidator {

    protected final String format;
    protected final String errorCodePrefix;
    protected final int expectedSchemaVersion;
    protected final Set<String> allowedTopLevelFields;

    protected AbstractJsonValidator(String format, String errorCodePrefix, int expectedSchemaVersion, Set<String> allowedTopLevelFields) {
        this.format = format;
        this.errorCodePrefix = errorCodePrefix;
        this.expectedSchemaVersion = expectedSchemaVersion;
        this.allowedTopLevelFields = new HashSet<>(allowedTopLevelFields);
        this.allowedTopLevelFields.add("format");
        this.allowedTopLevelFields.add("schemaVersion");
    }

    protected List<SchemaValidationError> validateRoot(JsonNode node, String source) {
        List<SchemaValidationError> errors = new ArrayList<>();
        if (node == null || !node.isObject()) {
            errors.add(error(errorCodePrefix + "_INVALID_JSON", "Expected a JSON object", "", source));
            return errors;
        }
        requireStringField(node, "format", errorCodePrefix + "_MISSING", errors, source);
        requireIntegerField(node, "schemaVersion", errorCodePrefix + "_MISSING", errors, source);

        if (node.has("format")) {
            String actualFormat = node.get("format").asText("");
            if (!format.equals(actualFormat)) {
                errors.add(error(errorCodePrefix + "_BAD_FORMAT", "format must be " + format, "format", source));
            }
        }

        if (node.has("schemaVersion") && !node.get("schemaVersion").isNull()) {
            int actualVersion = node.get("schemaVersion").asInt(-1);
            if (actualVersion != expectedSchemaVersion) {
                errors.add(error(errorCodePrefix + "_BAD_SCHEMA_VERSION", "schemaVersion must be " + expectedSchemaVersion, "schemaVersion", source));
            }
        }

        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (!allowedTopLevelFields.contains(entry.getKey())) {
                errors.add(error(errorCodePrefix + "_UNKNOWN_FIELD", "Unknown top-level field: " + entry.getKey(), entry.getKey(), source));
            }
        }

        return errors;
    }

    protected void requireStringField(JsonNode node, String field, String missingCode, List<SchemaValidationError> errors, String source) {
        if (!node.has(field) || node.get(field).isNull() || !node.get(field).isTextual() || node.get(field).asText().isBlank()) {
            errors.add(error(missingCode, "Missing required string field: " + field, field, source));
        }
    }

    protected void requireIntegerField(JsonNode node, String field, String missingCode, List<SchemaValidationError> errors, String source) {
        if (!node.has(field) || node.get(field).isNull() || !node.get(field).isIntegralNumber()) {
            errors.add(error(missingCode, "Missing required integer field: " + field, field, source));
        }
    }

    protected void requireArrayField(JsonNode node, String field, String missingCode, List<SchemaValidationError> errors, String source) {
        if (!node.has(field) || node.get(field).isNull() || !node.get(field).isArray()) {
            errors.add(error(missingCode, "Missing required array field: " + field, field, source));
        }
    }

    protected void requireObjectField(JsonNode node, String field, String missingCode, List<SchemaValidationError> errors, String source) {
        if (!node.has(field) || node.get(field).isNull() || !node.get(field).isObject()) {
            errors.add(error(missingCode, "Missing required object field: " + field, field, source));
        }
    }

    protected Optional<String> optionalText(JsonNode node, String field) {
        if (!node.has(field) || node.get(field).isNull() || !node.get(field).isTextual()) {
            return Optional.empty();
        }
        return Optional.of(node.get(field).asText());
    }

    protected SchemaValidationError error(String code, String message, String path, String source) {
        return new SchemaValidationError(code, SchemaValidationError.Severity.ERROR, message, path, source);
    }

    protected SchemaValidationError warning(String code, String message, String path, String source) {
        return new SchemaValidationError(code, SchemaValidationError.Severity.WARNING, message, path, source);
    }
}
