package dev.turboism.adapter.cubism.optimization.warpgrid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Verifies bytecode rewriting across reviewed artifacts and bit-exact IEEE-754 parity.
 */
class WarpGridInterpolationArtifactTest {

    private static final String REF_DIR_PROPERTY = "turboism.test.cubismRefDir";
    private static final String REF_DIR_ENV = "TURBOISM_CUBISM_REF";
    private static final String CUBISM_JAR = "Live2D_Cubism.jar";
    private static final String JAR_EXTENSION = ".jar";
    private static final String CLASS_EXTENSION = ".class";
    private static final String OWNER_DOT_NAME = WarpGridInterpolationTarget.OWNER.replace('/', '.');
    private static final String PREFERRED_PARITY_VERSION = "5.3.04";
    private static final String CUBISM_5305_VERSION = "5.3.05";
    private static final String ARCH_REFERENCE_SUFFIX = ".local/share/cubism-reference";
    private static final String PROTON_5305_PREFIX = "proton/pfx/drive_c/Program Files/Live2D Cubism 5.3/app/lib";
    private static final String XVERIFY_ALL_FLAG = "-Xverify:all";
    private static final String VERIFY_MANIFEST_FILE = "patched-targets.txt";
    private static final String TAB = "\t";
    private static final int VERIFY_TIMEOUT_SECONDS = 120;
    private static final String STAT_OOB_TAIL_CALLS = "oobTailCalls";
    private static final String STAT_OOB_POINTS = "oobPoints";
    private static final String STAT_IN_BOUNDS_FALLBACK_POINTS = "inBoundsFallbackPoints";
    private static final String STAT_FAILURES = "failures";
    private static final String STAT_BOUND = "bound";
    private static final String PROPERTIES_INTERNAL = "java/util/Properties";
    private static final String PROPERTIES_GET_METHOD = "get";
    private static final String METHOD_HANDLE_INTERNAL = "java/lang/invoke/MethodHandle";
    private static final String INVOKE_EXACT_METHOD = "invokeExact";
    private static final String CONSUMER_INTERNAL = "java/util/function/Consumer";
    private static final String ATOMIC_BOOLEAN_INTERNAL = "java/util/concurrent/atomic/AtomicBoolean";
    private static final String INTEGER_INTERNAL = "java/lang/Integer";
    private static final String BOOLEAN_INTERNAL = "java/lang/Boolean";
    private static final String VALUE_OF_METHOD = "valueOf";
    private static final long RANDOM_SEED = 0x5741525047524944L;
    private static final int RANDOM_TRIALS = 24;
    private static final int MAX_WALK_DEPTH = 2;
    private static final int COORD_STRIDE = 2;

    @Test
    void everyConfiguredReviewedArtifactRewritesWarpGridInterpolation(@TempDir final Path tempDir) throws Exception {
        final Path root = referenceRoot();
        final Path manifestFile = tempDir.resolve(VERIFY_MANIFEST_FILE);
        final List<String> manifestLines = new ArrayList<>();
        int rewritten = 0;
        for (final String version : ReviewedHostArtifacts.reviewedCubismVersions()) {
            final Path artifact = resolveVersionArtifact(root, version);
            if (artifact == null) {
                continue;
            }
            final HostArtifactDigest digest = HostArtifactDigest.from(artifact);
            final WarpGridInterpolationTarget target = WarpGridInterpolationTarget.of(digest)
                    .orElseThrow(() -> new AssertionError("reviewed artifact refused: " + version));
            try (URLClassLoader loader = hostLoader(artifact.getParent())) {
                final byte[] original = readClassBytes(artifact, WarpGridInterpolationTarget.OWNER);
                final byte[] simpleOriginal = readClassBytes(artifact, WarpGridInterpolationTarget.SIMPLE_GRID_OWNER);
                final byte[] borderOriginal = readClassBytes(artifact, WarpGridInterpolationTarget.BORDER_GRID_OWNER);
                final WarpGridInterpolationTransformer transformer = new WarpGridInterpolationTransformer(
                        loader, artifact, original, simpleOriginal, borderOriginal, target);
                final byte[] rewrittenBytes = transformer.transform(
                        null, loader, WarpGridInterpolationTarget.OWNER, null, protectionDomain(artifact), original);
                assertNotNull(rewrittenBytes, version + ": " + transformer.failure());
                assertEquals(1, transformer.matches(), version);
                assertNull(transformer.failure(), version);
                assertSingleLookupPrimitiveOobBytecode(original, rewrittenBytes, version);

                final byte[] rewrittenSimple = transformer.transform(
                        null,
                        loader,
                        WarpGridInterpolationTarget.SIMPLE_GRID_OWNER,
                        null,
                        protectionDomain(artifact),
                        simpleOriginal);
                assertNotNull(rewrittenSimple, version + ": " + transformer.failure());
                final byte[] rewrittenBorder = transformer.transform(
                        null,
                        loader,
                        WarpGridInterpolationTarget.BORDER_GRID_OWNER,
                        null,
                        protectionDomain(artifact),
                        borderOriginal);
                assertNotNull(rewrittenBorder, version + ": " + transformer.failure());
                assertEquals(3, transformer.matches(), version);
                assertNull(transformer.failure(), version);

                final Path versionOutDir = tempDir.resolve(version);
                writePatchedClass(versionOutDir, WarpGridInterpolationTarget.OWNER, rewrittenBytes);
                writePatchedClass(versionOutDir, WarpGridInterpolationTarget.SIMPLE_GRID_OWNER, rewrittenSimple);
                writePatchedClass(versionOutDir, WarpGridInterpolationTarget.BORDER_GRID_OWNER, rewrittenBorder);
                manifestLines.add(
                        version + TAB + artifact + TAB + versionOutDir + TAB + WarpGridInterpolationTarget.OWNER);
                manifestLines.add(version
                        + TAB
                        + artifact
                        + TAB
                        + versionOutDir
                        + TAB
                        + WarpGridInterpolationTarget.SIMPLE_GRID_OWNER);
                manifestLines.add(version
                        + TAB
                        + artifact
                        + TAB
                        + versionOutDir
                        + TAB
                        + WarpGridInterpolationTarget.BORDER_GRID_OWNER);

                // Load rewritten classes in-process as an immediate sanity gate ahead of -Xverify:all.
                try (RewrittenHostClassLoader verifyLoader = new RewrittenHostClassLoader(
                        urlsUnder(artifact.getParent()), WarpGridInterpolationArtifactTest.class.getClassLoader())) {
                    verifyLoader.installOverride(
                            OWNER_DOT_NAME,
                            rewrittenBytes,
                            Class.forName(OWNER_DOT_NAME, false, loader).getProtectionDomain());
                    verifyLoader.installOverride(
                            WarpGridInterpolationTarget.SIMPLE_GRID_OWNER.replace('/', '.'),
                            rewrittenSimple,
                            Class.forName(
                                            WarpGridInterpolationTarget.SIMPLE_GRID_OWNER.replace('/', '.'),
                                            false,
                                            loader)
                                    .getProtectionDomain());
                    verifyLoader.installOverride(
                            WarpGridInterpolationTarget.BORDER_GRID_OWNER.replace('/', '.'),
                            rewrittenBorder,
                            Class.forName(
                                            WarpGridInterpolationTarget.BORDER_GRID_OWNER.replace('/', '.'),
                                            false,
                                            loader)
                                    .getProtectionDomain());
                    Class.forName(OWNER_DOT_NAME, true, verifyLoader);
                    Class.forName(WarpGridInterpolationTarget.SIMPLE_GRID_OWNER.replace('/', '.'), true, verifyLoader);
                    Class.forName(WarpGridInterpolationTarget.BORDER_GRID_OWNER.replace('/', '.'), true, verifyLoader);
                }

                // Verify fail-closed refusal for wrong loader.
                try (URLClassLoader otherLoader = hostLoader(artifact.getParent())) {
                    assertNull(transformer.transform(
                            null,
                            otherLoader,
                            WarpGridInterpolationTarget.OWNER,
                            null,
                            protectionDomain(artifact),
                            original));
                }
            }
            rewritten++;
        }
        assertTrue(rewritten > 0, "no reviewed exact artifact found under " + root);
        Files.write(manifestFile, manifestLines);
        runStrictVerifySubprocess(manifestFile, manifestLines.size());
    }

