package dev.turboism.core.schema.runtimeconfig;

import com.fasterxml.jackson.databind.JsonNode;
import dev.turboism.core.schema.AbstractJsonValidator;
import dev.turboism.core.schema.SchemaValidationError;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Validator for turboism.runtime.config v1.
 */
public final class RuntimeConfigValidator extends AbstractJsonValidator {

    private static final Set<String> ALLOWED_FIELDS = Set.of(
        "worktreeId", "pluginDirs", "logLevel", "safeMode", "diagnostics", "hooks"
    );
    private static final Set<String> ALLOWED_LOG_LEVELS = Set.of("DEBUG", "INFO", "WARN", "ERROR");

    public RuntimeConfigValidator() {
        super("turboism.runtime.config", "RUNTIME_CONFIG", 1, ALLOWED_FIELDS);
    }

    @Override
    public List<SchemaValidationError> validate(JsonNode node) {
        return validate(node, "");
    }

    @Override
    public List<SchemaValidationError> validate(JsonNode node, String source) {
        List<SchemaValidationError> errors = new ArrayList<>(validateRoot(node, source));
        requireStringField(node, "worktreeId", "RUNTIME_CONFIG_MISSING", errors, source);

        if (node.has("worktreeId") && !node.get("worktreeId").isNull()) {
            String id = node.get("worktreeId").asText("");
            if (!id.matches("^[a-z][a-z0-9-]{2,63}$")) {
                errors.add(error("RUNTIME_CONFIG_BAD_WORKTREE_ID", "worktreeId does not match pattern [a-z][a-z0-9-]{2,63}: " + id, "worktreeId", source));
            }
        }

        if (node.has("logLevel") && !node.get("logLevel").isNull()) {
            String level = node.get("logLevel").asText("");
            if (!ALLOWED_LOG_LEVELS.contains(level)) {
                errors.add(error("RUNTIME_CONFIG_BAD_LOG_LEVEL", "logLevel must be one of " + ALLOWED_LOG_LEVELS + ": " + level, "logLevel", source));
            }
        }

        if (node.has("pluginDirs") && node.get("pluginDirs").isArray()) {
            node.get("pluginDirs").forEach(dir -> {
                if (dir.isTextual()) {
                    String path = dir.asText();
                    if (path.startsWith("/") || path.contains("..") || path.startsWith("\\\\")) {
                        errors.add(error("RUNTIME_CONFIG_BAD_PLUGIN_DIR", "pluginDirs must be relative paths without ..: " + path, "pluginDirs[]", source));
                    }
                }
            });
        }

        return errors;
    }
}
