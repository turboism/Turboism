package dev.turboism.plugin.backup.b1.application;

import dev.turboism.plugin.backup.webdav.WebDavConfig;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless-safe coverage of the settings dialog's form logic: value assembly,
 * validation, and the password-keep behavior. The Swing surface itself is
 * exercised only in the interactive session.
 */
final class WebDavSettingsDialogTest {

    private static final WebDavConfig CURRENT = new WebDavConfig(
        true, URI.create("https://dav.example"), "alice", "stored-password",
        "/backup", true, 2, 500, 30);

    @Test
    void assembleBuildsAValidatedConfigFromFormValues() {
        final WebDavConfig assembled = WebDavSettingsDialog.assemble(
            CURRENT, true, " https://dav.example/ ", "alice", "new-password".toCharArray(),
            "/turboism-backup ", false, 4, 1000, 60,
            dev.turboism.plugin.backup.webdav.WebDavConfig.RemoteTrigger.SAVE_TRIGGERED);
        assertEquals(URI.create("https://dav.example/"), assembled.url());
        assertEquals("alice", assembled.username());
        assertEquals("new-password", assembled.password());
        assertEquals("/turboism-backup", assembled.remotePath(), "remotePath must be normalized");
        assertEquals(4, assembled.retryMax());
        assertEquals(1000, assembled.retryBaseDelayMs());
        assertEquals(60, assembled.timeoutSeconds());
        assertTrue(!assembled.verifyTls());
    }

    @Test
    void emptyPasswordKeepsTheStoredPassword() {
        final WebDavConfig assembled = WebDavSettingsDialog.assemble(
            CURRENT, true, "https://dav.example", "alice", new char[0],
            "/backup", true, 2, 500, 30,
            dev.turboism.plugin.backup.webdav.WebDavConfig.RemoteTrigger.SAVE_TRIGGERED);
        assertEquals("stored-password", assembled.password(),
            "an empty password box must not wipe the stored password");
        final WebDavConfig withoutCurrent = WebDavSettingsDialog.assemble(
            null, true, "https://dav.example", "", null,
            "/backup", true, 2, 500, 30,
            dev.turboism.plugin.backup.webdav.WebDavConfig.RemoteTrigger.SAVE_TRIGGERED);
        assertEquals("", withoutCurrent.password());
    }

    @Test
    void placeholderOnlyPasswordKeepsTheStoredPassword() {
        final WebDavConfig assembled = WebDavSettingsDialog.assemble(
            CURRENT, true, "https://dav.example", "alice",
            WebDavSettingsDialog.PASSWORD_PLACEHOLDER.toCharArray(),
            "/backup", true, 2, 500, 30,
            dev.turboism.plugin.backup.webdav.WebDavConfig.RemoteTrigger.SAVE_TRIGGERED);
        assertEquals("stored-password", assembled.password(),
            "a placeholder-only password box must keep the stored password");
    }

    @Test
    void passwordPlaceholderAppearsOnlyWhenAPasswordIsStored() {
        assertEquals("********", WebDavSettingsDialog.PASSWORD_PLACEHOLDER);
        assertEquals("********", WebDavSettingsDialog.initialPasswordText(CURRENT),
            "a stored password must show the placeholder");
        assertEquals("", WebDavSettingsDialog.initialPasswordText(new WebDavConfig(
            true, URI.create("https://dav.example"), "alice", "",
            "/backup", true, 2, 500, 30)), "no stored password means no placeholder");
        assertTrue(WebDavSettingsDialog.isUnchangedPassword(
            WebDavSettingsDialog.PASSWORD_PLACEHOLDER.toCharArray()));
        assertTrue(WebDavSettingsDialog.isUnchangedPassword(new char[0]));
        assertFalse(WebDavSettingsDialog.isUnchangedPassword("typed".toCharArray()));
    }

    @Test
    void eyeToggleFlipsBetweenMaskedAndPlainEcho() {
        assertEquals(0, WebDavSettingsDialog.toggleEchoChar(WebDavSettingsDialog.PASSWORD_ECHO));
        assertEquals(WebDavSettingsDialog.PASSWORD_ECHO,
            WebDavSettingsDialog.toggleEchoChar((char) 0));
    }

    @Test
    void assembleRejectsInvalidUrlsAndRanges() {
        assertThrows(IllegalArgumentException.class, () -> WebDavSettingsDialog.assemble(
            CURRENT, true, "ftp://host", "", null, "/backup", true, 2, 500, 30,
            dev.turboism.plugin.backup.webdav.WebDavConfig.RemoteTrigger.SAVE_TRIGGERED));
        assertThrows(IllegalArgumentException.class, () -> WebDavSettingsDialog.assemble(
            CURRENT, true, "http://user:pass@host/", "", null, "/backup", true, 2, 500, 30,
            dev.turboism.plugin.backup.webdav.WebDavConfig.RemoteTrigger.SAVE_TRIGGERED));
        assertThrows(IllegalArgumentException.class, () -> WebDavSettingsDialog.assemble(
            CURRENT, true, "https://dav.example", "", null, "/backup", true, 11, 500, 30,
            dev.turboism.plugin.backup.webdav.WebDavConfig.RemoteTrigger.SAVE_TRIGGERED));
        assertThrows(IllegalArgumentException.class, () -> WebDavSettingsDialog.assemble(
            CURRENT, true, "https://dav.example", "", null, "../escape", true, 2, 500, 30,
            dev.turboism.plugin.backup.webdav.WebDavConfig.RemoteTrigger.SAVE_TRIGGERED));
        assertThrows(IllegalArgumentException.class, () -> WebDavSettingsDialog.assemble(
            CURRENT, true, "https://dav.example", "", null, "/backup", true, 2, 500, 0,
            dev.turboism.plugin.backup.webdav.WebDavConfig.RemoteTrigger.SAVE_TRIGGERED));
    }

