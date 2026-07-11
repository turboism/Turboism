package dev.turboism.mapping.draft;

import com.fasterxml.jackson.databind.JsonNode;
import dev.turboism.core.schema.AbstractJsonValidator;
import dev.turboism.core.schema.SchemaValidationError;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Strict validator for the exact-byte mapping update candidate format. */
public final class MappingUpdateCandidateValidator extends AbstractJsonValidator {
    private static final Set<String> TOP = Set.of(
        "operation", "target", "basePackSha256", "artifact", "scannerPolicy",
        "evidence", "before", "after", "resultPackSha256"
    );
    private static final Set<String> TARGET = Set.of("pack", "semanticName");
    private static final Set<String> ARTIFACT = Set.of("name", "size", "sha256");
    private static final Set<String> POLICY = Set.of(
        "maxArtifactBytes", "maxEntries", "maxClassEntries", "maxEntryBytes", "maxExpandedBytes"
    );
    private static final Set<String> EVIDENCE = Set.of("caller", "selectedTarget", "invocation");
    private static final Set<String> CALLER = Set.of("owner", "name", "descriptor");
    private static final Set<String> SELECTED = Set.of("owner", "name", "descriptor");
    private static final Set<String> STATE = Set.of("kind", "runtime");

    public MappingUpdateCandidateValidator() {
        super("turboism.mapping.update.candidate", "MAPPING_UPDATE_CANDIDATE", 1, TOP);
    }

    @Override public List<SchemaValidationError> validate(final JsonNode node) { return validate(node, ""); }

    @Override
    public List<SchemaValidationError> validate(final JsonNode node, final String source) {
        final List<SchemaValidationError> errors = new ArrayList<>(validateRoot(node, source));
        requireText(node, "operation", errors, source);
        requireObjectField(node, "target", "MAPPING_UPDATE_CANDIDATE_MISSING", errors, source);
        requireHash(node, "basePackSha256", errors, source);
        requireObjectField(node, "artifact", "MAPPING_UPDATE_CANDIDATE_MISSING", errors, source);
        requireObjectField(node, "scannerPolicy", "MAPPING_UPDATE_CANDIDATE_MISSING", errors, source);
        requireObjectField(node, "evidence", "MAPPING_UPDATE_CANDIDATE_MISSING", errors, source);
        requireObjectField(node, "before", "MAPPING_UPDATE_CANDIDATE_MISSING", errors, source);
        requireObjectField(node, "after", "MAPPING_UPDATE_CANDIDATE_MISSING", errors, source);
        requireHash(node, "resultPackSha256", errors, source);
        if (node.has("operation") && !"UPDATE_CLASS_RUNTIME".equals(node.get("operation").asText())) {
            add(errors, "MAPPING_UPDATE_CANDIDATE_BAD_OPERATION", "operation must be UPDATE_CLASS_RUNTIME", "operation", source);
        }
        validateObject(node.get("target"), TARGET, List.of("pack", "semanticName"), "target", errors, source);
        validateTarget(node.get("target"), errors, source);
        validateArtifact(node.get("artifact"), errors, source);
        validatePolicy(node.get("scannerPolicy"), errors, source);
        validateEvidence(node.get("evidence"), errors, source);
        validateState(node.get("before"), "before", errors, source);
        validateState(node.get("after"), "after", errors, source);
        validateForbiddenSelectors(node, errors, source);
        if (node.path("before").path("runtime").isTextual()
            && node.path("after").path("runtime").isTextual()
            && node.path("before").path("runtime").asText().equals(node.path("after").path("runtime").asText())) {
            add(errors, "MAPPING_UPDATE_CANDIDATE_NO_CHANGE", "before.runtime and after.runtime must differ", "after.runtime", source);
        }
        if (node.path("after").path("runtime").isTextual()
            && node.path("evidence").path("selectedTarget").path("owner").isTextual()
            && !node.path("after").path("runtime").asText()
                .equals(node.path("evidence").path("selectedTarget").path("owner").asText())) {
            add(errors, "MAPPING_UPDATE_CANDIDATE_TARGET_MISMATCH",
                "after.runtime must equal evidence.selectedTarget.owner", "after.runtime", source);
        }
        return errors;
    }

    private void validateTarget(final JsonNode node, final List<SchemaValidationError> errors, final String source) {
        if (node == null || !node.isObject()) return;
        if (!node.path("pack").isTextual() || !DraftMappingGrammar.isDirectDraftPack(node.path("pack").asText())) {
            add(errors, "MAPPING_UPDATE_CANDIDATE_BAD_TARGET", "target.pack must name one direct DRAFT mapping pack", "target.pack", source);
        }
        if (!node.path("semanticName").isTextual()
            || !DraftMappingGrammar.isSafeSemanticName(node.path("semanticName").asText())) {
            add(errors, "MAPPING_UPDATE_CANDIDATE_BAD_TARGET", "target.semanticName must be non-blank and path-safe", "target.semanticName", source);
        }
    }

    private void validateArtifact(final JsonNode node, final List<SchemaValidationError> errors, final String source) {
        validateObject(node, ARTIFACT, List.of("name", "size", "sha256"), "artifact", errors, source);
        if (node != null && node.isObject()) {
            final String name = node.path("name").asText("");
            if (name.isBlank() || name.contains("/") || name.contains("\\") || name.contains("..") || name.contains(":")) {
                add(errors, "MAPPING_UPDATE_CANDIDATE_BAD_ARTIFACT", "artifact.name must be a file name", "artifact.name", source);
            }
            if (!node.path("size").isIntegralNumber() || node.path("size").asLong(-1) < 0) {
                add(errors, "MAPPING_UPDATE_CANDIDATE_BAD_ARTIFACT", "artifact.size must be non-negative", "artifact.size", source);
            }
            requireHash(node, "sha256", errors, source, "artifact.sha256");
        }
    }

