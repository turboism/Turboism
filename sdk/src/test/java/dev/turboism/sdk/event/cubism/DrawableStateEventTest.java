package dev.turboism.sdk.event.cubism;

import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.BlendMode;
import dev.turboism.sdk.cubism.model.Color;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.FloatSequence;
import dev.turboism.sdk.cubism.model.IntSequence;
import dev.turboism.sdk.cubism.model.Point2;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DrawableStateEventTest {
    @Test
    void visibilityBeforeSealsMutationAfterCallback() {
        final DrawableVisibilityEvent.Before retained;
        try (DrawableVisibilityEvent.Before.Callback callback =
            DrawableVisibilityEvent.Before.openCallback(drawable(), true, false)) {
            retained = callback.event();
            retained.setVisible(true);
            assertTrue(retained.visible());
            assertTrue(retained.requestedVisible());
        }

        assertThrows(IllegalStateException.class, () -> retained.setVisible(false));
    }

    @Test
    void lockBeforeSealsMutationAfterCallback() {
        final DrawableLockEvent.Before retained;
        try (DrawableLockEvent.Before.Callback callback =
            DrawableLockEvent.Before.openCallback(drawable(), false, true)) {
            retained = callback.event();
            retained.setLocked(false);
            assertFalse(retained.locked());
            assertFalse(retained.requestedLocked());
        }

        assertThrows(IllegalStateException.class, () -> retained.setLocked(true));
    }

    @Test
    void geometryBeforeSealsMutationAfterCallback() {
        final ArtMeshGeometry requested = geometry(1.0F);
        final ArtMeshGeometry replacement = geometry(2.0F);
        final DrawableGeometryEvent.Before retained;
        try (DrawableGeometryEvent.Before.Callback callback =
            DrawableGeometryEvent.Before.openCallback(drawable(), requested, requested)) {
            retained = callback.event();
            retained.setGeometry(replacement);
            assertSame(requested, retained.requestedGeometry());
            assertSame(replacement, retained.geometry());
        }

        assertThrows(
            IllegalStateException.class,
            () -> retained.setGeometry(geometry(3.0F))
        );
    }

    private static ArtMeshGeometry geometry(final float position) {
        return new ArtMeshGeometry(
            java.util.List.of(
                new Point2(position, position),
                new Point2(position + 1.0F, position)
            ),
            java.util.List.of(new Point2(0.0F, 0.0F), new Point2(1.0F, 0.0F)),
            java.util.List.of()
        );
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

    private static IntSequence ints(final int... values) {
        return new IntSequence() {
            @Override public int size() { return values.length; }
            @Override public int get(final int index) { return values[index]; }
        };
    }

    private static FloatSequence sequence(final float... values) {
        return new FloatSequence() {
            @Override public int size() { return values.length; }
            @Override public float get(final int index) { return values[index]; }
        };
    }

    private static FloatSequence floats() {
        return sequence();
    }
}
