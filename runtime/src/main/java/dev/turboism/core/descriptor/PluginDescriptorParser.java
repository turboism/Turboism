package dev.turboism.core.descriptor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.turboism.core.descriptor.CorePluginDescriptor.CoreAuthor;
import dev.turboism.core.descriptor.CorePluginDescriptor.CoreDependencyRef;
import dev.turboism.core.descriptor.CorePluginDescriptor.CoreEnvironment;
import dev.turboism.core.descriptor.CorePluginDescriptor.CoreEventContract;
import dev.turboism.core.descriptor.CorePluginDescriptor.CoreEventExport;
import dev.turboism.core.descriptor.CorePluginDescriptor.CoreEventImport;
import dev.turboism.core.descriptor.CorePluginDescriptor.CoreI18n;
import dev.turboism.core.descriptor.CorePluginDescriptor.CorePermissionRef;
import dev.turboism.core.schema.SchemaValidationError;
import dev.turboism.sdk.plugin.PluginDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Reads {@code META-INF/turboism/plugin.json} into exactly one immutable descriptor, validating
 * while it reads.
 *
 * <p>The admitted schema version is declared by the document and governs it: version 2 declares
 * identity, packaging, dependencies, permissions and host requirements, version 3 adds
 * classification, version 4 adds public event exports and imports, and version 5 adds embedded
 * public event contract artifacts. An absent, non-integer or unadmitted {@code schemaVersion} fails
 * closed with {@code PLUGIN_META_BAD_SCHEMA_VERSION} and no further finding, because no tier
 * governs the rest of that document.</p>
 *
 * <p>Every field is described once, by {@link DescriptorSchemaTier}, together with the code, message
 * and path its violations report; the same description both reads the value and produces the
 * findings, so validation and construction cannot drift apart. Findings are reported root before
 * fields and required before value, so {@link #parse(JsonNode, String)} fails with the same first
 * code the field-by-field order suggests, and {@link #check(JsonNode, String)} returns all of them
 * for the gates that report every problem of a manifest.</p>
 */
public final class PluginDescriptorParser {

    /** Largest descriptor accepted from a stream; a bigger one fails with {@code PLUGIN_META_TOO_LARGE}. */
    public static final int MAX_DESCRIPTOR_BYTES = 1024 * 1024;

    private static final String ROOT = "";
    private static final String STREAM_SOURCE = "<input stream>";
    private static final String STRING_SOURCE = "<input string>";
    private static final String TOO_LARGE_CODE = "PLUGIN_META_TOO_LARGE";
    private static final String INVALID_JSON_CODE = "PLUGIN_META_INVALID_JSON";
    private static final String MISSING_CODE = "PLUGIN_META_MISSING";
    private static final String UNKNOWN_FIELD_CODE = "PLUGIN_META_UNKNOWN_FIELD";
    private static final String BAD_SCHEMA_VERSION_CODE = "PLUGIN_META_BAD_SCHEMA_VERSION";
    private static final String BAD_EVENT_ID_CODE = "PLUGIN_META_BAD_EVENT_ID";
    private static final String BAD_EVENT_TYPE_CODE = "PLUGIN_META_BAD_EVENT_TYPE";
    private static final String BAD_EVENT_ABI_CODE = "PLUGIN_META_BAD_EVENT_ABI";
    private static final String BAD_CONTRACT_VERSION_CODE = "PLUGIN_META_BAD_EVENT_CONTRACT_VERSION";
    private static final String MISSING_REQUIRED_STRING = "Missing required string field: ";
    private static final String MISSING_REQUIRED_OBJECT = "Missing required object field: ";
    private static final String MUST_BE_ARRAY = " must be an array";

    // Configuration is fixed before publication; Jackson can safely reuse its caches across plugins.
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Reads and validates a manifest from a stream. The stream is consumed but not closed.
     *
     * @param source stream containing the {@code plugin.json} bytes
     * @return the validated descriptor
     * @throws DescriptorParseException with code {@code PLUGIN_META_TOO_LARGE} when the stream
     *     exceeds {@link #MAX_DESCRIPTOR_BYTES}, {@code PLUGIN_META_INVALID_JSON} when the bytes are
     *     not well-formed JSON, or with the first schema finding's code
     */
    public PluginDescriptor parse(final InputStream source) throws DescriptorParseException {
        try {
            final byte[] bytes = source.readNBytes(MAX_DESCRIPTOR_BYTES + 1);
            if (bytes.length > MAX_DESCRIPTOR_BYTES) {
                throw new DescriptorParseException(TOO_LARGE_CODE, "plugin.json exceeds 1 MiB", STREAM_SOURCE);
            }
            return parseNode(MAPPER.readTree(bytes), STREAM_SOURCE);
        } catch (IOException exception) {
            throw new DescriptorParseException(INVALID_JSON_CODE, "Failed to parse plugin.json", exception);
        }
    }

    /**
     * Reads and validates a manifest held as a string.
     *
     * @param json the {@code plugin.json} document text
     * @return the validated descriptor
     * @throws DescriptorParseException with code {@code PLUGIN_META_INVALID_JSON} when the text is
     *     not well-formed JSON, or with the first schema finding's code
     */
    public PluginDescriptor parse(final String json) throws DescriptorParseException {
        try {
            return parseNode(MAPPER.readTree(json), STRING_SOURCE);
        } catch (IOException exception) {
            throw new DescriptorParseException(INVALID_JSON_CODE, "Failed to parse plugin.json", exception);
        }
    }

    /**
     * Reads and validates an already-parsed manifest tree against the schema version it declares.
     *
     * @param root the manifest as a JSON tree
     * @param source label used for diagnostics only
     * @return the validated descriptor
     * @throws DescriptorParseException carrying the code, message and path of the first finding
     */
    public PluginDescriptor parse(final JsonNode root, final String source) throws DescriptorParseException {
        return parseNode(root, source);
    }

    /**
     * Reports every finding of an already-parsed manifest tree, in the order {@link
     * #parse(JsonNode, String)} would report the first one.
     *
     * <p>This is the gate surface: a well-formed tree never throws, and an empty result means the
     * document is admissible under the schema version it declares.</p>
     *
     * @param root the manifest as a JSON tree
     * @param source label used for diagnostics only
     * @return every finding in read order, immutable
     */
    public List<SchemaValidationError> check(final JsonNode root, final String source) {
        final Findings findings = new Findings(source);
        checkRoot(root, findings, Optional.empty())
                .ifPresent(tier -> readFields(root, tier, new DescriptorBuilder(), findings));
        return findings.all();
    }

    /**
     * Reports every finding of a manifest tree held to one schema version, for the gates that own
     * the version instead of reading it from the document (the schema fixture harness and the
     * metadata CLI).
     *
     * @param root the manifest as a JSON tree
     * @param source label used for diagnostics only
     * @param version the schema version to hold the document to; a version no tier admits leaves
     *     the document held to the version it declares itself
     * @return every finding in read order, immutable
     */
    public List<SchemaValidationError> check(final JsonNode root, final String source, final int version) {
        final Findings findings = new Findings(source);
        checkRoot(root, findings, DescriptorSchemaTier.forVersion(version))
                .ifPresent(tier -> readFields(root, tier, new DescriptorBuilder(), findings));
        return findings.all();
    }

    private PluginDescriptor parseNode(final JsonNode root, final String source) throws DescriptorParseException {
        final Findings findings = new Findings(source);
        final Optional<DescriptorSchemaTier> tier = checkRoot(root, findings, Optional.empty());
        if (tier.isEmpty()) {
            throw findings.failure();
        }
        final DescriptorBuilder descriptor = new DescriptorBuilder();
        readFields(root, tier.get(), descriptor, findings);
        if (!findings.isEmpty()) {
            throw findings.failure();
        }
        return descriptor.build();
    }

    /**
     * Reads the root fields that select the schema and rejects top-level fields no tier admits.
     *
     * @param expected the tier the caller holds the document to, empty when the document's own
     *     declared version selects it
     * @return the tier that governs the rest of the read, empty when none does
     */
    private static Optional<DescriptorSchemaTier> checkRoot(
            final JsonNode root, final Findings findings, final Optional<DescriptorSchemaTier> expected) {
        if (root == null || !root.isObject()) {
            findings.add(INVALID_JSON_CODE, "Expected a JSON object", ROOT);
            return Optional.empty();
        }
        requireStringField(root, DescriptorSchemaTier.FORMAT_FIELD, findings);
        requireIntegerField(root, DescriptorSchemaTier.SCHEMA_VERSION_FIELD, findings);
        final JsonNode format = root.get(DescriptorSchemaTier.FORMAT_FIELD);
        if (format != null && !DescriptorSchemaTier.FORMAT.equals(format.asText(""))) {
            findings.add(
                    "PLUGIN_META_BAD_FORMAT",
                    "format must be " + DescriptorSchemaTier.FORMAT,
                    DescriptorSchemaTier.FORMAT_FIELD);
        }
        final int declared = declaredVersion(root);
        final Optional<DescriptorSchemaTier> governing;
        if (expected.isPresent()) {
            governing = expected;
            if (declared != expected.get().version()) {
                findings.add(
                        BAD_SCHEMA_VERSION_CODE,
                        "schemaVersion must be " + expected.get().version(),
                        DescriptorSchemaTier.SCHEMA_VERSION_FIELD);
            }
        } else {
            governing = DescriptorSchemaTier.forVersion(declared);
            if (governing.isEmpty()) {
                findings.add(
                        BAD_SCHEMA_VERSION_CODE,
                        "schemaVersion must be " + DescriptorSchemaTier.OLDEST_VERSION + "-"
                                + DescriptorSchemaTier.NEWEST_VERSION,
                        DescriptorSchemaTier.SCHEMA_VERSION_FIELD);
            }
        }
        // A document no tier governs has no admitted field set, so it is read no further.
        governing.ifPresent(tier -> findings.rejectUnknownTopLevel(root, tier.allowedFieldNames(), UNKNOWN_FIELD_CODE));
        return governing;
    }

    private static int declaredVersion(final JsonNode root) {
        final JsonNode version = root.get(DescriptorSchemaTier.SCHEMA_VERSION_FIELD);
        return version == null || version.isNull() ? -1 : version.asInt(-1);
    }

    private static void requireStringField(final JsonNode root, final String field, final Findings findings) {
        final JsonNode value = root.get(field);
        if (value == null
                || value.isNull()
                || !value.isTextual()
                || value.asText().isBlank()) {
            findings.add(MISSING_CODE, MISSING_REQUIRED_STRING + field, field);
        }
    }

    private static void requireIntegerField(final JsonNode root, final String field, final Findings findings) {
        final JsonNode value = root.get(field);
        if (value == null || value.isNull() || !value.isIntegralNumber()) {
            findings.add(MISSING_CODE, "Missing required integer field: " + field, field);
        }
    }

    /**
     * Reads the whole document against one tier: the required fields' presence first, then every
     * field's value, so a missing field is always reported before an invalid one.
     */
    private static void readFields(
            final JsonNode root,
            final DescriptorSchemaTier tier,
            final DescriptorBuilder descriptor,
            final Findings findings) {
        for (DescriptorFieldSpec field : tier.sweptFields()) {
            checkPresence(root, field, findings);
        }
        for (DescriptorFieldSpec field : tier.fields()) {
            field.reader().read(root, descriptor, findings);
        }
    }

    private static void checkPresence(final JsonNode root, final DescriptorFieldSpec field, final Findings findings) {
        final JsonNode value = root.get(field.name());
        switch (field.presence()) {
            case REQUIRED_STRING -> requireStringField(root, field.name(), findings);
            case REQUIRED_ARRAY -> requireArrayField(value, field.name(), findings);
            case REQUIRED_OBJECT -> {
                if (value == null || value.isNull() || !value.isObject()) {
                    findings.add(MISSING_CODE, MISSING_REQUIRED_OBJECT + field.name(), field.name());
                }
            }
            default -> {
                // Inline-presence and optional fields are reported by their own reader.
            }
        }
    }

    private static void requireArrayField(final JsonNode value, final String field, final Findings findings) {
        if (value == null || value.isNull() || !value.isArray()) {
            findings.add(MISSING_CODE, field + MUST_BE_ARRAY, field);
        }
    }

    static void readId(final JsonNode root, final DescriptorBuilder descriptor, final Findings findings) {
        final String id = text(root, "id");
        descriptor.id(id);
        if (root.has("id") && root.get("id").isTextual() && !DescriptorPredicates.isValidPluginId(id)) {
            findings.add("PLUGIN_META_BAD_ID", "Plugin ID must be a reverse-domain string", "id");
        }
    }

    static void readName(final JsonNode root, final DescriptorBuilder descriptor, final Findings findings) {
        descriptor.name(text(root, "name"));
    }

    static void readVersion(final JsonNode root, final DescriptorBuilder descriptor, final Findings findings) {
        final String version = text(root, "version");
        descriptor.version(version);
        if (root.has("version") && root.get("version").isTextual() && !DescriptorPredicates.isValidVersion(version)) {
            findings.add("PLUGIN_META_BAD_VERSION", "Plugin version must be MAJOR.MINOR.PATCH", "version");
        }
    }

    static void readDescription(final JsonNode root, final DescriptorBuilder descriptor, final Findings findings) {
        descriptor.description(text(root, "description"));
    }

    static void readEntrypoints(final JsonNode root, final DescriptorBuilder descriptor, final Findings findings) {
        final JsonNode values = root.get("entrypoints");
        if (values == null || !values.isArray()) {
            return;
        }
        final List<String> entrypoints = texts(values);
        descriptor.entrypoints(entrypoints);
        if (values.isEmpty()) {
            findings.add(
                    "PLUGIN_META_MISSING_ENTRYPOINT",
                    "entrypoints must contain at least one Java class",
                    "entrypoints");
            return;
        }
        final Set<String> seen = new HashSet<>();
        for (int index = 0; index < values.size(); index++) {
            final JsonNode value = values.get(index);
            final String path = "entrypoints[" + index + "]";
            if (!value.isTextual() || !DescriptorPredicates.isJavaBinaryName(value.textValue())) {
                findings.add("PLUGIN_META_BAD_ENTRYPOINT", "Entrypoint must be a Java binary name", path);
            } else if (!seen.add(value.textValue())) {
                findings.add("PLUGIN_META_DUPLICATE_ENTRYPOINT", "Entrypoints must be unique", path);
            }
        }
    }

    static void readTurboismApi(final JsonNode root, final DescriptorBuilder descriptor, final Findings findings) {
        final String turboismApi = text(root, "turboismApi");
        descriptor.turboismApi(turboismApi);
        if (!root.has("turboismApi") || !root.get("turboismApi").isTextual()) {
            return;
        }
        DescriptorPredicates.versionRangeFailure(turboismApi)
                .ifPresent(failure -> findings.add(
                        "PLUGIN_META_BAD_VERSION_RANGE",
                        "turboismApi is not a valid version range: " + failure,
                        "turboismApi"));
    }

    static void readAuthors(final JsonNode root, final DescriptorBuilder descriptor, final Findings findings) {
        final JsonNode authors = root.get("authors");
        if (authors == null || !authors.isArray()) {
            return;
        }
        final List<PluginDescriptor.Author> declared = new ArrayList<>();
        descriptor.authors(declared);
        if (authors.isEmpty()) {
            findings.add("PLUGIN_META_MISSING_AUTHOR", "authors must not be empty", "authors");
        }
        for (int index = 0; index < authors.size(); index++) {
            final JsonNode author = authors.get(index);
            final String base = "authors[" + index + "]";
            if (!author.isObject()) {
                findings.add("PLUGIN_META_BAD_AUTHOR", "Author must be an object", base);
                continue;
            }
            findings.rejectUnknown(
                    author, DescriptorSchemaTier.ALLOWED_AUTHOR_FIELDS, "PLUGIN_META_UNKNOWN_AUTHOR_FIELD", base);
            final JsonNode name = author.get("name");
            if (name == null || !name.isTextual() || name.asText().isBlank()) {
                findings.add("PLUGIN_META_BAD_AUTHOR", "Author name is required", base + ".name");
            }
            final JsonNode email = author.get("email");
            if (email != null && (!email.isTextual() || email.asText().isBlank())) {
                findings.add("PLUGIN_META_BAD_AUTHOR", "Author email must be non-empty text", base + ".email");
            }
            final Optional<String> contact =
                    email != null && email.isTextual() && !email.asText().isBlank()
                            ? Optional.of(email.asText())
                            : Optional.empty();
            declared.add(new CoreAuthor(name != null && name.isTextual() ? name.asText() : "", contact));
        }
    }

    static void readLicense(final JsonNode root, final DescriptorBuilder descriptor, final Findings findings) {
        descriptor.license(text(root, "license"));
    }

    static void readWebsite(final JsonNode root, final DescriptorBuilder descriptor, final Findings findings) {
        if (!root.has("website") || !root.get("website").isTextual()) {
            return;
        }
        final String website = root.get("website").textValue();
        DescriptorPredicates.websiteFailure(website)
                .ifPresentOrElse(
                        failure -> findings.add("PLUGIN_META_BAD_WEBSITE", failure, "website"),
                        () -> descriptor.website(Optional.of(website)));
    }

    static void readResources(final JsonNode root, final DescriptorBuilder descriptor, final Findings findings) {
        final JsonNode roots = root.get("resources");
        if (roots == null || !roots.isArray()) {
            return;
        }
        descriptor.resources(texts(roots));
        final Set<String> seen = new HashSet<>();
        for (int index = 0; index < roots.size(); index++) {
            final JsonNode value = roots.get(index);
            final String path = "resources[" + index + "]";
            if (!value.isTextual() || !DescriptorPredicates.isResourceRoot(value.textValue())) {
                findings.add(
                        "PLUGIN_META_BAD_RESOURCE_ROOT",
                        "Resource root must be a normalized relative prefix ending in '/'",
                        path);
            } else if (!seen.add(value.textValue())) {
                findings.add("PLUGIN_META_DUPLICATE_RESOURCE_ROOT", "Resource roots must be unique", path);
            }
        }
    }

    static void readI18n(final JsonNode root, final DescriptorBuilder descriptor, final Findings findings) {
        final JsonNode i18n = root.get("i18n");
        if (i18n == null || !i18n.isObject()) {
            return;
        }
        findings.rejectUnknown(
                i18n, DescriptorSchemaTier.ALLOWED_I18N_FIELDS, "PLUGIN_META_UNKNOWN_I18N_FIELD", "i18n");
        final JsonNode baseName = i18n.get("baseName");
        if (baseName == null || !baseName.isTextual() || !DescriptorPredicates.isResourceBaseName(baseName.asText())) {
            findings.add(
                    "PLUGIN_META_BAD_I18N_BASE",
                    "i18n.baseName must be a normalized resource base name",
                    "i18n.baseName");
        }
        final JsonNode locales = i18n.get("locales");
        final List<String> catalogs = locales == null || !locales.isArray() ? List.of() : texts(locales);
        descriptor.i18n(new CoreI18n(baseName != null && baseName.isTextual() ? baseName.asText() : "", catalogs));
        if (locales == null || !locales.isArray()) {
            findings.add("PLUGIN_META_BAD_I18N_LOCALES", "i18n.locales must be an array", "i18n.locales");
            return;
        }
        final Set<String> seen = new HashSet<>();
        for (int index = 0; index < locales.size(); index++) {
            final JsonNode value = locales.get(index);
            final String path = "i18n.locales[" + index + "]";
            if (!value.isTextual() || !DescriptorPredicates.isLocaleId(value.textValue())) {
                findings.add("PLUGIN_META_BAD_I18N_LOCALE", "Invalid locale catalog ID", path);
            } else if (!seen.add(value.textValue())) {
                findings.add("PLUGIN_META_DUPLICATE_I18N_LOCALE", "Locale catalog IDs must be unique", path);
            }
        }
    }

    static void readEnvironment(final JsonNode root, final DescriptorBuilder descriptor, final Findings findings) {
        final JsonNode environment = root.get("environment");
        if (environment == null) {
            descriptor.environment(new CoreEnvironment(false, "none"));
            return;
        }
        if (!environment.isObject()) {
            findings.add("PLUGIN_META_BAD_ENVIRONMENT", "environment must be an object", "environment");
            descriptor.environment(new CoreEnvironment(false, "none"));
            return;
        }
        findings.rejectUnknown(
                environment,
                DescriptorSchemaTier.ALLOWED_ENVIRONMENT_FIELDS,
                "PLUGIN_META_UNKNOWN_ENVIRONMENT_FIELD",
                "environment");
        final JsonNode requiresCubism = environment.get("requiresCubism");
        if (requiresCubism != null && !requiresCubism.isBoolean()) {
            findings.add(
                    "PLUGIN_META_BAD_ENVIRONMENT",
                    "environment.requiresCubism must be boolean",
                    "environment.requiresCubism");
        }
        final JsonNode ui = environment.get("ui");
        if (ui != null && (!ui.isTextual() || !DescriptorPredicates.ALLOWED_ENV_UI.contains(ui.asText()))) {
            findings.add(
                    "PLUGIN_META_BAD_ENVIRONMENT_UI",
                    "environment.ui must be one of " + DescriptorPredicates.ALLOWED_ENV_UI,
                    "environment.ui");
        }
        descriptor.environment(new CoreEnvironment(
                requiresCubism != null && requiresCubism.asBoolean(), ui != null ? ui.asText() : "none"));
    }

    static void readLifecycle(final JsonNode root, final DescriptorBuilder descriptor, final Findings findings) {
        final JsonNode lifecycle = root.get("lifecycle");
        if (lifecycle == null) {
            descriptor.hotReload(false);
            return;
        }
        if (!lifecycle.isObject()) {
            findings.add("PLUGIN_META_BAD_LIFECYCLE", "lifecycle must be an object", "lifecycle");
            descriptor.hotReload(false);
            return;
        }
        findings.rejectUnknown(
                lifecycle,
                DescriptorSchemaTier.ALLOWED_LIFECYCLE_FIELDS,
                "PLUGIN_META_UNKNOWN_LIFECYCLE_FIELD",
                "lifecycle");
        final JsonNode hotReload = lifecycle.get("hotReload");
        if (hotReload != null && !hotReload.isBoolean()) {
            findings.add("PLUGIN_META_BAD_LIFECYCLE", "lifecycle.hotReload must be boolean", "lifecycle.hotReload");
        }
        descriptor.hotReload(hotReload != null && hotReload.asBoolean());
    }

    static void readDependencies(final JsonNode root, final DescriptorBuilder descriptor, final Findings findings) {
        final JsonNode dependencies = root.get("dependencies");
        if (dependencies == null || dependencies.isNull()) {
            return;
        }
        if (!dependencies.isArray()) {
            findings.add("PLUGIN_META_BAD_DEPENDENCIES", "dependencies must be an array", "dependencies");
            return;
        }
        final List<PluginDescriptor.DependencyRef> declared = new ArrayList<>();
        descriptor.dependencies(declared);
        for (int index = 0; index < dependencies.size(); index++) {
            readDependency(dependencies.get(index), index, declared, findings);
        }
    }

    static void readDependency(
            final JsonNode dependency,
            final int index,
            final List<PluginDescriptor.DependencyRef> declared,
            final Findings findings) {
        final String base = "dependencies[" + index + "]";
        if (!dependency.isObject()) {
            findings.add("DEPENDENCY_INVALID", "Dependency must be an object", base);
            return;
        }
        findings.rejectUnknown(
                dependency, DescriptorSchemaTier.ALLOWED_DEPENDENCY_FIELDS, "DEPENDENCY_UNKNOWN_FIELD", base);
        final JsonNode id = dependency.get("id");
        if (id == null || !id.isTextual() || !DescriptorPredicates.isValidPluginId(id.asText())) {
            findings.add("DEPENDENCY_BAD_ID", "Dependency id must be a reverse-domain string", base + ".id");
        }
        final JsonNode version = dependency.get("version");
        if (version == null || !version.isTextual() || version.asText().isBlank()) {
            findings.add("DEPENDENCY_MISSING_VERSION", "Dependency version is required", base + ".version");
        } else {
            DescriptorPredicates.versionRangeFailure(version.asText())
                    .ifPresent(failure -> findings.add("DEPENDENCY_BAD_VERSION_RANGE", failure, base + ".version"));
        }
        final JsonNode type = dependency.get("type");
        if (type != null
                && (!type.isTextual() || !DescriptorPredicates.ALLOWED_DEPENDENCY_TYPES.contains(type.asText()))) {
            findings.add("DEPENDENCY_BAD_TYPE", "type must be required or optional", base + ".type");
        }
        final JsonNode ordering = dependency.get("ordering");
        if (ordering != null
                && (!ordering.isTextual()
                        || !DescriptorPredicates.ALLOWED_DEPENDENCY_ORDERINGS.contains(ordering.asText()))) {
            findings.add("DEPENDENCY_BAD_ORDERING", "ordering must be none, before, or after", base + ".ordering");
        }
        declared.add(new CoreDependencyRef(
                id != null && id.isTextual() ? id.asText() : "",
                type != null && type.isTextual() ? type.asText("required") : "required",
                version != null && version.isTextual() ? version.asText() : "",
                ordering != null && ordering.isTextual() ? ordering.asText("none") : "none",
                dependency.has("reason") ? Optional.of(dependency.get("reason").asText()) : Optional.empty()));
    }

    static void readPermissions(final JsonNode root, final DescriptorBuilder descriptor, final Findings findings) {
        final JsonNode permissions = root.get("permissions");
        if (permissions == null || permissions.isNull()) {
            return;
        }
        if (!permissions.isArray()) {
            findings.add("PLUGIN_META_BAD_PERMISSIONS", "permissions must be an array", "permissions");
            return;
        }
        final List<PluginDescriptor.PermissionRef> declared = new ArrayList<>();
        descriptor.permissions(declared);
        permissions.forEach(permission -> readPermission(permission, declared, findings));
    }

    static void readPermission(
            final JsonNode permission, final List<PluginDescriptor.PermissionRef> declared, final Findings findings) {
        if (!permission.isObject()) {
            findings.add("PERMISSION_INVALID", "Permission must be an object", "permissions[]");
            return;
        }
        findings.rejectUnknown(
                permission,
                DescriptorSchemaTier.ALLOWED_PERMISSION_FIELDS,
                "PERMISSION_UNKNOWN_FIELD",
                "permissions[]");
        final JsonNode id = permission.get("id");
        if (id == null || !id.isTextual() || id.asText().isBlank()) {
            findings.add("PERMISSION_MISSING_ID", "Permission id is required", "permissions[].id");
            return;
        }
        if (!DescriptorPredicates.KNOWN_PERMISSION_IDS.contains(id.asText())) {
            findings.add("PERMISSION_UNKNOWN_ID", "Unknown permission id: " + id.asText(), "permissions[].id");
        }
        final JsonNode reason = permission.get("reason");
        if (reason == null || !reason.isTextual() || reason.asText().isBlank()) {
            findings.add("PERMISSION_REASON_MISSING", "Permission reason is required", "permissions[].reason");
        }
        final JsonNode scope = permission.get("scope");
        if (scope != null
                && (!scope.isTextual() || !DescriptorPredicates.ALLOWED_PERMISSION_SCOPES.contains(scope.asText()))) {
            findings.add("PERMISSION_BAD_SCOPE", "scope must be application or user", "permissions[].scope");
        }
        declared.add(new CorePermissionRef(
                id.asText(),
                scope != null && scope.isTextual() ? scope.asText("application") : "application",
                reason != null && reason.isTextual() ? Optional.of(reason.asText()) : Optional.empty()));
    }

    static void readCapabilities(final JsonNode root, final DescriptorBuilder descriptor, final Findings findings) {
        final JsonNode capabilities = root.get("capabilities");
        if (capabilities == null) {
            return;
        }
        if (!capabilities.isArray()) {
            findings.add("PLUGIN_META_BAD_CAPABILITIES", "capabilities must be an array", "capabilities");
            return;
        }
        descriptor.capabilities(texts(capabilities));
        final Set<String> seen = new HashSet<>();
        for (int index = 0; index < capabilities.size(); index++) {
            final JsonNode value = capabilities.get(index);
            if (!value.isTextual() || value.asText().isBlank() || !seen.add(value.asText())) {
                findings.add(
                        "PLUGIN_META_BAD_CAPABILITIES",
                        "capabilities must contain unique non-empty strings",
                        "capabilities[" + index + "]");
            }
        }
    }

    static void readCategory(final JsonNode root, final DescriptorBuilder descriptor, final Findings findings) {
        final JsonNode category = root.get("category");
        if (category == null || category.isNull()) {
            findings.add(MISSING_CODE, MISSING_REQUIRED_STRING + "category", "category");
            return;
        }
        if (!category.isTextual() || !DescriptorPredicates.isKebabToken(category.asText())) {
            findings.add(
                    "PLUGIN_META_BAD_CATEGORY",
                    "category must be a lowercase kebab-case token of 2-32 characters",
                    "category");
            return;
        }
        descriptor.category(Optional.of(category.asText()));
    }

    static void readTags(final JsonNode root, final DescriptorBuilder descriptor, final Findings findings) {
        final JsonNode tags = root.get("tags");
        if (tags == null) {
            return;
        }
        if (!tags.isArray()) {
            findings.add("PLUGIN_META_BAD_TAGS", "tags must be an array", "tags");
            return;
        }
        descriptor.tags(texts(tags));
        if (tags.size() > DescriptorPredicates.MAX_TAGS) {
            findings.add(
                    "PLUGIN_META_BAD_TAGS",
                    "tags must contain at most " + DescriptorPredicates.MAX_TAGS + " values",
                    "tags");
        }
        final Set<String> seen = new HashSet<>();
        for (int index = 0; index < tags.size(); index++) {
            final JsonNode value = tags.get(index);
            final String path = "tags[" + index + "]";
            if (!value.isTextual() || !DescriptorPredicates.isKebabToken(value.textValue())) {
                findings.add(
                        "PLUGIN_META_BAD_TAGS",
                        "tags must contain lowercase kebab-case tokens of 2-32 characters",
                        path);
            } else if (!seen.add(value.textValue())) {
                findings.add("PLUGIN_META_BAD_TAGS", "Tags must be unique", path);
            }
        }
    }

    static void readEventExports(final JsonNode root, final DescriptorBuilder descriptor, final Findings findings) {
        final JsonNode exports = root.get("eventExports");
        if (exports == null) {
            return;
        }
        if (!exports.isArray()) {
            findings.add("PLUGIN_META_BAD_EVENT_EXPORTS", "eventExports must be an array", "eventExports");
            return;
        }
        final List<PluginDescriptor.EventExport> declared = new ArrayList<>();
        descriptor.eventExports(declared);
        final Set<String> ids = new HashSet<>();
        final Set<String> types = new HashSet<>();
        for (int index = 0; index < exports.size(); index++) {
            final JsonNode export = exports.get(index);
            final String base = "eventExports[" + index + "]";
            if (!export.isObject()) {
                findings.add("PLUGIN_META_BAD_EVENT_EXPORT", "Event export must be an object", base);
                continue;
            }
            findings.rejectUnknown(
                    export,
                    DescriptorSchemaTier.ALLOWED_EVENT_EXPORT_FIELDS,
                    "PLUGIN_META_UNKNOWN_EVENT_EXPORT_FIELD",
                    base);
            final String id = readEventId(export, "id", base, findings);
            readContractVersion(export, "contractVersion", base, findings);
            final String type = readEventType(export, "eventType", base, findings);
            readSha256(export, "abiSha256", base, findings);
            if (id != null && !ids.add(id)) {
                findings.add("PLUGIN_META_DUPLICATE_EVENT_EXPORT", "Event export ids must be unique", base + ".id");
            }
            if (type != null && !types.add(type)) {
                findings.add(
                        "PLUGIN_META_DUPLICATE_EVENT_TYPE", "Event export types must be unique", base + ".eventType");
            }
            declared.add(new CoreEventExport(
                    orEmpty(id),
                    orEmpty(text(export, "contractVersion")),
                    orEmpty(type),
                    orEmpty(text(export, "abiSha256"))));
        }
    }

    static void readEventImports(final JsonNode root, final DescriptorBuilder descriptor, final Findings findings) {
        final JsonNode imports = root.get("eventImports");
        if (imports == null) {
            return;
        }
        if (!imports.isArray()) {
            findings.add("PLUGIN_META_BAD_EVENT_IMPORTS", "eventImports must be an array", "eventImports");
            return;
        }
        final List<PluginDescriptor.EventImport> declared = new ArrayList<>();
        descriptor.eventImports(declared);
        final Set<String> routes = new HashSet<>();
        for (int index = 0; index < imports.size(); index++) {
            final JsonNode imported = imports.get(index);
            final String base = "eventImports[" + index + "]";
            if (!imported.isObject()) {
                findings.add("PLUGIN_META_BAD_EVENT_IMPORT", "Event import must be an object", base);
                continue;
            }
            findings.rejectUnknown(
                    imported,
                    DescriptorSchemaTier.ALLOWED_EVENT_IMPORT_FIELDS,
                    "PLUGIN_META_UNKNOWN_EVENT_IMPORT_FIELD",
                    base);
            final JsonNode provider = imported.get("provider");
            final String providerId =
                    provider != null && provider.isTextual() && DescriptorPredicates.isValidPluginId(provider.asText())
                            ? provider.asText()
                            : null;
            if (providerId == null) {
                findings.add(
                        "PLUGIN_META_BAD_EVENT_PROVIDER",
                        "provider must be a reverse-domain plugin id",
                        base + ".provider");
            }
            final String eventId = readEventId(imported, "eventId", base, findings);
            readContractVersionRange(imported, "contractVersion", base, findings);
            readEventType(imported, "eventType", base, findings);
            readSha256(imported, "abiSha256", base, findings);
            final JsonNode required = imported.get("required");
            if (required != null && !required.isBoolean()) {
                findings.add("PLUGIN_META_BAD_EVENT_IMPORT", "required must be boolean", base + ".required");
            }
            if (providerId != null && eventId != null && !routes.add(providerId + '\u0000' + eventId)) {
                findings.add("PLUGIN_META_DUPLICATE_EVENT_IMPORT", "Event import routes must be unique", base);
            }
            declared.add(new CoreEventImport(
                    orEmpty(providerId),
                    orEmpty(eventId),
                    orEmpty(text(imported, "contractVersion")),
                    orEmpty(text(imported, "eventType")),
                    orEmpty(text(imported, "abiSha256")),
                    required == null || required.asBoolean()));
        }
    }

    static void readEventContracts(final JsonNode root, final DescriptorBuilder descriptor, final Findings findings) {
        final JsonNode contracts = root.get("eventContracts");
        if (contracts == null) {
            return;
        }
        if (!contracts.isArray()) {
            findings.add("PLUGIN_META_BAD_EVENT_CONTRACTS", "eventContracts must be an array", "eventContracts");
            return;
        }
        final List<PluginDescriptor.EventContract> declared = new ArrayList<>();
        descriptor.eventContracts(declared);
        final Set<String> ids = new HashSet<>();
        final Set<String> artifacts = new HashSet<>();
        for (int index = 0; index < contracts.size(); index++) {
            final JsonNode contract = contracts.get(index);
            final String base = "eventContracts[" + index + "]";
            if (!contract.isObject()) {
                findings.add("PLUGIN_META_BAD_EVENT_CONTRACT", "Event contract must be an object", base);
                continue;
            }
            findings.rejectUnknown(
                    contract,
                    DescriptorSchemaTier.ALLOWED_EVENT_CONTRACT_FIELDS,
                    "PLUGIN_META_UNKNOWN_EVENT_CONTRACT_FIELD",
                    base);
            final String id = readEventId(contract, "id", base, findings);
            readContractVersion(contract, "version", base, findings);
            final JsonNode artifact = contract.get("artifact");
            final String artifactPath = artifact != null
                            && artifact.isTextual()
                            && DescriptorPredicates.isContractArtifact(artifact.asText())
                    ? artifact.asText()
                    : null;
            if (artifactPath == null) {
                findings.add(
                        "PLUGIN_META_BAD_EVENT_CONTRACT_ARTIFACT",
                        "artifact must be a JAR entry under META-INF/turboism/contracts/",
                        base + ".artifact");
            }
            readSha256(contract, "sha256", base, findings);
            if (id != null && !ids.add(id)) {
                findings.add("PLUGIN_META_DUPLICATE_EVENT_CONTRACT", "Event contract ids must be unique", base + ".id");
            }
            if (artifactPath != null && !artifacts.add(artifactPath)) {
                findings.add(
                        "PLUGIN_META_DUPLICATE_EVENT_CONTRACT",
                        "Event contract artifact paths must be unique",
                        base + ".artifact");
            }
            declared.add(new CoreEventContract(
                    orEmpty(id),
                    orEmpty(text(contract, "version")),
                    orEmpty(artifactPath),
                    orEmpty(text(contract, "sha256"))));
        }
    }

    static String readEventId(final JsonNode node, final String field, final String base, final Findings findings) {
        final JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || !DescriptorPredicates.isEventId(value.asText())) {
            findings.add(BAD_EVENT_ID_CODE, field + " must be a stable lowercase event id", base + "." + field);
            return null;
        }
        return value.asText();
    }

    static String readEventType(final JsonNode node, final String field, final String base, final Findings findings) {
        final JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || !DescriptorPredicates.isJavaBinaryName(value.asText())) {
            findings.add(BAD_EVENT_TYPE_CODE, field + " must be a Java binary name", base + "." + field);
            return null;
        }
        return value.asText();
    }

    static void readContractVersion(
            final JsonNode node, final String field, final String base, final Findings findings) {
        final JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || !DescriptorPredicates.isValidVersion(value.asText())) {
            findings.add(BAD_CONTRACT_VERSION_CODE, field + " must be MAJOR.MINOR.PATCH", base + "." + field);
        }
    }

    static void readContractVersionRange(
            final JsonNode node, final String field, final String base, final Findings findings) {
        final JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            findings.add(BAD_CONTRACT_VERSION_CODE, field + " must be a version range", base + "." + field);
            return;
        }
        DescriptorPredicates.versionRangeFailure(value.asText())
                .ifPresent(failure -> findings.add(BAD_CONTRACT_VERSION_CODE, failure, base + "." + field));
    }

    static void readSha256(final JsonNode node, final String field, final String base, final Findings findings) {
        final JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || !DescriptorPredicates.isSha256(value.asText())) {
            findings.add(BAD_EVENT_ABI_CODE, field + " must be a lowercase SHA-256 digest", base + "." + field);
        }
    }

    private static String text(final JsonNode root, final String field) {
        return root.has(field) ? root.get(field).asText("") : "";
    }

    private static List<String> texts(final JsonNode values) {
        final List<String> result = new ArrayList<>();
        values.forEach(value -> result.add(value.asText()));
        return result;
    }

    private static String orEmpty(final String value) {
        return value == null ? "" : value;
    }
}
