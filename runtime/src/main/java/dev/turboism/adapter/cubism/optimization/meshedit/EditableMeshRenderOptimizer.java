package dev.turboism.adapter.cubism.optimization.meshedit;

import dev.turboism.core.runtime.work.FatalErrors;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.BitSet;
import java.util.List;

/**
 * Eliminates quadratic point-in-edge scans and redundant buffer dirty marks during editable-mesh
 * wireframe and point overlay rendering.
 */
public final class EditableMeshRenderOptimizer {

    private static final int COORDS_PER_VERTEX = 2;
    private static final int PACK_MIX_SHIFT = 16;

    private static final MeshArrayFingerprint.WeakIdentityCache<MeshPointCache> MESH_POINT_CACHE =
            new MeshArrayFingerprint.WeakIdentityCache<>();
    private static final MeshArrayFingerprint.WeakIdentityCache<EntityRenderState> ENTITY_RENDER_STATE =
            new MeshArrayFingerprint.WeakIdentityCache<>();

    private static final ClassValue<MeshAccess> MESH_ACCESS = new ClassValue<>() {
        @Override
        protected MeshAccess computeValue(final Class<?> type) {
            return MeshAccess.resolve(type);
        }
    };
    private static final ClassValue<PointAccess> POINT_ACCESS = new ClassValue<>() {
        @Override
        protected PointAccess computeValue(final Class<?> type) {
            return PointAccess.resolve(type);
        }
    };
    private static final ClassValue<EdgeAccess> EDGE_ACCESS = new ClassValue<>() {
        @Override
        protected EdgeAccess computeValue(final Class<?> type) {
            return EdgeAccess.resolve(type);
        }
    };
    private static final ClassValue<FieldSnapshotter> FIELD_SNAPSHOTTER = new ClassValue<>() {
        @Override
        protected FieldSnapshotter computeValue(final Class<?> type) {
            return FieldSnapshotter.resolve(type);
        }
    };
    private static final ClassValue<ColorAccess> COLOR_ACCESS = new ClassValue<>() {
        @Override
        protected ColorAccess computeValue(final Class<?> type) {
            return ColorAccess.resolve(type);
        }
    };
    private static final ClassValue<SelectionAccess> SELECTION_ACCESS = new ClassValue<>() {
        @Override
        protected SelectionAccess computeValue(final Class<?> type) {
            return SelectionAccess.resolve(type);
        }
    };
    private static final ClassValue<PackAccess> PACK_ACCESS = new ClassValue<>() {
        @Override
        protected PackAccess computeValue(final Class<?> type) {
            return PackAccess.resolve(type);
        }
    };
    private static final ClassValue<MethodHandle> ENTITY_SET_ENABLED = new ClassValue<>() {
        @Override
        protected MethodHandle computeValue(final Class<?> type) {
            try {
                return MethodHandles.publicLookup()
                        .findVirtual(type, "setEnabled", MethodType.methodType(void.class, boolean.class));
            } catch (final ReflectiveOperationException failure) {
                return null;
            }
        }
    };

    private EditableMeshRenderOptimizer() {}

