package dev.turboism.mapping.schema;

import com.fasterxml.jackson.databind.JsonNode;
import dev.turboism.core.schema.AbstractJsonValidator;
import dev.turboism.core.schema.SchemaValidationError;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Validator for turboism.mapping.pack v1.
 */
public final class MappingPackValidator extends AbstractJsonValidator {

    private static final Set<String> ALLOWED_FIELDS = Set.of(
        "status", "source", "cubismVersion", "entries", "metadata", "x.legacy"
    );
    private static final Set<String> ALLOWED_STATUSES = Set.of("DRAFT", "QUARANTINE");
    private static final Set<String> ALLOWED_KINDS = Set.of(
        "class", "constructor", "method", "field"
    );
    private static final Set<String> ALLOWED_CONFIDENCES = Set.of("high", "medium", "low", "inferred", "probe");

    public MappingPackValidator() {
        super("turboism.mapping.pack", "MAPPING_PACK", 1, ALLOWED_FIELDS);
    }

    @Override
    public List<SchemaValidationError> validate(JsonNode node) {
        return validate(node, "");
    }

    @Override
    public List<SchemaValidationError> validate(JsonNode node, String source) {
        List<SchemaValidationError> errors = new ArrayList<>(validateRoot(node, source));
        requireStringField(node, "status", "MAPPING_PACK_MISSING", errors, source);
        requireStringField(node, "source", "MAPPING_PACK_MISSING", errors, source);
        requireStringField(node, "cubismVersion", "MAPPING_PACK_MISSING", errors, source);
        requireArrayField(node, "entries", "MAPPING_PACK_MISSING", errors, source);

        if (node.has("status") && !node.get("status").isNull()) {
            String status = node.get("status").asText("");
            if (status.equals("VERIFIED")) {
                errors.add(error("MAPPING_PACK_STATUS_VERIFIED_NOT_ALLOWED", "status must not be VERIFIED in this phase", "status", source));
            } else if (!ALLOWED_STATUSES.contains(status)) {
                errors.add(error("MAPPING_PACK_BAD_STATUS", "status must be DRAFT or QUARANTINE: " + status, "status", source));
            }
        }

        if (node.has("entries") && node.get("entries").isArray()) {
            if (node.get("entries").size() == 0) {
                errors.add(error("MAPPING_PACK_EMPTY_ENTRIES", "entries must contain at least one element", "entries", source));
            }
            for (int i = 0; i < node.get("entries").size(); i++) {
                validateEntry(node.get("entries").get(i), i, errors, source);
            }
        }

        return errors;
    }

    private void validateEntry(JsonNode e, int index, List<SchemaValidationError> errors, String source) {
        if (!e.isObject()) return;
        if (!e.has("kind") || e.get("kind").isNull() || !e.get("kind").isTextual()) {
            errors.add(error("MAPPING_PACK_ENTRY_MISSING_KIND", "Entry kind is missing", "entries[" + index + "].kind", source));
            return;
        }
        if (!ALLOWED_KINDS.contains(e.get("kind").asText(""))) {
            errors.add(error(
                "MAPPING_PACK_ENTRY_BAD_KIND",
                "Entry kind must be class, constructor, method, or field",
                "entries[" + index + "].kind",
                source
            ));
        }
        if (!e.has("runtime") || e.get("runtime").isNull() || !e.get("runtime").isTextual() || e.get("runtime").asText().isBlank()) {
            errors.add(error("MAPPING_PACK_ENTRY_MISSING_RUNTIME", "Entry runtime is missing", "entries[" + index + "].runtime", source));
        }
        if (e.has("confidence") && !e.get("confidence").isNull() && !ALLOWED_CONFIDENCES.contains(e.get("confidence").asText(""))) {
            errors.add(error("MAPPING_PACK_ENTRY_BAD_CONFIDENCE", "Entry confidence is invalid", "entries[" + index + "].confidence", source));
        }
    }
}
