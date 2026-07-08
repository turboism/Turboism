package dev.turboism.mapping.schema;

import com.fasterxml.jackson.databind.JsonNode;
import dev.turboism.core.schema.AbstractJsonValidator;
import dev.turboism.core.schema.SchemaValidationError;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Validator for turboism.profile v1.
 */
public final class ProfileValidator extends AbstractJsonValidator {

    private static final Set<String> ALLOWED_FIELDS = Set.of(
        "profileId", "inherits", "mappingPacks", "hookSelectorRefs", "capabilities", "notes",
        "product", "versionRange", "fingerprintStrategy", "status", "source", "verifiedBy", "verifiedAt"
    );

    private static final Set<String> ALLOWED_STATUS = Set.of("DRAFT", "VERIFIED", "DEGRADED", "BROKEN", "RETIRED");

    public ProfileValidator() {
        super("turboism.profile", "PROFILE", 1, ALLOWED_FIELDS);
    }

    @Override
    public List<SchemaValidationError> validate(JsonNode node) {
        return validate(node, "");
    }

    @Override
    public List<SchemaValidationError> validate(JsonNode node, String source) {
        List<SchemaValidationError> errors = new ArrayList<>(validateRoot(node, source));
        requireStringField(node, "profileId", "PROFILE_MISSING", errors, source);
        requireArrayField(node, "mappingPacks", "PROFILE_MISSING", errors, source);
        requireStringField(node, "product", "PROFILE_BAD_PRODUCT", errors, source);
        requireStringField(node, "versionRange", "PROFILE_BAD_VERSION_RANGE", errors, source);
        requireStringField(node, "fingerprintStrategy", "PROFILE_BAD_FINGERPRINT_STRATEGY", errors, source);
        requireStringField(node, "status", "PROFILE_BAD_STATUS", errors, source);
        requireStringField(node, "source", "PROFILE_BAD_SOURCE", errors, source);
        requireStringField(node, "verifiedBy", "PROFILE_MISSING", errors, source);

        if (node.has("profileId") && node.get("profileId").isTextual() && node.get("profileId").asText().isBlank()) {
            errors.add(error("PROFILE_BAD_ID", "profileId must be non-empty", "profileId", source));
        }

        if (node.has("status") && !ALLOWED_STATUS.contains(node.get("status").asText())) {
            errors.add(error("PROFILE_BAD_STATUS", "status must be one of DRAFT, VERIFIED, DEGRADED, BROKEN, RETIRED", "status", source));
        }

        if (node.has("fingerprintStrategy") && !"none".equals(node.get("fingerprintStrategy").asText())) {
            errors.add(error("PROFILE_BAD_FINGERPRINT_STRATEGY", "fingerprintStrategy must be 'none' in Phase 1", "fingerprintStrategy", source));
        }

        if (node.has("verifiedAt") && !node.get("verifiedAt").isNull()) {
            String verifiedAt = node.get("verifiedAt").asText();
            if (!isIsoTimestamp(verifiedAt)) {
                errors.add(error("PROFILE_BAD_VERIFIED_AT", "verifiedAt must be null or a UTC ISO-8601 timestamp", "verifiedAt", source));
            }
        }

        if (node.has("mappingPacks") && node.get("mappingPacks").isArray() && node.get("mappingPacks").size() == 0) {
            errors.add(error("PROFILE_EMPTY_MAPPING_REFS", "mappingPacks must contain at least one reference", "mappingPacks", source));
        }

        if (node.has("inherits") && node.get("inherits").isArray()) {
            List<String> chain = new ArrayList<>();
            Set<String> visited = new HashSet<>();
            detectCycle(node, chain, visited, errors, source);
        }

        return errors;
    }

    private void detectCycle(JsonNode node, List<String> chain, Set<String> visited, List<SchemaValidationError> errors, String source) {
        if (!node.has("profileId") || !node.get("profileId").isTextual()) return;
        String id = node.get("profileId").asText();
        if (visited.contains(id)) {
            errors.add(error("PROFILE_CYCLIC_INHERITANCE", "Profile inheritance contains a cycle: " + id, "inherits", source));
            return;
        }
        visited.add(id);
        chain.add(id);
        if (node.has("inherits") && node.get("inherits").isArray()) {
            node.get("inherits").forEach(parent -> {
                if (parent.isTextual()) {
                    String parentId = parent.asText();
                    if (chain.contains(parentId)) {
                        errors.add(error("PROFILE_CYCLIC_INHERITANCE", "Profile inheritance contains a cycle: " + parentId, "inherits", source));
                    }
                }
            });
        }
        chain.remove(chain.size() - 1);
    }

    private static boolean isIsoTimestamp(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        try {
            java.time.Instant.parse(value);
            return true;
        } catch (java.time.format.DateTimeParseException e) {
            return false;
        }
    }
}
