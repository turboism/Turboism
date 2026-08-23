package dev.turboism.sdk.event.cubism;

import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.model.Deformer;
import dev.turboism.sdk.cubism.model.IntSequence;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeformerOpacityEventTest {
    @Test
    void beforeSealsMutationAfterCallback() {
        final DeformerOpacityEvent.Before retained;
        try (DeformerOpacityEvent.Before.Callback callback =
            DeformerOpacityEvent.Before.openCallback(deformer(), 1.0F, 0.5F)) {
            retained = callback.event();
            retained.setOpacity(0.75F);
            assertEquals(0.75F, retained.opacity());
        }

        assertThrows(IllegalStateException.class, () -> retained.setOpacity(1.0F));
    }

    private static Deformer deformer() {
        return new Deformer() {
            @Override public DeformerId id() { return new DeformerId("WarpA"); }
            @Override public int parentDeformerIndex() { return -1; }
            @Override public IntSequence parameters() { return ints(); }
        };
    }

    private static IntSequence ints() {
        return new IntSequence() {
            @Override public int size() { return 0; }
            @Override public int get(final int index) { throw new IndexOutOfBoundsException(index); }
        };
    }
}
