package dev.turboism.adapter.cubism.mesh;

import dev.turboism.sdk.cubism.mesh.MeshEditUiService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mirror-linked deletion, checked against the behaviour the exact 5.3.02 host ships
 * natively. The doubles below mimic only the host members the bridge reflects over.
 */
final class MeshMirrorLinkedDeletionTest {

    @AfterEach
    void clearBridge() {
        NativeMeshMirrorBridge.participation().resetSession();
        NativeMeshMirrorBridge.counterparts().resetSession();
        NativeMeshMirrorBridge.uninstall();
    }

    /**
     * The framework carries no mirror policy of its own: with nothing registered, a host
     * deletion is untouched. This is what stops the backport being two implementations.
     */
    @Test
    void deletesNothingWhenNoPluginHasRegisteredThePolicy() {
        final List<String> diagnostics = new ArrayList<>();
        final RuntimeMeshEditUiService ui = new RuntimeMeshEditUiService();
        ui.contributeMirrorAxisAngleControl(new MeshEditUiService.MirrorAxisAngleControl(
            "mesh.mirror-axis.angle", "Angle", "Reset", -180.0f, 180.0f, 0.1f, ignored -> { }
        ));
        NativeMeshMirrorBridge.install(new RuntimeMeshMirrorAxisService(), ui);
        NativeMeshMirrorBridge.mirrorForTesting(new Mirror(true));
        NativeMeshMirrorBridge.diagnostics(diagnostics::add);
        final Mesh mesh = new Mesh(point(0, -1.0f, 0.0f), point(1, 1.0f, 0.0f));
        final Pack pack = new Pack(mesh);

        NativeMeshMirrorBridge.mirrorDeletePoints(List.of(List.of(mesh.point(0))), pack.undo, pack);

        assertTrue(pack.editMode.deleted.isEmpty());
        assertTrue(diagnostics.contains(stage("PARTICIPATION_SKIPPED reason=NO_PARTICIPANT")));
    }

    @Test
    void deletesTheMirrorCounterpartsOfTheDeletedPoints() {
        final List<String> diagnostics = install();
        // Points at x = -1 and x = 1 are counterparts across a vertical axis at x = 0.
        final Mesh mesh = new Mesh(point(0, -1.0f, 0.0f), point(1, 1.0f, 0.0f));
        final Pack pack = new Pack(mesh);

        NativeMeshMirrorBridge.mirrorDeletePoints(List.of(List.of(mesh.point(0))), pack.undo, pack);

        assertEquals(List.of(mesh.point(1)), pack.editMode.deleted);
        assertTrue(diagnostics.stream().anyMatch(value -> value.startsWith(
            stage("PARTICIPATION_APPLIED kind=POINTS count=1")
        )));
    }

    @Test
    void pointActionDeletesIncidentEdgesBeforeDeletingTheMirrorPoint() {
        final List<String> diagnostics = install();
        final Mesh mesh = new Mesh(
            point(0, -1.0f, 0.0f), point(1, 1.0f, 0.0f),
            point(2, 2.0f, 0.0f)
        );
        final Edge incident = new Edge(1, 2, "SOFT");
        mesh.edges.add(incident);
        final Pack pack = new Pack(mesh);

        NativeMeshMirrorBridge.mirrorDeletePointAction(
            List.of(List.of(mesh.point(0))), pack.undo, pack
        );

        assertEquals(List.of(incident), mesh.handler.removed);
        assertEquals(List.of(mesh.point(1)), pack.editMode.deleted);
        assertEquals(1, pack.undo.added.size());
        assertTrue(diagnostics.contains(
            stage("PARTICIPATION_APPLIED kind=POINTS count=1 incidentEdges=1")
        ));
    }

    @Test
    void normalizesDeletionReferencesThroughTheLiveMeshBeforeResolvingCounterparts() {
        final List<String> diagnostics = install();
        final Mesh mesh = new Mesh(point(0, -1.0f, 0.0f), point(1, 1.0f, 0.0f));
        final Pack pack = new Pack(mesh);

        NativeMeshMirrorBridge.mirrorDeletePoints(
            List.of(List.of(new CompatiblePointRef(mesh.point(0)))), pack.undo, pack
        );

        assertEquals(List.of(mesh.point(1)), pack.editMode.deleted);
        assertTrue(diagnostics.stream().anyMatch(value -> value.startsWith(
            stage("PARTICIPATION_APPLIED kind=POINTS count=1")
        )));
    }