    private void validatePolicy(final JsonNode node, final List<SchemaValidationError> errors, final String source) {
        validateObject(node, POLICY, List.copyOf(POLICY), "scannerPolicy", errors, source);
        if (node != null && node.isObject()) {
            for (String field : POLICY) {
                if (!node.path(field).isIntegralNumber() || node.path(field).asLong(0) <= 0) {
                    add(errors, "MAPPING_UPDATE_CANDIDATE_BAD_POLICY", "scanner policy limits must be positive integers", "scannerPolicy." + field, source);
                }
            }
        }
    }

    private void validateEvidence(final JsonNode node, final List<SchemaValidationError> errors, final String source) {
        validateObject(node, EVIDENCE, List.of("caller", "selectedTarget", "invocation"), "evidence", errors, source);
        if (node != null && node.isObject()) {
            validateObject(node.get("caller"), CALLER, List.copyOf(CALLER), "evidence.caller", errors, source);
            validateObject(node.get("selectedTarget"), SELECTED, List.copyOf(SELECTED), "evidence.selectedTarget", errors, source);
            validateMember(node.get("caller"), "evidence.caller", errors, source);
            validateMember(node.get("selectedTarget"), "evidence.selectedTarget", errors, source);
            if (!node.path("invocation").isTextual() || !Set.of("INSTANCE", "STATIC").contains(node.path("invocation").asText())) {
                add(errors, "MAPPING_UPDATE_CANDIDATE_BAD_EVIDENCE", "invocation is invalid", "evidence.invocation", source);
            }
        }
    }

    private void validateForbiddenSelectors(final JsonNode node, final List<SchemaValidationError> errors, final String source) {
        if (ForbiddenSelectorTerms.containsForbidden(
            node.path("target").path("semanticName").asText(null),
            node.path("before").path("runtime").asText(null),
            node.path("after").path("runtime").asText(null),
            node.path("evidence").path("caller").path("owner").asText(null),
            node.path("evidence").path("caller").path("name").asText(null),
            node.path("evidence").path("caller").path("descriptor").asText(null),
            node.path("evidence").path("selectedTarget").path("owner").asText(null),
            node.path("evidence").path("selectedTarget").path("name").asText(null),
            node.path("evidence").path("selectedTarget").path("descriptor").asText(null)
        )) {
            add(errors, "MAPPING_UPDATE_CANDIDATE_FORBIDDEN_SELECTOR",
                "candidate selectors must not target licensing or security controls", "target", source);
        }
    }

    private void validateState(final JsonNode node, final String path, final List<SchemaValidationError> errors, final String source) {
        validateObject(node, STATE, List.of("kind", "runtime"), path, errors, source);
        if (node != null && node.isObject()) {
            if (!"class".equals(node.path("kind").asText())) {
                add(errors, "MAPPING_UPDATE_CANDIDATE_BAD_STATE", "kind must be class", path + ".kind", source);
            }
            if (!node.path("runtime").isTextual() || node.path("runtime").asText().isBlank()) {
                add(errors, "MAPPING_UPDATE_CANDIDATE_BAD_STATE", "runtime must be non-blank text", path + ".runtime", source);
            }
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
        if (node == null || !node.isObject()) return;
        node.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) add(errors, "MAPPING_UPDATE_CANDIDATE_UNKNOWN_FIELD", "Unknown field: " + field, path + "." + field, source);
        });
        for (String field : required) {
            if (!node.has(field) || node.get(field).isNull()
                || (node.get(field).isTextual() && node.get(field).asText().isBlank())) {
                add(errors, "MAPPING_UPDATE_CANDIDATE_MISSING", "Missing field: " + field, path + "." + field, source);
            }
        }
    }

    private void validateMember(final JsonNode node, final String path, final List<SchemaValidationError> errors, final String source) {
        if (node == null || !node.isObject()) return;
        for (String field : List.of("owner", "name", "descriptor")) {
            requireNonBlankText(node, field, path + "." + field, errors, source);
        }
    }

    private void requireNonBlankText(final JsonNode node, final String field, final String path,
                                     final List<SchemaValidationError> errors, final String source) {
        if (!node.has(field) || !node.get(field).isTextual() || node.get(field).asText().isBlank()) {
            add(errors, "MAPPING_UPDATE_CANDIDATE_MISSING", field + " must be non-blank text", path, source);
        }
    }

    private void requireText(final JsonNode node, final String field, final List<SchemaValidationError> errors, final String source) {
        requireStringField(node, field, "MAPPING_UPDATE_CANDIDATE_MISSING", errors, source);
    }

    private void requireHash(final JsonNode node, final String field, final List<SchemaValidationError> errors, final String source) {
        requireHash(node, field, errors, source, field);
    }

    private void requireHash(final JsonNode node, final String field, final List<SchemaValidationError> errors, final String source, final String path) {
        if (node == null || !node.has(field) || !node.get(field).isTextual() || !node.get(field).asText().matches("[0-9a-f]{64}")) {
            add(errors, "MAPPING_UPDATE_CANDIDATE_BAD_HASH", field + " must be lowercase SHA-256", path, source);
        }
    }

    private void add(final List<SchemaValidationError> errors, final String code, final String message, final String path, final String source) {
        errors.add(error(code, message, path, source));
    }
}
