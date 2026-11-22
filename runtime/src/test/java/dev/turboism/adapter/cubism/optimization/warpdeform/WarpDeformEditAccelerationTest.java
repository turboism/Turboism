package dev.turboism.adapter.cubism.optimization.warpdeform;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

/**
 * Unit, bytecode-admission, live runtime parity, and overlay dirty-gating tests for edit-mode warp acceleration.
 */
class WarpDeformEditAccelerationTest {

    private static final String CLASS_EXTENSION = ".class";
    private static final String TEST_JAR_PROPERTY = "turboism.test.cubismEditorJar";
    private static final Path LOCAL_ARTIFACTS_ROOT =
            Path.of(System.getProperty("user.home"), "cubism-host-artifacts");
    private static final Path LOCAL_REF_5305 = Path.of(
            System.getProperty("user.home"),
            ".local",
            "share",
            "cubism-reference",
            "5.3.05",
            "proton",
            "pfx",
            "drive_c",
            "Program Files",
            "Live2D Cubism 5.3",
            "app",
            "lib",
            "Live2D_Cubism.jar");

    @Test
    void warpDeformMathInverseAndBezierLatticeMatchReferenceFormulas() {
        final float[] uv = new float[2];
        assertTrue(WarpDeformMath.solveBilinearQuad(0, 0, 1, 0, 0, 1, 1, 1, 0.25, 0.75, uv));
        assertEquals(0.25f, uv[0], 1e-5f);
        assertEquals(0.75f, uv[1], 1e-5f);
        assertFalse(WarpDeformMath.solveBilinearQuad(0, 0, 1, 0, 0, 1, 1, 1, 1.5, 0.5, uv));

        // Perspective-sheared quad inversion round-trip.
        final double x0 = 10, y0 = 20, x1 = 110, y1 = 30, x2 = 20, y2 = 140, x3 = 150, y3 = 165;
        for (int i = 1; i <= 4; i++) {
            for (int j = 1; j <= 4; j++) {
                final double u = i * 0.2;
                final double v = j * 0.2;
                final double px = (1 - u) * (1 - v) * x0 + u * (1 - v) * x1 + (1 - u) * v * x2 + u * v * x3;
                final double py = (1 - u) * (1 - v) * y0 + u * (1 - v) * y1 + (1 - u) * v * y2 + u * v * y3;
                assertTrue(WarpDeformMath.solveBilinearQuad(x0, y0, x1, y1, x2, y2, x3, y3, px, py, uv));
                assertEquals((float) u, uv[0], 1e-4f);
                assertEquals((float) v, uv[1], 1e-4f);
            }
        }

        // Flat Bezier patch corners evaluate linearly across [10..110, 20..120].
        final float ax = 10, ay = 20, bx = 110, by = 120;
        final float dx = (bx - ax) / 3f, dy = (by - ay) / 3f;
        final int packed = WarpDeformMath.computeCellDivisions(
                ax, ay, ax + dx, ay, ax, ay + dy,
                bx, ay, bx - dx, ay, bx, ay + dy,
                ax, by, ax + dx, by, ax, by - dy,
                bx, by, bx - dx, by, bx, by - dy,
                5, 8, 128);
        final int divX = packed >>> 16;
        final int divY = packed & 0xFFFF;
        assertEquals(8, divX);
        assertEquals(8, divY);
        final float[] lattice = WarpDeformMath.buildPatchCornerLattice(
                ax, ay, ax + dx, ay, ax, ay + dy,
                bx, ay, bx - dx, ay, bx, ay + dy,
                ax, by, ax + dx, by, ax, by - dy,
                bx, by, bx - dx, by, bx, by - dy,
                divX, divY);
        final float[] out = new float[2];
        WarpDeformMath.evaluatePatchLatticePoint(lattice, divX, divY, 0.35f, 0.65f, out, 0);
        assertEquals(ax + (bx - ax) * 0.35f, out[0], 1e-3f);
        assertEquals(ay + (by - ay) * 0.65f, out[1], 1e-3f);
    }

    @Test
    void weakIdentityCacheUsesReferenceIdentityAndClearsCleanly() {
        final WeakIdentityCache<List<String>, String> cache = new WeakIdentityCache<>();
        final List<String> a = new ArrayList<>();
        final List<String> b = new ArrayList<>();
        assertEquals("first", cache.getOrCompute(a, () -> "first"));
        assertEquals("second", cache.getOrCompute(b, () -> "second"));
        assertEquals("first", cache.get(a));
        assertEquals("second", cache.get(b));
        cache.clear();
        assertNull(cache.get(a));
        assertNull(cache.get(b));
    }

