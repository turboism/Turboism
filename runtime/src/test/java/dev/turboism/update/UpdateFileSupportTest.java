package dev.turboism.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
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

    @Test
    void ancestorAttributesAcceptVerifiedWineMountBoundaryAndRejectRedirection() throws Exception {
        final Path canonicalBase = directory.toRealPath();
        final Path mountPoint = Files.createDirectory(canonicalBase.resolve("home"));
        final Path redirectedTarget = Files.createDirectory(canonicalBase.resolve("elsewhere"));
        final BasicFileAttributes realDirectoryAttrs = Files.readAttributes(mountPoint, BasicFileAttributes.class);

        final BasicFileAttributes accepted =
                UpdateFileSupport.ancestorAttributesIfPresent(mountPoint, new UpdateFileSupport.AncestorProbe() {
                    @Override
                    public BasicFileAttributes readNoFollow(final Path path) throws IOException {
                        throw new FileSystemException(path.toString(), null, UpdateFileSupport.WINE_MOUNT_POINT_REASON);
                    }

                    @Override
                    public BasicFileAttributes readFollowing(final Path path) {
                        return realDirectoryAttrs;
                    }

                    @Override
                    public Path toRealPath(final Path path) {
                        return mountPoint;
                    }
                });
        assertTrue(accepted.isDirectory());

        final FileSystemException redirectedFailure =
                new FileSystemException(mountPoint.toString(), null, UpdateFileSupport.WINE_MOUNT_POINT_REASON);
        final FileSystemException thrownOnRedirect = assertThrows(
                FileSystemException.class,
                () -> UpdateFileSupport.ancestorAttributesIfPresent(mountPoint, new UpdateFileSupport.AncestorProbe() {
                    @Override
                    public BasicFileAttributes readNoFollow(final Path path) throws IOException {
                        throw redirectedFailure;
                    }

                    @Override
                    public BasicFileAttributes readFollowing(final Path path) {
                        return realDirectoryAttrs;
                    }

                    @Override
                    public Path toRealPath(final Path path) {
                        return redirectedTarget;
                    }
                }));
        assertSame(redirectedFailure, thrownOnRedirect);

        final FileSystemException unrelatedFailure =
                new FileSystemException(mountPoint.toString(), null, "Access is denied");
        final FileSystemException thrownUnrelated = assertThrows(
                FileSystemException.class,
                () -> UpdateFileSupport.ancestorAttributesIfPresent(mountPoint, new UpdateFileSupport.AncestorProbe() {
                    @Override
                    public BasicFileAttributes readNoFollow(final Path path) throws IOException {
                        throw unrelatedFailure;
                    }

                    @Override
                    public BasicFileAttributes readFollowing(final Path path) {
                        throw new AssertionError("Unrelated errors must not trigger fallback");
                    }

                    @Override
                    public Path toRealPath(final Path path) {
                        throw new AssertionError("Unrelated errors must not trigger fallback");
                    }
                }));
        assertSame(unrelatedFailure, thrownUnrelated);

        assertNull(UpdateFileSupport.ancestorAttributesIfPresent(
                canonicalBase.resolve("missing"), new UpdateFileSupport.AncestorProbe() {
                    @Override
                    public BasicFileAttributes readNoFollow(final Path path) throws IOException {
                        throw new NoSuchFileException(path.toString());
                    }

                    @Override
                    public BasicFileAttributes readFollowing(final Path path) {
                        throw new AssertionError("Missing paths must return null");
                    }

                    @Override
                    public Path toRealPath(final Path path) {
                        throw new AssertionError("Missing paths must return null");
                    }
                }));
    }
}
