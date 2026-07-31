package dev.turboism.distribution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfinedStagingFilesTest {
    @TempDir Path tempDir;

    @Test void publishesWhenWindowsProviderHasNoFileKeyAndChangesDirectoryCreationTime() throws Exception {
        final Path directory = tempDir.resolve("staging");
        final AtomicInteger reads = new AtomicInteger();
        final var target = ConfinedStagingFiles.create(directory, "plugin.jar", path ->
            attributes(path, null, FileTime.fromMillis(reads.incrementAndGet())));

        target.output().write(new byte[]{1});
        target.publish();

        assertTrue(Files.isRegularFile(directory.resolve("plugin.jar")));
        assertFalse(Files.exists(target.temporary()));
    }

    @Test void rejectsChangedDirectoryWhenStableFileKeysAreAvailable() throws Exception {
        final Path directory = tempDir.resolve("staging");
        final AtomicInteger reads = new AtomicInteger();
        final var target = ConfinedStagingFiles.create(directory, "plugin.jar", path ->
            attributes(path, reads.incrementAndGet() == 1 ? "before" : "after", FileTime.fromMillis(1)));
        target.output().write(new byte[]{1});

        assertThrows(IOException.class, target::publish);
        target.cleanup();
        assertFalse(Files.exists(target.temporary()));
    }

    @Test void cleanupRemovesUnpublishedPrivateTemporary() throws Exception {
        final Path directory = tempDir.resolve("staging");
        final var target = ConfinedStagingFiles.create(directory, "plugin.jar");
        target.output().write(new byte[]{1});
        assertTrue(Files.exists(target.temporary()));

        target.cleanup();

        assertFalse(Files.exists(target.temporary()));
        assertEquals(0, Files.list(directory).count());
    }

    private static BasicFileAttributes attributes(Path path, Object key, FileTime creation) throws IOException {
        final BasicFileAttributes actual = Files.readAttributes(path, BasicFileAttributes.class);
        return new BasicFileAttributes() {
            @Override public FileTime lastModifiedTime() { return actual.lastModifiedTime(); }
            @Override public FileTime lastAccessTime() { return actual.lastAccessTime(); }
            @Override public FileTime creationTime() { return creation; }
            @Override public boolean isRegularFile() { return actual.isRegularFile(); }
            @Override public boolean isDirectory() { return actual.isDirectory(); }
            @Override public boolean isSymbolicLink() { return actual.isSymbolicLink(); }
            @Override public boolean isOther() { return actual.isOther(); }
            @Override public long size() { return actual.size(); }
            @Override public Object fileKey() { return key; }
        };
    }
}
