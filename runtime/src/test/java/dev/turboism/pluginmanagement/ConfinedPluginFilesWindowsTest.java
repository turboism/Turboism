package dev.turboism.pluginmanagement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfinedPluginFilesWindowsTest {
    @TempDir Path home;

    @Test void acceptsWindowsProviderWithoutFileKeyAndMutableCreationTime() throws Exception {
        final Path target = home.resolve("state/pending.json");
        final AtomicInteger reads = new AtomicInteger();
        final ConfinedPluginFiles files = new ConfinedPluginFiles(home, path ->
            attributes(path, null, FileTime.fromMillis(reads.incrementAndGet())));
        final ConfinedPluginFiles.ParentIdentity parent = files.parent(target);
        try (var output = files.createNew(target, parent)) { output.write(java.nio.ByteBuffer.wrap(new byte[]{1})); }

        parent.verify();
        assertTrue(Files.isRegularFile(target));
    }

    @Test void rejectsStableFileKeyReplacement() throws Exception {
        final Path target = home.resolve("state/pending.json");
        final AtomicInteger reads = new AtomicInteger();
        final ConfinedPluginFiles files = new ConfinedPluginFiles(home, path ->
            attributes(path, reads.incrementAndGet() == 1 ? "before" : "after", FileTime.fromMillis(1)));
        final ConfinedPluginFiles.ParentIdentity parent = files.parent(target);

        assertThrows(IOException.class, parent::verify);
        assertFalse(Files.exists(target));
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
