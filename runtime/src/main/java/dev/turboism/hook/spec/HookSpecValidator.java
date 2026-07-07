package dev.turboism.hook.spec;

import com.fasterxml.jackson.databind.JsonNode;
import dev.turboism.core.schema.AbstractJsonValidator;
import dev.turboism.core.schema.SchemaValidationError;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Validator for turboism.hook.spec v1.
 */
public final class HookSpecValidator extends AbstractJsonValidator {

    private static final Set<String> ALLOWED_FIELDS = Set.of(
        "id", "phase", "kind", "required", "requiresMapping", "requiresCubismFacade", "requiresTransaction",
        "description", "owners"
    );
    private static final Set<String> ALLOWED_PHASES = Set.of("BEFORE", "AFTER", "REPLACE", "AROUND");
    private static final Set<String> ALLOWED_KINDS = Set.of("observing", "augmenting", "bridging", "patching");

    public HookSpecValidator() {
        super("turboism.hook.spec", "HOOK_SPEC", 1, ALLOWED_FIELDS);
    }

    @Override
    public List<SchemaValidationError> validate(JsonNode node) {
        return validate(node, "");
    }

    @Override
    public List<SchemaValidationError> validate(JsonNode node, String source) {
        List<SchemaValidationError> errors = new ArrayList<>(validateRoot(node, source));
        requireStringField(node, "id", "HOOK_SPEC_MISSING", errors, source);
        requireStringField(node, "phase", "HOOK_SPEC_MISSING", errors, source);
        requireStringField(node, "kind", "HOOK_SPEC_MISSING", errors, source);

        if (node.has("id") && !node.get("id").isNull()) {
            String id = node.get("id").asText("");
            if (!id.matches("^[a-zA-Z][a-zA-Z0-9_.-]*$")) {
                errors.add(error("HOOK_SPEC_BAD_ID", "Hook id must be a stable identifier: " + id, "id", source));
            }
        }

        if (node.has("phase") && !node.get("phase").isNull() && !ALLOWED_PHASES.contains(node.get("phase").asText(""))) {
            errors.add(error("HOOK_SPEC_BAD_PHASE", "phase must be one of " + ALLOWED_PHASES, "phase", source));
        }

        if (node.has("kind") && !node.get("kind").isNull() && !ALLOWED_KINDS.contains(node.get("kind").asText(""))) {
            errors.add(error("HOOK_SPEC_BAD_KIND", "kind must be one of " + ALLOWED_KINDS, "kind", source));
        }

        boolean required = node.has("required") && node.get("required").asBoolean(false);
        if (required) {
            if (!node.has("description") || !node.get("description").isTextual() || node.get("description").asText().isBlank()) {
                errors.add(error("HOOK_SPEC_REQUIRED_MISSING_META", "Required hooks must have a description", "description", source));
            }
            if (!node.has("owners") || !node.get("owners").isArray() || node.get("owners").size() == 0) {
                errors.add(error("HOOK_SPEC_REQUIRED_MISSING_META", "Required hooks must have at least one owner", "owners", source));
            }
        }

        return errors;
    }
}
