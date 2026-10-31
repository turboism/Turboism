package dev.turboism.update;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Cross-process serialization shared by helper application, recovery and runtime health confirmation. */
final class FrameworkUpdateLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    private FrameworkUpdateLock(final FileChannel channel, final FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    static FrameworkUpdateLock acquire(final Path work) throws IOException {
        final Path parent = work.toAbsolutePath().normalize().getParent();
        if (parent == null) throw new IOException("Update work directory needs a parent");
        FrameworkUpdateCache.installationAncestors(parent);
        Files.createDirectories(parent);
        FrameworkUpdateCache.installationAncestors(parent);
        final Path file = parent.resolve("helper-transaction.lock");
        final var channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
        try {
            final FileLock lock = channel.tryLock();
            if (lock == null) throw new IOException("Another update operation is active");
            return new FrameworkUpdateLock(channel, lock);
        } catch (IOException | OverlappingFileLockException failure) {
            channel.close();
            throw new IOException("Could not acquire update transaction lock", failure);
        }
    }

    @Override public void close() throws IOException {
        try {
            lock.release();
        } finally {
            channel.close();
        }
    }
}
