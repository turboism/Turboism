package dev.turboism.adapter.cubism.meshedit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Verifies bytecode patching, idempotency, strict {@code -Xverify:all} linkage, and differential
 * behavioral parity across all 21 official Cubism Editor JARs ({@code 5.0.00} through {@code 5.3.05}).
 */
final class MeshEditRealHostPatchVerificationTest {

    private static final String ARTIFACTS_DIR_NAME = "cubism-host-artifacts";
    private static final String JAR_NAME = "Live2D_Cubism.jar";
    private static final String JAR_SUFFIX = ".jar";
    private static final String JAR_5305_REL =
            ".local/share/cubism-reference/5.3.05/proton/pfx/drive_c/Program Files/Live2D Cubism 5.3/app/lib/"
                    + JAR_NAME;
    private static final String CLASS_SUFFIX = ".class";
    private static final String STUB_PACKAGE_PREFIX = "org.jdom.";
    private static final int EXPECTED_TOTAL_JARS = 21;
    private static final long VERIFY_TIMEOUT_SECONDS = 60L;

    private static final int GRID_SIZE = 7;
    private static final int STRESS_GRID_DIM = 100;
    private static final int STRIDE_XY = 2;
    private static final int TRI_STRIDE = 3;
    private static final int QUERY_STEPS = 8;
    private static final float FLOAT_TOL = 1.0e-5f;
    private static final double DOUBLE_TOL = 1.0e-6;
    private static final float SHIFT_X = 1.5f;
    private static final float SHIFT_Y = -0.75f;
    private static final long TEST_UID_BASE = 1000L;

    private static final List<String> ALL_TARGETS = List.of(
            MeshEditGeometryPatcher.PITD_COMPANION_INTERNAL,
            MeshEditGeometryPatcher.PIT_COMPANION_INTERNAL,
            MeshEditGeometryPatcher.MESH_UTILS_INTERNAL,
            MeshEditGeometryPatcher.MESH_GEN_TRANSFORMER_INTERNAL,
            MeshEditGeometryPatcher.DRAW_EDITABLE_MESH_INTERNAL,
            MeshEditGeometryPatcher.DRAW_ARTMESH_SELECTION_INTERNAL,
            MeshEditTopologyPatcher.TRIANGULATION_G_INTERNAL,
            MeshEditTopologyPatcher.EDITABLE_MESH_HELPER_INTERNAL,
            MeshEditTopologyPatcher.MESH_HANDLER_INTERNAL,
            MeshEditTopologyPatcher.G_EDITABLE_MESH_INTERNAL);