    /**
     * Extracts connected or isolated vertex positions from an editable mesh in linear {@code O(P + E)}
     * time without boxing integers or floats.
     *
     * @param mesh editable mesh host instance
     * @param connected {@code true} for vertices attached to at least one edge; {@code false} for
     *     isolated vertices
     * @return packed {@code [x0, y0, x1, y1, ...]} coordinates or {@code null} to run fallback
     */
    public static float[] extractPointPositions(final Object mesh, final boolean connected) {
        if (mesh == null) {
            return null;
        }
        try {
            final MeshAccess ma = MESH_ACCESS.get(mesh.getClass());
            final int posVer = (int) ma.posVersion.invoke(mesh);
            final int edgeVer = (int) ma.edgeVersion.invoke(mesh);
            final List<?> points = (List<?>) ma.getPoints.invoke(mesh);
            final List<?> edges = (List<?>) ma.getEdges.invoke(mesh);
            final int pointCount = points.size();
            final int edgeCount = edges.size();

            final MeshPointCache cached = MESH_POINT_CACHE.get(mesh);
            final long coordDigest = samplePointDigest(points);
            final long edgeDigest = sampleEdgeDigest(edges);
            if (cached != null
                    && cached.posVer == posVer
                    && cached.edgeVer == edgeVer
                    && cached.pointCount == pointCount
                    && cached.edgeCount == edgeCount
                    && cached.coordDigest == coordDigest
                    && cached.edgeDigest == edgeDigest) {
                return connected ? cached.connectedPositions : cached.isolatedPositions;
            }

            final boolean[] connectedMask = new boolean[pointCount];
            BitSet overflowMask = null;
            EdgeAccess ea = null;
            for (int e = 0; e < edgeCount; e++) {
                final Object edge = edges.get(e);
                if (ea == null) {
                    ea = EDGE_ACCESS.get(edge.getClass());
                }
                final int i1 = (int) ea.getIndex1.invoke(edge);
                final int i2 = (int) ea.getIndex2.invoke(edge);
                overflowMask = markVertex(connectedMask, overflowMask, pointCount, i1);
                overflowMask = markVertex(connectedMask, overflowMask, pointCount, i2);
            }

            PointAccess pa = null;
            int connectedCount = 0;
            for (int p = 0; p < pointCount; p++) {
                final Object pt = points.get(p);
                if (pa == null) {
                    pa = POINT_ACCESS.get(pt.getClass());
                }
                final int pointIdx = (int) pa.getIndex.invoke(pt);
                if (isMarked(connectedMask, overflowMask, pointCount, pointIdx)) {
                    connectedCount++;
                }
            }

            final int isolatedCount = pointCount - connectedCount;
            final float[] connectedArr = new float[connectedCount * COORDS_PER_VERTEX];
            final float[] isolatedArr = new float[isolatedCount * COORDS_PER_VERTEX];
            int cOff = 0;
            int iOff = 0;
            for (int p = 0; p < pointCount; p++) {
                final Object pt = points.get(p);
                final int pointIdx = (int) pa.getIndex.invoke(pt);
                final float x = (float) pa.getX.invoke(pt);
                final float y = (float) pa.getY.invoke(pt);
                if (isMarked(connectedMask, overflowMask, pointCount, pointIdx)) {
                    connectedArr[cOff++] = x;
                    connectedArr[cOff++] = y;
                } else {
                    isolatedArr[iOff++] = x;
                    isolatedArr[iOff++] = y;
                }
            }

            final MeshPointCache next = new MeshPointCache(
                    posVer, edgeVer, pointCount, edgeCount, coordDigest, edgeDigest, connectedArr, isolatedArr);
            MESH_POINT_CACHE.remove(mesh);
            MESH_POINT_CACHE.computeIfAbsent(mesh, ignored -> next);
            return connected ? connectedArr : isolatedArr;
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return null;
        }
    }

