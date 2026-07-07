package dev.turboism.core.descriptor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.turboism.sdk.plugin.PluginDescriptor;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 * Parses plugin.json into the SDK {@link PluginDescriptor} contract.
 */
public final class PluginDescriptorParser {

    private final ObjectMapper mapper = new ObjectMapper();

    public PluginDescriptor parse(InputStream source) throws DescriptorParseException {
        try {
            JsonNode root = mapper.readTree(source);
            return parseNode(root);
        } catch (IOException e) {
            throw new DescriptorParseException("PLUGIN_META_INVALID_JSON", "Failed to parse plugin.json", e);
        }
    }

    public PluginDescriptor parse(String json) throws DescriptorParseException {
        try {
            JsonNode root = mapper.readTree(json);
            return parseNode(root);
        } catch (IOException e) {
            throw new DescriptorParseException("PLUGIN_META_INVALID_JSON", "Failed to parse plugin.json", e);
        }
    }

    private PluginDescriptor parseNode(JsonNode root) throws DescriptorParseException {
        requireField(root, "format");
        requireField(root, "schemaVersion");
        requireField(root, "id");
        requireField(root, "name");
        requireField(root, "version");
        requireField(root, "entrypoints");
        requireField(root, "turboismApi");

        String format = root.get("format").asText();
        if (!"turboism.plugin.meta".equals(format)) {
            throw new DescriptorParseException("PLUGIN_META_BAD_FORMAT", "format must be turboism.plugin.meta", "format");
        }

        int schemaVersion = root.get("schemaVersion").asInt();
        if (schemaVersion != 1) {
            throw new DescriptorParseException("PLUGIN_META_BAD_SCHEMA_VERSION", "schemaVersion must be 1", "schemaVersion");
        }

        JsonNode entrypoints = root.get("entrypoints");
        if (!entrypoints.has("plugin")) {
            throw new DescriptorParseException("PLUGIN_META_MISSING_ENTRYPOINT", "entrypoints.plugin is required", "entrypoints.plugin");
        }

        Map<String, String> entrypointMap = new LinkedHashMap<>();
        entrypoints.fields().forEachRemaining(e -> entrypointMap.put(e.getKey(), e.getValue().asText()));

        return new CorePluginDescriptor(
            root.get("id").asText(),
            root.get("name").asText(),
            root.get("version").asText(),
            textOrEmpty(root, "description"),
            entrypointMap,
            root.get("turboismApi").asText(),
            parseAuthors(root),
            textOrEmpty(root, "license"),
            optionalText(root, "homepage"),
            parseDependencies(root),
            parsePermissions(root),
            listOrEmpty(root, "capabilities"),
            parseEnvironment(root)
        );
    }

    private void requireField(JsonNode node, String field) throws DescriptorParseException {
        if (!node.has(field) || node.get(field).isNull()) {
            throw new DescriptorParseException("PLUGIN_META_MISSING", "Missing required field: " + field, field);
        }
    }

    private String textOrEmpty(JsonNode node, String field) {
        return node.has(field) ? node.get(field).asText("") : "";
    }

    private Optional<String> optionalText(JsonNode node, String field) {
        return node.has(field) && !node.get(field).isNull()
            ? Optional.of(node.get(field).asText())
            : Optional.empty();
    }

    private List<String> listOrEmpty(JsonNode node, String field) {
        if (!node.has(field)) return List.of();
        List<String> result = new ArrayList<>();
        node.get(field).forEach(e -> result.add(e.asText()));
        return result;
    }

    private List<PluginDescriptor.Author> parseAuthors(JsonNode root) {
        if (!root.has("authors")) return List.of();
        List<PluginDescriptor.Author> result = new ArrayList<>();
        root.get("authors").forEach(a -> {
            String name = a.has("name") ? a.get("name").asText() : "";
            Optional<String> email = a.has("email") ? Optional.of(a.get("email").asText()) : Optional.empty();
            result.add(new CorePluginDescriptor.CoreAuthor(name, email));
        });
        return result;
    }

    private List<PluginDescriptor.DependencyRef> parseDependencies(JsonNode root) {
        if (!root.has("dependencies")) return List.of();
        List<PluginDescriptor.DependencyRef> result = new ArrayList<>();
        root.get("dependencies").forEach(d -> {
            String id = d.has("id") ? d.get("id").asText() : "";
            String type = d.has("type") ? d.get("type").asText("required") : "required";
            String version = d.has("version") ? d.get("version").asText() : "";
            String ordering = d.has("ordering") ? d.get("ordering").asText("none") : "none";
            Optional<String> reason = d.has("reason") ? Optional.of(d.get("reason").asText()) : Optional.empty();
            result.add(new CorePluginDescriptor.CoreDependencyRef(id, type, version, ordering, reason));
        });
        return result;
    }

    private List<PluginDescriptor.PermissionRef> parsePermissions(JsonNode root) {
        if (!root.has("permissions")) return List.of();
        List<PluginDescriptor.PermissionRef> result = new ArrayList<>();
        root.get("permissions").forEach(p -> {
            String id = p.has("id") ? p.get("id").asText() : "";
            String scope = p.has("scope") ? p.get("scope").asText("application") : "application";
            Optional<String> reason = p.has("reason") ? Optional.of(p.get("reason").asText()) : Optional.empty();
            result.add(new CorePluginDescriptor.CorePermissionRef(id, scope, reason));
        });
        return result;
    }

    private PluginDescriptor.Environment parseEnvironment(JsonNode root) {
        boolean requiresCubism = false;
        String ui = "none";
        if (root.has("environment")) {
            JsonNode env = root.get("environment");
            if (env.has("requiresCubism")) {
                requiresCubism = env.get("requiresCubism").asBoolean(false);
            }
            if (env.has("ui")) {
                ui = env.get("ui").asText("none");
            }
        }
        return new CorePluginDescriptor.CoreEnvironment(requiresCubism, ui);
    }
}
