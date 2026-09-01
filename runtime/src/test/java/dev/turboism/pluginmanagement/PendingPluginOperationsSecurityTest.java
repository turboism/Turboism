package dev.turboism.pluginmanagement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

final class PendingPluginOperationsSecurityTest {
    @TempDir Path home;

    @Test
    void failedApplyRestoresPreviousArtifactAndKeepsOperationPending() throws Exception {
        final Path plugins = home.resolve("plugins");
        Files.createDirectories(plugins);
        Files.writeString(plugins.resolve("example.plugin.jar"), "old");
        final Path packageFile = home.resolve("update.turboism-plugin");
        Files.write(packageFile, PluginManagementPackageFixture.packageBytes("example.plugin", "2.0.0"));
        final RuntimePluginManagementService service = new RuntimePluginManagementService(
            home, () -> Optional.of(packageFile), List::of
        );
        assertTrue(service.install().accepted());
        final PendingPluginOperations pending = new PendingPluginOperations(home);
        final Path staged = Path.of(pending.operations().get(0).stagedJar());
        Files.writeString(staged, "tampered");

        final PendingPluginOperations.ApplyResult result = pending.apply();

        assertFalse(result.applied());
        assertEquals("old", Files.readString(plugins.resolve("example.plugin.jar")));
        assertEquals(1, pending.operations().size());
        assertTrue(Files.exists(staged));
    }

    @Test
    void applyRejectsDuplicateInstalledPackagesWithoutChangingEither() throws Exception {
        final Path plugins = home.resolve("plugins");
        Files.createDirectories(plugins);
        Files.write(plugins.resolve("a.jar"), PluginManagementPackageFixture.pluginJarBytes("example.plugin", "1.0.0"));
        Files.write(plugins.resolve("b.jar"), PluginManagementPackageFixture.pluginJarBytes("example.plugin", "1.0.0"));
        final Path packageFile = home.resolve("update.turboism-plugin");
        Files.write(packageFile, PluginManagementPackageFixture.packageBytes("example.plugin", "2.0.0"));
        final RuntimePluginManagementService service = new RuntimePluginManagementService(
            home, () -> Optional.of(packageFile), List::of
        );
        assertTrue(service.install().accepted());
        final String beforeA = hash(plugins.resolve("a.jar"));
        final String beforeB = hash(plugins.resolve("b.jar"));

        final PendingPluginOperations.ApplyResult result = RuntimePluginManagementService.applyPending(home);

        assertFalse(result.applied());
        assertEquals(beforeA, hash(plugins.resolve("a.jar")));
        assertEquals(beforeB, hash(plugins.resolve("b.jar")));
    }