    /**
     * Checks whether the non-selection wireframe and point buffers on a {@code DrawEntity_EditableMesh}
     * match the previous rendered frame, preserving disabled highlight entity state when empty.
     *
     * @param entity {@code DrawEntity_EditableMesh} instance
     * @param mesh current {@code GEditableMesh2}
     * @param baseSetting {@code DrawEntity_EditableMesh$a}
     * @param drawSetting {@code DrawEntity_EditableMesh$c}
     * @param highlightEntity nullable {@code eArtMeshPointHighlight} entity
     * @return {@code true} if lines and base point buffers can stay untouched
     */
    public static boolean isOverlayCacheValid(
            final Object entity,
            final Object mesh,
            final Object baseSetting,
            final Object drawSetting,
            final Object highlightEntity) {
        if (entity == null || mesh == null || drawSetting == null) {
            return false;
        }
        final EntityRenderState state = ENTITY_RENDER_STATE.get(entity);
        if (state == null || !state.overlayValid) {
            return false;
        }
        try {
            final MeshAccess ma = MESH_ACCESS.get(mesh.getClass());
            final int posVer = (int) ma.posVersion.invoke(mesh);
            final int edgeVer = (int) ma.edgeVersion.invoke(mesh);
            final List<?> points = (List<?>) ma.getPoints.invoke(mesh);
            final List<?> edges = (List<?>) ma.getEdges.invoke(mesh);
            final long pointDigest = samplePointDigest(points);
            final long edgeDigest = sampleEdgeDigest(edges);
            final long baseDigest = snapshotDigest(baseSetting);
            final long drawDigest = snapshotDigest(drawSetting);
            final boolean match = state.overlayMesh.get() == mesh
                    && state.overlayPosVer == posVer
                    && state.overlayEdgeVer == edgeVer
                    && state.overlayPointCount == points.size()
                    && state.overlayEdgeCount == edges.size()
                    && state.overlayPointDigest == pointDigest
                    && state.overlayEdgeDigest == edgeDigest
                    && state.overlayBaseDigest == baseDigest
                    && state.overlayDrawDigest == drawDigest;
            if (!match) {
                return false;
            }
            if (highlightEntity != null && state.overlayIsolatedEmpty) {
                setEntityEnabled(highlightEntity, false);
            }
            return true;
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return false;
        }
    }

    /**
     * Records a completed overlay geometry and material build on a {@code DrawEntity_EditableMesh}.
     *
     * @param entity {@code DrawEntity_EditableMesh} instance
     * @param mesh rendered {@code GEditableMesh2}
     * @param baseSetting {@code DrawEntity_EditableMesh$a}
     * @param drawSetting {@code DrawEntity_EditableMesh$c}
     */
    public static void recordOverlayUpdate(
            final Object entity, final Object mesh, final Object baseSetting, final Object drawSetting) {
        if (entity == null || mesh == null || drawSetting == null) {
            return;
        }
        try {
            final EntityRenderState state =
                    ENTITY_RENDER_STATE.computeIfAbsent(entity, ignored -> new EntityRenderState());
            final MeshAccess ma = MESH_ACCESS.get(mesh.getClass());
            final List<?> points = (List<?>) ma.getPoints.invoke(mesh);
            final List<?> edges = (List<?>) ma.getEdges.invoke(mesh);
            final float[] isolated = extractPointPositions(mesh, false);
            state.overlayMesh = new WeakReference<>(mesh);
            state.overlayPosVer = (int) ma.posVersion.invoke(mesh);
            state.overlayEdgeVer = (int) ma.edgeVersion.invoke(mesh);
            state.overlayPointCount = points.size();
            state.overlayEdgeCount = edges.size();
            state.overlayPointDigest = samplePointDigest(points);
            state.overlayEdgeDigest = sampleEdgeDigest(edges);
            state.overlayBaseDigest = snapshotDigest(baseSetting);
            state.overlayDrawDigest = snapshotDigest(drawSetting);
            state.overlayIsolatedEmpty = isolated != null && isolated.length == 0;
            state.overlayValid = true;
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
        }
    }

