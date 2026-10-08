package dev.turboism.adapter.cubism.mesh;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.mesh.SelectionMode;
import dev.turboism.sdk.cubism.mesh.VertexSelection;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/** Exact native point-selector adapter. Selection writes are not authoring transactions and create no Undo entry. */
final class NativeVertexSelectionAdapter {
    private final NativeMeshToolSession session;
    private final HostSelectionAccess host;

    NativeVertexSelectionAdapter(final NativeMeshToolSession session, final VerifiedMemberResolver resolver) {
        this(session, exactHost(session, Objects.requireNonNull(resolver, "resolver")));
    }

    NativeVertexSelectionAdapter(final NativeMeshToolSession session, final HostSelectionAccess host) {
        this.session = Objects.requireNonNull(session, "session");
        this.host = Objects.requireNonNull(host, "host");
    }

    VertexSelection selection() {
        requireCurrent();
        return readSelection(vertexCount());
    }

    void select(final VertexSelection requested, final SelectionMode mode) {
        Objects.requireNonNull(requested, "requested");
        Objects.requireNonNull(mode, "mode");
        requireCurrent();
        final int vertexCount = vertexCount();
        validateBounds(requested.indices(), vertexCount);
        final VertexSelection current = readSelection(vertexCount);
        final VertexSelection expected = expected(current, requested, mode);
        if (mode == SelectionMode.ADD) {
            addMissing(current, requested);
        } else {
            replaceWith(expected);
        }
        requireCurrent();
        final VertexSelection observed = readSelection(vertexCount);
        if (!observed.equals(expected)) {
            throw new IllegalStateException("Native vertex selection did not match the requested observed result.");
        }
    }

    private void addMissing(final VertexSelection current, final VertexSelection requested) {
        final TreeSet<Integer> present = new TreeSet<>(current.indices());
        for (int index : requested.indices()) {
            if (present.add(index) && !host.add(host.pointRef(index))) {
                throw new IllegalStateException("Native point selector rejected an exact add operation.");
            }
        }
    }

    private void replaceWith(final VertexSelection selection) {
        host.clear();
        for (int index : selection.indices()) {
            if (!host.add(host.pointRef(index))) {
                throw new IllegalStateException("Native point selector rejected a computed selection member.");
            }
        }
    }

    private VertexSelection readSelection(final int vertexCount) {
        final List<?> points = host.selectedPoints();
        if (points == null) throw new IllegalStateException("Native point selection is unavailable.");
        final ArrayList<Integer> indices = new ArrayList<>(points.size());
        for (Object point : points) {
            if (point == null || host.pointMesh(point) != session.identity().editableMesh()) {
                throw new IllegalStateException("Native point selection contains a foreign mesh reference.");
            }
            final int index = host.pointIndex(point);
            if (index < 0 || index >= vertexCount) {
                throw new IllegalStateException("Native point selection contains an out-of-bounds vertex reference.");
            }
            indices.add(index);
        }
        return new VertexSelection(indices);
    }

    private int vertexCount() {
        final int count = host.pointCount();
        if (count < 0) throw new IllegalStateException("Native editable mesh point count is unavailable.");
        return count;
    }

    private void requireCurrent() {
        if (!session.revalidate()) throw new IllegalStateException("Native mesh-tool session is stale.");
    }

    private static void validateBounds(final List<Integer> indices, final int vertexCount) {
        for (int index : indices) {
            if (index < 0 || index >= vertexCount) {
                throw new IllegalArgumentException("Vertex index is outside the exact active mesh.");
            }
        }
    }

    private static VertexSelection expected(
            final VertexSelection current, final VertexSelection requested, final SelectionMode mode) {
        final TreeSet<Integer> result = new TreeSet<>(current.indices());
        switch (mode) {
            case REPLACE -> {
                result.clear();
                result.addAll(requested.indices());
            }
            case ADD -> result.addAll(requested.indices());
            case REMOVE -> result.removeAll(requested.indices());
            case TOGGLE -> {
                for (int index : requested.indices()) {
                    if (!result.remove(index)) result.add(index);
                }
            }
        }
        return new VertexSelection(List.copyOf(result));
    }

    private static HostSelectionAccess exactHost(
            final NativeMeshToolSession session, final VerifiedMemberResolver resolver) {
        return new HostSelectionAccess() {
            @Override
            public List<?> selectedPoints() {
                final Object value = resolver.invoke(
                        MeshToolSessionSelectorContract.POINT_SELECTOR_SELECTED_POINTS,
                        session.identity().pointSelector());
                if (!(value instanceof List<?> list)) {
                    throw new IllegalStateException("Native point selector did not return a list.");
                }
                return List.copyOf(list);
            }

            @Override
            public Object pointMesh(final Object point) {
                if (!resolver.isInstance(MeshToolSessionSelectorContract.POINT_REF_CLASS, point)) {
                    throw new IllegalStateException("Native point selector returned an unverified point reference.");
                }
                return resolver.invoke(MeshToolSessionSelectorContract.POINT_REF_MESH, point);
            }

            @Override
            public int pointIndex(final Object point) {
                final Object value = resolver.invoke(MeshToolSessionSelectorContract.POINT_REF_INDEX, point);
                if (!(value instanceof Number number))
                    throw new IllegalStateException("Native point index is unavailable.");
                return number.intValue();
            }

            @Override
            public Object pointRef(final int index) {
                final Object point = resolver.invoke(
                        MeshToolSessionSelectorContract.EDITABLE_MESH_POINT_REF,
                        session.identity().editableMesh(),
                        index);
                if (point == null
                        || !resolver.isInstance(MeshToolSessionSelectorContract.POINT_REF_CLASS, point)
                        || pointMesh(point) != session.identity().editableMesh()
                        || pointIndex(point) != index) {
                    throw new IllegalStateException("Native editable mesh returned a mismatched point reference.");
                }
                return point;
            }

            @Override
            public void clear() {
                resolver.invoke(
                        MeshToolSessionSelectorContract.POINT_SELECTOR_CLEAR,
                        session.identity().pointSelector());
            }

            @Override
            public boolean add(final Object point) {
                return Boolean.TRUE.equals(resolver.invoke(
                        MeshToolSessionSelectorContract.POINT_SELECTOR_ADD,
                        session.identity().pointSelector(),
                        point));
            }

            @Override
            public int pointCount() {
                final Object value = resolver.invoke(
                        MeshToolSessionSelectorContract.EDITABLE_MESH_POINT_COUNT,
                        session.identity().editableMesh());
                if (!(value instanceof Number number)) {
                    throw new IllegalStateException("Native editable mesh point count is unavailable.");
                }
                return number.intValue();
            }
        };
    }

    interface HostSelectionAccess {
        List<?> selectedPoints();

        Object pointMesh(Object point);

        int pointIndex(Object point);

        Object pointRef(int index);

        void clear();

        boolean add(Object point);

        int pointCount();
    }
}