    @Test
    void transformsAndDefinesBytecodeAcrossAllAvailableReviewedCubismJars() throws Exception {
        final List<Path> jars = availableReviewedJars();
        assumeTrue(!jars.isEmpty(), "no reviewed Cubism Editor JARs found for matrix verification");

        for (final Path jar : jars) {
            final HostArtifactDigest digest = HostArtifactDigest.cached(jar);
            final WarpDeformEditTarget target = WarpDeformEditTarget.of(digest)
                    .orElseThrow(() -> new AssertionError("missing target table entry for " + jar));
            final Map<String, byte[]> refs = readTargetBytes(jar, target.targetClasses());
            final ProtectionDomain validDomain = protectionDomain(jar);
            final ProtectionDomain wrongDomain = protectionDomain(Path.of("/tmp/wrong-source.jar"));

            final URL[] urls = hostUrls(jar);
            final Map<String, byte[]> rewrittenMap = new LinkedHashMap<>();
            try (RewrittenHostClassLoader transformLoader =
                    new RewrittenHostClassLoader(jar, urls, getClass().getClassLoader())) {
                final WarpDeformEditTransformer transformer =
                        new WarpDeformEditTransformer(transformLoader, jar, refs, target);

                // Wrong loader & wrong codeSource fail closed.
                final String sampleOwner = target.targetClasses().get(0);
                assertNull(transformer.transform(
                        null, getClass().getClassLoader(), sampleOwner, null, validDomain, refs.get(sampleOwner)));
                assertNull(transformer.transform(
                        null, transformLoader, sampleOwner, null, wrongDomain, refs.get(sampleOwner)));
                assertNotNull(transformer.failure());

                final WarpDeformEditTransformer cleanTransformer =
                        new WarpDeformEditTransformer(transformLoader, jar, refs, target);
                for (final String internalName : target.targetClasses()) {
                    final byte[] rewritten = cleanTransformer.transform(
                            null, transformLoader, internalName, null, validDomain, refs.get(internalName));
                    assertNotNull(
                            rewritten,
                            target.version() + " failed " + internalName + ": " + cleanTransformer.failure());
                    assertNull(cleanTransformer.failure());
                    assertEquals(
                            cleanTransformer.expectedMatchesFor(internalName),
                            cleanTransformer.matchesFor(internalName),
                            target.version() + " match count mismatch in " + internalName);
                    rewrittenMap.put(internalName.replace('/', '.'), rewritten);
                }
                assertEquals(cleanTransformer.expectedTotalSites(), cleanTransformer.sites(), target.version());
            }

            try (RewrittenHostClassLoader rewrittenLoader =
                    new RewrittenHostClassLoader(jar, urls, getClass().getClassLoader())) {
                rewrittenLoader.installOverrides(rewrittenMap, validDomain);

                // Force JVM verification & handle resolution on rewritten classes in this artifact.
                for (final String internalName : target.targetClasses()) {
                    Class.forName(internalName.replace('/', '.'), false, rewrittenLoader);
                }
                assertEquals(Map.of(), rewrittenLoader.remainingOverrides(), target.version());
                try (WarpDeformEditBridge bridge = new WarpDeformEditBridge(rewrittenLoader)) {
                    bridge.install();
                }
            }
        }
    }

    @Test
    void verifiesLiveExecutionParityAndOverlayDirtyGatingOnCubismJar() throws Throwable {
        final List<Path> parityJars = parityCandidateJars();
        assumeTrue(!parityJars.isEmpty(), "no Cubism Editor JAR supplied for live runtime parity test");

        for (final Path jar : parityJars) {
            verifyRuntimeParityForJar(jar);
        }
    }

    private void verifyRuntimeParityForJar(final Path jar) throws Throwable {
        final HostArtifactDigest digest = HostArtifactDigest.cached(jar);
        final WarpDeformEditTarget target = WarpDeformEditTarget.of(digest).orElseThrow();
        final Map<String, byte[]> refs = readTargetBytes(jar, target.targetClasses());
        final ProtectionDomain domain = protectionDomain(jar);
        final URL[] urls = hostUrls(jar);

        final Map<String, byte[]> overrides = new LinkedHashMap<>();
        try (RewrittenHostClassLoader transformLoader =
                new RewrittenHostClassLoader(jar, urls, getClass().getClassLoader())) {
            final WarpDeformEditTransformer transformer =
                    new WarpDeformEditTransformer(transformLoader, jar, refs, target);
            for (final String internalName : target.targetClasses()) {
                final byte[] out = transformer.transform(
                        null, transformLoader, internalName, null, domain, refs.get(internalName));
                assertNotNull(out, transformer.failure());
                overrides.put(internalName.replace('/', '.'), out);
            }
        }

        try (RewrittenHostClassLoader origLoader =
                        new RewrittenHostClassLoader(jar, urls, getClass().getClassLoader());
                RewrittenHostClassLoader rewLoader =
                        new RewrittenHostClassLoader(jar, urls, getClass().getClassLoader())) {
            origLoader.installOverrides(Map.of(), domain);
            rewLoader.installOverrides(overrides, domain);

            try (WarpDeformEditBridge bridge = new WarpDeformEditBridge(rewLoader)) {
                final float[] gridPos = {
                    0, 0, 50, 2, 100, 0,
                    -2, 50, 51, 52, 102, 50,
                    0, 100, 49, 101, 100, 100
                };
                // Exercise unpatched draw before installing bridge slots so origLoader stays isolated.
                final DrawResult origRes = exerciseWarpDraw(origLoader, gridPos, false);

                bridge.install();

                verifyFormReinitParityAndReuse(origLoader, rewLoader);
                verifySimpleGridAndTransformParity(origLoader, rewLoader);
                verifyBezierPointsParity(origLoader, rewLoader);
                verifyWarpDrawParityAndDirtyGating(origRes, rewLoader, gridPos, bridge);
                verifyActionFloatLoopParity(origLoader, rewLoader, target);

                final Map<String, Long> stats = bridge.snapshot();
                assertEquals(1L, stats.get("active"), () -> "jar=" + jar + " stats=" + stats);
                assertEquals(0L, stats.get("failures"), () -> "jar=" + jar + " stats=" + stats);
                assertTrue(stats.get("get01ExQuadCalls") > 0L, () -> "jar=" + jar + " stats=" + stats);
                assertTrue(stats.get("inverseQuadCalls") > 0L, () -> "jar=" + jar + " stats=" + stats);
                assertTrue(stats.get("bezierPointsCalls") > 0L, () -> "jar=" + jar + " stats=" + stats);
                assertTrue(stats.get("overlayUploadsElided") > 0L, () -> "jar=" + jar + " stats=" + stats);
                assertTrue(stats.get("selectedUploadElided") > 0L, () -> "jar=" + jar + " stats=" + stats);
            }
            assertNull(System.getProperties().get(WarpDeformEditBridge.ACTIVE_PROPERTY));
            // Verify fallback works cleanly after bridge close.
            verifyFormReinitFallbackAfterClose(rewLoader);
        }
    }

    private static Object[] createInitializedForms(final ClassLoader loader, final Class<?> rewFormCls)
            throws Exception {
        final Class<?> srcCls = Class.forName(WarpDeformEditTarget.WARP_SOURCE_OWNER.replace('/', '.'), true, loader);
        final Class<?> defCls = Class.forName(WarpDeformEditTarget.WARP_DEFORMER_OWNER.replace('/', '.'), true, loader);
        final Class<?> coordCls = Class.forName("com.live2d.doc.CoordType", true, loader);
        final Object coordType = coordCls.getMethod("b").invoke(null);
        final Object src = srcCls.getConstructor().newInstance();
        final Constructor<?> formCtor = rewFormCls.getConstructor(srcCls, defCls, coordCls);
        return new Object[] {formCtor.newInstance(src, null, coordType), formCtor.newInstance(src, null, coordType)};
    }

