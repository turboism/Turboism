package dev.turboism.config;

import dev.turboism.sdk.runtime.RuntimeSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeSettingsServiceTest {

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

        RuntimeSettings saved = service.save(new RuntimeSettings(true, "DEBUG", 64, true, true, true));
        RuntimeSettings reloaded = service.read();

        assertEquals(saved, reloaded);
        assertTrue(reloaded.safeMode());
        assertTrue(reloaded.skipStartupUpdateCheck());
        assertTrue(reloaded.skipStartupSplash());
        assertTrue(reloaded.skipStartupInformation());
        assertEquals("DEBUG", reloaded.logLevel());
        assertEquals(64, reloaded.maxLogStorageMiB());
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

        service.save(new RuntimeSettings(false, "INFO", 32, false, false, false));

        assertEquals(32, applied.get());
    }

    @Test
    void createsCanonicalConfigWhenMissing() {
        RuntimeSettingsFileService service = new RuntimeSettingsFileService(home, coordinator());

        RuntimeSettings settings = service.read();
        service.save(settings);

        assertTrue(Files.isRegularFile(home.resolve("config.json")));
        assertEquals("INFO", settings.logLevel());
        assertEquals(100, settings.maxLogStorageMiB());
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
