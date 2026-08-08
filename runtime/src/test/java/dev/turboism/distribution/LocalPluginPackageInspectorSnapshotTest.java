package dev.turboism.distribution;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class LocalPluginPackageInspectorSnapshotTest {
    @Test void privateSnapshotIsOwnerOnlyAndCanBeCleanedUp() throws Exception {
        final Method method = LocalPluginPackageInspector.class.getDeclaredMethod("privateSnapshot");
        method.setAccessible(true);
        final Path snapshot = (Path) method.invoke(null);
        try {
            if (Files.getFileStore(snapshot).supportsFileAttributeView("posix")) {
                assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(snapshot));
            }
        } finally {
            Files.deleteIfExists(snapshot);
        }
        assertFalse(Files.exists(snapshot));
    }
}
