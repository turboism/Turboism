package dev.turboism.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Cross-process launch/apply lease. The installation may only be mutated (applied, rolled back or recovered)
 * when no Cubism JVM is using it and no launcher is in its pre-launch window.
 *
 * <p>Every transition runs while the caller holds {@link FrameworkUpdateLock}; the lease file system therefore
 * never observes a lost update. Ownership of an entry is bound to {@code root + token + pid + startInstant}, so a
 * late-arriving JVM carrying an old token can never convert a newer launcher's {@code LAUNCHING} entry, and
 * liveness is judged by process identity rather than whether the transaction lock is still held.
 *
 * <p>Entries live under {@code <work>/lease}, which inherits the owner-only ACL already prepared for
 * {@code state/runtime/update}; each entry is written via temp-file + force + {@link StandardCopyOption#ATOMIC_MOVE}
 * with {@code REPLACE_EXISTING}. On Windows this maps to {@code MoveFileEx(MOVEFILE_REPLACE_EXISTING)}: NTFS
 * performs a single rename that replaces the target, and the operation fails closed if the target is open without
 * {@code FILE_SHARE_DELETE}. On POSIX it is {@code rename(2)}, also atomic-replace.
 */
final class FrameworkUpdateLaunchLease {
    static final String DIRECTORY = "lease";
    static final int MAX_ENTRIES = 256;
    static final int MAX_ENTRY_BYTES = 4096;
    static final String FORMAT = "turboism-launch-lease-v1";

    enum Kind {
        LAUNCHING,
        USING
    }

    record Entry(Kind kind, long pid, String started, String root, String token, Path file) {}

    /** Liveness seam. A reused pid counts as not the holder; an unavailable start instant counts as alive. */
    @FunctionalInterface
    interface Liveness {
        boolean alive(long pid, String started);
    }

    static final Liveness SYSTEM = (pid, started) -> {
        final Optional<ProcessHandle> handle = ProcessHandle.of(pid);
        if (handle.isEmpty() || !handle.get().isAlive()) return false;
        final Optional<Instant> now = handle.get().info().startInstant();
        if (now.isEmpty()) return true;
        return now.get().toString().equals(started);
    };

    private FrameworkUpdateLaunchLease() {}

    /** 256-bit random token (64 hex chars). */
    static String newToken() {
        final byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** Record that a launcher is about to start Cubism. Caller holds the transaction lock. */
    static Entry publishLaunching(
            final Path work, final Path root, final long launcherPid, final String started, final String token)
            throws IOException {
        validate(token, root);
        validateHolder(launcherPid, started);
        requireCapacity(work);
        final Path file = entryFile(work, token);
        if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Launch lease token collision");
        }
        write(file, entryJson(Kind.LAUNCHING, launcherPid, started, root, token));
        return new Entry(Kind.LAUNCHING, launcherPid, started, root.toString(), token, file);
    }

    /**
     * Runtime premain handshake. With a managed {@code token}, the matching {@code LAUNCHING} entry must exist:
     * its absence is rejected (fail closed) — only an explicit legacy launch with no token may register
     * independently, and that path still carries the documented pre-premain window. Re-handshaking the exact
     * same holder (pid + startInstant + token + root) is idempotent and does not consume entry capacity.
     */
    static Entry completeToUsing(
            final Path work, final Path root, final long runtimePid, final String started, final String tokenOrNull)
            throws IOException {
        final String token = tokenOrNull == null ? newToken() : tokenOrNull;
        validate(token, root);
        validateHolder(runtimePid, started);
        requirePlainLeaseDirectory(work);
        final Path file = entryFile(work, token);
        if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            final Entry existing = read(file);
            final boolean sameHolder = existing.kind == Kind.USING
                    && existing.pid == runtimePid
                    && existing.started.equals(started)
                    && existing.token.equals(token)
                    && existing.root.equals(root.toString());
            if (sameHolder) {
                return existing; // idempotent re-handshake
            }
            if (tokenOrNull != null
                    && existing.kind == Kind.LAUNCHING
                    && token.equals(existing.token)
                    && root.toString().equals(existing.root)) {
                write(file, entryJson(Kind.USING, runtimePid, started, root, token));
                return new Entry(Kind.USING, runtimePid, started, root.toString(), token, file);
            }
            throw new IOException("Launch lease handshake collision with an existing entry");
        }
        if (tokenOrNull != null) {
            throw new IOException("Launch lease handshake has no matching LAUNCHING entry");
        }
        // Legacy/direct launch: independent registration; the pre-premain window remains.
        return publishUsing(work, root, runtimePid, started, token);
    }

    /** Record that a runtime JVM is using the installation (legacy launch with no launcher entry). */
    static Entry publishUsing(
            final Path work, final Path root, final long runtimePid, final String started, final String token)
            throws IOException {
        validate(token, root);
        validateHolder(runtimePid, started);
        requireCapacity(work);
        final Path file = entryFile(work, token);
        if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Launch lease token collision");
        }
        write(file, entryJson(Kind.USING, runtimePid, started, root, token));
        return new Entry(Kind.USING, runtimePid, started, root.toString(), token, file);
    }

    /** Mutator admission: may a helper apply/recover right now? */
    record Decision(boolean allowed, String reason) {}

    static Decision mutatorDecision(final Path work, final Path root, final Liveness liveness) throws IOException {
        boolean recoveryRequired = false;
        for (final Entry entry : readAll(work)) {
            if (!root.toString().equals(entry.root)) {
                return new Decision(false, "Launch lease belongs to another installation");
            }
            if (entry.kind == Kind.USING) {
                if (liveness.alive(entry.pid, entry.started)) {
                    return new Decision(false, "A runtime instance is using this installation");
                }
                // Stale USING: the runtime exited; the caller reaps before mutating.
            } else {
                if (liveness.alive(entry.pid, entry.started)) {
                    return new Decision(false, "A launch is in flight");
                }
                recoveryRequired = true;
            }
        }
        if (recoveryRequired) {
            return new Decision(false, "Unproven launch termination; recovery required");
        }
        return new Decision(true, "");
    }

    record ReapResult(int reapedUsing, boolean recoveryRequired) {}

    /** Delete stale USING entries (owner gone). Never delete a LAUNCHING entry: that needs explicit recovery. */
    static ReapResult reapStale(final Path work, final Path root, final Liveness liveness) throws IOException {
        int reaped = 0;
        boolean recoveryRequired = false;
        for (final Entry entry : readAll(work)) {
            if (!root.toString().equals(entry.root)) continue;
            if (entry.kind == Kind.USING && !liveness.alive(entry.pid, entry.started)) {
                Files.deleteIfExists(entry.file);
                reaped++;
            } else if (entry.kind == Kind.LAUNCHING && !liveness.alive(entry.pid, entry.started)) {
                recoveryRequired = true;
            }
        }
        return new ReapResult(reaped, recoveryRequired);
    }

    /**
     * Launcher exit cleanup. The caller must only invoke this after it has proven no Cubism child survives:
     * either the official BAT/JVM was never started, or it was synchronously waited for until exit. On any
     * abnormal launch path where a child may still be alive, the {@code LAUNCHING} entry must be left in place.
     */
    static void releaseLaunching(
            final Path work, final Path root, final long launcherPid, final String started, final String token)
            throws IOException {
        requirePlainLeaseDirectory(work);
        final Path file = entryFile(work, token);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return;
        final Entry entry = read(file);
        if (entry.kind == Kind.LAUNCHING
                && entry.pid == launcherPid
                && entry.started.equals(started)
                && entry.token.equals(token)
                && entry.root.equals(root.toString())) {
            Files.deleteIfExists(file);
        }
    }

    static List<Entry> readAll(final Path work) throws IOException {
        final Path dir = directory(work);
        requirePlainLeaseDirectory(work);
        final BasicFileAttributes dirAttributes = FrameworkUpdateCache.attributes(dir);
        if (dirAttributes == null) return List.of();
        final List<Entry> entries = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(dir)) {
            for (final Path file : stream) {
                if (entries.size() >= MAX_ENTRIES) throw new IOException("Too many launch lease entries");
                entries.add(read(file));
            }
        }
        return entries;
    }

    // ------------------------------------------------------------------ private

    private static Path directory(final Path work) {
        return work.toAbsolutePath().normalize().resolve(DIRECTORY);
    }

    private static Path entryFile(final Path work, final String token) {
        return directory(work).resolve("lease-" + token + ".json");
    }

    private static void requireCapacity(final Path work) throws IOException {
        requirePlainLeaseDirectory(work);
        if (FrameworkUpdateCache.attributes(directory(work)) == null) return; // first entry
        if (readAll(work).size() >= MAX_ENTRIES) throw new IOException("Launch lease entry limit reached");
    }

    private static void requirePlainLeaseDirectory(final Path work) throws IOException {
        final Path dir = directory(work);
        final BasicFileAttributes a = FrameworkUpdateCache.attributes(dir);
        if (a == null) return;
        if (!a.isDirectory() || a.isSymbolicLink() || a.isOther()) {
            throw new IOException("Launch lease directory is not a plain directory");
        }
        FrameworkUpdateCache.installationAncestors(dir);
    }

    private static void write(final Path file, final ObjectNode node) throws IOException {
        FrameworkUpdateCache.installationAncestors(file.getParent());
        Files.createDirectories(file.getParent());
        FrameworkUpdateCache.installationAncestors(file.getParent());
        final byte[] bytes = UpdateFileSupport.JSON.writeValueAsBytes(node);
        final Path temporary = Files.createTempFile(file.getParent(), ".lease-", ".writing");
        try {
            Files.write(temporary, bytes, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            try (FileChannel channel =
                    FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                channel.force(true);
            }
            // Atomic replace: Windows MoveFileEx(MOVEFILE_REPLACE_EXISTING); POSIX rename(2). Fails closed if
            // the target is open without share-delete.
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static ObjectNode entryJson(
            final Kind kind, final long pid, final String started, final Path root, final String token) {
        final ObjectNode node = UpdateFileSupport.JSON.createObjectNode();
        node.put("format", FORMAT);
        node.put("schemaVersion", 1);
        node.put("kind", kind.name());
        node.put("pid", pid);
        node.put("started", started);
        node.put("root", root.toAbsolutePath().normalize().toString());
        node.put("token", token);
        return node;
    }

    private static Entry read(final Path file) throws IOException {
        final BasicFileAttributes a = FrameworkUpdateCache.attributes(file);
        if (a == null || !a.isRegularFile() || a.isSymbolicLink() || a.isOther()) {
            throw new IOException("Invalid launch lease entry: " + file.getFileName());
        }
        final byte[] bytes;
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(MAX_ENTRY_BYTES + 1);
        }
        if (bytes.length == 0 || bytes.length > MAX_ENTRY_BYTES) {
            throw new IOException("Invalid launch lease entry size: " + file.getFileName());
        }
        final JsonNode parsed = UpdateFileSupport.JSON.readTree(bytes);
        if (!(parsed instanceof ObjectNode obj)
                || !FORMAT.equals(obj.path("format").asText())
                || !obj.path("schemaVersion").isIntegralNumber()
                || !obj.path("schemaVersion").canConvertToInt()
                || obj.path("schemaVersion").intValue() != 1
                || !Set.of("LAUNCHING", "USING").contains(obj.path("kind").asText())
                || !obj.path("pid").isIntegralNumber()
                || !obj.path("pid").canConvertToLong()
                || obj.path("pid").longValue() <= 0
                || !obj.path("started").isTextual()
                || !obj.path("root").isTextual()
                || !obj.path("token").isTextual()
                || !token(obj.path("token").asText())
                || !Path.of(obj.path("root").asText()).isAbsolute()) {
            throw new IOException("Invalid launch lease entry: " + file.getFileName());
        }
        final String token = obj.path("token").asText();
        if (!file.getFileName().toString().equals("lease-" + token + ".json")) {
            throw new IOException("Launch lease filename does not match its token: " + file.getFileName());
        }
        final String started = obj.path("started").asText();
        try {
            Instant.parse(started);
        } catch (DateTimeParseException invalid) {
            throw new IOException("Invalid launch lease start instant: " + file.getFileName());
        }
        return new Entry(
                Kind.valueOf(obj.path("kind").asText()),
                obj.path("pid").longValue(),
                started,
                Path.of(obj.path("root").asText()).normalize().toString(),
                token,
                file);
    }

    private static boolean token(final String value) {
        return value != null && value.matches("[a-f0-9]{64}");
    }

    private static void validate(final String token, final Path root) throws IOException {
        if (!token(token)) throw new IOException("Invalid launch lease token");
        if (!root.toAbsolutePath().normalize().equals(root)) throw new IOException("Launch root must be normalized");
    }

    private static void validateHolder(final long pid, final String started) throws IOException {
        if (pid <= 0) throw new IOException("Invalid launch lease holder pid");
        try {
            Instant.parse(started);
        } catch (DateTimeParseException invalid) {
            throw new IOException("Invalid launch lease holder start instant");
        }
    }
}
