package dev.turboism.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/** Process-local coordination for cooperative confined-storage mutations. */
final class StorageMutationLocks {

    private static final int STRIPE_COUNT = 97;
    private static final ReentrantLock[] STRIPES = new ReentrantLock[STRIPE_COUNT];

    static {
        for (int index = 0; index < STRIPE_COUNT; index++) {
            STRIPES[index] = new ReentrantLock();
        }
    }

    private StorageMutationLocks() {
    }

    static LockScope acquire(final List<Path> canonicalRoots)
        throws IOException, InterruptedException {
        Objects.requireNonNull(canonicalRoots, "canonicalRoots");
        final Set<Integer> indexes = new HashSet<>();
        for (Path root : canonicalRoots) {
            final Path canonical = canonicalLockPath(
                Objects.requireNonNull(root, "canonical root")
            );
            indexes.add(Math.floorMod(canonical.toString().hashCode(), STRIPE_COUNT));
        }
        final List<Integer> ordered = new ArrayList<>(indexes);
        ordered.sort(Comparator.naturalOrder());
        final List<ReentrantLock> acquired = new ArrayList<>(ordered.size());
        try {
            for (int index : ordered) {
                final ReentrantLock lock = STRIPES[index];
                lock.lockInterruptibly();
                acquired.add(lock);
            }
            return new LockScope(acquired);
        } catch (InterruptedException failure) {
            unlockReverse(acquired);
            throw failure;
        }
    }

    private static Path canonicalLockPath(final Path root) throws IOException {
        Path existing = root;
        final Deque<Path> missingSegments = new ArrayDeque<>();
        while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            final Path name = existing.getFileName();
            existing = existing.getParent();
            if (name == null || existing == null) {
                throw new IOException("Plugin storage root has no existing ancestor");
            }
            missingSegments.addFirst(name);
        }
        Path canonical = existing.toRealPath();
        for (Path segment : missingSegments) {
            canonical = canonical.resolve(segment);
        }
        return canonical.normalize();
    }

    private static void unlockReverse(final List<ReentrantLock> locks) {
        for (int index = locks.size() - 1; index >= 0; index--) {
            locks.get(index).unlock();
        }
    }

    static final class LockScope implements AutoCloseable {
        private final List<ReentrantLock> locks;

        private LockScope(final List<ReentrantLock> locks) {
            this.locks = List.copyOf(locks);
        }

        @Override
        public void close() {
            unlockReverse(locks);
        }
    }
}
