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

/**
 * Strict validator for {@code turboism.plugin.meta} schema versions 2 and 3.
 *
 * <p>The no-argument constructor preserves the reviewed schema v2 contract;
 * {@link #v3()} selects the schema v3 contract that adds the required
 * {@code category} and the optional bounded {@code tags} field.</p>
 */
public final class PluginMetaValidator extends AbstractJsonValidator {

    private static final Set<String> V2_ALLOWED_FIELDS = Set.of(
        "id", "name", "version", "description", "entrypoints", "turboismApi",
        "authors", "license", "website", "resources", "i18n", "dependencies",
        "permissions", "capabilities", "environment"
    );
    private static final Set<String> V3_ALLOWED_FIELDS;
    private static final Set<String> V4_ALLOWED_FIELDS;
    static {
        final Set<String> v3 = new java.util.HashSet<>(V2_ALLOWED_FIELDS);
        v3.add("category");
        v3.add("tags");
        V3_ALLOWED_FIELDS = Set.copyOf(v3);
        final Set<String> v4 = new java.util.HashSet<>(V3_ALLOWED_FIELDS);
        v4.add("eventExports");
        v4.add("eventImports");
        V4_ALLOWED_FIELDS = Set.copyOf(v4);
    }
    private static final int MAX_TAGS = 12;
    private static final int MIN_TOKEN_LENGTH = 2;
    private static final int MAX_TOKEN_LENGTH = 32;
    private static final java.util.regex.Pattern KEBAB_TOKEN =
        java.util.regex.Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
    private static final Set<String> ALLOWED_AUTHOR_FIELDS = Set.of("name", "email");
    private static final Set<String> ALLOWED_I18N_FIELDS = Set.of("baseName", "locales");
    private static final Set<String> ALLOWED_ENVIRONMENT_FIELDS = Set.of("requiresCubism", "ui");
    private static final Set<String> ALLOWED_DEPENDENCY_FIELDS = Set.of(
        "id", "version", "type", "ordering", "reason"
    );
    private static final Set<String> ALLOWED_PERMISSION_FIELDS = Set.of("id", "scope", "reason");
    private static final Set<String> ALLOWED_EVENT_EXPORT_FIELDS = Set.of(
        "id", "contractVersion", "eventType", "abiSha256"
    );
    private static final Set<String> ALLOWED_EVENT_IMPORT_FIELDS = Set.of(
        "provider", "eventId", "contractVersion", "eventType", "abiSha256", "required"
    );
    private static final java.util.regex.Pattern EVENT_ID =
        java.util.regex.Pattern.compile("^[a-z][a-z0-9]*(?:[.-][a-z][a-z0-9-]*)*$");
    private static final java.util.regex.Pattern SHA256 =
        java.util.regex.Pattern.compile("^[0-9a-f]{64}$");
    private static final Set<String> ALLOWED_DEPENDENCY_TYPES = Set.of("required", "optional");
    private static final Set<String> ALLOWED_DEPENDENCY_ORDERINGS = Set.of("none", "before", "after");
    private static final Set<String> ALLOWED_ENV_UI = Set.of("none", "swing", "embedded");
    private static final Set<String> KNOWN_PERMISSION_IDS = Set.of(
        "turboism.ui.menu", "turboism.ui.toolbar", "turboism.ui.palette",
        "turboism.cubism.project.read", "turboism.cubism.model.read", "turboism.cubism.model.write",
        "turboism.cubism.model.observe", "turboism.cubism.model.intercept",
        "turboism.cubism.backup.observe", "turboism.cubism.selection.observe",
        "turboism.ui.scene-table.observe",
        "turboism.cubism.parameter.read", "turboism.cubism.mesh.read",
        "turboism.cubism.recent-file.read",
        "turboism.file.read", "turboism.file.write", "turboism.network.fetch",
        "turboism.process.run",
        "turboism.action.register", "turboism.ui.menu.contribute",
        "turboism.ui.toolbar.main.contribute", "turboism.ui.toolbar.palette.contribute",
        "turboism.ui.context-menu.contribute", "turboism.ui.context-source.read",
        "turboism.ui.overlay.contribute", "turboism.ui.viewport.read",
        "turboism.ui.recent-preview.contribute",
        "turboism.ui.dialog.contribute", "turboism.ui.dialog.automate", "turboism.ui.panel.contribute",
        "turboism.ui.file-chooser.request", "turboism.ui.status.notify",
        "turboism.ui.appearance.modify", "turboism.ui.appearance.observe",
        "turboism.ui.toolbar.contribute", "turboism.config.plugin.read",
        "turboism.config.plugin.write", "turboism.event.subscribe", "turboism.event.publish",
        "turboism.performance.stats.read", "turboism.host.unsafe"
    );

