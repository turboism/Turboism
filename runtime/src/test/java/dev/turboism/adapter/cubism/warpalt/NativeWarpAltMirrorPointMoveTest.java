package dev.turboism.adapter.cubism.warpalt;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviour contract of the converged point-write mirror ({@code mirrorPointMove})
 * against a stub doc-level point reference. User-facing axis semantics
 * (r32 feedback): 垂直镜像 moves the counterpart vertically (up/down, y
 * negated, x follows); 水平镜像 moves it horizontally (left/right, x negated,
 * y follows).
 */
final class NativeWarpAltMirrorPointMoveTest {

    private static final String STUB_NAME = StubPointRef.class.getName();

    @BeforeEach
    void arm() {
        NativeWarpAltMirrorBridge.setBinderClassNameForTesting(STUB_NAME);
        NativeWarpAltMirrorBridge.install();
    }

    @AfterEach
    void tearDown() {
        NativeWarpAltMirrorBridge.uninstall();
    }

    @Test
    void verticalArmedMirrorMovesCounterpartVertically() {
        final StubPointRef moved = StubPointRef.grid6x6().ref(0, 0);
        final StubPointRef counterpart = moved.sibling(5, 0);
        NativeWarpAltMirrorBridge.setArmedAxis(1);
        final var registration = participate();

        // target (0.9, 0.3) from old (0.0, 0.0): dx=0.9, dy=0.3, w=1
        NativeWarpAltMirrorBridge.mirrorPointMove(moved, new StubVector(0.9f, 0.3f), 1.0f);

        // 垂直镜像: partner of (r0,c0) in a 6-tall grid is (r5,c0) at (0.0, 5.0):
        // x follows (+0.9) -> 0.9, y negated (-0.3) -> 4.7
        assertEquals(0.9f, counterpart.positions[60], 1.0e-4f);
        assertEquals(4.7f, counterpart.positions[61], 1.0e-4f);
        registration.close();
        assertFalse(NativeWarpAltMirrorBridge.moveParticipation().hasParticipants());
    }

    @Test
    void horizontalArmedMirrorMovesCounterpartHorizontally() {
        final StubPointRef moved = StubPointRef.grid6x6().ref(1, 2);
        final StubPointRef counterpart = moved.sibling(1, 3);
        NativeWarpAltMirrorBridge.setArmedAxis(2);
        final var registration = participate();

        // dragged (1,2) old (0.2,1.0) -> target (2.4,1.5): dx=2.2, dy=0.5, w=1
        NativeWarpAltMirrorBridge.mirrorPointMove(moved, new StubVector(2.4f, 1.5f), 1.0f);

        // 水平镜像: partner of (r1,c2) in a 6-wide grid is (r1,c3) idx=9 old (0.3,1.0):
        // x negated: 0.3 - 2.2 = -1.9 ; y follows: 1.0 + 0.5 = 1.5
        assertEquals(-1.9f, counterpart.positions[18], 1.0e-4f);
        assertEquals(1.5f, counterpart.positions[19], 1.0e-4f);
        registration.close();
    }

    @Test
    void blendWeightScalesTheCounterpartDisplacement() {
        final StubPointRef moved = StubPointRef.grid6x6().ref(0, 0);
        final StubPointRef counterpart = moved.sibling(5, 0);
        NativeWarpAltMirrorBridge.setArmedAxis(1);
        final var registration = participate();

        // w=0.5: 垂直镜像 -> x follows (+0.45) -> 0.45 ; y negated (-0.15) -> 4.85
        NativeWarpAltMirrorBridge.mirrorPointMove(moved, new StubVector(0.9f, 0.3f), 0.5f);

        assertEquals(0.45f, counterpart.positions[60], 1.0e-4f);
        assertEquals(4.85f, counterpart.positions[61], 1.0e-4f);
        registration.close();
    }

    @Test
    void mirrorWritesOnlyTheCounterpartSlots() {
        // (r2,c2) idx=14, old (0.2,2.0); 垂直 counterpart (r3,c2) idx=20, old (0.2,3.0).
        final StubPointRef moved = StubPointRef.grid6x6().ref(2, 2);
        NativeWarpAltMirrorBridge.setArmedAxis(1);
        final var registration = participate();

        NativeWarpAltMirrorBridge.mirrorPointMove(moved, new StubVector(9.0f, 9.0f), 1.0f);

        // dx=8.8, dy=7.0 -> counterpart x follows: 0.2 + 8.8 = 9.0 ; y negated: 3.0 - 7.0 = -4.0
        assertEquals(9.0f, moved.positions[40], 1.0e-4f);
        assertEquals(-4.0f, moved.positions[41], 1.0e-4f);
        // The dragged point's own slots are untouched by the bridge: the native
        // moveToOnLocal body performs that write after the hook returns.
        assertEquals(0.2f, moved.positions[28], 1.0e-6f);
        assertEquals(2.0f, moved.positions[29], 1.0e-6f);
        registration.close();
    }