    private static void verifyFormReinitParityAndReuse(
            final ClassLoader origLoader, final ClassLoader rewLoader) throws Exception {
        final Class<?> rewFormCls = Class.forName(WarpDeformEditTarget.WARP_FORM_OWNER.replace('/', '.'), true, rewLoader);
        final Object[] forms = createInitializedForms(rewLoader, rewFormCls);
        final Object a = forms[0];
        final Object b = forms[1];
        final Method setPositions = rewFormCls.getMethod("setPositions", float[].class);
        final Method getPositions = rewFormCls.getMethod("getPositions");
        final Method reinit = rewFormCls.getMethod(
                "reinit",
                Class.forName("com.live2d.core.ICopyable", false, rewLoader),
                Class.forName("com.live2d.core.a", false, rewLoader));
        final Method deepCopy = rewFormCls.getMethod("deepCopy", Class.forName("com.live2d.core.a", false, rewLoader));
        final Object copyParam = Class.forName("com.live2d.core.a", true, rewLoader).getConstructor().newInstance();

        final float[] srcArr1 = {0, 0, 10, 0, 0, 10, 10, 10};
        final float[] srcArr2 = {1, 2, 12, 3, -1, 14, 15, 16};
        final float[] diffLenArr = {1, 2, 3, 4};

        setPositions.invoke(a, srcArr1.clone());
        setPositions.invoke(b, new float[srcArr1.length]);
        final float[] originalDstBuffer = (float[]) getPositions.invoke(b);

        reinit.invoke(b, a, copyParam);
        assertSame(originalDstBuffer, getPositions.invoke(b), "same-length distinct array must reuse buffer");
        assertArrayEquals(srcArr1, (float[]) getPositions.invoke(b), 0.0f);

        setPositions.invoke(a, srcArr2);
        reinit.invoke(b, a, copyParam);
        assertSame(originalDstBuffer, getPositions.invoke(b));
        assertArrayEquals(srcArr2, (float[]) getPositions.invoke(b), 0.0f);

        // Mutating src after reinit must never affect b, and deepCopy must never alias.
        srcArr2[0] = 999f;
        assertEquals(1f, ((float[]) getPositions.invoke(b))[0], 0.0f);
        final Object copy = deepCopy.invoke(b, copyParam);
        assertNotSame(getPositions.invoke(b), getPositions.invoke(copy));
        ((float[]) getPositions.invoke(copy))[0] = -888f;
        assertEquals(1f, ((float[]) getPositions.invoke(b))[0], 0.0f);

        // Different length allocates matching buffer.
        setPositions.invoke(a, diffLenArr);
        reinit.invoke(b, a, copyParam);
        assertNotSame(originalDstBuffer, getPositions.invoke(b));
        assertArrayEquals(diffLenArr, (float[]) getPositions.invoke(b), 0.0f);
    }

    private static void verifyFormReinitFallbackAfterClose(final ClassLoader rewLoader) throws Exception {
        final Class<?> rewFormCls = Class.forName(WarpDeformEditTarget.WARP_FORM_OWNER.replace('/', '.'), true, rewLoader);
        final Object[] forms = createInitializedForms(rewLoader, rewFormCls);
        final Object a = forms[0];
        final Object b = forms[1];
        final Method setPositions = rewFormCls.getMethod("setPositions", float[].class);
        final Method getPositions = rewFormCls.getMethod("getPositions");
        final Method reinit = rewFormCls.getMethod(
                "reinit",
                Class.forName("com.live2d.core.ICopyable", false, rewLoader),
                Class.forName("com.live2d.core.a", false, rewLoader));
        final Object copyParam = Class.forName("com.live2d.core.a", true, rewLoader).getConstructor().newInstance();

        setPositions.invoke(a, new float[] {5, 6, 7, 8});
        final float[] oldB = new float[4];
        setPositions.invoke(b, oldB);
        reinit.invoke(b, a, copyParam);
        assertNotSame(oldB, getPositions.invoke(b), "closed bridge must fall back to Arrays.copyOf");
        assertArrayEquals(new float[] {5, 6, 7, 8}, (float[]) getPositions.invoke(b), 0.0f);
    }

    private static void verifySimpleGridAndTransformParity(
            final ClassLoader origLoader, final ClassLoader rewLoader) throws Exception {
        final float[] quadPts = {
            0, 0, 50, 5, 100, 0,
            -5, 50, 52, 55, 105, 50,
            0, 100, 48, 105, 110, 110
        };
        final float[][] testCoords = {{25f, 25f}, {75f, 80f}, {0.5f, 0.5f}, {52f, 55f}, {-50f, 50f}, {200f, 200f}};

        for (final float[] pt : testCoords) {
            final float[] o01 = invokeGet01ExQuad(origLoader, 2, 2, quadPts, pt[0], pt[1], null);
            final float[] r01 = invokeGet01ExQuad(rewLoader, 2, 2, quadPts, pt[0], pt[1], null);
            if (o01 == null) {
                assertNull(r01);
            } else {
                assertNotNull(r01);
                assertArrayEquals(o01, r01, 1e-4f);
            }
            final float[] oTgt = invokeGet01ExQuad(origLoader, 2, 2, quadPts, pt[0], pt[1], new float[] {0.4f, 0.6f});
            final float[] rTgt = invokeGet01ExQuad(rewLoader, 2, 2, quadPts, pt[0], pt[1], new float[] {0.4f, 0.6f});
            if (oTgt == null) {
                assertNull(rTgt);
            } else {
                assertNotNull(rTgt);
                assertArrayEquals(oTgt, rTgt, 1e-4f);
            }
        }

        // CGridTransform.inverseTransform_3_3_Quad + isInside + CWarpDeformer.transformCanvasToLocal.
        for (final float[] pt : new float[][] {{25f, 25f}, {75f, 80f}}) {
            final float[] oInv = invokeInverseQuad(origLoader, 2, 2, quadPts, pt[0], pt[1]);
            final float[] rInv = invokeInverseQuad(rewLoader, 2, 2, quadPts, pt[0], pt[1]);
            assertArrayEquals(oInv, rInv, 1e-4f);
        }
        assertEquals(
                invokeGridIsInside(origLoader, 2, 2, quadPts, 25f, 25f, true),
                invokeGridIsInside(rewLoader, 2, 2, quadPts, 25f, 25f, true));
        assertEquals(
                invokeGridIsInside(origLoader, 2, 2, quadPts, 300f, 300f, true),
                invokeGridIsInside(rewLoader, 2, 2, quadPts, 300f, 300f, true));

        final float[] canvasIn = {25f, 25f, 75f, 80f, 52f, 55f};
        final float[] origLocal = invokeCanvasToLocal(origLoader, 2, 2, true, quadPts, canvasIn, 3);
        final float[] rewLocal = invokeCanvasToLocal(rewLoader, 2, 2, true, quadPts, canvasIn, 3);
        assertArrayEquals(origLocal, rewLocal, 1e-4f);
    }

