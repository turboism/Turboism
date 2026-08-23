package dev.turboism.sdk.event.cubism;

import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.model.BlendMode;
import dev.turboism.sdk.cubism.model.Color;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.FloatSequence;
import dev.turboism.sdk.cubism.model.IntSequence;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DrawableOpacityEventTest {
    @Test
    void beforeSealsMutationAfterCallback() {
        final DrawableOpacityEvent.Before retained;
        try (DrawableOpacityEvent.Before.Callback callback =
            DrawableOpacityEvent.Before.openCallback(drawable(), 1.0F, 0.5F)) {
            retained = callback.event();
            retained.setOpacity(0.75F);
            assertEquals(0.75F, retained.opacity());
        }

        assertThrows(IllegalStateException.class, () -> retained.setOpacity(1.0F));
    }

    private static Drawable drawable() {
        return new Drawable() {
            @Override public ArtMeshId id() { return new ArtMeshId("ArtMeshA"); }
            @Override public byte constantFlag() { return 0; }
            @Override public byte dynamicFlag() { return 0; }
            @Override public BlendMode blendMode() { return BlendMode.NORMAL; }
            @Override public int textureIndex() { return 0; }
            @Override public int drawOrder() { return 0; }
            @Override public int renderOrder() { return 0; }
            @Override public float getOpacity() { return 0.5F; }
            @Override public IntSequence masks() { return ints(); }
            @Override public FloatSequence vertexPositions() { return floats(); }
            @Override public FloatSequence vertexUvs() { return floats(); }
            @Override public IntSequence indices() { return ints(); }
            @Override public Color multiplyColor() { return new Color(1, 1, 1, 1); }
            @Override public Color screenColor() { return new Color(0, 0, 0, 1); }
            @Override public int parentPartIndex() { return -1; }
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

    private static FloatSequence floats() {
        return new FloatSequence() {
            @Override public int size() { return 0; }
            @Override public float get(final int index) { throw new IndexOutOfBoundsException(index); }
        };
    }
}
