package dev.turboism.sdk.cubism.clipmask;

import dev.turboism.sdk.cubism.id.ArtMeshId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClipMaskReplacementContractTest {
    @Test
    void replacementCopiesListsAndRejectsInvalidMaskLists() {
        final List<ArtMeshId> expected = new ArrayList<>(List.of(new ArtMeshId("mask")));
        final List<ArtMeshId> replacement = new ArrayList<>(List.of(new ArtMeshId("other")));
        final ClipMaskReplacement value = new ClipMaskReplacement(
            new ArtMeshId("target"), expected, false, replacement, true
        );

        expected.clear();
        replacement.clear();

        assertEquals(List.of(new ArtMeshId("mask")), value.expectedMaskArtMeshIds());
        assertEquals(List.of(new ArtMeshId("other")), value.replacementMaskArtMeshIds());
        assertThrows(IllegalArgumentException.class, () -> new ClipMaskReplacement(
            new ArtMeshId("target"),
            List.of(new ArtMeshId("mask"), new ArtMeshId("mask")),
            false,
            List.of(new ArtMeshId("other")),
            false
        ));
        assertThrows(IllegalArgumentException.class, () -> new ClipMaskReplacement(
            new ArtMeshId("target"),
            List.of(),
            false,
            List.of(new ArtMeshId("target")),
            false
        ));
        assertThrows(IllegalArgumentException.class, () -> new ClipMaskReplacement(
            new ArtMeshId("target"), List.of(), false, List.of(), false
        ));
    }
}
