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
    private final Field<Byte> constantFlag;
    private final Field<Byte> dynamicFlag;
    private final Field<BlendMode> blendMode;
    private final Field<Integer> textureIndex;
    private final int drawOrder;
    private final Field<Integer> renderOrder;
    private final Field<Color> multiplyColor;
    private final Field<Color> screenColor;
    private final int parentPartIndex;
    private final int parentDeformerIndex;

    private DetachedDrawable(final Drawable source, final float opacity) {
        id = source.id();
        this.opacity = opacity;
        // Evaluated-overlay fields fail closed on hosts without a Core projection
        // (e.g. Editor-bound models expose no CubismModel). The capture keeps the
        // failure verbatim and the getter rethrows it, so events still deliver the
        // fields that genuinely exist instead of aborting the whole lifecycle write.
        constantFlag = Field.read(source::constantFlag);
        dynamicFlag = Field.read(source::dynamicFlag);
        blendMode = Field.read(source::blendMode);
        textureIndex = Field.read(source::textureIndex);
        drawOrder = source.drawOrder();
        renderOrder = Field.read(source::renderOrder);
        multiplyColor = Field.read(source::multiplyColor);
        screenColor = Field.read(source::screenColor);
        parentPartIndex = source.parentPartIndex();
        parentDeformerIndex = source.parentDeformerIndex();
    }

    /** A field captured at event time: either its value or the failure it produced. */
    private static final class Field<T> {
        private final T value;
        private final RuntimeException failure;

        private Field(final T value, final RuntimeException failure) {
            this.value = value;
            this.failure = failure;
        }

        private static <T> Field<T> read(final java.util.function.Supplier<T> source) {
            try {
                return new Field<>(source.get(), null);
            } catch (RuntimeException failure) {
                return new Field<>(null, failure);
            }
        }

        private T get() {
            if (failure != null) throw failure;
            return value;
        }
    }

    static DetachedDrawable capture(final Drawable drawable, final float opacity) {
        return new DetachedDrawable(Objects.requireNonNull(drawable, "drawable"), opacity);
    }

    @Override public ArtMeshId id() { return id; }
    @Override public byte constantFlag() { return constantFlag.get(); }
    @Override public byte dynamicFlag() { return dynamicFlag.get(); }
    @Override public BlendMode blendMode() { return blendMode.get(); }
    @Override public int textureIndex() { return textureIndex.get(); }
    @Override public int drawOrder() { return drawOrder; }
    @Override public int renderOrder() { return renderOrder.get(); }
    @Override public float getOpacity() { return opacity; }
    @Override public IntSequence masks() { return EMPTY_INTS; }
    @Override public FloatSequence vertexPositions() { return EMPTY_FLOATS; }
    @Override public FloatSequence vertexUvs() { return EMPTY_FLOATS; }
    @Override public IntSequence indices() { return EMPTY_INTS; }
    @Override public Color multiplyColor() { return multiplyColor.get(); }
    @Override public Color screenColor() { return screenColor.get(); }
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
