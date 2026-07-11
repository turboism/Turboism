package dev.turboism.sdk.cubism;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClipMaskSnapshotTest {

    @Test
    void exposesArtMeshCentricShapeAndPreservesOrderedMaskSources() {
        ClipMaskSnapshot snapshot = new ClipMaskSnapshot(
            "target-mesh",
            List.of("mask-source-b", "mask-source-a"),
            true
        );

        assertEquals("target-mesh", snapshot.targetMeshId());
        assertEquals(List.of("mask-source-b", "mask-source-a"), snapshot.orderedMaskSourceIds());
        assertTrue(snapshot.inverted());
    }

    @Test
    void defensivelyCopiesOrderedMaskSources() {
        List<String> sourceIds = new ArrayList<>(List.of("mask-source"));
        ClipMaskSnapshot snapshot = new ClipMaskSnapshot("target-mesh", sourceIds, false);

        sourceIds.add("late-source");

        assertEquals(List.of("mask-source"), snapshot.orderedMaskSourceIds());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.orderedMaskSourceIds().add("other"));
        assertFalse(snapshot.inverted());
    }

    @Test
    void rejectsBlankTargetAndInvalidMaskSourceIds() {
        assertThrows(NullPointerException.class, () -> new ClipMaskSnapshot(null, List.of(), false));
        assertThrows(IllegalArgumentException.class, () -> new ClipMaskSnapshot(" ", List.of(), false));
        assertThrows(NullPointerException.class, () -> new ClipMaskSnapshot("target", null, false));
        assertThrows(NullPointerException.class, () -> new ClipMaskSnapshot("target", List.of("source", null), false));
        assertThrows(IllegalArgumentException.class, () -> new ClipMaskSnapshot("target", List.of("source", " "), false));
    }
}
