package dev.turboism.sdk.event.cubism;

import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.model.Deformer;
import dev.turboism.sdk.cubism.model.IntSequence;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeformerStateEventTest {
    @Test
    void visibilityBeforeSealsMutationAfterCallback() {
        final DeformerVisibilityEvent.Before retained;
        try (DeformerVisibilityEvent.Before.Callback callback =
            DeformerVisibilityEvent.Before.openCallback(deformer(), true, false)) {
            retained = callback.event();
            retained.setVisible(true);
            assertTrue(retained.visible());
            assertTrue(retained.requestedVisible());
        }

        assertThrows(IllegalStateException.class, () -> retained.setVisible(false));
    }

    @Test
    void lockBeforeSealsMutationAfterCallback() {
        final DeformerLockEvent.Before retained;
        try (DeformerLockEvent.Before.Callback callback =
            DeformerLockEvent.Before.openCallback(deformer(), false, true)) {
            retained = callback.event();
            retained.setLocked(false);
            assertFalse(retained.locked());
            assertFalse(retained.requestedLocked());
        }

        assertThrows(IllegalStateException.class, () -> retained.setLocked(true));
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