    @Test
    void normalizedSourcesExcludeCounterpartsAlreadyPresentInTheCallerDeletion() {
        final List<String> diagnostics = install();
        final Mesh mesh = new Mesh(point(0, -1.0f, 0.0f), point(1, 1.0f, 0.0f));
        final Pack pack = new Pack(mesh);

        NativeMeshMirrorBridge.mirrorDeletePoints(
            List.of(List.of(
                new CompatiblePointRef(mesh.point(0)),
                new CompatiblePointRef(mesh.point(1))
            )),
            pack.undo,
            pack
        );

        assertTrue(pack.editMode.deleted.isEmpty());
        assertTrue(diagnostics.contains(stage("PARTICIPATION_EMPTY kind=POINTS")));
    }

    @Test
    void neverDeletesAPointTheCallerIsAlreadyDeleting() {
        final List<String> diagnostics = install();
        final Mesh mesh = new Mesh(point(0, -1.0f, 0.0f), point(1, 1.0f, 0.0f));
        final Pack pack = new Pack(mesh);

        // Both sides selected: each is the other's counterpart, so nothing extra may be deleted.
        NativeMeshMirrorBridge.mirrorDeletePoints(
            List.of(List.of(mesh.point(0), mesh.point(1))), pack.undo, pack
        );

        assertTrue(pack.editMode.deleted.isEmpty());
        assertTrue(diagnostics.contains(stage("PARTICIPATION_EMPTY kind=POINTS")));
    }

    @Test
    void defaultCounterpartDoesNotDeleteTheSameNumericIdFromAnotherMesh() {
        final List<String> diagnostics = install();
        final Mesh first = new Mesh(
            point(0, -1.0f, 0.0f), point(1, 1.0f, 0.0f)
        );
        final Mesh second = new Mesh(
            point(0, -10.0f, 0.0f), point(1, 10.0f, 0.0f)
        );
        final Pack pack = new Pack(first, second);

        NativeMeshMirrorBridge.mirrorDeletePoints(List.of(List.of(first.point(0))), pack.undo, pack);

        assertEquals(List.of(first.point(1)), pack.editMode.deleted);
        assertTrue(diagnostics.stream().anyMatch(value -> value.startsWith(
            stage("PARTICIPATION_APPLIED kind=POINTS count=1")
        )));
    }

    @Test
    void ambiguousCustomPointIdFailsClosedWithoutHidingTheDefaultCounterpart() {
        final List<String> diagnostics = install();
        final Mesh first = new Mesh(
            point(0, -1.0f, 0.0f), point(1, 1.0f, 0.0f)
        );
        final Mesh second = new Mesh(
            point(0, -10.0f, 0.0f), point(1, 10.0f, 0.0f)
        );
        final Pack pack = new Pack(first, second);
        NativeMeshMirrorBridge.participation().participate(deletion ->
            dev.turboism.sdk.cubism.mesh.MeshEditContribution.ofPoints(List.of(
                new dev.turboism.sdk.cubism.mesh.MeshPointRef(1, 10.0f, 0.0f)
            ))
        );

        NativeMeshMirrorBridge.mirrorDeletePoints(List.of(List.of(first.point(0))), pack.undo, pack);

        assertEquals(List.of(first.point(1)), pack.editMode.deleted);
        assertTrue(diagnostics.stream().anyMatch(value -> value.startsWith(
            stage("PARTICIPATION_APPLIED kind=POINTS count=1")
        )));
    }

    @Test
    void sourceIdentityDoesNotHideTheSameNumericIdInAnotherMesh() {
        final List<String> diagnostics = install();
        final Mesh first = new Mesh(point(0, -1.0f, 0.0f));
        final Mesh second = new Mesh(point(0, 1.0f, 0.0f));
        final Pack pack = new Pack(first, second);
        NativeMeshMirrorBridge.participation().resetSession();
        NativeMeshMirrorBridge.participation().participate(deletion ->
            dev.turboism.sdk.cubism.mesh.MeshEditContribution.ofPoints(List.of(
                new dev.turboism.sdk.cubism.mesh.MeshPointRef(0, 1.0f, 0.0f)
            ))
        );

        NativeMeshMirrorBridge.mirrorDeletePoints(List.of(List.of(first.point(0))), pack.undo, pack);

        assertEquals(List.of(second.point(0)), pack.editMode.deleted);
        assertTrue(diagnostics.stream().anyMatch(value -> value.startsWith(
            stage("PARTICIPATION_APPLIED kind=POINTS count=1")
        )));
    }

