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
    private final DetachedValue<Color> multiplyColor;
    private final DetachedValue<Color> screenColor;
    private final DetachedValue<Integer> parentPartIndex;
    private final DetachedValue<Integer> parentDeformerIndex;

    DetachedDeformer(final Deformer source, final float opacity) {
        id = source.id();
        this.opacity = opacity;
        multiplyColor = DetachedValue.capture("multiplyColor", source::multiplyColor);
        screenColor = DetachedValue.capture("screenColor", source::screenColor);
        parentPartIndex = DetachedValue.capture("parentPartIndex", source::parentPartIndex);
        parentDeformerIndex = DetachedValue.capture("parentDeformerIndex", source::parentDeformerIndex);
    }

    static DetachedDeformer capture(final Deformer deformer, final float opacity) {
        Objects.requireNonNull(deformer, "deformer");
        if (deformer instanceof dev.turboism.sdk.cubism.model.WarpDeformer warp) {
            return DetachedWarpDeformer.capture(warp, opacity);
        }
        if (deformer instanceof dev.turboism.sdk.cubism.model.RotationDeformer rotation) {
            return DetachedRotationDeformer.capture(rotation, opacity);
        }
        return new DetachedDeformer(deformer, opacity);
    }

    @Override public DeformerId id() { return id; }
    @Override public float getOpacity() { return opacity; }
    @Override public Color multiplyColor() { return multiplyColor.get(); }
    @Override public Color screenColor() { return screenColor.get(); }
    @Override public int parentPartIndex() { return parentPartIndex.get(); }
    @Override public int parentDeformerIndex() { return parentDeformerIndex.get(); }
    @Override public IntSequence parameters() { return EMPTY_INTS; }
    @Override public void setOpacity(final float opacity) { throw detached(); }

    private static UnsupportedOperationException detached() {
        return new UnsupportedOperationException(
            "Event Deformer snapshots are read-only and host-detached."
        );
    }
}