    @Test
    void patchesVerifyAllTargetsAndIsIdempotentAcrossAllTwentyOneCubismJars(@TempDir final Path tempDir)
            throws Exception {
        final List<Path> jars = discoverAllTwentyOneJars();
        Assumptions.assumeTrue(
                jars.size() == EXPECTED_TOTAL_JARS,
                "All 21 Cubism JARs (5.0.00-5.3.05) must exist locally; found " + jars.size());

        final MeshEditAccelerationTransformer transformer = new MeshEditAccelerationTransformer();
        final Path manifestList = tempDir.resolve("patched-meshedit-targets.txt");
        final List<String> manifestLines = new ArrayList<>();

        for (final Path jarPath : jars) {
            final String version = versionLabel(jarPath);
            final Path versionOutDir = tempDir.resolve(version);
            Files.createDirectories(versionOutDir);

            try (JarFile jar = new JarFile(jarPath.toFile(), false);
                    URLClassLoader hostLoader = new URLClassLoader(
                            new URL[] {jarPath.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
                for (final String internalName : ALL_TARGETS) {
                    final JarEntry entry = jar.getJarEntry(internalName + CLASS_SUFFIX);
                    assertNotNull(entry, "Missing " + internalName + " in " + version);
                    final byte[] original;
                    try (InputStream in = jar.getInputStream(entry)) {
                        original = in.readAllBytes();
                    }

                    final byte[] patched = transformer.transform(hostLoader, internalName, null, null, original);
                    assertNotNull(
                            patched,
                            "Expected patched bytes for "
                                    + internalName
                                    + " in "
                                    + version
                                    + " reason="
                                    + transformer.diagnosticFor(internalName));
                    assertEquals(
                            MeshEditAccelerationTransformer.Outcome.PATCHED,
                            transformer.outcomeFor(internalName),
                            internalName + " in " + version);
                    assertNull(
                            transformer.transform(hostLoader, internalName, null, null, patched),
                            "Patch must be idempotent for " + internalName + " in " + version);

                    final Path outFile = versionOutDir.resolve(internalName + CLASS_SUFFIX);
                    Files.createDirectories(outFile.getParent());
                    Files.write(outFile, patched);
                    manifestLines.add(version + "\t" + jarPath + "\t" + versionOutDir + "\t" + internalName);
                }
            }
        }

        Files.write(manifestList, manifestLines);
        runStrictBytecodeVerifierSubprocess(manifestList, manifestLines.size());
    }

    @Test
    void patchedHostClassesMatchUnpatchedOutputsAcrossEarliestAndLatestHosts(@TempDir final Path tempDir)
            throws Exception {
        final List<Path> jars = discoverAllTwentyOneJars();
        Assumptions.assumeTrue(
                jars.size() == EXPECTED_TOTAL_JARS,
                "All 21 Cubism JARs (5.0.00-5.3.05) must exist locally; found " + jars.size());

        final List<Path> sampleJars = List.of(jars.get(0), jars.get(jars.size() - 1));
        for (final Path jarPath : sampleJars) {
            final String version = versionLabel(jarPath);
            final Path patchedDir = tempDir.resolve("patch-" + version);
            final Path rawDir = tempDir.resolve("raw-" + version);
            Files.createDirectories(patchedDir);
            Files.createDirectories(rawDir);

            final MeshEditAccelerationTransformer transformer = new MeshEditAccelerationTransformer();
            try (JarFile jar = new JarFile(jarPath.toFile(), false)) {
                for (final String internalName : ALL_TARGETS) {
                    final byte[] original = jar.getInputStream(jar.getJarEntry(internalName + CLASS_SUFFIX))
                            .readAllBytes();
                    final byte[] patched = transformer.transform(null, internalName, null, null, original);
                    assertNotNull(patched, "Patch failed for " + internalName + " in " + version);
                    final Path out = patchedDir.resolve(internalName + CLASS_SUFFIX);
                    Files.createDirectories(out.getParent());
                    Files.write(out, patched);
                }
            }

            try (PatchedJarLoader rawLoader =
                            new PatchedJarLoader(jarPath, rawDir, getClass().getClassLoader());
                    PatchedJarLoader optLoader =
                            new PatchedJarLoader(jarPath, patchedDir, getClass().getClassLoader())) {
                verifyPointInTriangleParity(rawLoader, optLoader, version);
                verifyEditableMeshTopologyParity(rawLoader, optLoader, version);
                verifyEditableMeshRenderAndSelectionHelpers(optLoader);
            }
        }
    }

    @Test
    void incrementalTenThousandPlusEdgesAvoidFullRebuildsAndMatchParity(@TempDir final Path tempDir) throws Exception {
        final List<Path> jars = discoverAllTwentyOneJars();
        Assumptions.assumeTrue(
                jars.size() == EXPECTED_TOTAL_JARS,
                "All 21 Cubism JARs (5.0.00-5.3.05) must exist locally; found " + jars.size());

        final Path jarPath = jars.get(jars.size() - 1);
        final Path patchedDir = tempDir.resolve("patched-stress");
        final Path rawDir = tempDir.resolve("raw-stress");
        Files.createDirectories(patchedDir);
        Files.createDirectories(rawDir);

        final MeshEditAccelerationTransformer transformer = new MeshEditAccelerationTransformer();
        try (JarFile jar = new JarFile(jarPath.toFile(), false)) {
            for (final String internalName : List.of(
                    MeshEditTopologyPatcher.G_EDITABLE_MESH_INTERNAL,
                    MeshEditTopologyPatcher.EDITABLE_MESH_HELPER_INTERNAL)) {
                final byte[] original = jar.getInputStream(jar.getJarEntry(internalName + CLASS_SUFFIX))
                        .readAllBytes();
                final byte[] patched = transformer.transform(null, internalName, null, null, original);
                assertNotNull(patched);
                final Path out = patchedDir.resolve(internalName + CLASS_SUFFIX);
                Files.createDirectories(out.getParent());
                Files.write(out, patched);
            }
        }

        try (PatchedJarLoader rawLoader =
                        new PatchedJarLoader(jarPath, rawDir, getClass().getClassLoader());
                PatchedJarLoader optLoader =
                        new PatchedJarLoader(jarPath, patchedDir, getClass().getClassLoader())) {
            EditableMeshTopologyOptimizer.resetBuildCountForTest();
            final List<Integer> optChecks = runTenThousandEdgeIncrementalLoop(optLoader, true);
            final int fullBuilds = EditableMeshTopologyOptimizer.fullBuildCountForTest();
            assertTrue(fullBuilds <= 2, "Expected <= 2 full MeshIndex builds across 11k+ edge adds, got " + fullBuilds);

            final List<Integer> rawChecks = runTenThousandEdgeIncrementalLoop(rawLoader, false);
            assertEquals(rawChecks, optChecks, "Incremental edge lookup and promotion parity mismatch");
        }
    }

    private static List<Integer> runTenThousandEdgeIncrementalLoop(final ClassLoader loader, final boolean exerciseAll)
            throws Exception {
        final Class<?> meshCls = Class.forName("com.live2d.graphics3d.editableMesh.GEditableMesh2", true, loader);
        final Class<?> ptType = Class.forName("com.live2d.graphics3d.editableMesh.MPoint2$PointType", true, loader);
        final Class<?> edgeType = Class.forName("com.live2d.graphics3d.editableMesh.MEdge$EdgeType", true, loader);
        final Object normalPt = enumConstant(ptType, "NORMAL");
        final Object normalEdge = enumConstant(edgeType, "NORMAL");
        final Object autoEdge = enumConstant(edgeType, "AUTO_TRIANGULATION");
        final Object userEdge = enumConstant(edgeType, "USER_TRIANGULATION");

        final Object mesh = meshCls.getConstructor().newInstance();
        final Method addPoint = meshCls.getMethod("addPoint", float.class, float.class, ptType, long.class);
        final Method addEdge =
                meshCls.getMethod("addEdge", int.class, int.class, edgeType, boolean.class, boolean.class);
        final Method getEdgeIndex = meshCls.getMethod("getEdgeIndex", int.class, int.class);
        final Method checkExisting =
                meshCls.getDeclaredMethod("chechExistingEdge_exe", int.class, int.class, boolean.class);
        checkExisting.setAccessible(true);

        for (int r = 0; r < STRESS_GRID_DIM; r++) {
            for (int c = 0; c < STRESS_GRID_DIM; c++) {
                addPoint.invoke(mesh, (float) c, (float) r, normalPt, TEST_UID_BASE + (long) r * STRESS_GRID_DIM + c);
            }
        }

        // Prime initial MeshIndex once after vertex creation.
        assertEquals(-1, (int) getEdgeIndex.invoke(mesh, 0, 1));

        // 1. Add ~6,000 AUTO_TRIANGULATION diagonal edges incrementally, checking existence before each add.
        final int cells = STRESS_GRID_DIM - 1;
        int addedAuto = 0;
        for (int r = 0; r < cells && addedAuto < 6000; r++) {
            for (int c = 0; c < cells && addedAuto < 6000; c++) {
                final int v00 = r * STRESS_GRID_DIM + c;
                final int v11 = v00 + STRESS_GRID_DIM + 1;
                if (exerciseAll) {
                    assertNull(checkExisting.invoke(mesh, v00, v11, false));
                }
                addEdge.invoke(mesh, v00, v11, autoEdge, true, false);
                addedAuto++;
            }
        }

        // 2. Add ~5,500 non-auto NORMAL / USER_TRIANGULATION edges (exercising allowCross=false &
        // checkSimilarEdge_exe).
        int addedNonAuto = 0;
        for (int r = 0; r < STRESS_GRID_DIM && addedNonAuto < 5500; r++) {
            for (int c = 0; c < cells && addedNonAuto < 5500; c++) {
                final int v = r * STRESS_GRID_DIM + c;
                if (exerciseAll) {
                    assertNull(checkExisting.invoke(mesh, v, v + 1, true));
                }
                final boolean allowCross = addedNonAuto >= 1000;
                addEdge.invoke(mesh, v, v + 1, (c & 1) == 0 ? normalEdge : userEdge, allowCross, false);
                addedNonAuto++;
            }
        }

        // 3. Promote an existing AUTO_TRIANGULATION edge to NORMAL via checkExitingTypedEdge path.
        final Integer promoted = (Integer) addEdge.invoke(mesh, 0, STRESS_GRID_DIM + 1, normalEdge, true, false);
        assertNotNull(promoted);

        // Compare deterministic spot queries matching both raw and patched meshes.
        return List.of(
                (int) getEdgeIndex.invoke(mesh, 0, STRESS_GRID_DIM + 1),
                boxOrMinusOne(checkExisting.invoke(mesh, 0, STRESS_GRID_DIM + 1, true)),
                boxOrMinusOne(checkExisting.invoke(mesh, 1, STRESS_GRID_DIM + 2, true)),
                boxOrMinusOne(checkExisting.invoke(mesh, 1, STRESS_GRID_DIM + 2, false)),
                (int) getEdgeIndex.invoke(mesh, 0, 1),
                boxOrMinusOne(checkExisting.invoke(mesh, 0, 1, true)));
    }

    private static void verifyPointInTriangleParity(
            final ClassLoader rawLoader, final ClassLoader optLoader, final String version) throws Exception {
        final MeshData mesh = createGridMeshData(GRID_SIZE);
        final float[] testPoints = buildSampleCoordinates(QUERY_STEPS);

        final Object[] rawRes = runGeometrySuite(rawLoader, mesh, testPoints);
        final Object[] optRes = runGeometrySuite(optLoader, mesh, testPoints);
        assertEquals(rawRes.length, optRes.length, version);

        final float[] rawTransformed = (float[]) rawRes[0];
        final float[] optTransformed = (float[]) optRes[0];
        assertEquals(rawTransformed.length, optTransformed.length);
        for (int i = 0; i < rawTransformed.length; i++) {
            assertEquals(rawTransformed[i], optTransformed[i], FLOAT_TOL, version + " transform[" + i + "]");
        }
        assertArrayEquals((boolean[]) rawRes[1], (boolean[]) optRes[1], version + " MeshUtils.a flags");
        assertArrayEquals((int[]) rawRes[2], (int[]) optRes[2], version + " MeshUtils.a indices");

        final String[] rawDoubles = (String[]) rawRes[3];
        final String[] optDoubles = (String[]) optRes[3];
        assertEquals(rawDoubles.length, optDoubles.length);
        for (int i = 0; i < rawDoubles.length; i++) {
            assertEquals(rawDoubles[i], optDoubles[i], version + " PointInTriangleD[" + i + "]");
        }
        final String[] rawFloats = (String[]) rawRes[4];
        final String[] optFloats = (String[]) optRes[4];
        assertEquals(rawFloats.length, optFloats.length);
        for (int i = 0; i < rawFloats.length; i++) {
            assertEquals(rawFloats[i], optFloats[i], version + " PointInTriangle[" + i + "]");
        }
    }

    private static Object[] runGeometrySuite(final ClassLoader loader, final MeshData mesh, final float[] queries)
            throws Exception {
        final Class<?> vecCls = Class.forName("com.live2d.graphics3d.type.GVector2", true, loader);
        final Constructor<?> vecCtor = vecCls.getConstructor(float.class, float.class);

        final Class<?> genCls = Class.forName("com.live2d.cubism.doc.model.extension.meshGenerator.a.a", true, loader);
        final Constructor<?> genCtor = genCls.getConstructor(float[].class, float[].class, int[].class, int.class);
        final Object transformer = genCtor.newInstance(mesh.srcPos, mesh.dstPos, mesh.indices, STRIDE_XY);
        final Method transformArr = genCls.getMethod("transform", float[].class, float[].class);
        final float[] transformedOut = new float[queries.length];
        transformArr.invoke(transformer, queries, transformedOut);

        final Class<?> utilsCls = Class.forName("com.live2d.graphics3d.mesh.MeshUtils", true, loader);
        final Object utilsObj = utilsCls.getField("a").get(null);
        final Method utilsFind = utilsCls.getMethod("a", float[].class, int[].class, int.class, vecCls, int[].class);

        final Class<?> pitdClass =
                Class.forName("com.live2d.cubism.doc.model.drawable.artMesh.PointInTriangleD", true, loader);
        final Object pitdCompanion = pitdClass.getField("Companion").get(null);
        final Method pitdFind =
                pitdCompanion.getClass().getMethod("a", vecCls, float[].class, int[].class, int.class, boolean.class);

        final Class<?> pitClass =
                Class.forName("com.live2d.cubism.doc.model.drawable.artMesh.PointInTriangle", true, loader);
        final Object pitCompanion = pitClass.getField("Companion").get(null);
        final Method pitFind =
                pitCompanion.getClass().getMethod("a", vecCls, float[].class, int[].class, int.class, boolean.class);

        final int count = queries.length / STRIDE_XY;
        final boolean[] strictFlags = new boolean[count];
        final int[] strictTris = new int[count * TRI_STRIDE];
        final String[] pitdStrings = new String[count * 2];
        final String[] pitStrings = new String[count * 2];
        final int[] triBuf = new int[TRI_STRIDE];
        for (int i = 0; i < count; i++) {
            final Object qVec = vecCtor.newInstance(queries[i * 2], queries[i * 2 + 1]);
            triBuf[0] = -1;
            triBuf[1] = -1;
            triBuf[2] = -1;
            strictFlags[i] = (boolean) utilsFind.invoke(utilsObj, mesh.srcPos, mesh.indices, STRIDE_XY, qVec, triBuf);
            System.arraycopy(triBuf, 0, strictTris, i * TRI_STRIDE, TRI_STRIDE);

            for (int skip = 0; skip < 2; skip++) {
                final boolean skipFallback = skip == 1;
                final Object dRes =
                        pitdFind.invoke(pitdCompanion, qVec, mesh.srcPos, mesh.indices, STRIDE_XY, skipFallback);
                pitdStrings[i * 2 + skip] = summarizePointInTriangle(dRes);
                final Object fRes =
                        pitFind.invoke(pitCompanion, qVec, mesh.srcPos, mesh.indices, STRIDE_XY, skipFallback);
                pitStrings[i * 2 + skip] = summarizePointInTriangle(fRes);
            }
        }
        return new Object[] {transformedOut, strictFlags, strictTris, pitdStrings, pitStrings};
    }

    private static String summarizePointInTriangle(final Object res) throws ReflectiveOperationException {
        if (res == null) {
            return "null";
        }
        final Class<?> cls = res.getClass();
        final Field f1 = cls.getDeclaredField("ptIndex1");
        final Field f2 = cls.getDeclaredField("ptIndex2");
        final Field f3 = cls.getDeclaredField("ptIndex3");
        final Field w1 = cls.getDeclaredField("weight1");
        final Field w2 = cls.getDeclaredField("weight2");
        final Field w3 = cls.getDeclaredField("weight3");
        f1.setAccessible(true);
        f2.setAccessible(true);
        f3.setAccessible(true);
        w1.setAccessible(true);
        w2.setAccessible(true);
        w3.setAccessible(true);
        final double d1 = ((Number) w1.get(res)).doubleValue();
        final double d2 = ((Number) w2.get(res)).doubleValue();
        final double d3 = ((Number) w3.get(res)).doubleValue();
        return String.format(
                java.util.Locale.ROOT,
                "%d,%d,%d:%.4f,%.4f,%.4f",
                f1.getInt(res),
                f2.getInt(res),
                f3.getInt(res),
                d1,
                d2,
                d3);
    }

    private static void verifyEditableMeshTopologyParity(
            final ClassLoader rawLoader, final ClassLoader optLoader, final String version) throws Exception {
        final TopologySummary rawSum = runTopologyScenario(rawLoader);
        final TopologySummary optSum = runTopologyScenario(optLoader);

        assertEquals(rawSum.edgeIndices, optSum.edgeIndices, version + " edge indices");
        assertEquals(rawSum.uidIndices, optSum.uidIndices, version + " UID indices");
        assertEquals(rawSum.existingChecks, optSum.existingChecks, version + " chechExistingEdge_exe");
        assertEquals(rawSum.similarChecks, optSum.similarChecks, version + " checkSimilarEdge_exe");
        assertEquals(rawSum.crossChecks, optSum.crossChecks, version + " checkCross__");
        assertEquals(rawSum.polygonSignatures, optSum.polygonSignatures, version + " b.b polygons");
        assertEquals(rawSum.triangleCount, optSum.triangleCount, version + " GEditableMeshHandler.a");
        assertEquals(rawSum.afterClearEdgeIdx, optSum.afterClearEdgeIdx, version + " clearAutoTriangulation reset");
        if (rawSum.duplicateGroups != null && optSum.duplicateGroups != null) {
            assertEquals(rawSum.duplicateGroups, optSum.duplicateGroups, version + " b.d duplicates");
        }
    }

    private static TopologySummary runTopologyScenario(final ClassLoader loader) throws Exception {
        final Class<?> meshCls = Class.forName("com.live2d.graphics3d.editableMesh.GEditableMesh2", true, loader);
        final Class<?> pointTypeCls =
                Class.forName("com.live2d.graphics3d.editableMesh.MPoint2$PointType", true, loader);
        final Class<?> edgeTypeCls = Class.forName("com.live2d.graphics3d.editableMesh.MEdge$EdgeType", true, loader);
        final Class<?> vecCls = Class.forName("com.live2d.graphics3d.type.GVector2", true, loader);
        final Class<?> edgeCls = Class.forName("com.live2d.graphics3d.editableMesh.MEdge", true, loader);
        final Class<?> helperCls = Class.forName("com.live2d.graphics3d.editableMesh.b", true, loader);
        final Class<?> handlerCls =
                Class.forName("com.live2d.graphics3d.editableMesh.GEditableMeshHandler", true, loader);

        final Object normalPt = enumConstant(pointTypeCls, "NORMAL");
        final Object normalEdge = enumConstant(edgeTypeCls, "NORMAL");
        final Object autoEdge = enumConstant(edgeTypeCls, "AUTO_TRIANGULATION");
        final Object userEdge = enumConstant(edgeTypeCls, "USER_TRIANGULATION");

        final Object mesh = meshCls.getConstructor().newInstance();
        final Method addPoint = meshCls.getMethod("addPoint", float.class, float.class, pointTypeCls, long.class);
        final Method addEdge =
                meshCls.getMethod("addEdge", int.class, int.class, edgeTypeCls, boolean.class, boolean.class);
        final Method getEdgeIndex = meshCls.getMethod("getEdgeIndex", int.class, int.class);
        final Method getPointIndexByUid = meshCls.getMethod("getPointIndexByUid", long.class);
        final Method getPointRefByUid = meshCls.getMethod("getPointRefByUid", long.class);
        final Method checkExisting =
                meshCls.getDeclaredMethod("chechExistingEdge_exe", int.class, int.class, boolean.class);
        checkExisting.setAccessible(true);
        final Method checkSimilar = meshCls.getDeclaredMethod("checkSimilarEdge_exe", int.class, int.class);
        checkSimilar.setAccessible(true);
        final Method checkCross =
                meshCls.getMethod("checkCross__", vecCls, int.class, int.class, boolean.class, boolean.class, edgeCls);
        final Method clearAuto = meshCls.getMethod("clearAutoTriangulation");

        for (int r = 0; r < GRID_SIZE; r++) {
            for (int c = 0; c < GRID_SIZE; c++) {
                final long uid = TEST_UID_BASE + r * GRID_SIZE + c;
                addPoint.invoke(mesh, (float) c, (float) r, normalPt, uid);
            }
        }
        // Add duplicate point at (0, 0) to verify b.d and handler dedup parity.
        addPoint.invoke(mesh, 0.0f, 0.0f, normalPt, TEST_UID_BASE + 999L);

        for (int r = 0; r < GRID_SIZE; r++) {
            for (int c = 0; c < GRID_SIZE - 1; c++) {
                final int v = r * GRID_SIZE + c;
                addEdge.invoke(mesh, v, v + 1, normalEdge, true, true);
            }
        }
        for (int r = 0; r < GRID_SIZE - 1; r++) {
            for (int c = 0; c < GRID_SIZE; c++) {
                final int v = r * GRID_SIZE + c;
                addEdge.invoke(mesh, v, v + GRID_SIZE, r % 2 == 0 ? normalEdge : userEdge, true, true);
            }
        }
        addEdge.invoke(mesh, 0, GRID_SIZE + 1, autoEdge, true, true);
        addEdge.invoke(mesh, 1, GRID_SIZE, normalEdge, true, true);

        final List<Integer> edgeIndices =
                List.of((int) getEdgeIndex.invoke(mesh, 0, 1), (int) getEdgeIndex.invoke(mesh, GRID_SIZE + 1, 0), (int)
                        getEdgeIndex.invoke(mesh, 0, GRID_SIZE * GRID_SIZE - 1));
        final List<Integer> uidIndices = List.of(
                (int) getPointIndexByUid.invoke(mesh, TEST_UID_BASE),
                (int) getPointIndexByUid.invoke(mesh, TEST_UID_BASE + 12L),
                (int) getPointIndexByUid.invoke(mesh, TEST_UID_BASE + 99999L));
        assertNotNull(getPointRefByUid.invoke(mesh, TEST_UID_BASE));
        assertNull(getPointRefByUid.invoke(mesh, TEST_UID_BASE + 99999L));

        final List<Integer> existingChecks = List.of(
                boxOrMinusOne(checkExisting.invoke(mesh, 0, GRID_SIZE + 1, false)),
                boxOrMinusOne(checkExisting.invoke(mesh, 0, GRID_SIZE + 1, true)),
                boxOrMinusOne(checkExisting.invoke(mesh, 0, 1, true)));
        final List<Integer> similarChecks = List.of(
                boxOrMinusOne(checkSimilar.invoke(mesh, 0, 2)),
                boxOrMinusOne(checkSimilar.invoke(mesh, 0, GRID_SIZE + 2)));

        final Object p0Vec = vecCls.getConstructor(float.class, float.class).newInstance(0.0f, 0.0f);
        final List<Boolean> crossChecks =
                List.of((boolean) checkCross.invoke(mesh, p0Vec, 0, GRID_SIZE + 1, false, false, null), (boolean)
                        checkCross.invoke(mesh, p0Vec, 0, 2, false, false, null));

        final Object helperObj = helperCls.getField("a").get(null);
        @SuppressWarnings("unchecked")
        final List<int[]> polys =
                (List<int[]>) helperCls.getMethod("b", meshCls).invoke(helperObj, mesh);
        final List<String> polySigs = new ArrayList<>();
        for (final int[] poly : polys) {
            polySigs.add(java.util.Arrays.toString(poly));
        }

        final Object handler = handlerCls.getConstructor(meshCls).newInstance(mesh);
        @SuppressWarnings("unchecked")
        final List<?> tris = (List<?>) handlerCls.getMethod("a", meshCls).invoke(handler, mesh);

        List<List<Integer>> dupGroups = null;
        try {
            @SuppressWarnings("unchecked")
            final List<List<Integer>> foundDups =
                    (List<List<Integer>>) helperCls.getMethod("d", meshCls).invoke(helperObj, mesh);
            dupGroups = foundDups;
        } catch (final NoSuchMethodException absentInOlderHost) {
            // b.d exists only on Cubism 5.3.02+.
        }

        clearAuto.invoke(mesh);
        final int afterClear = (int) getEdgeIndex.invoke(mesh, 0, GRID_SIZE + 1);

        return new TopologySummary(
                edgeIndices,
                uidIndices,
                existingChecks,
                similarChecks,
                crossChecks,
                polySigs,
                tris.size(),
                dupGroups,
                afterClear);
    }

    private static void verifyEditableMeshRenderAndSelectionHelpers(final PatchedJarLoader loader) throws Exception {
        final Class<?> meshCls = Class.forName("com.live2d.graphics3d.editableMesh.GEditableMesh2", true, loader);
        final Class<?> ptType = Class.forName("com.live2d.graphics3d.editableMesh.MPoint2$PointType", true, loader);
        final Class<?> edgeType = Class.forName("com.live2d.graphics3d.editableMesh.MEdge$EdgeType", true, loader);
        final Object normalPt = enumConstant(ptType, "NORMAL");
        final Object normalEdge = enumConstant(edgeType, "NORMAL");

        final Object mesh = meshCls.getConstructor().newInstance();
        final Method addPoint = meshCls.getMethod("addPoint", float.class, float.class, ptType, long.class);
        final Method addEdge =
                meshCls.getMethod("addEdge", int.class, int.class, edgeType, boolean.class, boolean.class);
        addPoint.invoke(mesh, 0.0f, 0.0f, normalPt, 1L);
        addPoint.invoke(mesh, 1.0f, 0.0f, normalPt, 2L);
        addPoint.invoke(mesh, 5.0f, 5.0f, normalPt, 3L);
        addEdge.invoke(mesh, 0, 1, normalEdge, false, false);

        final float[] connected = EditableMeshRenderOptimizer.extractPointPositions(mesh, true);
        final float[] isolated = EditableMeshRenderOptimizer.extractPointPositions(mesh, false);
        assertArrayEquals(new float[] {0.0f, 0.0f, 1.0f, 0.0f}, connected, FLOAT_TOL);
        assertArrayEquals(new float[] {5.0f, 5.0f}, isolated, FLOAT_TOL);

        final Object entity = new Object();
        final Object baseSetting = new Object();
        final Object drawSetting = new Object();
        assertFalse(EditableMeshRenderOptimizer.isOverlayCacheValid(entity, mesh, baseSetting, drawSetting, null));
        EditableMeshRenderOptimizer.recordOverlayUpdate(entity, mesh, baseSetting, drawSetting);
        assertTrue(EditableMeshRenderOptimizer.isOverlayCacheValid(entity, mesh, baseSetting, drawSetting, null));
        EditableMeshRenderOptimizer.invalidateRenderState(entity);
        assertFalse(EditableMeshRenderOptimizer.isOverlayCacheValid(entity, mesh, baseSetting, drawSetting, null));

        final Class<?> artMeshSelCls =
                Class.forName("com.live2d.cubism.doc.model.drawable.artMesh.ArtMeshSelection", true, loader);
        final Object artMeshSel = artMeshSelCls.getConstructor().newInstance();
        final Method getSelector = artMeshSelCls.getMethod("getPointSelector");
        final Object selector = getSelector.invoke(artMeshSel);
        final Object ref1 = definePointRefSubclass(loader, 1);
        final Class<?> iPointRef = Class.forName("com.live2d.doc.selection.IPointRef", true, loader);
        selector.getClass()
                .getMethod("add", iPointRef, float.class, boolean.class)
                .invoke(selector, ref1, 0.5f, false);

        final float[] colors = new float[8];
        assertTrue(
                EditableMeshSelectionOptimizer.fillSoftSelectionColors(artMeshSel, 2, 0.8f, 1.0f, 0.5f, 0.25f, colors));
        assertArrayEquals(new float[] {0.0f, 0.0f, 0.0f, 0.0f, 0.4f, 0.2f, 0.1f, 0.4f}, colors, FLOAT_TOL);
    }

    private static Object definePointRefSubclass(final PatchedJarLoader loader, final int vertexIndex)
            throws Exception {
        final String superInternal = "com/live2d/cubism/doc/model/drawable/artMesh/MeshPointRef";
        final String subInternal = "com/live2d/cubism/doc/model/drawable/artMesh/TurboismTestMeshPointRef";
        final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, subInternal, null, superInternal, null);

        final MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, superInternal, "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        final MethodVisitor getIdx = cw.visitMethod(Opcodes.ACC_PUBLIC, "a", "()I", null, null);
        getIdx.visitCode();
        getIdx.visitLdcInsn(vertexIndex);
        getIdx.visitInsn(Opcodes.IRETURN);
        getIdx.visitMaxs(0, 0);
        getIdx.visitEnd();

        final MethodVisitor hash = cw.visitMethod(Opcodes.ACC_PUBLIC, "hashCode", "()I", null, null);
        hash.visitCode();
        hash.visitLdcInsn(vertexIndex);
        hash.visitInsn(Opcodes.IRETURN);
        hash.visitMaxs(0, 0);
        hash.visitEnd();

        final MethodVisitor eq = cw.visitMethod(Opcodes.ACC_PUBLIC, "equals", "(Ljava/lang/Object;)Z", null, null);
        eq.visitCode();
        final Label ne = new Label();
        eq.visitVarInsn(Opcodes.ALOAD, 0);
        eq.visitVarInsn(Opcodes.ALOAD, 1);
        eq.visitJumpInsn(Opcodes.IF_ACMPNE, ne);
        eq.visitInsn(Opcodes.ICONST_1);
        eq.visitInsn(Opcodes.IRETURN);
        eq.visitLabel(ne);
        eq.visitInsn(Opcodes.ICONST_0);
        eq.visitInsn(Opcodes.IRETURN);
        eq.visitMaxs(0, 0);
        eq.visitEnd();

        cw.visitEnd();
        final Class<?> subCls = loader.defineHostClass(subInternal.replace('/', '.'), cw.toByteArray());
        return subCls.getConstructor().newInstance();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumConstant(final Class<?> enumType, final String constantName) {
        return Enum.valueOf((Class<Enum>) enumType.asSubclass(Enum.class), constantName);
    }

    private static int boxOrMinusOne(final Object boxed) {
        return boxed instanceof final Integer value ? value : -1;
    }

    private static List<Path> discoverAllTwentyOneJars() throws IOException {
        final Path home = Path.of(System.getProperty("user.home"));
        final Path artifactsDir = home.resolve(ARTIFACTS_DIR_NAME);
        final List<Path> jars = new ArrayList<>();
        if (Files.isDirectory(artifactsDir)) {
            try (var dirs = Files.list(artifactsDir)) {
                dirs.filter(Files::isDirectory)
                        .sorted()
                        .map(dir -> dir.resolve(JAR_NAME))
                        .filter(Files::isRegularFile)
                        .forEach(jars::add);
            }
        }
        final Path jar5305 = home.resolve(JAR_5305_REL);
        if (Files.isRegularFile(jar5305)) {
            jars.add(jar5305);
        }
        return jars;
    }

    private static String versionLabel(final Path jarPath) {
        final Path parent = jarPath.getParent();
        if (parent != null
                && ARTIFACTS_DIR_NAME.equals(parent.getParent().getFileName().toString())) {
            return parent.getFileName().toString();
        }
        return "5.3.05";
    }

    private static void runStrictBytecodeVerifierSubprocess(final Path manifestList, final int expectedCount)
            throws Exception {
        final String javaExe =
                Path.of(System.getProperty("java.home"), "bin", "java").toString();
        final String classpath = System.getProperty("java.class.path");
        final Process process = new ProcessBuilder(
                        javaExe,
                        "-Xverify:all",
                        "-cp",
                        classpath,
                        StrictVerifierMain.class.getName(),
                        manifestList.toString())
                .redirectErrorStream(true)
                .start();
        final String output;
        try (InputStream in = process.getInputStream()) {
            output = new String(in.readAllBytes());
        }
        assertTrue(
                process.waitFor(VERIFY_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS), "Verifier timed out");
        assertEquals(0, process.exitValue(), "Strict -Xverify:all failed:\n" + output);
        assertTrue(output.contains("VERIFIED_TARGETS=" + expectedCount), output);
    }

    private static MeshData createGridMeshData(final int size) {
        final float[] src = new float[size * size * STRIDE_XY];
        final float[] dst = new float[size * size * STRIDE_XY];
        for (int r = 0; r < size; r++) {
            for (int c = 0; c < size; c++) {
                final int off = (r * size + c) * STRIDE_XY;
                src[off] = c;
                src[off + 1] = r;
                dst[off] = c + SHIFT_X;
                dst[off + 1] = r + SHIFT_Y;
            }
        }
        final int[] indices = new int[(size - 1) * (size - 1) * 2 * TRI_STRIDE];
        int p = 0;
        for (int r = 0; r < size - 1; r++) {
            for (int c = 0; c < size - 1; c++) {
                final int v00 = r * size + c;
                final int v10 = v00 + 1;
                final int v01 = v00 + size;
                final int v11 = v01 + 1;
                indices[p++] = v00;
                indices[p++] = v10;
                indices[p++] = v11;
                indices[p++] = v00;
                indices[p++] = v11;
                indices[p++] = v01;
            }
        }
        return new MeshData(src, dst, indices);
    }

    private static float[] buildSampleCoordinates(final int steps) {
        final float[] coords = new float[(steps * steps + 1) * STRIDE_XY];
        int p = 0;
        for (int ix = 0; ix < steps; ix++) {
            for (int iy = 0; iy < steps; iy++) {
                coords[p++] = (ix / (float) steps) * (GRID_SIZE - 1);
                coords[p++] = (iy / (float) steps) * (GRID_SIZE - 1);
            }
        }
        coords[p++] = -2.0f;
        coords[p] = 10.0f;
        return coords;
    }

    private record MeshData(float[] srcPos, float[] dstPos, int[] indices) {}

    private record TopologySummary(
            List<Integer> edgeIndices,
            List<Integer> uidIndices,
            List<Integer> existingChecks,
            List<Integer> similarChecks,
            List<Boolean> crossChecks,
            List<String> polygonSignatures,
            int triangleCount,
            List<List<Integer>> duplicateGroups,
            int afterClearEdgeIdx) {}

    /** Child entrypoint launched under {@code -Xverify:all} to link all patched host classes. */
    public static final class StrictVerifierMain {

        private StrictVerifierMain() {}

        /**
         * Links patched classes from each Cubism version against its host JAR under strict verification.
         *
         * @param args single-element array containing manifest file path
         * @throws Exception when verification fails
         */
        public static void main(final String[] args) throws Exception {
            final List<String> lines = Files.readAllLines(Path.of(args[0]));
            int verified = 0;
            String currentVersion = null;
            PatchedJarLoader currentLoader = null;
            try {
                for (final String line : lines) {
                    if (line.isBlank()) {
                        continue;
                    }
                    final String[] parts = line.split("\t");
                    final String version = parts[0];
                    final Path jarPath = Path.of(parts[1]);
                    final Path patchedDir = Path.of(parts[2]);
                    final String internalName = parts[3];
                    if (!version.equals(currentVersion)) {
                        if (currentLoader != null) {
                            currentLoader.close();
                        }
                        currentVersion = version;
                        currentLoader =
                                new PatchedJarLoader(jarPath, patchedDir, StrictVerifierMain.class.getClassLoader());
                    }
                    final Class<?> defined = Class.forName(internalName.replace('/', '.'), false, currentLoader);
                    defined.getDeclaredMethods();
                    verified++;
                }
            } finally {
                if (currentLoader != null) {
                    currentLoader.close();
                }
            }
            System.out.println("VERIFIED_TARGETS=" + verified);
        }
    }

    private static final class PatchedJarLoader extends URLClassLoader {

        private final JarFile hostJar;
        private final Path patchRoot;
        private final ClassLoader runtimeParent;

        PatchedJarLoader(final Path jarPath, final Path patchRoot, final ClassLoader runtimeParent) throws IOException {
            super(siblingUrls(jarPath), ClassLoader.getPlatformClassLoader());
            this.hostJar = new JarFile(jarPath.toFile(), false);
            this.patchRoot = patchRoot;
            this.runtimeParent = runtimeParent;
        }

        Class<?> defineHostClass(final String name, final byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }

        private static URL[] siblingUrls(final Path jarPath) throws IOException {
            final List<URL> urls = new ArrayList<>();
            boolean hasJogl = false;
            try (var files = Files.walk(jarPath.getParent(), 2)) {
                for (final Path file : files.toList()) {
                    if (Files.isRegularFile(file) && file.toString().endsWith(JAR_SUFFIX) && !file.equals(jarPath)) {
                        urls.add(file.toUri().toURL());
                        hasJogl |= file.getFileName().toString().contains("jogl");
                    }
                }
            }
            final Path home = Path.of(System.getProperty("user.home"));
            if (!hasJogl) {
                final Path libDir = home.resolve(JAR_5305_REL).getParent();
                if (Files.isDirectory(libDir)) {
                    try (var files = Files.walk(libDir, 2)) {
                        for (final Path file : files.toList()) {
                            final String fname = file.getFileName().toString();
                            if (Files.isRegularFile(file)
                                    && fname.endsWith(JAR_SUFFIX)
                                    && (fname.contains("gluegen")
                                            || fname.contains("jogl")
                                            || fname.contains("jdom")
                                            || fname.contains("log4j"))) {
                                urls.add(file.toUri().toURL());
                            }
                        }
                    }
                }
            }
            return urls.toArray(URL[]::new);
        }

        @Override
        protected Class<?> findClass(final String name) throws ClassNotFoundException {
            final String internal = name.replace('.', '/');
            final Path patchedFile = this.patchRoot.resolve(internal + CLASS_SUFFIX);
            if (Files.isRegularFile(patchedFile)) {
                try {
                    final byte[] bytes = Files.readAllBytes(patchedFile);
                    return defineClass(name, bytes, 0, bytes.length);
                } catch (final IOException failure) {
                    throw new ClassNotFoundException(name, failure);
                }
            }
            final JarEntry hostEntry = this.hostJar.getJarEntry(internal + CLASS_SUFFIX);
            if (hostEntry != null) {
                try (InputStream in = this.hostJar.getInputStream(hostEntry)) {
                    final byte[] bytes = in.readAllBytes();
                    return defineClass(name, bytes, 0, bytes.length);
                } catch (final IOException failure) {
                    throw new ClassNotFoundException(name, failure);
                }
            }
            try {
                return super.findClass(name);
            } catch (final ClassNotFoundException absent) {
                if (name.startsWith(STUB_PACKAGE_PREFIX)) {
                    final ClassWriter cw = new ClassWriter(0);
                    cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internal, null, "java/lang/Object", null);
                    cw.visitEnd();
                    final byte[] stub = cw.toByteArray();
                    return defineClass(name, stub, 0, stub.length);
                }
                return this.runtimeParent.loadClass(name);
            }
        }

        @Override
        protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("dev.turboism.")) {
                return this.runtimeParent.loadClass(name);
            }
            return super.loadClass(name, resolve);
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                this.hostJar.close();
            }
        }
    }
}
