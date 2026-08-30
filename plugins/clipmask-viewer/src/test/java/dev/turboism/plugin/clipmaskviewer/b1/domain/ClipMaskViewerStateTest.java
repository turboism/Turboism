package dev.turboism.plugin.clipmaskviewer.b1.domain;

import dev.turboism.sdk.cubism.service.clipmask.CubismClipMaskService;
import dev.turboism.sdk.cubism.service.clipmask.CubismClipMaskService.ClipMaskRecord;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClipMaskViewerStateTest {

    @Test
    void refreshDataBuildsRecordsIndexUsersAndDupeBuckets() {
        final ClipMaskViewerState state = new ClipMaskViewerState();
        state.refreshData(service(
            record("user-1", "A", false, "mask-1", "mask-2"),
            record("user-2", "B", false, "mask-2", "mask-1"),
            record("user-3", "C", false, "mask-9"),
            record("user-4", "D", false)
        ));

        assertEquals(4, state.records().size());
        assertEquals(4, state.byGuid().size());
        assertEquals(3, state.maskUsers().size());
        assertEquals(1, state.dupeBuckets().size());
        assertEquals(1, state.countOrderConflicts());
        assertEquals(3, state.countUniqueMasks());
        assertEquals(3, state.countWithMasks());
    }

    @Test
    void filterRelatedKeepsOnlyMasksAndUsers() {
        final ClipMaskViewerState state = new ClipMaskViewerState();
        state.refreshData(service(
            record("mask-only", "M", false),
            record("user-1", "A", false, "mask-only")
        ));

        final List<ClipMaskRecord> related = state.filterRelated();

        assertEquals(2, related.size());
        assertEquals("mask-only", related.get(0).guid());
        assertEquals("user-1", related.get(1).guid());
    }

    @Test
    void emptyServiceProducesEmptyState() {
        final ClipMaskViewerState state = new ClipMaskViewerState();
        state.refreshData(service());

        assertTrue(state.records().isEmpty());
        assertTrue(state.byGuid().isEmpty());
        assertTrue(state.maskUsers().isEmpty());
        assertTrue(state.dupeBuckets().isEmpty());
        assertEquals(0, state.countUniqueMasks());
        assertEquals(0, state.countOrderConflicts());
        assertTrue(state.filterRelated().isEmpty());
    }

    @Test
    void analyzesLargeDetachedRelationshipSetIntoCachedLinearViews() {
        final java.util.ArrayList<ClipMaskRecord> records = new java.util.ArrayList<>();
        for (int index = 0; index < 5_000; index++) {
            records.add(record(
                "user-" + index,
                "ArtMesh" + index,
                false,
                "mask-" + (index % 250),
                "mask-" + ((index + 1) % 250)
            ));
        }

        final ClipMaskViewerState.Snapshot snapshot = ClipMaskViewerState.analyze(records);

        assertEquals(5_000, snapshot.records().size());
        assertEquals(250, snapshot.maskUsers().size());
        assertEquals(5_000, snapshot.relatedRecords().size());
        assertEquals(5_000, snapshot.countWithMasks());
        assertEquals(250, snapshot.countUniqueMasks());
        assertTrue(snapshot.records() != records);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.records().add(
            record("extra", "extra", false)
        ));
    }

    @Test
    void clearResetsStateAfterServiceFailure() {
        final ClipMaskViewerState state = new ClipMaskViewerState();
        state.refreshData(service(record("user-1", "A", false, "mask-1")));
        assertEquals(1, state.records().size());

        state.clear();

        assertTrue(state.records().isEmpty());
        assertTrue(state.byGuid().isEmpty());
        assertTrue(state.maskUsers().isEmpty());
        assertTrue(state.dupeBuckets().isEmpty());
    }

    private static CubismClipMaskService service(final ClipMaskRecord... records) {
        return () -> List.of(records);
    }

    private static ClipMaskRecord record(
        final String guid,
        final String id,
        final boolean inverted,
        final String... masks
    ) {
        return new ClipMaskRecord(guid, id, guid, inverted, List.of(masks));
    }
}