    private static void verifyBezierPointsParity(final ClassLoader origLoader, final ClassLoader rewLoader)
            throws Exception {
        final float[] origPts = invokeCreatePointsOnBezier(origLoader, 17, 13);
        final float[] rewPts = invokeCreatePointsOnBezier(rewLoader, 17, 13);
        assertEquals(origPts.length, rewPts.length);
        assertArrayEquals(origPts, rewPts, 1e-3f);
    }

    private static void verifyWarpDrawParityAndDirtyGating(
            final DrawResult origRes, final ClassLoader rewLoader, final float[] gridPos, final WarpDeformEditBridge bridge)
            throws Exception {
        final DrawResult rewFirst = exerciseWarpDraw(rewLoader, gridPos, true);

        assertArrayEquals(origRes.whitePos, rewFirst.whitePos, 1e-5f);
        assertArrayEquals(origRes.whiteIdx, rewFirst.whiteIdx);
        assertArrayEquals(origRes.blackPos, rewFirst.blackPos, 1e-5f);
        assertArrayEquals(origRes.majorIdx, rewFirst.majorIdx);
        assertArrayEquals(origRes.detailIdx, rewFirst.detailIdx);
        assertArrayEquals(origRes.selectedColors, rewFirst.selectedColors, 1e-5f);
        assertEquals(26, rewFirst.softPos.length);
        assertEquals(48, rewFirst.softIdx.length);
        assertEquals(52, rewFirst.softColors.length);
        // Point 0 has weight 1.0 * alpha 0.8 = 0.8; point 4 (center) has weight 0.5 * 0.8 = 0.4.
        assertEquals(0.8f, rewFirst.softColors[3], 1e-5f);
        assertEquals(0.2f * 0.8f, rewFirst.softColors[0], 1e-5f);
        // Center cell 0 (mesh index 3) accumulates 0.8/4 + 0.4/4 = 0.3f alpha.
        assertEquals(0.3f, rewFirst.softColors[3 * 4 + 3], 1e-5f);

        // Second draw with unchanged arrays must elide uploads cleanly.
        final DrawResult rewSecond = exerciseWarpDrawOnExisting(rewFirst.draw, rewLoader, gridPos, true);
        assertTrue(bridge.snapshot().get("overlayUploadsElided") > 0L);
        assertTrue(bridge.snapshot().get("selectedUploadElided") > 0L);
        assertArrayEquals(rewFirst.softColors, rewSecond.softColors, 1e-5f);

        // Mutating gridPos in place must invalidate snapshot and update overlays immediately.
        final float[] mutated = gridPos.clone();
        mutated[0] = 7.5f;
        System.arraycopy(mutated, 0, gridPos, 0, mutated.length);
        final DrawResult rewThird = exerciseWarpDrawOnExisting(rewFirst.draw, rewLoader, gridPos, true);
        assertEquals(7.5f, rewThird.whitePos[0], 1e-5f);
        assertEquals(7.5f, rewThird.softPos[0], 1e-5f);
    }

    private static void verifyActionFloatLoopParity(
            final ClassLoader origLoader, final ClassLoader rewLoader, final WarpDeformEditTarget target)
            throws Exception {
        for (final String internalName : target.targetClasses()) {
            if (!internalName.startsWith(WarpDeformEditTarget.ACTION_PACKAGE_PREFIX)) {
                continue;
            }
            final String clsName = internalName.replace('/', '.');
            final Class<?> origCls = Class.forName(clsName, true, origLoader);
            final Class<?> rewCls = Class.forName(clsName, true, rewLoader);
            for (final Method m : rewCls.getDeclaredMethods()) {
                if ("a".equals(m.getName()) && m.getParameterCount() == 1 && m.getParameterTypes()[0] == List.class) {
                    final List<Object> vecsOrig = buildGVector2List(origLoader, 12);
                    final List<Object> vecsRew = buildGVector2List(rewLoader, 12);
                    final float[] expected = (float[]) invokeDeclared(origCls, "a", List.class, vecsOrig);
                    final float[] actual = (float[]) invokeDeclared(rewCls, "a", List.class, vecsRew);
                    assertArrayEquals(expected, actual, 0.0f);
                }
            }
        }
    }

    private static Object invokeDeclared(
            final Class<?> cls, final String method, final Class<?> paramType, final Object arg) throws Exception {
        final Method m = cls.getDeclaredMethod(method, paramType);
        m.setAccessible(true);
        Object target = null;
        if (Modifier.isStatic(m.getModifiers())) {
            return m.invoke(null, arg);
        }
        try {
            final Field singleton = cls.getDeclaredField("a");
            if (Modifier.isStatic(singleton.getModifiers()) && cls.isAssignableFrom(singleton.getType())) {
                singleton.setAccessible(true);
                target = singleton.get(null);
            }
        } catch (final NoSuchFieldException ignored) {
            // Fall through to zero-argument constructor below.
        }
        if (target == null) {
            final Constructor<?> ctor = cls.getDeclaredConstructor();
            ctor.setAccessible(true);
            target = ctor.newInstance();
        }
        return m.invoke(target, arg);
    }

