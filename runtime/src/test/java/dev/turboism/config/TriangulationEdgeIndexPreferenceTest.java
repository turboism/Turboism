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
        new RuntimeConfigRepository(home, ignored -> {}).update(root -> {
            root.put("meshTriangulationEdgeIndex", false);
            return root;
        });
        assertFalse(TriangulationEdgeIndexPreference.read(home));
        new RuntimeConfigRepository(home, ignored -> {}).update(root -> {
            root.put("meshTriangulationEdgeIndex", true);
            return root;
        });
        assertTrue(TriangulationEdgeIndexPreference.read(home));
    }

    @Test
    void invalidConfigurationDisablesTheOptimization() throws Exception {
        Files.writeString(home.resolve("config.json"), "{not json");
        assertFalse(TriangulationEdgeIndexPreference.read(home));
        Files.writeString(home.resolve("config.json"), "{\"meshTriangulationEdgeIndex\": \"off\"}");
        assertFalse(TriangulationEdgeIndexPreference.read(home));
    }
}
