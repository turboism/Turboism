package dev.turboism.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpdateFileSupportTest {
    @TempDir
    Path directory;

    @Test
    void failedCopyPreservesTargetAndSuccessfulCopyCanRestoreIt() throws Exception {
        final Path target = directory.resolve("plugin.jar");
        final Path backup = directory.resolve("backup.jar");
        final Path staged = directory.resolve("staged.jar");
        Files.writeString(target, "old");
        Files.copy(target, backup);
        Files.writeString(staged, "new");
        assertThrows(
                IOException.class,
                () -> UpdateFileSupport.writeAtomic(target, new InputStream() {
                    @Override
                    public int read() throws IOException {
                        throw new IOException("copy failed");
                    }
                }));
        assertEquals("old", Files.readString(target));
        UpdateFileSupport.copyAtomic(staged, target);
        assertEquals("new", Files.readString(target));
        UpdateFileSupport.copyAtomic(backup, target);
        assertEquals("old", Files.readString(target));
        try (var files = Files.list(directory)) {
            assertEquals(3, files.count());
        }
    }
}
