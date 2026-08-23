package dev.turboism.adapter.cubism.lifecycle;

import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.model.Color;
import dev.turboism.sdk.cubism.model.Deformer;
import dev.turboism.sdk.cubism.model.IntSequence;

import java.util.Objects;

/** Immutable, host-detached Deformer projection for event delivery. */
class DetachedDeformer implements Deformer {
    private static final IntSequence EMPTY_INTS = new IntSequence() {
        @Override public int size() { return 0; }
        @Override public int get(final int index) { throw new IndexOutOfBoundsException(index); }
    };

    private final DeformerId id;
    private final float opacity;
    private final Color multiplyColor;
    private final Color screenColor;
    private final int parentPartIndex;
    private final int parentDeformerIndex;

    DetachedDeformer(final Deformer source, final float opacity) {
        id = source.id();
        this.opacity = opacity;
        multiplyColor = optional(
            source::multiplyColor,
            new Color(1.0F, 1.0F, 1.0F, 1.0F)
        );
        screenColor = optional(
            source::screenColor,
            new Color(0.0F, 0.0F, 0.0F, 1.0F)
        );
        parentPartIndex = optional(source::parentPartIndex, -1);
        parentDeformerIndex = optional(source::parentDeformerIndex, -1);
    }

    static DetachedDeformer capture(final Deformer deformer, final float opacity) {
        return new DetachedDeformer(Objects.requireNonNull(deformer, "deformer"), opacity);
    }

    @Override public DeformerId id() { return id; }
    @Override public float getOpacity() { return opacity; }
    @Override public Color multiplyColor() { return multiplyColor; }
    @Override public Color screenColor() { return screenColor; }
    @Override public int parentPartIndex() { return parentPartIndex; }
    @Override public int parentDeformerIndex() { return parentDeformerIndex; }
    @Override public IntSequence parameters() { return EMPTY_INTS; }
    @Override public void setOpacity(final float opacity) { throw detached(); }

    private static <T> T optional(
        final java.util.function.Supplier<T> supplier,
        final T fallback
    ) {
        try {
            return supplier.get();
        } catch (UnsupportedOperationException ignored) {
            return fallback;
        }
    }

    private static int optional(
        final java.util.function.IntSupplier supplier,
        final int fallback
    ) {
        try {
            return supplier.getAsInt();
        } catch (UnsupportedOperationException ignored) {
            return fallback;
        }
    }

    private static UnsupportedOperationException detached() {
        return new UnsupportedOperationException(
            "Event Deformer snapshots are read-only and host-detached."
        );
    }
}
