package dev.turboism.plugin.clipmaskviewer.b1.domain;

import dev.turboism.sdk.cubism.service.clipmask.CubismClipMaskService.ClipMaskRecord;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClipMaskAnalyzerTest {

    @Test
    void countUniqueMasksCountsDistinctMaskGuidsOnly() {
        final List<ClipMaskRecord> records = List.of(
            record("user-1", "A", false, "mask-1", "mask-2"),
            record("user-2", "B", false, "mask-2", "mask-3")
        );

        assertEquals(3, ClipMaskAnalyzer.countUniqueMasks(records));
    }

    @Test
    void buildMaskUsersIndexesUsersByMaskGuidInRecordOrder() {
        final List<ClipMaskRecord> records = List.of(
            record("user-1", "A", false, "mask-1"),
            record("user-2", "B", false, "mask-1", "mask-2")
        );

        final Map<String, List<ClipMaskRecord>> users = ClipMaskAnalyzer.buildMaskUsers(records);

        assertEquals(List.of("mask-1", "mask-2"), List.copyOf(users.keySet()));
        assertEquals(2, users.get("mask-1").size());
        assertEquals("user-1", users.get("mask-1").get(0).guid());
        assertEquals("user-2", users.get("mask-1").get(1).guid());
        assertEquals(1, users.get("mask-2").size());
    }

    @Test
    void groupByUnorderedMaskSetBucketsSameSetDifferentOrderAndSeparatesInverted() {
        final List<ClipMaskRecord> records = List.of(
            record("user-1", "A", false, "mask-1", "mask-2"),
            record("user-2", "B", false, "mask-2", "mask-1"),
            record("user-3", "C", true, "mask-1", "mask-2"),
            record("user-4", "D", false, "mask-1", "mask-3")
        );

        final Map<String, List<ClipMaskRecord>> dupes =
            ClipMaskAnalyzer.groupByUnorderedMaskSet(records);

        assertEquals(1, dupes.size());
        final List<ClipMaskRecord> bucket = dupes.values().iterator().next();
        assertEquals(2, bucket.size());
        assertEquals("user-1", bucket.get(0).guid());
        assertEquals("user-2", bucket.get(1).guid());
    }

    @Test
    void countOrderConflictsCountsOnlyOrderDifferingUsersPerBucket() {
        final List<ClipMaskRecord> records = List.of(
            record("user-1", "A", false, "mask-1", "mask-2"),
            record("user-2", "B", false, "mask-2", "mask-1"),
            record("user-3", "C", false, "mask-1", "mask-2"),
            record("user-4", "D", false, "mask-9"),
            record("user-5", "E", false, "mask-9")
        );

        // bucket {mask-1;mask-2} has one order-differing member (user-2);
        // bucket {mask-9} has same order -> no conflict.
        assertEquals(1, ClipMaskAnalyzer.countOrderConflicts(records));
    }

    @Test
    void emptyAndNullInputsReturnEmptyResults() {
        assertEquals(0, ClipMaskAnalyzer.countUniqueMasks(List.of()));
        assertEquals(0, ClipMaskAnalyzer.countUniqueMasks(null));
        assertTrue(ClipMaskAnalyzer.buildMaskUsers(List.of()).isEmpty());
        assertTrue(ClipMaskAnalyzer.buildMaskUsers(null).isEmpty());
        assertTrue(ClipMaskAnalyzer.groupByUnorderedMaskSet(List.of()).isEmpty());
        assertTrue(ClipMaskAnalyzer.groupByUnorderedMaskSet(null).isEmpty());
        assertEquals(0, ClipMaskAnalyzer.countOrderConflicts(List.of()));
        assertEquals(0, ClipMaskAnalyzer.countOrderConflicts(null));
    }

    @Test
    void indexByGuidKeepsFirstOccurrenceOrder() {
        final List<ClipMaskRecord> records = List.of(
            record("user-1", "A", false, "mask-1"),
            record("user-2", "B", false, "mask-2")
        );

        final Map<String, ClipMaskRecord> index = ClipMaskAnalyzer.indexByGuid(records);

        assertEquals(List.of("user-1", "user-2"), List.copyOf(index.keySet()));
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
