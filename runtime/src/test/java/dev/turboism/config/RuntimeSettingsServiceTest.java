package dev.turboism.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.turboism.sdk.runtime.RuntimeSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeSettingsServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path home;

    @Test
    void savesCanonicalConfigAtomicallyAndPreservesSafeModeOverrides() throws Exception {
        Files.writeString(home.resolve("config.json"), """
            {
              "format": "turboism.runtime.config",
              "schemaVersion": 1,
              "worktreeId": "settings-test",
              "pluginDirs": ["plugins"],
              "logLevel": "INFO",
              "safeMode": false,
              "hooks": {"disabledIds": [], "denylistedClasses": [], "startup": {}}
            }
            """);
        RuntimeSettingsFileService service = new RuntimeSettingsFileService(home, coordinator());

        RuntimeSettings saved = service.save(new RuntimeSettings(true, "DEBUG", 64, true, true, true, true));
        RuntimeSettings reloaded = service.read();

        assertEquals(saved, reloaded);
        assertTrue(reloaded.safeMode());
        assertTrue(reloaded.skipStartupUpdateCheck());
        assertTrue(reloaded.skipStartupSplash());
        assertTrue(reloaded.skipStartupInformation());
        assertEquals("DEBUG", reloaded.logLevel());
        assertEquals(64, reloaded.maxLogStorageMiB());
        assertTrue(reloaded.separateExportSaveDirectory());
        assertFalse(Files.exists(home.resolve("config/runtime.json")));
        assertFalse(Files.exists(home.resolve("config.json.tmp")));
    }

    @Test
    void appliesSavedLogLevelToTheRunningLogger() {
        final AtomicReference<String> applied = new AtomicReference<>();
        final RuntimeSettingsFileService service = new RuntimeSettingsFileService(
            home,
            coordinator(),
            applied::set
        );

        service.save(new RuntimeSettings(false, "TRACE", false, false, false));

        assertEquals("TRACE", applied.get());
    }

    @Test
    void appliesSavedStorageLimitToTheRunningLogger() {
        final AtomicInteger applied = new AtomicInteger();
        final RuntimeSettingsFileService service = new RuntimeSettingsFileService(
            home,
            coordinator(),
            ignored -> {},
            applied::set
        );

        service.save(new RuntimeSettings(false, "INFO", 32, false, false, false, false));

        assertEquals(32, applied.get());
    }

    @Test
    void firstReadCreatesCanonicalConfigWithDefaults() throws Exception {
        RuntimeSettingsFileService service = new RuntimeSettingsFileService(home, coordinator());

        RuntimeSettings settings = service.read();

        Path config = home.resolve("config.json");
        assertTrue(Files.isRegularFile(config));
        assertEquals(RuntimeConfigRepository.defaults(), JSON.readTree(config.toFile()));
        assertEquals(settings, service.read());
        assertFalse(settings.safeMode());
        assertEquals("INFO", settings.logLevel());
        assertEquals(RuntimeSettings.DEFAULT_MAX_LOG_STORAGE_MIB, settings.maxLogStorageMiB());
    }

    @Test
    void readingExistingValidConfigDoesNotRewriteIt() throws Exception {
        Path config = home.resolve("config.json");
        Files.writeString(config, """
            {
              "format": "turboism.runtime.config",
              "schemaVersion": 1,
              "worktreeId": "existing-config",
              "pluginDirs": ["plugins"],
              "logLevel": "WARN",
              "maxLogStorageMiB": 64,
              "safeMode": true,
              "hooks": {"disabledIds": [], "denylistedClasses": [], "startup": {}}
            }
            """);
        byte[] before = Files.readAllBytes(config);

        RuntimeSettingsFileService service = new RuntimeSettingsFileService(home, coordinator());
        RuntimeSettings settings = service.read();

        assertTrue(settings.safeMode());
        assertEquals("WARN", settings.logLevel());
        assertEquals(64, settings.maxLogStorageMiB());
        assertArrayEquals(before, Files.readAllBytes(config));
    }

    @Test
    void legacyConfigWithoutNewFieldDefaultsToFalse() throws Exception {
        Files.writeString(home.resolve("config.json"), """
            {
              "format": "turboism.runtime.config",
              "schemaVersion": 1,
              "worktreeId": "settings-legacy",
              "pluginDirs": ["plugins"],
              "logLevel": "INFO",
              "safeMode": false,
              "hooks": {"disabledIds": [], "denylistedClasses": [], "startup": {}}
            }
            """);
        RuntimeSettingsFileService service = new RuntimeSettingsFileService(home, coordinator());

        assertFalse(service.read().separateExportSaveDirectory());
    }

    @Test
    void delegatesEmptyDockCleanup() {
        final AtomicInteger cleanups = new AtomicInteger();
        final dev.turboism.ui.panel.RuntimeDockMaintenanceCoordinator coordinator =
            new dev.turboism.ui.panel.RuntimeDockMaintenanceCoordinator();
        coordinator.bind(1, cleanups::incrementAndGet);
        RuntimeSettingsFileService service = new RuntimeSettingsFileService(home, coordinator);

        assertEquals("Empty dock cleanup completed.", service.cleanEmptyDocks().message());
        assertEquals(1, cleanups.get());
    }


    private static dev.turboism.ui.panel.RuntimeDockMaintenanceCoordinator coordinator() {
        final dev.turboism.ui.panel.RuntimeDockMaintenanceCoordinator coordinator =
            new dev.turboism.ui.panel.RuntimeDockMaintenanceCoordinator();
        coordinator.bind(1, () -> { });
        return coordinator;
    }
}
