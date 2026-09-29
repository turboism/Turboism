package dev.turboism.adapter.cubism.warpalt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Behaviour contract of the green-handle tick mirror against stub host-shape
 * classes. The stub ref re-implements the reviewed {@code CBezierGrid$a} shape
 * (grid/col/row/kind getters, propagation flag {@code f}, seven-arg ctor,
 * {@code moveToOnLocal}); the recorded calls prove the bridge re-dispatches the
 * native write on the mirrored counterpart rather than writing one vector.
 */
final class NativeWarpAltMirrorBridgeGreenTest {

    private StubGrid grid;
    private dev.turboism.sdk.plugin.Registration registration;

    @BeforeEach
    void setUp() {
        grid = new StubGrid(4, 4); // 5x5 bezier points, indices inclusive
        StubRef.moves.clear();
        com.live2d.cubism.doc.model.deformer.warp.k.calls.clear();
        NativeWarpAltMirrorBridge.install();
        registration = NativeWarpAltMirrorBridge.moveParticipation().participate();
    }

    @AfterEach
    void tearDown() {
        if (registration != null) registration.close();
        NativeWarpAltMirrorBridge.uninstall();
    }

    private StubAction drag(final int col, final int row, final StubKind kind, final float dx, final float dy) {
        final StubRef ref = new StubRef(
                grid, new StubVec(0, 0), grid.pts[col][row], col, row, kind, true);
        return new StubAction(new StubSelection(ref), 1.0f, new StubVec(dx, dy));
    }

    @Test
    void verticalAxisMirrorsHandleToRowCounterpartWithKindSwap() {
        NativeWarpAltMirrorBridge.setArmedAxis(1);

        NativeWarpAltMirrorBridge.mirrorGreenTick(drag(2, 1, StubKind.CONTROL_N, 4f, 6f), event(4f, 6f));

        assertEquals(1, StubRef.moves.size());
        final StubRef.Move move = StubRef.moves.get(0);
        // 垂直镜像: row 1 -> 5-1-1? no — counterpart row = bezierRow - row = 4-1 = 3.
        assertEquals(2, move.col);
        assertEquals(3, move.row);
        assertEquals(StubKind.CONTROL_S, move.kind, "N handle mirrors to the counterpart's S handle");
        // local pos (10,20) + mirrored delta (dx, -dy) = (14, 14)
        assertEquals(14f, move.targetX, 1.0e-6f);
        assertEquals(14f, move.targetY, 1.0e-6f);
        assertEquals(1.0f, move.weight, 1.0e-6f);
    }

    @Test
    void horizontalAxisMirrorsHandleToColumnCounterpartWithKindSwap() {
        NativeWarpAltMirrorBridge.setArmedAxis(2);

        NativeWarpAltMirrorBridge.mirrorGreenTick(drag(1, 3, StubKind.CONTROL_W, 4f, 6f), event(4f, 6f));

        assertEquals(1, StubRef.moves.size());
        final StubRef.Move move = StubRef.moves.get(0);
        assertEquals(3, move.col, "counterpart col = bezierCol - col = 4-1 = 3");
        assertEquals(3, move.row);
        assertEquals(StubKind.CONTROL_E, move.kind);
        // mirrored delta (-dx, dy) = (-4, 6); pos (10,20) -> (6, 26)
        assertEquals(6f, move.targetX, 1.0e-6f);
        assertEquals(26f, move.targetY, 1.0e-6f);
    }

    @Test
    void anchorDragReplaysEngineMoveAndSmoothingOnCounterpart() {
        NativeWarpAltMirrorBridge.setArmedAxis(1);

        NativeWarpAltMirrorBridge.mirrorGreenTick(drag(1, 0, StubKind.ANCHOR, 2f, 3f), event(2f, 3f));

        // The grid engine k replays the move on counterpart (1,4) with the
        // mirrored target pos(0,0)+(dx,-dy)=(2,-3), then both directional
        // smoothing passes — the propagation the lone ref write missed.
        assertEquals(3, com.live2d.cubism.doc.model.deformer.warp.k.calls.size());
        assertTrue(com.live2d.cubism.doc.model.deformer.warp.k.calls.get(0)
                .startsWith("move.a 1,4 -> 2.0,-3.0 flag=true"),
                com.live2d.cubism.doc.model.deformer.warp.k.calls.get(0));
        assertEquals("smooth.a 1,4 dim=4 smooth=4",
                com.live2d.cubism.doc.model.deformer.warp.k.calls.get(1));
        assertEquals("smooth.b 1,4 dim=4 smooth=4",
                com.live2d.cubism.doc.model.deformer.warp.k.calls.get(2));
    }

