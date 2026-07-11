package dev.turboism.mapping.draft;

import com.fasterxml.jackson.databind.JsonNode;
import dev.turboism.core.schema.AbstractJsonValidator;
import dev.turboism.core.schema.SchemaValidationError;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Validator for the generated mapping-update diff presentation format.
 * The format is not an apply input; {@link MappingReviewService#apply(ApplyRequest)} ignores it.
 */
public final class MappingUpdateDiffValidator extends AbstractJsonValidator {
    private static final Set<String> TOP = Set.of("candidateSha256", "target", "changes");
    private static final Set<String> TARGET = Set.of("pack", "semanticName");
    private static final Set<String> CHANGE = Set.of("path", "before", "after");

    public MappingUpdateDiffValidator() {
        super("turboism.mapping.update.diff", "MAPPING_UPDATE_DIFF", 1, TOP);
    }

    @Override public List<SchemaValidationError> validate(final JsonNode node) {
        return validate(node, "");
    }

    @Override public List<SchemaValidationError> validate(final JsonNode node, final String source) {
        final List<SchemaValidationError> errors = new ArrayList<>(validateRoot(node, source));
        if (!node.path("candidateSha256").isTextual() || !node.path("candidateSha256").asText().matches("[0-9a-f]{64}")) {
            errors.add(error("MAPPING_UPDATE_DIFF_BAD_HASH", "candidateSha256 must be lowercase SHA-256", "candidateSha256", source));
        }
        validateObject(node.get("target"), TARGET, List.of("pack", "semanticName"), "target", errors, source);
        validateTarget(node.get("target"), errors, source);
        if (!node.path("changes").isArray() || node.path("changes").size() != 1) {
            errors.add(error("MAPPING_UPDATE_DIFF_BAD_CHANGES", "changes must contain exactly one runtime update", "changes", source));
        } else {
            final JsonNode change = node.path("changes").get(0);
            validateObject(change, CHANGE, List.of("path", "before", "after"), "changes[0]", errors, source);
            final String semanticName = node.path("target").path("semanticName").asText("");
            final String expectedPath = "entries[semanticName=" + semanticName + "].runtime";
            if (!change.path("path").isTextual() || !expectedPath.equals(change.path("path").asText())) {
                errors.add(error("MAPPING_UPDATE_DIFF_BAD_CHANGES", "change path must identify the target semanticName runtime field", "changes[0].path", source));
            }
            if (change.path("before").isTextual() && change.path("after").isTextual()
                && change.path("before").asText().equals(change.path("after").asText())) {
                errors.add(error("MAPPING_UPDATE_DIFF_BAD_CHANGES", "before and after must differ", "changes[0].after", source));
            }
        }
        return errors;
    }

    private void validateTarget(final JsonNode node, final List<SchemaValidationError> errors, final String source) {
        if (node == null || !node.isObject()) return;
        if (!node.path("pack").isTextual()
            || !DraftMappingGrammar.isDirectDraftPack(node.path("pack").asText())) {
            errors.add(error("MAPPING_UPDATE_DIFF_BAD_TARGET", "target.pack must name one direct DRAFT mapping pack", "target.pack", source));
        }
        if (!node.path("semanticName").isTextual()
            || !DraftMappingGrammar.isSafeSemanticName(node.path("semanticName").asText())) {
            errors.add(error("MAPPING_UPDATE_DIFF_BAD_TARGET", "target.semanticName must be non-blank and path-safe", "target.semanticName", source));
        }
    }

    private void validateObject(
        final JsonNode node,
        final Set<String> allowed,
        final List<String> required,
        final String path,
        final List<SchemaValidationError> errors,
        final String source
    ) {
        if (node == null || !node.isObject()) {
            errors.add(error("MAPPING_UPDATE_DIFF_MISSING", "Missing object: " + path, path, source));
            return;
        }
        node.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) errors.add(error("MAPPING_UPDATE_DIFF_UNKNOWN_FIELD", "Unknown field: " + field, path + "." + field, source));
        });
        for (String field : required) {
            if (!node.path(field).isTextual() || node.path(field).asText().isBlank()) {
                errors.add(error("MAPPING_UPDATE_DIFF_MISSING", "Missing field: " + field, path + "." + field, source));
            }
        }
    }
}
