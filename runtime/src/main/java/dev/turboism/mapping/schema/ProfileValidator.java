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
        "id", "inherits", "mappingPackRefs", "hookSelectorRefs", "capabilities", "notes"
    );

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
        requireStringField(node, "id", "PROFILE_MISSING", errors, source);
        requireArrayField(node, "mappingPackRefs", "PROFILE_MISSING", errors, source);

        if (node.has("mappingPackRefs") && node.get("mappingPackRefs").isArray() && node.get("mappingPackRefs").size() == 0) {
            errors.add(error("PROFILE_EMPTY_MAPPING_REFS", "mappingPackRefs must contain at least one reference", "mappingPackRefs", source));
        }

        if (node.has("inherits") && node.get("inherits").isArray()) {
            List<String> chain = new ArrayList<>();
            Set<String> visited = new HashSet<>();
            detectCycle(node, chain, visited, errors, source);
        }

        return errors;
    }

    private void detectCycle(JsonNode node, List<String> chain, Set<String> visited, List<SchemaValidationError> errors, String source) {
        if (!node.has("id") || !node.get("id").isTextual()) return;
        String id = node.get("id").asText();
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
}
