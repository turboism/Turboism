package dev.turboism.core.schema.permission;

import com.fasterxml.jackson.databind.JsonNode;
import dev.turboism.core.schema.AbstractJsonValidator;
import dev.turboism.core.schema.SchemaValidationError;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Validator for turboism.permission v1.
 */
public final class PermissionValidator extends AbstractJsonValidator {

    private static final Set<String> ALLOWED_FIELDS = Set.of("id", "scope", "reason");
    private static final Set<String> KNOWN_IDS = Set.of(
        "turboism.ui.menu", "turboism.ui.toolbar", "turboism.ui.palette",
        "turboism.cubism.project.read", "turboism.cubism.model.read", "turboism.cubism.model.write",
        "turboism.cubism.parameter.read", "turboism.cubism.mesh.read",
        "turboism.file.read", "turboism.file.write", "turboism.network.fetch",
        "turboism.action.register", "turboism.ui.menu.contribute",
        "turboism.ui.toolbar.main.contribute", "turboism.ui.toolbar.palette.contribute",
        "turboism.config.plugin.read", "turboism.config.plugin.write",
        "turboism.event.subscribe", "turboism.event.publish"
    );

    public PermissionValidator() {
        super("turboism.permission", "PERMISSION", 1, ALLOWED_FIELDS);
    }

    @Override
    public List<SchemaValidationError> validate(JsonNode node) {
        return validate(node, "");
    }

    @Override
    public List<SchemaValidationError> validate(JsonNode node, String source) {
        List<SchemaValidationError> errors = new ArrayList<>(validateRoot(node, source));
        requireStringField(node, "id", "PERMISSION_MISSING_ID", errors, source);

        if (node.has("id") && !node.get("id").isNull()) {
            String id = node.get("id").asText("");
            if (!KNOWN_IDS.contains(id)) {
                errors.add(error("PERMISSION_UNKNOWN_ID", "Unknown permission id: " + id, "id", source));
            }
        }

        if (!node.has("reason") || node.get("reason").isNull() || !node.get("reason").isTextual() || node.get("reason").asText().isBlank()) {
            errors.add(error("PERMISSION_REASON_MISSING", "Permission reason is missing or empty", "reason", source));
        }

        if (node.has("scope") && !node.get("scope").isNull() && !Set.of("application", "user").contains(node.get("scope").asText(""))) {
            errors.add(error("PERMISSION_BAD_SCOPE", "scope must be application or user", "scope", source));
        }

        return errors;
    }
}
