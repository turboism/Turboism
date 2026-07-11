package dev.turboism.mapping.draft;

import com.fasterxml.jackson.databind.JsonNode;
import dev.turboism.core.schema.AbstractJsonValidator;
import dev.turboism.core.schema.SchemaValidationError;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Strict validator for a human decision over the exact candidate bytes. */
public final class MappingReviewValidator extends AbstractJsonValidator {
    private static final Set<String> FIELDS = Set.of("decision", "candidateSha256", "reviewer", "reviewedAt");
    private static final Set<String> DECISIONS = Set.of("PENDING", "APPROVED", "REJECTED");

    public MappingReviewValidator() {
        super("turboism.mapping.update.review", "MAPPING_UPDATE_REVIEW", 1, FIELDS);
    }

    @Override public List<SchemaValidationError> validate(final JsonNode node) {
        return validate(node, "");
    }

    @Override public List<SchemaValidationError> validate(final JsonNode node, final String source) {
        final List<SchemaValidationError> errors = new ArrayList<>(validateRoot(node, source));
        requireStringField(node, "decision", "MAPPING_UPDATE_REVIEW_MISSING", errors, source);
        if (!node.has("candidateSha256") || !node.get("candidateSha256").isTextual()
            || !node.get("candidateSha256").asText().matches("[0-9a-f]{64}")) {
            errors.add(error("MAPPING_UPDATE_REVIEW_BAD_HASH", "candidateSha256 must be lowercase SHA-256", "candidateSha256", source));
        }
        final String decision = node.path("decision").asText("");
        if (!DECISIONS.contains(decision)) {
            errors.add(error("MAPPING_UPDATE_REVIEW_BAD_DECISION", "decision must be PENDING, APPROVED, or REJECTED", "decision", source));
        }
        if ("PENDING".equals(decision)) {
            if (!node.has("reviewer") || !node.get("reviewer").isNull()
                || !node.has("reviewedAt") || !node.get("reviewedAt").isNull()) {
                errors.add(error("MAPPING_UPDATE_REVIEW_PENDING_FIELDS", "PENDING review must have null reviewer and reviewedAt", "decision", source));
            }
        } else if (DECISIONS.contains(decision)) {
            if (!node.has("reviewer") || !node.get("reviewer").isTextual() || node.get("reviewer").asText().isBlank()) {
                errors.add(error("MAPPING_UPDATE_REVIEW_MISSING", "reviewer is required after a decision", "reviewer", source));
            }
            if (!node.has("reviewedAt") || !node.get("reviewedAt").isTextual() || !isUtcInstant(node.get("reviewedAt").asText())) {
                errors.add(error("MAPPING_UPDATE_REVIEW_BAD_TIME", "reviewedAt must be a UTC ISO-8601 instant", "reviewedAt", source));
            }
        }
        return errors;
    }

    private static boolean isUtcInstant(final String value) {
        try {
            return Instant.parse(value).toString().equals(value);
        } catch (DateTimeParseException exception) {
            return false;
        }
    }
}
