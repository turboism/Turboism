package dev.turboism.adapter.cubism.mesh;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

final class NativeMeshMirrorBridgeTest {

    @AfterEach
    void clearBridge() {
        NativeMeshMirrorBridge.uninstall();
    }

    @Test
    void preservesNativeResultsAtZeroAndUsesTheRotatedAxisOtherwise() {
        final RuntimeMeshMirrorAxisService axis = new RuntimeMeshMirrorAxisService();
        NativeMeshMirrorBridge.install(axis, new RuntimeMeshEditUiService());
        final Point original = new Point(9.0f, 9.0f);
        final Point source = new Point(1.0f, 1.0f);

        assertSame(original, NativeMeshMirrorBridge.adjustPoint(original, new State(true), source));

        axis.setCurrentAngleDegrees(45.0f);
        assertSame(original, NativeMeshMirrorBridge.adjustPoint(original, new State(true), source));
        assertEquals(false, NativeMeshMirrorBridge.adjustHit(
            false, new State(true), new Point(2.0f, -1.95f), 0.1f
        ));
    }

    public static final class Point {
        private final float x;
        private final float y;

        public Point(final float x, final float y) {
            this.x = x;
            this.y = y;
        }

        public float getX() { return x; }
        public float getY() { return y; }
    }

    public record State(boolean isVertical) { }
}