    @Test
    void anchorDragRespectsKeepRelationEditType() {
        NativeWarpAltMirrorBridge.setArmedAxis(1);
        com.live2d.cubism.setting.AppSetting.INSTANCE.getDeformer().getWarpDeformer()
                .setCurrentWarpEditType(com.live2d.cubism.doc.model.extension.warpBezier.WarpEditType.KEEP_RELATION);
        try {
            NativeWarpAltMirrorBridge.mirrorGreenTick(drag(1, 0, StubKind.ANCHOR, 2f, 3f), event(2f, 3f));
        } finally {
            com.live2d.cubism.setting.AppSetting.INSTANCE.getDeformer().getWarpDeformer()
                    .setCurrentWarpEditType(com.live2d.cubism.doc.model.extension.warpBezier.WarpEditType.SMOOTH_ALL);
        }

        // KEEP_RELATION replays the k.b variant only — no smoothing passes.
        assertEquals(1, com.live2d.cubism.doc.model.deformer.warp.k.calls.size());
        assertTrue(com.live2d.cubism.doc.model.deformer.warp.k.calls.get(0).startsWith("move.b 1,4"),
                com.live2d.cubism.doc.model.deformer.warp.k.calls.get(0));
    }

    @Test
    void onAxisAnchorSkipsToAvoidSelfOverwrite() {
        NativeWarpAltMirrorBridge.setArmedAxis(1);
        // row 2 of 0..4 lies on the vertical mirror axis; the anchor is its own
        // counterpart. The hook precedes the native write, so a mirrored engine
        // replay on the same point would shift the base position the native
        // target is computed from — the drag must skip mirroring here.
        NativeWarpAltMirrorBridge.mirrorGreenTick(drag(1, 2, StubKind.ANCHOR, 2f, 3f), event(2f, 3f));
        assertTrue(com.live2d.cubism.doc.model.deformer.warp.k.calls.isEmpty());
        assertTrue(StubRef.moves.isEmpty());
    }

    @Test
    void onAxisSameKindSkips() {
        NativeWarpAltMirrorBridge.setArmedAxis(1);
        // On-axis CONTROL_E's mirror is itself (W/E are not swapped vertically);
        // the native drag already moved it — nothing to mirror.
        NativeWarpAltMirrorBridge.mirrorGreenTick(drag(1, 2, StubKind.CONTROL_E, 2f, 3f), event(2f, 3f));
        assertTrue(StubRef.moves.isEmpty());
    }

    @Test
    void onAxisKindSwapMirrorsOntoSamePoint() {
        NativeWarpAltMirrorBridge.setArmedAxis(1);
        // On-axis N handle mirrors onto the same point's S handle.
        NativeWarpAltMirrorBridge.mirrorGreenTick(drag(1, 2, StubKind.CONTROL_N, 2f, 3f), event(2f, 3f));
        assertEquals(1, StubRef.moves.size());
        final StubRef.Move move = StubRef.moves.get(0);
        assertEquals(2, move.row);
        assertEquals(StubKind.CONTROL_S, move.kind);
    }

    @Test
    void inertWithoutAxisOrParticipation() {
        NativeWarpAltMirrorBridge.mirrorGreenTick(drag(1, 1, StubKind.CONTROL_E, 2f, 3f), event(2f, 3f));
        assertTrue(StubRef.moves.isEmpty(), "axis 0 must not write");

        NativeWarpAltMirrorBridge.setArmedAxis(1);
        registration.close();
        registration = null;
        NativeWarpAltMirrorBridge.mirrorGreenTick(drag(1, 1, StubKind.CONTROL_E, 2f, 3f), event(2f, 3f));
        assertTrue(StubRef.moves.isEmpty(), "no participant must not write");
    }

    @Test
    void subEpsilonDeltaDoesNotMirror() {
        NativeWarpAltMirrorBridge.setArmedAxis(1);
        NativeWarpAltMirrorBridge.mirrorGreenTick(
                drag(1, 1, StubKind.CONTROL_E, 1.0e-4f, 0f), event(1.0e-4f, 0f));
        assertTrue(StubRef.moves.isEmpty());
    }