    /**
     * Checks whether selected-point buffers on a {@code DrawEntity_EditableMesh} match the previous
     * rendered frame, keeping disabled selection entity state accurate when empty.
     *
     * @param entity {@code DrawEntity_EditableMesh} instance
     * @param pack action context pack
     * @param mesh current {@code GEditableMesh2}
     * @param selection nullable {@code GEditableMeshSelection}
     * @param drawSetting {@code DrawEntity_EditableMesh$c}
     * @param selectedEntity {@code eArtMeshSelectedPoint} entity
     * @return {@code true} if selected-point buffers and material colors match the last build
     */
    public static boolean isSelectionCacheValid(
            final Object entity,
            final Object pack,
            final Object mesh,
            final Object selection,
            final Object drawSetting,
            final Object selectedEntity) {
        if (entity == null || mesh == null || drawSetting == null) {
            return false;
        }
        final EntityRenderState state = ENTITY_RENDER_STATE.get(entity);
        if (state == null || !state.selectionValid) {
            return false;
        }
        try {
            final MeshAccess ma = MESH_ACCESS.get(mesh.getClass());
            final int posVer = (int) ma.posVersion.invoke(mesh);
            final List<?> points = (List<?>) ma.getPoints.invoke(mesh);
            final long pointDigest = samplePointDigest(points);
            final long drawDigest = snapshotDigest(drawSetting);
            final long selDigest = selectionDigest(selection);
            final Object editMode =
                    pack != null ? PACK_ACCESS.get(pack.getClass()).resolveEditMode(pack) : null;
            final boolean match = state.selectionMesh.get() == mesh
                    && state.selectionPosVer == posVer
                    && state.selectionPointCount == points.size()
                    && state.selectionPointDigest == pointDigest
                    && state.selectionDrawDigest == drawDigest
                    && state.selectionDigest == selDigest
                    && state.selectionEditMode.get() == editMode;
            if (!match) {
                return false;
            }
            if (selectedEntity != null && state.selectionEmpty && state.selectionWasMeshEditor) {
                setEntityEnabled(selectedEntity, false);
            }
            return true;
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return false;
        }
    }

    /**
     * Records a completed selected-point buffer update on a {@code DrawEntity_EditableMesh}.
     *
     * @param entity {@code DrawEntity_EditableMesh} instance
     * @param pack action context pack
     * @param mesh rendered {@code GEditableMesh2}
     * @param selection nullable {@code GEditableMeshSelection}
     * @param drawSetting {@code DrawEntity_EditableMesh$c}
     */
    public static void recordSelectionUpdate(
            final Object entity,
            final Object pack,
            final Object mesh,
            final Object selection,
            final Object drawSetting) {
        if (entity == null || mesh == null || drawSetting == null) {
            return;
        }
        try {
            final EntityRenderState state =
                    ENTITY_RENDER_STATE.computeIfAbsent(entity, ignored -> new EntityRenderState());
            final MeshAccess ma = MESH_ACCESS.get(mesh.getClass());
            final List<?> points = (List<?>) ma.getPoints.invoke(mesh);
            final Object editMode =
                    pack != null ? PACK_ACCESS.get(pack.getClass()).resolveEditMode(pack) : null;
            state.selectionMesh = new WeakReference<>(mesh);
            state.selectionPosVer = (int) ma.posVersion.invoke(mesh);
            state.selectionPointCount = points.size();
            state.selectionPointDigest = samplePointDigest(points);
            state.selectionDrawDigest = snapshotDigest(drawSetting);
            state.selectionDigest = selectionDigest(selection);
            state.selectionEditMode = new WeakReference<>(editMode);
            state.selectionWasMeshEditor = editMode != null
                    && "com.live2d.cubism.doc.modeling.CModelingEditMode_MeshEditor"
                            .equals(editMode.getClass().getName());
            state.selectionEmpty = selectedCountOf(selection) < 1;
            state.selectionValid = true;
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
        }
    }

    /**
     * Clears cached render state when an entity is disabled so the next enabled frame updates.
     *
     * @param entity {@code DrawEntity_EditableMesh} instance
     */
    public static void invalidateRenderState(final Object entity) {
        if (entity == null) {
            return;
        }
        final EntityRenderState state = ENTITY_RENDER_STATE.get(entity);
        if (state != null) {
            state.overlayValid = false;
            state.selectionValid = false;
        }
    }

    private static BitSet markVertex(final boolean[] dense, final BitSet sparse, final int limit, final int index) {
        if (index >= 0 && index < limit) {
            dense[index] = true;
            return sparse;
        }
        final BitSet resolved = sparse != null ? sparse : new BitSet();
        if (index >= 0) {
            resolved.set(index);
        }
        return resolved;
    }

    private static boolean isMarked(final boolean[] dense, final BitSet sparse, final int limit, final int index) {
        if (index >= 0 && index < limit) {
            return dense[index];
        }
        return index >= 0 && sparse != null && sparse.get(index);
    }

