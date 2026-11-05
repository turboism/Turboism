package dev.turboism.config;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.config.optimization.Optimization;
import dev.turboism.config.optimization.OptimizationCatalog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativeOptimizationConfigTest {
    @TempDir
    Path home;

    /** Declared override property to its previous value; {@code null} means it was not set. */
    private final Map<String, String> properties = new LinkedHashMap<>();

    @BeforeEach
    void clearOverrides() {
        for (Optimization optimization : OptimizationCatalog.persisted()) {
            for (String property : optimization.overrideProperties()) {
                properties.put(property, System.getProperty(property));
                System.clearProperty(property);
            }
        }
    }

    @AfterEach
    void restoreOverrides() {
        properties.forEach((property, value) -> {
            if (value == null) System.clearProperty(property);
            else System.setProperty(property, value);
        });
        properties.clear();
    }

    @Test
    void absentConfigUsesDefaultsWithoutCreatingAFile() {
        var snapshot = NativeOptimizationConfig.load(home, ignored -> {});
        boolean proton = dev.turboism.runtime.env.ProtonEnvironment.underWineOrProton();
        for (Optimization optimization : OptimizationCatalog.persisted()) {
            var field = PerformanceSettingsConfig.Field.forKey(optimization.key());
            assertEquals(
                    Boolean.parseBoolean(field.defaultValue(proton)),
                    snapshot.choice(optimization).enabled());
            assertEquals("platform-default", snapshot.choice(optimization).source());
        }
        assertFalse(Files.exists(home.resolve("config.json")));
    }

    @Test
    void globalChoicesAreCapturedTogetherAndReReadOnNextLaunch() {
        var repository = new RuntimeConfigRepository(home, ignored -> {});
        repository.update(root -> {
            for (Optimization optimization : OptimizationCatalog.persisted())
                (optimization.access() == Optimization.Access.PERSISTED_LAUNCHER ? root.withObject("launcher") : root)
                        .put(optimization.key(), false);
            return root;
        });
        var firstLaunch = NativeOptimizationConfig.load(home, ignored -> {});
        repository.update(root -> {
            for (Optimization optimization : OptimizationCatalog.persisted())
                (optimization.access() == Optimization.Access.PERSISTED_LAUNCHER ? root.withObject("launcher") : root)
                        .put(optimization.key(), true);
            return root;
        });
        var nextLaunch = NativeOptimizationConfig.load(home, ignored -> {});
        for (Optimization optimization : OptimizationCatalog.persisted()) {
            assertFalse(firstLaunch.choice(optimization).enabled());
            assertTrue(nextLaunch.choice(optimization).enabled());
            assertEquals("global-config", nextLaunch.choice(optimization).source());
        }
    }

    @Test
    void diagnosticOverrideHasAnExplicitSourceAndDoesNotRewriteTheFile() throws Exception {
        new RuntimeConfigRepository(home, ignored -> {}).update(root -> {
            root.withObject("launcher").put("incrementalUpdate", false);
            return root;
        });
        String saved = Files.readString(home.resolve("config.json"));
        System.setProperty(OptimizationCatalog.INCREMENTAL_UPDATE.property(), "true");
        var launch = NativeOptimizationConfig.load(home, ignored -> {});
        assertTrue(launch.choice(OptimizationCatalog.INCREMENTAL_UPDATE).enabled());
        assertEquals(
                "jvm-override",
                launch.choice(OptimizationCatalog.INCREMENTAL_UPDATE).source());
        System.setProperty(OptimizationCatalog.INCREMENTAL_UPDATE.property(), "false");
        assertTrue(launch.choice(OptimizationCatalog.INCREMENTAL_UPDATE).enabled(), "snapshot is immutable");
        assertEquals(saved, Files.readString(home.resolve("config.json")));
    }

    @Test
    void invalidGlobalConfigDisablesEveryOptimizationEvenWithOverrides() throws Exception {
        Files.writeString(home.resolve("config.json"), "{broken");
        for (Optimization optimization : OptimizationCatalog.persisted())
            System.setProperty(optimization.property(), "true");
        var snapshot = NativeOptimizationConfig.load(home, ignored -> {});
        for (Optimization optimization : OptimizationCatalog.persisted()) {
            assertFalse(snapshot.choice(optimization).enabled());
            assertEquals("invalid-config", snapshot.choice(optimization).source());
        }
    }

    @Test
    void diagnosticOnlyEntriesHaveNoPersistedPreferenceToResolve() {
        var snapshot = NativeOptimizationConfig.load(home, ignored -> {});
        assertThrows(IllegalArgumentException.class, () -> snapshot.choice(OptimizationCatalog.SORTING_INFO_CACHE));
    }
}
