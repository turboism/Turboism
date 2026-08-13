package dev.turboism.sdk.cubism.service.clipmask;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CubismClipMaskServiceContractTest {

    @Test
    void recordKeepsFieldSemanticsAndImmutableMaskList() {
        final List<String> masks = new ArrayList<>(List.of("mask-a", "mask-b"));
        final CubismClipMaskService.ClipMaskRecord record =
            new CubismClipMaskService.ClipMaskRecord("guid-1", "artmesh-1", "Face", true, masks);

        assertEquals("guid-1", record.guid());
        assertEquals("artmesh-1", record.id());
        assertEquals("Face", record.displayName());
        assertTrue(record.inverted());
        assertEquals(List.of("mask-a", "mask-b"), record.orderedMaskGuids());
        assertTrue(record.hasMasks());

        masks.add("mask-c");
        assertEquals(List.of("mask-a", "mask-b"), record.orderedMaskGuids());
        assertThrows(UnsupportedOperationException.class, () -> record.orderedMaskGuids().add("x"));
    }

    @Test
    void recordWithoutMasksReportsHasMasksFalse() {
        final CubismClipMaskService.ClipMaskRecord record =
            new CubismClipMaskService.ClipMaskRecord("guid-2", "", "Eyes", false, List.of());

        assertFalse(record.hasMasks());
        assertTrue(record.orderedMaskGuids().isEmpty());
    }

    @Test
    void recordRejectsBlankGuid() {
        assertThrows(IllegalArgumentException.class,
            () -> new CubismClipMaskService.ClipMaskRecord(" ", "id", "name", false, List.of()));
        assertThrows(NullPointerException.class,
            () -> new CubismClipMaskService.ClipMaskRecord(null, "id", "name", false, List.of()));
    }

    @Test
    void recordRejectsBlankMaskElementsAndNullList() {
        assertThrows(IllegalArgumentException.class,
            () -> new CubismClipMaskService.ClipMaskRecord("g", "id", "name", false, List.of("ok", " ")));
        assertThrows(NullPointerException.class,
            () -> new CubismClipMaskService.ClipMaskRecord("g", "id", "name", false, null));
        assertThrows(NullPointerException.class,
            () -> new CubismClipMaskService.ClipMaskRecord("g", "id", "name", false, List.of("ok", null)));
    }

    @Test
    void recordNormalizesNullIdAndDisplayNameToEmptyString() {
        final CubismClipMaskService.ClipMaskRecord record =
            new CubismClipMaskService.ClipMaskRecord("guid-3", null, null, false, List.of("m"));

        assertEquals("", record.id());
        assertEquals("", record.displayName());
    }
}
