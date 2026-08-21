package dev.turboism.adapter.cubism.mesh;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exact 5.3.02 selected-point movement semantics backported only into 5.2.03. */
final class MeshMirrorSelectedMovementTest {

    @AfterEach
    void clearBridge() {
        NativeMeshMirrorBridge.uninstall();
    }

    @Test
    void pluginPolicyMovesTheUnselectedCounterpartWithTheSelectionWeight() {
        final List<String> diagnostics = install(true);
        final Point source = point(0, -1.0f, 0.0f);
        final Point counterpart = point(1, 1.0f, 0.0f);
        final Mesh mesh = new Mesh(source, counterpart);
        mesh.selector.select(source, 0.4f);
        final Pack pack = new Pack(new Context(mesh, new Vector(2.0f, 3.0f)));

        NativeMeshMirrorBridge.mirrorMoveSelected(pack);

        assertEquals(-1.0f, counterpart.position.x, 0.0001f);
        assertEquals(3.0f, counterpart.position.y, 0.0001f);
        assertEquals(0.4f, counterpart.lastWeight, 0.0001f);
        assertEquals(1, counterpart.moveCount);
        assertEquals(0, source.moveCount, "the native loop still owns movement of selected sources");
        assertTrue(diagnostics.contains(stage("MOVE_PARTICIPATION_APPLIED count=1")));
    }

    @Test
    void successfulMovementDiagnosticIsEmittedOnlyOncePerInstallation() {
        final List<String> diagnostics = install(true);
        final Point source = point(0, -1.0f, 0.0f);
        final Point counterpart = point(1, 1.0f, 0.0f);
        final Mesh mesh = new Mesh(source, counterpart);
        mesh.selector.select(source, 1.0f);
        final Pack pack = new Pack(new Context(mesh, new Vector(1.0f, 0.0f)));

        NativeMeshMirrorBridge.mirrorMoveSelected(pack);
        NativeMeshMirrorBridge.mirrorMoveSelected(pack);

        assertEquals(1, diagnostics.stream()
            .filter(stage("MOVE_PARTICIPATION_APPLIED count=1")::equals)
            .count());
    }

    @Test
    void movementUsesTheNativeTwentyScaleTolerance() {
        install(true);
        final Point source = point(0, -15.0f, 0.0f);
        final Point counterpart = point(1, 5.0f, 0.0f);
        final Mesh mesh = new Mesh(source, counterpart);
        mesh.selector.select(source, 1.0f);

        NativeMeshMirrorBridge.mirrorMoveSelected(
            new Pack(new Context(mesh, new Vector(1.0f, 0.0f)))
        );

        assertEquals(1, counterpart.moveCount);
        assertEquals(14.0f, counterpart.position.x, 0.0001f);
    }

    @Test
    void bothSidesSelectedAreLeftForTheNativeLoop() {
        install(true);
        final Point first = point(0, -1.0f, 0.0f);
        final Point second = point(1, 1.0f, 0.0f);
        final Mesh mesh = new Mesh(first, second);
        mesh.selector.select(first, 1.0f);
        mesh.selector.select(second, 1.0f);

        NativeMeshMirrorBridge.mirrorMoveSelected(
            new Pack(new Context(mesh, new Vector(2.0f, 0.0f)))
        );

        assertEquals(0, first.moveCount);
        assertEquals(0, second.moveCount);
    }

    @Test
    void noPluginPolicyPreservesNative5203Movement() {
        final List<String> diagnostics = install(false);
        final Point source = point(0, -1.0f, 0.0f);
        final Point counterpart = point(1, 1.0f, 0.0f);
        final Mesh mesh = new Mesh(source, counterpart);
        mesh.selector.select(source, 1.0f);

        NativeMeshMirrorBridge.mirrorMoveSelected(
            new Pack(new Context(mesh, new Vector(2.0f, 0.0f)))
        );

        assertEquals(0, counterpart.moveCount);
        assertTrue(diagnostics.contains(stage("MOVE_PARTICIPATION_SKIPPED reason=NO_PARTICIPANT")));
    }

