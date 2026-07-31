package dev.turboism.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeStartupConfigTest {

    @TempDir
    Path temporaryHome;

    @Test
    void missingGlobalConfigKeepsEveryStartupSuppressionDisabled() {
        final RuntimeStartupConfig config = RuntimeStartupConfig.load(temporaryHome);

        assertFalse(config.safeMode());
        assertFalse(config.skipStartupUpdateCheck());
        assertFalse(config.skipStartupSplash());
        assertFalse(config.skipStartupInformation());
    }


    @Test
    void loadsExplicitStartupControlsFromTheCanonicalGlobalConfig() throws Exception {
        Files.writeString(temporaryHome.resolve("config.json"), """
            {
              "format": "turboism.runtime.config",
              "schemaVersion": 1,
              "worktreeId": "startup-test",
              "safeMode": false,
              "hooks": {
                "startup": {
                  "skipUpdateCheck": true,
                  "skipSplash": true,
                  "skipInformation": true
                }
              }
            }
            """);

        final RuntimeStartupConfig config = RuntimeStartupConfig.load(temporaryHome);

        assertFalse(config.safeMode());
        assertTrue(config.skipStartupUpdateCheck());
        assertTrue(config.skipStartupSplash());
        assertTrue(config.skipStartupInformation());
    }

    @Test
    void ignoresLegacyRuntimeConfig() throws Exception {
        final Path legacyDirectory = Files.createDirectories(temporaryHome.resolve("config"));
        Files.writeString(legacyDirectory.resolve("runtime.json"), configJson(true));

        final RuntimeStartupConfig config = RuntimeStartupConfig.load(temporaryHome);

        assertFalse(config.safeMode());
    }

    private static String configJson(final boolean safeMode) {
        return """
            {
              "format": "turboism.runtime.config",
              "schemaVersion": 1,
              "worktreeId": "startup-test",
              "safeMode": %s,
              "hooks": { "startup": {} }
            }
            """.formatted(safeMode);
    }


    @Test
    void rejectsTheWholeStartupPolicyWhenAnyStartupFieldHasTheWrongType() throws Exception {
        Files.writeString(temporaryHome.resolve("config.json"), """
            {
              "format": "turboism.runtime.config",
              "schemaVersion": 1,
              "worktreeId": "startup-test",
              "safeMode": false,
              "hooks": {
                "startup": {
                  "skipUpdateCheck": "true",
                  "skipSplash": true,
                  "skipInformation": true
                }
              }
            }
            """);
        final List<String> diagnostics = new ArrayList<>();

        final RuntimeStartupConfig config = RuntimeStartupConfig.load(temporaryHome, diagnostics::add);

        assertFalse(config.skipStartupUpdateCheck());
        assertFalse(config.skipStartupSplash());
        assertFalse(config.skipStartupInformation());
        assertEquals(List.of("RUNTIME_STARTUP_CONFIG_INVALID"), diagnostics);
    }

    @Test
    void rejectsUnknownNestedStartupFieldsAsOneInvalidPolicy() throws Exception {
        Files.writeString(temporaryHome.resolve("config.json"), """
            {
              "format": "turboism.runtime.config",
              "schemaVersion": 1,
              "worktreeId": "startup-test",
              "hooks": {
                "startup": {
                  "skipUpdateCheck": true,
                  "unexpected": true
                }
              }
            }
            """);
        final List<String> diagnostics = new ArrayList<>();

        final RuntimeStartupConfig config = RuntimeStartupConfig.load(temporaryHome, diagnostics::add);

        assertFalse(config.skipStartupUpdateCheck());
        assertFalse(config.skipStartupSplash());
        assertFalse(config.skipStartupInformation());
        assertEquals(List.of("RUNTIME_STARTUP_CONFIG_INVALID"), diagnostics);
    }


    @Test
    void safeModeOverridesEveryRequestedStartupSuppression() throws Exception {
        Files.writeString(temporaryHome.resolve("config.json"), """
            {
              "format": "turboism.runtime.config",
              "schemaVersion": 1,
              "worktreeId": "startup-test",
              "safeMode": true,
              "hooks": {
                "startup": {
                  "skipUpdateCheck": true,
                  "skipSplash": true,
                  "skipInformation": true
                }
              }
            }
            """);

        final RuntimeStartupConfig config = RuntimeStartupConfig.load(temporaryHome);

        assertTrue(config.safeMode());
        assertTrue(config.requestedSkipStartupUpdateCheck());
        assertTrue(config.requestedSkipStartupSplash());
        assertTrue(config.requestedSkipStartupInformation());
        assertFalse(config.skipStartupUpdateCheck());
        assertFalse(config.skipStartupSplash());
        assertFalse(config.skipStartupInformation());
    }
}
