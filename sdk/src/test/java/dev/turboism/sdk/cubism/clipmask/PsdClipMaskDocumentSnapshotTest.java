package dev.turboism.sdk.cubism.clipmask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.cubism.clipmask.PsdClipMaskDocumentSnapshot.PsdLayerSnapshot;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class PsdClipMaskDocumentSnapshotTest {

    @Test
    void exposesImmutableRecursiveLayerBindingsAndKeepsTheLegacyConstructor() {
        final PsdLayerSnapshot child = new PsdLayerSnapshot(
            "clip",
            "Clip",
            true,
            List.of(new ArtMeshId("ArtMesh")),
            Optional.of("base"),
            List.of()
        );
        final PsdLayerSnapshot root = new PsdLayerSnapshot(
            "group", "Group", true, List.of(), Optional.empty(), List.of(child)
        );
        final PsdClipMaskDocumentSnapshot document = new PsdClipMaskDocumentSnapshot(
            "psd", "textures/source.psd", List.of(root)
        );

        assertEquals(List.of(child), document.layers().get(0).children());
        assertEquals(List.of(new ArtMeshId("ArtMesh")), child.artMeshIds());
        assertEquals(Optional.of("base"), child.clippingBaseLayerId());
        assertTrue(child.clipping(), "the convenience constructor derives clipping from the base layer");
        assertFalse(root.clipping());
        assertEquals(List.of(), new PsdLayerSnapshot("legacy", "Legacy", true).artMeshIds());
        assertFalse(new PsdLayerSnapshot("legacy", "Legacy", true).clipping());
        assertThrows(UnsupportedOperationException.class, () -> document.layers().add(root));
        assertThrows(UnsupportedOperationException.class, () -> root.children().add(child));
        assertThrows(IllegalArgumentException.class, () -> new PsdLayerSnapshot(
            "clip", "Clip", true, List.of(), Optional.of(" "), List.of()
        ));
    }

    @Test
    void representsClippingWithoutAResolvableBaseExplicitly() {
        final PsdLayerSnapshot clipped = new PsdLayerSnapshot(
            "clipped", "Clipped", true, true, List.of(new ArtMeshId("ArtMesh")),
            Optional.empty(), List.of()
        );

        assertTrue(clipped.clipping());
        assertEquals(Optional.empty(), clipped.clippingBaseLayerId());
    }

    @Test
    void rejectsANonClippingLayerThatDeclaresAClippingBase() {
        assertThrows(IllegalArgumentException.class, () -> new PsdLayerSnapshot(
            "clipped", "Clipped", true, false, List.of(),
            Optional.of("base"), List.of()
        ));
    }
}
