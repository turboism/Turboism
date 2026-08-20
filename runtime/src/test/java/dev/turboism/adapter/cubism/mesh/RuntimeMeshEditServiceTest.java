package dev.turboism.adapter.cubism.mesh;

import dev.turboism.sdk.cubism.mesh.MeshEdgeKind;
import dev.turboism.sdk.cubism.mesh.MeshEdgeRef;
import dev.turboism.sdk.cubism.mesh.MeshEditResult;
import dev.turboism.sdk.cubism.mesh.MeshPointPosition;
import dev.turboism.sdk.cubism.mesh.MeshPointRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RuntimeMeshEditServiceTest {

    @AfterEach
    void clearBridge() {
        NativeMeshMirrorBridge.uninstall();
    }

    @Test
    void addPointUsesOneHostSnapshotAndRoundTripsThroughUndoRedo() {
        final Fixture fixture = new Fixture(new Mesh());
        final RuntimeMeshEditService service = fixture.service();

        final MeshEditResult result = service.addPoints(List.of(new MeshPointPosition(3.0f, 4.0f)));

        assertTrue(result.accepted());
        assertEquals(1, fixture.pack.beginCalls);
        assertEquals(1, fixture.pack.snapshotCalls);
        assertEquals(1, fixture.pack.commitCalls);
        assertEquals(1, fixture.mesh.points.size());
        assertEquals(0, fixture.mesh.points.get(0).id);
        fixture.pack.undo();
        assertTrue(fixture.mesh.points.isEmpty());
        fixture.pack.redo();
        assertEquals(1, fixture.mesh.points.size());
        assertEquals(3.0f, fixture.mesh.points.get(0).position.x, 0.0001f);
    }

    @Test
    void movePointMovesConnectedEdgesAndRoundTripsThroughUndoRedo() {
        final Mesh mesh = new Mesh();
        mesh.points.add(new Point(0, 0.0f, 0.0f));
        mesh.points.add(new Point(1, 1.0f, 1.0f));
        mesh.edges.add(new Edge(0, 1, EdgeType.NORMAL));
        final Fixture fixture = new Fixture(mesh);

        final MeshEditResult result = fixture.service().movePoints(List.of(new MeshPointRef(1, 5.0f, 6.0f)));

        assertTrue(result.accepted());
        assertEquals(5.0f, mesh.points.get(1).position.x, 0.0001f);
        assertEquals(new Edge(0, 1, EdgeType.NORMAL), mesh.edges.get(0));
        fixture.pack.undo();
        assertEquals(1.0f, mesh.points.get(1).position.x, 0.0001f);
        fixture.pack.redo();
        assertEquals(5.0f, mesh.points.get(1).position.x, 0.0001f);
    }

    @Test
    void addEdgeRejectsAnExistingEdgeWithoutLeavingAnUndoEntry() {
        final Mesh mesh = new Mesh();
        mesh.points.add(new Point(0, 0.0f, 0.0f));
        mesh.points.add(new Point(1, 1.0f, 1.0f));
        mesh.edges.add(new Edge(0, 1, EdgeType.NORMAL));
        final Fixture fixture = new Fixture(mesh);

        final MeshEditResult result = fixture.service().addEdges(List.of(
            new MeshEdgeRef(0, 1, MeshEdgeKind.INNER)
        ));

        assertFalse(result.accepted());
        assertEquals(1, mesh.edges.size());
        assertEquals(0, fixture.pack.cancelCalls);
        assertEquals(0, fixture.pack.commitCalls);
        assertEquals(0, fixture.pack.beginCalls);
        assertFalse(fixture.pack.canUndo());
    }

    @Test
    void directWritesRefuseAmbiguousMultipleMeshesBeforeOpeningUndo() {
        final Fixture fixture = new Fixture(new Mesh(), new Mesh());

        final MeshEditResult result = fixture.service().addPoints(List.of(new MeshPointPosition(1.0f, 2.0f)));

        assertFalse(result.accepted());
        assertEquals(0, fixture.pack.beginCalls);
        assertEquals(0, fixture.pack.snapshotCalls);
    }

    @Test
    void failureAfterFirstMutationRestoresMeshAndRemovesUndoEntry() {
        final Mesh mesh = new Mesh();
        mesh.failAddPointCall = 2;
        final Fixture fixture = new Fixture(mesh);

        final MeshEditResult result = fixture.service().addPoints(List.of(
            new MeshPointPosition(1.0f, 2.0f),
            new MeshPointPosition(3.0f, 4.0f)
        ));

        assertFalse(result.accepted());
        assertTrue(mesh.points.isEmpty());
        assertEquals(1, fixture.pack.commitCalls);
        assertEquals(1, fixture.pack.cancelCalls);
        assertFalse(fixture.pack.canUndo());
        assertEquals(null, fixture.pack.current);
    }

    @Test
    void editModeRollbackCommitsThenRevertsThroughItsUndoManager() throws Exception {
        final RollbackEditMode editMode = new RollbackEditMode();

        NativeMeshMirrorBridge.cancelUndoGroup(editMode);

        assertFalse(editMode.cancelled);
        assertEquals(1, editMode.undoManager.revertCalls);
    }

    @Test
    void duplicateMovesAreRejectedBeforeOpeningUndo() {
        final Mesh mesh = new Mesh();
        mesh.points.add(new Point(0, 0.0f, 0.0f));
        final Fixture fixture = new Fixture(mesh);

        final MeshEditResult result = fixture.service().movePoints(List.of(
            new MeshPointRef(0, 1.0f, 2.0f),
            new MeshPointRef(0, 3.0f, 4.0f)
        ));

        assertFalse(result.accepted());
        assertEquals(0.0f, mesh.points.get(0).position.x, 0.0001f);
        assertEquals(0, fixture.pack.beginCalls);
    }

    public static final class RollbackEditMode {
        final UndoManager undoManager = new UndoManager();
        boolean cancelled;
        public boolean endEdit(final boolean cancelled, final Object callback) {
            this.cancelled = cancelled;
            return true;
        }
        public UndoManager getUndoManager() { return undoManager; }
    }

    public static final class UndoManager {
        int revertCalls;
        public void revert() { revertCalls++; }
    }

    private static final class Fixture {
        final Mesh mesh;
        final EditMode editMode;
        final ActionPack pack;

        Fixture(final Mesh... meshes) {
            mesh = meshes[0];
            editMode = new EditMode(meshes);
            pack = new ActionPack(editMode);
            NativeMeshMirrorBridge.install(new RuntimeMeshMirrorAxisService(), new RuntimeMeshEditUiService());
            NativeMeshMirrorBridge.attachControl(new JPanel(), new Panel(pack));
        }

        RuntimeMeshEditService service() {
            return new RuntimeMeshEditService();
        }
    }

    public static final class Panel {
        public final ToolMode toolMode;
        Panel(final ActionPack pack) { toolMode = new ToolMode(pack); }
    }

    public static final class ToolMode {
        private final Controller controller;
        ToolMode(final ActionPack pack) { controller = new Controller(pack); }
        public Controller getCtrl$cubism() { return controller; }
    }

    public static final class Controller {
        public final CompletePack completePack;
        Controller(final ActionPack pack) { completePack = new CompletePack(pack); }
    }

    public static final class CompletePack {
        public final ViewContext currentViewContext;
        CompletePack(final ActionPack pack) { currentViewContext = new ViewContext(pack); }
    }

    public static final class ViewContext {
        public final EditMode currentEditMode;
        private final ActionPack lastActionPack;
        ViewContext(final ActionPack pack) {
            currentEditMode = pack.editMode;
            lastActionPack = pack;
        }
        public ActionPack getLastActionPack() { return lastActionPack; }
    }

    public static final class ActionPack {
        private final EditMode editMode;
        int beginCalls;
        int snapshotCalls;
        int commitCalls;
        int cancelCalls;
        Group current;
        Group committed;

        ActionPack(final EditMode editMode) {
            this.editMode = editMode;
        }

        public Group a(final String label) {
            beginCalls++;
            current = new Group(label, editMode.meshes());
            return current;
        }

        public void d(final String label) {
            snapshotCalls++;
            current.captureBefore();
        }

        public void a(final boolean cancelled, final boolean revert, final Object callback) {
            if (cancelled) {
                cancelCalls++;
                current = null;
                return;
            }
            commitCalls++;
            current.captureAfter();
            committed = current;
            current = null;
            if (revert) {
                cancelCalls++;
                committed.restoreBefore();
                committed = null;
            }
        }

        void undo() { committed.restoreBefore(); }
        void redo() { committed.restoreAfter(); }
        boolean canUndo() { return committed != null; }
    }

    public static final class EditMode {
        private final List<Entry> entries;
        EditMode(final Mesh... meshes) {
            entries = new ArrayList<>();
            for (Mesh mesh : meshes) entries.add(new Entry(mesh));
        }
        public List<Entry> getEditDataList() { return entries; }
        List<Mesh> meshes() { return entries.stream().map(Entry::b).toList(); }
    }

    public record Entry(Mesh mesh) {
        public Mesh b() { return mesh; }
    }

    public static final class Group {
        final String label;
        final List<Mesh> targets;
        List<Mesh> before;
        List<Mesh> after;
        Group(final String label, final List<Mesh> targets) {
            this.label = label;
            this.targets = targets;
        }
        void captureBefore() { before = targets.stream().map(Mesh::copy).toList(); }
        void captureAfter() { after = targets.stream().map(Mesh::copy).toList(); }
        void restoreBefore() { restore(before); }
        void restoreAfter() { restore(after); }
        private void restore(final List<Mesh> snapshots) {
            for (int index = 0; index < targets.size(); index++) targets.get(index).restore(snapshots.get(index));
        }
    }

    public static final class Mesh {
        final List<Point> points = new ArrayList<>();
        final List<Edge> edges = new ArrayList<>();
        int nextPointUid;
        int addPointCalls;
        int failAddPointCall;

        public List<PointRef> getAllPointRef() {
            final List<PointRef> refs = new ArrayList<>();
            for (Point point : points) refs.add(new PointRef(this, point.id));
            return refs;
        }

        public List<Edge> getEdges() { return edges; }

        public int addPoint(final float x, final float y, final PointType type, final long uid) {
            addPointCalls++;
            if (failAddPointCall == addPointCalls) throw new IllegalStateException("synthetic add failure");
            final int id = points.size();
            points.add(new Point(id, x, y));
            nextPointUid++;
            return id;
        }

        public Integer addEdge(
            final int first,
            final int second,
            final EdgeType type,
            final boolean replace,
            final boolean checkCross
        ) {
            final Edge edge = new Edge(Math.min(first, second), Math.max(first, second), type);
            final int existing = edges.indexOf(edge);
            if (existing >= 0) return existing;
            edges.add(edge);
            return edges.size() - 1;
        }

        Mesh copy() {
            final Mesh copy = new Mesh();
            for (Point point : points) copy.points.add(point.copy());
            copy.edges.addAll(edges);
            copy.nextPointUid = nextPointUid;
            return copy;
        }

        void restore(final Mesh source) {
            points.clear();
            for (Point point : source.points) points.add(point.copy());
            edges.clear();
            edges.addAll(source.edges);
            nextPointUid = source.nextPointUid;
        }
    }

    public static final class PointRef {
        private final Mesh mesh;
        private final int id;
        PointRef(final Mesh mesh, final int id) { this.mesh = mesh; this.id = id; }
        public int b() { return id; }
        public Vector getPos() { return mesh.points.get(id).position; }
        public void moveToOnLocal(final Vector position, final float weight) {
            mesh.points.get(id).position = new Vector(position.x, position.y);
        }
    }

    public static final class Point {
        final int id;
        Vector position;
        Point(final int id, final float x, final float y) {
            this.id = id;
            position = new Vector(x, y);
        }
        Point copy() { return new Point(id, position.x, position.y); }
    }

    public static final class Vector {
        final float x;
        final float y;
        public Vector(final float x, final float y) { this.x = x; this.y = y; }
        public float getX() { return x; }
        public float getY() { return y; }
    }

    public enum PointType { NORMAL }
    public enum EdgeType { LOCKED, NORMAL }

    public record Edge(int index1, int index2, EdgeType type) {
        public int getIndex1() { return index1; }
        public int getIndex2() { return index2; }
        public EdgeType getType() { return type; }
    }
}
