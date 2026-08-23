package dev.turboism.sdk.event.cubism;

import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.model.IntSequence;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.cubism.model.WarpDeformer;
import dev.turboism.sdk.cubism.model.WarpGrid;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WarpDeformerGridEventTest {
    @Test
    void beforeSealsMutationAfterCallback() {
        final WarpGrid requested = grid(1.0F);
        final WarpGrid replacement = grid(2.0F);
        final WarpDeformerGridEvent.Before retained;
        try (WarpDeformerGridEvent.Before.Callback callback =
            WarpDeformerGridEvent.Before.openCallback(deformer(requested), requested, requested)) {
            retained = callback.event();
            retained.setGrid(replacement);
            assertEquals(replacement, retained.grid());
        }

        assertThrows(IllegalStateException.class, () -> retained.setGrid(grid(3.0F)));
    }

    private static WarpDeformer deformer(final WarpGrid grid) {
        return new WarpDeformer() {
            @Override public DeformerId id() { return new DeformerId("WarpA"); }
            @Override public int parentDeformerIndex() { return -1; }
            @Override public IntSequence parameters() { return ints(); }
            @Override public WarpGrid grid() { return grid; }
            @Override public void replaceGrid(final WarpGrid replacement) { }
        };
    }

    private static WarpGrid grid(final float offset) {
        return new WarpGrid(1, 1, false, List.of(
            new Point2(offset, 0), new Point2(offset + 1, 0),
            new Point2(offset, 1), new Point2(offset + 1, 1)
        ));
    }

    private static IntSequence ints() {
        return new IntSequence() {
            @Override public int size() { return 0; }
            @Override public int get(final int index) { throw new IndexOutOfBoundsException(index); }
        };
    }
}