    @Test
    void nullAndMalformedSelectionsFailClosed() {
        NativeWarpAltMirrorBridge.setArmedAxis(1);
        NativeWarpAltMirrorBridge.mirrorGreenTick(null, event(1f, 1f));
        NativeWarpAltMirrorBridge.mirrorGreenTick(new Object(), event(1f, 1f));
        NativeWarpAltMirrorBridge.mirrorGreenTick(new StubAction(null, 1f, new StubVec(1, 1)), event(1f, 1f));
        assertTrue(StubRef.moves.isEmpty());
    }

    private static StubEvent event(final float dx, final float dy) {
        return new StubEvent(new StubVec(dx, dy));
    }

    // ---- stub host shapes ----

    enum StubKind {
        ANCHOR,
        CONTROL_N,
        CONTROL_W,
        CONTROL_E,
        CONTROL_S
    }

    static final class StubVec {
        final float x;
        final float y;

        public StubVec(final float x, final float y) {
            this.x = x;
            this.y = y;
        }

        public float getX() {
            return x;
        }

        public float getY() {
            return y;
        }
    }

    static final class StubPt {
        final StubVec anchor = new StubVec(0, 0);

        public StubVec getAnchor() {
            return anchor;
        }
    }

    /** Stands in for {@code CBezierGrid$a}; records every {@code moveToOnLocal}. */
    static final class StubRef {
        static final List<Move> moves = new ArrayList<>();

        final StubGrid a;
        final StubVec g;
        final StubPt b;
        final int c;
        final int d;
        final StubKind e;
        final boolean f;

        public StubRef(
                final StubGrid grid,
                final StubVec pos,
                final StubPt pt,
                final int col,
                final int row,
                final StubKind kind,
                final boolean flag) {
            this.a = grid;
            this.g = pos;
            this.b = pt;
            this.c = col;
            this.d = row;
            this.e = kind;
            this.f = flag;
        }

        public StubGrid a() {
            return a;
        }

        public StubPt b() {
            return b;
        }

        public int c() {
            return c;
        }

        public int d() {
            return d;
        }

        public StubKind e() {
            return e;
        }

        public StubVec getPos() {
            // Compat refs expose their local position here; the bridge only adds
            // the mirrored delta, so a fixed stub position keeps math readable.
            return new StubVec(10, 20);
        }

        public void moveToOnLocal(final StubVec target, final float weight) {
            moves.add(new Move(c, d, e, target.getX(), target.getY(), weight));
        }

        static final class Move {
            final int col;
            final int row;
            final StubKind kind;
            final float targetX;
            final float targetY;
            final float weight;

            Move(final int col, final int row, final StubKind kind,
                    final float targetX, final float targetY, final float weight) {
                this.col = col;
                this.row = row;
                this.kind = kind;
                this.targetX = targetX;
                this.targetY = targetY;
                this.weight = weight;
            }
        }
    }

    static final class StubGrid {
        final StubPt[][] pts;
        private final int cols;
        private final int rows;

        StubGrid(final int cols, final int rows) {
            this.cols = cols;
            this.rows = rows;
            this.pts = new StubPt[cols + 1][rows + 1];
            for (int c = 0; c <= cols; c++) {
                for (int r = 0; r <= rows; r++) {
                    pts[c][r] = new StubPt();
                }
            }
        }

        public int getBezierCol() {
            return cols;
        }

        public int getBezierRow() {
            return rows;
        }

        public StubPt[][] getBezierPtRef() {
            return pts;
        }

        /** Mirrors {@code getCompatiblePointRef}: the ref's own space swap is a no-op. */
        public StubRef getCompatiblePointRef(final StubRef ref, final com.live2d.doc.selection.d space) {
            assertNotNull(space);
            return ref;
        }
    }

    /** {@code doc.selection.m} wrapper shape: {@code a()} returns the ref. */
    static final class StubSelection {
        private final StubRef ref;

        StubSelection(final StubRef ref) {
            this.ref = ref;
        }

        public StubRef a() {
            return ref;
        }
    }

    /** {@code warp.a$b} action shape: field {@code b} + {@code b()} weight getter. */
    static final class StubAction {
        private final StubSelection b;
        private final float weight;
        private final StubVec delta;

        StubAction(final StubSelection selection, final float weight, final StubVec delta) {
            this.b = selection;
            this.weight = weight;
            this.delta = delta;
        }

        public float b() {
            return weight;
        }
    }

    static final class StubEvent {
        private final StubVec delta;

        StubEvent(final StubVec delta) {
            this.delta = delta;
        }

        public boolean aw() {
            return true;
        }

        public StubVec aH() {
            return delta;
        }
    }
}
