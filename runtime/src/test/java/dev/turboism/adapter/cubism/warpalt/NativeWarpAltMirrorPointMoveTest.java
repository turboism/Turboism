package dev.turboism.adapter.cubism.warpalt;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviour contract of the converged point-write mirror ({@code mirrorPointMove})
 * against a stub doc-level point reference. The stub exposes the reviewed Kotlin
 * surface: private {@code step} field, public {@code h()} index, {@code g()}
 * positions array, and a {@code moveToOnLocal}-shaped write.
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
    void verticalMirrorWritesCounterpartIntoTheSamePositionsArray() {
        final StubPointRef moved = StubPointRef.grid6x6().ref(0, 0);
        final StubPointRef counterpart = moved.sibling(0, 5);
        final var registration = participate();

        // target (0.9, 0.3) from old (0.0, 0.0): dx=0.9, dy=0.3, w=1
        NativeWarpAltMirrorBridge.mirrorPointMove(moved, new StubVector(0.9f, 0.3f), 1.0f);

        // vertical partner of (r0,c0) in 6-wide grid is (r0,c5) at (0.5, 0.0):
        // x: 0.5 - 0.9 = -0.4 ; y: 0.0 + 0.3 = 0.3
        assertEquals(-0.4f, counterpart.positions[10], 1.0e-4f);
        assertEquals(0.3f, counterpart.positions[11], 1.0e-4f);
        registration.close();
    }

    @Test
    void horizontalArmedAxisMirrorUsesNegatedY() {
        final StubPointRef moved = StubPointRef.grid6x6().ref(1, 2);
        final StubPointRef counterpart = moved.sibling(4, 2);
        final var registration = participate();
        NativeWarpAltMirrorBridge.setArmedAxis(2);

        // dragged (1,2) old (0.2,1.0) -> target (2.4,1.5): dx=2.2, dy=0.5, w=1
        NativeWarpAltMirrorBridge.mirrorPointMove(moved, new StubVector(2.4f, 1.5f), 1.0f);

        // horizontal partner of (r1,c2) in 6-tall grid is (r4,c2) old (2.0,4.0):
        // x: 2.0 + 0.4 = 2.4 ; y: 4.0 - 0.5 = 3.5
        assertEquals(2.4f, counterpart.positions[52], 1.0e-4f);
        assertEquals(3.5f, counterpart.positions[53], 1.0e-4f);
        registration.close();
    }

    @Test
    void blendWeightScalesTheCounterpartDisplacement() {
        final StubPointRef moved = StubPointRef.grid6x6().ref(0, 0);
        final StubPointRef counterpart = moved.sibling(0, 5);
        final var registration = participate();

        // w=0.5: vertical -> x: 0.5 - 0.45 = 0.05 ; y: 0.0 + 0.15 = 0.15
        NativeWarpAltMirrorBridge.mirrorPointMove(moved, new StubVector(0.9f, 0.3f), 0.5f);

        assertEquals(0.05f, counterpart.positions[10], 1.0e-4f);
        assertEquals(0.15f, counterpart.positions[11], 1.0e-4f);
        registration.close();
    }

    @Test
    void mirrorWritesOnlyTheCounterpartSlots() {
        // (r2,c2) idx=14, old (0.2,2.0); counterpart (r2,c3) idx=15, old (0.3,2.0).
        final StubPointRef moved = StubPointRef.grid6x6().ref(2, 2);
        final var registration = participate();

        NativeWarpAltMirrorBridge.mirrorPointMove(moved, new StubVector(9.0f, 9.0f), 1.0f);

        // dx=8.8, dy=7.0 -> counterpart x: 0.3 - 8.8 = -8.5 ; y: 2.0 + 7.0 = 9.0
        assertEquals(-8.5f, moved.positions[30], 1.0e-4f);
        assertEquals(9.0f, moved.positions[31], 1.0e-4f);
        // The dragged point's own slots are untouched by the bridge: the native
        // moveToOnLocal body performs that write after the hook returns.
        assertEquals(0.2f, moved.positions[28], 1.0e-6f);
        assertEquals(2.0f, moved.positions[29], 1.0e-6f);
        registration.close();
    }

    @Test
    void liveCtrlHoldsTheMirrorForSelfOnlyDrag() {
        final StubPointRef moved = StubPointRef.grid6x6().ref(0, 0);
        final StubPointRef counterpart = moved.sibling(0, 5);
        final var registration = participate();

        // Native Ctrl semantics: self-only adjustment, never mirrored.
        NativeWarpAltMirrorBridge.setLiveCtrlDown(true);
        NativeWarpAltMirrorBridge.mirrorPointMove(moved, new StubVector(0.9f, 0.3f), 1.0f);
        assertEquals(0.5f, counterpart.positions[10], 1.0e-6f);

        NativeWarpAltMirrorBridge.setLiveCtrlDown(false);
        NativeWarpAltMirrorBridge.mirrorPointMove(moved, new StubVector(0.9f, 0.3f), 1.0f);
        assertEquals(-0.4f, counterpart.positions[10], 1.0e-4f);
        registration.close();
    }

    @Test
    void inertWithoutParticipantsOrWithoutAlt() {
        final StubPointRef moved = StubPointRef.grid6x6().ref(0, 0);
        final StubPointRef counterpart = moved.sibling(0, 5);
        final float[] before = counterpart.positions.clone();

        // no participant
        NativeWarpAltMirrorBridge.mirrorPointMove(moved, new StubVector(0.9f, 0.3f), 1.0f);
        assertEquals(before[10], counterpart.positions[10], 1.0e-6f);

        final var registration = participate();
        // disarmed
        NativeWarpAltMirrorBridge.setArmedAxis(0);
        NativeWarpAltMirrorBridge.mirrorPointMove(moved, new StubVector(0.9f, 0.3f), 1.0f);
        assertEquals(before[10], counterpart.positions[10], 1.0e-6f);

        NativeWarpAltMirrorBridge.setArmedAxis(1);
        NativeWarpAltMirrorBridge.mirrorPointMove(moved, new StubVector(0.9f, 0.3f), 1.0f);
        assertEquals(-0.4f, counterpart.positions[10], 1.0e-4f);
        registration.close();
    }

    private static dev.turboism.sdk.plugin.Registration participate() {
        NativeWarpAltMirrorBridge.setArmedAxis(1);
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
        private static final ArrayList<StubPointRef> ALL = new ArrayList<>();
        final float[] positions;
        final int step; // reviewed host behaviour: 0 in the level-2 edit flow
        final int index;

        private StubPointRef(final float[] positions, final int index) {
            this.positions = positions;
            this.index = index;
            this.step = 0;
        }

        static StubPointRef grid6x6() {
            ALL.clear();
            final float[] positions = new float[72];
            for (int row = 0; row < 6; row++) {
                for (int column = 0; column < 6; column++) {
                    positions[(row * 6 + column) * 2] = column * 0.1f;
                    positions[(row * 6 + column) * 2 + 1] = row * 1.0f;
                }
            }
            final StubPointRef root = new StubPointRef(positions, 0);
            ALL.add(root);
            return root;
        }

        StubPointRef ref(final int row, final int column) {
            final StubPointRef ref = new StubPointRef(positions, row * 6 + column);
            ALL.add(ref);
            return ref;
        }

        StubPointRef sibling(final int row, final int column) {
            return ref(row, column);
        }

        public int a() { return index; }

        public int h() { return step; }

        public float[] g() { return positions; }

        List<float[]> debug() { return List.of(positions); }
    }
}
