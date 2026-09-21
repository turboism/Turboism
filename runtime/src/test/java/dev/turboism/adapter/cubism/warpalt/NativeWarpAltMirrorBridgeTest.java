package dev.turboism.adapter.cubism.warpalt;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviour contract of the drag-tick bridge against stub host-shape classes. The
 * bridge reaches the host purely reflectively and recognises the warp binder by the
 * reviewed class name, so each mirror test points the recognition seam at the stub
 * and the negative tests prove the seam is restored afterwards.
 */
final class NativeWarpAltMirrorBridgeTest {

    @BeforeEach
    void pointRecognitionAtTheStub() {
        NativeWarpAltMirrorBridge.setBinderClassNameForTesting(
            StubWarpBinder.class.getName());
        NativeWarpAltMirrorBridge.install();
    }

    @AfterEach
    void tearDown() {
        NativeWarpAltMirrorBridge.uninstall();
    }

    @Test
    void verticalAltMirrorsXAndFollowsY() {
        final StubWarpBinder binder = StubWarpBinder.square3x3();
        binder.select(0, 0);
        final var registration = participate();

        // dragged (0,0) at (0,0) moves toward (1.5,0.6): dx=1.5, dy=0.6
        // vertical partner of (0,0) in a 3-wide grid is (0,2) at (2,0):
        // x: 2 - 1.5 = 0.5, y: 0 + 0.6 = 0.6
        NativeWarpAltMirrorBridge.mirrorWarpDragMove(
            new StubHandler(binder), new StubVector(1.5f, 0.6f), new StubEvent(true, false));

        assertEquals(0.5f, binder.grid.get(0).get(2).x, 1.0e-4f);
        assertEquals(0.6f, binder.grid.get(0).get(2).y, 1.0e-4f);
        registration.close();
        assertFalse(NativeWarpAltMirrorBridge.moveParticipation().hasParticipants());
    }

    @Test
    void horizontalAltShiftMirrorsYAndFollowsX() {
        final StubWarpBinder binder = StubWarpBinder.square3x3();
        binder.select(0, 2);
        final var registration = participate();

        // dragged (0,2) at (2,0) moves toward (2.4,0.8): dx=0.4, dy=0.8
        // horizontal partner of (0,2) in a 3-tall grid is (2,2) at (2,2):
        // x: 2 + 0.4 = 2.4, y: 2 - 0.8 = 1.2
        NativeWarpAltMirrorBridge.mirrorWarpDragMove(
            new StubHandler(binder), new StubVector(2.4f, 0.8f), new StubEvent(true, true));

        assertEquals(2.4f, binder.grid.get(2).get(2).x, 1.0e-4f);
        assertEquals(1.2f, binder.grid.get(2).get(2).y, 1.0e-4f);
        registration.close();
    }

    @Test
    void onAxisCentrePointHasNoCounterpart() {
        final StubWarpBinder binder = StubWarpBinder.square3x3();
        binder.select(1, 1); // exact grid centre, self-partner on both axes
        final var registration = participate();

        NativeWarpAltMirrorBridge.mirrorWarpDragMove(
            new StubHandler(binder), new StubVector(1.9f, 1.9f), new StubEvent(true, false));

        assertEquals(0.0f, binder.grid.get(1).get(0).x, 1.0e-4f);
        assertEquals(2.0f, binder.grid.get(1).get(2).x, 1.0e-4f);
        registration.close();
    }

    @Test
    void inertWithoutAltOrWithoutParticipationOrWithoutSelection() {
        final StubWarpBinder binder = StubWarpBinder.square3x3();
        binder.select(0, 0);

        // No participant yet: nothing moves even with Alt held.
        NativeWarpAltMirrorBridge.mirrorWarpDragMove(
            new StubHandler(binder), new StubVector(1.5f, 0.6f), new StubEvent(true, false));
        assertEquals(2.0f, binder.grid.get(0).get(2).x, 1.0e-4f);

        final var registration = participate();
        // Alt not held: nothing moves.
        NativeWarpAltMirrorBridge.mirrorWarpDragMove(
            new StubHandler(binder), new StubVector(1.5f, 0.6f), new StubEvent(false, false));
        assertEquals(2.0f, binder.grid.get(0).get(2).x, 1.0e-4f);

        // No selected point: nothing moves.
        binder.selectedPointIndex = null;
        NativeWarpAltMirrorBridge.mirrorWarpDragMove(
            new StubHandler(binder), new StubVector(1.5f, 0.6f), new StubEvent(true, false));
        assertEquals(2.0f, binder.grid.get(0).get(2).x, 1.0e-4f);

        registration.close();
    }