    @Test
    void disabledMirrorOrMissingCounterpartFailsOpen() {
        install(true, false);
        final Point source = point(0, -1.0f, 0.0f);
        final Point far = point(1, 40.0f, 0.0f);
        final Mesh mesh = new Mesh(source, far);
        mesh.selector.select(source, 1.0f);

        NativeMeshMirrorBridge.mirrorMoveSelected(
            new Pack(new Context(mesh, new Vector(2.0f, 0.0f)))
        );

        assertEquals(0, far.moveCount);
    }

    private static List<String> install(final boolean participate) {
        return install(participate, true);
    }

    private static List<String> install(final boolean participate, final boolean mirrorEnabled) {
        final List<String> diagnostics = new ArrayList<>();
        NativeMeshMirrorBridge.install(
            new RuntimeMeshMirrorAxisService(), new RuntimeMeshEditUiService()
        );
        NativeMeshMirrorBridge.mirrorForTesting(new Mirror(mirrorEnabled));
        NativeMeshMirrorBridge.diagnostics(diagnostics::add);
        if (participate) NativeMeshMirrorBridge.moveParticipation().participate();
        return diagnostics;
    }

    private static Point point(final int id, final float x, final float y) {
        return new Point(id, new Vector(x, y));
    }

    private static String stage(final String value) {
        return "MESH_MIRROR_DIAG stage=" + value;
    }

    public static final class Mirror {
        private final boolean enabled;

        Mirror(final boolean enabled) {
            this.enabled = enabled;
        }

        public boolean a() {
            return enabled;
        }

        public Vector a(final Vector vector) {
            return new Vector(-vector.x, vector.y);
        }
    }

    public static final class Vector {
        final float x;
        final float y;

        public Vector(final float x, final float y) {
            this.x = x;
            this.y = y;
        }

        public float getX() {
            return x;
        }

        public float getY() {
            return y;
        }

        public Vector plus(final Vector other) {
            return new Vector(x + other.x, y + other.y);
        }

        public float distance(final Vector other) {
            return (float) Math.hypot(x - other.x, y - other.y);
        }
    }

    public static final class Point {
        private final int id;
        private Vector position;
        private float lastWeight;
        private int moveCount;

        Point(final int id, final Vector position) {
            this.id = id;
            this.position = position;
        }

        public int b() {
            return id;
        }

        public Vector getPos() {
            return position;
        }

        public void moveToOnLocal(final Vector target, final float weight) {
            position = target;
            lastWeight = weight;
            moveCount++;
        }
    }

    public static final class Selector implements Iterable<Point> {
        private final Map<Point, Float> weights = new LinkedHashMap<>();

        void select(final Point point, final float weight) {
            weights.put(point, weight);
        }

        public float getWeight(final Point point, final float fallback) {
            return weights.getOrDefault(point, fallback);
        }

        @Override
        public Iterator<Point> iterator() {
            return weights.keySet().iterator();
        }
    }

    public static final class Selection {
        private final Selector selector;

        Selection(final Selector selector) {
            this.selector = selector;
        }

        public Selector getPointSelector() {
            return selector;
        }
    }

    public static final class Mesh {
        private final List<Point> points;
        final Selector selector = new Selector();

        Mesh(final Point... points) {
            this.points = List.of(points);
        }

        public List<Point> getAllPointRef() {
            return points;
        }

        public Selection getSelection() {
            return new Selection(selector);
        }

        public Point getCompatiblePointRef(final Point point) {
            return points.contains(point) ? point : null;
        }
    }

    public static final class Context {
        private final Mesh mesh;
        private final Vector delta;

        Context(final Mesh mesh, final Vector delta) {
            this.mesh = mesh;
            this.delta = delta;
        }

        public Mesh b() {
            return mesh;
        }

        public Vector p() {
            return delta;
        }

        public Vector b(final Vector vector) {
            return vector;
        }

        public Vector a(final Vector vector) {
            return vector;
        }
    }

    public static final class EditMode { }

    public static final class Pack {
        private final List<Context> contexts;
        private final EditMode editMode = new EditMode();

        Pack(final Context... contexts) {
            this.contexts = List.of(contexts);
        }

        public List<Context> aT() {
            return contexts;
        }

        public EditMode aP() {
            return editMode;
        }

        public float aL() {
            return 1.0f;
        }
    }
}