    @Test
    void leavesTheHostDeletionAloneWhenNoCounterpartIsInsideTheTolerance() {
        install();
        // The mirror of x = -1 is x = 1; the only other point sits far outside aL().
        final Mesh mesh = new Mesh(point(0, -1.0f, 0.0f), point(1, 40.0f, 0.0f));
        final Pack pack = new Pack(mesh);

        NativeMeshMirrorBridge.mirrorDeletePoints(List.of(List.of(mesh.point(0))), pack.undo, pack);

        assertTrue(pack.editMode.deleted.isEmpty());
    }

    @Test
    void doesNothingWhileTheMirrorAxisIsDisabled() {
        install(false);
        final Mesh mesh = new Mesh(point(0, -1.0f, 0.0f), point(1, 1.0f, 0.0f));
        final Pack pack = new Pack(mesh);

        NativeMeshMirrorBridge.mirrorDeletePoints(List.of(List.of(mesh.point(0))), pack.undo, pack);

        assertTrue(pack.editMode.deleted.isEmpty());
    }

    @Test
    void doesNothingWhileTheBridgeIsUnbound() {
        final Mesh mesh = new Mesh(point(0, -1.0f, 0.0f), point(1, 1.0f, 0.0f));
        final Pack pack = new Pack(mesh);

        NativeMeshMirrorBridge.mirrorDeletePoints(List.of(List.of(mesh.point(0))), pack.undo, pack);

        assertTrue(pack.editMode.deleted.isEmpty());
    }

    @Test
    void failsOpenAndReportsWhenTheHostShapeIsUnexpected() {
        final List<String> diagnostics = install();

        NativeMeshMirrorBridge.mirrorDeletePoints(List.of(List.of()), new Object(), new Object());

        assertFalse(diagnostics.stream().anyMatch(value -> value.contains("PARTICIPATION_APPLIED")));
    }

    @Test
    void deletesTheMirrorCounterpartEdgeIntoTheCallersUndoGroup() {
        final List<String> diagnostics = install();
        final Mesh mesh = new Mesh(
            point(0, -2.0f, 0.0f), point(1, -1.0f, 0.0f),
            point(2, 1.0f, 0.0f), point(3, 2.0f, 0.0f)
        );
        final Edge source = new Edge(0, 1, "SOFT");
        final Edge counterpart = new Edge(2, 3, "SOFT");
        mesh.edges.add(source);
        mesh.edges.add(counterpart);
        final Pack pack = new Pack(mesh);

        NativeMeshMirrorBridge.rememberEdgeUndoGroup(pack.undo);
        NativeMeshMirrorBridge.mirrorDeleteEdge(source, pack);

        assertEquals(List.of(counterpart), mesh.handler.removed, diagnostics.toString());
        assertEquals(1, pack.undo.added.size());
        assertTrue(diagnostics.contains(stage("PARTICIPATION_APPLIED kind=EDGES count=1")));
    }

    @Test
    void exactSourceEdgeIdentitySelectsTheCorrectMesh() {
        final List<String> diagnostics = install();
        final Mesh first = new Mesh(
            point(0, -20.0f, 0.0f), point(1, -10.0f, 0.0f),
            point(2, 10.0f, 0.0f), point(3, 20.0f, 0.0f)
        );
        final Edge firstSource = new Edge(0, 1, "SOFT");
        final Edge firstCounterpart = new Edge(2, 3, "SOFT");
        first.edges.add(firstSource);
        first.edges.add(firstCounterpart);
        final Mesh second = new Mesh(
            point(0, -2.0f, 0.0f), point(1, -1.0f, 0.0f),
            point(2, 1.0f, 0.0f), point(3, 2.0f, 0.0f)
        );
        final Edge source = new Edge(0, 1, "SOFT");
        final Edge counterpart = new Edge(2, 3, "SOFT");
        second.edges.add(source);
        second.edges.add(counterpart);
        final Pack pack = new Pack(first, second);

        NativeMeshMirrorBridge.rememberEdgeUndoGroup(pack.undo);
        NativeMeshMirrorBridge.mirrorDeleteEdge(source, pack);

        assertTrue(first.handler.removed.isEmpty());
        assertEquals(List.of(counterpart), second.handler.removed, diagnostics.toString());
        assertTrue(diagnostics.contains(stage("PARTICIPATION_APPLIED kind=EDGES count=1")));
    }

