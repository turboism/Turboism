package dev.turboism.mapping.draft;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;

/** Acquires and owns the filesystem lock used to serialize mapping replacement. */
@FunctionalInterface
interface LockAcquirer {
    AcquiredLock acquire(Path lockPath) throws IOException;

    static LockAcquirer system() {
        return lockPath -> {
            FileChannel channel = null;
            try {
                channel = FileSafety.openRegularNoFollow(
                    lockPath, Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE), "APPLY_LOCK_FAILED");
                final FileLock lock = channel.lock();
                return new AcquiredLock(channel, lock);
            } catch (IOException | RuntimeException exception) {
                if (channel != null) {
                    try {
                        channel.close();
                    } catch (IOException cleanup) {
                        exception.addSuppressed(cleanup);
                    }
                }
                throw exception;
            }
        };
    }

    record AcquiredLock(FileChannel channel, FileLock lock) implements AutoCloseable {
        @Override public void close() throws IOException {
            IOException failure = null;
            try {
                lock.close();
            } catch (IOException exception) {
                failure = exception;
            }
            try {
                channel.close();
            } catch (IOException exception) {
                if (failure == null) failure = exception;
                else failure.addSuppressed(exception);
            }
            if (failure != null) throw failure;
        }
    }
}