    @Test
    void rewrittenHostMethodMatchesOriginalRawBitsAcrossInBoundsOutOfBoundsAndDegenerateCases() throws Throwable {
        final Path root = referenceRoot();
        final Path artifact = resolveParityArtifact(root);
        final HostArtifactDigest digest = HostArtifactDigest.from(artifact);
        final WarpGridInterpolationTarget target = WarpGridInterpolationTarget.of(digest)
                .orElseThrow(() -> new AssertionError("parity artifact refused: " + artifact));
        final byte[] originalBytes = readClassBytes(artifact, WarpGridInterpolationTarget.OWNER);
        final byte[] simpleOriginalBytes = readClassBytes(artifact, WarpGridInterpolationTarget.SIMPLE_GRID_OWNER);
        final byte[] borderOriginalBytes = readClassBytes(artifact, WarpGridInterpolationTarget.BORDER_GRID_OWNER);

        try (URLClassLoader originalLoader = hostLoader(artifact.getParent());
                RewrittenHostClassLoader rewrittenLoader = new RewrittenHostClassLoader(
                        urlsUnder(artifact.getParent()), WarpGridInterpolationArtifactTest.class.getClassLoader())) {
            final WarpGridInterpolationTransformer transformer = new WarpGridInterpolationTransformer(
                    rewrittenLoader, artifact, originalBytes, simpleOriginalBytes, borderOriginalBytes, target);
            final byte[] rewrittenBytes = transformer.transform(
                    null,
                    rewrittenLoader,
                    WarpGridInterpolationTarget.OWNER,
                    null,
                    protectionDomain(artifact),
                    originalBytes);
            final byte[] rewrittenSimpleBytes = transformer.transform(
                    null,
                    rewrittenLoader,
                    WarpGridInterpolationTarget.SIMPLE_GRID_OWNER,
                    null,
                    protectionDomain(artifact),
                    simpleOriginalBytes);
            final byte[] rewrittenBorderBytes = transformer.transform(
                    null,
                    rewrittenLoader,
                    WarpGridInterpolationTarget.BORDER_GRID_OWNER,
                    null,
                    protectionDomain(artifact),
                    borderOriginalBytes);
            assertNotNull(rewrittenBytes, transformer.failure());
            assertNotNull(rewrittenSimpleBytes, transformer.failure());
            assertNotNull(rewrittenBorderBytes, transformer.failure());
            assertEquals(3, transformer.matches());
            final ProtectionDomain signedDomain =
                    Class.forName(OWNER_DOT_NAME, false, originalLoader).getProtectionDomain();
            rewrittenLoader.installOverride(OWNER_DOT_NAME, rewrittenBytes, signedDomain);
            rewrittenLoader.installOverride(
                    WarpGridInterpolationTarget.SIMPLE_GRID_OWNER.replace('/', '.'),
                    rewrittenSimpleBytes,
                    Class.forName(
                                    WarpGridInterpolationTarget.SIMPLE_GRID_OWNER.replace('/', '.'),
                                    false,
                                    originalLoader)
                            .getProtectionDomain());
            rewrittenLoader.installOverride(
                    WarpGridInterpolationTarget.BORDER_GRID_OWNER.replace('/', '.'),
                    rewrittenBorderBytes,
                    Class.forName(
                                    WarpGridInterpolationTarget.BORDER_GRID_OWNER.replace('/', '.'),
                                    false,
                                    originalLoader)
                            .getProtectionDomain());

            final Invocation originalInvoker = loadInvoker(originalLoader);
            final Invocation rewrittenInvoker = loadInvoker(rewrittenLoader);

            try (WarpGridInterpolationBridge bridge = WarpGridInterpolationBridge.open(rewrittenLoader)) {
                bridge.install();
                final MethodHandle publishedHandle = assertInstanceOf(
                        MethodHandle.class, System.getProperties().get(WarpGridInterpolationBridge.OOB_PROPERTY));
                assertEquals(
                        WarpGridInterpolationTarget.OOB_RUN_DESCRIPTOR,
                        publishedHandle.type().descriptorString());
                assertInstanceOf(
                        MethodHandle.class,
                        System.getProperties().get(WarpGridInterpolationBridge.BORDER_GRID_PROPERTY));

                verifyMixedTailCountersAndParity(originalInvoker, rewrittenInvoker, bridge);
                verifyNullParity(originalInvoker, rewrittenInvoker);
                verifyDeterministicCases(originalInvoker, rewrittenInvoker);
                assertTrue(bridge.snapshot().get(STAT_OOB_TAIL_CALLS) > 0L, "OOB delegation was not exercised");
                verifyRandomizedCases(originalInvoker, rewrittenInvoker);
                verifySimpleAndBorderGridParity(originalLoader, rewrittenLoader, true);
                bridge.close();
                assertNull(System.getProperties().get(WarpGridInterpolationBridge.OOB_PROPERTY));
                assertNull(System.getProperties().get(WarpGridInterpolationBridge.BORDER_GRID_PROPERTY));
                // Verify inactive fallback path still produces exact raw-bit equality.
                verifyNullParity(originalInvoker, rewrittenInvoker);
                verifyDeterministicCases(originalInvoker, rewrittenInvoker);
                verifySimpleAndBorderGridParity(originalLoader, rewrittenLoader, false);
            }
        }
    }

