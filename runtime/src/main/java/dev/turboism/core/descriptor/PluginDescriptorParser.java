package dev.turboism.core.descriptor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.turboism.core.schema.SchemaValidationError;
import dev.turboism.core.schema.plugin.PluginMetaValidator;
import dev.turboism.sdk.plugin.PluginDescriptor;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Parses schema-version-2 through schema-version-4 plugin.json into the SDK descriptor contract. */
public final class PluginDescriptorParser {

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Parses and validates a manifest read from a stream. The stream is consumed but not closed.
     *
     * @param source stream containing the {@code plugin.json} bytes
     * @return the validated descriptor
     * @throws DescriptorParseException with code {@code PLUGIN_META_INVALID_JSON} if the bytes are
     *     not well-formed JSON, or with the first schema error's code if validation fails
     */
    public PluginDescriptor parse(final InputStream source) throws DescriptorParseException {
        try {
            return parseNode(mapper.readTree(source), "<input stream>");
        } catch (IOException exception) {
            throw new DescriptorParseException(
                "PLUGIN_META_INVALID_JSON",
                "Failed to parse plugin.json",
                exception
            );
        }
    }

    /**
     * Parses and validates a manifest held as a string.
     *
     * @param json the {@code plugin.json} document text
     * @return the validated descriptor
     * @throws DescriptorParseException with code {@code PLUGIN_META_INVALID_JSON} if the text is
     *     not well-formed JSON, or with the first schema error's code if validation fails
     */
    public PluginDescriptor parse(final String json) throws DescriptorParseException {
        try {
            return parseNode(mapper.readTree(json), "<input string>");
        } catch (IOException exception) {
            throw new DescriptorParseException(
                "PLUGIN_META_INVALID_JSON",
                "Failed to parse plugin.json",
                exception
            );
        }
    }

    /**
     * Validates an already-parsed manifest tree.
     *
     * <p>The schema is chosen from the document itself: version 3 adds classification and version 4
     * adds public event exports/imports. An absent version retains the version 2 compatibility
     * contract; any unsupported declared version fails schema validation.</p>
     *
     * @param root the manifest as a JSON tree
     * @param source label used in validation error paths, for diagnostics only
     * @return the validated descriptor
     * @throws DescriptorParseException carrying the code, message and path of the first schema
     *     violation found
     */
    public PluginDescriptor parse(final JsonNode root, final String source)
        throws DescriptorParseException {
        return parseNode(root, source);
    }

    private PluginDescriptor parseNode(final JsonNode root, final String source)
        throws DescriptorParseException {
        final int schemaVersion = root.path("schemaVersion").asInt(2);
        final boolean classified = schemaVersion >= 3;
        final PluginMetaValidator validator = PluginMetaValidator.forSchemaVersion(schemaVersion);
        final List<SchemaValidationError> errors = validator.validate(root, source);
        if (!errors.isEmpty()) {
            final SchemaValidationError first = errors.get(0);
            throw new DescriptorParseException(first.code(), first.message(), first.path());
        }
        return new CorePluginDescriptor(
            root.get("id").asText(),
            root.get("name").asText(),
            root.get("version").asText(),
            textOrEmpty(root, "description"),
            list(root.get("entrypoints")),
            root.get("turboismApi").asText(),
            parseAuthors(root),
            textOrEmpty(root, "license"),
            Optional.of(root.get("website").asText()),
            list(root.get("resources")),
            parseI18n(root.get("i18n")),
            parseDependencies(root),
            parsePermissions(root),
            listOrEmpty(root, "capabilities"),
            parseEnvironment(root),
            classified ? Optional.of(root.get("category").asText()) : Optional.empty(),
            classified ? listOrEmpty(root, "tags") : List.of(),
            schemaVersion == 4 ? parseEventExports(root) : List.of(),
            schemaVersion == 4 ? parseEventImports(root) : List.of()
        );
    }

    private static String textOrEmpty(final JsonNode node, final String field) {
        return node.has(field) ? node.get(field).asText("") : "";
    }

