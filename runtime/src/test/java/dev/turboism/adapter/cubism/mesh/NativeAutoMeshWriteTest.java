package dev.turboism.adapter.cubism.mesh;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.live2d.core.ICopyable;
import com.live2d.core.a;
import dev.turboism.automesh.AutoMeshWriteLayout;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.Point2;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Contracts of the native-shaped editable-mesh write.
 *
 * <p>The Editor derives uvs, keyforms and triangulation from the editable mesh, so the write must
 * land the canvas points and every triangle edge in a fresh mesh and copy that mesh into the
 * instance the edit mode holds. A fake mesh with the host member shapes proves both halves.</p>
 */
class NativeAutoMeshWriteTest {

    /** Left edge of the ArtMesh rectangle, far from the canvas origin like a real part. */
    private static final float MESH_LEFT = 1531.0f;

    /** Top edge of the ArtMesh rectangle. */
    private static final float MESH_TOP = 1774.0f;

    /** Width of the ArtMesh rectangle. */
    private static final float MESH_WIDTH = 275.0f;

    /** Height of the ArtMesh rectangle. */
    private static final float MESH_HEIGHT = 280.0f;

    /** Uvs of the two-triangle source quad. */
    private static final float[] SOURCE_UVS = {0.0f, 0.0f, 1.0f, 0.0f, 1.0f, 1.0f, 0.0f, 1.0f};

    /** Base positions of the source quad, in the same vertex order as its uvs. */
    private static final float[] SOURCE_POSITIONS = {
        MESH_LEFT,
        MESH_TOP,
        MESH_LEFT + MESH_WIDTH,
        MESH_TOP,
        MESH_LEFT + MESH_WIDTH,
        MESH_TOP + MESH_HEIGHT,
        MESH_LEFT,
        MESH_TOP + MESH_HEIGHT
    };

    /** Triangles of the source quad. */
    private static final int[] SOURCE_INDICES = {0, 1, 2, 0, 2, 3};

    /** Mesh-space tolerance far below one drawn pixel; the conversion stores 32-bit floats. */
    private static final float MESH_TOLERANCE = 1.0e-2f;

    @BeforeEach
    void resetCounters() {
        FakeMesh.reset();
    }

    @Test
    void theFreshMeshReceivesEveryCanvasPointAndEveryTriangleEdgeOnce() throws Exception {
        final FakeMesh target = new FakeMesh(new FakeGuid("mesh-guid"));

        NativeAutoMeshWrite.replaceGeometry(
                target, new float[] {0.0f, 0.0f, 10.0f, 0.0f, 10.0f, 10.0f, 0.0f, 10.0f}, edgesOfSquare());

        assertEquals(
                List.of(0.0f, 0.0f, 10.0f, 0.0f, 10.0f, 10.0f, 0.0f, 10.0f),
                target.points,
                "the target keeps the canvas points");
        assertEquals(5, target.edges.size(), "four sides plus one diagonal, each offered once");
        assertTrue(target.edges.contains(List.of(0, 1)));
        assertTrue(target.edges.contains(List.of(0, 2)), "the diagonal is a triangle edge too");
        assertEquals(1, target.positionUpdated, "the copy marks positions updated");
        assertEquals(1, target.edgeUpdated, "the copy marks edges updated");
    }

    @Test
    void theWriteBuildsAFreshMeshWithTheTargetsOwnGuidAndCopiesIt() throws Exception {
        final FakeGuid guid = new FakeGuid("mesh-guid");
        final FakeMesh target = new FakeMesh(guid);
        target.points.addAll(List.of(99.0f, 99.0f));

        NativeAutoMeshWrite.replaceGeometry(target, new float[] {1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f}, new int[] {0, 1});

        assertEquals(2, FakeMesh.instances, "the Editor's generator replaces a fresh mesh, never the live one");
        assertSame(guid, FakeMesh.lastGuid, "the fresh mesh keeps the target's guid");
        assertEquals(List.of(1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f), target.points, "the stale point is gone");
        assertEquals(List.of(List.of(0, 1)), target.edges);
        assertEquals(1, target.reinitCalls, "one copy replaces the target content");
    }

    @Test
    void aZeroLengthTriangleEdgeIsNotOffered() throws Exception {
        final FakeMesh target = new FakeMesh(new FakeGuid("mesh-guid"));

        NativeAutoMeshWrite.replaceGeometry(
                target, new float[] {0.0f, 0.0f, 1.0f, 0.0f, 1.0f, 1.0f}, new int[] {0, 0, 0, 1, 1, 2});

        assertEquals(List.of(List.of(0, 1), List.of(1, 2)), target.edges);
    }

