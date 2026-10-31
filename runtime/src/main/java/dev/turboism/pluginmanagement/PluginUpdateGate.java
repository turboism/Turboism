package dev.turboism.pluginmanagement;

import dev.turboism.update.FrameworkUpdateHealth;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Root-bound coordination gate between manual framework+plugin updates and background auto-updaters.
 *
 * <p>Prevents concurrent staging, serializes the critical commit sections, and suppresses
 * background auto-updates while a manual hold is active or unapplied framework tasks exist on disk.</p>
 */
public final class PluginUpdateGate {

    /** Owned disposable lease representing an active manual update lock. */
    public interface Lease extends AutoCloseable {
        @Override
        void close();
    }

    private static final Map<Path, Object> ROOT_LOCKS = new ConcurrentHashMap<>();
    private static final Map<Path, LeaseImpl> ACTIVE_LEASES = new ConcurrentHashMap<>();

    private PluginUpdateGate() {}

    /** Returns the synchronization monitor for the specified installation home. */
    public static Object rootLock(final Path home) {
        final Path key = normalizeKey(home);
        return ROOT_LOCKS.computeIfAbsent(key, k -> new Object());
    }

    /**
     * Acquires an owned manual update lease for the specified home.
     *
     * @param home installation home
     * @return lease if acquired; empty if already held by another manual operation
     */
    public static Optional<Lease> acquireLease(final Path home) {
        final Path key = normalizeKey(home);
        synchronized (rootLock(home)) {
            if (ACTIVE_LEASES.containsKey(key)) {
                return Optional.empty();
            }
            final LeaseImpl lease = new LeaseImpl(key);
            ACTIVE_LEASES.put(key, lease);
            return Optional.of(lease);
        }
    }

    /** Returns whether a manual update lease is currently held for the home. */
    public static boolean isHeld(final Path home) {
        final Path key = normalizeKey(home);
        synchronized (rootLock(home)) {
            return ACTIVE_LEASES.containsKey(key);
        }
    }

    /**
     * Checks whether background plugin auto-updates are blocked.
     *
     * <p>Blocked if an active lease exists OR if a framework update pending/journal file exists on disk.</p>
     */
    public static boolean isAutoUpdateBlocked(final Path home) {
        final Path key = normalizeKey(home);
        synchronized (rootLock(home)) {
            if (ACTIVE_LEASES.containsKey(key)) {
                return true;
            }
        }
        if (home != null) {
            try {
                final Path helperWork = FrameworkUpdateHealth.workDirectory(home);
                if (Files.exists(helperWork.resolve("pending.json"), LinkOption.NOFOLLOW_LINKS)
                        || Files.exists(helperWork.resolve("journal.json"), LinkOption.NOFOLLOW_LINKS)) {
                    return true;
                }
            } catch (Exception conservative) {
                return true;
            }
        }
        return false;
    }

    private static Path normalizeKey(final Path home) {
        return home == null ? Path.of("") : home.toAbsolutePath().normalize();
    }

    private static final class LeaseImpl implements Lease {
        private final Path key;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        LeaseImpl(final Path key) {
            this.key = key;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                synchronized (PluginUpdateGate.rootLock(key)) {
                    ACTIVE_LEASES.remove(key, this);
                }
            }
        }
    }
}
