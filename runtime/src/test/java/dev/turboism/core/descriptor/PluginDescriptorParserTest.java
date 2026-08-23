package dev.turboism.core.descriptor;

import dev.turboism.sdk.plugin.PluginDescriptor;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

class PluginDescriptorParserTest {

    private final PluginDescriptorParser parser = new PluginDescriptorParser();

    private InputStream toStream(final String json) {
        return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void parsesValidDescriptorWithMultipleEntrypointsAndResources()
        throws DescriptorParseException {
        final String json = """
            {
              "format": "turboism.plugin.meta",
              "schemaVersion": 2,
              "id": "dev.turboism.plugin.demo",
              "name": "Demo Plugin",
              "version": "0.1.0",
              "entrypoints": [
                "dev.turboism.plugin.demo.DemoPlugin",
                "dev.turboism.plugin.demo.ModelHooks"
              ],
              "turboismApi": "[0.1.0,0.2.0)",
              "authors": [{ "name": "Turboism Contributors" }],
              "website": "https://turboism.dev",
              "resources": ["icons/", "themes/"],
              "i18n": {
                "baseName": "META-INF/turboism/i18n/messages",
                "locales": ["base", "en", "zh_Hans"]
              }
            }
            """;

        final PluginDescriptor descriptor = parser.parse(toStream(json));

        assertEquals("dev.turboism.plugin.demo", descriptor.id());
        assertEquals("0.1.0", descriptor.version());
        assertEquals(List.of(
            "dev.turboism.plugin.demo.DemoPlugin",
            "dev.turboism.plugin.demo.ModelHooks"
        ), descriptor.entrypoints());
        assertEquals(List.of("icons/", "themes/"), descriptor.resources());
        assertEquals("META-INF/turboism/i18n/messages", descriptor.i18n().baseName());
        assertEquals(List.of("base", "en", "zh_Hans"), descriptor.i18n().locales());
        assertEquals("https://turboism.dev", descriptor.website().orElseThrow());
    }

    @Test
    void rejectsSchemaVersionOne() {
        final String json = validDescriptor().replace(
            "\"schemaVersion\": 2",
            "\"schemaVersion\": 1"
        );
        assertThrows(DescriptorParseException.class, () -> parser.parse(toStream(json)));
    }

    @Test
    void rejectsLegacyEntrypointObject() {
        final String json = validDescriptor().replace(
            "[\"dev.turboism.plugin.demo.DemoPlugin\"]",
            "{\"plugin\":\"dev.turboism.plugin.demo.DemoPlugin\"}"
        );
        assertThrows(DescriptorParseException.class, () -> parser.parse(toStream(json)));
    }

    @Test
    void rejectsEmptyEntrypoints() {
        final String json = validDescriptor().replace(
            "[\"dev.turboism.plugin.demo.DemoPlugin\"]",
            "[]"
        );
        assertThrows(DescriptorParseException.class, () -> parser.parse(toStream(json)));
    }

    @Test
    void rejectsUndeclaredLegacyHomepage() {
        final String json = validDescriptor().replace(
            "\"website\": \"https://turboism.dev\"",
            "\"homepage\": \"https://turboism.dev\""
        );
        assertThrows(DescriptorParseException.class, () -> parser.parse(toStream(json)));
    }

    @Test
    void parsesV3DescriptorWithCategoryAndTags() throws DescriptorParseException {
        final String json = v3Descriptor(
            "\"category\": \"modeling\",\n              \"tags\": [\"parameter\", \"batch-edit\"],"
        );

        final PluginDescriptor descriptor = parser.parse(toStream(json));

        assertEquals("modeling", descriptor.category().orElseThrow());
        assertEquals(List.of("parameter", "batch-edit"), descriptor.tags());
        assertEquals("dev.turboism.plugin.demo", descriptor.id());
        assertEquals("[0.1.0,0.2.0)", descriptor.turboismApi());
    }

    @Test
    void parsesV3DescriptorWithOmittedTagsAsEmptyImmutableList() throws DescriptorParseException {
        final String json = v3Descriptor("\"category\": \"modeling\",");

        final PluginDescriptor descriptor = parser.parse(toStream(json));

        assertEquals("modeling", descriptor.category().orElseThrow());
        assertEquals(List.of(), descriptor.tags());
        try {
            descriptor.tags().add("mutated");
            fail("tags must be immutable");
        } catch (UnsupportedOperationException expected) {
            // tags are a defensive immutable copy
        }
    }

    @Test
    void parsesV3DescriptorWithExplicitEmptyTags() throws DescriptorParseException {
        final String json = v3Descriptor("\"category\": \"modeling\",\n              \"tags\": [],");

        final PluginDescriptor descriptor = parser.parse(toStream(json));

        assertEquals("modeling", descriptor.category().orElseThrow());
        assertEquals(List.of(), descriptor.tags());
    }

    @Test
    void parsesV2DescriptorWithEmptyClassification() throws DescriptorParseException {
        final PluginDescriptor descriptor = parser.parse(toStream(validDescriptor()));

        assertEquals(Optional.empty(), descriptor.category());
        assertEquals(List.of(), descriptor.tags());
    }

    @Test
    void rejectsV3DescriptorWithoutCategory() {
        final String json = v3Descriptor("");

        final DescriptorParseException failure = assertThrows(
            DescriptorParseException.class, () -> parser.parse(toStream(json))
        );
        assertEquals("PLUGIN_META_MISSING", failure.code());
    }

    @Test
    void rejectsV3DescriptorWithDuplicateTags() {
        final String json = v3Descriptor(
            "\"category\": \"modeling\",\n              \"tags\": [\"parameter\", \"parameter\"],"
        );

        final DescriptorParseException failure = assertThrows(
            DescriptorParseException.class, () -> parser.parse(toStream(json))
        );
        assertEquals("PLUGIN_META_BAD_TAGS", failure.code());
    }

    @Test
    void rejectsV3DescriptorWithMalformedCategory() {
        final String json = v3Descriptor("\"category\": \"Modeling\",");

        final DescriptorParseException failure = assertThrows(
            DescriptorParseException.class, () -> parser.parse(toStream(json))
        );
        assertEquals("PLUGIN_META_BAD_CATEGORY", failure.code());
    }

    @Test
    void rejectsV3DescriptorWithMoreThanTwelveTags() {
        final String json = v3Descriptor(
            "\"category\": \"modeling\",\n              \"tags\": ["
                + "\"a-1\",\"a-2\",\"a-3\",\"a-4\",\"a-5\",\"a-6\","
                + "\"a-7\",\"a-8\",\"a-9\",\"a-10\",\"a-11\",\"a-12\",\"a-13\"],"
        );

        final DescriptorParseException failure = assertThrows(
            DescriptorParseException.class, () -> parser.parse(toStream(json))
        );
        assertEquals("PLUGIN_META_BAD_TAGS", failure.code());
    }

    @Test
    void rejectsV2DescriptorCarryingV3FieldsAsUnknown() {
        final String json = validDescriptor().replace(
            "\"i18n\": {",
            "\"category\": \"modeling\",\n              \"tags\": [\"parameter\"],\n              \"i18n\": {"
        );

        final DescriptorParseException failure = assertThrows(
            DescriptorParseException.class, () -> parser.parse(toStream(json))
        );
        assertEquals("PLUGIN_META_UNKNOWN_FIELD", failure.code());
    }

    @Test
    void rejectsV3DescriptorWithWrongTypeCategory() {
        final String json = v3Descriptor("\"category\": 42,");

        final DescriptorParseException failure = assertThrows(
            DescriptorParseException.class, () -> parser.parse(toStream(json))
        );
        assertEquals("PLUGIN_META_BAD_CATEGORY", failure.code());
    }

    @Test
    void rejectsV3DescriptorWithNullCategoryAsMissing() {
        final String json = v3Descriptor("\"category\": null,");

        final DescriptorParseException failure = assertThrows(
            DescriptorParseException.class, () -> parser.parse(toStream(json))
        );
        assertEquals("PLUGIN_META_MISSING", failure.code());
    }

    @Test
    void rejectsV3DescriptorWithNonArrayTags() {
        final String json = v3Descriptor("\"category\": \"modeling\",\n              \"tags\": \"parameter\",");

        final DescriptorParseException failure = assertThrows(
            DescriptorParseException.class, () -> parser.parse(toStream(json))
        );
        assertEquals("PLUGIN_META_BAD_TAGS", failure.code());
    }

    @Test
    void parsesV4PublicEventContracts() throws DescriptorParseException {
        final String digest = "a".repeat(64);
        final PluginDescriptor descriptor = parser.parse(toStream(v4Descriptor(
            """
              "eventExports": [{
                "id": "demo.started",
                "contractVersion": "1.0.0",
                "eventType": "dev.turboism.sdk.event.demo.DemoStartedEvent",
                "abiSha256": "%s"
              }],
              "eventImports": [{
                "provider": "dev.turboism.plugin.provider",
                "eventId": "provider.ready",
                "contractVersion": "[1.0.0,2.0.0)",
                "eventType": "dev.turboism.sdk.event.provider.ProviderReadyEvent",
                "abiSha256": "%s",
                "required": false
              }],
            """.formatted(digest, digest)
        )));

        assertEquals(1, descriptor.eventExports().size());
        assertEquals("demo.started", descriptor.eventExports().get(0).id());
        assertEquals("1.0.0", descriptor.eventExports().get(0).contractVersion());
        assertEquals(1, descriptor.eventImports().size());
        assertEquals(
            "dev.turboism.plugin.provider",
            descriptor.eventImports().get(0).providerId()
        );
        assertEquals("[1.0.0,2.0.0)", descriptor.eventImports().get(0).contractVersion());
        assertEquals(false, descriptor.eventImports().get(0).required());
    }

    @Test
    void rejectsV4DuplicateEventExportIds() {
        final String digest = "a".repeat(64);
        final DescriptorParseException failure = assertThrows(
            DescriptorParseException.class,
            () -> parser.parse(toStream(v4Descriptor(
                """
                  "eventExports": [
                    {
                      "id": "demo.started",
                      "contractVersion": "1.0.0",
                      "eventType": "dev.turboism.sdk.event.demo.DemoStartedEvent",
                      "abiSha256": "%s"
                    },
                    {
                      "id": "demo.started",
                      "contractVersion": "1.1.0",
                      "eventType": "dev.turboism.sdk.event.demo.DemoStartedAgainEvent",
                      "abiSha256": "%s"
                    }
                  ],
                """.formatted(digest, digest)
            )))
        );
        assertEquals("PLUGIN_META_DUPLICATE_EVENT_EXPORT", failure.code());
    }

    @Test
    void rejectsV4MalformedEventAbi() {
        final DescriptorParseException failure = assertThrows(
            DescriptorParseException.class,
            () -> parser.parse(toStream(v4Descriptor(
                """
                  "eventExports": [{
                    "id": "demo.started",
                    "contractVersion": "1.0.0",
                    "eventType": "dev.turboism.sdk.event.demo.DemoStartedEvent",
                    "abiSha256": "not-a-digest"
                  }],
                """
            )))
        );
        assertEquals("PLUGIN_META_BAD_EVENT_ABI", failure.code());
    }

    @Test
    void legacyDescriptorImplementationInheritsEmptyClassification() {
        // A descriptor implementation compiled against the old method set must
        // remain loadable: the default methods supply empty classification.
        final PluginDescriptor legacy = new PluginDescriptor() {
            @Override public String id() { return "legacy.plugin"; }
            @Override public String name() { return "Legacy"; }
            @Override public String version() { return "1.0.0"; }
            @Override public String description() { return ""; }
            @Override public List<String> entrypoints() { return List.of("legacy.Plugin"); }
            @Override public String turboismApi() { return "[0.1.0,0.2.0)"; }
            @Override public List<Author> authors() { return List.of(); }
            @Override public String license() { return ""; }
            @Override public Optional<String> website() { return Optional.empty(); }
            @Override public List<String> resources() { return List.of(); }
            @Override public I18n i18n() {
                return new I18n() {
                    @Override public String baseName() { return "META-INF/turboism/i18n/messages"; }
                    @Override public List<String> locales() { return List.of(); }
                };
            }
            @Override public List<DependencyRef> dependencies() { return List.of(); }
            @Override public List<PermissionRef> permissions() { return List.of(); }
            @Override public List<String> capabilities() { return List.of(); }
            @Override public Environment environment() {
                return new Environment() {
                    @Override public boolean requiresCubism() { return false; }
                    @Override public String ui() { return "none"; }
                };
            }
        };

        assertEquals(Optional.empty(), legacy.category());
        assertEquals(List.of(), legacy.tags());
        assertEquals(List.of(), legacy.eventExports());
        assertEquals(List.of(), legacy.eventImports());
    }

    private static String v4Descriptor(final String eventFields) {
        return """
            {
              "format": "turboism.plugin.meta",
              "schemaVersion": 4,
              "id": "dev.turboism.plugin.demo",
              "name": "Demo Plugin",
              "version": "0.1.0",
              "entrypoints": ["dev.turboism.plugin.demo.DemoPlugin"],
              "turboismApi": "[0.1.0,0.2.0)",
              "authors": [{ "name": "Turboism Contributors" }],
              "website": "https://turboism.dev",
              "resources": [],
              "i18n": {
                "baseName": "META-INF/turboism/i18n/messages",
                "locales": []
              },
              "category": "modeling",
              "tags": [],
              %s
              "description": ""
            }
            """.formatted(eventFields);
    }

    private static String v3Descriptor(final String classificationFields) {
        return """
            {
              "format": "turboism.plugin.meta",
              "schemaVersion": 3,
              "id": "dev.turboism.plugin.demo",
              "name": "Demo Plugin",
              "version": "0.1.0",
              "entrypoints": ["dev.turboism.plugin.demo.DemoPlugin"],
              "turboismApi": "[0.1.0,0.2.0)",
              "authors": [{ "name": "Turboism Contributors" }],
              "website": "https://turboism.dev",
              "resources": [],
              "i18n": {
                "baseName": "META-INF/turboism/i18n/messages",
                "locales": []
              },
              %s
              "description": ""
            }
            """.formatted(classificationFields);
    }

    private static String validDescriptor() {
        return """
            {
              "format": "turboism.plugin.meta",
              "schemaVersion": 2,
              "id": "dev.turboism.plugin.demo",
              "name": "Demo Plugin",
              "version": "0.1.0",
              "entrypoints": ["dev.turboism.plugin.demo.DemoPlugin"],
              "turboismApi": "[0.1.0,0.2.0)",
              "authors": [{ "name": "Turboism Contributors" }],
              "website": "https://turboism.dev",
              "resources": [],
              "i18n": {
                "baseName": "META-INF/turboism/i18n/messages",
                "locales": []
              }
            }
            """;
    }
}
