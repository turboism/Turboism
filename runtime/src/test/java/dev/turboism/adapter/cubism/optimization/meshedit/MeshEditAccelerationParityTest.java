package dev.turboism.adapter.cubism.optimization.meshedit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Unit and fail-closed verification tests for mesh-edit geometry, grid, and transformer bridges. */
final class MeshEditAccelerationParityTest {

    private static final int GRID_COLS = 6;
    private static final int GRID_ROWS = 6;
    private static final int STRIDE_XY = 2;
    private static final int STRIDE_XYZW = 4;
    private static final int TRI_STRIDE = 3;
    private static final int OUTSIDE_OFFSET = 100;
    private static final int QUERY_GRID_SAMPLES = 9;
    private static final float CELL_SCALE = 1.0f;
    private static final float DELTA_TOLERANCE = 1.0e-5f;
    private static final double DOUBLE_TOLERANCE = 1.0e-5;
    private static final float SHIFT_X = 2.0f;
    private static final float SHIFT_Y = -3.0f;

    private static final String GVEC2_INTERNAL = "com/live2d/graphics3d/type/GVector2";
    private static final String PITD_INTERNAL = "com/live2d/cubism/doc/model/drawable/artMesh/PointInTriangleD";
    private static final String PIT_INTERNAL = "com/live2d/cubism/doc/model/drawable/artMesh/PointInTriangle";

    @Test
    void pointInTriangleGridMatchesBruteForceAndHandlesBoundariesAndOutOfBounds() throws Exception {
        final SyntheticHostLoader host = new SyntheticHostLoader(getClass().getClassLoader());
        final Class<?> vecClass = host.defineVector2();
        final Class<?> pitdClass = host.definePointInTriangle(PITD_INTERNAL, "D");
        final Class<?> pitClass = host.definePointInTriangle(PIT_INTERNAL, "F");
        final Constructor<?> vecCtor = vecClass.getConstructor(float.class, float.class);

        final MeshFixture fixture = buildGridMesh(GRID_COLS, GRID_ROWS, STRIDE_XY);
        assertTrue(fixture.indices.length / TRI_STRIDE >= 24);

        final int[] outTri = new int[TRI_STRIDE];
        for (int ix = 0; ix < GRID_COLS - 1; ix++) {
            for (int iy = 0; iy < GRID_ROWS - 1; iy++) {
                final float qx = ix + 0.35f;
                final float qy = iy + 0.65f;
                final Object vec = vecCtor.newInstance(qx, qy);

                final Object pitd =
                        PointInTriangleAccelerator.findDouble(vec, fixture.srcPos, fixture.indices, STRIDE_XY, true);
                assertNotNull(pitd);
                assertFalse(pitd == PointInTriangleAccelerator.NULL_SENTINEL);
                assertSame(pitdClass, pitd.getClass());
                assertBarycentricReconstructs(fixture.srcPos, STRIDE_XY, pitd, qx, qy, true);

                final Object pit =
                        PointInTriangleAccelerator.findFloat(vec, fixture.srcPos, fixture.indices, STRIDE_XY, true);
                assertNotNull(pit);
                assertFalse(pit == PointInTriangleAccelerator.NULL_SENTINEL);
                assertSame(pitClass, pit.getClass());
                assertBarycentricReconstructs(fixture.srcPos, STRIDE_XY, pit, qx, qy, false);

                final int strictStatus = PointInTriangleAccelerator.findStrictTriangle(
                        fixture.srcPos, fixture.indices, STRIDE_XY, vec, outTri);
                assertEquals(1, strictStatus);
            }
        }

        final Object outsideVec = vecCtor.newInstance(OUTSIDE_OFFSET, OUTSIDE_OFFSET);
        assertSame(
                PointInTriangleAccelerator.NULL_SENTINEL,
                PointInTriangleAccelerator.findDouble(outsideVec, fixture.srcPos, fixture.indices, STRIDE_XY, true));
        final Object nearest =
                PointInTriangleAccelerator.findDouble(outsideVec, fixture.srcPos, fixture.indices, STRIDE_XY, false);
        assertNotNull(nearest);
        assertFalse(nearest == PointInTriangleAccelerator.NULL_SENTINEL);
        assertEquals(
                0,
                PointInTriangleAccelerator.findStrictTriangle(
                        fixture.srcPos, fixture.indices, STRIDE_XY, outsideVec, outTri));

        final Object vertexVec = vecCtor.newInstance(0.0f, 0.0f);
        assertEquals(
                0,
                PointInTriangleAccelerator.findStrictTriangle(
                        fixture.srcPos, fixture.indices, STRIDE_XY, vertexVec, outTri));

        assertEquals(
                -1,
                PointInTriangleAccelerator.findStrictTriangle(fixture.srcPos, fixture.indices, 1, vertexVec, outTri));
        assertNull(PointInTriangleAccelerator.findDouble(null, fixture.srcPos, fixture.indices, STRIDE_XY, true));
    }