    /** Schema v2 contract; retained for existing fixture and CLI tooling. */
    public PluginMetaValidator() {
        this(2);
    }

    /** Schema v3 contract: v2 fields plus required {@code category} and optional {@code tags}. */
    public static PluginMetaValidator v3() {
        return new PluginMetaValidator(3);
    }

    /** Schema v4 contract: v3 classification plus public event exports and imports. */
    public static PluginMetaValidator v4() {
        return new PluginMetaValidator(4);
    }

    /** Validator for the declared schema version; versions other than 2-4 fail closed. */
    public static PluginMetaValidator forSchemaVersion(final int schemaVersion) {
        return switch (schemaVersion) {
            case 3 -> v3();
            case 4 -> v4();
            default -> new PluginMetaValidator();
        };
    }

    private PluginMetaValidator(final int schemaVersion) {
        super(
            "turboism.plugin.meta",
            "PLUGIN_META",
            schemaVersion,
            switch (schemaVersion) {
                case 4 -> V4_ALLOWED_FIELDS;
                case 3 -> V3_ALLOWED_FIELDS;
                default -> V2_ALLOWED_FIELDS;
            }
        );
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
        if (expectedSchemaVersion >= 3) {
            validateClassification(node, errors, source);
        }
        if (expectedSchemaVersion == 4) {
            validateEventContracts(node, errors, source);
        }
        return errors;
    }

    private void validateClassification(
        final JsonNode node,
        final List<SchemaValidationError> errors,
        final String source
    ) {
        if (!node.has("category") || node.get("category").isNull()) {
            errors.add(error(
                "PLUGIN_META_MISSING",
                "Missing required string field: category",
                "category",
                source
            ));
        } else if (!node.get("category").isTextual()
            || !isKebabToken(node.get("category").asText())) {
            errors.add(error(
                "PLUGIN_META_BAD_CATEGORY",
                "category must be a lowercase kebab-case token of 2-32 characters",
                "category",
                source
            ));
        }
        if (!node.has("tags")) {
            return;
        }
        final JsonNode tags = node.get("tags");
        if (!tags.isArray()) {
            errors.add(error("PLUGIN_META_BAD_TAGS", "tags must be an array", "tags", source));
            return;
        }
        if (tags.size() > MAX_TAGS) {
            errors.add(error(
                "PLUGIN_META_BAD_TAGS",
                "tags must contain at most " + MAX_TAGS + " values",
                "tags",
                source
            ));
        }
        final Set<String> seen = new HashSet<>();
        for (int index = 0; index < tags.size(); index++) {
            final JsonNode value = tags.get(index);
            final String path = "tags[" + index + "]";
            if (!value.isTextual() || !isKebabToken(value.asText())) {
                errors.add(error(
                    "PLUGIN_META_BAD_TAGS",
                    "tags must contain lowercase kebab-case tokens of 2-32 characters",
                    path,
                    source
                ));
            } else if (!seen.add(value.asText())) {
                errors.add(error("PLUGIN_META_BAD_TAGS", "Tags must be unique", path, source));
            }
        }
    }

