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
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    void savesAndReloadsConfiguredLocale() {
        RuntimeSettingsFileService service = new RuntimeSettingsFileService(home, coordinator());
        RuntimeSettings requested = new RuntimeSettings(
            false, "INFO", 100, false, false, false, false, "zh-Hant"
        );

        service.save(requested);

        assertEquals("zh-Hant", service.read().locale());
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
        assertEquals(RuntimeSettings.DEFAULT_LOCALE, service.read().locale());
    }

    @Test
    void firstSuccessfulReadInitializesTheBaselineOnce() throws Exception {
        writeValid("settings-baseline");
        RuntimeSettingsFileService service = new RuntimeSettingsFileService(home, coordinator());

        RuntimeSettings first = service.read();
        RuntimeSettings baseline = service.baselineForTest();
        assertEquals(first, baseline);

        service.save(new RuntimeSettings(false, "TRACE", 32, true, false, false, true, "ja"));
        assertEquals(baseline, service.baselineForTest(), "a later save must never replace the baseline");
        assertEquals("ja", service.read().locale());
        assertEquals(baseline, service.baselineForTest());
    }

    @Test
    void firstSuccessfulSaveInitializesTheBaselineWhenNoReadHappenedFirst() {
        RuntimeSettingsFileService service = new RuntimeSettingsFileService(home, coordinator());

        RuntimeSettings saved = service.save(new RuntimeSettings(false, "DEBUG", 64, true, true, true, true, "ko"));
        assertEquals(saved, service.baselineForTest());

        service.save(new RuntimeSettings(false, "INFO", 100, false, false, false, false, "en"));
        assertEquals(saved, service.baselineForTest(), "the baseline stays at the first success");
        assertEquals("en", service.activeForTest().locale());
    }

    @Test
    void laterSuccessfulReadsReplaceActiveButNeverTheBaseline() throws Exception {
        writeValid("settings-active");
        RuntimeSettingsFileService service = new RuntimeSettingsFileService(home, coordinator());
        RuntimeSettings baseline = service.read();

        Files.writeString(home.resolve("config.json"), """
            {
              "format": "turboism.runtime.config",
              "schemaVersion": 1,
              "worktreeId": "settings-active",
              "pluginDirs": ["plugins"],
              "logLevel": "TRACE",
              "safeMode": false,
              "hooks": {"disabledIds": [], "denylistedClasses": [], "startup": {}}
            }
            """);
        RuntimeSettings reloaded = service.read();
        assertEquals("TRACE", reloaded.logLevel());
        assertEquals(baseline, service.baselineForTest());
        assertEquals(reloaded, service.activeForTest());
    }

    @Test
    void rejectedInvalidReloadPreservesActiveAndBaseline() throws Exception {
        writeValid("settings-reload");
        RuntimeSettingsFileService service = new RuntimeSettingsFileService(home, coordinator());
        RuntimeSettings baseline = service.read();

        Files.writeString(home.resolve("config.json"), """
            {
              "format": "turboism.runtime.config",
              "schemaVersion": 1,
              "worktreeId": "settings-reload",
              "pluginDirs": ["plugins"],
              "logLevel": "BOGUS",
              "safeMode": false,
              "hooks": {"disabledIds": [], "denylistedClasses": [], "startup": {}}
            }
            """);
        RuntimeSettings preserved = service.read();
        assertEquals(baseline, preserved);
        assertEquals(baseline, service.baselineForTest());
        assertEquals(preserved, service.activeForTest());
    }

    @Test
    void rejectedInvalidReloadAfterMultipleSuccessfulValuesPreservesTheLastActive() throws Exception {
        writeValid("settings-multi");
        RuntimeSettingsFileService service = new RuntimeSettingsFileService(home, coordinator());
        RuntimeSettings baseline = service.read();
        service.save(new RuntimeSettings(false, "TRACE", 32, true, false, false, true, "ja"));
        RuntimeSettings second = service.read();
        assertEquals("ja", second.locale());

        Files.writeString(home.resolve("config.json"), "not json at all");
        assertEquals(second, service.read(), "an invalid reload preserves the last active value");
        assertEquals(baseline, service.baselineForTest());
        assertEquals(second, service.activeForTest());
    }

    @Test
    void rejectedSaveThrowsAndChangesNeitherActiveNorBaseline() throws Exception {
        writeValid("settings-save");
        RuntimeSettingsFileService service = new RuntimeSettingsFileService(home, coordinator());
        RuntimeSettings baseline = service.read();
        String before = Files.readString(home.resolve("config.json"));

        assertThrows(
            RuntimeException.class,
            () -> service.save(new RuntimeSettings(false, "BOGUS", 100, false, false, false, false, "en"))
        );
        assertEquals(baseline, service.baselineForTest());
        assertEquals(baseline, service.activeForTest());
        assertEquals(before, Files.readString(home.resolve("config.json")), "the file must stay untouched");
    }

    @Test
    void persistedLocaleChangeUpdatesStoredAndActiveOnlyWithoutMutatingTheResolvedLocale() throws Exception {
        System.setProperty("turboism.locale", "ja");
        try {
            final java.util.Locale resolvedBefore = dev.turboism.i18n.PluginLocaleResolver.resolveStartup(
                "", java.util.Locale.KOREAN, java.util.Locale.ENGLISH, ignored -> { }
            );
            writeValid("settings-locale");
            RuntimeSettingsFileService service = new RuntimeSettingsFileService(home, coordinator());
            service.read();
            java.util.Locale defaultBefore = java.util.Locale.getDefault();

            service.save(new RuntimeSettings(false, "INFO", 100, false, false, false, false, "zh-Hant"));

            assertEquals("zh-Hant", service.read().locale(), "the persisted choice updates stored settings");
            assertEquals("zh-Hant", service.activeForTest().locale());
            assertEquals(resolvedBefore, dev.turboism.i18n.PluginLocaleResolver.resolveStartup(
                "", java.util.Locale.KOREAN, java.util.Locale.ENGLISH, ignored -> { }
            ), "the already-resolved effective locale is unchanged (restart required)");
            assertEquals(defaultBefore, java.util.Locale.getDefault(), "no JVM-global locale mutation");
        } finally {
            System.clearProperty("turboism.locale");
        }
    }

    @Test
    void unsupportedPersistedLocaleIsTreatedAsAbsentWithoutTouchingTheFile() throws Exception {
        Files.writeString(home.resolve("config.json"), """
            {
              "format": "turboism.runtime.config",
              "schemaVersion": 1,
              "worktreeId": "settings-badlocale",
              "pluginDirs": ["plugins"],
              "logLevel": "INFO",
              "locale": "fr",
              "safeMode": false,
              "hooks": {"disabledIds": [], "denylistedClasses": [], "startup": {}}
            }
            """);
        final java.util.List<String> diagnostics = new java.util.ArrayList<>();
        RuntimeSettingsFileService service = new RuntimeSettingsFileService(
            new RuntimeConfigRepository(home, diagnostics::add),
            coordinator()
        );

        RuntimeSettings settings = service.read();
        assertEquals(RuntimeSettings.DEFAULT_LOCALE, settings.locale(), "an unsupported persisted locale is read as absent");
        assertEquals(1, diagnostics.size());
        assertTrue(diagnostics.get(0).contains("RUNTIME_CONFIG_BAD_LOCALE"));
        assertTrue(Files.readString(home.resolve("config.json")).contains("\"locale\": \"fr\""), "the file stays untouched");

        // Writes retain strict validation: saving an unsupported locale is rejected.
        assertThrows(
            IllegalArgumentException.class,
            () -> service.save(new RuntimeSettings(false, "INFO", 100, false, false, false, false, "fr"))
        );
        assertTrue(Files.readString(home.resolve("config.json")).contains("\"locale\": \"fr\""));
    }

    @Test
    void legacyConfigWithoutLocaleInitializesTheBaselineWithTheDefault() throws Exception {
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

        RuntimeSettings settings = service.read();
        assertEquals(RuntimeSettings.DEFAULT_LOCALE, settings.locale());
        assertEquals(settings, service.baselineForTest());
    }


    @Test
    void serializesReadsAndStateReadsBehindPostCommitCallbacks() throws Exception {
        writeValid("settings-serialized");
        final java.util.concurrent.CountDownLatch callbackEntered =
            new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch releaseCallback =
            new java.util.concurrent.CountDownLatch(1);
        final RuntimeSettings requested =
            new RuntimeSettings(false, "DEBUG", 64, false, false, false, false, "en");
        final RuntimeSettingsFileService service = new RuntimeSettingsFileService(
            home,
            coordinator(),
            ignored -> {
                callbackEntered.countDown();
                try {
                    releaseCallback.await(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
            },
            ignored -> {}
        );
        final java.util.concurrent.ExecutorService executor =
            java.util.concurrent.Executors.newFixedThreadPool(3);
        try {
            final java.util.concurrent.Future<RuntimeSettings> save =
                executor.submit(() -> service.save(requested));
            assertTrue(callbackEntered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            final java.util.concurrent.Future<RuntimeSettings> read =
                executor.submit(() -> service.read());
            final java.util.concurrent.Future<RuntimeSettings> active =
                executor.submit(() -> service.activeForTest());

            assertThrows(
                java.util.concurrent.TimeoutException.class,
                () -> read.get(1, java.util.concurrent.TimeUnit.SECONDS)
            );
            assertThrows(
                java.util.concurrent.TimeoutException.class,
                () -> active.get(1, java.util.concurrent.TimeUnit.SECONDS)
            );

            releaseCallback.countDown();
            assertEquals(requested, save.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(requested, read.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(requested, active.get(5, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            releaseCallback.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    @Test
    void reportsPostCommitCallbackFailuresAfterTryingBothCallbacks() throws Exception {
        writeValid("settings-callback-failure");
        final RuntimeException levelFailure = new RuntimeException("level callback failed");
        final RuntimeException storageFailure = new RuntimeException("storage callback failed");
        final java.util.List<String> callbacks = new java.util.ArrayList<>();
        final RuntimeSettingsFileService service = new RuntimeSettingsFileService(
            home,
            coordinator(),
            ignored -> {
                callbacks.add("log-level");
                throw levelFailure;
            },
            ignored -> {
                callbacks.add("storage-limit");
                throw storageFailure;
            }
        );
        final RuntimeSettings requested =
            new RuntimeSettings(false, "DEBUG", 64, false, false, false, false, "en");

        final RuntimeSettingsFileService.PostCommitCallbackFailure failure = assertThrows(
            RuntimeSettingsFileService.PostCommitCallbackFailure.class,
            () -> service.save(requested)
        );

        assertEquals(java.util.List.of("log-level", "storage-limit"), callbacks);
        assertTrue(failure.getCause() == levelFailure);
        assertEquals(1, failure.getSuppressed().length);
        assertTrue(failure.getSuppressed()[0] == storageFailure);
        assertEquals(requested, service.baselineForTest());
        assertEquals(requested, service.activeForTest());
        assertTrue(Files.readString(home.resolve("config.json")).contains("\"logLevel\" : \"DEBUG\""));
        assertEquals(requested, service.read());
    }


    @Test
    void propagatesCallbackErrorsWithoutInvokingLaterCallbacks() throws Exception {
        writeValid("settings-callback-error");
        final Error callbackFailure = new Error("fatal callback failed");
        final java.util.List<String> callbacks = new java.util.ArrayList<>();
        final RuntimeSettingsFileService service = new RuntimeSettingsFileService(
            home,
            coordinator(),
            ignored -> {
                callbacks.add("log-level");
                throw callbackFailure;
            },
            ignored -> callbacks.add("storage-limit")
        );
        final RuntimeSettings requested =
            new RuntimeSettings(false, "DEBUG", 64, false, false, false, false, "en");

        final Error failure = assertThrows(Error.class, () -> service.save(requested));

        assertTrue(failure == callbackFailure);
        assertEquals(java.util.List.of("log-level"), callbacks);
        assertEquals(requested, service.baselineForTest());
        assertEquals(requested, service.activeForTest());
        assertTrue(Files.readString(home.resolve("config.json")).contains("\"logLevel\" : \"DEBUG\""));
    }

    @Test
    void homeConstructorForwardsConfigDiagnostics() throws Exception {
        Files.writeString(home.resolve("config.json"), """
            {
              "format": "turboism.runtime.config",
              "schemaVersion": 1,
              "worktreeId": "settings-home-diagnostic",
              "pluginDirs": ["plugins"],
              "logLevel": "INFO",
              "locale": "fr",
              "safeMode": false,
              "hooks": {"disabledIds": [], "denylistedClasses": [], "startup": {}}
            }
            """);
        final java.util.List<String> diagnostics = new java.util.ArrayList<>();
        final RuntimeSettingsFileService service = new RuntimeSettingsFileService(
            home,
            coordinator(),
            ignored -> {},
            ignored -> {},
            diagnostics::add
        );

        final RuntimeSettings settings = service.read();

        assertEquals(RuntimeSettings.DEFAULT_LOCALE, settings.locale());
        assertEquals(1, diagnostics.size());
        assertTrue(diagnostics.get(0).contains("RUNTIME_CONFIG_BAD_LOCALE"));
        assertTrue(Files.readString(home.resolve("config.json")).contains("\"locale\": \"fr\""));
    }
    private void writeValid(final String worktreeId) throws Exception {
        Files.writeString(home.resolve("config.json"), valid(worktreeId));
    }

    private static String valid(final String worktreeId) {
        return """
            {
              "format": "turboism.runtime.config",
              "schemaVersion": 1,
              "worktreeId": "%s",
              "pluginDirs": ["plugins"],
              "logLevel": "INFO",
              "safeMode": false,
              "hooks": {"disabledIds": [], "denylistedClasses": [], "startup": {}}
            }
            """.formatted(worktreeId);
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
