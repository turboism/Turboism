package dev.turboism.core.schema.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import dev.turboism.core.schema.AbstractJsonValidator;
import dev.turboism.core.schema.SchemaValidationError;
import dev.turboism.core.version.VersionRange;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict validator for {@code turboism.plugin.meta} schema version 2. */
public final class PluginMetaValidator extends AbstractJsonValidator {

    private static final Set<String> ALLOWED_FIELDS = Set.of(
        "id", "name", "version", "description", "entrypoints", "turboismApi",
        "authors", "license", "website", "resources", "i18n", "dependencies",
        "permissions", "capabilities", "environment"
    );
    private static final Set<String> ALLOWED_AUTHOR_FIELDS = Set.of("name", "email");
    private static final Set<String> ALLOWED_I18N_FIELDS = Set.of("baseName", "locales");
    private static final Set<String> ALLOWED_ENVIRONMENT_FIELDS = Set.of("requiresCubism", "ui");
    private static final Set<String> ALLOWED_DEPENDENCY_FIELDS = Set.of(
        "id", "version", "type", "ordering", "reason"
    );
    private static final Set<String> ALLOWED_PERMISSION_FIELDS = Set.of("id", "scope", "reason");
    private static final Set<String> ALLOWED_DEPENDENCY_TYPES = Set.of("required", "optional");
    private static final Set<String> ALLOWED_DEPENDENCY_ORDERINGS = Set.of("none", "before", "after");
    private static final Set<String> ALLOWED_ENV_UI = Set.of("none", "swing", "embedded");
    private static final Set<String> KNOWN_PERMISSION_IDS = Set.of(
        "turboism.ui.menu", "turboism.ui.toolbar", "turboism.ui.palette",
        "turboism.cubism.project.read", "turboism.cubism.model.read", "turboism.cubism.model.write",
        "turboism.cubism.model.observe", "turboism.cubism.model.intercept",
        "turboism.cubism.parameter.read", "turboism.cubism.mesh.read",
        "turboism.file.read", "turboism.file.write", "turboism.network.fetch",
        "turboism.action.register", "turboism.ui.menu.contribute",
        "turboism.ui.toolbar.main.contribute", "turboism.ui.toolbar.palette.contribute",
        "turboism.ui.context-menu.contribute", "turboism.ui.context-source.read",
        "turboism.ui.overlay.contribute", "turboism.ui.viewport.read",
        "turboism.ui.dialog.contribute", "turboism.ui.panel.contribute",
        "turboism.ui.file-chooser.request", "turboism.ui.status.notify",
        "turboism.ui.appearance.modify", "turboism.ui.toolbar.contribute", "turboism.config.plugin.read",
        "turboism.config.plugin.write", "turboism.event.subscribe", "turboism.event.publish",
        "turboism.host.unsafe"
    );

    public PluginMetaValidator() {
        super("turboism.plugin.meta", "PLUGIN_META", 2, ALLOWED_FIELDS);
    }

    @Override
    public List<SchemaValidationError> validate(final JsonNode node) {
        return validate(node, "");
    }

    @Override
    public List<SchemaValidationError> validate(final JsonNode node, final String source) {
        final List<SchemaValidationError> errors = new ArrayList<>(validateRoot(node, source));
        if (errors.stream().anyMatch(error -> error.code().equals("PLUGIN_META_INVALID_JSON"))) {
            return errors;
        }

        requireStringField(node, "id", "PLUGIN_META_MISSING", errors, source);
        requireStringField(node, "name", "PLUGIN_META_MISSING", errors, source);
        requireStringField(node, "version", "PLUGIN_META_MISSING", errors, source);
        requireArrayField(node, "entrypoints", errors, source);
        requireStringField(node, "turboismApi", "PLUGIN_META_MISSING", errors, source);
        requireArrayField(node, "authors", errors, source);
        requireStringField(node, "website", "PLUGIN_META_MISSING", errors, source);
        requireArrayField(node, "resources", errors, source);
        requireObjectField(node, "i18n", "PLUGIN_META_MISSING", errors, source);

        validateId(node, errors, source);
        validateVersion(node, errors, source);
        validateVersionRange(node, errors, source);
        validateEntrypoints(node, errors, source);
        validateAuthors(node, errors, source);
        validateWebsite(node, errors, source);
        validateResources(node, errors, source);
        validateI18n(node, errors, source);
        validateEnvironment(node, errors, source);
        validateDependencies(node, errors, source);
        validatePermissions(node, errors, source);
        validateStringArray(node, "capabilities", "PLUGIN_META_BAD_CAPABILITIES", errors, source, false);
        return errors;
    }