    @Test
    void customSourceEndpointContributionExcludesOnlyTheExactSourceEdge() {
        final List<String> diagnostics = install();
        final Mesh first = new Mesh(point(0, -2.0f, 0.0f), point(1, -1.0f, 0.0f));
        final Edge source = new Edge(0, 1, "SOFT");
        first.edges.add(source);
        final Mesh second = new Mesh(point(0, -20.0f, 0.0f), point(1, -10.0f, 0.0f));
        final Edge custom = new Edge(0, 1, "SOFT");
        second.edges.add(custom);
        final Pack pack = new Pack(first, second);
        NativeMeshMirrorBridge.participation().resetSession();
        NativeMeshMirrorBridge.participation().participate(deletion ->
            dev.turboism.sdk.cubism.mesh.MeshEditContribution.ofEdges(List.of(
                new dev.turboism.sdk.cubism.mesh.MeshEdgeRef(
                    0, 1, dev.turboism.sdk.cubism.mesh.MeshEdgeKind.UNKNOWN
                )
            ))
        );

        NativeMeshMirrorBridge.rememberEdgeUndoGroup(pack.undo);
        NativeMeshMirrorBridge.mirrorDeleteEdge(source, pack);

        assertTrue(first.handler.removed.isEmpty());
        assertEquals(List.of(custom), second.handler.removed, diagnostics.toString());
        assertTrue(diagnostics.contains(stage("PARTICIPATION_APPLIED kind=EDGES count=1")));
    }

    @Test
    void defaultCounterpartEdgeDoesNotDeleteTheSameEndpointsFromAnotherMesh() {
        final List<String> diagnostics = install();
        final Mesh first = new Mesh(
            point(0, -2.0f, 0.0f), point(1, -1.0f, 0.0f),
            point(2, 1.0f, 0.0f), point(3, 2.0f, 0.0f)
        );
        final Edge source = new Edge(0, 1, "SOFT");
        final Edge counterpart = new Edge(2, 3, "SOFT");
        first.edges.add(source);
        first.edges.add(counterpart);
        final Mesh second = new Mesh(
            point(0, -20.0f, 0.0f), point(1, -10.0f, 0.0f),
            point(2, 10.0f, 0.0f), point(3, 20.0f, 0.0f)
        );
        final Edge unrelated = new Edge(2, 3, "SOFT");
        second.edges.add(unrelated);
        final Pack pack = new Pack(first, second);

        NativeMeshMirrorBridge.rememberEdgeUndoGroup(pack.undo);
        NativeMeshMirrorBridge.mirrorDeleteEdge(source, pack);

        assertEquals(List.of(counterpart), first.handler.removed, diagnostics.toString());
        assertTrue(second.handler.removed.isEmpty());
        assertTrue(diagnostics.contains(stage("PARTICIPATION_APPLIED kind=EDGES count=1")));
    }

    @Test
    void ambiguousCustomEdgeEndpointsFailClosedWithoutHidingTheDefaultCounterpart() {
        final List<String> diagnostics = install();
        final Mesh first = new Mesh(
            point(0, -2.0f, 0.0f), point(1, -1.0f, 0.0f),
            point(2, 1.0f, 0.0f), point(3, 2.0f, 0.0f)
        );
        final Edge source = new Edge(0, 1, "SOFT");
        final Edge counterpart = new Edge(2, 3, "SOFT");
        first.edges.add(source);
        first.edges.add(counterpart);
        final Mesh second = new Mesh(
            point(0, -20.0f, 0.0f), point(1, -10.0f, 0.0f),
            point(2, 10.0f, 0.0f), point(3, 20.0f, 0.0f)
        );
        final Edge custom = new Edge(2, 3, "SOFT");
        second.edges.add(custom);
        final Pack pack = new Pack(first, second);
        NativeMeshMirrorBridge.participation().participate(deletion ->
            dev.turboism.sdk.cubism.mesh.MeshEditContribution.ofEdges(List.of(
                new dev.turboism.sdk.cubism.mesh.MeshEdgeRef(
                    2, 3, dev.turboism.sdk.cubism.mesh.MeshEdgeKind.UNKNOWN
                )
            ))
        );

        NativeMeshMirrorBridge.rememberEdgeUndoGroup(pack.undo);
        NativeMeshMirrorBridge.mirrorDeleteEdge(source, pack);

        assertEquals(List.of(counterpart), first.handler.removed);
        assertTrue(second.handler.removed.isEmpty());
        assertTrue(diagnostics.contains(stage("PARTICIPATION_APPLIED kind=EDGES count=1")));
    }

