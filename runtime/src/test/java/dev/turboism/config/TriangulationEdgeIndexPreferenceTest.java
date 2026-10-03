package dev.turboism.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TriangulationEdgeIndexPreferenceTest {

    @TempDir
    Path home;

    @Test
    void defaultsToEnabledWhenNothingIsPersisted() {
        assertTrue(TriangulationEdgeIndexPreference.read(home));
        assertTrue(TriangulationEdgeIndexPreference.read(null));
    }

    @Test
    void honorsAnExplicitDisableAndEnable() throws Exception {
        Files.writeString(home.resolve("config.json"), "{\"meshTriangulationEdgeIndex\": false}");
        assertFalse(TriangulationEdgeIndexPreference.read(home));
        Files.writeString(home.resolve("config.json"), "{\"meshTriangulationEdgeIndex\": true}");
        assertTrue(TriangulationEdgeIndexPreference.read(home));
    }

    @Test
    void unreadableOrWrongTypedValuesKeepTheDefault() throws Exception {
        Files.writeString(home.resolve("config.json"), "{not json");
        assertTrue(TriangulationEdgeIndexPreference.read(home));
        Files.writeString(home.resolve("config.json"), "{\"meshTriangulationEdgeIndex\": \"off\"}");
        assertTrue(TriangulationEdgeIndexPreference.read(home));
    }
}