    @Test
    void tryTransformBatchPreservesAffineAndGridMappingsForStridedCoordinates() {
        final MeshFixture fixture = buildGridMesh(GRID_COLS, GRID_ROWS, STRIDE_XYZW);
        final float[] inputs = new float[] {0.25f, 0.25f, 2.7f, 1.3f, 4.1f, 3.8f};
        final float[] outputs = new float[inputs.length];

        final float[] returned = PointInTriangleAccelerator.tryTransformBatch(
                fixture.srcPos, fixture.dstPos, fixture.indices, STRIDE_XYZW, inputs, outputs);
        assertSame(outputs, returned);
        for (int i = 0; i < inputs.length; i += STRIDE_XY) {
            assertEquals(inputs[i] + SHIFT_X, outputs[i], DELTA_TOLERANCE);
            assertEquals(inputs[i + 1] + SHIFT_Y, outputs[i + 1], DELTA_TOLERANCE);
        }

        assertNull(PointInTriangleAccelerator.tryTransformBatch(
                fixture.srcPos, fixture.dstPos, fixture.indices, STRIDE_XYZW, new float[3], new float[3]));
    }

    @Test
    void topologyPrimitivesPreserveEmptyContractsAndGridBounds() {
        assertNull(EditableMeshTopologyOptimizer.buildIncidentEdgeMatrix(null, List.of()));
        assertEquals(0, EditableMeshTopologyOptimizer.buildIncidentEdgeMatrix(List.of(), List.of()).length);
        final long[] emptyEdgeTable = EditableMeshTopologyOptimizer.buildUndirectedEdgeTable(List.of());
        assertTrue(emptyEdgeTable.length > 0);
        assertFalse(EditableMeshTopologyOptimizer.containsUndirectedEdge(emptyEdgeTable, 0, 1));
        final int[] dims = MeshArrayFingerprint.gridDimensions(4.0, 2.0, 24);
        assertTrue(dims[0] >= 4 && dims[1] >= 4);
    }

    @Test
    void transformerIsFailClosedOnUnrelatedAndMalformedClasses() {
        final MeshEditAccelerationTransformer transformer = new MeshEditAccelerationTransformer();
        assertNull(transformer.transform(
                getClass().getClassLoader(), "com/example/UnrelatedClass", null, null, new byte[] {1, 2, 3}));
        assertEquals(MeshEditAccelerationTransformer.Outcome.NOT_APPLICABLE, transformer.outcome());

        final ClassWriter malformed = new ClassWriter(0);
        malformed.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                MeshEditGeometryPatcher.MESH_UTILS_INTERNAL,
                null,
                "java/lang/Object",
                null);
        malformed.visitEnd();
        final byte[] malformedBytes = malformed.toByteArray();