    @Test
    void skipsAnEdgeWhoseMirrorCounterpartDoesNotExist() {
        final List<String> diagnostics = install();
        final Mesh mesh = new Mesh(
            point(0, -2.0f, 0.0f), point(1, -1.0f, 0.0f),
            point(2, 1.0f, 0.0f), point(3, 2.0f, 0.0f)
        );
        final Edge source = new Edge(0, 1, "SOFT");
        mesh.edges.add(source);
        final Pack pack = new Pack(mesh);

        NativeMeshMirrorBridge.rememberEdgeUndoGroup(pack.undo);
        NativeMeshMirrorBridge.mirrorDeleteEdge(source, pack);

        assertTrue(mesh.handler.removed.isEmpty());
        assertTrue(pack.undo.added.isEmpty());
        assertTrue(diagnostics.contains(stage("PARTICIPATION_EMPTY kind=EDGES")));
    }

    @Test
    void eraserDeletesMirrorPointsAndTheirIncidentEdgesIntoTheOuterUndo() {
        final List<String> diagnostics = install();
        final Mesh mesh = new Mesh(
            point(0, -2.0f, 0.0f), point(1, 2.0f, 0.0f),
            point(2, 3.0f, 0.0f)
        );
        final Edge incident = new Edge(1, 2, "SOFT");
        mesh.edges.add(incident);
        final Pack pack = new Pack(mesh);
        final Point candidate = point(0, -2.0f, 0.0f);

        NativeMeshMirrorBridge.mirrorDeleteEraserPoints(
            List.of(candidate), pack, pack.undo
        );

        assertEquals(List.of(incident), mesh.handler.removed);
        assertEquals(List.of(mesh.point(1)), pack.editMode.deleted);
        assertEquals(1, pack.undo.added.size());
        assertTrue(diagnostics.contains(
            stage("PARTICIPATION_APPLIED kind=POINTS count=1 incidentEdges=1")
        ));
    }

    @Test
    void eraserDeletesCounterpartsSeparatelyIntoTheCurrentOuterUndo() {
        final List<String> diagnostics = install();
        final Mesh mesh = new Mesh(
            point(0, -2.0f, 0.0f), point(1, -1.0f, 0.0f),
            point(2, 1.0f, 0.0f), point(3, 2.0f, 0.0f)
        );
        final Edge liveSource = new Edge(0, 1, "SOFT");
        final Edge counterpart = new Edge(2, 3, "SOFT");
        mesh.edges.add(liveSource);
        mesh.edges.add(counterpart);
        final Pack pack = new Pack(mesh);
        final List<Edge> wrappers = new ArrayList<>(List.of(new Edge(0, 1, "SOFT")));

        NativeMeshMirrorBridge.mirrorDeleteEraserEdges(wrappers, pack, pack.undo);

        assertEquals(List.of(new Edge(0, 1, "SOFT")), wrappers);
        assertEquals(List.of(counterpart), mesh.handler.removed, diagnostics.toString());
        assertEquals(1, pack.undo.added.size());
        assertTrue(diagnostics.contains(stage("PARTICIPATION_APPLIED kind=ERASER_EDGES count=1")));
    }

    @Test
    void eraserResolvesSubclassWrappersThroughTheNativeEdgeType() {
        final List<String> diagnostics = install();
        final Mesh mesh = new Mesh(
            point(0, -2.0f, 0.0f), point(1, -1.0f, 0.0f),
            point(2, 1.0f, 0.0f), point(3, 2.0f, 0.0f)
        );
        final NativeEdge liveSource = new NativeEdge(0, 1, EdgeType.SOFT);
        final NativeEdge counterpart = new NativeEdge(2, 3, EdgeType.SOFT);
        mesh.edges.add(liveSource);
        mesh.edges.add(counterpart);
        final Pack pack = new Pack(mesh);

        NativeMeshMirrorBridge.mirrorDeleteEraserEdges(
            List.of(new EdgeWrapper(0, 1, EdgeType.SOFT, true)), pack, pack.undo
        );

        assertEquals(List.of(counterpart), mesh.handler.removed, diagnostics.toString());
        assertEquals(1, pack.undo.added.size());
        assertTrue(diagnostics.contains(stage("PARTICIPATION_APPLIED kind=ERASER_EDGES count=1")));
    }

