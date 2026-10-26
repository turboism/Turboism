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
    void uniformLocationPreferenceDefaultsOffAndSurvivesReopen() throws Exception {
        try (CubismJvmSettingsFileService settings = service(Map.of())) {
            assertFalse(settings.uniformLocationCache());
            settings.saveModelUpdateSkip(false);
            assertFalse(settings.saveUniformLocationCache(false));
            assertFalse(settings.uniformLocationCache());
        }
        try (CubismJvmSettingsFileService reopened =
                new CubismJvmSettingsFileService(new RuntimeConfigRepository(home, ignored -> {}), home, Map.of())) {
            assertFalse(reopened.uniformLocationCache(), "explicit opt-out survives restart");
            assertFalse(reopened.modelUpdateSkip(), "unrelated preference is preserved");
            assertTrue(reopened.saveUniformLocationCache(true));
            assertTrue(reopened.uniformLocationCache());
            assertTrue(Files.readString(home.resolve("config.json")).contains("\"uniformLocationCache\" : true"));
            assertFalse(reopened.modelUpdateSkip());
        }
    }

    @Test
    void nativeDefaultsAndExplicitOverrides() throws Exception {
        try (CubismJvmSettingsFileService service = service(Map.of())) {
            assertFalse(service.modelUpdateSkip());
            assertFalse(service.uniformLocationCache());
            assertFalse(service.uploadElision(), "rendering optimizations require opt-in on every platform");
            assertFalse(service.incrementalUpdate());
            assertFalse(service.inputPathElision(), "input-path elision defaults off natively");
            assertFalse(service.mesaGlThread(), "mesa glthread defaults off natively");
            assertTrue(service.saveIncrementalUpdate(true));
            assertTrue(service.saveInputPathElision(true));
            assertTrue(service.saveMesaGlThread(true));
            assertFalse(service.saveUploadElision(false));
        }
        try (CubismJvmSettingsFileService reopened =
                new CubismJvmSettingsFileService(new RuntimeConfigRepository(home, ignored -> {}), home, Map.of())) {
            assertTrue(reopened.incrementalUpdate(), "explicit experimental opt-in survives restart");
            assertTrue(reopened.inputPathElision(), "explicit input-path opt-in survives restart");
            assertTrue(reopened.mesaGlThread(), "explicit mesa-glthread opt-in survives restart");
            assertFalse(reopened.uploadElision(), "explicit upload-elision opt-out survives restart");
            assertFalse(reopened.uniformLocationCache());
            reopened.saveIncrementalUpdate(false);
            reopened.saveInputPathElision(false);
            reopened.saveMesaGlThread(false);
            reopened.saveUploadElision(true);
            assertFalse(reopened.incrementalUpdate());
            assertFalse(reopened.inputPathElision());
            assertFalse(reopened.mesaGlThread());
            assertTrue(reopened.uploadElision());
            final String saved = Files.readString(home.resolve("config.json"));
            assertTrue(saved.contains("\"incrementalUpdate\" : false"));
            assertTrue(saved.contains("\"uploadElision\" : true"));
            assertTrue(saved.contains("\"inputPathElision\" : false"));
            assertTrue(saved.contains("\"mesaGlThread\" : false"));
        }
    }

    @Test
    void protonDefaultsAndExplicitOverrides() throws Exception {
        final Map<String, String> proton = Map.of(dev.turboism.runtime.env.ProtonEnvironment.MANAGED_MARKER, "1");
        try (CubismJvmSettingsFileService service = service(proton)) {
            assertFalse(service.uploadElision(), "rendering optimizations require opt-in on every platform");
            assertFalse(service.inputPathElision(), "rendering optimizations require opt-in on every platform");
            assertFalse(service.mesaGlThread(), "rendering optimizations require opt-in on every platform");
            assertFalse(service.incrementalUpdate(), "unrelated opt-in stays off");
            // Explicit choices persist even when they match this platform default.
            assertTrue(service.saveInputPathElision(true));
            assertTrue(Files.readString(home.resolve("config.json")).contains("\"inputPathElision\" : true"));
            assertFalse(service.saveInputPathElision(false));
            assertFalse(service.saveMesaGlThread(false));
            assertFalse(service.inputPathElision());
            assertFalse(service.mesaGlThread());
        }
        try (CubismJvmSettingsFileService reopened =
                new CubismJvmSettingsFileService(new RuntimeConfigRepository(home, ignored -> {}), home, proton)) {
            assertFalse(reopened.inputPathElision(), "explicit opt-out survives restart under Proton");
            assertFalse(reopened.mesaGlThread(), "explicit opt-out survives restart under Proton");
            final String saved = Files.readString(home.resolve("config.json"));
            assertTrue(saved.contains("\"inputPathElision\" : false"));
            assertTrue(saved.contains("\"mesaGlThread\" : false"));
            // The same explicit opt-out reads false on native too — explicit
            // values always win over either platform default.
            try (CubismJvmSettingsFileService nativeReopen = new CubismJvmSettingsFileService(
                    new RuntimeConfigRepository(home, ignored -> {}), home, Map.of())) {
                assertFalse(nativeReopen.inputPathElision());
                assertFalse(nativeReopen.mesaGlThread());
            }
        }
    }

    @Test
    void protonMarkersDoNotEnableRenderingOptimizations() throws Exception {
        // Platform markers never implicitly enable rendering optimizations.
        try (CubismJvmSettingsFileService service = service(Map.of("TURBOISM_PROTON", "1"))) {
            assertFalse(service.inputPathElision());
            assertFalse(service.mesaGlThread());
        }
        for (final Map<String, String> env : java.util.List.of(
                Map.of("WINEPREFIX", "/pfx"),
                Map.of("STEAM_COMPAT_DATA_PATH", "/steam/compat"),
                Map.of("WINEFSYNC", "1"),
                Map.of("TURBOISM_PROTON", ""))) {
            try (CubismJvmSettingsFileService service = service(env)) {
                assertFalse(service.inputPathElision(), "env=" + env);
                assertFalse(service.mesaGlThread(), "env=" + env);
            }
        }
    }

    @Test
    void explicitPlatformDefaultSurvivesMovingGlobalConfigToAnotherPlatform() throws Exception {
        try (CubismJvmSettingsFileService nativeSettings = service(Map.of())) {
            nativeSettings.saveInputPathElision(false);
            nativeSettings.saveMesaGlThread(false);
        }
        try (CubismJvmSettingsFileService protonSettings = new CubismJvmSettingsFileService(
                new RuntimeConfigRepository(home, ignored -> {}),
                home,
                Map.of(dev.turboism.runtime.env.ProtonEnvironment.MANAGED_MARKER, "1"))) {
            assertFalse(protonSettings.inputPathElision());
            assertFalse(protonSettings.mesaGlThread());
        }
    }

    @Test
    void savedPreferenceAndObservedStartupStateRemainSeparate() throws Exception {
        try (CubismJvmSettingsFileService settings = service(Map.of())) {
            var option = NativeOptimizationConfig.Option.MODEL_UPDATE_SKIP;
            NativeOptimizationDiagnostics.requested(option, new NativeOptimizationConfig.Choice(true, "global-config"));
            NativeOptimizationDiagnostics.installed(
                    "TURBOISM_MODEL_UPDATE_SKIP", "not-installed", "host-not-supported");
            settings.saveModelUpdateSkip(false);
            var status = settings.optimizationStatuses().stream()
                    .filter(row -> row.key().equals("modelUpdateSkip"))
                    .findFirst()
                    .orElseThrow();
            assertFalse(status.saved());
            assertTrue(status.requested().orElseThrow());
            assertEquals("not-installed", status.state());
            assertEquals("host-not-supported", status.reason());
            assertEquals(home.resolve("config.json").toString(), settings.globalConfigPath());
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
            assertTrue(restored.contains("\"modelUpdateSkip\" : true"));
            assertTrue(restored.contains("\"incrementalUpdate\" : false"));
        }
    }
}
