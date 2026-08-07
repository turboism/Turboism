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
    void assembledConfigNeverRendersThePassword() {
        final WebDavConfig assembled = WebDavSettingsDialog.assemble(
            CURRENT, true, "https://dav.example", "alice", "hunter2".toCharArray(),
            "/backup", true, 2, 500, 30,
            dev.turboism.plugin.backup.webdav.WebDavConfig.RemoteTrigger.SAVE_TRIGGERED);
        assertTrue(!assembled.toString().contains("hunter2"), "toString must redact the password");
    }
}