    private static void setEntityEnabled(final Object entity, final boolean enabled) throws Throwable {
        final MethodHandle setter = ENTITY_SET_ENABLED.get(entity.getClass());
        if (setter != null) {
            setter.invoke(entity, enabled);
        }
    }

    private static int selectedCountOf(final Object selection) throws Throwable {
        if (selection == null) {
            return 0;
        }
        final SelectionAccess sa = SELECTION_ACCESS.get(selection.getClass());
        final Object selector = sa.getPointSelector.invoke(selection);
        if (selector == null) {
            return 0;
        }
        return (int) sa.getSelectedCount.invoke(selector);
    }

    private static long samplePointDigest(final List<?> points) throws Throwable {
        final int size = points.size();
        if (size == 0) {
            return 0L;
        }
        final PointAccess pa = POINT_ACCESS.get(points.get(0).getClass());
        long hash = MeshArrayFingerprint.mix(size);
        final int step = Math.max(1, size >>> 5);
        for (int i = 0; i < size; i += step) {
            final Object pt = points.get(i);
            final long xy = (Float.floatToRawIntBits((float) pa.getX.invoke(pt)) & 0xffffffffL)
                    | ((Float.floatToRawIntBits((float) pa.getY.invoke(pt)) & 0xffffffffL) << Integer.SIZE);
            hash = MeshArrayFingerprint.mix(hash ^ xy ^ ((long) (int) pa.getIndex.invoke(pt) << PACK_MIX_SHIFT));
        }
        final Object last = points.get(size - 1);
        final long lastXy = (Float.floatToRawIntBits((float) pa.getX.invoke(last)) & 0xffffffffL)
                | ((Float.floatToRawIntBits((float) pa.getY.invoke(last)) & 0xffffffffL) << Integer.SIZE);
        return MeshArrayFingerprint.mix(hash ^ lastXy);
    }

    private static long sampleEdgeDigest(final List<?> edges) throws Throwable {
        final int size = edges.size();
        if (size == 0) {
            return 0L;
        }
        final EdgeAccess ea = EDGE_ACCESS.get(edges.get(0).getClass());
        long hash = MeshArrayFingerprint.mix(size);
        final int step = Math.max(1, size >>> 5);
        for (int i = 0; i < size; i += step) {
            final Object e = edges.get(i);
            final int i1 = (int) ea.getIndex1.invoke(e);
            final int i2 = (int) ea.getIndex2.invoke(e);
            final int typeOrd = System.identityHashCode(ea.getType.invoke(e));
            hash = MeshArrayFingerprint.mix(hash ^ (((long) i1) << Integer.SIZE) ^ (i2 & 0xffffffffL) ^ typeOrd);
        }
        return hash;
    }

    private static long snapshotDigest(final Object settings) throws Throwable {
        if (settings == null) {
            return 0L;
        }
        return FIELD_SNAPSHOTTER.get(settings.getClass()).digest(settings);
    }

    private static long selectionDigest(final Object selection) throws Throwable {
        if (selection == null) {
            return 0L;
        }
        final SelectionAccess sa = SELECTION_ACCESS.get(selection.getClass());
        final Object selector = sa.getPointSelector.invoke(selection);
        if (selector == null) {
            return 1L;
        }
        final List<?> entries = (List<?>) sa.getSelectedWithWeight.invoke(selector);
        final int count = entries.size();
        if (count == 0) {
            return 2L;
        }
        long hash = MeshArrayFingerprint.mix(count);
        for (int i = 0; i < count; i++) {
            final Object entry = entries.get(i);
            final Object ref = sa.resolveRef(entry);
            final float weight = sa.resolveWeight(entry);
            final int idx = sa.resolveRefIndex(ref);
            hash = MeshArrayFingerprint.mix(
                    hash ^ (((long) idx) << Integer.SIZE) ^ (Float.floatToRawIntBits(weight) & 0xffffffffL));
        }
        return hash;
    }

    private static final class MeshPointCache {

