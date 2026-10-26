package dev.turboism.plugin.mcp;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.model.RotationAuthoringSnapshot;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

final class McpAuthoringSnapshotStoreTest {
    private static final RotationAuthoringSnapshot NATIVE = new RotationAuthoringSnapshot(
            new DeformerId("head"), "native-source-guid", 0, List.of(), List.of(), List.of());

    @Test
    void tokensAreSessionBoundExpireAndNeverSubstituteForNativeExpectedState() {
        var time = new AtomicLong();
        var store = new McpAuthoringSnapshotStore(time::get);
        var entry = put(store, "session-a", 100);
        assertSame(NATIVE, store.get(entry.snapshotId(), "session-a").nativeSnapshot());
        assertEquals(Map.of("documentId", "document-1"), entry.binding());
        assertThrows(NoSuchElementException.class, () -> store.get(entry.snapshotId(), "session-b"));
        time.set(McpAuthoringSnapshotStore.TTL_NANOS);
        assertThrows(NoSuchElementException.class, () -> store.get(entry.snapshotId(), "session-a"));
    }

    @Test
    void evictsOldestSnapshotAtCountAndByteLimitsAndSupportsInvalidation() {
        var store = new McpAuthoringSnapshotStore();
        var first = put(store, "a", 1);
        for (int i = 0; i < McpAuthoringSnapshotStore.MAX_ENTRIES; i++) put(store, "a", 1);
        assertThrows(NoSuchElementException.class, () -> store.get(first.snapshotId(), "a"));
        var large = put(store, "a", McpAuthoringSnapshotStore.MAX_ENTRY_BYTES);
        String oldId = large.snapshotId();
        for (int i = 0; i < 4; i++) put(store, "a", McpAuthoringSnapshotStore.MAX_ENTRY_BYTES);
        assertThrows(NoSuchElementException.class, () -> store.get(oldId, "a"));
        var last = put(store, "a", 10);
        store.invalidate(last.snapshotId());
        assertThrows(NoSuchElementException.class, () -> store.get(last.snapshotId(), "a"));
    }

    @Test
    void detachesNestedRowsFromCallerMutation() {
        var store = new McpAuthoringSnapshotStore();
        var keys = new java.util.ArrayList<Float>(List.of(0F));
        var row = new java.util.LinkedHashMap<String, Object>(Map.of("keys", keys));
        var entry =
                store.put("a", Map.of(), "rotation_deformer", "head", "native-source-guid", NATIVE, List.of(row), 100);
        keys.add(1F);
        row.put("extra", "changed");
        assertEquals(Map.of("keys", List.of(0F)), entry.rows().get(0));
        assertThrows(
                UnsupportedOperationException.class, () -> entry.rows().get(0).put("x", 1));
    }

    @Test
    void rejectsOversizedOrUntypedSnapshotsBeforeRetainingThem() {
        var store = new McpAuthoringSnapshotStore();
        assertThrows(
                IllegalArgumentException.class, () -> put(store, "a", McpAuthoringSnapshotStore.MAX_ENTRY_BYTES + 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> store.put("a", Map.of(), "rotation", "head", "source", "not-native", List.of(), 1));
    }

    private static McpAuthoringSnapshotStore.Entry put(McpAuthoringSnapshotStore store, String session, int size) {
        return store.put(
                session,
                Map.of("documentId", "document-1"),
                "rotation_deformer",
                "head",
                "native-source-guid",
                NATIVE,
                List.of(),
                size);
    }
}