    @Test
    void assembleCarriesTheRemoteTrigger() {
        final dev.turboism.plugin.backup.webdav.WebDavConfig auto = WebDavSettingsDialog.assemble(
            CURRENT, true, "https://dav.example", "alice", new char[0],
            "/backup", true, 2, 500, 30,
            dev.turboism.plugin.backup.webdav.WebDavConfig.RemoteTrigger.AUTO_BACKUP_SYNC);
        assertEquals(dev.turboism.plugin.backup.webdav.WebDavConfig.RemoteTrigger.AUTO_BACKUP_SYNC,
            auto.remoteTrigger());
        final dev.turboism.plugin.backup.webdav.WebDavConfig saved = WebDavSettingsDialog.assemble(
            CURRENT, true, "https://dav.example", "alice", new char[0],
            "/backup", true, 2, 500, 30,
            dev.turboism.plugin.backup.webdav.WebDavConfig.RemoteTrigger.SAVE_TRIGGERED);
        assertEquals(dev.turboism.plugin.backup.webdav.WebDavConfig.RemoteTrigger.SAVE_TRIGGERED,
            saved.remoteTrigger());
    }

    @Test
    void remoteTriggerModesAreLocalizedWithEnumNameFallback() {
        final dev.turboism.sdk.i18n.PluginLocalization english = new MapLocalization(java.util.Map.of(
            "backup.remote-trigger.save-triggered", "On local save",
            "backup.remote-trigger.auto-backup-sync", "Follow auto-backup",
            "backup.dialog.remote-trigger-label", "Remote trigger"));
        assertEquals("On local save", WebDavSettingsDialog.remoteTriggerText(
            english, dev.turboism.plugin.backup.webdav.WebDavConfig.RemoteTrigger.SAVE_TRIGGERED));
        assertEquals("Follow auto-backup", WebDavSettingsDialog.remoteTriggerText(
            english, dev.turboism.plugin.backup.webdav.WebDavConfig.RemoteTrigger.AUTO_BACKUP_SYNC));
        assertEquals("Remote trigger", WebDavSettingsDialog.remoteTriggerLabel(english));

        final dev.turboism.sdk.i18n.PluginLocalization chinese = new MapLocalization(java.util.Map.of(
            "backup.remote-trigger.save-triggered", "本地保存触发",
            "backup.remote-trigger.auto-backup-sync", "随自动保存触发",
            "backup.dialog.remote-trigger-label", "远程保存方式"));
        assertEquals("本地保存触发", WebDavSettingsDialog.remoteTriggerText(
            chinese, dev.turboism.plugin.backup.webdav.WebDavConfig.RemoteTrigger.SAVE_TRIGGERED));
        assertEquals("随自动保存触发", WebDavSettingsDialog.remoteTriggerText(
            chinese, dev.turboism.plugin.backup.webdav.WebDavConfig.RemoteTrigger.AUTO_BACKUP_SYNC));
        assertEquals("远程保存方式", WebDavSettingsDialog.remoteTriggerLabel(chinese));

        final dev.turboism.sdk.i18n.PluginLocalization empty = new MapLocalization(java.util.Map.of());
        assertEquals("SAVE_TRIGGERED", WebDavSettingsDialog.remoteTriggerText(
            empty, dev.turboism.plugin.backup.webdav.WebDavConfig.RemoteTrigger.SAVE_TRIGGERED),
            "a missing key must fall back to the enum name");
        assertEquals("Remote trigger", WebDavSettingsDialog.remoteTriggerLabel(empty));
    }

    @Test
    void bothCatalogsDeclareTheRemoteTriggerKeys() throws Exception {
        for (String catalog : new String[] {"messages.properties", "messages_zh_Hans.properties"}) {
            final java.util.Properties properties = new java.util.Properties();
            try (var in = WebDavSettingsDialog.class.getClassLoader().getResourceAsStream(
                    "META-INF/turboism/i18n/" + catalog)) {
                assertTrue(in != null, catalog + " must be on the classpath");
                properties.load(in);
            }
            for (String key : new String[] {
                "backup.remote-trigger.save-triggered",
                "backup.remote-trigger.auto-backup-sync",
                "backup.dialog.remote-trigger-label"
            }) {
                assertTrue(properties.containsKey(key), catalog + " must declare " + key);
                assertTrue(!properties.getProperty(key).isBlank(), catalog + ": " + key + " must not be blank");
            }
        }
    }

    /** Headless test localization: returns the mapped text, contains() on the map. */
    private static final class MapLocalization implements dev.turboism.sdk.i18n.PluginLocalization {
        private final java.util.Map<String, String> texts;

        MapLocalization(final java.util.Map<String, String> texts) {
            this.texts = texts;
        }

        @Override
        public String text(final String key) {
            return texts.get(key);
        }

        @Override
        public String format(final String key, final Object... arguments) {
            return texts.get(key);
        }

        @Override
        public boolean contains(final String key) {
            return texts.containsKey(key);
        }

        @Override
        public java.util.Locale locale() {
            return java.util.Locale.ROOT;
        }
    }

    @Test
    void assembledConfigNeverRendersThePassword() {
        final WebDavConfig assembled = WebDavSettingsDialog.assemble(
            CURRENT, true, "https://dav.example", "alice", "hunter2".toCharArray(),
            "/backup", true, 2, 500, 30,
            dev.turboism.plugin.backup.webdav.WebDavConfig.RemoteTrigger.SAVE_TRIGGERED);
        assertTrue(!assembled.toString().contains("hunter2"), "toString must redact the password");
    }
}