    private void requireArrayField(
        final JsonNode node,
        final String field,
        final List<SchemaValidationError> errors,
        final String source
    ) {
        if (!node.has(field) || node.get(field).isNull() || !node.get(field).isArray()) {
            errors.add(error("PLUGIN_META_MISSING", field + " must be an array", field, source));
        }
    }

    private void validateId(
        final JsonNode node,
        final List<SchemaValidationError> errors,
        final String source
    ) {
        if (node.has("id") && node.get("id").isTextual()) {
            final String id = node.get("id").textValue();
            if (!isValidPluginId(id)) {
                errors.add(error("PLUGIN_META_BAD_ID", "Plugin ID must be a reverse-domain string", "id", source));
            }
        }
    }

    private void validateVersion(
        final JsonNode node,
        final List<SchemaValidationError> errors,
        final String source
    ) {
        if (node.has("version") && node.get("version").isTextual()
            && !isValidVersion(node.get("version").textValue())) {
            errors.add(error("PLUGIN_META_BAD_VERSION", "Plugin version must be MAJOR.MINOR.PATCH", "version", source));
        }
    }

    private void validateVersionRange(
        final JsonNode node,
        final List<SchemaValidationError> errors,
        final String source
    ) {
        if (!node.has("turboismApi") || !node.get("turboismApi").isTextual()) {
            return;
        }
        try {
            VersionRange.parse(node.get("turboismApi").textValue());
        } catch (IllegalArgumentException exception) {
            errors.add(error(
                "PLUGIN_META_BAD_VERSION_RANGE",
                "turboismApi is not a valid version range: " + exception.getMessage(),
                "turboismApi",
                source
            ));
        }
    }

    private void validateEntrypoints(
        final JsonNode node,
        final List<SchemaValidationError> errors,
        final String source
    ) {
        if (!node.has("entrypoints") || !node.get("entrypoints").isArray()) {
            return;
        }
        final JsonNode values = node.get("entrypoints");
        if (values.isEmpty()) {
            errors.add(error(
                "PLUGIN_META_MISSING_ENTRYPOINT",
                "entrypoints must contain at least one Java class",
                "entrypoints",
                source
            ));
            return;
        }
        final Set<String> seen = new HashSet<>();
        for (int index = 0; index < values.size(); index++) {
            final JsonNode value = values.get(index);
            final String path = "entrypoints[" + index + "]";
            if (!value.isTextual() || !isJavaBinaryName(value.textValue())) {
                errors.add(error("PLUGIN_META_BAD_ENTRYPOINT", "Entrypoint must be a Java binary name", path, source));
            } else if (!seen.add(value.textValue())) {
                errors.add(error("PLUGIN_META_DUPLICATE_ENTRYPOINT", "Entrypoints must be unique", path, source));
            }
        }
    }

    private void validateAuthors(
        final JsonNode node,
        final List<SchemaValidationError> errors,
        final String source
    ) {
        if (!node.has("authors") || !node.get("authors").isArray()) {
            return;
        }
        final JsonNode authors = node.get("authors");
        if (authors.isEmpty()) {
            errors.add(error("PLUGIN_META_MISSING_AUTHOR", "authors must not be empty", "authors", source));
        }
        for (int index = 0; index < authors.size(); index++) {
            final JsonNode author = authors.get(index);
            final String base = "authors[" + index + "]";
            if (!author.isObject()) {
                errors.add(error("PLUGIN_META_BAD_AUTHOR", "Author must be an object", base, source));
                continue;
            }
            rejectUnknownFields(author, ALLOWED_AUTHOR_FIELDS, "PLUGIN_META_UNKNOWN_AUTHOR_FIELD", base, errors, source);
            if (!author.has("name") || !author.get("name").isTextual() || author.get("name").asText().isBlank()) {
                errors.add(error("PLUGIN_META_BAD_AUTHOR", "Author name is required", base + ".name", source));
            }
            if (author.has("email") && (!author.get("email").isTextual() || author.get("email").asText().isBlank())) {
                errors.add(error("PLUGIN_META_BAD_AUTHOR", "Author email must be non-empty text", base + ".email", source));
            }
        }
    }