    private void validateEventContracts(
        final JsonNode node,
        final List<SchemaValidationError> errors,
        final String source
    ) {
        validateEventExports(node, errors, source);
        validateEventImports(node, errors, source);
    }

    private void validateEventExports(
        final JsonNode node,
        final List<SchemaValidationError> errors,
        final String source
    ) {
        if (!node.has("eventExports")) {
            return;
        }
        final JsonNode exports = node.get("eventExports");
        if (!exports.isArray()) {
            errors.add(error(
                "PLUGIN_META_BAD_EVENT_EXPORTS",
                "eventExports must be an array",
                "eventExports",
                source
            ));
            return;
        }
        final Set<String> ids = new HashSet<>();
        final Set<String> types = new HashSet<>();
        for (int index = 0; index < exports.size(); index++) {
            final JsonNode export = exports.get(index);
            final String base = "eventExports[" + index + "]";
            if (!export.isObject()) {
                errors.add(error(
                    "PLUGIN_META_BAD_EVENT_EXPORT",
                    "Event export must be an object",
                    base,
                    source
                ));
                continue;
            }
            rejectUnknownFields(
                export,
                ALLOWED_EVENT_EXPORT_FIELDS,
                "PLUGIN_META_UNKNOWN_EVENT_EXPORT_FIELD",
                base,
                errors,
                source
            );
            final String id = requiredEventId(export, "id", base, errors, source);
            requiredVersion(export, "contractVersion", base, errors, source);
            final String type = requiredJavaType(export, "eventType", base, errors, source);
            requiredSha256(export, "abiSha256", base, errors, source);
            if (id != null && !ids.add(id)) {
                errors.add(error(
                    "PLUGIN_META_DUPLICATE_EVENT_EXPORT",
                    "Event export ids must be unique",
                    base + ".id",
                    source
                ));
            }
            if (type != null && !types.add(type)) {
                errors.add(error(
                    "PLUGIN_META_DUPLICATE_EVENT_TYPE",
                    "Event export types must be unique",
                    base + ".eventType",
                    source
                ));
            }
        }
    }

    private void validateEventImports(
        final JsonNode node,
        final List<SchemaValidationError> errors,
        final String source
    ) {
        if (!node.has("eventImports")) {
            return;
        }
        final JsonNode imports = node.get("eventImports");
        if (!imports.isArray()) {
            errors.add(error(
                "PLUGIN_META_BAD_EVENT_IMPORTS",
                "eventImports must be an array",
                "eventImports",
                source
            ));
            return;
        }
        final Set<String> routes = new HashSet<>();
        for (int index = 0; index < imports.size(); index++) {
            final JsonNode imported = imports.get(index);
            final String base = "eventImports[" + index + "]";
            if (!imported.isObject()) {
                errors.add(error(
                    "PLUGIN_META_BAD_EVENT_IMPORT",
                    "Event import must be an object",
                    base,
                    source
                ));
                continue;
            }
            rejectUnknownFields(
                imported,
                ALLOWED_EVENT_IMPORT_FIELDS,
                "PLUGIN_META_UNKNOWN_EVENT_IMPORT_FIELD",
                base,
                errors,
                source
            );
            final String provider = requiredPluginId(
                imported,
                "provider",
                base,
                errors,
                source
            );
            final String eventId = requiredEventId(
                imported,
                "eventId",
                base,
                errors,
                source
            );
            requiredVersionRange(imported, "contractVersion", base, errors, source);
            requiredJavaType(imported, "eventType", base, errors, source);
            requiredSha256(imported, "abiSha256", base, errors, source);
            if (imported.has("required") && !imported.get("required").isBoolean()) {
                errors.add(error(
                    "PLUGIN_META_BAD_EVENT_IMPORT",
                    "required must be boolean",
                    base + ".required",
                    source
                ));
            }
            if (provider != null && eventId != null
                && !routes.add(provider + " " + eventId)) {
                errors.add(error(
                    "PLUGIN_META_DUPLICATE_EVENT_IMPORT",
                    "Event import routes must be unique",
                    base,
                    source
                ));
            }
        }
    }

