package dev.turboism.config;

import dev.turboism.internal.core.CubismJvmSettingsService;
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
    void uniformLocationPreferenceDefaultsOnAndSurvivesReopen() throws Exception {
        try (CubismJvmSettingsFileService settings = service(Map.of())) {
            assertTrue(settings.uniformLocationCache());
            settings.saveModelUpdateSkip(false);
            assertFalse(settings.saveUniformLocationCache(false));
            assertFalse(settings.uniformLocationCache());
        }
        try (CubismJvmSettingsFileService reopened = new CubismJvmSettingsFileService(
                new RuntimeConfigRepository(home, ignored -> { }), home, Map.of())) {
            assertFalse(reopened.uniformLocationCache(), "explicit opt-out survives restart");
            assertFalse(reopened.modelUpdateSkip(), "unrelated preference is preserved");
            assertTrue(reopened.saveUniformLocationCache(true));
            assertTrue(reopened.uniformLocationCache());
            assertFalse(Files.readString(home.resolve("config.json")).contains("uniformLocationCache"));
            assertFalse(reopened.modelUpdateSkip());
        }
    }

    @Test
    void nativeDefaultsAndExplicitOverrides() throws Exception {
        try (CubismJvmSettingsFileService service = service(Map.of())) {
            assertTrue(service.modelUpdateSkip());
            assertTrue(service.uniformLocationCache());
            assertTrue(service.uploadElision(), "upload elision defaults on everywhere");
            assertFalse(service.incrementalUpdate());
            assertFalse(service.inputPathElision(), "input-path elision defaults off natively");
            assertFalse(service.mesaGlThread(), "mesa glthread defaults off natively");
            assertTrue(service.saveIncrementalUpdate(true));
            assertTrue(service.saveInputPathElision(true));
            assertTrue(service.saveMesaGlThread(true));
            assertFalse(service.saveUploadElision(false));
        }
        try (CubismJvmSettingsFileService reopened = new CubismJvmSettingsFileService(
                new RuntimeConfigRepository(home, ignored -> { }), home, Map.of())) {
            assertTrue(reopened.incrementalUpdate(), "explicit experimental opt-in survives restart");
            assertTrue(reopened.inputPathElision(), "explicit input-path opt-in survives restart");
            assertTrue(reopened.mesaGlThread(), "explicit mesa-glthread opt-in survives restart");
            assertFalse(reopened.uploadElision(), "explicit upload-elision opt-out survives restart");
            assertTrue(reopened.uniformLocationCache());
            reopened.saveIncrementalUpdate(false);
            reopened.saveInputPathElision(false);
            reopened.saveMesaGlThread(false);
            reopened.saveUploadElision(true);
            assertFalse(reopened.incrementalUpdate());
            assertFalse(reopened.inputPathElision());
            assertFalse(reopened.mesaGlThread());
            assertTrue(reopened.uploadElision());
            final String saved = Files.readString(home.resolve("config.json"));
            assertFalse(saved.contains("incrementalUpdate"), "platform default value clears the key");
            assertFalse(saved.contains("uploadElision"), "platform default value clears the key");
            assertFalse(saved.contains("inputPathElision"), "platform default value clears the key");
            assertFalse(saved.contains("mesaGlThread"), "platform default value clears the key");
        }
    }

    @Test
    void protonDefaultsAndExplicitOverrides() throws Exception {
        final Map<String, String> proton =
            Map.of(dev.turboism.runtime.env.ProtonEnvironment.MANAGED_MARKER, "1");
        try (CubismJvmSettingsFileService service = service(proton)) {
            assertTrue(service.uploadElision(), "upload elision defaults on under Proton too");
            assertTrue(service.inputPathElision(), "input-path elision defaults on under Proton");
            assertTrue(service.mesaGlThread(), "mesa glthread defaults on under Proton");
            assertFalse(service.incrementalUpdate(), "unrelated opt-in stays off");
            // Saving the platform default removes the key; only explicit
            // non-default values persist.
            assertTrue(service.saveInputPathElision(true));
            assertFalse(Files.readString(home.resolve("config.json")).contains("inputPathElision"));
            assertFalse(service.saveInputPathElision(false));
            assertFalse(service.saveMesaGlThread(false));
            assertFalse(service.inputPathElision());
            assertFalse(service.mesaGlThread());
        }
        try (CubismJvmSettingsFileService reopened = new CubismJvmSettingsFileService(
                new RuntimeConfigRepository(home, ignored -> { }), home, proton)) {
            assertFalse(reopened.inputPathElision(), "explicit opt-out survives restart under Proton");
            assertFalse(reopened.mesaGlThread(), "explicit opt-out survives restart under Proton");
            final String saved = Files.readString(home.resolve("config.json"));
            assertTrue(saved.contains("\"inputPathElision\" : false"));
            assertTrue(saved.contains("\"mesaGlThread\" : false"));
            // The same explicit opt-out reads false on native too — explicit
            // values always win over either platform default.
            try (CubismJvmSettingsFileService nativeReopen = new CubismJvmSettingsFileService(
                    new RuntimeConfigRepository(home, ignored -> { }), home, Map.of())) {
                assertFalse(nativeReopen.inputPathElision());
                assertFalse(nativeReopen.mesaGlThread());
            }
        }
    }

    @Test
    void protonMarkerVariantsDriveThePlatformDefault() throws Exception {
        for (final Map<String, String> env : java.util.List.of(
            Map.of("WINEPREFIX", "/pfx"),
            Map.of("STEAM_COMPAT_DATA_PATH", "/steam/compat"),
            Map.of("WINEFSYNC", "1")
        )) {
            try (CubismJvmSettingsFileService service = service(env)) {
                assertTrue(service.inputPathElision(), "env=" + env);
                assertTrue(service.mesaGlThread(), "env=" + env);
            }
        }
        // Empty marker values carry no signal.
        try (CubismJvmSettingsFileService service = service(Map.of("WINEPREFIX", ""))) {
            assertFalse(service.inputPathElision());
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
            assertFalse(saved.contains("incrementalUpdate"));
            service.saveModelUpdateSkip(true);
            assertTrue(service.modelUpdateSkip());
            assertFalse(service.incrementalUpdate());
            final String restored = Files.readString(home.resolve("config.json"));
            assertFalse(restored.contains("modelUpdateSkip"));
            assertFalse(restored.contains("incrementalUpdate"));
        }
    }
}
