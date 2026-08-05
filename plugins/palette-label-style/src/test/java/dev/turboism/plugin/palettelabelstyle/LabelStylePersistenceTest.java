package dev.turboism.plugin.palettelabelstyle;

import dev.turboism.sdk.config.PluginConfigRegistry;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.context.ContextMenuRegistry.Location;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LabelStylePersistenceTest {

    @Test
    void scopePathMapsBlankProjectIdsToDefault() throws Exception {
        assertEquals("palette-label-style/colors-default.properties", LabelStylePersistence.scopePath(null));
        assertEquals("palette-label-style/colors-default.properties", LabelStylePersistence.scopePath(""));
        assertEquals("palette-label-style/colors-default.properties", LabelStylePersistence.scopePath("  "));
        assertEquals("palette-label-style/colors-project-1.properties", LabelStylePersistence.scopePath("project-1"));
        assertEquals("default", LabelStylePersistence.safeProjectId(null));
    }

    @Test
    void keyUsesPaletteObjectAndProperty() throws Exception {
        assertEquals(
            "PARAMETER_TAB:ParamAngleX:text",
            LabelStylePersistence.key(Location.PARAMETER_TAB, "ParamAngleX", LabelStylePersistence.PROPERTY_TEXT)
        );
        assertEquals(
            "DEFORMER_TAB:Deformer_1:background",
            LabelStylePersistence.key(Location.DEFORMER_TAB, "Deformer_1", LabelStylePersistence.PROPERTY_BACKGROUND)
        );
    }

    @Test
    void parseKeySplitsStoredKeys() throws Exception {
        final LabelStylePersistence.StoredEntry entry = LabelStylePersistence
            .parseKey("PART_TAB:Part_1:text").orElseThrow();
        assertEquals(Location.PART_TAB, entry.palette());
        assertEquals("Part_1", entry.objectId());
        assertEquals("text", entry.property());
    }

    @Test
    void parseKeyKeepsColonsInsideObjectIds() throws Exception {
        final LabelStylePersistence.StoredEntry entry = LabelStylePersistence
            .parseKey("PARAMETER_TAB:group:with:colon:text").orElseThrow();
        assertEquals("group:with:colon", entry.objectId());
        assertEquals("text", entry.property());
    }

    @Test
    void parseKeyRejectsMalformedKeys() throws Exception {
        assertTrue(LabelStylePersistence.parseKey(null).isEmpty());
        assertTrue(LabelStylePersistence.parseKey("").isEmpty());
        assertTrue(LabelStylePersistence.parseKey("PARAMETER_TAB").isEmpty());
        assertTrue(LabelStylePersistence.parseKey(":obj:text").isEmpty());
        assertTrue(LabelStylePersistence.parseKey("UNKNOWN_TAB:obj:text").isEmpty());
        assertTrue(LabelStylePersistence.parseKey("PARAMETER_TAB::text").isEmpty());
        assertTrue(LabelStylePersistence.parseKey("PARAMETER_TAB:obj:font").isEmpty());
        assertTrue(LabelStylePersistence.parseKey("index").isEmpty());
    }

    @Test
    void writePersistsEntryAndReadAllReturnsIt() throws Exception {
        final RecordingPluginConfigRegistry config = new RecordingPluginConfigRegistry();
        LabelStylePersistence.write(config, "project-1", Location.PARAMETER_TAB, "ParamAngleX", "text", "#E53935");
        LabelStylePersistence.write(config, "project-1", Location.PARAMETER_TAB, "ParamAngleX", "background", "#2196F3");

        assertEquals(
            Map.of(
                "PARAMETER_TAB:ParamAngleX:text", "#E53935",
                "PARAMETER_TAB:ParamAngleX:background", "#2196F3"
            ),
            LabelStylePersistence.readAll(config, "project-1")
        );
        assertTrue(LabelStylePersistence.readAll(config, "other-project").isEmpty());
    }

    @Test
    void clearRemovesEntryFromReplay() throws Exception {
        final RecordingPluginConfigRegistry config = new RecordingPluginConfigRegistry();
        LabelStylePersistence.write(config, "project-1", Location.PART_TAB, "Part_1", "text", "#4CAF50");
        LabelStylePersistence.clear(config, "project-1", Location.PART_TAB, "Part_1", "text");

        assertTrue(LabelStylePersistence.readAll(config, "project-1").isEmpty());
        // The tombstone value is still observable by direct read but never replayed.
        assertEquals(Optional.of(""), config.readString(
            LabelStylePersistence.scopePath("project-1"), "PART_TAB:Part_1:text"));
    }

    @Test
    void rewriteAfterClearReappears() throws Exception {
        final RecordingPluginConfigRegistry config = new RecordingPluginConfigRegistry();
        LabelStylePersistence.write(config, "p", Location.PARAMETER_TAB, "Angle", "text", "#FF9800");
        LabelStylePersistence.clear(config, "p", Location.PARAMETER_TAB, "Angle", "text");
        LabelStylePersistence.write(config, "p", Location.PARAMETER_TAB, "Angle", "text", "#9C27B0");

        assertEquals(
            Map.of("PARAMETER_TAB:Angle:text", "#9C27B0"),
            LabelStylePersistence.readAll(config, "p")
        );
    }

    @Test
    void projectsAreIsolatedByScopePath() throws Exception {
        final RecordingPluginConfigRegistry config = new RecordingPluginConfigRegistry();
        LabelStylePersistence.write(config, "project-a", Location.DEFORMER_TAB, "Deformer_1", "text", "#E53935");
        LabelStylePersistence.write(config, "project-b", Location.DEFORMER_TAB, "Deformer_1", "text", "#2196F3");

        assertEquals(
            Map.of("DEFORMER_TAB:Deformer_1:text", "#E53935"),
            LabelStylePersistence.readAll(config, "project-a")
        );
        assertEquals(
            Map.of("DEFORMER_TAB:Deformer_1:text", "#2196F3"),
            LabelStylePersistence.readAll(config, "project-b")
        );
    }

    @Test
    void readAllSkipsInvalidHexValues() throws Exception {
        final RecordingPluginConfigRegistry config = new RecordingPluginConfigRegistry();
        config.rawWrite("palette-label-style/colors-p.properties",
            "PARAMETER_TAB:Angle:text", "#E53935");
        config.rawWrite("palette-label-style/colors-p.properties",
            "PARAMETER_TAB:Broken:text", "not-a-color");
        config.rawWrite("palette-label-style/colors-p.properties",
            "index", "PARAMETER_TAB:Angle:text,PARAMETER_TAB:Broken:text");

        assertEquals(
            Map.of("PARAMETER_TAB:Angle:text", "#E53935"),
            LabelStylePersistence.readAll(config, "p")
        );
    }

    @Test
    void readAllReturnsEmptyWhenIndexIsAbsent() throws Exception {
        assertTrue(LabelStylePersistence.readAll(new RecordingPluginConfigRegistry(), "p").isEmpty());
    }

    /** In-memory PluginConfigRegistry mirroring the runtime's scope enforcement. */
    private static final class RecordingPluginConfigRegistry implements PluginConfigRegistry {
        private final Map<String, Map<String, String>> scopes = new HashMap<>();

        @Override
        public Registration readScope(final String relativePath) {
            scopes.computeIfAbsent(relativePath, ignored -> new HashMap<>());
            return () -> scopes.remove(relativePath);
        }

        @Override
        public Registration writeScope(final String relativePath) {
            scopes.computeIfAbsent(relativePath, ignored -> new HashMap<>());
            return () -> scopes.remove(relativePath);
        }

        @Override
        public Optional<String> readString(final String relativePath, final String key) {
            return Optional.ofNullable(scope(relativePath).get(key));
        }

        @Override
        public void writeString(final String relativePath, final String key, final String value) {
            scope(relativePath).put(key, value);
        }

        /** Direct write bypassing scope registration, for replay fixtures. */
        void rawWrite(final String relativePath, final String key, final String value) {
            scopes.computeIfAbsent(relativePath, ignored -> new HashMap<>()).put(key, value);
        }

        private Map<String, String> scope(final String relativePath) {
            return scopes.computeIfAbsent(relativePath, ignored -> new HashMap<>());
        }

        List<String> scopes() {
            return List.copyOf(scopes.keySet());
        }
    }

}