    private void validateWebsite(
        final JsonNode node,
        final List<SchemaValidationError> errors,
        final String source
    ) {
        if (!node.has("website") || !node.get("website").isTextual()) {
            return;
        }
        try {
            final URI uri = URI.create(node.get("website").textValue());
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null) {
                throw new IllegalArgumentException("website must be an absolute HTTP(S) URL");
            }
        } catch (IllegalArgumentException exception) {
            errors.add(error("PLUGIN_META_BAD_WEBSITE", exception.getMessage(), "website", source));
        }
    }

    private void validateResources(
        final JsonNode node,
        final List<SchemaValidationError> errors,
        final String source
    ) {
        if (!node.has("resources") || !node.get("resources").isArray()) {
            return;
        }
        final Set<String> seen = new HashSet<>();
        final JsonNode roots = node.get("resources");
        for (int index = 0; index < roots.size(); index++) {
            final JsonNode value = roots.get(index);
            final String path = "resources[" + index + "]";
            if (!value.isTextual() || !isResourceRoot(value.textValue())) {
                errors.add(error(
                    "PLUGIN_META_BAD_RESOURCE_ROOT",
                    "Resource root must be a normalized relative prefix ending in '/'",
                    path,
                    source
                ));
            } else if (!seen.add(value.textValue())) {
                errors.add(error("PLUGIN_META_DUPLICATE_RESOURCE_ROOT", "Resource roots must be unique", path, source));
            }
        }
    }

    private void validateI18n(
        final JsonNode node,
        final List<SchemaValidationError> errors,
        final String source
    ) {
        if (!node.has("i18n") || !node.get("i18n").isObject()) {
            return;
        }
        final JsonNode i18n = node.get("i18n");
        rejectUnknownFields(i18n, ALLOWED_I18N_FIELDS, "PLUGIN_META_UNKNOWN_I18N_FIELD", "i18n", errors, source);
        if (!i18n.has("baseName") || !i18n.get("baseName").isTextual()
            || !isResourceBaseName(i18n.get("baseName").asText())) {
            errors.add(error(
                "PLUGIN_META_BAD_I18N_BASE",
                "i18n.baseName must be a normalized resource base name",
                "i18n.baseName",
                source
            ));
        }
        if (!i18n.has("locales") || !i18n.get("locales").isArray()) {
            errors.add(error("PLUGIN_META_BAD_I18N_LOCALES", "i18n.locales must be an array", "i18n.locales", source));
            return;
        }
        final Set<String> seen = new HashSet<>();
        final JsonNode locales = i18n.get("locales");
        for (int index = 0; index < locales.size(); index++) {
            final JsonNode value = locales.get(index);
            final String path = "i18n.locales[" + index + "]";
            if (!value.isTextual() || !isLocaleId(value.textValue())) {
                errors.add(error("PLUGIN_META_BAD_I18N_LOCALE", "Invalid locale catalog ID", path, source));
            } else if (!seen.add(value.textValue())) {
                errors.add(error("PLUGIN_META_DUPLICATE_I18N_LOCALE", "Locale catalog IDs must be unique", path, source));
            }
        }
    }

    private void validateEnvironment(
        final JsonNode node,
        final List<SchemaValidationError> errors,
        final String source
    ) {
        if (!node.has("environment")) {
            return;
        }
        final JsonNode environment = node.get("environment");
        if (!environment.isObject()) {
            errors.add(error("PLUGIN_META_BAD_ENVIRONMENT", "environment must be an object", "environment", source));
            return;
        }
        rejectUnknownFields(
            environment,
            ALLOWED_ENVIRONMENT_FIELDS,
            "PLUGIN_META_UNKNOWN_ENVIRONMENT_FIELD",
            "environment",
            errors,
            source
        );
        if (environment.has("requiresCubism") && !environment.get("requiresCubism").isBoolean()) {
            errors.add(error(
                "PLUGIN_META_BAD_ENVIRONMENT",
                "environment.requiresCubism must be boolean",
                "environment.requiresCubism",
                source
            ));
        }
        if (environment.has("ui") && (!environment.get("ui").isTextual()
            || !ALLOWED_ENV_UI.contains(environment.get("ui").asText()))) {
            errors.add(error(
                "PLUGIN_META_BAD_ENVIRONMENT_UI",
                "environment.ui must be one of " + ALLOWED_ENV_UI,
                "environment.ui",
                source
            ));
        }
    }

    private void validateDependencies(
        final JsonNode node,
        final List<SchemaValidationError> errors,
        final String source
    ) {
        if (!node.has("dependencies") || node.get("dependencies").isNull()) {
            return;
        }
        final JsonNode dependencies = node.get("dependencies");
        if (!dependencies.isArray()) {
            errors.add(error("PLUGIN_META_BAD_DEPENDENCIES", "dependencies must be an array", "dependencies", source));
            return;
        }
        for (int index = 0; index < dependencies.size(); index++) {
            validateDependency(dependencies.get(index), index, errors, source);
        }
    }

    private void validateDependency(
        final JsonNode dependency,
        final int index,
        final List<SchemaValidationError> errors,
        final String source
    ) {
        final String base = "dependencies[" + index + "]";
        if (!dependency.isObject()) {
            errors.add(error("DEPENDENCY_INVALID", "Dependency must be an object", base, source));
            return;
        }
        rejectUnknownFields(dependency, ALLOWED_DEPENDENCY_FIELDS, "DEPENDENCY_UNKNOWN_FIELD", base, errors, source);
        if (!dependency.has("id") || !dependency.get("id").isTextual()
            || !isValidPluginId(dependency.get("id").asText())) {
            errors.add(error("DEPENDENCY_BAD_ID", "Dependency id must be a reverse-domain string", base + ".id", source));
        }
        if (!dependency.has("version") || !dependency.get("version").isTextual()
            || dependency.get("version").asText().isBlank()) {
            errors.add(error("DEPENDENCY_MISSING_VERSION", "Dependency version is required", base + ".version", source));
        } else {
            try {
                VersionRange.parse(dependency.get("version").asText());
            } catch (IllegalArgumentException exception) {
                errors.add(error("DEPENDENCY_BAD_VERSION_RANGE", exception.getMessage(), base + ".version", source));
            }
        }
        if (dependency.has("type") && (!dependency.get("type").isTextual()
            || !ALLOWED_DEPENDENCY_TYPES.contains(dependency.get("type").asText()))) {
            errors.add(error("DEPENDENCY_BAD_TYPE", "type must be required or optional", base + ".type", source));
        }
        if (dependency.has("ordering") && (!dependency.get("ordering").isTextual()
            || !ALLOWED_DEPENDENCY_ORDERINGS.contains(dependency.get("ordering").asText()))) {
            errors.add(error(
                "DEPENDENCY_BAD_ORDERING",
                "ordering must be none, before, or after",
                base + ".ordering",
                source
            ));
        }
    }

    private void validatePermissions(
        final JsonNode node,
        final List<SchemaValidationError> errors,
        final String source
    ) {
        if (!node.has("permissions") || node.get("permissions").isNull()) {
            return;
        }
        if (!node.get("permissions").isArray()) {
            errors.add(error("PLUGIN_META_BAD_PERMISSIONS", "permissions must be an array", "permissions", source));
            return;
        }
        node.get("permissions").forEach(permission -> validatePermission(permission, errors, source));
    }

    private void validatePermission(
        final JsonNode permission,
        final List<SchemaValidationError> errors,
        final String source
    ) {
        if (!permission.isObject()) {
            errors.add(error("PERMISSION_INVALID", "Permission must be an object", "permissions[]", source));
            return;
        }
        rejectUnknownFields(
            permission,
            ALLOWED_PERMISSION_FIELDS,
            "PERMISSION_UNKNOWN_FIELD",
            "permissions[]",
            errors,
            source
        );
        if (!permission.has("id") || !permission.get("id").isTextual()
            || permission.get("id").asText().isBlank()) {
            errors.add(error("PERMISSION_MISSING_ID", "Permission id is required", "permissions[].id", source));
            return;
        }
        final String id = permission.get("id").asText();
        if (!KNOWN_PERMISSION_IDS.contains(id)) {
            errors.add(error("PERMISSION_UNKNOWN_ID", "Unknown permission id: " + id, "permissions[].id", source));
        }
        if (!permission.has("reason") || !permission.get("reason").isTextual()
            || permission.get("reason").asText().isBlank()) {
            errors.add(error("PERMISSION_REASON_MISSING", "Permission reason is required", "permissions[].reason", source));
        }
        if (permission.has("scope") && (!permission.get("scope").isTextual()
            || !Set.of("application", "user").contains(permission.get("scope").asText()))) {
            errors.add(error("PERMISSION_BAD_SCOPE", "scope must be application or user", "permissions[].scope", source));
        }
    }

    private void validateStringArray(
        final JsonNode node,
        final String field,
        final String code,
        final List<SchemaValidationError> errors,
        final String source,
        final boolean required
    ) {
        if (!node.has(field)) {
            if (required) {
                errors.add(error("PLUGIN_META_MISSING", field + " is required", field, source));
            }
            return;
        }
        if (!node.get(field).isArray()) {
            errors.add(error(code, field + " must be an array", field, source));
            return;
        }
        final Set<String> seen = new HashSet<>();
        for (int index = 0; index < node.get(field).size(); index++) {
            final JsonNode value = node.get(field).get(index);
            if (!value.isTextual() || value.asText().isBlank() || !seen.add(value.asText())) {
                errors.add(error(code, field + " must contain unique non-empty strings", field + "[" + index + "]", source));
            }
        }
    }

    private void rejectUnknownFields(
        final JsonNode object,
        final Set<String> allowed,
        final String code,
        final String base,
        final List<SchemaValidationError> errors,
        final String source
    ) {
        final Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
        while (fields.hasNext()) {
            final String name = fields.next().getKey();
            if (!allowed.contains(name)) {
                errors.add(error(code, "Unknown field: " + name, base + "." + name, source));
            }
        }
    }

    private boolean isJavaBinaryName(final String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (String part : value.split("\\.", -1)) {
            if (part.isEmpty()) {
                return false;
            }
            int offset = 0;
            int point = part.codePointAt(offset);
            if (!Character.isJavaIdentifierStart(point)) {
                return false;
            }
            offset += Character.charCount(point);
            while (offset < part.length()) {
                point = part.codePointAt(offset);
                if (!Character.isJavaIdentifierPart(point)) {
                    return false;
                }
                offset += Character.charCount(point);
            }
        }
        return true;
    }

    private boolean isResourceRoot(final String value) {
        return isNormalizedResourcePath(value, true)
            && !value.regionMatches(true, 0, "META-INF/turboism/", 0, "META-INF/turboism/".length());
    }

    private boolean isResourceBaseName(final String value) {
        return isNormalizedResourcePath(value, false)
            && !value.endsWith(".properties")
            && !value.endsWith("/");
    }

    private boolean isNormalizedResourcePath(final String value, final boolean directory) {
        if (value == null || value.isBlank() || value.startsWith("/") || value.contains("\\")) {
            return false;
        }
        if (directory != value.endsWith("/")) {
            return false;
        }
        final String[] parts = value.split("/", -1);
        for (int index = 0; index < parts.length; index++) {
            final String part = parts[index];
            if (part.isEmpty() && index == parts.length - 1 && directory) {
                continue;
            }
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                return false;
            }
        }
        return true;
    }

    private boolean isLocaleId(final String value) {
        return "base".equals(value) || (value != null && value.matches("^[a-z]{2,3}(?:_[A-Za-z0-9]{2,8})*$"));
    }

    private boolean isValidPluginId(final String id) {
        return id != null && id.matches("^[a-z][a-z0-9-]*(?:\\.[a-z][a-z0-9-]*)+$");
    }

    private boolean isValidVersion(final String version) {
        if (version == null || !version.matches("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$")) {
            return false;
        }
        try {
            for (String part : version.split("\\.")) {
                Integer.parseInt(part);
            }
            return true;
        } catch (NumberFormatException exception) {
            return false;
        }
    }
}