    private String requiredPluginId(
        final JsonNode node,
        final String field,
        final String base,
        final List<SchemaValidationError> errors,
        final String source
    ) {
        if (!node.has(field) || !node.get(field).isTextual()
            || !isValidPluginId(node.get(field).asText())) {
            errors.add(error(
                "PLUGIN_META_BAD_EVENT_PROVIDER",
                field + " must be a reverse-domain plugin id",
                base + "." + field,
                source
            ));
            return null;
        }
        return node.get(field).asText();
    }

    private String requiredEventId(
        final JsonNode node,
        final String field,
        final String base,
        final List<SchemaValidationError> errors,
        final String source
    ) {
        if (!node.has(field) || !node.get(field).isTextual()
            || !EVENT_ID.matcher(node.get(field).asText()).matches()) {
            errors.add(error(
                "PLUGIN_META_BAD_EVENT_ID",
                field + " must be a stable lowercase event id",
                base + "." + field,
                source
            ));
            return null;
        }
        return node.get(field).asText();
    }

    private String requiredJavaType(
        final JsonNode node,
        final String field,
        final String base,
        final List<SchemaValidationError> errors,
        final String source
    ) {
        if (!node.has(field) || !node.get(field).isTextual()
            || !isJavaBinaryName(node.get(field).asText())) {
            errors.add(error(
                "PLUGIN_META_BAD_EVENT_TYPE",
                field + " must be a Java binary name",
                base + "." + field,
                source
            ));
            return null;
        }
        return node.get(field).asText();
    }

    private void requiredVersion(
        final JsonNode node,
        final String field,
        final String base,
        final List<SchemaValidationError> errors,
        final String source
    ) {
        if (!node.has(field) || !node.get(field).isTextual()
            || !isValidVersion(node.get(field).asText())) {
            errors.add(error(
                "PLUGIN_META_BAD_EVENT_CONTRACT_VERSION",
                field + " must be MAJOR.MINOR.PATCH",
                base + "." + field,
                source
            ));
        }
    }

    private void requiredVersionRange(
        final JsonNode node,
        final String field,
        final String base,
        final List<SchemaValidationError> errors,
        final String source
    ) {
        if (!node.has(field) || !node.get(field).isTextual()) {
            errors.add(error(
                "PLUGIN_META_BAD_EVENT_CONTRACT_VERSION",
                field + " must be a version range",
                base + "." + field,
                source
            ));
            return;
        }
        try {
            VersionRange.parse(node.get(field).asText());
        } catch (IllegalArgumentException failure) {
            errors.add(error(
                "PLUGIN_META_BAD_EVENT_CONTRACT_VERSION",
                failure.getMessage(),
                base + "." + field,
                source
            ));
        }
    }

    private void requiredSha256(
        final JsonNode node,
        final String field,
        final String base,
        final List<SchemaValidationError> errors,
        final String source
    ) {
        if (!node.has(field) || !node.get(field).isTextual()
            || !SHA256.matcher(node.get(field).asText()).matches()) {
            errors.add(error(
                "PLUGIN_META_BAD_EVENT_ABI",
                field + " must be a lowercase SHA-256 digest",
                base + "." + field,
                source
            ));
        }
    }

    private static boolean isKebabToken(final String value) {
        return value != null
            && value.length() >= MIN_TOKEN_LENGTH
            && value.length() <= MAX_TOKEN_LENGTH
            && KEBAB_TOKEN.matcher(value).matches();
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
        return "base".equals(value) || (value != null && value.matches("^[A-Za-z]{2,8}(?:[-_][A-Za-z0-9]{1,8})*$"));
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
