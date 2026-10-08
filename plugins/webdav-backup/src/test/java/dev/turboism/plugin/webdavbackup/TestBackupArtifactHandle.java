package dev.turboism.plugin.webdavbackup;

import dev.turboism.sdk.cubism.backup.BackupArtifact;
import dev.turboism.sdk.cubism.backup.BackupArtifactHandle;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Test double for the runtime-issued artifact handle: mirrors the production
 * semantics (metadata captured at issue, save-triggered artifacts live under a
 * {@code turboism-backup-*} directory, {@code discard} removes the file and
 * prunes the empty temp directory) over a plain test path.
 */
public final class TestBackupArtifactHandle implements BackupArtifactHandle {

    private final Path file;
    private final boolean temporary;
    private final BackupArtifact artifact;

    private TestBackupArtifactHandle(final Path file, final boolean temporary) {
        this.file = file;
        this.temporary = temporary;
        long size = 0L;
        try {
            size = Files.size(file);
        } catch (IOException unavailable) {
            size = 0L;
        }
        this.artifact = new BackupArtifact(file.getFileName().toString(), size, temporary);
    }

    /** Issues a handle whose temporary flag follows the runtime's {@code turboism-backup-} parent convention. */
    public static TestBackupArtifactHandle of(final Path file) {
        final Path parent = file.getParent();
        final boolean temporary = parent != null
                && parent.getFileName() != null
                && parent.getFileName().toString().startsWith("turboism-backup-");
        return new TestBackupArtifactHandle(file, temporary);
    }

    @Override
    public BackupArtifact artifact() {
        return artifact;
    }

    @Override
    public long lastModifiedMillis() {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException unavailable) {
            return -1L;
        }
    }

    @Override
    public InputStream openStream() throws IOException {
        return Files.newInputStream(file);
    }

    @Override
    public void discard() throws IOException {
        if (!temporary) {
            throw new IllegalStateException("only temporary artifacts can be discarded");
        }
        Files.deleteIfExists(file);
        final Path dir = file.getParent();
        if (dir != null && Files.isDirectory(dir)) {
            try (var entries = Files.list(dir)) {
                if (entries.findAny().isEmpty()) {
                    Files.deleteIfExists(dir);
                }
            }
        }
    }
}
