package dev.turboism.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.internal.core.CubismJvmSettingsService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
        return new CubismJvmSettingsFileService(new RuntimeConfigRepository(home, ignored -> {}), home, environment);
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
            assertTrue(Files.readString(home.resolve("config.json")).contains("\"reduceAutoBackup\" : true"));
            service.saveReduceAutoBackup(false);
            assertFalse(service.reduceAutoBackup());
            assertTrue(Files.readString(home.resolve("config.json")).contains("\"reduceAutoBackup\" : false"));
        }
    }

    @Test
    void reduceAutoBackupEnvOverrideWins() throws Exception {
        for (String truthy : new String[] {"1", "true", "TRUE", "yes", "on", " true "}) {
            try (CubismJvmSettingsFileService service = service(Map.of("TURBOISM_REDUCE_AUTO_BACKUP", truthy))) {
                assertTrue(service.reduceAutoBackup(), "value=" + truthy);
            }
        }
        for (String falsy : new String[] {"0", "false", "no", "off", "junk"}) {
            try (CubismJvmSettingsFileService service = service(Map.of("TURBOISM_REDUCE_AUTO_BACKUP", falsy))) {
                assertFalse(service.reduceAutoBackup(), "value=" + falsy);
            }
        }
        try (CubismJvmSettingsFileService service = service(Map.of("TURBOISM_REDUCE_AUTO_BACKUP", "0"))) {
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
            assertTrue(Files.readString(home.resolve("config.json")).contains("\"zgc\" : false"));
            service.saveZgc(true);
            assertTrue(service.zgc());
            assertTrue(Files.readString(home.resolve("config.json")).contains("\"zgc\" : true"));
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
            assertTrue(saved.contains("\"zgc\" : true"));
        }
    }

    @Test
    void memoryProfileDefaultsToSystem() throws Exception {
        try (CubismJvmSettingsFileService service = service(Map.of())) {
            assertEquals(CubismJvmSettingsService.MemoryProfile.SYSTEM, service.memoryProfile());
        }
    }

    @Test
    void memoryProfileRoundTripsThroughLauncherConfig() throws Exception {
        try (CubismJvmSettingsFileService service = service(Map.of())) {
            service.saveMemoryProfile(CubismJvmSettingsService.MemoryProfile.BALANCED_4G);
            assertEquals(CubismJvmSettingsService.MemoryProfile.BALANCED_4G, service.memoryProfile());
            assertTrue(Files.readString(home.resolve("config.json")).contains("\"memoryProfile\" : \"balanced4g\""));
            service.saveMemoryProfile(CubismJvmSettingsService.MemoryProfile.BALANCED_4G_FAST_SOFT);
            assertEquals(CubismJvmSettingsService.MemoryProfile.BALANCED_4G_FAST_SOFT, service.memoryProfile());
            assertTrue(Files.readString(home.resolve("config.json"))
                    .contains("\"memoryProfile\" : \"balanced4gFastSoft\""));
            service.saveMemoryProfile(CubismJvmSettingsService.MemoryProfile.SYSTEM);
            assertEquals(CubismJvmSettingsService.MemoryProfile.SYSTEM, service.memoryProfile());
            assertTrue(Files.readString(home.resolve("config.json")).contains("\"memoryProfile\" : \"system\""));
        }
    }

    @Test
    void memoryProfileSurvivesReopenBesideOtherLauncherFields() throws Exception {
        try (CubismJvmSettingsFileService service = service(Map.of())) {
            service.saveZgc(false);
            service.saveMemoryProfile(CubismJvmSettingsService.MemoryProfile.BALANCED_4G_FAST_SOFT);
        }
        try (CubismJvmSettingsFileService reopened =
                new CubismJvmSettingsFileService(new RuntimeConfigRepository(home, ignored -> {}), home, Map.of())) {
            assertEquals(CubismJvmSettingsService.MemoryProfile.BALANCED_4G_FAST_SOFT, reopened.memoryProfile());
            assertFalse(reopened.zgc(), "unrelated launcher preference is preserved");
        }
    }

    @Test
    void malformedMemoryProfileFailsClosedToSystemWithDiagnostic() throws Exception {
        Files.writeString(home.resolve("config.json"), """
            {
              "format": "turboism.runtime.config",
              "schemaVersion": 1,
              "worktreeId": "jvm-settings-test",
              "pluginDirs": ["plugins"],
              "logLevel": "INFO",
              "safeMode": false,
              "launcher": {"memoryProfile": "extreme16g"},
              "hooks": {"disabledIds": [], "denylistedClasses": [], "startup": {}}
            }
            """);
        final List<String> diagnostics = new ArrayList<>();
        try (CubismJvmSettingsFileService service =
                new CubismJvmSettingsFileService(new RuntimeConfigRepository(home, diagnostics::add), home, Map.of())) {
            assertEquals(CubismJvmSettingsService.MemoryProfile.SYSTEM, service.memoryProfile());
            assertTrue(diagnostics.contains("RUNTIME_CONFIG_BAD_MEMORY_PROFILE"));
            assertTrue(
                    Files.readString(home.resolve("config.json")).contains("extreme16g"),
                    "the corrupt persisted value stays on disk until an explicit save");
        }
    }

    @Test
    void nonTextualMemoryProfileFailsClosedToSystemWithDiagnostic() throws Exception {
        Files.writeString(home.resolve("config.json"), """
            {
              "format": "turboism.runtime.config",
              "schemaVersion": 1,
              "worktreeId": "jvm-settings-test",
              "pluginDirs": ["plugins"],
              "logLevel": "INFO",
              "safeMode": false,
              "launcher": {"memoryProfile": 42},
              "hooks": {"disabledIds": [], "denylistedClasses": [], "startup": {}}
            }
            """);
        final List<String> diagnostics = new ArrayList<>();
        try (CubismJvmSettingsFileService service =
                new CubismJvmSettingsFileService(new RuntimeConfigRepository(home, diagnostics::add), home, Map.of())) {
            assertEquals(CubismJvmSettingsService.MemoryProfile.SYSTEM, service.memoryProfile());
            assertTrue(diagnostics.contains("RUNTIME_CONFIG_BAD_MEMORY_PROFILE"));
        }
    }

    @Test
    void savedPreferenceAndObservedStartupStateRemainSeparate() throws Exception {
        try (CubismJvmSettingsFileService settings = service(Map.of())) {
            new RuntimeConfigRepository(home, ignored -> {}).update(root -> {
                root.withObject("launcher").put("modelUpdateSkip", true);
                return root;
            });
            NativeOptimizationDiagnostics.startup(
                    NativeOptimizationConfig.load(home, ignored -> {}).settings());
            NativeOptimizationDiagnostics.installed(
                    "TURBOISM_MODEL_UPDATE_SKIP", "not-installed", "host-not-supported");
            new OptimizationSettingsFileService(new RuntimeConfigRepository(home, ignored -> {}))
                    .setEnabled("modelUpdateSkip", false);
            var status = settings.performanceStatuses().stream()
                    .filter(row -> row.key().equals("modelUpdateSkip"))
                    .findFirst()
                    .orElseThrow();
            assertEquals("false", status.saved(), "the edited preference");
            assertEquals("true", status.requested(), "the startup request captured before the edit");
            assertEquals("global-config", status.source());
            assertEquals("not-installed: host-not-supported", status.actual(), "the observed outcome");
            assertTrue(status.pendingRestart());
            assertEquals(home.resolve("config.json").toString(), settings.globalConfigPath());
        } finally {
            NativeOptimizationDiagnostics.resetForTesting();
        }
    }
}
