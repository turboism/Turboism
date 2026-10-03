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
