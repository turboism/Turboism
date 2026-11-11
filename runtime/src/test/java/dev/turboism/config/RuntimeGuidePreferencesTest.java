package dev.turboism.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeGuidePreferencesTest {

    @TempDir
    Path home;

    @Test
    void freshInstallShowsTheGuideOnceAndStoresTheDismissal() throws Exception {
        final AtomicInteger diagnostics = new AtomicInteger();
        final RuntimeGuidePreferences preferences =
                new RuntimeGuidePreferences(home, code -> diagnostics.incrementAndGet());

        assertTrue(preferences.shouldShowGuide(), "a materialized default document opens the guide");
        assertTrue(Files.readString(home.resolve("config.json")).contains("\"showOnStartup\" : true"));

        preferences.markGuideSeen();
        assertFalse(preferences.shouldShowGuide());

        final RuntimeGuidePreferences reopened =
                new RuntimeGuidePreferences(home, code -> diagnostics.incrementAndGet());
        assertFalse(reopened.shouldShowGuide(), "a new reader must see the stored dismissal");
        reopened.markGuideSeen();
        assertEquals(0, diagnostics.get(), "a recorded dismissal is never rewritten as a failure");
    }

    @Test
    void upgradeWithoutTheSectionStillOpensTheGuide() throws Exception {
        Files.writeString(home.resolve("config.json"), """
            {
              "format": "turboism.runtime.config",
              "schemaVersion": 1,
              "worktreeId": "upgrade-test",
              "pluginDirs": ["plugins"]
            }
            """);
        final RuntimeGuidePreferences preferences = new RuntimeGuidePreferences(home, ignored -> {});

        assertTrue(preferences.shouldShowGuide());
        preferences.markGuideSeen();

        assertFalse(preferences.shouldShowGuide());
        assertTrue(Files.readString(home.resolve("config.json")).contains("\"showOnStartup\" : false"));
    }
}