    @Test
    void mirrorAppliesUnderNativeCtrlSelfOnlyDrags() {
        // Native Ctrl semantics: the gesture moves the control point without
        // deforming the child shapes. The mirrored counterpart write is a plain
        // positions-array update on the same gesture envelope, so both cage
        // points move while the native deformation suppression covers it all.
        final StubPointRef moved = StubPointRef.grid6x6().ref(0, 0);
        final StubPointRef counterpart = moved.sibling(5, 0);
        NativeWarpAltMirrorBridge.setArmedAxis(1);
        final var registration = participate();

        NativeWarpAltMirrorBridge.mirrorPointMove(moved, new StubVector(0.9f, 0.3f), 1.0f);
        assertEquals(4.7f, counterpart.positions[61], 1.0e-4f);
        registration.close();
    }

    @Test
    void inertWithoutParticipantsOrWithoutArm() {
        final StubPointRef moved = StubPointRef.grid6x6().ref(0, 0);
        final StubPointRef counterpart = moved.sibling(5, 0);
        final float[] before = counterpart.positions.clone();

        // no participant
        NativeWarpAltMirrorBridge.mirrorPointMove(moved, new StubVector(0.9f, 0.3f), 1.0f);
        assertEquals(before[60], counterpart.positions[60], 1.0e-6f);

        NativeWarpAltMirrorBridge.setArmedAxis(1);
        final var registration = participate();
        // disarmed
        NativeWarpAltMirrorBridge.setArmedAxis(0);
        NativeWarpAltMirrorBridge.mirrorPointMove(moved, new StubVector(0.9f, 0.3f), 1.0f);
        assertEquals(before[60], counterpart.positions[60], 1.0e-6f);

        NativeWarpAltMirrorBridge.setArmedAxis(1);
        NativeWarpAltMirrorBridge.mirrorPointMove(moved, new StubVector(0.9f, 0.3f), 1.0f);
        assertEquals(0.9f, counterpart.positions[60], 1.0e-4f);
        assertEquals(4.7f, counterpart.positions[61], 1.0e-4f);
        registration.close();
    }

    @Test
    void subEpsilonJitterDoesNotMirror() {
        final StubPointRef moved = StubPointRef.grid6x6().ref(0, 0);
        final StubPointRef counterpart = moved.sibling(5, 0);
        final var registration = participate();

        NativeWarpAltMirrorBridge.mirrorPointMove(
            moved, new StubVector(5.0e-4f, 5.0e-4f), 1.0f);

        // Sub-epsilon jitter leaves both the dragged point and its counterpart
        // untouched (the stub's old positions are 0.0/5.0 for (r5,c0)).
        assertEquals(0.0f, counterpart.positions[60], 1.0e-6f);
        assertEquals(5.0f, counterpart.positions[61], 1.0e-6f);
        registration.close();
    }

    @Test
    void participationRegistryTracksRegistrations() {
        final RuntimeWarpAltMirrorParticipation participation =
            NativeWarpAltMirrorBridge.moveParticipation();
        assertFalse(participation.hasParticipants());
        final var first = participation.participate();
        final var second = participation.participate();
        assertTrue(participation.hasParticipants());
        first.close();
        assertTrue(participation.hasParticipants());
        second.close();
        assertFalse(participation.hasParticipants());
    }

    private static dev.turboism.sdk.plugin.Registration participate() {
        return NativeWarpAltMirrorBridge.moveParticipation().participate();
    }

    /** Minimal local target vector shape: getX/getY. */
    static final class StubVector {
        final float x;
        final float y;

        StubVector(final float x, final float y) {
            this.x = x;
            this.y = y;
        }

        public float getX() { return x; }

        public float getY() { return y; }
    }

    /** Minimal doc-level point reference over a 6x6 grid (5x5 divisions). */
    static final class StubPointRef {
        final float[] positions;
        final int step; // reviewed host behaviour: 0 in the level-2 edit flow
        final int index;

        private StubPointRef(final float[] positions, final int index) {
            this.positions = positions;
            this.index = index;
            this.step = 0;
        }

        static StubPointRef grid6x6() {
            final StubPointRef binder = new StubPointRef(grid72(), 0);
            return binder;
        }

        private static float[] grid72() {
            final float[] positions = new float[72];
            for (int row = 0; row < 6; row++) {
                for (int column = 0; column < 6; column++) {
                    positions[(row * 6 + column) * 2] = column * 0.1f;
                    positions[(row * 6 + column) * 2 + 1] = row * 1.0f;
                }
            }
            return positions;
        }

        StubPointRef ref(final int row, final int column) {
            return new StubPointRef(positions, row * 6 + column);
        }

        StubPointRef sibling(final int row, final int column) {
            return ref(row, column);
        }

        public int a() { return index; }

        public int h() { return step; }

        public float[] g() { return positions; }
    }
}