    @Test
    void subEpsilonJitterDoesNotMirror() {
        final StubWarpBinder binder = StubWarpBinder.square3x3();
        binder.select(0, 0);
        final var registration = participate();

        NativeWarpAltMirrorBridge.mirrorWarpDragMove(
            new StubHandler(binder), new StubVector(5.0e-4f, 5.0e-4f), new StubEvent(true, false));

        assertEquals(2.0f, binder.grid.get(0).get(2).x, 1.0e-4f);
        registration.close();
    }

    @Test
    void recognitionIsByTheReviewedClassNameNotByShape() {
        // Shape alone must not be enough: a handler exposing a binder-shaped object
        // whose class is not the reviewed warp binder must be ignored.
        final StubWarpBinder binder = StubWarpBinder.square3x3();
        binder.select(0, 0);
        NativeWarpAltMirrorBridge.resetBinderClassName();
        final var registration = participate();

        NativeWarpAltMirrorBridge.mirrorWarpDragMove(
            new StubHandler(binder), new StubVector(1.5f, 0.6f), new StubEvent(true, false));

        assertEquals(2.0f, binder.grid.get(0).get(2).x, 1.0e-4f);
        registration.close();
    }

    @Test
    void nonWarpBinderHandlerIsIgnored() {
        final StubWarpBinder binder = StubWarpBinder.square3x3();
        binder.select(0, 0);
        final var registration = participate();

        NativeWarpAltMirrorBridge.mirrorWarpDragMove(
            new StubHandler(new Object()), new StubVector(1.5f, 0.6f), new StubEvent(true, false));

        assertEquals(2.0f, binder.grid.get(0).get(2).x, 1.0e-4f);
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

    /** Minimal GVector2 shape: getX/setX, getY/setY. */
    static final class StubVector {
        float x;
        float y;

        StubVector(final float x, final float y) {
            this.x = x;
            this.y = y;
        }

        public float getX() { return x; }

        public void setX(final float value) { this.x = value; }

        public float getY() { return y; }

        public void setY(final float value) { this.y = value; }
    }

    /** Minimal actionManager.aG shape: aA() = Alt, aB() = Shift. */
    static final class StubEvent {
        private final boolean alt;
        private final boolean shift;

        StubEvent(final boolean alt, final boolean shift) {
            this.alt = alt;
            this.shift = shift;
        }

        public boolean aA() { return alt; }

        public boolean aB() { return shift; }
    }

    /** Minimal WarpBinder shape with the reviewed Kotlin accessors. */
    static final class StubWarpBinder {
        final ArrayList<ArrayList<StubVector>> grid = new ArrayList<>();
        Integer selectedPointIndex;

        static StubWarpBinder square3x3() {
            final StubWarpBinder binder = new StubWarpBinder();
            for (int row = 0; row < 3; row++) {
                final ArrayList<StubVector> points = new ArrayList<>();
                for (int column = 0; column < 3; column++) {
                    points.add(new StubVector(column, row));
                }
                binder.grid.add(points);
            }
            return binder;
        }

        void select(final int row, final int column) {
            selectedPointIndex = row * 3 + column;
        }

        public ArrayList<ArrayList<StubVector>> getGridPoints() { return grid; }

        public Integer getSelectedPointIndex() { return selectedPointIndex; }

        public int rowPointSize() { return grid.get(0).size(); }

        public int columnPointSize() { return grid.size(); }

        public IndexPair toGridIndex(final int index) {
            return new IndexPair(index / 3, index % 3);
        }
    }

    static final class IndexPair {
        private final int first;
        private final int second;

        IndexPair(final int first, final int second) {
            this.first = first;
            this.second = second;
        }

        public int getFirst() { return first; }

        public int getSecond() { return second; }
    }

    /** Minimal temporaryHandler shape: a() returns the binder. */
    static final class StubHandler {
        private final Object binder;

        StubHandler(final Object binder) {
            this.binder = binder;
        }

        public Object a() { return binder; }
    }
}
