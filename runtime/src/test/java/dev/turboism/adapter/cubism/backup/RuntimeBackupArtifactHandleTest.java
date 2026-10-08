package dev.turboism.adapter.cubism.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.cubism.backup.BackupArtifactHandle;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.permission.PermissionIds;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeBackupArtifactHandleTest {

    @TempDir
    Path temporary;

    @Test
    void openStreamReadsTheIssuedArtifact() throws IOException {
        final Path dir = Files.createDirectories(temporary.resolve("backup"));
        final Path file = Files.writeString(dir.resolve("model_backup.cmo3"), "artifact-bytes");
        final BackupArtifactHandle handle =
                RuntimeBackupArtifactHandle.issue(file, dir, false, PermissionChecker.allowAll());
        assertEquals("model_backup.cmo3", handle.fileName());
        assertEquals("artifact-bytes".length(), handle.sizeBytes());
        assertFalse(handle.temporary());
        try (var in = handle.openStream()) {
            assertEquals("artifact-bytes", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void openStreamRejectsASymbolicLinkArtifact() throws IOException {
        final Path dir = Files.createDirectories(temporary.resolve("backup"));
        final Path outside = Files.writeString(temporary.resolve("secret.txt"), "secret");
        final Path link = dir.resolve("linked.cmo3");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
            Assumptions.assumeTrue(false, "symbolic links are not creatable on this platform");
        }
        final BackupArtifactHandle handle =
                RuntimeBackupArtifactHandle.issue(link, dir, false, PermissionChecker.allowAll());
        assertThrows(IOException.class, handle::openStream);
    }

    @Test
    void openStreamRejectsAnArtifactOutsideItsIssuingDirectory() throws IOException {
        final Path dir = Files.createDirectories(temporary.resolve("backup"));
        final Path other = Files.createDirectories(temporary.resolve("other"));
        final Path file = Files.writeString(other.resolve("escape.cmo3"), "escape");
        // A mis-issued handle must still be confined at use time, not at issue time.
        final BackupArtifactHandle handle =
                RuntimeBackupArtifactHandle.issue(file, dir, false, PermissionChecker.allowAll());
        assertThrows(IOException.class, handle::openStream);
    }

    @Test
    void openStreamFailsWhenTheArtifactWasDeleted() throws IOException {
        final Path dir = Files.createDirectories(temporary.resolve("backup"));
        final Path file = Files.writeString(dir.resolve("gone.cmo3"), "bytes");
        final BackupArtifactHandle handle =
                RuntimeBackupArtifactHandle.issue(file, dir, false, PermissionChecker.allowAll());
        Files.delete(file);
        assertThrows(IOException.class, handle::openStream);
    }

    @Test
    void openStreamAndDiscardRequireTheBackupObservePermission() {
        final AtomicInteger checks = new AtomicInteger();
        final PermissionChecker denying = (permissionId, operation) -> {
            checks.incrementAndGet();
            throw new CubismPermissionException("missing " + permissionId);
        };
        final BackupArtifactHandle handle =
                RuntimeBackupArtifactHandle.issue(temporary.resolve("model.cmo3"), temporary, true, denying);
        assertThrows(CubismPermissionException.class, handle::openStream);
        assertThrows(CubismPermissionException.class, handle::discard);
        assertEquals(2, checks.get());
    }

    @Test
    void discardRemovesTheTemporaryArtifactAndPrunesItsEmptyDirectory() throws IOException {
        final Path dir = Files.createDirectories(temporary.resolve("turboism-backup-test"));
        final Path file = Files.writeString(dir.resolve("model_backup.cmo3"), "temp");
        final BackupArtifactHandle handle =
                RuntimeBackupArtifactHandle.issue(file, dir, true, PermissionChecker.allowAll());
        handle.discard();
        assertFalse(Files.exists(file), "the temporary artifact must be deleted");
        assertFalse(Files.exists(dir), "the emptied issuing temp directory must be pruned");
    }

    @Test
    void discardKeepsANonEmptyIssuingDirectory() throws IOException {
        final Path dir = Files.createDirectories(temporary.resolve("turboism-backup-test"));
        final Path file = Files.writeString(dir.resolve("model_backup.cmo3"), "temp");
        Files.writeString(dir.resolve("other_backup.cmo3"), "kept");
        final BackupArtifactHandle handle =
                RuntimeBackupArtifactHandle.issue(file, dir, true, PermissionChecker.allowAll());
        handle.discard();
        assertFalse(Files.exists(file));
        assertTrue(Files.isDirectory(dir), "a non-empty issuing directory must survive");
    }

    @Test
    void issueReportsTheSymbolicLinkSizeNotTheTargetSize() throws IOException {
        final Path dir = Files.createDirectories(temporary.resolve("backup"));
        final Path outside = Files.writeString(temporary.resolve("secret.txt"), "a much longer secret body");
        final Path link = dir.resolve("linked.cmo3");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
            Assumptions.assumeTrue(false, "symbolic links are not creatable on this platform");
        }
        final BackupArtifactHandle handle =
                RuntimeBackupArtifactHandle.issue(link, dir, false, PermissionChecker.allowAll());

        assertEquals(
                Files.readAttributes(
                                link,
                                java.nio.file.attribute.BasicFileAttributes.class,
                                java.nio.file.LinkOption.NOFOLLOW_LINKS)
                        .size(),
                handle.sizeBytes(),
                "issue must stat the link itself, never the link target");
        assertThrows(IOException.class, handle::openStream);
    }

    @Test
    void openStreamRejectsAnArtifactBehindALinkedDirectory() throws IOException {
        final Path dir = Files.createDirectories(temporary.resolve("backup"));
        final Path outside = Files.createDirectories(temporary.resolve("outside"));
        Files.writeString(outside.resolve("artifact.cmo3"), "secret");
        final Path linkedSubdir = dir.resolve("linked-subdir");
        try {
            Files.createSymbolicLink(linkedSubdir, outside);
        } catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
            Assumptions.assumeTrue(false, "symbolic links are not creatable on this platform");
        }
        // The artifact resolves through a linked directory that escapes the
        // issuing root: containment must be checked against the real parent,
        // not the literal path.
        final Path artifact = linkedSubdir.resolve("artifact.cmo3");
        final BackupArtifactHandle handle =
                RuntimeBackupArtifactHandle.issue(artifact, dir, false, PermissionChecker.allowAll());

        assertThrows(IOException.class, handle::openStream);
    }

    @Test
    void openStreamRejectsAnArtifactReplacedByASymbolicLink() throws IOException {
        final Path dir = Files.createDirectories(temporary.resolve("backup"));
        final Path file = Files.writeString(dir.resolve("model_backup.cmo3"), "artifact-bytes");
        final Path outside = Files.writeString(temporary.resolve("secret.txt"), "secret");
        final BackupArtifactHandle handle =
                RuntimeBackupArtifactHandle.issue(file, dir, false, PermissionChecker.allowAll());
        Files.delete(file);
        try {
            Files.createSymbolicLink(file, outside);
        } catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
            Assumptions.assumeTrue(false, "symbolic links are not creatable on this platform");
        }
        assertThrows(IOException.class, handle::openStream);
    }

    @Test
    void openStreamReadsThroughASymbolicLinkIssuingRoot() throws IOException {
        final Path real = Files.createDirectories(temporary.resolve("real-backup"));
        final Path file = Files.writeString(real.resolve("model_backup.cmo3"), "artifact-bytes");
        final Path linkedRoot = temporary.resolve("linked-root");
        try {
            Files.createSymbolicLink(linkedRoot, real);
        } catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
            Assumptions.assumeTrue(false, "symbolic links are not creatable on this platform");
        }
        final BackupArtifactHandle handle = RuntimeBackupArtifactHandle.issue(
                linkedRoot.resolve("model_backup.cmo3"), linkedRoot, false, PermissionChecker.allowAll());

        try (var in = handle.openStream()) {
            assertEquals("artifact-bytes", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void discardRejectsHostOwnedArtifacts() throws IOException {
        final Path dir = Files.createDirectories(temporary.resolve("backup"));
        final Path file = Files.writeString(dir.resolve("model_backup.cmo3"), "host-owned");
        final BackupArtifactHandle handle =
                RuntimeBackupArtifactHandle.issue(file, dir, false, PermissionChecker.allowAll());
        assertThrows(IllegalStateException.class, handle::discard);
        assertTrue(Files.exists(file), "a host-owned artifact must never be deleted");
    }

    @Test
    void discardOfAnAlreadyRemovedArtifactSucceedsSilently() throws IOException {
        final Path dir = Files.createDirectories(temporary.resolve("turboism-backup-test"));
        final Path file = Files.writeString(dir.resolve("model_backup.cmo3"), "temp");
        final BackupArtifactHandle handle =
                RuntimeBackupArtifactHandle.issue(file, dir, true, PermissionChecker.allowAll());
        Files.delete(file);
        handle.discard();
        assertFalse(Files.exists(dir));
    }

    @Test
    void handleReportsTheGrantedPermissionOperation() throws IOException {
        final List<String> operations = new java.util.ArrayList<>();
        final PermissionChecker recording = (permissionId, operation) -> {
            operations.add(permissionId + ":" + operation);
        };
        final BackupArtifactHandle handle =
                RuntimeBackupArtifactHandle.issue(temporary.resolve("model.cmo3"), temporary, true, recording);
        assertThrows(IOException.class, handle::openStream);
        handle.discard();
        assertEquals(
                List.of(
                        PermissionIds.TURBOISM_CUBISM_BACKUP_OBSERVE + ":cubism.backup.artifact.read",
                        PermissionIds.TURBOISM_CUBISM_BACKUP_OBSERVE + ":cubism.backup.artifact.discard"),
                operations);
    }
}
