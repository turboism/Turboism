package dev.turboism.core.schema.runtimeconfig;

import com.fasterxml.jackson.databind.JsonNode;
import dev.turboism.core.schema.AbstractJsonValidator;
import dev.turboism.core.schema.SchemaValidationError;
import dev.turboism.sdk.runtime.RuntimeSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Validator for turboism.runtime.config v1.
 */
public final class RuntimeConfigValidator extends AbstractJsonValidator {

    private static final Set<String> ALLOWED_FIELDS = Set.of(
        "worktreeId", "pluginDirs", "disabledPlugins", "logLevel", "maxLogStorageMiB",
        "safeMode", "diagnostics", "hooks"
    );
    private static final Set<String> ALLOWED_LOG_LEVELS = Set.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL");

    private static final Set<String> ALLOWED_HOOK_FIELDS = Set.of(
        "disabledIds", "denylistedClasses", "startup"
    );
    private static final Set<String> ALLOWED_STARTUP_FIELDS = Set.of(
        "skipUpdateCheck", "skipSplash", "skipInformation"
    );

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

        if (node.has("maxLogStorageMiB")) {
            final JsonNode value = node.get("maxLogStorageMiB");
            if (value == null || !value.isIntegralNumber()) {
                errors.add(error(
                    "RUNTIME_CONFIG_BAD_TYPE",
                    "maxLogStorageMiB must be an integer",
                    "maxLogStorageMiB",
                    source
                ));
            } else if (!value.canConvertToInt()
                || value.intValue() < RuntimeSettings.MIN_MAX_LOG_STORAGE_MIB
                || value.intValue() > RuntimeSettings.MAX_MAX_LOG_STORAGE_MIB) {
                errors.add(error(
                    "RUNTIME_CONFIG_BAD_LOG_STORAGE_LIMIT",
                    "maxLogStorageMiB must be between "
                        + RuntimeSettings.MIN_MAX_LOG_STORAGE_MIB + " and "
                        + RuntimeSettings.MAX_MAX_LOG_STORAGE_MIB,
                    "maxLogStorageMiB",
                    source
                ));
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

        validateStringArray(node, "disabledPlugins", "disabledPlugins", errors, source);

        validateOptionalBoolean(node, "safeMode", errors, source);
        validateHooks(node, errors, source);

        return errors;
    }

    private void validateHooks(
        final JsonNode root,
        final List<SchemaValidationError> errors,
        final String source
    ) {
        if (!root.has("hooks")) {
            return;
        }
        final JsonNode hooks = root.get("hooks");
        if (hooks == null || !hooks.isObject()) {
            errors.add(error("RUNTIME_CONFIG_BAD_TYPE", "hooks must be an object", "hooks", source));
            return;
        }
        hooks.fieldNames().forEachRemaining(field -> {
            if (!ALLOWED_HOOK_FIELDS.contains(field)) {
                errors.add(error(
                    "RUNTIME_CONFIG_UNKNOWN_FIELD",
                    "Unknown hooks field: " + field,
                    "hooks." + field,
                    source
                ));
            }
        });
        validateStringArray(hooks, "disabledIds", "hooks.disabledIds", errors, source);
        validateStringArray(hooks, "denylistedClasses", "hooks.denylistedClasses", errors, source);
        if (!hooks.has("startup")) {
            return;
        }
        final JsonNode startup = hooks.get("startup");
        if (startup == null || !startup.isObject()) {
            errors.add(error(
                "RUNTIME_CONFIG_BAD_TYPE",
                "hooks.startup must be an object",
                "hooks.startup",
                source
            ));
            return;
        }
        startup.fieldNames().forEachRemaining(field -> {
            if (!ALLOWED_STARTUP_FIELDS.contains(field)) {
                errors.add(error(
                    "RUNTIME_CONFIG_UNKNOWN_FIELD",
                    "Unknown hooks.startup field: " + field,
                    "hooks.startup." + field,
                    source
                ));
            }
        });
        for (String field : ALLOWED_STARTUP_FIELDS) {
            validateOptionalBoolean(startup, field, "hooks.startup." + field, errors, source);
        }
    }

    private void validateOptionalBoolean(
        final JsonNode node,
        final String field,
        final List<SchemaValidationError> errors,
        final String source
    ) {
        validateOptionalBoolean(node, field, field, errors, source);
    }

    private void validateOptionalBoolean(
        final JsonNode node,
        final String field,
        final String path,
        final List<SchemaValidationError> errors,
        final String source
    ) {
        if (node.has(field) && !node.get(field).isBoolean()) {
            errors.add(error("RUNTIME_CONFIG_BAD_TYPE", path + " must be a boolean", path, source));
        }
    }

    private void validateStringArray(
        final JsonNode node,
        final String field,
        final String path,
        final List<SchemaValidationError> errors,
        final String source
    ) {
        if (!node.has(field)) {
            return;
        }
        final JsonNode value = node.get(field);
        if (!value.isArray() || !allTextual(value)) {
            errors.add(error("RUNTIME_CONFIG_BAD_TYPE", path + " must be an array of strings", path, source));
        }
    }

    private static boolean allTextual(final JsonNode array) {
        for (JsonNode value : array) {
            if (!value.isTextual()) {
                return false;
            }
        }
        return true;
    }
}
