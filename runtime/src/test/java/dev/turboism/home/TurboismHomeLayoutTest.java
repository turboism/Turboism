package dev.turboism.home;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TurboismHomeLayoutTest {

    @TempDir
    Path home;

    @Test
    void pluginPathsLeaveConfigDataAndCacheAbsentAndUseSharedRuntimeLogs() throws Exception {
        final PluginHomePaths paths = TurboismHomeLayout.create(home).plugin("dev.example.plugin");

        assertAll(
            () -> assertEquals(home.resolve("config/dev.example.plugin"), paths.configDir()),
            () -> assertEquals(home.resolve("data/dev.example.plugin"), paths.dataDir()),
            () -> assertEquals(home.resolve("cache/dev.example.plugin"), paths.cacheDir()),
            () -> assertEquals(home.resolve("state/dev.example.plugin"), paths.stateDir()),
            () -> assertFalse(Files.exists(paths.configDir())),
            () -> assertFalse(Files.exists(paths.dataDir())),
            () -> assertFalse(Files.exists(paths.cacheDir())),
            () -> assertTrue(Files.isDirectory(paths.stateDir())),
            () -> assertThrows(UnsupportedOperationException.class, paths::logsDir),
            () -> assertFalse(Files.exists(home.resolve("logs/dev.example.plugin")))
        );
    }
}
