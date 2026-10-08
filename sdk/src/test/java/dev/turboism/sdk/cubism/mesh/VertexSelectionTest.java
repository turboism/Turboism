package dev.turboism.sdk.cubism.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class VertexSelectionTest {

    @Test
    void canonicalizesByCopyingSortingAndDeduplicating() {
        final var source = new ArrayList<>(List.of(4, 1, 4, 2));

        final var selection = new VertexSelection(source);
        source.clear();

        assertEquals(List.of(1, 2, 4), selection.indices());
        assertThrows(
                UnsupportedOperationException.class, () -> selection.indices().add(5));
    }

    @Test
    void rejectsNullListsNullElementsAndNegativeIndices() {
        assertThrows(NullPointerException.class, () -> new VertexSelection(null));
        assertThrows(NullPointerException.class, () -> new VertexSelection(Arrays.asList(1, null)));
        assertThrows(IllegalArgumentException.class, () -> new VertexSelection(List.of(-1)));
    }

    @Test
    void exposesCanonicalEmptySelection() {
        assertEquals(List.of(), VertexSelection.empty().indices());
        assertEquals(VertexSelection.empty(), new VertexSelection(List.of()));
    }
}