    @Test
    void layoutUvsLandInsideTheArtMeshBasePositionsNotAtTheOrigin() {
        // The live defect wrote every point near the canvas origin; the layout must reuse the
        // ArtMesh base space, so a centre uv lands at the centre of the mesh rectangle.
        final AutoMeshWriteLayout layout = AutoMeshWriteLayout.of(new ArtMeshGeometry(
                List.of(new Point2(0.0f, 0.0f), new Point2(0.0f, 0.0f), new Point2(0.0f, 0.0f)),
                List.of(new Point2(0.25f, 0.25f), new Point2(0.5f, 0.5f), new Point2(0.75f, 0.25f)),
                List.of(0, 1, 2)));

        final AutoMeshCanvasPoints mesh = AutoMeshCanvasPoints.of(layout, SOURCE_UVS, SOURCE_POSITIONS, SOURCE_INDICES);

        assertArrayEquals(
                new float[] {
                    MESH_LEFT + MESH_WIDTH * 0.25f, MESH_TOP + MESH_HEIGHT * 0.25f,
                    MESH_LEFT + MESH_WIDTH * 0.5f, MESH_TOP + MESH_HEIGHT * 0.5f,
                    MESH_LEFT + MESH_WIDTH * 0.75f, MESH_TOP + MESH_HEIGHT * 0.25f
                },
                mesh.pointsXY(),
                MESH_TOLERANCE,
                "every layout uv maps through the ArtMesh uv-to-base-position correspondence");
        assertArrayEquals(new int[] {0, 1, 1, 2, 2, 0}, mesh.edgePairs(), "each triangle side appears once");
    }

    @Test
    void aSourceWithoutTrianglesCannotMapTheLayout() {
        final AutoMeshWriteLayout layout = AutoMeshWriteLayout.of(
                new ArtMeshGeometry(List.of(new Point2(0.0f, 0.0f)), List.of(new Point2(0.5f, 0.5f)), List.of()));

        assertThrows(
                IllegalArgumentException.class,
                () -> AutoMeshCanvasPoints.of(layout, SOURCE_UVS, SOURCE_POSITIONS, new int[0]));
    }

    private static int[] edgesOfSquare() {
        // Two triangles over one square; the shared diagonal is already offered once.
        return new int[] {0, 1, 1, 2, 0, 2, 2, 3, 0, 3};
    }

    /** Mesh guid stand-in; the writer only carries it from the target to the fresh mesh. */
    private static final class FakeGuid {

        private final String value;

        private FakeGuid(final String value) {
            this.value = value;
        }
    }

    /** One host edge as the writer reads it back through {@code getEdges()}. */
    private record FakeEdge(int index1, int index2, Object type) {

        public Object getIndex1() {
            return index1;
        }

        public Object getIndex2() {
            return index2;
        }

        public Object getType() {
            return type;
        }
    }

    /**
     * Editable-mesh double that carries the host member shapes the writer reflectively names.
     *
     * <p>It records what the fresh mesh received and copies that content into the live instance
     * through {@link ICopyable#reinit}, which is the host's own copy shape.</p>
     */
    private static final class FakeMesh implements ICopyable {

        /** Point types the writer selects by name. */
        private enum PointType {
            LOCKED,
            NORMAL,
            AUTO
        }

        /** Edge types the writer selects by name. */
        private enum EdgeType {
            LOCKED,
            NORMAL
        }

        private static int instances;
        private static FakeGuid lastGuid;

        /** Clears the instance counters between cases; the class lives for the whole run. */
        private static void reset() {
            instances = 0;
            lastGuid = null;
        }

        private final FakeGuid guid;
        private final List<Float> points = new ArrayList<>();
        private final List<List<Integer>> edges = new ArrayList<>();
        private int positionUpdated;
        private int edgeUpdated;
        private int reinitCalls;

        private FakeMesh(final FakeGuid guid) {
            this.guid = guid;
            instances++;
            lastGuid = guid;
        }

        public FakeGuid getMeshGuid() {
            return guid;
        }

        public int addPoint(final float x, final float y, final PointType type, final long uid) {
            assertEquals(PointType.AUTO, type, "generated points are AUTO, so a later run may replace them");
            points.add(x);
            points.add(y);
            return points.size() / 2 - 1;
        }

        public Integer addEdge(
                final int first, final int second, final EdgeType type, final boolean rename, final boolean reverse) {
            edges.add(List.of(first, second));
            return edges.size() - 1;
        }

        public List<FakeEdge> getEdges() {
            final List<FakeEdge> host = new ArrayList<>(edges.size());
            for (final List<Integer> edge : edges) {
                host.add(new FakeEdge(edge.get(0), edge.get(1), EdgeType.NORMAL));
            }
            return host;
        }

        public void setPositionUpdated() {
            positionUpdated++;
        }

        public void setEdgeUpdated() {
            edgeUpdated++;
        }

        @Override
        public ICopyable deepCopy(final a context) {
            throw new UnsupportedOperationException("the writer copies into the live instance");
        }

