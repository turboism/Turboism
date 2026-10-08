package dev.turboism.adapter.cubism.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.turboism.sdk.cubism.mesh.SelectionMode;
import dev.turboism.sdk.cubism.mesh.VertexSelection;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NativeVertexSelectionAdapterTest {
    @Test
    void appliesExactModesAndUsesDirectAddWithoutClearForBrushAdd() {
        final Fixture fixture = fixture(5);
        fixture.host.indices(0, 2);

        fixture.adapter.select(new VertexSelection(List.of(3)), SelectionMode.ADD);
        assertEquals(List.of(0, 2, 3), fixture.adapter.selection().indices());
        assertEquals(0, fixture.host.clearCalls);
        assertEquals(1, fixture.host.addCalls);

        fixture.adapter.select(new VertexSelection(List.of(1, 4)), SelectionMode.REPLACE);
        assertEquals(List.of(1, 4), fixture.adapter.selection().indices());
        fixture.adapter.select(new VertexSelection(List.of(4)), SelectionMode.REMOVE);
        assertEquals(List.of(1), fixture.adapter.selection().indices());
        fixture.adapter.select(new VertexSelection(List.of(0, 1, 3)), SelectionMode.TOGGLE);
        assertEquals(List.of(0, 3), fixture.adapter.selection().indices());
        assertEquals(3, fixture.host.clearCalls);
    }

    @Test
    void rejectsBoundsForeignOwnersAndObservedResultDriftBeforeReturning() {
        final Fixture fixture = fixture(3);
        assertThrows(
                IllegalArgumentException.class,
                () -> fixture.adapter.select(new VertexSelection(List.of(3)), SelectionMode.ADD));
        assertEquals(0, fixture.host.addCalls);

        fixture.host.points.add(new Point(new Object(), 1));
        assertThrows(IllegalStateException.class, fixture.adapter::selection);

        fixture.host.points.clear();
        fixture.host.ignoreAdds = true;
        assertThrows(
                IllegalStateException.class,
                () -> fixture.adapter.select(new VertexSelection(List.of(1)), SelectionMode.ADD));
    }

    private static Fixture fixture(final int vertexCount) {
        final Object mode = new Object();
        final Object editableMesh = new Object();
        final MeshToolSessionResolver.Snapshot snapshot = new MeshToolSessionResolver.Snapshot(
                mode,
                new Object(),
                new Object(),
                new Object(),
                editableMesh,
                new Object(),
                new Object(),
                new Object(),
                new Object(),
                new Object(),
                new Object(),
                new Object(),
                new Object(),
                new Object());
        final MeshToolSessionResolver resolver = new MeshToolSessionResolver((ignoredMode, ignoredEntries) -> snapshot);
        final NativeMeshToolSession session = resolver.resolve(mode, List.of()).orElseThrow();
        final FakeHostSelection host = new FakeHostSelection(editableMesh, vertexCount);
        return new Fixture(new NativeVertexSelectionAdapter(session, host), host);
    }

    private record Fixture(NativeVertexSelectionAdapter adapter, FakeHostSelection host) {}

    private record Point(Object mesh, int index) {}

    private static final class FakeHostSelection implements NativeVertexSelectionAdapter.HostSelectionAccess {
        private final Object mesh;
        private final int pointCount;
        private final Set<Point> points = new LinkedHashSet<>();
        private int clearCalls;
        private int addCalls;
        private boolean ignoreAdds;

        private FakeHostSelection(final Object mesh, final int pointCount) {
            this.mesh = mesh;
            this.pointCount = pointCount;
        }

        void indices(final int... values) {
            for (int value : values) points.add(new Point(mesh, value));
        }

        @Override
        public List<?> selectedPoints() {
            return new ArrayList<>(points);
        }

        @Override
        public Object pointMesh(final Object point) {
            return ((Point) point).mesh();
        }

        @Override
        public int pointIndex(final Object point) {
            return ((Point) point).index();
        }

        @Override
        public Object pointRef(final int index) {
            return new Point(mesh, index);
        }

        @Override
        public void clear() {
            clearCalls++;
            points.clear();
        }

        @Override
        public boolean add(final Object point) {
            addCalls++;
            return ignoreAdds || points.add((Point) point);
        }

        @Override
        public int pointCount() {
            return pointCount;
        }
    }
}