    private static void verifySimpleAndBorderGridParity(
            final ClassLoader originalLoader, final ClassLoader rewrittenLoader, final boolean checkZeroAlloc)
            throws Throwable {
        final String simpleDot = WarpGridInterpolationTarget.SIMPLE_GRID_OWNER.replace('/', '.');
        final String borderDot = WarpGridInterpolationTarget.BORDER_GRID_OWNER.replace('/', '.');
        final String vecDot = WarpGridInterpolationTarget.GVECTOR2_OWNER.replace('/', '.');
        final String icGridDot = "com.live2d.cubism.doc.model.deformer.warp.grid.ICGrid";

        final Class<?> origSimpleCls = Class.forName(simpleDot, true, originalLoader);
        final Class<?> rewrSimpleCls = Class.forName(simpleDot, true, rewrittenLoader);
        final Class<?> origBorderCls = Class.forName(borderDot, true, originalLoader);
        final Class<?> rewrBorderCls = Class.forName(borderDot, true, rewrittenLoader);
        final Class<?> origVecCls = Class.forName(vecDot, true, originalLoader);
        final Class<?> rewrVecCls = Class.forName(vecDot, true, rewrittenLoader);
        final Class<?> origIcGridCls = Class.forName(icGridDot, true, originalLoader);
        final Class<?> rewrIcGridCls = Class.forName(icGridDot, true, rewrittenLoader);

        final Constructor<?> origSimpleCtor = origSimpleCls.getConstructor(int.class, int.class, float[].class);
        final Constructor<?> rewrSimpleCtor = rewrSimpleCls.getConstructor(int.class, int.class, float[].class);
        final Constructor<?> origBorderCtor = origBorderCls.getConstructor(origIcGridCls);
        final Constructor<?> rewrBorderCtor = rewrBorderCls.getConstructor(rewrIcGridCls);
        final Constructor<?> origVecCtor = origVecCls.getConstructor(float.class, float.class);
        final Constructor<?> rewrVecCtor = rewrVecCls.getConstructor(float.class, float.class);

        final Method origGet01Ex = origSimpleCls.getMethod(
                WarpGridInterpolationTarget.SIMPLE_GRID_GET01_EX_METHOD,
                float.class,
                float.class,
                origVecCls,
                float[].class);
        final Method rewrGet01Ex = rewrSimpleCls.getMethod(
                WarpGridInterpolationTarget.SIMPLE_GRID_GET01_EX_METHOD,
                float.class,
                float.class,
                rewrVecCls,
                float[].class);
        final Method origBorderEval = origBorderCls.getMethod(
                WarpGridInterpolationTarget.BORDER_GRID_METHOD, float.class, float.class, origVecCls, float[].class);
        final Method rewrBorderEval = rewrBorderCls.getMethod(
                WarpGridInterpolationTarget.BORDER_GRID_METHOD, float.class, float.class, rewrVecCls, float[].class);
        final Method origBorderUpdate = origBorderCls.getMethod("b", origIcGridCls);
        final Method rewrBorderUpdate = rewrBorderCls.getMethod("b", rewrIcGridCls);

        final float[] foldedPoints = buildFoldedGrid(4, 3);
        final Object origSimple = origSimpleCtor.newInstance(4, 3, foldedPoints.clone());
        final Object rewrSimple = rewrSimpleCtor.newInstance(4, 3, foldedPoints.clone());
        final Object origBorder = origBorderCtor.newInstance(origSimple);
        final Object rewrBorder = rewrBorderCtor.newInstance(rewrSimple);

        // Null output array triggers exact NullPointerException parity.
        assertThrows(Exception.class, () -> origGet01Ex.invoke(origSimple, 0.5f, 0.5f, null, null));
        assertThrows(Exception.class, () -> rewrGet01Ex.invoke(rewrSimple, 0.5f, 0.5f, null, null));
        assertThrows(Exception.class, () -> origBorderEval.invoke(origBorder, 0.5f, 0.5f, null, null));
        assertThrows(Exception.class, () -> rewrBorderEval.invoke(rewrBorder, 0.5f, 0.5f, null, null));

        final Random rng = new Random(RANDOM_SEED ^ 0xCAFEBABEL);
        final List<float[]> probeCoords = new ArrayList<>();
        // Include interior, epsilon-boundary, corners, border bands, far affine fallback, NaN, and Inf.
        final float[] deterministicCoords = {
            0.0f,
            0.0f,
            1.0f,
            1.0f,
            12.0f,
            -8.0f,
            -1e-6f * 0.5f,
            5.0f,
            100.0f,
            100.0f,
            -150.0f,
            200.0f,
            40.0f,
            -30.0f,
            -35.0f,
            50.0f,
            15.0f,
            22.0f,
            0.25f,
            0.25f,
            Float.NaN,
            1.0f,
            Float.POSITIVE_INFINITY,
            2.0f
        };
        for (int i = 0; i < deterministicCoords.length; i += COORD_STRIDE) {
            probeCoords.add(new float[] {deterministicCoords[i], deterministicCoords[i + 1]});
        }
        for (int i = 0; i < 200; i++) {
            probeCoords.add(new float[] {(rng.nextFloat() - 0.35f) * 80.0f, (rng.nextFloat() - 0.35f) * 80.0f});
        }

        final float[] origTarget = {0.35f, 0.65f};
        for (final boolean withTarget : new boolean[] {false, true}) {
            final Object oVec = withTarget ? origVecCtor.newInstance(origTarget[0], origTarget[1]) : null;
            final Object rVec = withTarget ? rewrVecCtor.newInstance(origTarget[0], origTarget[1]) : null;
            for (final float[] pt : probeCoords) {
                final float[] oOut = {-7.0f, -9.0f};
                final float[] rOut = {-7.0f, -9.0f};
                final float[] oRes = (float[]) origGet01Ex.invoke(origSimple, pt[0], pt[1], oVec, oOut);
                final float[] rRes = (float[]) rewrGet01Ex.invoke(rewrSimple, pt[0], pt[1], rVec, rOut);
                assertFloatResultBitParity(oRes, rRes, oOut, rOut, "CSimpleGrid.get01_ex", pt[0], pt[1], withTarget);

                final float[] obOut = {-3.0f, -5.0f};
                final float[] rbOut = {-3.0f, -5.0f};
                final float[] obRes = (float[]) origBorderEval.invoke(origBorder, pt[0], pt[1], oVec, obOut);
                final float[] rbRes = (float[]) rewrBorderEval.invoke(rewrBorder, pt[0], pt[1], rVec, rbOut);
                assertFloatResultBitParity(obRes, rbRes, obOut, rbOut, "warp.grid.a.a", pt[0], pt[1], withTarget);
            }
        }

        // Grid mutation must invalidate cached border quads cleanly.
        final float[] shiftedPoints = buildGrid(4, 3, -50.0f, 40.0f, 70.0f, 60.0f, false);
        final Object origShifted = origSimpleCtor.newInstance(4, 3, shiftedPoints.clone());
        final Object rewrShifted = rewrSimpleCtor.newInstance(4, 3, shiftedPoints.clone());
        origBorderUpdate.invoke(origBorder, origShifted);
        rewrBorderUpdate.invoke(rewrBorder, rewrShifted);
        final float[] obAfter = {-1.0f, -1.0f};
        final float[] rbAfter = {-1.0f, -1.0f};
        final float[] obAfterRes = (float[]) origBorderEval.invoke(origBorder, -120.0f, 70.0f, null, obAfter);
        final float[] rbAfterRes = (float[]) rewrBorderEval.invoke(rewrBorder, -120.0f, 70.0f, null, rbAfter);
        assertFloatResultBitParity(
                obAfterRes, rbAfterRes, obAfter, rbAfter, "warp.grid.a.a after rebuild", -120.0f, 70.0f, false);

        // Exercise single-cell (1x1) and wide sparse (12x6) grids to cover X-shift boundary transitions and lazy Y
        // load.
        final float[] singleCellPts = buildGrid(1, 1, -20.0f, -10.0f, 40.0f, 30.0f, false);
        final Object origSingle = origSimpleCtor.newInstance(1, 1, singleCellPts.clone());
        final Object rewrSingle = rewrSimpleCtor.newInstance(1, 1, singleCellPts.clone());
        for (final float[] pt :
                new float[][] {{0.0f, 5.0f}, {-20.0f, -10.0f}, {20.0f, 20.0f}, {-50.0f, 5.0f}, {10.0f, -50.0f}}) {
            final float[] sOrigOut = {-2.0f, -4.0f};
            final float[] sRewrOut = {-2.0f, -4.0f};
            final float[] sOrigRes = (float[]) origGet01Ex.invoke(origSingle, pt[0], pt[1], null, sOrigOut);
            final float[] sRewrRes = (float[]) rewrGet01Ex.invoke(rewrSingle, pt[0], pt[1], null, sRewrOut);
            assertFloatResultBitParity(
                    sOrigRes, sRewrRes, sOrigOut, sRewrOut, "CSimpleGrid.get01_ex 1x1", pt[0], pt[1], false);
        }

        final float[] wideGridPts = buildGrid(12, 6, -60.0f, -30.0f, 10.0f, 10.0f, true);
        final Object origWide = origSimpleCtor.newInstance(12, 6, wideGridPts.clone());
        final Object rewrWide = rewrSimpleCtor.newInstance(12, 6, wideGridPts.clone());
        final Object origWideTarget = origVecCtor.newInstance(0.75f, 0.25f);
        final Object rewrWideTarget = rewrVecCtor.newInstance(0.75f, 0.25f);
        for (final float[] pt : new float[][] {
            {-55.0f, -25.0f},
            {55.0f, 25.0f},
            {5.0f, 100.0f},
            {-200.0f, 0.0f},
            {200.0f, 0.0f},
            {15.0f, 5.0f}
        }) {
            for (final boolean useTarget : new boolean[] {false, true}) {
                final float[] wOrigOut = {-3.0f, -6.0f};
                final float[] wRewrOut = {-3.0f, -6.0f};
                final float[] wOrigRes = (float[])
                        origGet01Ex.invoke(origWide, pt[0], pt[1], useTarget ? origWideTarget : null, wOrigOut);
                final float[] wRewrRes = (float[])
                        rewrGet01Ex.invoke(rewrWide, pt[0], pt[1], useTarget ? rewrWideTarget : null, wRewrOut);
                assertFloatResultBitParity(
                        wOrigRes, wRewrRes, wOrigOut, wRewrOut, "CSimpleGrid.get01_ex 12x6", pt[0], pt[1], useTarget);
            }
        }

        if (checkZeroAlloc) {
            final MethodHandle rewrSimpleMh =
                    java.lang.invoke.MethodHandles.lookup().unreflect(rewrGet01Ex);
            final MethodHandle rewrBorderMh =
                    java.lang.invoke.MethodHandles.lookup().unreflect(rewrBorderEval);
            final com.sun.management.ThreadMXBean allocMx =
                    (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
            final long tid = Thread.currentThread().getId();
            final float[] allocOut = new float[2];
            for (int i = 0; i < 5_000; i++) {
                final Object ignored1 = (float[]) rewrSimpleMh.invoke(rewrSimple, 15.0f, 12.0f, null, allocOut);
                final Object ignored2 = (float[]) rewrBorderMh.invoke(rewrBorder, -120.0f, 70.0f, null, allocOut);
            }
            allocMx.getThreadAllocatedBytes(tid);
            long minSimple = Long.MAX_VALUE;
            long minBorder = Long.MAX_VALUE;
            for (int pass = 0; pass < 3; pass++) {
                final long b0 = allocMx.getThreadAllocatedBytes(tid);
                for (int i = 0; i < 1_000; i++) {
                    final Object ignored = (float[]) rewrSimpleMh.invoke(rewrSimple, 15.0f, 12.0f, null, allocOut);
                }
                minSimple = Math.min(minSimple, allocMx.getThreadAllocatedBytes(tid) - b0);

                final long b1 = allocMx.getThreadAllocatedBytes(tid);
                for (int i = 0; i < 1_000; i++) {
                    final Object ignored = (float[]) rewrBorderMh.invoke(rewrBorder, -120.0f, 70.0f, null, allocOut);
                }
                minBorder = Math.min(minBorder, allocMx.getThreadAllocatedBytes(tid) - b1);
            }
            assertEquals(0L, minSimple, "rewritten CSimpleGrid.get01_ex must allocate zero bytes");
            assertEquals(0L, minBorder, "rewritten warp.grid.a.a must allocate zero bytes");
        }
    }

    private static void assertFloatResultBitParity(
            final float[] origRet,
            final float[] rewrRet,
            final float[] origOut,
            final float[] rewrOut,
            final String label,
            final float px,
            final float py,
            final boolean withTarget) {
        if (origRet == null) {
            assertNull(rewrRet, label + " expected null at (" + px + "," + py + ") target=" + withTarget);
        } else {
            assertNotNull(rewrRet, label + " expected non-null at (" + px + "," + py + ") target=" + withTarget);
            assertEquals(
                    Float.floatToRawIntBits(origOut[0]),
                    Float.floatToRawIntBits(rewrOut[0]),
                    label + " x bit mismatch at (" + px + "," + py + ") orig=" + origOut[0] + " rewr=" + rewrOut[0]);
            assertEquals(
                    Float.floatToRawIntBits(origOut[1]),
                    Float.floatToRawIntBits(rewrOut[1]),
                    label + " y bit mismatch at (" + px + "," + py + ") orig=" + origOut[1] + " rewr=" + rewrOut[1]);
        }
    }

    private static float[] buildFoldedGrid(final int col, final int row) {
        final float[] pt = new float[(col + 1) * (row + 1) * COORD_STRIDE];
        for (int r = 0; r <= row; r++) {
            for (int c = 0; c <= col; c++) {
                final int idx = (c + r * (col + 1)) * COORD_STRIDE;
                final float fold = (c == 2 && r == 1) ? -8.0f : 0.0f;
                pt[idx] = c * 10.0f + fold + 0.5f * r;
                pt[idx + 1] = r * 10.0f - 0.25f * c;
            }
        }
        return pt;
    }

    private static void verifyDeterministicCases(final Invocation original, final Invocation rewritten)
            throws Throwable {
        // Pure in-bounds fast path (quad and tri, zero and nonzero offsets/steps).
        final float[] regularGrid = buildGrid(3, 2, 10.0f, 15.0f, 20.0f, -12.0f, false);
        final float[] inBoundsPts = {
            0.0f, 0.0f,
            0.25f, 0.75f,
            0.5f, 0.5f,
            0.9999f, 0.0001f,
            0.1f, 0.2f,
            0.8f, 0.9f
        };
        assertBitParity(original, rewritten, inBoundsPts, 6, 0, COORD_STRIDE, regularGrid, 3, 2, true, false);
        assertBitParity(original, rewritten, inBoundsPts, 6, 0, COORD_STRIDE, regularGrid, 3, 2, false, false);
        assertBitParity(original, rewritten, inBoundsPts, 3, 2, 4, regularGrid, 3, 2, false, true);

        // Edge cases covering every border triangle band, far OOB, NaN, -0.0f, Inf, and nextDown(1.0f).
        final float[] edgePts = {
            -0.0f,
            -0.0f,
            1.0f,
            1.0f,
            Math.nextDown(1.0f),
            0.5f,
            -0.5f,
            -0.5f,
            -0.5f,
            1.5f,
            -0.5f,
            0.4f,
            1.5f,
            -0.5f,
            1.5f,
            1.5f,
            1.5f,
            0.6f,
            0.4f,
            -0.5f,
            0.6f,
            1.5f,
            -2.0f,
            0.5f,
            3.0f,
            0.5f,
            -3.5f,
            4.25f,
            Float.NaN,
            0.25f,
            0.25f,
            Float.NaN,
            Float.POSITIVE_INFINITY,
            0.5f,
            0.0f,
            Math.nextDown(1.0f)
        };
        final int count = edgePts.length / COORD_STRIDE;
        assertBitParity(original, rewritten, edgePts, count, 0, COORD_STRIDE, regularGrid, 3, 2, true, false);
        assertBitParity(original, rewritten, edgePts, count, 0, COORD_STRIDE, regularGrid, 3, 2, false, false);

        // Degenerate 1x1 collapsed grid and empty/inverted iteration bounds.
        final float[] collapsedGrid = buildGrid(1, 1, 0.0f, 0.0f, 0.0f, 0.0f, true);
        assertBitParity(original, rewritten, edgePts, count, 0, COORD_STRIDE, collapsedGrid, 1, 1, true, false);
        assertBitParity(original, rewritten, edgePts, count, 0, COORD_STRIDE, collapsedGrid, 1, 1, false, false);
        assertBitParity(original, rewritten, inBoundsPts, 0, 0, COORD_STRIDE, regularGrid, 3, 2, true, false);
    }

    private static void verifyRandomizedCases(final Invocation original, final Invocation rewritten) throws Throwable {
        final Random rng = new Random(RANDOM_SEED);
        for (int trial = 0; trial < RANDOM_TRIALS; trial++) {
            final int col = 1 + rng.nextInt(5);
            final int row = 1 + rng.nextInt(5);
            final boolean isQuad = rng.nextBoolean();
            final boolean collapsed = trial == 0;
            final float[] grid = buildGrid(
                    col,
                    row,
                    (rng.nextFloat() - 0.5f) * 200.0f,
                    (rng.nextFloat() - 0.5f) * 200.0f,
                    (rng.nextFloat() - 0.5f) * 100.0f,
                    (rng.nextFloat() - 0.5f) * 100.0f,
                    collapsed);
            final int step = COORD_STRIDE + rng.nextInt(3);
            final int numPoint = 40;
            final int offset = rng.nextInt(3);
            final float[] src = new float[numPoint * step + 4];
            for (int p = offset; p < numPoint * step; p += step) {
                src[p] = (rng.nextFloat() * 6.0f) - 2.5f;
                src[p + 1] = (rng.nextFloat() * 6.0f) - 2.5f;
            }
            assertBitParity(original, rewritten, src, numPoint, offset, step, grid, col, row, isQuad, trial % 4 == 0);
        }
    }

    private static void assertBitParity(
            final Invocation original,
            final Invocation rewritten,
            final float[] srcTemplate,
            final int numPoint,
            final int srcOffset,
            final int srcStep,
            final float[] grid,
            final int col,
            final int row,
            final boolean isQuad,
            final boolean aliasInPlace)
            throws Throwable {
        final float[] origSrc = srcTemplate.clone();
        final float[] rewrSrc = srcTemplate.clone();
        final float[] origDst = aliasInPlace ? origSrc : new float[srcTemplate.length];
        final float[] rewrDst = aliasInPlace ? rewrSrc : new float[srcTemplate.length];

        original.invoke(origSrc, origDst, numPoint, srcOffset, srcStep, grid, col, row, isQuad);
        rewritten.invoke(rewrSrc, rewrDst, numPoint, srcOffset, srcStep, grid, col, row, isQuad);

        assertEquals(origDst.length, rewrDst.length);
        for (int idx = 0; idx < origDst.length; idx++) {
            assertEquals(
                    Float.floatToRawIntBits(origDst[idx]),
                    Float.floatToRawIntBits(rewrDst[idx]),
                    "raw bit mismatch at index=" + idx + " isQuad=" + isQuad + " col=" + col + " row=" + row);
        }
    }

    private static float[] buildGrid(
            final int col,
            final int row,
            final float ox,
            final float oy,
            final float sx,
            final float sy,
            final boolean collapsed) {
        final float[] pt = new float[(col + 1) * (row + 1) * COORD_STRIDE];
        if (collapsed) {
            return pt;
        }
        for (int r = 0; r <= row; r++) {
            for (int c = 0; c <= col; c++) {
                final int idx = (c + r * (col + 1)) * COORD_STRIDE;
                pt[idx] = ox + sx * ((float) c / col) + 0.125f * r;
                pt[idx + 1] = oy + sy * ((float) r / row) - 0.125f * c;
            }
        }
        return pt;
    }

    private static Invocation loadInvoker(final ClassLoader loader) throws Exception {
        final Class<?> owner = Class.forName(OWNER_DOT_NAME, true, loader);
        final Field singletonField = owner.getField(WarpGridInterpolationTarget.SINGLETON_FIELD);
        final Object singleton = singletonField.get(null);
        final var method = owner.getMethod(
                WarpGridInterpolationTarget.METHOD,
                float[].class,
                float[].class,
                int.class,
                int.class,
                int.class,
                float[].class,
                int.class,
                int.class,
                boolean.class);
        final MethodHandle handle =
                java.lang.invoke.MethodHandles.lookup().unreflect(method).bindTo(singleton);
        return handle::invokeExact;
    }

    private static Path resolveParityArtifact(final Path root) {
        final Path preferred = root.resolve(PREFERRED_PARITY_VERSION).resolve(CUBISM_JAR);
        if (Files.isRegularFile(preferred)) {
            return preferred;
        }
        for (final String version : ReviewedHostArtifacts.reviewedCubismVersions()) {
            final Path candidate = root.resolve(version).resolve(CUBISM_JAR);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new AssertionError("no reviewed exact artifact found under " + root);
    }

    private static URLClassLoader hostLoader(final Path directory) throws Exception {
        return new URLClassLoader(urlsUnder(directory), WarpGridInterpolationArtifactTest.class.getClassLoader());
    }

    private static URL[] urlsUnder(final Path directory) throws Exception {
        final List<URL> urls = new ArrayList<>();
        try (var files = Files.walk(directory, MAX_WALK_DEPTH)) {
            for (final Path path :
                    files.filter(p -> p.toString().endsWith(JAR_EXTENSION)).toList()) {
                urls.add(path.toUri().toURL());
            }
        }
        return urls.toArray(URL[]::new);
    }

    private static byte[] readClassBytes(final Path artifact, final String owner) throws Exception {
        try (JarFile jar = new JarFile(artifact.toFile())) {
            final var entry = jar.getJarEntry(owner + CLASS_EXTENSION);
            assertNotNull(entry, owner + " absent from " + artifact);
            try (var input = jar.getInputStream(entry)) {
                return input.readAllBytes();
            }
        }
    }

    private static ProtectionDomain protectionDomain(final Path artifact) throws Exception {
        return new ProtectionDomain(new CodeSource(artifact.toUri().toURL(), (Certificate[]) null), null);
    }

    private static void verifyMixedTailCountersAndParity(
            final Invocation original, final Invocation rewritten, final WarpGridInterpolationBridge bridge)
            throws Throwable {
        final float[] grid = buildGrid(2, 2, 5.0f, -3.0f, 12.0f, 8.0f, false);
        final float[] mixed = {0.25f, 0.25f, 1.0f, 0.5f, 0.5f, 0.75f, -0.5f, 1.5f};
        final int count = mixed.length / COORD_STRIDE;

        assertBitParity(original, rewritten, mixed, count, 0, COORD_STRIDE, grid, 2, 2, true, false);
        final Map<String, Long> afterQuad = bridge.snapshot();
        assertEquals(2L, afterQuad.get(STAT_OOB_TAIL_CALLS));
        assertEquals(2L, afterQuad.get(STAT_OOB_POINTS));
        assertEquals(0L, afterQuad.get(STAT_IN_BOUNDS_FALLBACK_POINTS));
        assertEquals(0L, afterQuad.get(STAT_FAILURES));
        assertEquals(1L, afterQuad.get(STAT_BOUND));

        assertBitParity(original, rewritten, mixed, count, 0, COORD_STRIDE, grid, 2, 2, false, false);
        final Map<String, Long> afterTri = bridge.snapshot();
        assertEquals(4L, afterTri.get(STAT_OOB_TAIL_CALLS));
        assertEquals(4L, afterTri.get(STAT_OOB_POINTS));
        assertEquals(0L, afterTri.get(STAT_IN_BOUNDS_FALLBACK_POINTS));
        assertEquals(0L, afterTri.get(STAT_FAILURES));
        assertEquals(1L, afterTri.get(STAT_BOUND));
    }

    private static void verifyNullParity(final Invocation original, final Invocation rewritten) {
        final float[] coords = {0.5f, 0.5f};
        final float[] grid = buildGrid(1, 1, 0.0f, 0.0f, 1.0f, 1.0f, false);
        assertThrows(
                NullPointerException.class,
                () -> original.invoke(null, coords.clone(), 1, 0, COORD_STRIDE, grid, 1, 1, true));
        assertThrows(
                NullPointerException.class,
                () -> rewritten.invoke(null, coords.clone(), 1, 0, COORD_STRIDE, grid, 1, 1, true));
        assertThrows(
                NullPointerException.class,
                () -> rewritten.invoke(coords.clone(), null, 0, 0, COORD_STRIDE, grid, 1, 1, true));
        assertThrows(
                NullPointerException.class,
                () -> rewritten.invoke(coords.clone(), coords.clone(), 1, 0, COORD_STRIDE, null, 1, 1, false));
    }

    private static void assertSingleLookupPrimitiveOobBytecode(
            final byte[] originalBytes, final byte[] rewrittenBytes, final String version) {
        final BytecodeProfile original = BytecodeProfile.scan(originalBytes);
        final BytecodeProfile rewritten = BytecodeProfile.scan(rewrittenBytes);
        assertEquals(1, rewritten.propertyLookups, version + ": expected single Properties.get lookup");
        assertEquals(1, rewritten.invokeExactCalls, version + ": expected single MethodHandle.invokeExact");
        assertEquals(0, rewritten.consumerCalls, version + ": unexpected Consumer call");
        assertEquals(0, rewritten.atomicBooleanCalls, version + ": unexpected AtomicBoolean call");
        assertEquals(0, rewritten.boxingCalls, version + ": unexpected Integer/Boolean boxing");
        assertEquals(original.objectArrayAllocs, rewritten.objectArrayAllocs, version + ": added Object[] allocation");
    }

    private static final class BytecodeProfile extends ClassVisitor {
        private int propertyLookups;
        private int invokeExactCalls;
        private int consumerCalls;
        private int atomicBooleanCalls;
        private int boxingCalls;
        private int objectArrayAllocs;

        private BytecodeProfile() {
            super(Opcodes.ASM9);
        }

        private static BytecodeProfile scan(final byte[] classBytes) {
            final BytecodeProfile profile = new BytecodeProfile();
            new ClassReader(classBytes).accept(profile, 0);
            return profile;
        }

        @Override
        public MethodVisitor visitMethod(
                final int access,
                final String name,
                final String descriptor,
                final String signature,
                final String[] exceptions) {
            if (!WarpGridInterpolationTarget.METHOD.equals(name)
                    || !WarpGridInterpolationTarget.DESCRIPTOR.equals(descriptor)) {
                return null;
            }
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public void visitMethodInsn(
                        final int opcode,
                        final String owner,
                        final String methodName,
                        final String methodDesc,
                        final boolean isInterface) {
                    if (PROPERTIES_INTERNAL.equals(owner) && PROPERTIES_GET_METHOD.equals(methodName)) {
                        propertyLookups++;
                    } else if (METHOD_HANDLE_INTERNAL.equals(owner)
                            && INVOKE_EXACT_METHOD.equals(methodName)
                            && WarpGridInterpolationTarget.OOB_RUN_DESCRIPTOR.equals(methodDesc)) {
                        invokeExactCalls++;
                    } else if (CONSUMER_INTERNAL.equals(owner)) {
                        consumerCalls++;
                    } else if (ATOMIC_BOOLEAN_INTERNAL.equals(owner)) {
                        atomicBooleanCalls++;
                    } else if ((INTEGER_INTERNAL.equals(owner) || BOOLEAN_INTERNAL.equals(owner))
                            && VALUE_OF_METHOD.equals(methodName)) {
                        boxingCalls++;
                    }
                }

                @Override
                public void visitLdcInsn(final Object value) {
                    if (value instanceof ConstantDynamic condy && isPropertiesLookupCondy(condy)) {
                        propertyLookups++;
                    }
                }

                @Override
                public void visitTypeInsn(final int opcode, final String type) {
                    if (opcode == Opcodes.ANEWARRAY) {
                        objectArrayAllocs++;
                    }
                }
            };
        }

        private static boolean isPropertiesLookupCondy(final ConstantDynamic condy) {
            for (int i = 0; i < condy.getBootstrapMethodArgumentCount(); i++) {
                final Object arg = condy.getBootstrapMethodArgument(i);
                if (arg instanceof Handle handle
                        && PROPERTIES_INTERNAL.equals(handle.getOwner())
                        && PROPERTIES_GET_METHOD.equals(handle.getName())) {
                    return true;
                }
            }
            return false;
        }
    }

    private static Path resolveVersionArtifact(final Path root, final String version) {
        final Path candidate = root.resolve(version).resolve(CUBISM_JAR);
        if (Files.isRegularFile(candidate)) {
            return candidate;
        }
        if (CUBISM_5305_VERSION.equals(version)) {
            final Path win5305 = Path.of(
                    System.getProperty("user.home"),
                    ARCH_REFERENCE_SUFFIX,
                    CUBISM_5305_VERSION,
                    PROTON_5305_PREFIX,
                    CUBISM_JAR);
            if (Files.isRegularFile(win5305)) {
                return win5305;
            }
        }
        return null;
    }

    private static void writePatchedClass(final Path outDir, final String owner, final byte[] bytes)
            throws IOException {
        final Path outFile = outDir.resolve(owner + CLASS_EXTENSION);
        Files.createDirectories(outFile.getParent());
        Files.write(outFile, bytes);
    }

    private static void runStrictVerifySubprocess(final Path manifestFile, final int expectedVerifiedCount)
            throws Exception {
        final String javaExe =
                Path.of(System.getProperty("java.home"), "bin", "java").toString();
        final String classpath = System.getProperty("java.class.path");
        final Process process = new ProcessBuilder(
                        javaExe,
                        XVERIFY_ALL_FLAG,
                        "-cp",
                        classpath,
                        StrictChildVerifier.class.getName(),
                        manifestFile.toString(),
                        Integer.toString(expectedVerifiedCount))
                .redirectErrorStream(true)
                .start();
        final String output;
        try (InputStream stream = process.getInputStream()) {
            output = new String(stream.readAllBytes());
        }
        assertTrue(process.waitFor(VERIFY_TIMEOUT_SECONDS, TimeUnit.SECONDS), "Strict -Xverify:all timed out");
        assertEquals(0, process.exitValue(), "Strict -Xverify:all failed:\n" + output);
    }

    /** Child entrypoint launched under {@code -Xverify:all} to link all rewritten warp-grid host classes. */
    public static final class StrictChildVerifier {
        private StrictChildVerifier() {}

        /** Loads and links every patched class listed in the manifest under strict verification. */
        public static void main(final String[] args) throws Exception {
            final List<String> lines = Files.readAllLines(Path.of(args[0]));
            final int expectedCount = Integer.parseInt(args[1]);
            String currentTag = null;
            PatchedJarLoader currentLoader = null;
            int verifiedCount = 0;
            try {
                for (final String line : lines) {
                    if (line.isBlank()) {
                        continue;
                    }
                    final String[] parts = line.split(TAB);
                    final String tag = parts[0];
                    final Path jarPath = Path.of(parts[1]);
                    final Path patchRoot = Path.of(parts[2]);
                    final String internalName = parts[3];
                    if (!tag.equals(currentTag)) {
                        if (currentLoader != null) {
                            currentLoader.close();
                        }
                        currentTag = tag;
                        currentLoader =
                                new PatchedJarLoader(jarPath, patchRoot, StrictChildVerifier.class.getClassLoader());
                    }
                    final Class<?> defined = Class.forName(internalName.replace('/', '.'), false, currentLoader);
                    defined.getDeclaredMethods();
                    verifiedCount++;
                }
            } finally {
                if (currentLoader != null) {
                    currentLoader.close();
                }
            }
            if (verifiedCount != expectedCount) {
                throw new IllegalStateException("Unexpected verified count: " + verifiedCount + " != " + expectedCount);
            }
        }
    }

    private static final class PatchedJarLoader extends URLClassLoader {
        private final JarFile hostJar;
        private final Path patchRoot;
        private final ClassLoader fallbackParent;

        private PatchedJarLoader(final Path jarPath, final Path patchRoot, final ClassLoader fallbackParent)
                throws Exception {
            super(siblingUrls(jarPath), ClassLoader.getPlatformClassLoader());
            this.hostJar = new JarFile(jarPath.toFile(), false);
            this.patchRoot = patchRoot;
            this.fallbackParent = fallbackParent;
        }

        private static URL[] siblingUrls(final Path jarPath) throws Exception {
            final List<URL> urls = new ArrayList<>();
            try (var files = Files.walk(jarPath.getParent(), MAX_WALK_DEPTH)) {
                for (final Path file : files.toList()) {
                    if (Files.isRegularFile(file) && file.toString().endsWith(JAR_EXTENSION) && !file.equals(jarPath)) {
                        urls.add(file.toUri().toURL());
                    }
                }
            }
            return urls.toArray(URL[]::new);
        }

        @Override
        protected Class<?> findClass(final String name) throws ClassNotFoundException {
            final String internal = name.replace('.', '/');
            final Path patchedFile = patchRoot.resolve(internal + CLASS_EXTENSION);
            if (Files.isRegularFile(patchedFile)) {
                try {
                    final byte[] bytes = Files.readAllBytes(patchedFile);
                    return defineClass(name, bytes, 0, bytes.length);
                } catch (final IOException failure) {
                    throw new ClassNotFoundException(name, failure);
                }
            }
            final JarEntry entry = hostJar.getJarEntry(internal + CLASS_EXTENSION);
            if (entry != null) {
                try (InputStream in = hostJar.getInputStream(entry)) {
                    final byte[] bytes = in.readAllBytes();
                    return defineClass(name, bytes, 0, bytes.length);
                } catch (final IOException failure) {
                    throw new ClassNotFoundException(name, failure);
                }
            }
            try {
                return super.findClass(name);
            } catch (final ClassNotFoundException ignored) {
                return fallbackParent.loadClass(name);
            }
        }

        @Override
        public void close() throws IOException {
            hostJar.close();
            super.close();
        }
    }

    private static Path referenceRoot() {
        final String configured =
                System.getProperty(REF_DIR_PROPERTY, System.getenv().getOrDefault(REF_DIR_ENV, ""));
        assumeTrue(!configured.isBlank(), "exact Cubism reference directory not configured");
        final Path root = Path.of(configured);
        assumeTrue(Files.isDirectory(root), "reference directory absent: " + root);
        return root;
    }

    @FunctionalInterface
    private interface Invocation {
        void invoke(
                float[] src,
                float[] dst,
                int numPoint,
                int srcOffset,
                int srcStep,
                float[] pt,
                int col,
                int row,
                boolean isQuad)
                throws Throwable;
    }

    private static final class RewrittenHostClassLoader extends URLClassLoader {
        private final Map<String, OverrideEntry> overrides = new HashMap<>();

        private RewrittenHostClassLoader(final URL[] urls, final ClassLoader parent) {
            super(urls, parent);
        }

        private void installOverride(final String name, final byte[] bytes, final ProtectionDomain domain) {
            overrides.put(name, new OverrideEntry(bytes, domain));
        }

        @Override
        protected Class<?> findClass(final String name) throws ClassNotFoundException {
            final OverrideEntry entry = overrides.remove(name);
            if (entry != null) {
                return defineClass(name, entry.bytes(), 0, entry.bytes().length, entry.domain());
            }
            return super.findClass(name);
        }

        private record OverrideEntry(byte[] bytes, ProtectionDomain domain) {}
    }
}
