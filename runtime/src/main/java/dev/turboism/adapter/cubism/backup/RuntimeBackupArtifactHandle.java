package dev.turboism.adapter.cubism.backup;

import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.cubism.backup.BackupArtifact;
import dev.turboism.sdk.cubism.backup.BackupArtifactHandle;
import dev.turboism.sdk.permission.PermissionIds;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Runtime {@link BackupArtifactHandle}: an opaque token over one artifact file
 * inside the directory the runtime issued it from.
 *
 * <p>Safety contract:</p>
 * <ul>
 *   <li>The plugin-facing surface never sees the path; reads resolve the file
 *       fresh on every {@link #openStream()} call, refuse symbolic links, and
 *       reject resolutions that escape the issuing directory (the host backup
 *       directory, or the {@code turboism-backup-*} temp directory for
 *       save-triggered artifacts).</li>
 *   <li>Reads and discards demand {@code turboism.cubism.backup.observe}: the
 *       artifact bytes cross the same observation boundary as the backup
 *       completion event.</li>
 *   <li>{@link #discard()} only accepts runtime-created temporary artifacts;
 *       host-owned artifacts in the configured backup directory fail closed.
 *       A successful discard prunes the issuing temp directory when empty.</li>
 * </ul>
 */
final class RuntimeBackupArtifactHandle implements BackupArtifactHandle {

    private static final String OPERATION_READ = "cubism.backup.artifact.read";
    private static final String OPERATION_DISCARD = "cubism.backup.artifact.discard";

    private final Path file;
    private final Path root;
    private final boolean temporary;
    private final PermissionChecker permissions;
    private final BackupArtifact artifact;
    private final long lastModifiedMillis;

    private RuntimeBackupArtifactHandle(
            final Path file,
            final Path root,
            final boolean temporary,
            final PermissionChecker permissions,
            final BackupArtifact artifact,
            final long lastModifiedMillis) {
        this.file = file;
        this.root = root;
        this.temporary = temporary;
        this.permissions = permissions;
        this.artifact = artifact;
        this.lastModifiedMillis = lastModifiedMillis;
    }

    /**
     * Issues a handle over {@code file} confined to {@code root}. Metadata is
     * captured once at issue time; a stat failure yields a zero size and a
     * {@code -1} modification time rather than a throwing factory.
     */
    static RuntimeBackupArtifactHandle issue(
            final Path file, final Path root, final boolean temporary, final PermissionChecker permissions) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(permissions, "permissions");
        long size = 0L;
        long modified = -1L;
        try {
            size = Math.max(0L, Files.size(file));
            modified =
                    Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toMillis();
        } catch (IOException unavailable) {
            size = 0L;
            modified = -1L;
        }
        final BackupArtifact artifact = new BackupArtifact(file.getFileName().toString(), size, temporary);
        return new RuntimeBackupArtifactHandle(file, root, temporary, permissions, artifact, modified);
    }

    @Override
    public BackupArtifact artifact() {
        return artifact;
    }

    @Override
    public long lastModifiedMillis() {
        return lastModifiedMillis;
    }

    @Override
    public InputStream openStream() throws IOException {
        permissions.check(PermissionIds.TURBOISM_CUBISM_BACKUP_OBSERVE, OPERATION_READ);
        return Files.newInputStream(confined());
    }

    @Override
    public void discard() throws IOException {
        permissions.check(PermissionIds.TURBOISM_CUBISM_BACKUP_OBSERVE, OPERATION_DISCARD);
        if (!temporary) {
            throw new IllegalStateException(
                    "only runtime-created temporary backup artifacts can be discarded: " + file.getFileName());
        }
        // The issuing directory of a temporary artifact is the runtime temp dir
        // itself; deleting a link removes the link, never its target.
        if (!root.equals(file.getParent())) {
            throw new IOException("temporary backup artifact left its issuing directory: " + file.getFileName());
        }
        Files.deleteIfExists(file);
        if (Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            try (var entries = Files.list(root)) {
                if (entries.findAny().isEmpty()) {
                    Files.deleteIfExists(root);
                }
            }
        }
    }

    /**
     * Re-resolves the artifact against its issuing directory: the file must
     * still be a regular file (links refused) whose real path stays inside the
     * real issuing root. A missing file or an escape fails with IOException.
     */
    private Path confined() throws IOException {
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("backup artifact is not a regular file: " + file.getFileName());
        }
        final Path realFile = file.toRealPath(LinkOption.NOFOLLOW_LINKS);
        final Path realRoot = root.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!realFile.startsWith(realRoot)) {
            throw new IOException("backup artifact escapes its issuing directory: " + file.getFileName());
        }
        return realFile;
    }

    @Override
    public String toString() {
        return "BackupArtifactHandle[" + artifact.fileName() + "]";
    }
}
