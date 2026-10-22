package dev.turboism.config;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.config.NativeOptimizationConfig.Option;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativeOptimizationConfigTest {
    @TempDir
    Path home;

    private final Map<Option, String> properties = new EnumMap<>(Option.class);

    @BeforeEach
    void clearOverrides() {
        for (Option option : Option.values()) {
            properties.put(option, System.getProperty(option.property()));
            System.clearProperty(option.property());
        }
    }

    @AfterEach
    void restoreOverrides() {
        for (Option option : Option.values()) {
            String value = properties.get(option);
            if (value == null) System.clearProperty(option.property());
            else System.setProperty(option.property(), value);
        }
    }

    @Test
    void absentConfigUsesDefaultsWithoutCreatingAFile() {
        var snapshot = NativeOptimizationConfig.load(home, ignored -> {});
        boolean proton = dev.turboism.runtime.env.ProtonEnvironment.underWineOrProton();
        for (Option option : Option.values()) {
            assertEquals(option.defaultEnabled(proton), snapshot.choice(option).enabled());
            assertEquals("platform-default", snapshot.choice(option).source());
        }
        assertFalse(Files.exists(home.resolve("config.json")));
    }

    @Test
    void globalChoicesAreCapturedTogetherAndReReadOnNextLaunch() {
        var repository = new RuntimeConfigRepository(home, ignored -> {});
        repository.update(root -> {
            for (Option option : Option.values())
                (option.field().launcher() ? root.withObject("launcher") : root).put(option.key(), false);
            return root;
        });
        var firstLaunch = NativeOptimizationConfig.load(home, ignored -> {});
        repository.update(root -> {
            for (Option option : Option.values())
                (option.field().launcher() ? root.withObject("launcher") : root).put(option.key(), true);
            return root;
        });
        var nextLaunch = NativeOptimizationConfig.load(home, ignored -> {});
        for (Option option : Option.values()) {
            assertFalse(firstLaunch.choice(option).enabled());
            assertTrue(nextLaunch.choice(option).enabled());
            assertEquals("global-config", nextLaunch.choice(option).source());
        }
    }

    @Test
    void diagnosticOverrideHasAnExplicitSourceAndDoesNotRewriteTheFile() throws Exception {
        new RuntimeConfigRepository(home, ignored -> {}).update(root -> {
            root.withObject("launcher").put("incrementalUpdate", false);
            return root;
        });
        String saved = Files.readString(home.resolve("config.json"));
        System.setProperty(Option.INCREMENTAL_UPDATE.property(), "true");
        var launch = NativeOptimizationConfig.load(home, ignored -> {});
        assertTrue(launch.choice(Option.INCREMENTAL_UPDATE).enabled());
        assertEquals("jvm-override", launch.choice(Option.INCREMENTAL_UPDATE).source());
        System.setProperty(Option.INCREMENTAL_UPDATE.property(), "false");
        assertTrue(launch.choice(Option.INCREMENTAL_UPDATE).enabled(), "snapshot is immutable");
        assertEquals(saved, Files.readString(home.resolve("config.json")));
    }

    @Test
    void invalidGlobalConfigDisablesEveryOptimizationEvenWithOverrides() throws Exception {
        Files.writeString(home.resolve("config.json"), "{broken");
        for (Option option : Option.values()) System.setProperty(option.property(), "true");
        var snapshot = NativeOptimizationConfig.load(home, ignored -> {});
        for (Option option : Option.values()) {
            assertFalse(snapshot.choice(option).enabled());
            assertEquals("invalid-config", snapshot.choice(option).source());
        }
    }
}
