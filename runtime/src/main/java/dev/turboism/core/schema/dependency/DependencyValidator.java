package dev.turboism.core.schema.dependency;

import com.fasterxml.jackson.databind.JsonNode;
import dev.turboism.core.schema.AbstractJsonValidator;
import dev.turboism.core.schema.SchemaValidationError;
import dev.turboism.core.version.VersionRange;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Validator for turboism.dependency v1.
 */
public final class DependencyValidator extends AbstractJsonValidator {

    private static final Set<String> ALLOWED_FIELDS = Set.of("id", "version", "type", "ordering", "reason");

    public DependencyValidator() {
        super("turboism.dependency", "DEPENDENCY", 1, ALLOWED_FIELDS);
    }

    @Override
    public List<SchemaValidationError> validate(JsonNode node) {
        return validate(node, "");
    }

    @Override
    public List<SchemaValidationError> validate(JsonNode node, String source) {
        List<SchemaValidationError> errors = new ArrayList<>(validateRoot(node, source));
        requireStringField(node, "id", "DEPENDENCY_MISSING_ID", errors, source);
        requireStringField(node, "version", "DEPENDENCY_MISSING_VERSION", errors, source);

        if (node.has("id") && !node.get("id").isNull()) {
            String id = node.get("id").asText("");
            if (!id.matches("^[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*)+$")) {
                errors.add(error("DEPENDENCY_BAD_ID", "Dependency id must be a valid reverse-domain string: " + id, "id", source));
            }
        }

        if (node.has("version") && !node.get("version").isNull()) {
            try {
                VersionRange.parse(node.get("version").asText(""));
            } catch (IllegalArgumentException e) {
                errors.add(error("DEPENDENCY_BAD_VERSION_RANGE", e.getMessage(), "version", source));
            }
        }

        if (node.has("type") && !node.get("type").isNull() && !Set.of("required", "optional").contains(node.get("type").asText(""))) {
            errors.add(error("DEPENDENCY_BAD_TYPE", "type must be required or optional", "type", source));
        }

        if (node.has("ordering") && !node.get("ordering").isNull() && !Set.of("none", "before", "after").contains(node.get("ordering").asText(""))) {
            errors.add(error("DEPENDENCY_BAD_ORDERING", "ordering must be none, before, or after", "ordering", source));
        }

        return errors;
    }
}
