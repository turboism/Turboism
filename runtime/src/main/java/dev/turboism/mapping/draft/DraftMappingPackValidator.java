package dev.turboism.mapping.draft;

import com.fasterxml.jackson.databind.JsonNode;
import dev.turboism.core.schema.AbstractJsonValidator;
import dev.turboism.core.schema.SchemaValidationError;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Pipeline-only validator for the strict DRAFT mapping pack subset accepted by M15. */
public final class DraftMappingPackValidator extends AbstractJsonValidator {
    private static final Set<String> TOP = Set.of("status", "source", "cubismVersion", "entries", "metadata", "x.legacy");
    private static final Set<String> ENTRY = Set.of(
        "semanticName", "kind", "owner", "name", "runtime", "intermediary", "descriptor",
        "profile", "status", "stability", "source", "verifiedBy", "verifiedAt", "confidence",
        "x.legacy", "x.verification", "x.review"
    );
    private static final Set<String> REQUIRED = Set.of(
        "semanticName", "kind", "owner", "name", "runtime", "profile", "status", "stability",
        "source", "verifiedBy", "verifiedAt", "confidence"
    );

    public DraftMappingPackValidator() {
        super("turboism.mapping.pack", "DRAFT_MAPPING_PACK", 1, TOP);
    }

    @Override public List<SchemaValidationError> validate(final JsonNode node) { return validate(node, ""); }

    @Override public List<SchemaValidationError> validate(final JsonNode node, final String source) {
        final List<SchemaValidationError> errors = new ArrayList<>(validateRoot(node, source));
        if (node == null || !node.isObject()) return errors;
        requireStringField(node, "status", "DRAFT_MAPPING_PACK_MISSING", errors, source);
        requireStringField(node, "source", "DRAFT_MAPPING_PACK_MISSING", errors, source);
        requireStringField(node, "cubismVersion", "DRAFT_MAPPING_PACK_MISSING", errors, source);
        requireArrayField(node, "entries", "DRAFT_MAPPING_PACK_MISSING", errors, source);
        if (node.has("status") && !"DRAFT".equals(node.path("status").asText())) {
            errors.add(error("DRAFT_MAPPING_PACK_BAD_STATUS", "pipeline accepts only top-level DRAFT packs", "status", source));
        }
        if (!node.path("entries").isArray() || node.path("entries").isEmpty()) return errors;
        final Set<String> names = new HashSet<>();
        for (int index = 0; index < node.path("entries").size(); index++) {
            validateEntry(node.path("entries").get(index), index, names, errors, source);
        }
        rejectUnsafePaths(node, "", errors, source);
        return errors;
    }

    private void validateEntry(final JsonNode entry, final int index, final Set<String> names,
                               final List<SchemaValidationError> errors, final String source) {
        final String base = "entries[" + index + "]";
        if (!entry.isObject()) {
            errors.add(error("DRAFT_MAPPING_PACK_BAD_ENTRY", "entry must be an object", base, source));
            return;
        }
        entry.fieldNames().forEachRemaining(field -> {
            if (!ENTRY.contains(field)) errors.add(error("DRAFT_MAPPING_PACK_ENTRY_UNKNOWN_FIELD", "Unknown entry field: " + field, base + "." + field, source));
        });
        for (String field : REQUIRED) {
            if (!entry.has(field) || entry.get(field).isNull() && !"verifiedAt".equals(field)
                || entry.has(field) && entry.get(field).isTextual() && entry.get(field).asText().isBlank()) {
                errors.add(error("DRAFT_MAPPING_PACK_ENTRY_MISSING", "Missing required entry field: " + field, base + "." + field, source));
            }
        }
        final String semantic = entry.path("semanticName").asText("");
        if (!semantic.isBlank() && !DraftMappingGrammar.isSafeSemanticName(semantic)) {
            errors.add(error("DRAFT_MAPPING_PACK_BAD_SEMANTIC_NAME", "semanticName must be non-blank and path-safe", base + ".semanticName", source));
        }
        if (!semantic.isBlank() && !names.add(semantic)) {
            errors.add(error("DRAFT_MAPPING_PACK_DUPLICATE_SEMANTIC_NAME", "semanticName must be unique", base + ".semanticName", source));
        }
        if (!Set.of("class", "method", "field").contains(entry.path("kind").asText())) {
            errors.add(error("DRAFT_MAPPING_PACK_BAD_KIND", "kind must be class, method, or field", base + ".kind", source));
        }
        if (!"DRAFT".equals(entry.path("status").asText()) || !"experimental".equals(entry.path("stability").asText())
            || !"none".equals(entry.path("verifiedBy").asText()) || !entry.has("verifiedAt") || !entry.get("verifiedAt").isNull()) {
            errors.add(error("DRAFT_MAPPING_PACK_ENTRY_NOT_DRAFT", "entry must be DRAFT/experimental with verifiedBy=none and verifiedAt=null", base, source));
        }
        if (!entry.path("confidence").isTextual()
            || !Set.of("high", "medium", "low").contains(entry.path("confidence").asText())) {
            errors.add(error("DRAFT_MAPPING_PACK_BAD_CONFIDENCE", "confidence must be high, medium, or low", base + ".confidence", source));
        }
        for (String field : List.of(
            "semanticName", "kind", "owner", "name", "runtime", "intermediary", "descriptor",
            "profile", "status", "stability", "source", "verifiedBy", "confidence"
        )) {
            if (entry.has(field) && !entry.get(field).isTextual()) {
                errors.add(error("DRAFT_MAPPING_PACK_BAD_SCALAR", field + " must be text", base + "." + field, source));
            }
        }
    }

    private void rejectUnsafePaths(final JsonNode node, final String path,
                                   final List<SchemaValidationError> errors, final String source) {
        if (node.isObject()) {
            node.fields().forEachRemaining(field -> {
                final String child = path + "." + field.getKey();
                if (field.getKey().toLowerCase(Locale.ROOT).endsWith("path")) {
                    if (!field.getValue().isTextual()
                        || !DraftMappingGrammar.isSafeRelativePath(field.getValue().asText())) {
                        errors.add(error("DRAFT_MAPPING_PACK_UNSAFE_PATH", "path field must be a safe relative string", child, source));
                    }
                }
                rejectUnsafePaths(field.getValue(), child, errors, source);
            });
        } else if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) rejectUnsafePaths(node.get(index), path + "[" + index + "]", errors, source);
        }
    }

}
