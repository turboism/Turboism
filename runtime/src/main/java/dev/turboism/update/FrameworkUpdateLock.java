package dev.turboism.update;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Cross-process serialization for helper transactions and framework cache writers. */
final class FrameworkUpdateLock implements AutoCloseable {
    // POSIX record locks belong to the process: closing ANY descriptor for a locked file can release
    // its locks. Refuse local contention before opening a second channel, not after tryLock fails.
    private static final Set<Path> OWNED_FILES = ConcurrentHashMap.newKeySet();

    private final Path file;
    private final FileChannel channel;
    private final FileLock lock;
    private boolean closed;

    private FrameworkUpdateLock(final Path file, final FileChannel channel, final FileLock lock) {
        this.file = file;
        this.channel = channel;
        this.lock = lock;
    }

    static FrameworkUpdateLock acquire(final Path work) throws IOException {
        final Path parent = work.toAbsolutePath().normalize().getParent();
        if (parent == null) throw new IOException("Update work directory needs a parent");
        FrameworkUpdateCache.installationAncestors(parent);
        Files.createDirectories(parent);
        FrameworkUpdateCache.installationAncestors(parent);
        try {
            return acquireFile(parent.resolve("helper-transaction.lock"));
        } catch (IOException failure) {
            throw new IOException("Could not acquire update transaction lock", failure);
        }
    }

    /** Acquires a lock file after the caller has validated its parent and any cache-specific ownership rules. */
    static FrameworkUpdateLock acquireFile(final Path requested) throws IOException {
        final Path absolute = requested.toAbsolutePath().normalize();
        final Path parent = absolute.getParent();
        if (parent == null) throw new IOException("Update lock file needs a parent");
        final Path file = parent.toRealPath().resolve(absolute.getFileName());
        if (!OWNED_FILES.add(file)) throw new IOException("Another in-process update operation holds this lock");
        FileChannel channel = null;
        boolean transferred = false;
        try {
            channel = FileChannel.open(
                    file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            final FileLock lock = channel.tryLock();
            if (lock == null) throw new IOException("Another update operation is active");
            final var acquired = new FrameworkUpdateLock(file, channel, lock);
            transferred = true;
            return acquired;
        } catch (IOException | OverlappingFileLockException failure) {
            throw new IOException("Could not acquire framework update lock", failure);
        } finally {
            if (!transferred) {
                try {
                    if (channel != null) channel.close();
                } finally {
                    OWNED_FILES.remove(file);
                }
            }
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        try {
            try {
                lock.release();
            } finally {
                channel.close();
            }
        } finally {
            OWNED_FILES.remove(file);
        }
    }
}