    @Test
    void eraserResolvesEndpointWrappersInEachContextLikeNative5302() {
        final List<String> diagnostics = install();
        final Mesh first = new Mesh(
            point(0, -20.0f, 0.0f), point(1, -10.0f, 0.0f),
            point(2, 10.0f, 0.0f), point(3, 20.0f, 0.0f)
        );
        final Edge firstSource = new Edge(0, 1, "SOFT");
        final Edge firstCounterpart = new Edge(2, 3, "SOFT");
        first.edges.add(firstSource);
        first.edges.add(firstCounterpart);
        final Mesh second = new Mesh(
            point(0, -2.0f, 0.0f), point(1, -1.0f, 0.0f),
            point(2, 1.0f, 0.0f), point(3, 2.0f, 0.0f)
        );
        final Edge secondSource = new Edge(0, 1, "SOFT");
        final Edge secondCounterpart = new Edge(2, 3, "SOFT");
        second.edges.add(secondSource);
        second.edges.add(secondCounterpart);
        final Pack pack = new Pack(first, second);
        final Edge wrapper = new Edge(0, 1, "SOFT");

        NativeMeshMirrorBridge.mirrorDeleteEraserEdges(List.of(wrapper), pack, pack.undo);

        assertEquals(List.of(firstCounterpart), first.handler.removed, diagnostics.toString());
        assertEquals(List.of(secondCounterpart), second.handler.removed, diagnostics.toString());
        assertEquals(2, pack.undo.added.size());
        assertTrue(diagnostics.contains(stage("PARTICIPATION_APPLIED kind=ERASER_EDGES count=2")));
    }

    @Test
    void discreteEdgeDeletionStillRequiresExactSourceIdentity() {
        final List<String> diagnostics = install();
        final Mesh mesh = new Mesh(
            point(0, -2.0f, 0.0f), point(1, -1.0f, 0.0f),
            point(2, 1.0f, 0.0f), point(3, 2.0f, 0.0f)
        );
        mesh.edges.add(new Edge(0, 1, "SOFT"));
        mesh.edges.add(new Edge(2, 3, "SOFT"));
        final Pack pack = new Pack(mesh);

        NativeMeshMirrorBridge.rememberEdgeUndoGroup(pack.undo);
        NativeMeshMirrorBridge.mirrorDeleteEdge(new Edge(0, 1, "SOFT"), pack);

        assertTrue(mesh.handler.removed.isEmpty());
        assertTrue(pack.undo.added.isEmpty());
        assertTrue(diagnostics.contains(stage("PARTICIPATION_EMPTY kind=EDGES")));
    }

    private static List<String> install() {
        return install(true);
    }

    private static List<String> install(final boolean mirrorEnabled) {
        final List<String> diagnostics = new ArrayList<>();
        final RuntimeMeshEditUiService ui = new RuntimeMeshEditUiService();
        ui.contributeMirrorAxisAngleControl(new MeshEditUiService.MirrorAxisAngleControl(
            "mesh.mirror-axis.angle", "Angle", "Reset", -180.0f, 180.0f, 0.1f, ignored -> { }
        ));
        NativeMeshMirrorBridge.install(new RuntimeMeshMirrorAxisService(), ui);
        NativeMeshMirrorBridge.mirrorForTesting(new Mirror(mirrorEnabled));
        NativeMeshMirrorBridge.diagnostics(diagnostics::add);
        registerMirrorPolicy();
        return diagnostics;
    }

    /** The same policy the mirror-axis enhancement plugin registers; the framework holds none. */
    private static void registerMirrorPolicy() {
        NativeMeshMirrorBridge.participation().participate(deletion ->
            deletion.mirrorAxis().enabled()
                ? NativeMeshMirrorBridge.counterparts().mirrorOf(deletion)
                : dev.turboism.sdk.cubism.mesh.MeshEditContribution.none()
        );
    }

    /** Stands in for the host mirror singleton; reflects across a vertical axis at x = 0. */
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

    private static String stage(final String value) {
        return "MESH_MIRROR_DIAG stage=" + value;
    }

    private static Point point(final int id, final float x, final float y) {
        return new Point(id, new Vector(x, y));
    }

