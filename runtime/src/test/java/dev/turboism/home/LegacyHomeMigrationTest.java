package dev.turboism.home;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LegacyHomeMigrationTest {

    @TempDir
    Path home;

    @Test
    void migratesTypedConfigAndRemainingPluginDataWithoutOverwritingNewFiles() throws Exception {
        final Path legacy = home.resolve("plugin-data/dev.example.plugin");
        Files.createDirectories(legacy.resolve("data/typed-config/settings"));
        Files.writeString(legacy.resolve("data/typed-config/settings/ui.cfg"), "old-config");
        Files.createDirectories(legacy.resolve("data/imports"));
        Files.writeString(legacy.resolve("data/imports/work.json"), "business-data");
        Files.createDirectories(legacy.resolve("cache"));
        Files.writeString(legacy.resolve("cache/index.bin"), "cache");
        Files.createDirectories(legacy.resolve("state"));
        Files.writeString(legacy.resolve("state/session.json"), "state");
        Files.createDirectories(legacy.resolve("logs"));
        Files.writeString(legacy.resolve("logs/plugin.log"), "log");

        final Path existing = home.resolve("config/dev.example.plugin/settings/ui.cfg");
        Files.createDirectories(existing.getParent());
        Files.writeString(existing, "new-config");

        LegacyHomeMigration.migrate(home);

        assertEquals("new-config", Files.readString(existing));
        assertTrue(Files.exists(legacy.resolve("data/typed-config/settings/ui.cfg")));
        assertEquals("business-data", Files.readString(home.resolve("data/dev.example.plugin/imports/work.json")));
        assertEquals("cache", Files.readString(home.resolve("cache/dev.example.plugin/index.bin")));
        assertEquals("state", Files.readString(home.resolve("state/dev.example.plugin/session.json")));
        assertEquals("log", Files.readString(home.resolve("logs/dev.example.plugin/plugin.log")));
        assertFalse(Files.exists(legacy.resolve("data/imports/work.json")));
    }
}
