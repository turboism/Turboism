package dev.turboism.home;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
        assertEquals("log", Files.readString(legacy.resolve("logs/plugin.log")));
        assertFalse(Files.exists(home.resolve("logs/dev.example.plugin")));
        assertFalse(Files.exists(legacy.resolve("data/imports/work.json")));
    }

    /**
     * {@code dev.turboism.plugin.backup} became {@code dev.turboism.plugin.webdav} in {@code v0.44.0}.
     * The typed-config root is keyed by plugin id, so an upgraded installation holds the WebDAV
     * document under the retired id and the renamed plugin would start from defaults (inventory §2.4,
     * gap G10).
     */
    @Test
    void copiesRenamedPluginConfigToTheCurrentIdAndKeepsThePreUpgradeImage() throws Exception {
        final Path previous = home.resolve("config/dev.turboism.plugin.backup/backup/webdav.cfg");
        Files.createDirectories(previous.getParent());
        final byte[] released =
                "turboism-typed-config-v1\nschemaVersion=1\nrevision=1\ncount=0\n".getBytes(StandardCharsets.UTF_8);
        Files.write(previous, released);

        LegacyHomeMigration.migrate(home);

        final Path current = home.resolve("config/dev.turboism.plugin.webdav/backup/webdav.cfg");
        assertArrayEquals(released, Files.readAllBytes(current), "the renamed plugin must read the released document");
        assertArrayEquals(
                released,
                Files.readAllBytes(previous),
                "the pre-upgrade document must stay byte-identical for rollback");

        LegacyHomeMigration.migrate(home);
        assertArrayEquals(
                released, Files.readAllBytes(current), "a second start must not duplicate or change the copy");
    }

    @Test
    void renamedPluginConfigNeverReplacesADocumentTheNewPluginAlreadyWrote() throws Exception {
        final Path previous = home.resolve("config/dev.turboism.plugin.backup/backup/webdav.cfg");
        Files.createDirectories(previous.getParent());
        Files.writeString(previous, "retired-copy");
        final Path current = home.resolve("config/dev.turboism.plugin.webdav/backup/webdav.cfg");
        Files.createDirectories(current.getParent());
        Files.writeString(current, "authoritative");

        LegacyHomeMigration.migrate(home);

        assertEquals("authoritative", Files.readString(current));
        assertEquals("retired-copy", Files.readString(previous));
    }

    @Test
    void renamedPluginConfigSkipsSymbolicLinksInsteadOfFollowingThem() throws Exception {
        final Path previousDirectory = home.resolve("config/dev.turboism.plugin.backup/backup");
        Files.createDirectories(previousDirectory);
        final Path outside = home.resolve("outside/secret.cfg");
        Files.createDirectories(outside.getParent());
        Files.writeString(outside, "secret");
        final Path link = previousDirectory.resolve("webdav.cfg");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException unsupported) {
            return; // the platform/filesystem cannot create symbolic links here
        }

        LegacyHomeMigration.migrate(home);

        assertFalse(
                Files.exists(home.resolve("config/dev.turboism.plugin.webdav/backup/webdav.cfg")),
                "a symlinked document must never be copied into the current plugin directory");
        assertEquals("secret", Files.readString(outside), "a skipped link must not be rewritten either");
    }
}