    // --- host doubles: only the members the bridge reflects over ---

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

        public float distance(final Vector other) {
            return (float) Math.hypot(x - other.x, y - other.y);
        }
    }

    public static final class Point {
        private final int id;
        private final Vector position;

        public Point(final int id, final Vector position) {
            this.id = id;
            this.position = position;
        }

        public int b() {
            return id;
        }

        public Vector getPos() {
            return position;
        }
    }

    /** Host-shaped source handle whose live point must be recovered by getCompatiblePointRef. */
    public static final class CompatiblePointRef {
        private final Point live;

        CompatiblePointRef(final Point live) {
            this.live = live;
        }
    }

    public static class Edge {
        private final int index1;
        private final int index2;
        private final String type;

        public Edge(final int index1, final int index2, final String type) {
            this.index1 = index1;
            this.index2 = index2;
            this.type = type;
        }

        public int getIndex1() {
            return index1;
        }

        public int getIndex2() {
            return index2;
        }

        public String getType() {
            return type;
        }

        @Override
        public boolean equals(final Object other) {
            return other instanceof Edge edge
                && edge.index1 == index1 && edge.index2 == index2 && edge.type.equals(type);
        }

        @Override
        public int hashCode() {
            return index1 * 31 + index2;
        }
    }

    public enum EdgeType { SOFT }

    public static class NativeEdge {
        private final int index1;
        private final int index2;
        private final EdgeType type;

        public NativeEdge(final int index1, final int index2, final EdgeType type) {
            this.index1 = index1;
            this.index2 = index2;
            this.type = type;
        }

        public int getIndex1() {
            return index1;
        }

        public int getIndex2() {
            return index2;
        }

        public EdgeType getType() {
            return type;
        }

        @Override
        public boolean equals(final Object other) {
            return other instanceof NativeEdge edge
                && edge.index1 == index1 && edge.index2 == index2 && edge.type == type;
        }

        @Override
        public int hashCode() {
            return index1 * 31 + index2;
        }
    }

    public static final class EdgeWrapper extends NativeEdge {
        public EdgeWrapper(
            final int index1,
            final int index2,
            final EdgeType type,
            final boolean candidate
        ) {
            super(index1, index2, type);
        }
    }

    public static final class Handler {
        final List<Object> removed = new ArrayList<>();

        public Object a(final List<?> edges) {
            removed.addAll(edges);
            return new Undo();
        }
    }

    public static final class Undo { }

    public static final class GroupUndo {
        final List<Object> added = new ArrayList<>();

        public void plusAssign(final Undo undo) {
            added.add(undo);
        }
    }

    public static final class Mesh {
        private final List<Point> points;
        final List<Object> edges = new ArrayList<>();
        final Handler handler = new Handler();

        Mesh(final Point... points) {
            this.points = List.of(points);
        }

        Point point(final int index) {
            return points.get(index);
        }

        public List<Point> getAllPointRef() {
            return points;
        }

        public Point getCompatiblePointRef(final Point point) {
            return points.contains(point) ? point : null;
        }

        public Point getCompatiblePointRef(final CompatiblePointRef reference) {
            return reference != null && points.contains(reference.live) ? reference.live : null;
        }

        public List<Object> getEdges() {
            return edges;
        }

        public Handler getHandler() {
            return handler;
        }
    }

    public static final class Context {
        private final Mesh mesh;

        Context(final Mesh mesh) {
            this.mesh = mesh;
        }

        public Mesh b() {
            return mesh;
        }

        /** Identity transforms: the doubles already work in mirror space. */
        public Vector b(final Vector vector) {
            return vector;
        }

        public Vector a(final Vector vector) {
            return vector;
        }
    }

    public static final class EditMode {
        final List<Object> deleted = new ArrayList<>();

        public void delete_exe(final List<?> groups, final GroupUndo undo) {
            for (Object group : groups) {
                if (group instanceof List<?> inner) deleted.addAll(inner);
            }
        }
    }

    /** Stands in for the host action pack; also hosts the mirror singleton lookup. */
    public static final class Pack {
        final EditMode editMode = new EditMode();
        final GroupUndo undo = new GroupUndo();
        private final List<Context> contexts;
        Pack(final Mesh... meshes) {
            final List<Context> collected = new ArrayList<>();
            for (Mesh mesh : meshes) collected.add(new Context(mesh));
            this.contexts = List.copyOf(collected);
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
