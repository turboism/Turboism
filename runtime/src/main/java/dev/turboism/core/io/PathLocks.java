package dev.turboism.core.io;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One JVM monitor per normalized absolute path.
 *
 * <p>Replaces the per-path lock maps each persistence call site currently keeps for itself, so
 * two writers in the same process serialize on the same path even when they live in different
 * packages. Cross-process exclusion stays with the callers that need it (file locks, leases):
 * this type never acquires an operating-system lock.
 *
 * <p>The registry keeps one monitor per distinct normalized path for the life of the process. That
 * is bounded by the number of paths a call site can name, which is why call sites that name an
 * unbounded set of user-chosen paths keep their own policy instead of using this type.
 */
public final class PathLocks {

    private static final ConcurrentHashMap<Path, Object> MONITORS = new ConcurrentHashMap<>();

    private PathLocks() {}

    /**
     * @param path the path to serialize on; {@code ..} segments and relative forms are folded
     *     into one absolute normalized key so that two equivalent spellings share a monitor
     * @return the monitor for {@code path}, identical for every equivalent spelling
     * @throws NullPointerException if {@code path} is {@code null}
     */
    public static Object forPath(final Path path) {
        return MONITORS.computeIfAbsent(
                Objects.requireNonNull(path, "path").toAbsolutePath().normalize(), key -> new Object());
    }
}
