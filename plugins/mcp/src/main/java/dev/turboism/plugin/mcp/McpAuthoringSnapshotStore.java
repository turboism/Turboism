package dev.turboism.plugin.mcp;

import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot;
import dev.turboism.sdk.cubism.model.ArtMeshEvaluationSnapshot;
import dev.turboism.sdk.cubism.model.RotationAuthoringSnapshot;
import dev.turboism.sdk.cubism.model.WarpAuthoringSnapshot;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Bounded session-owned immutable authoring snapshots. Tokens are identities, never CAS substitutes. */
final class McpAuthoringSnapshotStore {
    static final long TTL_NANOS = java.time.Duration.ofMinutes(5).toNanos();
    static final int MAX_ENTRIES = 16;
    static final int MAX_ENTRY_BYTES = 4 * 1024 * 1024;
    static final int MAX_TOTAL_BYTES = 16 * 1024 * 1024;
    private final LongSupplier clock;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
    private int bytes;

    McpAuthoringSnapshotStore() {
        this(System::nanoTime);
    }

    McpAuthoringSnapshotStore(LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    synchronized Entry put(
            String session,
            Map<String, Object> binding,
            String kind,
            String objectId,
            String sourceGuid,
            Object nativeSnapshot,
            List<Map<String, Object>> rows,
            int encodedBytes) {
        return put(session, binding, kind, objectId, sourceGuid, nativeSnapshot, rows, encodedBytes, null);
    }

    synchronized Entry put(
            String session,
            Map<String, Object> binding,
            String kind,
            String objectId,
            String sourceGuid,
            Object nativeSnapshot,
            List<Map<String, Object>> rows,
            int encodedBytes,
            String retainedSnapshotId) {
        if (!(nativeSnapshot instanceof ArtMeshAuthoringSnapshot
                || nativeSnapshot instanceof WarpAuthoringSnapshot
                || nativeSnapshot instanceof RotationAuthoringSnapshot
                || nativeSnapshot instanceof ArtMeshEvaluationSnapshot))
            throw new IllegalArgumentException("Unsupported native authoring snapshot type");
        if (encodedBytes < 0 || encodedBytes > MAX_ENTRY_BYTES)
            throw new IllegalArgumentException("Snapshot byte budget exceeded");
        prune();
        if (retainedSnapshotId != null) {
            var retained = get(retainedSnapshotId, session);
            // Parent and dependent sample each fit MAX_ENTRY_BYTES, together below total budget.
            // Move the parent last without extending its original TTL before evicting older entries.
            entries.remove(retainedSnapshotId);
            entries.put(retainedSnapshotId, retained);
        }
        while (entries.size() >= MAX_ENTRIES || bytes + encodedBytes > MAX_TOTAL_BYTES) removeOldest();
        var entry = new Entry(
                UUID.randomUUID().toString(),
                session,
                Map.copyOf(binding),
                kind,
                objectId,
                sourceGuid,
                nativeSnapshot,
                rows.stream().map(McpAuthoringSnapshotStore::immutableRow).toList(),
                encodedBytes,
                clock.getAsLong());
        entries.put(entry.snapshotId(), entry);
        bytes += encodedBytes;
        return entry;
    }

    synchronized Entry get(String snapshotId, String session) {
        prune();
        var entry = entries.get(snapshotId);
        // A cross-session lookup is indistinguishable from an unknown or expired token.
        if (entry == null || !entry.session().equals(session))
            throw new NoSuchElementException("Unknown or expired snapshot");
        return entry;
    }

    synchronized void invalidate(String snapshotId) {
        var previous = entries.remove(snapshotId);
        if (previous != null) bytes -= previous.encodedBytes();
    }

    private void prune() {
        long now = clock.getAsLong();
        for (Iterator<Entry> iterator = entries.values().iterator(); iterator.hasNext(); ) {
            var entry = iterator.next();
            if (now - entry.createdNanos() >= TTL_NANOS) {
                iterator.remove();
                bytes -= entry.encodedBytes();
            }
        }
    }

    private void removeOldest() {
        var iterator = entries.values().iterator();
        var entry = iterator.next();
        iterator.remove();
        bytes -= entry.encodedBytes();
    }

    private static Map<String, Object> immutableRow(Map<?, ?> row) {
        var result = new LinkedHashMap<String, Object>();
        for (var field : row.entrySet()) {
            if (!(field.getKey() instanceof String key))
                throw new IllegalArgumentException("Non-string snapshot field");
            result.put(key, immutableValue(field.getValue()));
        }
        return java.util.Collections.unmodifiableMap(result);
    }

    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> map) return immutableRow(map);
        if (value instanceof List<?> list)
            return list.stream().map(McpAuthoringSnapshotStore::immutableValue).toList();
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean)
            return value;
        throw new IllegalArgumentException("Unsupported snapshot field type");
    }

    record Entry(
            String snapshotId,
            String session,
            Map<String, Object> binding,
            String kind,
            String objectId,
            String sourceGuid,
            Object nativeSnapshot,
            List<Map<String, Object>> rows,
            int encodedBytes,
            long createdNanos) {
        Entry {
            Objects.requireNonNull(session);
            Objects.requireNonNull(nativeSnapshot);
        }
    }
}
