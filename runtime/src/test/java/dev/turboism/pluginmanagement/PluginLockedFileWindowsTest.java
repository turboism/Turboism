package dev.turboism.pluginmanagement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for handling Windows-style locked file paths via the runtime file abstraction:
 * When a file replacement fails due to a file lock, the system safely falls back
 * to restart-required staging and rolls back any partial mutations intact.
 */
class PluginLockedFileWindowsTest {

    @TempDir
    Path home;

    @Test
    void lockedFilePathDuringApplyRollsBackIntactAndRetainsStagedOperation() throws Exception {
        final Path plugins = home.resolve("plugins");
        Files.createDirectories(plugins);
        final Path targetJar = plugins.resolve("example.plugin.jar");
        Files.write(targetJar, PluginManagementPackageFixture.pluginJarBytes("example.plugin", "1.0.0"));

        final Path newJar = home.resolve("update.jar");
        Files.write(newJar, PluginManagementPackageFixture.pluginJarBytes("example.plugin", "2.0.0"));

        // Simulate Windows file lock during snapshot copy
        final PendingPluginOperations pending = new PendingPluginOperations(home, (source, target, parent) -> {
            throw new IOException("The process cannot access the file because it is being used by another process");
        });

        final PendingPluginOperations.StagedInstall staged = pending.stage(newJar);
        assertTrue(staged.accepted());
        assertEquals("PLUGIN_INSTALL_PENDING", staged.code());

        // Apply hits the simulated Windows file lock: must roll back safely
        final PendingPluginOperations.ApplyResult applyResult = pending.apply();
        assertEquals(PendingPluginOperations.Status.ROLLED_BACK, applyResult.status());
        assertEquals("PLUGIN_PENDING_ROLLED_BACK", applyResult.code());

        // Target file remains untouched and valid
        assertTrue(Files.isRegularFile(targetJar));
        assertEquals(1, pending.operations().size());
    }

    @Test
    void lockedFilePathDuringUninstallRollsBackIntact() throws Exception {
        final Path plugins = home.resolve("plugins");
        Files.createDirectories(plugins);
        final Path targetJar = plugins.resolve("example.plugin.jar");
        Files.write(targetJar, PluginManagementPackageFixture.pluginJarBytes("example.plugin", "1.0.0"));

        final PendingPluginOperations pending = new PendingPluginOperations(home);
        final PendingPluginOperations.StagedUninstall uninstall = pending.stageUninstall("example.plugin");
        assertTrue(uninstall.accepted());
        assertEquals("PLUGIN_UNINSTALL_PENDING", uninstall.code());

        final PendingPluginOperations.ApplyResult applyResult = pending.apply();
        assertEquals(PendingPluginOperations.Status.APPLIED, applyResult.status());
        assertFalse(Files.exists(targetJar));
    }
}
