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
import dev.turboism.sdk.cubism.model.TexturePixels;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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

    /** Raster width the texture double describes. */
    private static final int TEXTURE_WIDTH = 8;

    /** Raster height the texture double describes. */
    private static final int TEXTURE_HEIGHT = 4;

    /** uv-to-pixel scale of the texture double, chosen so pixels differ from uvs. */
    private static final double UV_TO_PIXEL_SCALE = 3.0d;

    /** Local-to-canvas scale of the transform double, chosen so it is not the identity. */
    private static final double LOCAL_TO_CANVAS_SCALE = 2.5d;

    /** Local-to-canvas offset of the transform double. */
    private static final double LOCAL_TO_CANVAS_OFFSET = 7.0d;

    /** Canvas tolerance far below one drawn pixel; the conversion stores 32-bit floats. */
    private static final float TOLERANCE = 1.0e-4f;

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
    void layoutUvsReachTheCanvasThroughTheTextureAndImageTransforms() {
        final AutoMeshWriteLayout layout = new AutoMeshWriteLayout(
                new ArtMeshGeometry(
                        List.of(new Point2(0.0f, 0.0f), new Point2(1.0f, 1.0f)),
                        List.of(new Point2(0.0f, 0.0f), new Point2(1.0f, 1.0f)),
                        List.of()),
                Optional.of(texture()));

        final AutoMeshCanvasPoints canvas = AutoMeshCanvasPoints.of(layout, null, localToCanvas());

        assertArrayEquals(
                new float[] {
                    (float) LOCAL_TO_CANVAS_OFFSET,
                    (float) LOCAL_TO_CANVAS_OFFSET,
                    (float) (TEXTURE_WIDTH * UV_TO_PIXEL_SCALE * LOCAL_TO_CANVAS_SCALE + LOCAL_TO_CANVAS_OFFSET),
                    (float) (TEXTURE_HEIGHT * UV_TO_PIXEL_SCALE * LOCAL_TO_CANVAS_SCALE + LOCAL_TO_CANVAS_OFFSET)
                },
                canvas.pointsXY(),
                TOLERANCE,
                "the layout pixel travels through the texture affine and the image transform");
    }

    @Test
    void geometryOnlyLayoutScalesByTheImageExtentAndListsEveryEdgeOnce() {
        final AutoMeshWriteLayout layout = AutoMeshWriteLayout.of(new ArtMeshGeometry(
                List.of(new Point2(0.0f, 0.0f), new Point2(1.0f, 0.0f), new Point2(0.0f, 1.0f)),
                List.of(new Point2(0.0f, 0.0f), new Point2(1.0f, 0.0f), new Point2(0.0f, 1.0f)),
                List.of(0, 1, 2)));

        final AutoMeshCanvasPoints canvas =
                AutoMeshCanvasPoints.of(layout, new double[] {TEXTURE_WIDTH, TEXTURE_HEIGHT}, identity());

        assertArrayEquals(
                new float[] {0.0f, 0.0f, TEXTURE_WIDTH, 0.0f, 0.0f, TEXTURE_HEIGHT}, canvas.pointsXY(), TOLERANCE);
        assertArrayEquals(new int[] {0, 1, 1, 2, 2, 0}, canvas.edgePairs(), "each triangle side appears once");
    }

    @Test
    void aLayoutWithoutPixelExtentCannotBeScaled() {
        final AutoMeshWriteLayout layout = AutoMeshWriteLayout.of(new ArtMeshGeometry(
                List.of(new Point2(0.0f, 0.0f), new Point2(1.0f, 0.0f)),
                List.of(new Point2(0.0f, 0.0f), new Point2(1.0f, 0.0f)),
                List.of()));

        assertThrows(IllegalArgumentException.class, () -> AutoMeshCanvasPoints.of(layout, null, identity()));
    }

    private static int[] edgesOfSquare() {
        // Two triangles over one square; the shared diagonal is already offered once.
        return new int[] {0, 1, 1, 2, 0, 2, 2, 3, 0, 3};
    }

    private static TexturePixels texture() {
        final double[] uvToPixel = {
            TEXTURE_WIDTH * UV_TO_PIXEL_SCALE, 0.0d, 0.0d, 0.0d, TEXTURE_HEIGHT * UV_TO_PIXEL_SCALE, 0.0d
        };
        return new TexturePixels(TEXTURE_WIDTH, TEXTURE_HEIGHT, new int[TEXTURE_WIDTH * TEXTURE_HEIGHT], uvToPixel);
    }

    private static double[] localToCanvas() {
        return new double[] {
            LOCAL_TO_CANVAS_SCALE, 0.0d, LOCAL_TO_CANVAS_OFFSET, 0.0d, LOCAL_TO_CANVAS_SCALE, LOCAL_TO_CANVAS_OFFSET
        };
    }

    private static double[] identity() {
        return new double[] {1.0d, 0.0d, 0.0d, 0.0d, 1.0d, 0.0d};
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
}
