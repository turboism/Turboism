package dev.turboism.core.schema.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import dev.turboism.core.schema.AbstractJsonValidator;
import dev.turboism.core.schema.SchemaValidationError;
import dev.turboism.core.version.VersionRange;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Strict validator for turboism.plugin.meta v1.
 */
public final class PluginMetaValidator extends AbstractJsonValidator {

    private static final Set<String> ALLOWED_FIELDS = Set.of(
        "id", "name", "version", "description", "entrypoints", "turboismApi",
        "authors", "license", "homepage", "dependencies", "permissions", "capabilities", "environment"
    );

    private static final Set<String> ALLOWED_ENV_UI = Set.of("none", "swing", "embedded");
    private static final Set<String> KNOWN_PERMISSION_IDS = Set.of(
        "turboism.ui.menu", "turboism.ui.toolbar", "turboism.ui.palette",
        "turboism.cubism.project.read", "turboism.cubism.model.read", "turboism.cubism.model.write",
        "turboism.file.read", "turboism.file.write", "turboism.network.fetch"
    );

    public PluginMetaValidator() {
        super("turboism.plugin.meta", "PLUGIN_META", 1, ALLOWED_FIELDS);
    }

    @Override
    public List<SchemaValidationError> validate(JsonNode node) {
        return validate(node, "");
    }

    @Override
    public List<SchemaValidationError> validate(JsonNode node, String source) {
        List<SchemaValidationError> errors = new ArrayList<>(validateRoot(node, source));
        if (errors.stream().anyMatch(e -> e.code().equals("PLUGIN_META_INVALID_JSON"))) {
            return errors;
        }

        requireStringField(node, "id", "PLUGIN_META_MISSING", errors, source);
        requireStringField(node, "name", "PLUGIN_META_MISSING", errors, source);
        requireStringField(node, "version", "PLUGIN_META_MISSING", errors, source);
        requireObjectField(node, "entrypoints", "PLUGIN_META_MISSING", errors, source);
        requireStringField(node, "turboismApi", "PLUGIN_META_MISSING", errors, source);

        if (node.has("id") && !node.get("id").isNull()) {
            String id = node.get("id").asText("");
            if (!isValidPluginId(id)) {
                errors.add(error("PLUGIN_META_BAD_ID", "Plugin ID must be a valid reverse-domain string: " + id, "id", source));
            }
        }

        if (node.has("version") && !node.get("version").isNull()) {
            String version = node.get("version").asText("");
            if (!isValidVersion(version)) {
                errors.add(error("PLUGIN_META_BAD_VERSION", "Plugin version must be MAJOR.MINOR.PATCH: " + version, "version", source));
            }
        }

        if (node.has("turboismApi") && !node.get("turboismApi").isNull()) {
            String range = node.get("turboismApi").asText("");
            try {
                VersionRange.parse(range);
            } catch (IllegalArgumentException e) {
                errors.add(error("PLUGIN_META_BAD_VERSION_RANGE", "turboismApi is not a valid version range: " + e.getMessage(), "turboismApi", source));
            }
        }

        if (node.has("entrypoints") && node.get("entrypoints").isObject()) {
            JsonNode entrypoints = node.get("entrypoints");
            if (!entrypoints.has("plugin") || entrypoints.get("plugin").isNull() || !entrypoints.get("plugin").isTextual()) {
                errors.add(error("PLUGIN_META_MISSING_ENTRYPOINT", "entrypoints.plugin is required", "entrypoints.plugin", source));
            }
        }

        if (node.has("environment") && node.get("environment").isObject()) {
            JsonNode env = node.get("environment");
            if (env.has("ui") && !env.get("ui").isNull()) {
                String ui = env.get("ui").asText("");
                if (!ALLOWED_ENV_UI.contains(ui)) {
                    errors.add(error("PLUGIN_META_BAD_ENVIRONMENT_UI", "environment.ui must be one of " + ALLOWED_ENV_UI + ": " + ui, "environment.ui", source));
                }
            }
        }

        if (node.has("permissions") && node.get("permissions").isArray()) {
            node.get("permissions").forEach(p -> validatePermission(p, errors, source));
        }

        return errors;
    }

    private void validatePermission(JsonNode p, List<SchemaValidationError> errors, String source) {
        if (!p.isObject()) return;
        if (!p.has("id") || p.get("id").isNull() || !p.get("id").isTextual() || p.get("id").asText().isBlank()) {
            errors.add(error("PERMISSION_MISSING_ID", "Permission id is missing", "permissions[].id", source));
            return;
        }
        String id = p.get("id").asText();
        if (!KNOWN_PERMISSION_IDS.contains(id)) {
            errors.add(error("PERMISSION_UNKNOWN_ID", "Unknown permission id: " + id, "permissions[].id", source));
        }
        if (!p.has("reason") || p.get("reason").isNull() || !p.get("reason").isTextual() || p.get("reason").asText().isBlank()) {
            errors.add(error("PERMISSION_REASON_MISSING", "Permission reason is missing", "permissions[].reason", source));
        }
        if (p.has("scope") && !p.get("scope").isNull() && !Set.of("application", "user").contains(p.get("scope").asText(""))) {
            errors.add(error("PERMISSION_BAD_SCOPE", "Permission scope must be application or user", "permissions[].scope", source));
        }
    }

    private boolean isValidPluginId(String id) {
        if (id == null || id.isBlank()) return false;
        if (id.startsWith(".") || id.endsWith(".")) return false;
        return id.matches("^[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*)+$");
    }

    private boolean isValidVersion(String version) {
        if (version == null || version.isBlank()) return false;
        String[] parts = version.split("\\.");
        if (parts.length != 3) return false;
        try {
            for (String part : parts) {
                if (Integer.parseInt(part) < 0) return false;
            }
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