    private static List<Object> buildGVector2List(final ClassLoader loader, final int count) throws Exception {
        final Class<?> vecCls = Class.forName(WarpDeformEditTarget.GVECTOR2_OWNER.replace('/', '.'), true, loader);
        final Constructor<?> ctor = vecCls.getConstructor(float.class, float.class);
        final List<Object> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(ctor.newInstance(i * 1.25f - 3f, i * 2.5f + 1f));
        }
        return list;
    }

    private static float[] invokeGet01ExQuad(
            final ClassLoader loader,
            final int col,
            final int row,
            final float[] pts,
            final float x,
            final float y,
            final float[] targetXY)
            throws Exception {
        final Class<?> sgCls = Class.forName(WarpDeformEditTarget.SIMPLE_GRID_OWNER.replace('/', '.'), true, loader);
        final Class<?> vCls = Class.forName(WarpDeformEditTarget.GVECTOR2_OWNER.replace('/', '.'), true, loader);
        final Object sg = sgCls.getConstructor(int.class, int.class, float[].class).newInstance(col, row, pts);
        final Object target = targetXY == null
                ? null
                : vCls.getConstructor(float.class, float.class).newInstance(targetXY[0], targetXY[1]);
        final Method m = sgCls.getMethod("get01_ex_quad", float.class, float.class, vCls, float[].class);
        return (float[]) m.invoke(sg, x, y, target, new float[2]);
    }

    private static float[] invokeInverseQuad(
            final ClassLoader loader, final int col, final int row, final float[] pts, final float x, final float y)
            throws Exception {
        final Class<?> sgCls = Class.forName(WarpDeformEditTarget.SIMPLE_GRID_OWNER.replace('/', '.'), true, loader);
        final Class<?> gtCls = Class.forName(WarpDeformEditTarget.GRID_TRANSFORM_OWNER.replace('/', '.'), true, loader);
        final Class<?> rectCls = Class.forName(WarpDeformEditTarget.GRECTF_OWNER.replace('/', '.'), true, loader);
        final Class<?> icGrid = Class.forName(
                "com.live2d.cubism.doc.model.deformer.warp.grid.ICGrid", true, loader);
        final Class<?> vCls = Class.forName(WarpDeformEditTarget.GVECTOR2_OWNER.replace('/', '.'), true, loader);
        final Object sg = sgCls.getConstructor(int.class, int.class, float[].class).newInstance(col, row, pts);
        final Object rect = rectCls.getConstructor(float.class, float.class, float.class, float.class)
                .newInstance(0f, 0f, 100f, 200f);
        final Object gt = gtCls.getConstructor(rectCls, icGrid, int.class, int.class).newInstance(rect, sg, col, row);
        final Object src = vCls.getConstructor(float.class, float.class).newInstance(x, y);
        final Object dst = vCls.getConstructor().newInstance();
        gtCls.getMethod("inverseTransform_3_3_Quad", vCls, vCls).invoke(gt, src, dst);
        return new float[] {(float) vCls.getMethod("getX").invoke(dst), (float) vCls.getMethod("getY").invoke(dst)};
    }

    private static boolean invokeGridIsInside(
            final ClassLoader loader,
            final int col,
            final int row,
            final float[] pts,
            final float x,
            final float y,
            final boolean isDst)
            throws Exception {
        final Class<?> sgCls = Class.forName(WarpDeformEditTarget.SIMPLE_GRID_OWNER.replace('/', '.'), true, loader);
        final Class<?> gtCls = Class.forName(WarpDeformEditTarget.GRID_TRANSFORM_OWNER.replace('/', '.'), true, loader);
        final Class<?> rectCls = Class.forName(WarpDeformEditTarget.GRECTF_OWNER.replace('/', '.'), true, loader);
        final Class<?> icGrid = Class.forName(
                "com.live2d.cubism.doc.model.deformer.warp.grid.ICGrid", true, loader);
        final Class<?> vCls = Class.forName(WarpDeformEditTarget.GVECTOR2_OWNER.replace('/', '.'), true, loader);
        final Object sg = sgCls.getConstructor(int.class, int.class, float[].class).newInstance(col, row, pts);
        final Object rect = rectCls.getConstructor(float.class, float.class, float.class, float.class)
                .newInstance(0f, 0f, 100f, 100f);
        final Object gt = gtCls.getConstructor(rectCls, icGrid, int.class, int.class).newInstance(rect, sg, col, row);
        final Object pt = vCls.getConstructor(float.class, float.class).newInstance(x, y);
        return (boolean) gtCls.getMethod("isInside", vCls, boolean.class).invoke(gt, pt, isDst);
    }

    private static float[] invokeCanvasToLocal(
            final ClassLoader loader,
            final int col,
            final int row,
            final boolean quadTransform,
            final float[] pts,
            final float[] canvasPts,
            final int count)
            throws Exception {
        final Class<?> modelCls = Class.forName("com.live2d.cubism.doc.model.CModel", true, loader);
        final Class<?> srcCls = Class.forName(WarpDeformEditTarget.WARP_SOURCE_OWNER.replace('/', '.'), true, loader);
        final Class<?> defCls = Class.forName(WarpDeformEditTarget.WARP_DEFORMER_OWNER.replace('/', '.'), true, loader);
        final Class<?> formCls = Class.forName(WarpDeformEditTarget.WARP_FORM_OWNER.replace('/', '.'), true, loader);
        final Object model = modelCls.getConstructor().newInstance();
        final Object src = srcCls.getConstructor().newInstance();
        srcCls.getMethod("setCol", int.class).invoke(src, col);
        srcCls.getMethod("setRow", int.class).invoke(src, row);
        srcCls.getMethod("setQuadTransform", boolean.class).invoke(src, quadTransform);
        final Object deformer = defCls.getConstructor(modelCls, srcCls).newInstance(model, src);
        final Object form = formCls.getConstructor().newInstance();
        formCls.getMethod("setPositions", float[].class).invoke(form, pts);
        defCls.getMethod("setAffectedForm", formCls).invoke(deformer, form);
        defCls.getMethod("setInterpolatedForm", formCls).invoke(deformer, form);
        final float[] out = new float[count * 2];
        defCls.getMethod("transformCanvasToLocal", float[].class, float[].class, int.class, int.class, int.class)
                .invoke(deformer, canvasPts, out, count, 0, 0);
        return out;
    }

    private static float[] invokeCreatePointsOnBezier(final ClassLoader loader, final int col, final int row)
            throws Exception {
        final Class<?> bgCls = Class.forName(WarpDeformEditTarget.BEZIER_GRID_OWNER.replace('/', '.'), true, loader);
        final Class<?> bpCls = Class.forName(WarpDeformEditTarget.BEZIER_PT_OWNER.replace('/', '.'), true, loader);
        final Class<?> vCls = Class.forName(WarpDeformEditTarget.GVECTOR2_OWNER.replace('/', '.'), true, loader);
        final Constructor<?> vCtor = vCls.getConstructor(float.class, float.class);
        final Object bg = bgCls.getConstructor(int.class, int.class).newInstance(2, 2);
        final Object[][] refs = (Object[][]) bgCls.getMethod("getBezierPtRef").invoke(bg);
        for (int ix = 0; ix <= 2; ix++) {
            for (int iy = 0; iy <= 2; iy++) {
                final float ax = ix * 100f + (iy == 1 ? 15f : 0f);
                final float ay = iy * 80f + (ix == 1 ? -12f : 0f);
                final Object anchor = vCtor.newInstance(ax, ay);
                final Object cn = vCtor.newInstance(ax + 4f, ay - 26f);
                final Object cs = vCtor.newInstance(ax - 3f, ay + 26f);
                final Object cw = vCtor.newInstance(ax - 32f, ay + 5f);
                final Object ce = vCtor.newInstance(ax + 32f, ay - 4f);
                refs[ix][iy] = bpCls.getConstructor(vCls, vCls, vCls, vCls, vCls).newInstance(anchor, cn, cs, cw, ce);
            }
        }
        return (float[]) bgCls.getMethod("createPointsOnBezier", int.class, int.class).invoke(bg, col, row);
    }

    private record DrawResult(
            Object draw,
            float[] whitePos,
            int[] whiteIdx,
            float[] blackPos,
            int[] majorIdx,
            int[] detailIdx,
            float[] selectedColors,
            float[] softPos,
            int[] softIdx,
            float[] softColors) {}

    private static DrawResult exerciseWarpDraw(
            final ClassLoader loader, final float[] gridPos, final boolean invokeSoftBridge) throws Exception {
        final Class<?> modelCls = Class.forName("com.live2d.cubism.doc.model.CModel", true, loader);
        final Class<?> srcCls = Class.forName(WarpDeformEditTarget.WARP_SOURCE_OWNER.replace('/', '.'), true, loader);
        final Class<?> defCls = Class.forName(WarpDeformEditTarget.WARP_DEFORMER_OWNER.replace('/', '.'), true, loader);
        final Class<?> formCls = Class.forName(WarpDeformEditTarget.WARP_FORM_OWNER.replace('/', '.'), true, loader);
        final Class<?> drawCls = Class.forName(WarpDeformEditTarget.WARP_DRAW_OWNER.replace('/', '.'), true, loader);
        final Class<?> refCls =
                Class.forName(WarpDeformEditTarget.WARP_POINT_REF_OWNER.replace('/', '.'), true, loader);

        final Object model = modelCls.getConstructor().newInstance();
        final Object src = srcCls.getConstructor().newInstance();
        srcCls.getMethod("setCol", int.class).invoke(src, 2);
        srcCls.getMethod("setRow", int.class).invoke(src, 2);
        final Object deformer = defCls.getConstructor(modelCls, srcCls).newInstance(model, src);
        final Object calcForm = formCls.getConstructor().newInstance();
        formCls.getMethod("setPositions", float[].class).invoke(calcForm, gridPos);
        final Object interpForm = formCls.getConstructor().newInstance();
        formCls.getMethod("setPositions", float[].class).invoke(interpForm, new float[] {
            0.1f, 0.1f, 0.5f, 0.1f, 0.9f, 0.1f,
            0.1f, 0.5f, 0.5f, 0.5f, 0.9f, 0.5f,
            0.1f, 0.9f, 0.5f, 0.9f, 0.9f, 0.9f
        });
        defCls.getMethod("setAffectedForm", formCls).invoke(deformer, calcForm);
        defCls.getMethod("setInterpolatedForm", formCls).invoke(deformer, interpForm);

        final Object selObj = srcCls.getMethod("getSelection").invoke(src);
        final Object selector = selObj.getClass().getMethod("getPointSelector").invoke(selObj);
        final Object ref0 = refCls.getConstructor(srcCls, int.class, formCls).newInstance(src, 0, calcForm);
        final Object ref4 = refCls.getConstructor(srcCls, int.class, formCls).newInstance(src, 4, calcForm);
        final Method addPt = selector.getClass()
                .getMethod(
                        "add",
                        Class.forName("com.live2d.doc.selection.IPointRef", false, loader),
                        float.class,
                        boolean.class);
        addPt.invoke(selector, ref0, 1.0f, false);
        addPt.invoke(selector, ref4, 0.5f, false);

        final Object draw = drawCls.getConstructor(defCls).newInstance(deformer);
        return exerciseWarpDrawOnExisting(draw, loader, gridPos, invokeSoftBridge);
    }

    private static DrawResult exerciseWarpDrawOnExisting(
            final Object draw, final ClassLoader loader, final float[] pos, final boolean invokeSoftBridge)
            throws Exception {
        final Class<?> drawCls = draw.getClass();
        final Class<?> cColorCls = Class.forName(WarpDeformEditTarget.CCOLOR_OWNER.replace('/', '.'), true, loader);
        final Object color = cColorCls.getConstructor(float.class, float.class, float.class, float.class)
                .newInstance(0.2f, 0.6f, 0.9f, 0.8f);

        final Method cornerM = drawCls.getDeclaredMethod(
                "drawCornerTriangle", float[].class, int.class, int.class, float.class, float.class, cColorCls);
        cornerM.setAccessible(true);
        cornerM.invoke(draw, pos, 2, 2, 0.8f, 0.1f, color);

        final Method majorM = drawCls.getDeclaredMethod(
                "drawMajorLines", float[].class, int.class, int.class, cColorCls, boolean.class);
        majorM.setAccessible(true);
        majorM.invoke(draw, pos, 2, 2, color, false);

        final Method detailM = drawCls.getDeclaredMethod(
                "drawDetailLines", float[].class, int.class, int.class, cColorCls, boolean.class);
        detailM.setAccessible(true);
        detailM.invoke(draw, pos, 2, 2, color, true);

        final Method selectedM = drawCls.getDeclaredMethod("drawSelectedPoint", boolean.class);
        selectedM.setAccessible(true);
        selectedM.invoke(draw, false);

        final Field weightField = drawCls.getDeclaredField("eWarpDeformerWeightMap");
        weightField.setAccessible(true);
        final Object weightEntity = weightField.get(draw);

        final Class<?> defCls = Class.forName(WarpDeformEditTarget.WARP_DEFORMER_OWNER.replace('/', '.'), true, loader);
        final Field defField = drawCls.getDeclaredField("deformer");
        defField.setAccessible(true);
        final Object deformer = defField.get(draw);
        final Object src = defCls.getMethod("getSource").invoke(deformer);
        final Object selection = src.getClass().getMethod("getSelection").invoke(src);

        final Method softMethod = drawCls.getDeclaredMethod(
                "drawSoftSelection",
                float[].class,
                int.class,
                int.class,
                Class.forName("com.live2d.cubism.view.context.actionManager.b", false, loader));
        assertNotNull(softMethod);
        if (invokeSoftBridge) {
            final Object softHandle = System.getProperties().get(WarpDeformEditBridge.DRAW_SOFT_SELECTION_PROPERTY);
            if (softHandle instanceof java.lang.invoke.MethodHandle mh) {
                weightEntity.getClass().getMethod("setEnabled", boolean.class).invoke(weightEntity, true);
                try {
                    assertTrue((boolean) mh.invokeExact(draw, selection, color, pos, 2, 2));
                } catch (final Throwable t) {
                    throw new AssertionError("drawSoftSelectionBody handle failed", t);
                }
            }
        }

        final Object whiteMesh = entityMesh(drawCls, draw, "eTriangleOnCornerWhite");
        final Object blackMesh = entityMesh(drawCls, draw, "eTriangleOnCornerBlack");
        final Object softMesh = entityMesh(drawCls, draw, "eWarpDeformerWeightMap");

        final Field lineNormalField = drawCls.getDeclaredField("eWarpGridLineNormal");
        lineNormalField.setAccessible(true);
        final Object lineNormalRenderer = lineNormalField.get(draw).getClass().getMethod("getLineRenderer").invoke(lineNormalField.get(draw));

        final Field lineDetailField = drawCls.getDeclaredField("eWarpGridLineSelected");
        lineDetailField.setAccessible(true);
        final Object lineDetailRenderer = lineDetailField.get(draw).getClass().getMethod("getLineRenderer").invoke(lineDetailField.get(draw));

        final Field selectedField = drawCls.getDeclaredField("eWarpGridSelectedPoint");
        selectedField.setAccessible(true);
        final Object selectedRenderer = selectedField.get(draw).getClass().getMethod("getPointRenderer").invoke(selectedField.get(draw));

        return new DrawResult(
                draw,
                ((float[]) whiteMesh.getClass().getMethod("getPositions").invoke(whiteMesh)).clone(),
                ((int[]) whiteMesh.getClass().getMethod("getIndices").invoke(whiteMesh)).clone(),
                ((float[]) blackMesh.getClass().getMethod("getPositions").invoke(blackMesh)).clone(),
                ((int[]) lineNormalRenderer.getClass().getMethod("getIndices").invoke(lineNormalRenderer)).clone(),
                ((int[]) lineDetailRenderer.getClass().getMethod("getIndices").invoke(lineDetailRenderer)).clone(),
                ((float[]) selectedRenderer.getClass().getMethod("getColors").invoke(selectedRenderer)).clone(),
                ((float[]) softMesh.getClass().getMethod("getPositions").invoke(softMesh)).clone(),
                ((int[]) softMesh.getClass().getMethod("getIndices").invoke(softMesh)).clone(),
                ((float[]) softMesh.getClass().getMethod("getColors").invoke(softMesh)).clone());
    }

    private static Object entityMesh(final Class<?> drawCls, final Object draw, final String fieldName)
            throws Exception {
        final Field f = drawCls.getDeclaredField(fieldName);
        f.setAccessible(true);
        final Object entity = f.get(draw);
        final Object container = entity.getClass().getMethod("getMeshContainer").invoke(entity);
        return container.getClass().getMethod("getMesh").invoke(container);
    }

    private static void setDeclaredField(final Class<?> owner, final Object target, final String name, final Object val)
            throws Exception {
        final Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, val);
    }

    private static Class<?> findFieldOwner(final Class<?> start, final String name) {
        Class<?> cur = start;
        while (cur != null) {
            try {
                cur.getDeclaredField(name);
                return cur;
            } catch (final NoSuchFieldException next) {
                cur = cur.getSuperclass();
            }
        }
        throw new IllegalArgumentException("field " + name + " absent on " + start.getName());
    }

    private static List<Path> availableReviewedJars() {
        final List<Path> result = new ArrayList<>();
        if (Files.isDirectory(LOCAL_ARTIFACTS_ROOT)) {
            for (final String version : ReviewedHostArtifacts.reviewedCubismVersions()) {
                final Path candidate = LOCAL_ARTIFACTS_ROOT.resolve(version).resolve("Live2D_Cubism.jar");
                if (Files.isRegularFile(candidate)) {
                    result.add(candidate);
                }
            }
        }
        if (Files.isRegularFile(LOCAL_REF_5305)) {
            result.add(LOCAL_REF_5305);
        }
        final String configured = System.getProperty(TEST_JAR_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            final Path path = Path.of(configured).toAbsolutePath().normalize();
            if (Files.isRegularFile(path) && !result.contains(path)) {
                result.add(path);
            }
        }
        return result;
    }

    private static List<Path> parityCandidateJars() {
        final List<Path> result = new ArrayList<>();
        final String configured = System.getProperty(TEST_JAR_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            final Path path = Path.of(configured).toAbsolutePath().normalize();
            if (Files.isRegularFile(path)) {
                result.add(path);
            }
        }
        if (result.isEmpty()) {
            final Path p5302 = LOCAL_ARTIFACTS_ROOT.resolve("5.3.02/Live2D_Cubism.jar");
            if (Files.isRegularFile(p5302)) {
                result.add(p5302);
            }
            if (Files.isRegularFile(LOCAL_REF_5305)) {
                result.add(LOCAL_REF_5305);
            }
        }
        return result;
    }

    private static Map<String, byte[]> readTargetBytes(final Path artifact, final List<String> internalNames)
            throws Exception {
        final Map<String, byte[]> map = new LinkedHashMap<>();
        try (JarFile jar = new JarFile(artifact.toFile())) {
            for (final String internalName : internalNames) {
                final JarEntry entry = jar.getJarEntry(internalName + CLASS_EXTENSION);
                assertNotNull(entry, internalName + " absent from " + artifact);
                try (var stream = jar.getInputStream(entry)) {
                    map.put(internalName, stream.readAllBytes());
                }
            }
        }
        return map;
    }

    private static URL[] hostUrls(final Path artifact) throws Exception {
        final List<URL> urls = new ArrayList<>();
        urls.add(artifact.toUri().toURL());
        final Path dir = artifact.getParent();
        boolean hasJogl = false;
        boolean hasJdom = false;
        if (dir != null && Files.isDirectory(dir)) {
            try (var entries = Files.walk(dir, 2)) {
                for (final Path entry : entries.filter(p -> Files.isRegularFile(p)
                        && p.getFileName().toString().endsWith(".jar")).toList()) {
                    if (!entry.equals(artifact)) {
                        final String fileName = entry.getFileName().toString();
                        hasJogl |= fileName.startsWith("jogl");
                        hasJdom |= fileName.startsWith("jdom");
                        urls.add(entry.toUri().toURL());
                    }
                }
            }
        }
        if ((!hasJogl || !hasJdom) && Files.isDirectory(LOCAL_REF_5305.getParent())) {
            try (var entries = Files.walk(LOCAL_REF_5305.getParent(), 2)) {
                for (final Path entry : entries.filter(p -> Files.isRegularFile(p)
                        && p.getFileName().toString().endsWith(".jar")).toList()) {
                    final String fileName = entry.getFileName().toString();
                    if (!fileName.startsWith("Live2D")) {
                        urls.add(entry.toUri().toURL());
                    }
                }
            }
        }
        return urls.toArray(URL[]::new);
    }

    private static ProtectionDomain protectionDomain(final Path artifact) throws Exception {
        return new ProtectionDomain(new CodeSource(artifact.toUri().toURL(), (Certificate[]) null), null);
    }

    private static final class RewrittenHostClassLoader extends URLClassLoader {

        private final JarFile hostJar;
        private final Map<String, byte[]> overrides = new LinkedHashMap<>();
        private ProtectionDomain domain;
        private volatile boolean closed;

        private RewrittenHostClassLoader(final Path artifact, final URL[] urls, final ClassLoader parent)
                throws Exception {
            super(urls, parent);
            this.hostJar = new JarFile(artifact.toFile());
            this.domain = protectionDomain(artifact);
        }

        private void installOverrides(final Map<String, byte[]> entries, final ProtectionDomain domain) {
            overrides.clear();
            overrides.putAll(entries);
            this.domain = domain;
        }

        private Map<String, byte[]> remainingOverrides() {
            return Map.copyOf(overrides);
        }

        @Override
        protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    if (shouldLoadChildFirst(name)) {
                        loaded = findClass(name);
                    } else {
                        try {
                            loaded = super.loadClass(name, false);
                        } catch (final ClassNotFoundException absent) {
                            if (name.startsWith("org.jdom.")) {
                                loaded = defineStubClass(name);
                            } else {
                                throw absent;
                            }
                        }
                    }
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }

        private Class<?> defineStubClass(final String binaryName) {
            final ClassWriter cw = new ClassWriter(0);
            cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, binaryName.replace('.', '/'), null, "java/lang/Object", null);
            cw.visitEnd();
            final byte[] raw = cw.toByteArray();
            return defineClass(binaryName, raw, 0, raw.length);
        }

        private boolean shouldLoadChildFirst(final String name) {
            if (closed) {
                return false;
            }
            if (overrides.containsKey(name)) {
                return true;
            }
            if (name.startsWith("java.")
                    || name.startsWith("javax.")
                    || name.startsWith("jdk.")
                    || name.startsWith("sun.")
                    || name.startsWith("dev.turboism.")) {
                return false;
            }
            return hostJar.getJarEntry(name.replace('.', '/') + CLASS_EXTENSION) != null;
        }

        @Override
        protected Class<?> findClass(final String name) throws ClassNotFoundException {
            final byte[] bytes = overrides.remove(name);
            if (bytes != null) {
                return defineClass(name, bytes, 0, bytes.length, domain);
            }
            // Define un-rewritten host JAR classes under the same unsigned domain so package signers match.
            final JarEntry entry = closed ? null : hostJar.getJarEntry(name.replace('.', '/') + CLASS_EXTENSION);
            if (entry != null) {
                try (var stream = hostJar.getInputStream(entry)) {
                    final byte[] raw = stream.readAllBytes();
                    return defineClass(name, raw, 0, raw.length, domain);
                } catch (final Exception unreadable) {
                    throw new ClassNotFoundException(name, unreadable);
                }
            }
            return super.findClass(name);
        }

        @Override
        public void close() throws java.io.IOException {
            closed = true;
            try {
                super.close();
            } finally {
                hostJar.close();
            }
        }
    }
}