        assertNull(transformer.transform(
                getClass().getClassLoader(), MeshEditGeometryPatcher.MESH_UTILS_INTERNAL, null, null, malformedBytes));
        assertEquals(
                MeshEditAccelerationTransformer.Outcome.SHAPE_REJECTED,
                transformer.outcomeFor(MeshEditGeometryPatcher.MESH_UTILS_INTERNAL));
        assertFalse(transformer
                .diagnosticFor(MeshEditGeometryPatcher.MESH_UTILS_INTERNAL)
                .isEmpty());
    }

    @Test
    void singlePointQueriesDeferGridBuildUntilRepeatedWhileBatchBuildsEagerly() throws Throwable {
        final SyntheticHostLoader host = new SyntheticHostLoader(MeshEditAccelerationParityTest.class.getClassLoader());
        final Class<?> vecCls = host.defineVector2();
        final Constructor<?> vecCtor = vecCls.getConstructor(float.class, float.class);
        final Class<?> pitdCls = host.definePointInTriangle(PITD_INTERNAL, "D");
        final Class<?> pitCls = host.definePointInTriangle(PIT_INTERNAL, "F");
        assertNotNull(pitdCls);
        assertNotNull(pitCls);

        final MeshFixture strictFixture = buildGridMesh(GRID_COLS, GRID_ROWS, STRIDE_XY);
        final Object queryInside = vecCtor.newInstance(2.25f, 2.75f);
        final Object queryOutside = vecCtor.newInstance(OUTSIDE_OFFSET + 2.25f, OUTSIDE_OFFSET + 2.75f);
        final int[] outTri = new int[TRI_STRIDE];
        assertFalse(PointInTriangleAccelerator.hasCachedGridForCurrentThread(
                strictFixture.srcPos(), strictFixture.indices(), STRIDE_XY));
        assertEquals(
                1,
                PointInTriangleAccelerator.findStrictTriangle(
                        strictFixture.srcPos(), strictFixture.indices(), STRIDE_XY, queryInside, outTri));
        assertFalse(PointInTriangleAccelerator.hasCachedGridForCurrentThread(
                strictFixture.srcPos(), strictFixture.indices(), STRIDE_XY));
        assertEquals(
                0,
                PointInTriangleAccelerator.findStrictTriangle(
                        strictFixture.srcPos(), strictFixture.indices(), STRIDE_XY, queryOutside, outTri));
        assertFalse(PointInTriangleAccelerator.hasCachedGridForCurrentThread(
                strictFixture.srcPos(), strictFixture.indices(), STRIDE_XY));
        assertEquals(
                1,
                PointInTriangleAccelerator.findStrictTriangle(
                        strictFixture.srcPos(), strictFixture.indices(), STRIDE_XY, queryInside, outTri));
        assertTrue(PointInTriangleAccelerator.hasCachedGridForCurrentThread(
                strictFixture.srcPos(), strictFixture.indices(), STRIDE_XY));

        final MeshFixture floatFixture = buildGridMesh(GRID_COLS, GRID_ROWS, STRIDE_XY);
        final Object float1 = PointInTriangleAccelerator.findFloat(
                queryInside, floatFixture.srcPos(), floatFixture.indices(), STRIDE_XY, false);
        assertNotNull(float1);
        assertFalse(PointInTriangleAccelerator.hasCachedGridForCurrentThread(
                floatFixture.srcPos(), floatFixture.indices(), STRIDE_XY));
        assertSame(
                PointInTriangleAccelerator.NULL_SENTINEL,
                PointInTriangleAccelerator.findFloat(
                        queryOutside, floatFixture.srcPos(), floatFixture.indices(), STRIDE_XY, true));
        assertFalse(PointInTriangleAccelerator.hasCachedGridForCurrentThread(
                floatFixture.srcPos(), floatFixture.indices(), STRIDE_XY));
        final Object float3 = PointInTriangleAccelerator.findFloat(
                queryInside, floatFixture.srcPos(), floatFixture.indices(), STRIDE_XY, false);
        assertNotNull(float3);
        assertTrue(PointInTriangleAccelerator.hasCachedGridForCurrentThread(
                floatFixture.srcPos(), floatFixture.indices(), STRIDE_XY));
        assertBarycentricReconstructs(floatFixture.srcPos(), STRIDE_XY, float3, 2.25f, 2.75f, false);

        final MeshFixture doubleFixture = buildGridMesh(GRID_COLS, GRID_ROWS, STRIDE_XY);
        final Object dbl1 = PointInTriangleAccelerator.findDouble(
                queryInside, doubleFixture.srcPos(), doubleFixture.indices(), STRIDE_XY, false);
        assertNotNull(dbl1);
        assertFalse(PointInTriangleAccelerator.hasCachedGridForCurrentThread(
                doubleFixture.srcPos(), doubleFixture.indices(), STRIDE_XY));
        assertNotNull(PointInTriangleAccelerator.findDouble(
                queryInside, doubleFixture.srcPos(), doubleFixture.indices(), STRIDE_XY, false));
        assertFalse(PointInTriangleAccelerator.hasCachedGridForCurrentThread(
                doubleFixture.srcPos(), doubleFixture.indices(), STRIDE_XY));
        final Object dbl3 = PointInTriangleAccelerator.findDouble(
                queryInside, doubleFixture.srcPos(), doubleFixture.indices(), STRIDE_XY, false);
        assertNotNull(dbl3);
        assertTrue(PointInTriangleAccelerator.hasCachedGridForCurrentThread(
                doubleFixture.srcPos(), doubleFixture.indices(), STRIDE_XY));
        assertBarycentricReconstructs(doubleFixture.srcPos(), STRIDE_XY, dbl3, 2.25f, 2.75f, true);

        final MeshFixture batchFixture = buildGridMesh(GRID_COLS, GRID_ROWS, STRIDE_XY);
        final float[] batchOut = new float[2];
        assertNotNull(PointInTriangleAccelerator.tryTransformBatch(
                batchFixture.srcPos(),
                batchFixture.dstPos(),
                batchFixture.indices(),
                STRIDE_XY,
                new float[] {2.25f, 2.75f},
                batchOut));
        assertTrue(PointInTriangleAccelerator.hasCachedGridForCurrentThread(
                batchFixture.srcPos(), batchFixture.indices(), STRIDE_XY));
    }

    private static void assertBarycentricReconstructs(
            final float[] pos,
            final int step,
            final Object result,
            final float expectedX,
            final float expectedY,
            final boolean isDouble)
            throws Exception {
        final Class<?> cls = result.getClass();
        final int i0 = readIntField(cls, result, "ptIndex1");
        final int i1 = readIntField(cls, result, "ptIndex2");
        final int i2 = readIntField(cls, result, "ptIndex3");
        final double w0 = isDouble ? readDoubleField(cls, result, "weight1") : readFloatField(cls, result, "weight1");
        final double w1 = isDouble ? readDoubleField(cls, result, "weight2") : readFloatField(cls, result, "weight2");
        final double w2 = isDouble ? readDoubleField(cls, result, "weight3") : readFloatField(cls, result, "weight3");
        final double rx = w0 * pos[i0 * step] + w1 * pos[i1 * step] + w2 * pos[i2 * step];
        final double ry = w0 * pos[i0 * step + 1] + w1 * pos[i1 * step + 1] + w2 * pos[i2 * step + 1];
        assertEquals(expectedX, rx, DOUBLE_TOLERANCE);
        assertEquals(expectedY, ry, DOUBLE_TOLERANCE);
    }

    private static int readIntField(final Class<?> cls, final Object target, final String name)
            throws ReflectiveOperationException {
        final Field f = cls.getDeclaredField(name);
        f.setAccessible(true);
        return f.getInt(target);
    }

    private static float readFloatField(final Class<?> cls, final Object target, final String name)
            throws ReflectiveOperationException {
        final Field f = cls.getDeclaredField(name);
        f.setAccessible(true);
        return f.getFloat(target);
    }

    private static double readDoubleField(final Class<?> cls, final Object target, final String name)
            throws ReflectiveOperationException {
        final Field f = cls.getDeclaredField(name);
        f.setAccessible(true);
        return f.getDouble(target);
    }

    private static MeshFixture buildGridMesh(final int cols, final int rows, final int stride) {
        final int vertexCount = cols * rows;
        final float[] src = new float[vertexCount * stride];
        final float[] dst = new float[vertexCount * stride];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                final int off = (r * cols + c) * stride;
                src[off] = c * CELL_SCALE;
                src[off + 1] = r * CELL_SCALE;
                dst[off] = src[off] + SHIFT_X;
                dst[off + 1] = src[off + 1] + SHIFT_Y;
            }
        }
        final int quads = (cols - 1) * (rows - 1);
        final int[] indices = new int[quads * 2 * TRI_STRIDE];
        int p = 0;
        for (int r = 0; r < rows - 1; r++) {
            for (int c = 0; c < cols - 1; c++) {
                final int v00 = r * cols + c;
                final int v10 = v00 + 1;
                final int v01 = v00 + cols;
                final int v11 = v01 + 1;
                indices[p++] = v00;
                indices[p++] = v10;
                indices[p++] = v11;
                indices[p++] = v00;
                indices[p++] = v11;
                indices[p++] = v01;
            }
        }
        return new MeshFixture(src, dst, indices);
    }

    private record MeshFixture(float[] srcPos, float[] dstPos, int[] indices) {}

    private static final class SyntheticHostLoader extends ClassLoader {

        SyntheticHostLoader(final ClassLoader parent) {
            super(parent);
        }

        Class<?> defineVector2() {
            final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(
                    Opcodes.V17,
                    Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                    GVEC2_INTERNAL,
                    null,
                    "java/lang/Object",
                    null);
            cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "x", "F", null, null)
                    .visitEnd();
            cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "y", "F", null, null)
                    .visitEnd();

            final MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(FF)V", null, null);
            init.visitCode();
            init.visitVarInsn(Opcodes.ALOAD, 0);
            init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            init.visitVarInsn(Opcodes.ALOAD, 0);
            init.visitVarInsn(Opcodes.FLOAD, 1);
            init.visitFieldInsn(Opcodes.PUTFIELD, GVEC2_INTERNAL, "x", "F");
            init.visitVarInsn(Opcodes.ALOAD, 0);
            init.visitVarInsn(Opcodes.FLOAD, 2);
            init.visitFieldInsn(Opcodes.PUTFIELD, GVEC2_INTERNAL, "y", "F");
            init.visitInsn(Opcodes.RETURN);
            init.visitMaxs(0, 0);
            init.visitEnd();

            emitGetter(cw, "getX", "x");
            emitGetter(cw, "getY", "y");
            cw.visitEnd();
            final byte[] bytes = cw.toByteArray();
            return defineClass(GVEC2_INTERNAL.replace('/', '.'), bytes, 0, bytes.length);
        }

        Class<?> definePointInTriangle(final String internalName, final String weightDesc) {
            final String desc = "(I" + weightDesc + "I" + weightDesc + "I" + weightDesc + ")V";
            final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, internalName, null, "java/lang/Object", null);
            for (int k = 1; k <= TRI_STRIDE; k++) {
                cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "ptIndex" + k, "I", null, null)
                        .visitEnd();
                cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "weight" + k, weightDesc, null, null)
                        .visitEnd();
            }
            final MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", desc, null, null);
            init.visitCode();
            init.visitVarInsn(Opcodes.ALOAD, 0);
            init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            int slot = 1;
            final int loadOp = "D".equals(weightDesc) ? Opcodes.DLOAD : Opcodes.FLOAD;
            final int slotStep = "D".equals(weightDesc) ? 2 : 1;
            for (int k = 1; k <= TRI_STRIDE; k++) {
                init.visitVarInsn(Opcodes.ALOAD, 0);
                init.visitVarInsn(Opcodes.ILOAD, slot++);
                init.visitFieldInsn(Opcodes.PUTFIELD, internalName, "ptIndex" + k, "I");
                init.visitVarInsn(Opcodes.ALOAD, 0);
                init.visitVarInsn(loadOp, slot);
                init.visitFieldInsn(Opcodes.PUTFIELD, internalName, "weight" + k, weightDesc);
                slot += slotStep;
            }
            init.visitInsn(Opcodes.RETURN);
            init.visitMaxs(0, 0);
            init.visitEnd();
            cw.visitEnd();
            final byte[] bytes = cw.toByteArray();
            return defineClass(internalName.replace('/', '.'), bytes, 0, bytes.length);
        }

        private static void emitGetter(final ClassWriter cw, final String methodName, final String fieldName) {
            final MethodVisitor mv =
                    cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, methodName, "()F", null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, GVEC2_INTERNAL, fieldName, "F");
            mv.visitInsn(Opcodes.FRETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
    }
}
