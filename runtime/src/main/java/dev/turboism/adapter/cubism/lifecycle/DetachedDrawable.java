package dev.turboism.adapter.cubism.lifecycle;

import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.model.BlendMode;
import dev.turboism.sdk.cubism.model.Color;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.FloatSequence;
import dev.turboism.sdk.cubism.model.IntSequence;

import java.util.Objects;

/** Immutable, host-detached Drawable projection for event delivery. */
final class DetachedDrawable implements Drawable {
    private static final IntSequence EMPTY_INTS = new IntSequence() {
        @Override public int size() { return 0; }
        @Override public int get(final int index) { throw new IndexOutOfBoundsException(index); }
    };
    private static final FloatSequence EMPTY_FLOATS = new FloatSequence() {
        @Override public int size() { return 0; }
        @Override public float get(final int index) { throw new IndexOutOfBoundsException(index); }
    };

    private final ArtMeshId id;
    private final float opacity;
    private final byte constantFlag;
    private final byte dynamicFlag;
    private final BlendMode blendMode;
    private final int textureIndex;
    private final int drawOrder;
    private final int renderOrder;
    private final Color multiplyColor;
    private final Color screenColor;
    private final int parentPartIndex;
    private final int parentDeformerIndex;

    private DetachedDrawable(final Drawable source, final float opacity) {
        id = source.id();
        this.opacity = opacity;
        constantFlag = source.constantFlag();
        dynamicFlag = source.dynamicFlag();
        blendMode = source.blendMode();
        textureIndex = source.textureIndex();
        drawOrder = source.drawOrder();
        renderOrder = source.renderOrder();
        multiplyColor = source.multiplyColor();
        screenColor = source.screenColor();
        parentPartIndex = source.parentPartIndex();
        parentDeformerIndex = source.parentDeformerIndex();
    }

    static DetachedDrawable capture(final Drawable drawable, final float opacity) {
        return new DetachedDrawable(Objects.requireNonNull(drawable, "drawable"), opacity);
    }

    @Override public ArtMeshId id() { return id; }
    @Override public byte constantFlag() { return constantFlag; }
    @Override public byte dynamicFlag() { return dynamicFlag; }
    @Override public BlendMode blendMode() { return blendMode; }
    @Override public int textureIndex() { return textureIndex; }
    @Override public int drawOrder() { return drawOrder; }
    @Override public int renderOrder() { return renderOrder; }
    @Override public float getOpacity() { return opacity; }
    @Override public IntSequence masks() { return EMPTY_INTS; }
    @Override public FloatSequence vertexPositions() { return EMPTY_FLOATS; }
    @Override public FloatSequence vertexUvs() { return EMPTY_FLOATS; }
    @Override public IntSequence indices() { return EMPTY_INTS; }
    @Override public Color multiplyColor() { return multiplyColor; }
    @Override public Color screenColor() { return screenColor; }
    @Override public int parentPartIndex() { return parentPartIndex; }
    @Override public int parentDeformerIndex() { return parentDeformerIndex; }
    @Override public IntSequence parameters() { return EMPTY_INTS; }
    @Override public void setOpacity(final float opacity) { throw detached(); }

    private static UnsupportedOperationException detached() {
        return new UnsupportedOperationException(
            "Event Drawable snapshots are read-only and host-detached."
        );
    }
}