        @Override
        public void reinit(final ICopyable source, final a context) {
            final FakeMesh fresh = (FakeMesh) source;
            reinitCalls++;
            points.clear();
            points.addAll(fresh.points);
            edges.clear();
            edges.addAll(fresh.edges);
            positionUpdated = fresh.positionUpdated;
            edgeUpdated = fresh.edgeUpdated;
        }
    }

    /**
     * Pins every host class name the production write loads to the reviewed artifact archive.
     *
     * <p>The write fails closed on a missing class, so a mistyped name refuses every live run while
     * fake-mesh tests stay green. This case reads the production constants, not a test copy.</p>
     */
    @Test
    void everyHostClassNameTheWriteLoadsExistsInEachReviewedArtifact() throws Exception {
        final java.nio.file.Path root = referenceRoot();
        org.junit.jupiter.api.Assumptions.assumeTrue(root != null, NO_ARCHIVE_MESSAGE);
        final List<String> missing = new ArrayList<>();
        int inspected = 0;
        for (final String version : dev.turboism.automesh.host.AutoMeshHostProfile.supportedHostVersions()) {
            final java.nio.file.Path jar = root.resolve(version).resolve(EDITOR_JAR);
            if (!java.nio.file.Files.isRegularFile(jar)) {
                continue;
            }
            inspected++;
            try (java.util.jar.JarFile archive = new java.util.jar.JarFile(jar.toFile())) {
                for (final String name : NativeAutoMeshWrite.HOST_CLASS_NAMES) {
                    if (archive.getJarEntry(name.replace('.', '/') + CLASS_SUFFIX) == null) {
                        missing.add(version + " " + name);
                    }
                }
            }
        }
        assertTrue(inspected > 0, NO_ARCHIVE_MESSAGE);
        assertEquals(List.of(), missing, "every host class the write loads must exist in each reviewed artifact");
    }

    @Test
    void eachReviewedArtifactCarriesOneSourceUpdateActionTheModelingWriteNames() throws Exception {
        final java.nio.file.Path root = referenceRoot();
        org.junit.jupiter.api.Assumptions.assumeTrue(root != null, NO_ARCHIVE_MESSAGE);
        final List<String> missing = new ArrayList<>();
        int inspected = 0;
        for (final String version : dev.turboism.automesh.host.AutoMeshHostProfile.supportedHostVersions()) {
            final java.nio.file.Path jar = root.resolve(version).resolve(EDITOR_JAR);
            if (!java.nio.file.Files.isRegularFile(jar)) {
                continue;
            }
            inspected++;
            try (java.net.URLClassLoader loader =
                    new java.net.URLClassLoader(new java.net.URL[] {jar.toUri().toURL()}, null)) {
                boolean found = false;
                for (final String name : NativeAutoMeshWrite.SOURCE_UPDATE_ACTIONS) {
                    final byte[] bytes;
                    try (java.io.InputStream input =
                            loader.getResourceAsStream(name.replace('.', '/') + CLASS_SUFFIX)) {
                        bytes = input == null ? null : input.readAllBytes();
                    }
                    if (bytes != null
                            && new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1)
                                    .contains(SOURCE_UPDATE_DESCRIPTOR)) {
                        found = true;
                    }
                }
                if (!found) missing.add(version);
            }
        }
        assertTrue(inspected > 0, NO_ARCHIVE_MESSAGE);
        assertEquals(List.of(), missing, "each reviewed artifact must carry the ArtMesh source-update action");
    }

    private static java.nio.file.Path referenceRoot() {
        for (final String candidate :
                new String[] {System.getProperty(REFERENCE_PROPERTY), System.getenv(REFERENCE_ENVIRONMENT)}) {
            if (candidate != null && !candidate.isBlank()) {
                final java.nio.file.Path root =
                        java.nio.file.Path.of(candidate).toAbsolutePath().normalize();
                return java.nio.file.Files.isDirectory(root) ? root : null;
            }
        }
        return null;
    }

    private static final String REFERENCE_PROPERTY = "turboism.test.cubismRefDir";
    private static final String REFERENCE_ENVIRONMENT = "TURBOISM_CUBISM_REF";
    private static final String EDITOR_JAR = "Live2D_Cubism.jar";
    private static final String CLASS_SUFFIX = ".class";
    /** Descriptor prefix of the action that rebuilds an ArtMesh source from an editable mesh. */
    private static final String SOURCE_UPDATE_DESCRIPTOR =
            "(Lcom/live2d/cubism/doc/model/drawable/artMesh/CArtMeshSource;"
                    + "Lcom/live2d/graphics3d/editableMesh/GEditableMesh2;Ljava/util/Map;";

    private static final String NO_ARCHIVE_MESSAGE =
            "reviewed Cubism artifact archive is not configured; set TURBOISM_CUBISM_REF";
}
