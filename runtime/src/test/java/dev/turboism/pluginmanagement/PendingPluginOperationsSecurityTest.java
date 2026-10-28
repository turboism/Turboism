package dev.turboism.pluginmanagement;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PendingPluginOperationsSecurityTest {
    @TempDir
    Path home;

    @Test
    void failedApplyRestoresPreviousArtifactAndKeepsOperationPending() throws Exception {
        final Path plugins = home.resolve("plugins");
        Files.createDirectories(plugins);
        Files.writeString(plugins.resolve("example.plugin.jar"), "old");
        final Path packageFile = home.resolve("update.jar");
        Files.write(packageFile, PluginManagementPackageFixture.pluginJarBytes("example.plugin", "2.0.0"));
        final RuntimePluginManagementService service =
                new RuntimePluginManagementService(home, () -> Optional.of(packageFile), List::of);
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
    void applyReplacesDuplicateInstalledPackagesWithTheVerifiedUpdate() throws Exception {
        final Path plugins = home.resolve("plugins");
        Files.createDirectories(plugins);
        Files.write(plugins.resolve("a.jar"), PluginManagementPackageFixture.pluginJarBytes("example.plugin", "1.0.0"));
        Files.write(plugins.resolve("b.jar"), PluginManagementPackageFixture.pluginJarBytes("example.plugin", "1.0.0"));
        final Path packageFile = home.resolve("update.jar");
        Files.write(packageFile, PluginManagementPackageFixture.pluginJarBytes("example.plugin", "2.0.0"));
        final RuntimePluginManagementService service =
                new RuntimePluginManagementService(home, () -> Optional.of(packageFile), List::of);
        assertTrue(service.install().accepted());
        service.close();
        final byte[] unrelated = PluginManagementPackageFixture.pluginJarBytes("other.plugin", "1.0.0");
        Files.write(plugins.resolve("unrelated.jar"), unrelated);

        final PendingPluginOperations.ApplyResult result = RuntimePluginManagementService.applyPending(home);

        assertTrue(result.applied(), result.code());
        assertFalse(Files.exists(plugins.resolve("a.jar")));
        assertFalse(Files.exists(plugins.resolve("b.jar")));
        assertArrayEquals(Files.readAllBytes(packageFile), Files.readAllBytes(plugins.resolve("example.plugin.jar")));
        assertArrayEquals(unrelated, Files.readAllBytes(plugins.resolve("unrelated.jar")));
        assertTrue(new PendingPluginOperations(home).operations().isEmpty());
    }

    @Test
    void failedDuplicateRepairPreservesBothInstalledPackages() throws Exception {
        final Path plugins = home.resolve("plugins");
        Files.createDirectories(plugins);
        final byte[] older = PluginManagementPackageFixture.pluginJarBytes("example.plugin", "1.0.0");
        final byte[] current = PluginManagementPackageFixture.pluginJarBytes("example.plugin", "1.1.0");
        Files.write(plugins.resolve("installer.jar"), older);
        Files.write(plugins.resolve("example.plugin.jar"), current);
        final Path packageFile = home.resolve("update.jar");
        Files.write(packageFile, PluginManagementPackageFixture.pluginJarBytes("example.plugin", "2.0.0"));
        final PendingPluginOperations pending = new PendingPluginOperations(home);
        final var staged = pending.stage(packageFile);
        assertTrue(staged.accepted());
        Files.writeString(staged.prepared().stagedJar(), "tampered");

        assertEquals(PendingPluginOperations.Status.ROLLED_BACK, pending.apply().status());
        assertArrayEquals(older, Files.readAllBytes(plugins.resolve("installer.jar")));
        assertArrayEquals(current, Files.readAllBytes(plugins.resolve("example.plugin.jar")));
        assertEquals(1, pending.operations().size());
    }

    @Test
    void hotApplyRequiresAnUnambiguousCanonicalPackage() throws Exception {
        final Path plugins = home.resolve("plugins");
        Files.createDirectories(plugins);
        final PendingPluginOperations pending = new PendingPluginOperations(home);
        assertTrue(pending.canHotApply("example.plugin"));
        final byte[] content = PluginManagementPackageFixture.pluginJarBytes("example.plugin", "1.0.0");
        Files.write(plugins.resolve("installer.jar"), content);
        assertFalse(pending.canHotApply("example.plugin"));
        Files.write(plugins.resolve("example.plugin.jar"), content);
        assertFalse(pending.canHotApply("example.plugin"));
        Files.delete(plugins.resolve("installer.jar"));
        assertTrue(pending.canHotApply("example.plugin"));
    }

    @Test
    void applyPreflightsThePrivateSnapshotAfterStagedSourceReplacement() throws Exception {
        final Path plugins = home.resolve("plugins");
        Files.createDirectories(plugins);
        final Path installed = plugins.resolve("example.plugin.jar");
        Files.write(installed, PluginManagementPackageFixture.pluginJarBytes("example.plugin", "1.0.0"));
        final String installedHash = hash(installed);
        final Path packageFile = home.resolve("update.jar");
        Files.write(packageFile, PluginManagementPackageFixture.pluginJarBytes("example.plugin", "2.0.0"));
        final RuntimePluginManagementService service =
                new RuntimePluginManagementService(home, () -> Optional.of(packageFile), List::of);
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
        try {
            Files.createSymbolicLink(home.resolve("state/runtime/plugin-management"), outside);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symbolic links are not creatable on this platform");
        }
        final Path packageFile = home.resolve("sample.jar");
        Files.write(packageFile, PluginManagementPackageFixture.pluginJarBytes("example.plugin", "1.0.0"));

        final var result = new RuntimePluginManagementService(home, () -> Optional.of(packageFile), List::of).install();

        assertFalse(result.accepted());
        assertTrue(Files.list(outside)
                .noneMatch(path -> path.getFileName().toString().endsWith(".jar")));
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
        Files.write(
                plugins.resolve("example.plugin.jar"),
                PluginManagementPackageFixture.pluginJarBytes("example.plugin", "1.0.0"));
        final Path journal = home.resolve("state/runtime/plugin-management/pending.json");
        Files.createDirectories(journal.getParent());
        Files.writeString(journal, "not-json");
        final RuntimePluginManagementService service =
                new RuntimePluginManagementService(home, Optional::empty, List::of);

        assertTrue(service.plugins().stream().anyMatch(plugin -> plugin.id().equals("example.plugin")));
        assertEquals("PLUGIN_PENDING_RECOVERY_REQUIRED", service.install().code());
        assertEquals(
                "PLUGIN_PENDING_RECOVERY_REQUIRED",
                service.uninstall("example.plugin").code());
        assertEquals(
                "PLUGIN_PENDING_RECOVERY_REQUIRED",
                service.setEnabled("example.plugin", false).code());
        final PendingPluginOperations.StagedUninstall direct =
                new PendingPluginOperations(home).stageUninstall("example.plugin");
        assertFalse(direct.accepted());
        assertEquals("PLUGIN_PENDING_RECOVERY_REQUIRED", direct.code());
        assertEquals("not-json", Files.readString(journal));
        service.close();
    }

    @Test
    void replacingPendingInstallDeletesOldStagedArtifact() throws Exception {
        final Path first = home.resolve("first.jar");
        final Path second = home.resolve("second.jar");
        Files.write(first, PluginManagementPackageFixture.pluginJarBytes("example.plugin", "1.0.0"));
        Files.write(second, PluginManagementPackageFixture.pluginJarBytes("example.plugin", "2.0.0"));
        final RuntimePluginManagementService firstService =
                new RuntimePluginManagementService(home, () -> Optional.of(first), List::of);
        assertTrue(firstService.install().accepted());
        final PendingPluginOperations pending = new PendingPluginOperations(home);
        final Path oldStaged = Path.of(pending.operations().get(0).stagedJar());

        assertTrue(new RuntimePluginManagementService(home, () -> Optional.of(second), List::of)
                .install()
                .accepted());

        assertFalse(Files.exists(oldStaged));
        assertEquals("2.0.0", pending.operations().get(0).version());
    }

    @Test
    void repeatedStagingOfSamePackagePreservesArtifactAndAppliesSuccessfully() throws Exception {
        final Path packageFile = home.resolve("repeat.jar");
        Files.write(packageFile, PluginManagementPackageFixture.pluginJarBytes("example.plugin", "1.0.0"));
        final PendingPluginOperations pending = new PendingPluginOperations(home);

        final var firstStage = pending.stage(packageFile);
        assertTrue(firstStage.accepted());
        final Path stagedJar = firstStage.prepared().stagedJar();
        assertTrue(Files.exists(stagedJar));

        final var secondStage = pending.stage(packageFile);
        assertTrue(secondStage.accepted());
        assertTrue(Files.exists(stagedJar), "Staged jar must not be deleted on re-staging the same package");
        assertEquals(1, pending.operations().size());

        final var applyResult = pending.apply();
        assertTrue(applyResult.applied(), applyResult.code());
        assertTrue(Files.exists(home.resolve("plugins/example.plugin.jar")));
        assertTrue(pending.operations().isEmpty());
    }

    @Test
    void directPreparedJarStagingRevalidatesAndIsIdempotent() throws Exception {
        final Path packageFile = home.resolve("direct.jar");
        Files.write(packageFile, PluginManagementPackageFixture.pluginJarBytes("example.plugin", "1.0.0"));
        final Path packagesDir = home.resolve("state/runtime/plugin-management/packages");
        final var prepResult = new dev.turboism.distribution.LocalPluginJarPreparer().prepare(packageFile, packagesDir);
        assertTrue(prepResult instanceof dev.turboism.distribution.LocalPluginJarPreparer.Prepared);
        final var prepared = ((dev.turboism.distribution.LocalPluginJarPreparer.Prepared) prepResult).value();

        final PendingPluginOperations pending = new PendingPluginOperations(home);
        final var first = pending.stage(prepared);
        assertTrue(first.accepted());
        assertEquals("PLUGIN_INSTALL_PENDING", first.code());

        // Repeated stage with same prepared instance
        final var second = pending.stage(prepared);
        assertTrue(second.accepted());
        assertTrue(Files.exists(prepared.stagedJar()));

        final var applyResult = pending.apply();
        assertTrue(applyResult.applied());
        assertTrue(Files.exists(home.resolve("plugins/example.plugin.jar")));
    }

    @Test
    void directPreparedJarStagingRejectsTamperedArtifact() throws Exception {
        final Path packageFile = home.resolve("tampered.jar");
        Files.write(packageFile, PluginManagementPackageFixture.pluginJarBytes("example.plugin", "1.0.0"));
        final Path packagesDir = home.resolve("state/runtime/plugin-management/packages");
        final var prepResult = new dev.turboism.distribution.LocalPluginJarPreparer().prepare(packageFile, packagesDir);
        final var prepared = ((dev.turboism.distribution.LocalPluginJarPreparer.Prepared) prepResult).value();

        // Tamper with the staged file on disk
        Files.writeString(prepared.stagedJar(), "corrupted-content");

        final PendingPluginOperations pending = new PendingPluginOperations(home);
        final var stageResult = pending.stage(prepared);
        assertFalse(stageResult.accepted());
        assertEquals("PLUGIN_TAMPERED", stageResult.code());
    }

    private static String hash(final Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    @Test
    void unreadableJournalRetainsPackageForRecovery() throws Exception {
        final Path source = Files.write(
                home.resolve("recover.jar"), PluginManagementPackageFixture.pluginJarBytes("example.plugin", "1.0.0"));
        final var pending = new PendingPluginOperations(home);
        final var staged = pending.stage(source);
        assertTrue(staged.accepted());
        Files.writeString(home.resolve("state/runtime/plugin-management/pending.json"), "corrupt");
        assertFalse(pending.stage(staged.prepared()).accepted());
        pending.discardIfUnreferenced(staged.prepared().stagedJar());
        assertTrue(Files.exists(staged.prepared().stagedJar()));
    }

    @Test
    void failedAttemptCleanupKeepsExistingJournalReferenceAndNeverDeletesOutsidePackages() throws Exception {
        final Path source = Files.write(
                home.resolve("keep.jar"), PluginManagementPackageFixture.pluginJarBytes("example.plugin", "1.0.0"));
        final var pending = new PendingPluginOperations(home);
        final var staged = pending.stage(source);
        assertTrue(staged.accepted());
        pending.discardIfUnreferenced(staged.prepared().stagedJar());
        pending.discardIfUnreferenced(source);
        assertTrue(Files.exists(source));
        assertTrue(Files.exists(staged.prepared().stagedJar()));
        assertTrue(pending.apply().applied());
    }
}