        final int posVer;
        final int edgeVer;
        final int pointCount;
        final int edgeCount;
        final long coordDigest;
        final long edgeDigest;
        final float[] connectedPositions;
        final float[] isolatedPositions;

        MeshPointCache(
                final int posVer,
                final int edgeVer,
                final int pointCount,
                final int edgeCount,
                final long coordDigest,
                final long edgeDigest,
                final float[] connectedPositions,
                final float[] isolatedPositions) {
            this.posVer = posVer;
            this.edgeVer = edgeVer;
            this.pointCount = pointCount;
            this.edgeCount = edgeCount;
            this.coordDigest = coordDigest;
            this.edgeDigest = edgeDigest;
            this.connectedPositions = connectedPositions;
            this.isolatedPositions = isolatedPositions;
        }
    }

    private static final class EntityRenderState {

        boolean overlayValid;
        boolean overlayIsolatedEmpty;
        WeakReference<Object> overlayMesh = new WeakReference<>(null);
        int overlayPosVer;
        int overlayEdgeVer;
        int overlayPointCount;
        int overlayEdgeCount;
        long overlayPointDigest;
        long overlayEdgeDigest;
        long overlayBaseDigest;
        long overlayDrawDigest;

        boolean selectionValid;
        boolean selectionEmpty;
        boolean selectionWasMeshEditor;
        WeakReference<Object> selectionMesh = new WeakReference<>(null);
        WeakReference<Object> selectionEditMode = new WeakReference<>(null);
        int selectionPosVer;
        int selectionPointCount;
        long selectionPointDigest;
        long selectionDrawDigest;
        long selectionDigest;
    }

    private static final class MeshAccess {

        final MethodHandle getPoints;
        final MethodHandle getEdges;
        final MethodHandle posVersion;
        final MethodHandle edgeVersion;

        private MeshAccess(
                final MethodHandle getPoints,
                final MethodHandle getEdges,
                final MethodHandle posVersion,
                final MethodHandle edgeVersion) {
            this.getPoints = getPoints;
            this.getEdges = getEdges;
            this.posVersion = posVersion;
            this.edgeVersion = edgeVersion;
        }

        static MeshAccess resolve(final Class<?> type) {
            try {
                final MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                return new MeshAccess(
                        lookup.findVirtual(type, "getPoints", MethodType.methodType(List.class)),
                        lookup.findVirtual(type, "getEdges", MethodType.methodType(List.class)),
                        lookup.findVirtual(type, "get_postion_edit_version", MethodType.methodType(int.class)),
                        lookup.findVirtual(type, "get_edge_edit_version", MethodType.methodType(int.class)));
            } catch (final ReflectiveOperationException failure) {
                throw new IllegalStateException("Failed to bind editable mesh accessors for " + type, failure);
            }
        }
    }

    private static final class PointAccess {

        final MethodHandle getIndex;
        final MethodHandle getX;
        final MethodHandle getY;

        private PointAccess(final MethodHandle getIndex, final MethodHandle getX, final MethodHandle getY) {
            this.getIndex = getIndex;
            this.getX = getX;
            this.getY = getY;
        }

        static PointAccess resolve(final Class<?> type) {
            try {
                final MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                return new PointAccess(
                        lookup.findVirtual(type, "getIndex", MethodType.methodType(int.class)),
                        lookup.findVirtual(type, "getX", MethodType.methodType(float.class)),
                        lookup.findVirtual(type, "getY", MethodType.methodType(float.class)));
            } catch (final ReflectiveOperationException failure) {
                throw new IllegalStateException("Failed to bind editable mesh point accessors for " + type, failure);
            }
        }
    }

    private static final class EdgeAccess {

        final MethodHandle getIndex1;
        final MethodHandle getIndex2;
        final MethodHandle getType;

        private EdgeAccess(final MethodHandle getIndex1, final MethodHandle getIndex2, final MethodHandle getType) {
            this.getIndex1 = getIndex1;
            this.getIndex2 = getIndex2;
            this.getType = getType;
        }

