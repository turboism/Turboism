package dev.turboism.core.schema.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import dev.turboism.core.schema.AbstractJsonValidator;
import dev.turboism.core.schema.SchemaValidationError;
import dev.turboism.core.version.VersionRange;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Strict validator for turboism.plugin.meta v1.
 */
public final class PluginMetaValidator extends AbstractJsonValidator {

    private static final Set<String> ALLOWED_FIELDS = Set.of(
        "id", "name", "version", "description", "entrypoints", "turboismApi",
        "authors", "license", "homepage", "dependencies", "permissions", "capabilities", "environment"
    );

    private static final Set<String> ALLOWED_DEPENDENCY_FIELDS = Set.of(
        "id", "version", "type", "ordering", "reason"
    );

    private static final Set<String> ALLOWED_DEPENDENCY_TYPES = Set.of("required", "optional");
    private static final Set<String> ALLOWED_DEPENDENCY_ORDERINGS = Set.of("none", "before", "after");
    private static final Set<String> ALLOWED_ENV_UI = Set.of("none", "swing", "embedded");
    private static final Set<String> KNOWN_PERMISSION_IDS = Set.of(
        "turboism.ui.menu", "turboism.ui.toolbar", "turboism.ui.palette",
        "turboism.cubism.project.read", "turboism.cubism.model.read", "turboism.cubism.model.write",
        "turboism.cubism.parameter.read", "turboism.cubism.mesh.read",
        "turboism.file.read", "turboism.file.write", "turboism.network.fetch",
        "turboism.action.register", "turboism.ui.menu.contribute",
        "turboism.ui.toolbar.main.contribute", "turboism.ui.toolbar.palette.contribute",
        "turboism.ui.context-menu.contribute", "turboism.ui.context-source.read",
        "turboism.ui.overlay.contribute", "turboism.ui.viewport.read",
        "turboism.ui.dialog.contribute",
        "turboism.ui.panel.contribute", "turboism.ui.file-chooser.request",
        "turboism.ui.status.notify", "turboism.ui.toolbar.contribute",
        "turboism.config.plugin.read", "turboism.config.plugin.write",
        "turboism.event.subscribe", "turboism.event.publish"
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
            validateEntrypoint(node.get("entrypoints"), errors, source);
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

        validateDependencies(node, errors, source);

        if (node.has("permissions") && node.get("permissions").isArray()) {
            node.get("permissions").forEach(p -> validatePermission(p, errors, source));
        }

        return errors;
    }

    private void validateEntrypoint(JsonNode entrypoints, List<SchemaValidationError> errors, String source) {
        if (entrypoints.size() != 1 || !entrypoints.has("plugin")) {
            errors.add(error("PLUGIN_META_MISSING_ENTRYPOINT", "entrypoints must contain exactly plugin",
                "entrypoints", source));
            return;
        }
        JsonNode plugin = entrypoints.get("plugin");
        if (!plugin.isTextual() || !isJavaBinaryName(plugin.textValue())) {
            errors.add(error("PLUGIN_META_BAD_ENTRYPOINT", "entrypoints.plugin must be a Java binary name",
                "entrypoints.plugin", source));
        }
    }

    private boolean isJavaBinaryName(String value) {
        if (value == null || value.isEmpty()) return false;
        for (String part : value.split("\\.", -1)) {
            if (part.isEmpty()) return false;
            int offset = 0;
            int point = part.codePointAt(offset);
            if (!Character.isJavaIdentifierStart(point)) return false;
            offset += Character.charCount(point);
            while (offset < part.length()) {
                point = part.codePointAt(offset);
                if (!Character.isJavaIdentifierPart(point)) return false;
                offset += Character.charCount(point);
            }
        }
        return true;
    }

    private void validateDependencies(JsonNode node, List<SchemaValidationError> errors, String source) {
        if (!node.has("dependencies") || node.get("dependencies").isNull()) {
            return;
        }
        JsonNode dependencies = node.get("dependencies");
        if (!dependencies.isArray()) {
            errors.add(error("PLUGIN_META_BAD_DEPENDENCIES", "dependencies must be an array", "dependencies", source));
            return;
        }
        for (int index = 0; index < dependencies.size(); index++) {
            validateDependency(dependencies.get(index), index, errors, source);
        }
    }

    private void validateDependency(
        JsonNode dependency,
        int index,
        List<SchemaValidationError> errors,
        String source
    ) {
        String basePath = "dependencies[" + index + "]";
        if (!dependency.isObject()) {
            errors.add(error("DEPENDENCY_INVALID", "Dependency must be an object", basePath, source));
            return;
        }

        Iterator<Map.Entry<String, JsonNode>> fields = dependency.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!ALLOWED_DEPENDENCY_FIELDS.contains(field.getKey())) {
                errors.add(error(
                    "DEPENDENCY_UNKNOWN_FIELD",
                    "Unknown dependency field: " + field.getKey(),
                    basePath + "." + field.getKey(),
                    source
                ));
            }
        }

        if (!dependency.has("id") || dependency.get("id").isNull() || !dependency.get("id").isTextual() || dependency.get("id").asText().isBlank()) {
            errors.add(error("DEPENDENCY_MISSING_ID", "Dependency id is missing", basePath + ".id", source));
        } else if (!isValidPluginId(dependency.get("id").asText())) {
            errors.add(error("DEPENDENCY_BAD_ID", "Dependency id must be a valid reverse-domain string: " + dependency.get("id").asText(), basePath + ".id", source));
        }

        if (!dependency.has("version") || dependency.get("version").isNull() || !dependency.get("version").isTextual() || dependency.get("version").asText().isBlank()) {
            errors.add(error("DEPENDENCY_MISSING_VERSION", "Dependency version is missing", basePath + ".version", source));
        } else {
            try {
                VersionRange.parse(dependency.get("version").asText());
            } catch (IllegalArgumentException e) {
                errors.add(error("DEPENDENCY_BAD_VERSION_RANGE", e.getMessage(), basePath + ".version", source));
            }
        }

        if (dependency.has("type") && !dependency.get("type").isNull()) {
            String type = dependency.get("type").asText("");
            if (!ALLOWED_DEPENDENCY_TYPES.contains(type)) {
                errors.add(error("DEPENDENCY_BAD_TYPE", "type must be required or optional", basePath + ".type", source));
            }
        }

        if (dependency.has("ordering") && !dependency.get("ordering").isNull()) {
            String ordering = dependency.get("ordering").asText("");
            if (!ALLOWED_DEPENDENCY_ORDERINGS.contains(ordering)) {
                errors.add(error("DEPENDENCY_BAD_ORDERING", "ordering must be none, before, or after", basePath + ".ordering", source));
            }
        }
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
