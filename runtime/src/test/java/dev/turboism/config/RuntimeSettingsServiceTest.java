package dev.turboism.config;

import dev.turboism.sdk.runtime.RuntimeSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
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

        RuntimeSettings saved = service.save(new RuntimeSettings(true, "DEBUG", true, true, true));
        RuntimeSettings reloaded = service.read();

        assertEquals(saved, reloaded);
        assertTrue(reloaded.safeMode());
        assertTrue(reloaded.skipStartupUpdateCheck());
        assertTrue(reloaded.skipStartupSplash());
        assertTrue(reloaded.skipStartupInformation());
        assertEquals("DEBUG", reloaded.logLevel());
        assertFalse(Files.exists(home.resolve("config/runtime.json")));
        assertFalse(Files.exists(home.resolve("config.json.tmp")));
    }

    @Test
    void createsCanonicalConfigWhenMissing() {
        RuntimeSettingsFileService service = new RuntimeSettingsFileService(home, coordinator());

        RuntimeSettings settings = service.read();
        service.save(settings);

        assertTrue(Files.isRegularFile(home.resolve("config.json")));
        assertEquals("INFO", settings.logLevel());
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