    @Test
    void applyPreflightsThePrivateSnapshotAfterStagedSourceReplacement() throws Exception {
        final Path plugins = home.resolve("plugins");
        Files.createDirectories(plugins);
        final Path installed = plugins.resolve("example.plugin.jar");
        Files.write(installed, PluginManagementPackageFixture.pluginJarBytes("example.plugin", "1.0.0"));
        final String installedHash = hash(installed);
        final Path packageFile = home.resolve("update.tplugin");
        Files.write(packageFile, PluginManagementPackageFixture.packageBytes("example.plugin", "2.0.0"));
        final RuntimePluginManagementService service = new RuntimePluginManagementService(
            home, () -> Optional.of(packageFile), List::of
        );
        assertTrue(service.install().accepted());
        service.close();
        final PendingPluginOperations journal = new PendingPluginOperations(home);
        final Path staged = Path.of(journal.operations().get(0).stagedJar());
        final byte[] replacement = PluginManagementPackageFixture.pluginJarBytes("example.plugin", "3.0.0");
        final PendingPluginOperations pending = new PendingPluginOperations(home, (source, snapshot, parent) -> {
            final Path replacementPath = source.resolveSibling("replacement.jar");
            Files.write(replacementPath, replacement);
            Files.move(replacementPath, source, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            Files.copy(source, snapshot);
        });

        final PendingPluginOperations.ApplyResult result = pending.apply();

        assertEquals(PendingPluginOperations.Status.ROLLED_BACK, result.status());
        assertEquals(installedHash, hash(installed));
        assertTrue(Files.exists(staged));
        try (var entries = Files.list(plugins)) {
            assertFalse(entries.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void stagingSymlinkFailsClosedWithoutWritingOutsideHome() throws Exception {
        final Path outside = Files.createTempDirectory("plugin-outside-");
        Files.createDirectories(home.resolve("state/runtime"));
        Files.createSymbolicLink(home.resolve("state/runtime/plugin-management"), outside);
        final Path packageFile = home.resolve("sample.tplugin");
        Files.write(packageFile, PluginManagementPackageFixture.packageBytes("example.plugin", "1.0.0"));

        final var result = new RuntimePluginManagementService(
            home, () -> Optional.of(packageFile), List::of
        ).install();

        assertFalse(result.accepted());
        assertTrue(Files.list(outside).noneMatch(path -> path.getFileName().toString().endsWith(".jar")));
    }

    @Test
    void corruptedJournalRequiresRecoveryAndIsPreservedForRetry() throws Exception {
        final Path journal = home.resolve("state/runtime/plugin-management/pending.json");
        Files.createDirectories(journal.getParent());
        Files.writeString(journal, "not-json");

        final PendingPluginOperations.ApplyResult result = new PendingPluginOperations(home).apply();

        assertEquals(PendingPluginOperations.Status.RECOVERY_REQUIRED, result.status());
        assertTrue(Files.exists(journal));
    }

    @Test
    void corruptedJournalPreservesServiceListingAndBlocksMutations() throws Exception {
        final Path plugins = home.resolve("plugins");
        Files.createDirectories(plugins);
        Files.write(plugins.resolve("example.plugin.jar"),
            PluginManagementPackageFixture.pluginJarBytes("example.plugin", "1.0.0"));
        final Path journal = home.resolve("state/runtime/plugin-management/pending.json");
        Files.createDirectories(journal.getParent());
        Files.writeString(journal, "not-json");
        final RuntimePluginManagementService service = new RuntimePluginManagementService(
            home, Optional::empty, List::of
        );

        assertTrue(service.plugins().stream().anyMatch(plugin -> plugin.id().equals("example.plugin")));
        assertEquals("PLUGIN_PENDING_RECOVERY_REQUIRED", service.install().code());
        assertEquals("PLUGIN_PENDING_RECOVERY_REQUIRED", service.uninstall("example.plugin").code());
        assertEquals("PLUGIN_PENDING_RECOVERY_REQUIRED", service.setEnabled("example.plugin", false).code());
        final PendingPluginOperations.StagedUninstall direct = new PendingPluginOperations(home)
            .stageUninstall("example.plugin");
        assertFalse(direct.accepted());
        assertEquals("PLUGIN_PENDING_RECOVERY_REQUIRED", direct.code());
        assertEquals("not-json", Files.readString(journal));
        service.close();
    }

    @Test
    void replacingPendingInstallDeletesOldStagedArtifact() throws Exception {
        final Path first = home.resolve("first.tplugin");
        final Path second = home.resolve("second.tplugin");
        Files.write(first, PluginManagementPackageFixture.packageBytes("example.plugin", "1.0.0"));
        Files.write(second, PluginManagementPackageFixture.packageBytes("example.plugin", "2.0.0"));
        final RuntimePluginManagementService firstService = new RuntimePluginManagementService(
            home, () -> Optional.of(first), List::of
        );
        assertTrue(firstService.install().accepted());
        final PendingPluginOperations pending = new PendingPluginOperations(home);
        final Path oldStaged = Path.of(pending.operations().get(0).stagedJar());

        assertTrue(new RuntimePluginManagementService(home, () -> Optional.of(second), List::of).install().accepted());

        assertFalse(Files.exists(oldStaged));
        assertEquals("2.0.0", pending.operations().get(0).version());
    }

    private static String hash(final Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
