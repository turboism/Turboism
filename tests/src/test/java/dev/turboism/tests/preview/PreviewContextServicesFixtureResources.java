package dev.turboism.tests.preview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reads fixture source resources and creates its fixed plugin descriptor. */
final class PreviewContextServicesFixtureResources {

    private static final String ENTRYPOINT =
        "dev.example.previewcontextservices.PreviewContextServicesPlugin";

    private PreviewContextServicesFixtureResources() {
    }

    static String source(final String markerDirectoryProperty) throws IOException {
        return read("PreviewContextServicesPlugin.java")
            .replace("__MARKER_DIRECTORY_PROPERTY__", markerDirectoryProperty);
    }

    static byte[] descriptor() throws IOException {
        final ObjectMapper mapper = new ObjectMapper();
        final ObjectNode descriptor = mapper.createObjectNode();
        descriptor.put("format", "turboism.plugin.meta");
        descriptor.put("schemaVersion", 2);
        descriptor.put("id", PreviewContextServicesPluginJarFixture.PLUGIN_ID);
        descriptor.put("name", "Preview Context Services Fixture");
        descriptor.put("version", "0.1.0");
        descriptor.put("description", "Characterizes preview PluginContext service wiring.");
        descriptor.putArray("entrypoints").add(ENTRYPOINT);
        descriptor.put("turboismApi", "[0.1.0,0.2.0)");
        descriptor.putArray("authors").addObject().put("name", "Turboism Tests");
        descriptor.put("license", "Test License");
        descriptor.put("website", "https://turboism.dev/tests");
        descriptor.putArray("resources");
        descriptor.putObject("i18n")
            .put("baseName", "META-INF/turboism/i18n/messages")
            .putArray("locales");
        descriptor.putArray("dependencies");
        permissions(descriptor.putArray("permissions"));
        descriptor.putArray("capabilities");
        descriptor.putObject("environment").put("requiresCubism", false).put("ui", "none");
        return mapper.writeValueAsBytes(descriptor);
    }

    static Map<String, String> expectedMarkerValues() {
        final Map<String, String> values = new LinkedHashMap<>();
        values.put("localization", "⟦fixture.missing⟧");
        values.put("task.status", "ACCEPTED");
        values.put("task.accepted", "true");
        values.put("task.outcome", "SUCCEEDED");
        values.put("storage.written", "true");
        values.put("storage.value", "characterization-value");
        values.put("storage.truncated", "false");
        values.put("storage.error", "empty");
        values.put("config.default.source", "DEFAULT_MISSING");
        values.put("config.default.revision", "0");
        values.put("config.default.value", "true");
        values.put("config.write.written", "true");
        values.put("config.write.revision", "1");
        values.put("config.write.error", "empty");
        values.put("config.stored.source", "STORED");
        values.put("config.stored.revision", "1");
        values.put("config.stored.value", "false");
        values.put("userFiles.status", "UNAVAILABLE");
        values.put("userFiles.error", "RUNTIME_UNAVAILABLE");
        values.put("userFiles.handle", "false");
        values.put("hostReads.status", "REJECTED");
        values.put("hostReads.error", "PERMISSION_DENIED");
        values.put("hostReads.handle", "false");
        return Map.copyOf(values);
    }

    private static void permissions(final ArrayNode permissions) {
        for (String id : new String[]{
            "turboism.file.read", "turboism.file.write", "turboism.config.plugin.read",
            "turboism.config.plugin.write", "turboism.ui.file-chooser.request"
        }) {
            permissions.addObject().put("id", id).put("scope", "application")
                .put("reason", "Characterizes preview context service access.");
        }
    }

    private static String read(final String name) throws IOException {
        final String resource = "/dev/turboism/tests/preview/" + name;
        try (InputStream input = PreviewContextServicesFixtureResources.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Context services fixture resource is missing: " + resource);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
