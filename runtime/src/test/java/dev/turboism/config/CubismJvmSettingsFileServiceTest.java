package dev.turboism.config;

import dev.turboism.shell.CubismJvmSettingsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CubismJvmSettingsFileServiceTest {

    @TempDir
    Path home;

    private CubismJvmSettingsFileService service(final Map<String, String> environment) throws Exception {
        Files.writeString(home.resolve("config.json"), """
            {
              "format": "turboism.runtime.config",
              "schemaVersion": 1,
              "worktreeId": "jvm-settings-test",
              "pluginDirs": ["plugins"],
              "logLevel": "INFO",
              "safeMode": false,
              "hooks": {"disabledIds": [], "denylistedClasses": [], "startup": {}}
            }
            """);
        return new CubismJvmSettingsFileService(
            new RuntimeConfigRepository(home, ignored -> { }),
            home,
            environment
        );
    }

    @Test
    void reduceAutoBackupDefaultsToFalse() throws Exception {
        try (CubismJvmSettingsFileService service = service(Map.of())) {
            assertFalse(service.reduceAutoBackup());
        }
    }

    @Test
    void reduceAutoBackupRoundTripsThroughConfig() throws Exception {
        try (CubismJvmSettingsFileService service = service(Map.of())) {
            service.saveReduceAutoBackup(true);
            assertTrue(service.reduceAutoBackup());
            assertTrue(Files.readString(home.resolve("config.json"))
                .contains("\"reduceAutoBackup\" : true"));
            service.saveReduceAutoBackup(false);
            assertFalse(service.reduceAutoBackup());
            assertFalse(Files.readString(home.resolve("config.json"))
                .contains("reduceAutoBackup"));
        }
    }

    @Test
    void reduceAutoBackupEnvOverrideWins() throws Exception {
        for (String truthy : new String[] {"1", "true", "TRUE", "yes", "on", " true "}) {
            try (CubismJvmSettingsFileService service = service(
                Map.of("TURBOISM_REDUCE_AUTO_BACKUP", truthy))) {
                assertTrue(service.reduceAutoBackup(), "value=" + truthy);
            }
        }
        for (String falsy : new String[] {"0", "false", "no", "off", "junk"}) {
            try (CubismJvmSettingsFileService service = service(
                Map.of("TURBOISM_REDUCE_AUTO_BACKUP", falsy))) {
                assertFalse(service.reduceAutoBackup(), "value=" + falsy);
            }
        }
        try (CubismJvmSettingsFileService service = service(
            Map.of("TURBOISM_REDUCE_AUTO_BACKUP", "0"))) {
            service.saveReduceAutoBackup(true);
            assertFalse(service.reduceAutoBackup());
        }
    }

    @Test
    void zgcDefaultsToTrue() throws Exception {
        try (CubismJvmSettingsFileService service = service(Map.of())) {
            assertTrue(service.zgc());
        }
    }

    @Test
    void zgcRoundTripsThroughLauncherConfig() throws Exception {
        try (CubismJvmSettingsFileService service = service(Map.of())) {
            service.saveZgc(false);
            assertFalse(service.zgc());
            assertTrue(Files.readString(home.resolve("config.json"))
                .contains("\"zgc\" : false"));
            service.saveZgc(true);
            assertTrue(service.zgc());
            assertFalse(Files.readString(home.resolve("config.json"))
                .contains("zgc"));
        }
    }

    @Test
    void zgcSurvivesBesideOtherLauncherFields() throws Exception {
        try (CubismJvmSettingsFileService service = service(Map.of())) {
            service.save(CubismJvmSettingsService.CubismJvm.GRAALVM);
            service.saveZgc(false);
            assertFalse(service.zgc());
            service.saveZgc(true);
            assertTrue(service.zgc());
            final String saved = Files.readString(home.resolve("config.json"));
            assertTrue(saved.contains("cubismJvm"));
            assertFalse(saved.contains("zgc"));
        }
    }

    @Test
    void optimizationsDefaultToTrue() throws Exception {
        try (CubismJvmSettingsFileService service = service(Map.of())) {
            assertTrue(service.modelUpdateSkip());
            assertTrue(service.incrementalUpdate());
        }
    }

    @Test
    void optimizationsRoundTripThroughLauncherConfig() throws Exception {
        try (CubismJvmSettingsFileService service = service(Map.of())) {
            service.saveModelUpdateSkip(false);
            service.saveIncrementalUpdate(false);
            assertFalse(service.modelUpdateSkip());
            assertFalse(service.incrementalUpdate());
            final String saved = Files.readString(home.resolve("config.json"));
            assertTrue(saved.contains("\"modelUpdateSkip\" : false"));
            assertTrue(saved.contains("\"incrementalUpdate\" : false"));
            service.saveModelUpdateSkip(true);
            assertTrue(service.modelUpdateSkip());
            assertFalse(service.incrementalUpdate());
            final String restored = Files.readString(home.resolve("config.json"));
            assertFalse(restored.contains("modelUpdateSkip"));
            assertTrue(restored.contains("\"incrementalUpdate\" : false"));
        }
    }
}