    private static List<String> listOrEmpty(final JsonNode node, final String field) {
        return node.has(field) ? list(node.get(field)) : List.of();
    }

    private static List<String> list(final JsonNode values) {
        final List<String> result = new ArrayList<>();
        values.forEach(value -> result.add(value.asText()));
        return List.copyOf(result);
    }

    private static List<PluginDescriptor.Author> parseAuthors(final JsonNode root) {
        final List<PluginDescriptor.Author> result = new ArrayList<>();
        root.get("authors").forEach(author -> result.add(new CorePluginDescriptor.CoreAuthor(
            author.get("name").asText(),
            author.has("email")
                ? Optional.of(author.get("email").asText())
                : Optional.empty()
        )));
        return List.copyOf(result);
    }

    private static PluginDescriptor.I18n parseI18n(final JsonNode i18n) {
        return new CorePluginDescriptor.CoreI18n(
            i18n.get("baseName").asText(),
            list(i18n.get("locales"))
        );
    }

    private static List<PluginDescriptor.DependencyRef> parseDependencies(final JsonNode root) {
        if (!root.has("dependencies")) {
            return List.of();
        }
        final List<PluginDescriptor.DependencyRef> result = new ArrayList<>();
        root.get("dependencies").forEach(dependency -> result.add(
            new CorePluginDescriptor.CoreDependencyRef(
                dependency.get("id").asText(),
                dependency.has("type") ? dependency.get("type").asText("required") : "required",
                dependency.get("version").asText(),
                dependency.has("ordering") ? dependency.get("ordering").asText("none") : "none",
                dependency.has("reason")
                    ? Optional.of(dependency.get("reason").asText())
                    : Optional.empty()
            )
        ));
        return List.copyOf(result);
    }

    private static List<PluginDescriptor.PermissionRef> parsePermissions(final JsonNode root) {
        if (!root.has("permissions")) {
            return List.of();
        }
        final List<PluginDescriptor.PermissionRef> result = new ArrayList<>();
        root.get("permissions").forEach(permission -> result.add(
            new CorePluginDescriptor.CorePermissionRef(
                permission.get("id").asText(),
                permission.has("scope") ? permission.get("scope").asText("application") : "application",
                permission.has("reason")
                    ? Optional.of(permission.get("reason").asText())
                    : Optional.empty()
            )
        ));
        return List.copyOf(result);
    }

    private static List<PluginDescriptor.EventExport> parseEventExports(final JsonNode root) {
        if (!root.has("eventExports")) {
            return List.of();
        }
        final List<PluginDescriptor.EventExport> result = new ArrayList<>();
        root.get("eventExports").forEach(exported -> result.add(
            new CorePluginDescriptor.CoreEventExport(
                exported.get("id").asText(),
                exported.get("contractVersion").asText(),
                exported.get("eventType").asText(),
                exported.get("abiSha256").asText()
            )
        ));
        return List.copyOf(result);
    }

    private static List<PluginDescriptor.EventImport> parseEventImports(final JsonNode root) {
        if (!root.has("eventImports")) {
            return List.of();
        }
        final List<PluginDescriptor.EventImport> result = new ArrayList<>();
        root.get("eventImports").forEach(imported -> result.add(
            new CorePluginDescriptor.CoreEventImport(
                imported.get("provider").asText(),
                imported.get("eventId").asText(),
                imported.get("contractVersion").asText(),
                imported.get("eventType").asText(),
                imported.get("abiSha256").asText(),
                !imported.has("required") || imported.get("required").asBoolean()
            )
        ));
        return List.copyOf(result);
    }

    private static PluginDescriptor.Environment parseEnvironment(final JsonNode root) {
        boolean requiresCubism = false;
        String ui = "none";
        if (root.has("environment")) {
            final JsonNode environment = root.get("environment");
            requiresCubism = environment.has("requiresCubism")
                && environment.get("requiresCubism").asBoolean();
            ui = environment.has("ui") ? environment.get("ui").asText() : "none";
        }
        return new CorePluginDescriptor.CoreEnvironment(requiresCubism, ui);
    }
}