        static EdgeAccess resolve(final Class<?> type) {
            try {
                final MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                final Class<?> edgeTypeClass = Class.forName(
                        "com.live2d.graphics3d.editableMesh.MEdge$EdgeType", false, type.getClassLoader());
                return new EdgeAccess(
                        lookup.findVirtual(type, "getIndex1", MethodType.methodType(int.class)),
                        lookup.findVirtual(type, "getIndex2", MethodType.methodType(int.class)),
                        lookup.findVirtual(type, "getType", MethodType.methodType(edgeTypeClass)));
            } catch (final ReflectiveOperationException failure) {
                throw new IllegalStateException("Failed to bind editable mesh edge accessors for " + type, failure);
            }
        }
    }

    private static final class FieldSnapshotter {

        private final Field[] fields;

        private FieldSnapshotter(final Field[] fields) {
            this.fields = fields;
        }

        static FieldSnapshotter resolve(final Class<?> type) {
            final Field[] declared = type.getDeclaredFields();
            for (final Field field : declared) {
                field.setAccessible(true);
            }
            return new FieldSnapshotter(declared);
        }

        long digest(final Object instance) throws IllegalAccessException {
            long hash = MeshArrayFingerprint.mix(fields.length);
            for (final Field field : fields) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                final Class<?> ft = field.getType();
                if (ft == int.class) {
                    hash = MeshArrayFingerprint.mix(hash ^ field.getInt(instance));
                } else if (ft == float.class) {
                    hash = MeshArrayFingerprint.mix(
                            hash ^ (Float.floatToRawIntBits(field.getFloat(instance)) & 0xffffffffL));
                } else if (ft == boolean.class) {
                    hash = MeshArrayFingerprint.mix(hash ^ (field.getBoolean(instance) ? 1L : 0L));
                } else {
                    final Object val = field.get(instance);
                    if (val != null
                            && "com.live2d.type.CColor".equals(val.getClass().getName())) {
                        hash = MeshArrayFingerprint.mix(
                                hash ^ COLOR_ACCESS.get(val.getClass()).rgbaDigest(val));
                    } else {
                        hash = MeshArrayFingerprint.mix(hash ^ System.identityHashCode(val));
                    }
                }
            }
            return hash;
        }
    }

    private static final class ColorAccess {

        final MethodHandle getR;
        final MethodHandle getG;
        final MethodHandle getB;
        final MethodHandle getA;

        private ColorAccess(
                final MethodHandle getR, final MethodHandle getG, final MethodHandle getB, final MethodHandle getA) {
            this.getR = getR;
            this.getG = getG;
            this.getB = getB;
            this.getA = getA;
        }

        static ColorAccess resolve(final Class<?> type) {
            try {
                final MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                return new ColorAccess(
                        lookup.findVirtual(type, "getRed", MethodType.methodType(float.class)),
                        lookup.findVirtual(type, "getGreen", MethodType.methodType(float.class)),
                        lookup.findVirtual(type, "getBlue", MethodType.methodType(float.class)),
                        lookup.findVirtual(type, "getAlpha", MethodType.methodType(float.class)));
            } catch (final ReflectiveOperationException failure) {
                throw new IllegalStateException("Failed to bind CColor accessors for " + type, failure);
            }
        }

        long rgbaDigest(final Object color) {
            try {
                final long rg = (Float.floatToRawIntBits((float) getR.invoke(color)) & 0xffffffffL)
                        | ((Float.floatToRawIntBits((float) getG.invoke(color)) & 0xffffffffL) << Integer.SIZE);
                final long ba = (Float.floatToRawIntBits((float) getB.invoke(color)) & 0xffffffffL)
                        | ((Float.floatToRawIntBits((float) getA.invoke(color)) & 0xffffffffL) << Integer.SIZE);
                return MeshArrayFingerprint.mix(rg) ^ MeshArrayFingerprint.mix(ba);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                return System.identityHashCode(color);
            }
        }
    }

    private static final class SelectionAccess {

        final MethodHandle getPointSelector;
        final MethodHandle getSelectedCount;
        final MethodHandle getSelectedWithWeight;
        volatile MethodHandle entryGetRef;
        volatile MethodHandle entryGetWeight;
        volatile MethodHandle refGetIndexEditable;
        volatile MethodHandle refGetIndexAction;

        private SelectionAccess(
                final MethodHandle getPointSelector,
                final MethodHandle getSelectedCount,
                final MethodHandle getSelectedWithWeight) {
            this.getPointSelector = getPointSelector;
            this.getSelectedCount = getSelectedCount;
            this.getSelectedWithWeight = getSelectedWithWeight;
        }

        static SelectionAccess resolve(final Class<?> type) {
            try {
                final MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                final Class<?> psClass =
                        Class.forName("com.live2d.doc.selection.PointSelector", false, type.getClassLoader());
                return new SelectionAccess(
                        lookup.findVirtual(type, "getPointSelector", MethodType.methodType(psClass)),
                        lookup.findVirtual(psClass, "getSelectedCount", MethodType.methodType(int.class)),
                        lookup.findVirtual(psClass, "getSelectedPointWithWeight", MethodType.methodType(List.class)));
            } catch (final ReflectiveOperationException failure) {
                throw new IllegalStateException("Failed to bind selection accessors for " + type, failure);
            }
        }

        Object resolveRef(final Object weightEntry) throws Throwable {
            MethodHandle mh = entryGetRef;
            if (mh == null) {
                final Class<?> ipr = Class.forName(
                        "com.live2d.doc.selection.IPointRef",
                        false,
                        weightEntry.getClass().getClassLoader());
                mh = MethodHandles.publicLookup().findVirtual(weightEntry.getClass(), "a", MethodType.methodType(ipr));
                entryGetRef = mh;
            }
            return mh.invoke(weightEntry);
        }

        float resolveWeight(final Object weightEntry) throws Throwable {
            MethodHandle mh = entryGetWeight;
            if (mh == null) {
                mh = MethodHandles.publicLookup()
                        .findVirtual(weightEntry.getClass(), "b", MethodType.methodType(float.class));
                entryGetWeight = mh;
            }
            return (float) mh.invoke(weightEntry);
        }

        int resolveRefIndex(final Object ref) throws Throwable {
            if (ref == null) {
                return -1;
            }
            final String name = ref.getClass().getName();
            if ("com.live2d.graphics3d.editableMesh.a".equals(name)) {
                MethodHandle mh = refGetIndexEditable;
                if (mh == null) {
                    mh = MethodHandles.publicLookup()
                            .findVirtual(ref.getClass(), "b", MethodType.methodType(int.class));
                    refGetIndexEditable = mh;
                }
                return (int) mh.invoke(ref);
            }
            if ("com.live2d.cubism.view.context.actionManager.Z$b".equals(name)) {
                MethodHandle mh = refGetIndexAction;
                if (mh == null) {
                    mh = MethodHandles.publicLookup()
                            .findVirtual(ref.getClass(), "b", MethodType.methodType(int.class));
                    refGetIndexAction = mh;
                }
                return (int) mh.invoke(ref);
            }
            return -1;
        }
    }

    private static final class PackAccess {

        private final MethodHandle resolveMode;

        private PackAccess(final MethodHandle resolveMode) {
            this.resolveMode = resolveMode;
        }

        static PackAccess resolve(final Class<?> type) {
            try {
                final Class<?> editModeInterface =
                        Class.forName("com.live2d.doc.IEditMode", false, type.getClassLoader());
                for (final Method method : type.getMethods()) {
                    if (method.getParameterCount() == 0 && method.getReturnType() == editModeInterface) {
                        return new PackAccess(MethodHandles.publicLookup().unreflect(method));
                    }
                }
                return new PackAccess(null);
            } catch (final ReflectiveOperationException failure) {
                return new PackAccess(null);
            }
        }

        Object resolveEditMode(final Object pack) throws Throwable {
            return resolveMode == null ? null : resolveMode.invoke(pack);
        }
    }
}
