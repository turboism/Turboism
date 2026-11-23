package dev.turboism.adapter.cubism.optimization.warpdeform;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.turboism.adapter.cubism.optimization.memory.MemoryLeakMitigationBridge;
import dev.turboism.adapter.cubism.optimization.memory.MemoryLeakMitigationTarget;
import dev.turboism.adapter.cubism.optimization.memory.MemoryLeakMitigationTransformer;
import dev.turboism.adapter.cubism.optimization.modelupdate.ModelUpdateSkipBridge;
import dev.turboism.adapter.cubism.optimization.modelupdate.ModelUpdateSkipTarget;
import dev.turboism.adapter.cubism.optimization.modelupdate.ModelUpdateSkipTransformer;
import dev.turboism.adapter.cubism.optimization.uploadelision.SkippedFrameUploadElisionBridge;
import dev.turboism.adapter.cubism.optimization.uploadelision.SkippedFrameUploadElisionTarget;
import dev.turboism.adapter.cubism.optimization.uploadelision.SkippedFrameUploadElisionTransformer;
import dev.turboism.adapter.cubism.optimization.warpgrid.WarpGridInterpolationBridge;
import dev.turboism.adapter.cubism.optimization.warpgrid.WarpGridInterpolationTarget;
import dev.turboism.adapter.cubism.optimization.warpgrid.WarpGridInterpolationTransformer;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.lang.instrument.ClassFileTransformer;
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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
    private static final Path LOCAL_ARTIFACTS_ROOT = Path.of(System.getProperty("user.home"), "cubism-host-artifacts");
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
                ax, ay, ax + dx, ay, ax, ay + dy, bx, ay, bx - dx, ay, bx, ay + dy, ax, by, ax + dx, by, ax, by - dy,
                bx, by, bx - dx, by, bx, by - dy, 5, 8, 128);
        final int divX = packed >>> 16;
        final int divY = packed & 0xFFFF;
        assertEquals(8, divX);
        assertEquals(8, divY);
        final float[] lattice = WarpDeformMath.buildPatchCornerLattice(
                ax, ay, ax + dx, ay, ax, ay + dy, bx, ay, bx - dx, ay, bx, ay + dy, ax, by, ax + dx, by, ax, by - dy,
                bx, by, bx - dx, by, bx, by - dy, divX, divY);
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

    @Test
    void verifiesEditSubtreeParallelParityOverlayElisionAndMultiTransformerComposition() throws Throwable {
        final List<Path> parityJars = parityCandidateJars();
        assumeTrue(!parityJars.isEmpty(), "no Cubism Editor JAR supplied for edit subtree parity test");
        for (final Path jar : parityJars) {
            verifyEditSubtreeParallelParityAndCompositionForJar(jar);
        }
    }

    private void verifyEditSubtreeParallelParityAndCompositionForJar(final Path jar) throws Throwable {
        final HostArtifactDigest digest = HostArtifactDigest.cached(jar);
        final WarpDeformEditTarget warpEditTarget = WarpDeformEditTarget.of(digest).orElseThrow();
        final ModelUpdateSkipTarget updateSkipTarget = ModelUpdateSkipTarget.of(digest).orElseThrow();
        final WarpGridInterpolationTarget warpGridTarget = WarpGridInterpolationTarget.of(digest).orElseThrow();
        final MemoryLeakMitigationTarget memoryTarget = MemoryLeakMitigationTarget.of(digest).orElseThrow();
        final SkippedFrameUploadElisionTarget uploadTarget =
                SkippedFrameUploadElisionTarget.of(digest).orElseThrow();
        assertNotNull(uploadTarget);

        final Set<String> allOwners = new LinkedHashSet<>(warpEditTarget.targetClasses());
        allOwners.add(updateSkipTarget.owner());
        allOwners.add(WarpGridInterpolationTarget.OWNER);
        allOwners.addAll(SkippedFrameUploadElisionTarget.OWNERS);
        allOwners.addAll(memoryTarget.rewrittenOwners());
        final List<String> unionList = List.copyOf(allOwners);
        final Map<String, byte[]> refs = readTargetBytes(jar, unionList);
        final ProtectionDomain domain = protectionDomain(jar);
        final URL[] urls = hostUrls(jar);

        // Verify reverse registration order on updaterOwner composes without shape refusal.
        try (RewrittenHostClassLoader reverseLoader =
                new RewrittenHostClassLoader(jar, urls, getClass().getClassLoader())) {
            final WarpDeformEditTransformer firstWarp =
                    new WarpDeformEditTransformer(reverseLoader, jar, refs, warpEditTarget);
            final ModelUpdateSkipTransformer secondSkip = new ModelUpdateSkipTransformer(
                    reverseLoader, jar, refs.get(updateSkipTarget.owner()), updateSkipTarget);
            final String updaterOwner = warpEditTarget.updaterOwner();
            final byte[] afterWarp = firstWarp.transform(
                    null, reverseLoader, updaterOwner, null, domain, refs.get(updaterOwner));
            assertNotNull(afterWarp, firstWarp::failure);
            final byte[] afterBoth = secondSkip.transform(
                    null, reverseLoader, updateSkipTarget.owner(), null, domain, afterWarp);
            assertNotNull(afterBoth, secondSkip::failure);
            assertEquals(1, secondSkip.matches());
            assertEquals(2, firstWarp.matchesFor(updaterOwner));
        }

        // Compose all five default-ON transformers in DeclarativeHooks order and load with -Xverify:all semantics.
        final Map<String, byte[]> composedOverrides = new LinkedHashMap<>();
        try (RewrittenHostClassLoader txLoader = new RewrittenHostClassLoader(jar, urls, getClass().getClassLoader())) {
            final ModelUpdateSkipTransformer updateSkipTx = new ModelUpdateSkipTransformer(
                    txLoader, jar, refs.get(updateSkipTarget.owner()), updateSkipTarget);
            final WarpGridInterpolationTransformer warpGridTx = new WarpGridInterpolationTransformer(
                    txLoader, jar, refs.get(WarpGridInterpolationTarget.OWNER), warpGridTarget);
            final WarpDeformEditTransformer warpEditTx =
                    new WarpDeformEditTransformer(txLoader, jar, refs, warpEditTarget);
            final MemoryLeakMitigationTransformer memoryTx =
                    new MemoryLeakMitigationTransformer(txLoader, jar, memoryTarget);
            final List<SkippedFrameUploadElisionTransformer> uploadTxs = new ArrayList<>();
            for (final String vboOwner : SkippedFrameUploadElisionTarget.OWNERS) {
                uploadTxs.add(new SkippedFrameUploadElisionTransformer(
                        txLoader, jar, refs.get(vboOwner), vboOwner));
            }

            final List<ClassFileTransformer> chain = new ArrayList<>();
            chain.add(updateSkipTx);
            chain.add(warpGridTx);
            chain.add(warpEditTx);
            chain.addAll(uploadTxs);
            chain.add(memoryTx);

            for (final String owner : unionList) {
                byte[] current = refs.get(owner);
                boolean modified = false;
                for (final ClassFileTransformer tx : chain) {
                    final byte[] next = tx.transform(null, txLoader, owner, null, domain, current);
                    if (next != null) {
                        current = next;
                        modified = true;
                    }
                }
                if (modified) {
                    composedOverrides.put(owner.replace('/', '.'), current);
                }
            }
            assertNull(updateSkipTx.failure(), updateSkipTx::failure);
            assertNull(warpGridTx.failure(), warpGridTx::failure);
            assertNull(warpEditTx.failure(), warpEditTx::failure);
            assertNull(memoryTx.failure(), memoryTx::failure);
            assertEquals(1, updateSkipTx.matches());
            assertEquals(warpEditTx.expectedTotalSites(), warpEditTx.sites());
            assertEquals(MemoryLeakMitigationTransformer.EXPECTED_SITES, memoryTx.sites());
            for (final SkippedFrameUploadElisionTransformer u : uploadTxs) {
                assertNull(u.failure(), u::failure);
            }
        }

        final String prevSkipProp = System.getProperty(ModelUpdateSkipBridge.ENABLE_PROPERTY);
        final String prevEditProp = System.getProperty(WarpDeformEditBridge.ENABLE_PROPERTY);
        System.setProperty(ModelUpdateSkipBridge.ENABLE_PROPERTY, "true");
        System.setProperty(WarpDeformEditBridge.ENABLE_PROPERTY, "true");
        try (RewrittenHostClassLoader origLoader = new RewrittenHostClassLoader(jar, urls, getClass().getClassLoader());
                RewrittenHostClassLoader rewLoader = new RewrittenHostClassLoader(jar, urls, getClass().getClassLoader())) {
            rewLoader.installOverrides(composedOverrides, domain);
            for (final String owner : composedOverrides.keySet()) {
                Class.forName(owner.replace('/', '.'), false, rewLoader);
            }

            try (WarpDeformEditBridge warpEditBridge = new WarpDeformEditBridge(rewLoader);
                    ModelUpdateSkipBridge skipBridge = new ModelUpdateSkipBridge(updateSkipTarget, rewLoader);
                    SkippedFrameUploadElisionBridge uploadBridge = new SkippedFrameUploadElisionBridge(rewLoader);
                    WarpGridInterpolationBridge warpGridBridge = WarpGridInterpolationBridge.open(rewLoader);
                    MemoryLeakMitigationBridge memoryBridge = new MemoryLeakMitigationBridge(rewLoader, memoryTarget)) {
                warpEditBridge.install();
                skipBridge.install();
                uploadBridge.install();
                warpGridBridge.install();
                memoryBridge.install();

                runSyntheticSubtreeDifferentialParity(
                        origLoader, rewLoader, warpEditTarget, warpEditBridge);
                runOverlayElisionParityOnComposedLoader(rewLoader, warpEditBridge);
            }
        } finally {
            if (prevSkipProp == null) {
                System.clearProperty(ModelUpdateSkipBridge.ENABLE_PROPERTY);
            } else {
                System.setProperty(ModelUpdateSkipBridge.ENABLE_PROPERTY, prevSkipProp);
            }
            if (prevEditProp == null) {
                System.clearProperty(WarpDeformEditBridge.ENABLE_PROPERTY);
            } else {
                System.setProperty(WarpDeformEditBridge.ENABLE_PROPERTY, prevEditProp);
            }
        }
    }

    private static final class SyntheticModelHarness {
        final ClassLoader loader;
        final Object modelSource;
        final Object model;
        final Object rootWarpSrc;
        final Object rootWarp;
        final Object midRotSrc;
        final Object midRot;
        final Object paramSrc;
        final Object param;
        final Object updater;
        final Method updateModelMethod;
        final Object updCtx;
        final List<Object> subtreeAMeshes;
        final List<Object> subtreeBMeshes;
        final List<Object> allMeshes;
        final List<float[]> baseRootGridPositions;
        final boolean hasConflictFlag;

        private SyntheticModelHarness(
                final ClassLoader loader,
                final Object modelSource,
                final Object model,
                final Object rootWarpSrc,
                final Object rootWarp,
                final Object midRotSrc,
                final Object midRot,
                final Object paramSrc,
                final Object param,
                final Object updater,
                final Method updateModelMethod,
                final Object updCtx,
                final List<Object> subtreeAMeshes,
                final List<Object> subtreeBMeshes,
                final List<Object> allMeshes,
                final List<float[]> baseRootGridPositions,
                final boolean hasConflictFlag) {
            this.loader = loader;
            this.modelSource = modelSource;
            this.model = model;
            this.rootWarpSrc = rootWarpSrc;
            this.rootWarp = rootWarp;
            this.midRotSrc = midRotSrc;
            this.midRot = midRot;
            this.paramSrc = paramSrc;
            this.param = param;
            this.updater = updater;
            this.updateModelMethod = updateModelMethod;
            this.updCtx = updCtx;
            this.subtreeAMeshes = subtreeAMeshes;
            this.subtreeBMeshes = subtreeBMeshes;
            this.allMeshes = allMeshes;
            this.baseRootGridPositions = baseRootGridPositions;
            this.hasConflictFlag = hasConflictFlag;
        }

        void invokeUpdate() throws Exception {
            if (hasConflictFlag) {
                updateModelMethod.invoke(updater, model, updCtx, null, null, null, false);
            } else {
                updateModelMethod.invoke(updater, model, updCtx, null, null, null);
            }
        }
    }

    private static SyntheticModelHarness buildSyntheticHierarchy(
            final ClassLoader loader, final WarpDeformEditTarget target) throws Exception {
        final Class<?> modelSrcCls = Class.forName("com.live2d.cubism.doc.model.CModelSource", true, loader);
        final Class<?> modelCls = Class.forName("com.live2d.cubism.doc.model.CModel", true, loader);
        final Class<?> paramSrcCls = Class.forName("com.live2d.cubism.doc.model.param.CParameterSource", true, loader);
        final Class<?> paramGuidCls = Class.forName("com.live2d.type.CParameterGuid", true, loader);
        final Class<?> paramIdCls = Class.forName("com.live2d.cubism.doc.model.id.CParameterId", true, loader);
        final Class<?> defGuidCls = Class.forName("com.live2d.type.CDeformerGuid", true, loader);
        final Class<?> defIdCls = Class.forName("com.live2d.cubism.doc.model.id.CDeformerId", true, loader);
        final Class<?> drawGuidCls = Class.forName("com.live2d.type.CDrawableGuid", true, loader);
        final Class<?> drawIdCls = Class.forName("com.live2d.cubism.doc.model.id.CDrawableId", true, loader);
        final Class<?> affGuidCls = Class.forName("com.live2d.type.CAffecterGuid", true, loader);
        final Class<?> affIdCls = Class.forName("com.live2d.cubism.doc.model.id.CAffecterId", true, loader);
        final Class<?> formGuidCls = Class.forName("com.live2d.type.CFormGuid", true, loader);
        final Class<?> coordCls = Class.forName("com.live2d.doc.CoordType", true, loader);
        final Class<?> warpSrcCls = Class.forName(WarpDeformEditTarget.WARP_SOURCE_OWNER.replace('/', '.'), true, loader);
        final Class<?> warpFormCls = Class.forName(WarpDeformEditTarget.WARP_FORM_OWNER.replace('/', '.'), true, loader);
        final Class<?> rotSrcCls =
                Class.forName("com.live2d.cubism.doc.model.deformer.rotation.CRotationDeformerSource", true, loader);
        final Class<?> rotFormCls =
                Class.forName("com.live2d.cubism.doc.model.deformer.rotation.CRotationDeformerForm", true, loader);
        final Class<?> meshSrcCls =
                Class.forName("com.live2d.cubism.doc.model.drawable.artMesh.CArtMeshSource", true, loader);
        final Class<?> meshFormCls =
                Class.forName("com.live2d.cubism.doc.model.drawable.artMesh.CArtMeshForm", true, loader);
        final Class<?> glueSrcCls =
                Class.forName("com.live2d.cubism.doc.model.affecter.glue.CGlueSource", true, loader);
        final Class<?> glueFormCls =
                Class.forName("com.live2d.cubism.doc.model.affecter.glue.CGlueForm", true, loader);
        final Class<?> renderTypeCls = Class.forName("com.live2d.graphics3d.rendering.RenderType", true, loader);
        final Class<?> acDefSrcCls = Class.forName("com.live2d.cubism.doc.model.deformer.ACDeformerSource", true, loader);
        final Class<?> acDrawSrcCls = Class.forName("com.live2d.cubism.doc.model.drawable.ACDrawableSource", true, loader);
        final Class<?> acAffSrcCls = Class.forName("com.live2d.cubism.doc.model.affecter.ACAffecterSource", true, loader);
        final Class<?> acCtrlSrcCls = Class.forName("com.live2d.cubism.doc.model.ACParameterControllableSource", true, loader);

        final Object coordLocal = coordCls.getMethod("b").invoke(null);
        final Object coordCanvas = coordCls.getMethod("c").invoke(null);
        final Object modelSource = modelSrcCls.getConstructor().newInstance();
        final Object rootPart = modelSrcCls.getMethod("getRootPart").invoke(modelSource);
        final Object defSet = modelSrcCls.getMethod("getDeformerSourceSet").invoke(modelSource);
        final Object drawSet = modelSrcCls.getMethod("getDrawableSourceSet").invoke(modelSource);
        final Object affSet = modelSrcCls.getMethod("getAffecterSourceSet").invoke(modelSource);
        final Object paramSet = modelSrcCls.getMethod("getParameterSourceSet").invoke(modelSource);

        final Object paramGuid = paramGuidCls.getConstructor(UUID.class).newInstance(UUID.nameUUIDFromBytes("pX".getBytes()));
        final Object paramSrc = paramSrcCls.getConstructor().newInstance();
        paramSrcCls.getMethod("setGuid", paramGuidCls).invoke(paramSrc, paramGuid);
        paramSrcCls.getMethod("setId", paramIdCls).invoke(paramSrc, paramIdCls.getConstructor(String.class).newInstance("ParamX"));
        paramSrcCls.getMethod("setMinValue", float.class).invoke(paramSrc, -30.0f);
        paramSrcCls.getMethod("setMaxValue", float.class).invoke(paramSrc, 30.0f);
        paramSrcCls.getMethod("setDefaultValue", float.class).invoke(paramSrc, 0.0f);
        paramSet.getClass().getMethod("add", paramSrcCls, int.class).invoke(paramSet, paramSrc, -1);

        final Method addDef = defSet.getClass().getMethod("add", acDefSrcCls, int.class);
        final Method addDraw = drawSet.getClass().getMethod("add", acDrawSrcCls, int.class);
        final Method addAff = affSet.getClass().getMethod("add", acAffSrcCls, int.class);
        final Method addPartChild = rootPart.getClass().getMethod("addChild", acCtrlSrcCls, int.class);

        final float[] baseGrid3x3 = new float[] {
            -100f, -100f, 0f, -100f, 100f, -100f,
            -100f, 0f, 0f, 0f, 100f, 0f,
            -100f, 100f, 0f, 100f, 100f, 100f
        };

        // Subtree A root WarpDeformer with 18 depth-1 child WarpDeformers (>= 16) and 36 ArtMeshes (>= 32).
        final Object rootWarpGuid = defGuidCls.getConstructor(UUID.class).newInstance(UUID.nameUUIDFromBytes("rootWarp".getBytes()));
        final Object rootWarpSrc = createWarpDeformerSource(
                modelSrcCls, modelSource, warpSrcCls, warpFormCls, defGuidCls, defIdCls, formGuidCls, coordCls,
                rootWarpGuid, "RootWarp", null, coordLocal, baseGrid3x3);
        addDef.invoke(defSet, rootWarpSrc, -1);
        addPartChild.invoke(rootPart, rootWarpSrc, -1);

        // Bind two keys on ParamX on rootWarp so parameter changes mutate geometry cleanly.
        final Object rootKfGrid = warpSrcCls.getMethod("getKeyformGridSource").invoke(rootWarpSrc);
        rootKfGrid.getClass().getMethod("addKey", float.class, paramGuidCls).invoke(rootKfGrid, 0.0f, paramGuid);
        rootKfGrid.getClass().getMethod("addKey", float.class, paramGuidCls).invoke(rootKfGrid, 30.0f, paramGuid);
        @SuppressWarnings("unchecked")
        final List<Object> rootKeys = (List<Object>) warpSrcCls.getMethod("getKeyforms").invoke(rootWarpSrc);
        if (rootKeys.size() > 1) {
            final float[] keyMoved = baseGrid3x3.clone();
            for (int i = 0; i < keyMoved.length; i += 2) {
                keyMoved[i] += 20.0f;
            }
            warpFormCls.getMethod("setPositions", float[].class).invoke(rootKeys.get(rootKeys.size() - 1), keyMoved);
        }

        final List<Object> lvl1WarpGuids = new ArrayList<>();
        final List<Object> subtreeAMeshSources = new ArrayList<>();
        for (int c = 0; c < 18; c++) {
            final Object cGuid = defGuidCls.getConstructor(UUID.class)
                    .newInstance(UUID.nameUUIDFromBytes(("lvl1_" + c).getBytes()));
            final Object childWarp = createWarpDeformerSource(
                    modelSrcCls, modelSource, warpSrcCls, warpFormCls, defGuidCls, defIdCls, formGuidCls, coordCls,
                    cGuid, "ChildWarp_" + c, rootWarpGuid, coordLocal, baseGrid3x3);
            addDef.invoke(defSet, childWarp, -1);
            addPartChild.invoke(rootPart, childWarp, -1);
            lvl1WarpGuids.add(cGuid);

            for (int m = 0; m < 2; m++) {
                final String mName = "MeshA_" + c + "_" + m;
                final Object mGuid = drawGuidCls.getConstructor(UUID.class)
                        .newInstance(UUID.nameUUIDFromBytes(mName.getBytes()));
                final Object ms = createArtMeshSource(
                        modelSrcCls, modelSource, meshSrcCls, meshFormCls, defGuidCls, drawGuidCls, drawIdCls,
                        formGuidCls, coordCls, mGuid, mName, cGuid, coordCanvas,
                        new float[] {-10f + m, -10f, 10f + m, -10f, 10f + m, 10f, -10f + m, 10f});
                addDraw.invoke(drawSet, ms, -1);
                addPartChild.invoke(rootPart, ms, -1);
                subtreeAMeshSources.add(ms);
            }
        }

        // Attach CGlue between mesh 0 and mesh 2 across distinct child warps in Subtree A.
        final Object glueSrc = glueSrcCls
                .getConstructor(modelSrcCls, meshSrcCls, meshSrcCls)
                .newInstance(modelSource, subtreeAMeshSources.get(0), subtreeAMeshSources.get(2));
        final Object glueGuid = affGuidCls.getConstructor(UUID.class)
                .newInstance(UUID.nameUUIDFromBytes("GlueAB".getBytes()));
        glueSrcCls.getMethod("setGuid", affGuidCls).invoke(glueSrc, glueGuid);
        glueSrcCls.getMethod("setId", affIdCls).invoke(glueSrc, affIdCls.getConstructor(String.class).newInstance("GlueAB"));
        final Object glueForm = glueFormCls.getConstructor(glueSrcCls, Class.forName(
                "com.live2d.cubism.doc.model.affecter.glue.CGlue", false, loader)).newInstance(glueSrc, null);
        final Object glueFormGuid = formGuidCls.getConstructor(UUID.class)
                .newInstance(UUID.nameUUIDFromBytes("gf0".getBytes()));
        glueFormCls.getMethod("setGuid", formGuidCls).invoke(glueForm, glueFormGuid);
        @SuppressWarnings("unchecked")
        final List<Object> glueKfs = (List<Object>) glueSrcCls.getMethod("getKeyforms").invoke(glueSrc);
        glueKfs.add(glueForm);
        final Object glueKfGrid = glueSrcCls.getMethod("getKeyformGridSource").invoke(glueSrc);
        glueKfGrid.getClass().getMethod("setInitialKeyform", formGuidCls).invoke(glueKfGrid, glueFormGuid);
        glueSrcCls
                .getMethod("addBindVertex_forMeshEditor", meshSrcCls, long.class, float.class, meshSrcCls, long.class, float.class)
                .invoke(glueSrc, subtreeAMeshSources.get(0), 0L, 0.5f, subtreeAMeshSources.get(2), 0L, 0.5f);
        addAff.invoke(affSet, glueSrc, -1);
        addPartChild.invoke(rootPart, glueSrc, -1);

        // Subtree B: depth 0 Warp -> mid-tree CRotationDeformer -> 4 ArtMeshes.
        final Object sibWarpGuid = defGuidCls.getConstructor(UUID.class)
                .newInstance(UUID.nameUUIDFromBytes("sibWarp".getBytes()));
        final Object sibWarpSrc = createWarpDeformerSource(
                modelSrcCls, modelSource, warpSrcCls, warpFormCls, defGuidCls, defIdCls, formGuidCls, coordCls,
                sibWarpGuid, "SiblingRootWarp", null, coordLocal, baseGrid3x3);
        addDef.invoke(defSet, sibWarpSrc, -1);
        addPartChild.invoke(rootPart, sibWarpSrc, -1);

        final Object midRotGuid = defGuidCls.getConstructor(UUID.class)
                .newInstance(UUID.nameUUIDFromBytes("midRot".getBytes()));
        final Object midRotSrc = rotSrcCls.getConstructor(modelSrcCls).newInstance(modelSource);
        rotSrcCls.getMethod("setGuid", defGuidCls).invoke(midRotSrc, midRotGuid);
        rotSrcCls.getMethod("setId", defIdCls).invoke(midRotSrc, defIdCls.getConstructor(String.class).newInstance("MidRot"));
        rotSrcCls.getMethod("setTargetDeformerGuid", defGuidCls).invoke(midRotSrc, sibWarpGuid);
        final Object rotDefForm = rotFormCls.getConstructor(
                rotSrcCls, Class.forName("com.live2d.cubism.doc.model.deformer.rotation.CRotationDeformer", false, loader), coordCls)
                .newInstance(midRotSrc, null, coordLocal);
        final Object rotFormGuid = formGuidCls.getConstructor(UUID.class)
                .newInstance(UUID.nameUUIDFromBytes("rf0".getBytes()));
        rotFormCls.getMethod("setGuid", formGuidCls).invoke(rotDefForm, rotFormGuid);
        rotFormCls.getMethod("setOriginX", float.class).invoke(rotDefForm, 12.0f);
        rotFormCls.getMethod("setOriginY", float.class).invoke(rotDefForm, 8.0f);
        rotFormCls.getMethod("setScale", float.class).invoke(rotDefForm, 1.0f);
        @SuppressWarnings("unchecked")
        final List<Object> rotKfs = (List<Object>) rotSrcCls.getMethod("getKeyforms").invoke(midRotSrc);
        rotKfs.add(rotDefForm);
        final Object rotKfGrid = rotSrcCls.getMethod("getKeyformGridSource").invoke(midRotSrc);
        rotKfGrid.getClass().getMethod("setInitialKeyform", formGuidCls).invoke(rotKfGrid, rotFormGuid);
        addDef.invoke(defSet, midRotSrc, -1);
        addPartChild.invoke(rootPart, midRotSrc, -1);

        final List<Object> subtreeBMeshSources = new ArrayList<>();
        for (int m = 0; m < 4; m++) {
            final String mName = "MeshB_" + m;
            final Object mGuid = drawGuidCls.getConstructor(UUID.class)
                    .newInstance(UUID.nameUUIDFromBytes(mName.getBytes()));
            final Object ms = createArtMeshSource(
                    modelSrcCls, modelSource, meshSrcCls, meshFormCls, defGuidCls, drawGuidCls, drawIdCls,
                    formGuidCls, coordCls, mGuid, mName, midRotGuid, coordCanvas,
                    new float[] {20f + m, 20f, 40f + m, 20f, 40f + m, 40f, 20f + m, 40f});
            addDraw.invoke(drawSet, ms, -1);
            addPartChild.invoke(rootPart, ms, -1);
            subtreeBMeshSources.add(ms);
        }

        rootPart.getClass().getMethod("setVisibleAndLockInHierarchy").invoke(rootPart);
        modelSrcCls.getMethod("setModelEditing", boolean.class).invoke(modelSource, true);

        final Object renderNormal = Enum.valueOf((Class) renderTypeCls, "NORMAL");
        final Object model = modelSrcCls.getMethod("createModelInstance", renderTypeCls).invoke(modelSource, renderNormal);
        final Object rootWarp = modelCls.getMethod("getDeformer", defGuidCls).invoke(model, rootWarpGuid);
        final Object midRot = modelCls.getMethod("getDeformer", defGuidCls).invoke(model, midRotGuid);

        final List<Object> subtreeAMeshes = new ArrayList<>();
        for (final Object ms : subtreeAMeshSources) {
            final Object g = meshSrcCls.getMethod("getGuid").invoke(ms);
            subtreeAMeshes.add(modelCls.getMethod("getArtMesh", drawGuidCls).invoke(model, g));
        }
        final List<Object> subtreeBMeshes = new ArrayList<>();
        for (final Object ms : subtreeBMeshSources) {
            final Object g = meshSrcCls.getMethod("getGuid").invoke(ms);
            subtreeBMeshes.add(modelCls.getMethod("getArtMesh", drawGuidCls).invoke(model, g));
        }
        @SuppressWarnings("unchecked")
        final List<Object> allMeshes = (List<Object>) modelCls.getMethod("getAllArtMeshes").invoke(model);

        final Object paramSetInst = modelCls.getMethod("getParameterSet").invoke(model);
        @SuppressWarnings("unchecked")
        final List<Object> params = (List<Object>) paramSetInst.getClass().getMethod("getParameters").invoke(paramSetInst);
        final Object param = params.get(0);

        final String updaterDot = target.updaterOwner().replace('/', '.');
        final Class<?> updaterCls = Class.forName(updaterDot, true, loader);
        final Object updater = updaterCls.getField("a").get(null);
        final String updCtxDot = resolveUpdaterContextClass(loader, target).getName();
        final Class<?> updCtxCls = Class.forName(updCtxDot, true, loader);
        final Class<?> docCls = Class.forName("com.live2d.cubism.doc.modeling.CModelingDocument", false, loader);
        final Class<?> viewCls = Class.forName("com.live2d.cubism.view.context.CEViewContext", false, loader);
        final Class<?> paramCtxCls = resolveContextParamClass(updaterCls, modelCls, updCtxCls, docCls, viewCls);
        final boolean conflictFlag = updater.getClass().getName() != null && hasUpdateModelConflictFlag(updaterCls, modelCls, updCtxCls, docCls, viewCls, paramCtxCls);
        final Method updateModelMethod = conflictFlag
                ? updaterCls.getMethod("a", modelCls, updCtxCls, docCls, viewCls, paramCtxCls, boolean.class)
                : updaterCls.getMethod("a", modelCls, updCtxCls, docCls, viewCls, paramCtxCls);
        final Object updCtx = updCtxCls.getConstructor(viewCls).newInstance((Object) null);

        final List<float[]> baseGrids = List.of(baseGrid3x3);
        return new SyntheticModelHarness(
                loader, modelSource, model, rootWarpSrc, rootWarp, midRotSrc, midRot,
                paramSrc, param, updater, updateModelMethod, updCtx,
                subtreeAMeshes, subtreeBMeshes, allMeshes, baseGrids, conflictFlag);
    }

    private static Class<?> resolveUpdaterContextClass(
            final ClassLoader loader, final WarpDeformEditTarget target) throws ClassNotFoundException {
        final String desc = target.coreUpdateDescriptor();
        final int idx = desc.indexOf("Ljava/util/List;L") + "Ljava/util/List;L".length();
        final int end = desc.indexOf(';', idx);
        return Class.forName(desc.substring(idx, end).replace('/', '.'), true, loader);
    }

    private static Class<?> resolveContextParamClass(
            final Class<?> updaterCls,
            final Class<?> modelCls,
            final Class<?> updCtxCls,
            final Class<?> docCls,
            final Class<?> viewCls) {
        for (final Method m : updaterCls.getMethods()) {
            if (!"a".equals(m.getName())) {
                continue;
            }
            final Class<?>[] p = m.getParameterTypes();
            if ((p.length == 5 || p.length == 6)
                    && p[0] == modelCls
                    && p[1] == updCtxCls
                    && p[2] == docCls
                    && p[3] == viewCls) {
                return p[4];
            }
        }
        throw new IllegalStateException("updateModel overload not found on " + updaterCls.getName());
    }

    private static boolean hasUpdateModelConflictFlag(
            final Class<?> updaterCls,
            final Class<?> modelCls,
            final Class<?> updCtxCls,
            final Class<?> docCls,
            final Class<?> viewCls,
            final Class<?> paramCtxCls) {
        try {
            updaterCls.getMethod("a", modelCls, updCtxCls, docCls, viewCls, paramCtxCls, boolean.class);
            return true;
        } catch (final NoSuchMethodException absent) {
            return false;
        }
    }

    private static Object createWarpDeformerSource(
            final Class<?> modelSrcCls,
            final Object modelSource,
            final Class<?> warpSrcCls,
            final Class<?> warpFormCls,
            final Class<?> defGuidCls,
            final Class<?> defIdCls,
            final Class<?> formGuidCls,
            final Class<?> coordCls,
            final Object guid,
            final String id,
            final Object parentGuid,
            final Object coordLocal,
            final float[] gridPos)
            throws Exception {
        final Object ws = warpSrcCls.getConstructor(modelSrcCls).newInstance(modelSource);
        warpSrcCls.getMethod("setGuid", defGuidCls).invoke(ws, guid);
        warpSrcCls.getMethod("setId", defIdCls).invoke(ws, defIdCls.getConstructor(String.class).newInstance(id));
        warpSrcCls.getMethod("setCol", int.class).invoke(ws, 2);
        warpSrcCls.getMethod("setRow", int.class).invoke(ws, 2);
        warpSrcCls.getMethod("setQuadTransform", boolean.class).invoke(ws, true);
        if (parentGuid != null) {
            warpSrcCls.getMethod("setTargetDeformerGuid", defGuidCls).invoke(ws, parentGuid);
        }
        final Object wf = warpFormCls
                .getConstructor(warpSrcCls, Class.forName(
                        WarpDeformEditTarget.WARP_DEFORMER_OWNER.replace('/', '.'), false, warpSrcCls.getClassLoader()), coordCls)
                .newInstance(ws, null, coordLocal);
        final Object fGuid = formGuidCls.getConstructor(UUID.class)
                .newInstance(UUID.nameUUIDFromBytes((id + "_kf").getBytes()));
        warpFormCls.getMethod("setGuid", formGuidCls).invoke(wf, fGuid);
        warpFormCls.getMethod("setPositions", float[].class).invoke(wf, gridPos.clone());
        @SuppressWarnings("unchecked")
        final List<Object> kfs = (List<Object>) warpSrcCls.getMethod("getKeyforms").invoke(ws);
        kfs.add(wf);
        final Object kfGrid = warpSrcCls.getMethod("getKeyformGridSource").invoke(ws);
        kfGrid.getClass().getMethod("setInitialKeyform", formGuidCls).invoke(kfGrid, fGuid);
        return ws;
    }

    private static Object createArtMeshSource(
            final Class<?> modelSrcCls,
            final Object modelSource,
            final Class<?> meshSrcCls,
            final Class<?> meshFormCls,
            final Class<?> defGuidCls,
            final Class<?> drawGuidCls,
            final Class<?> drawIdCls,
            final Class<?> formGuidCls,
            final Class<?> coordCls,
            final Object guid,
            final String id,
            final Object parentGuid,
            final Object coordCanvas,
            final float[] positions)
            throws Exception {
        final Object ms = meshSrcCls.getConstructor(modelSrcCls).newInstance(modelSource);
        meshSrcCls.getMethod("setGuid", drawGuidCls).invoke(ms, guid);
        meshSrcCls.getMethod("setId", drawIdCls).invoke(ms, drawIdCls.getConstructor(String.class).newInstance(id));
        meshSrcCls.getMethod("setTargetDeformerGuid", defGuidCls).invoke(ms, parentGuid);
        meshSrcCls.getMethod("setPositions", float[].class).invoke(ms, positions.clone());
        meshSrcCls.getMethod("setIndices", int[].class).invoke(ms, new int[] {0, 1, 2, 0, 2, 3});
        meshSrcCls.getMethod("setUvs", float[].class).invoke(ms, new float[] {0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f});
        final Object mf = meshFormCls
                .getConstructor(meshSrcCls, Class.forName(
                        "com.live2d.cubism.doc.model.drawable.artMesh.CArtMesh", false, meshSrcCls.getClassLoader()), coordCls)
                .newInstance(ms, null, coordCanvas);
        final Object fGuid = formGuidCls.getConstructor(UUID.class)
                .newInstance(UUID.nameUUIDFromBytes((id + "_kf").getBytes()));
        meshFormCls.getMethod("setGuid", formGuidCls).invoke(mf, fGuid);
        meshFormCls.getMethod("setPositions", float[].class).invoke(mf, positions.clone());
        @SuppressWarnings("unchecked")
        final List<Object> kfs = (List<Object>) meshSrcCls.getMethod("getKeyforms").invoke(ms);
        kfs.add(mf);
        final Object kfGrid = meshSrcCls.getMethod("getKeyformGridSource").invoke(ms);
        kfGrid.getClass().getMethod("setInitialKeyform", formGuidCls).invoke(kfGrid, fGuid);
        final ClassLoader mLoader = meshSrcCls.getClassLoader();
        final Class<?> extMeshCls = Class.forName(
                "com.live2d.cubism.doc.model.extension.editableMesh.CEditableMeshExtension", true, mLoader);
        final Class<?> ptTypeCls = Class.forName(
                "com.live2d.graphics3d.editableMesh.MPoint2$PointType", true, mLoader);
        final Class<?> acExtCls = Class.forName(
                "com.live2d.cubism.doc.model.extension.ACExtension", false, mLoader);
        final Object ext = extMeshCls.getConstructor(meshSrcCls).newInstance(ms);
        final Object editMesh = extMeshCls.getMethod("getEditableMesh").invoke(ext);
        if (((List<?>) editMesh.getClass().getMethod("getPoints").invoke(editMesh)).isEmpty()) {
            @SuppressWarnings("unchecked")
            final Object ptNormal = Enum.valueOf((Class) ptTypeCls, "NORMAL");
            final Method addPt =
                    editMesh.getClass().getMethod("addPoint", float.class, float.class, ptTypeCls, long.class);
            for (int vi = 0; vi < positions.length / 2; vi++) {
                addPt.invoke(editMesh, positions[vi * 2], positions[vi * 2 + 1], ptNormal, (long) vi);
            }
        }
        meshSrcCls.getMethod("addExtension", acExtCls, int.class).invoke(ms, ext, -1);
        return ms;
    }

    private static void runSyntheticSubtreeDifferentialParity(
            final ClassLoader origLoader,
            final ClassLoader rewLoader,
            final WarpDeformEditTarget target,
            final WarpDeformEditBridge bridge)
            throws Exception {
        final SyntheticModelHarness orig = buildSyntheticHierarchy(origLoader, target);
        final SyntheticModelHarness rew = buildSyntheticHierarchy(rewLoader, target);

        // Frame 0: establish baseline natively across all 40 ArtMeshes.
        orig.invokeUpdate();
        rew.invokeUpdate();
        assertAllMeshesRawFloatParity(orig, rew, "baseline");
        final long baselineFallbacks = bridge.snapshot().get("fallbackModelUpdates");
        assertTrue(baselineFallbacks >= 1L, "expected baseline fallback update");

        final int[] vboBeforeSubtreeA = readMeshPositionVboCounters(rew.subtreeAMeshes);
        final int[] vboBeforeSubtreeB = readMeshPositionVboCounters(rew.subtreeBMeshes);

        // Frames 1..3: mid-tree CRotationDeformer drag (only Subtree B = 4/40 meshes dirty).
        for (int tick = 1; tick <= 3; tick++) {
            dragRotationDeformer(orig.midRot, 12.0f * tick, 2.0f * tick);
            dragRotationDeformer(rew.midRot, 12.0f * tick, 2.0f * tick);
            orig.invokeUpdate();
            rew.invokeUpdate();
            assertAllMeshesRawFloatParity(orig, rew, "mid-rot-drag-" + tick);
        }

        final Map<String, Long> midRotStats = bridge.snapshot();
        assertEquals(3L, midRotStats.get("fastModelUpdates"), () -> "stats=" + midRotStats);
        assertEquals(baselineFallbacks, midRotStats.get("fallbackModelUpdates"), () -> "stats=" + midRotStats);
        assertEquals(3L * 36L, midRotStats.get("skippedMeshes"), () -> "stats=" + midRotStats);
        assertEquals(3L * 4L, midRotStats.get("dirtyMeshesProcessed"), () -> "stats=" + midRotStats);

        final int[] vboAfterMidSubtreeA = readMeshPositionVboCounters(rew.subtreeAMeshes);
        final int[] vboAfterMidSubtreeB = readMeshPositionVboCounters(rew.subtreeBMeshes);
        for (int i = 0; i < vboBeforeSubtreeA.length; i++) {
            assertEquals(vboBeforeSubtreeA[i], vboAfterMidSubtreeA[i], "untouched Subtree A VBO dirtied at " + i);
        }
        for (int i = 0; i < vboBeforeSubtreeB.length; i++) {
            assertEquals(vboBeforeSubtreeB[i] + 3, vboAfterMidSubtreeB[i], "dirty Subtree B VBO missing updates at " + i);
        }

        // Frames 4..5: root WarpDeformer drag (18 depth-1 Warps >= 16, 36 ArtMeshes >= 32, CGlue active).
        final float[] baseGrid = rew.baseRootGridPositions.get(0);
        for (int tick = 1; tick <= 2; tick++) {
            final float[] dragged = baseGrid.clone();
            for (int p = 0; p < dragged.length; p += 2) {
                dragged[p] += 6.25f * tick;
                dragged[p + 1] -= 3.5f * tick;
            }
            dragWarpDeformer(orig.rootWarp, dragged);
            dragWarpDeformer(rew.rootWarp, dragged);
            orig.invokeUpdate();
            rew.invokeUpdate();
            assertAllMeshesRawFloatParity(orig, rew, "root-warp-drag-" + tick);
        }

        final Map<String, Long> rootStats = bridge.snapshot();
        assertEquals(5L, rootStats.get("fastModelUpdates"), () -> "stats=" + rootStats);
        assertTrue(rootStats.get("parallelDeformerLevels") >= 2L, () -> "stats=" + rootStats);
        assertTrue(rootStats.get("parallelMeshBatches") >= 2L, () -> "stats=" + rootStats);
        final int[] vboAfterRootSubtreeB = readMeshPositionVboCounters(rew.subtreeBMeshes);
        assertArrayEquals(vboAfterMidSubtreeB, vboAfterRootSubtreeB, "untouched Subtree B VBO dirtied during root drag");

        // Frame 6: parameter change forces seamless fail-closed fallback + exact parity.
        orig.param.getClass().getMethod("setValue", float.class).invoke(orig.param, 18.0f);
        rew.param.getClass().getMethod("setValue", float.class).invoke(rew.param, 18.0f);
        orig.invokeUpdate();
        rew.invokeUpdate();
        assertAllMeshesRawFloatParity(orig, rew, "param-fallback");
        assertEquals(baselineFallbacks + 1L, bridge.snapshot().get("fallbackModelUpdates"));

        // Frame 7: Undo/Redo reinitInstance on a child deformer clears deformedForm and forces fallback,
        // then Frame 8 resumes fast drag cleanly.
        orig.midRotSrc.getClass().getMethod(
                "reinitInstance", Class.forName("com.live2d.cubism.doc.model.ACParameterControllable", false, orig.loader))
                .invoke(orig.midRotSrc, orig.midRot);
        rew.midRotSrc.getClass().getMethod(
                "reinitInstance", Class.forName("com.live2d.cubism.doc.model.ACParameterControllable", false, rew.loader))
                .invoke(rew.midRotSrc, rew.midRot);
        orig.invokeUpdate();
        rew.invokeUpdate();
        assertAllMeshesRawFloatParity(orig, rew, "undo-reinit-fallback");
        assertEquals(baselineFallbacks + 2L, bridge.snapshot().get("fallbackModelUpdates"));

        dragRotationDeformer(orig.midRot, 55.0f, 9.5f);
        dragRotationDeformer(rew.midRot, 55.0f, 9.5f);
        orig.invokeUpdate();
        rew.invokeUpdate();
        assertAllMeshesRawFloatParity(orig, rew, "post-reinit-fast-resume");
        assertEquals(6L, bridge.snapshot().get("fastModelUpdates"));
    }

    private static void dragRotationDeformer(final Object deformer, final float angleDeg, final float originShift)
            throws Exception {
        final Object src = deformer.getClass().getMethod("getSource").invoke(deformer);
        @SuppressWarnings("unchecked")
        final List<Object> kfs = (List<Object>) src.getClass().getMethod("getKeyforms").invoke(src);
        final Object kf0 = kfs.get(0);
        kf0.getClass().getMethod("setAngle", float.class).invoke(kf0, angleDeg);
        kf0.getClass().getMethod("setOriginX", float.class).invoke(kf0, 12.0f + originShift);
        final Object interp = deformer.getClass().getMethod("getInterpolatedForm").invoke(deformer);
        interp.getClass().getMethod("setAngle", float.class).invoke(interp, angleDeg);
        interp.getClass().getMethod("setOriginX", float.class).invoke(interp, 12.0f + originShift);
        deformer.getClass().getMethod("setDirtyDeformedForm", boolean.class).invoke(deformer, true);
    }

    private static void dragWarpDeformer(final Object deformer, final float[] newPositions) throws Exception {
        final Object src = deformer.getClass().getMethod("getSource").invoke(deformer);
        @SuppressWarnings("unchecked")
        final List<Object> kfs = (List<Object>) src.getClass().getMethod("getKeyforms").invoke(src);
        final Object kf0 = kfs.get(0);
        final float[] kfPos = (float[]) kf0.getClass().getMethod("getPositions").invoke(kf0);
        System.arraycopy(newPositions, 0, kfPos, 0, newPositions.length);
        final Object interp = deformer.getClass().getMethod("getInterpolatedForm").invoke(deformer);
        interp.getClass().getMethod("setPositions", float[].class).invoke(interp, newPositions.clone());
        deformer.getClass().getMethod("setDirtyDeformedForm", boolean.class).invoke(deformer, true);
    }

    private static int[] readMeshPositionVboCounters(final List<Object> meshes) throws Exception {
        final int[] counters = new int[meshes.size()];
        for (int i = 0; i < meshes.size(); i++) {
            final Object m = meshes.get(i);
            final Object container = m.getClass().getMethod("getMeshContainer").invoke(m);
            final Object gmesh = container.getClass().getMethod("getMesh").invoke(container);
            final Object posBuf = gmesh.getClass().getMethod("getPositionsBuffer").invoke(gmesh);
            final Field kField = findFieldOwner(posBuf.getClass(), "k").getDeclaredField("k");
            kField.setAccessible(true);
            counters[i] = kField.getInt(posBuf);
        }
        return counters;
    }

    private static void assertAllMeshesRawFloatParity(
            final SyntheticModelHarness orig, final SyntheticModelHarness rew, final String phase) throws Exception {
        assertEquals(orig.allMeshes.size(), rew.allMeshes.size(), phase);
        for (int i = 0; i < orig.allMeshes.size(); i++) {
            final Object om = orig.allMeshes.get(i);
            final Object rm = rew.allMeshes.get(i);
            final Object oForm = om.getClass().getMethod("getCalculatedForm").invoke(om);
            final Object rForm = rm.getClass().getMethod("getCalculatedForm").invoke(rm);
            final float[] oPos = (float[]) oForm.getClass().getMethod("getPositions").invoke(oForm);
            final float[] rPos = (float[]) rForm.getClass().getMethod("getPositions").invoke(rForm);
            assertRawFloatArraysEqual(oPos, rPos, phase + " form mesh#" + i);

            final Object oCont = om.getClass().getMethod("getMeshContainer").invoke(om);
            final Object rCont = rm.getClass().getMethod("getMeshContainer").invoke(rm);
            final Object oGMesh = oCont.getClass().getMethod("getMesh").invoke(oCont);
            final Object rGMesh = rCont.getClass().getMethod("getMesh").invoke(rCont);
            final float[] oGPos = (float[]) oGMesh.getClass().getMethod("getPositions").invoke(oGMesh);
            final float[] rGPos = (float[]) rGMesh.getClass().getMethod("getPositions").invoke(rGMesh);
            assertRawFloatArraysEqual(oGPos, rGPos, phase + " GMesh2 mesh#" + i);
        }
    }

    private static void assertRawFloatArraysEqual(final float[] expected, final float[] actual, final String context) {
        assertEquals(expected.length, actual.length, context + " length");
        for (int i = 0; i < expected.length; i++) {
            final int idx = i;
            assertEquals(
                    Float.floatToRawIntBits(expected[i]),
                    Float.floatToRawIntBits(actual[i]),
                    () -> context + " bit mismatch at [" + idx + "] expected=" + expected[idx] + " actual=" + actual[idx]);
        }
    }

    private static void runOverlayElisionParityOnComposedLoader(
            final ClassLoader loader, final WarpDeformEditBridge bridge) throws Exception {
        final Class<?> unsafeCls = Class.forName("sun.misc.Unsafe");
        final Field theUnsafe = unsafeCls.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        final Object unsafe = theUnsafe.get(null);
        final Method allocate = unsafeCls.getMethod("allocateInstance", Class.class);

        final Class<?> modelSrcCls = Class.forName("com.live2d.cubism.doc.model.CModelSource", true, loader);
        final Class<?> modelCls = Class.forName("com.live2d.cubism.doc.model.CModel", true, loader);
        final Class<?> defGuidCls = Class.forName("com.live2d.type.CDeformerGuid", true, loader);
        final Class<?> defIdCls = Class.forName("com.live2d.cubism.doc.model.id.CDeformerId", true, loader);
        final Class<?> formGuidCls = Class.forName("com.live2d.type.CFormGuid", true, loader);
        final Class<?> coordCls = Class.forName("com.live2d.doc.CoordType", true, loader);
        final Class<?> rotSrcCls =
                Class.forName("com.live2d.cubism.doc.model.deformer.rotation.CRotationDeformerSource", true, loader);
        final Class<?> rotDefCls =
                Class.forName("com.live2d.cubism.doc.model.deformer.rotation.CRotationDeformer", true, loader);
        final Class<?> rotFormCls =
                Class.forName("com.live2d.cubism.doc.model.deformer.rotation.CRotationDeformerForm", true, loader);
        final Class<?> acDefSrcCls = Class.forName("com.live2d.cubism.doc.model.deformer.ACDeformerSource", true, loader);
        final Class<?> acCtrlSrcCls = Class.forName("com.live2d.cubism.doc.model.ACParameterControllableSource", true, loader);
        final Class<?> renderTypeCls = Class.forName("com.live2d.graphics3d.rendering.RenderType", true, loader);

        final Object coordLocal = coordCls.getMethod("b").invoke(null);
        final Object modelSource = modelSrcCls.getConstructor().newInstance();
        final Object rootPart = modelSrcCls.getMethod("getRootPart").invoke(modelSource);
        final Object defSet = modelSrcCls.getMethod("getDeformerSourceSet").invoke(modelSource);
        final Method addDef = defSet.getClass().getMethod("add", acDefSrcCls, int.class);
        final Method addPart = rootPart.getClass().getMethod("addChild", acCtrlSrcCls, int.class);

        final Object rotAGuid = defGuidCls.getConstructor(UUID.class).newInstance(UUID.nameUUIDFromBytes("ovA".getBytes()));
        final Object rotASrc = rotSrcCls.getConstructor(modelSrcCls).newInstance(modelSource);
        rotSrcCls.getMethod("setGuid", defGuidCls).invoke(rotASrc, rotAGuid);
        rotSrcCls.getMethod("setId", defIdCls).invoke(rotASrc, defIdCls.getConstructor(String.class).newInstance("OvRotA"));
        final Object rotForm = rotFormCls.getConstructor(rotSrcCls, rotDefCls, coordCls).newInstance(rotASrc, null, coordLocal);
        final Object rfGuid = formGuidCls.getConstructor(UUID.class).newInstance(UUID.nameUUIDFromBytes("ovRf".getBytes()));
        rotFormCls.getMethod("setGuid", formGuidCls).invoke(rotForm, rfGuid);
        @SuppressWarnings("unchecked")
        final List<Object> rKfs = (List<Object>) rotSrcCls.getMethod("getKeyforms").invoke(rotASrc);
        rKfs.add(rotForm);
        final Object kfGrid = rotSrcCls.getMethod("getKeyformGridSource").invoke(rotASrc);
        kfGrid.getClass().getMethod("setInitialKeyform", formGuidCls).invoke(kfGrid, rfGuid);
        addDef.invoke(defSet, rotASrc, -1);
        addPart.invoke(rootPart, rotASrc, -1);
        rootPart.getClass().getMethod("setVisibleAndLockInHierarchy").invoke(rootPart);

        final Object renderNormal = Enum.valueOf((Class) renderTypeCls, "NORMAL");
        final Object model = modelSrcCls.getMethod("createModelInstance", renderTypeCls).invoke(modelSource, renderNormal);
        final Object rotDeformer = modelCls.getMethod("getDeformer", defGuidCls).invoke(model, rotAGuid);

        final Class<?> packCls = Class.forName("com.live2d.cubism.pack.CECompletePack", false, loader);
        final Class<?> docCls = Class.forName("com.live2d.cubism.doc.modeling.CModelingDocument", true, loader);
        final Class<?> editModeCls = Class.forName("com.live2d.cubism.doc.modeling.CModelingEditMode_Main", true, loader);
        final Class<?> viewCls = Class.forName("com.live2d.cubism.view.context.CEViewContext_ModelingView", false, loader);
        final Class<?> absViewCls = Class.forName("com.live2d.cubism.view.context.CEViewContext", false, loader);
        final Class<?> camCls = Class.forName("com.live2d.cubism.view.context.CECameraManager", false, loader);
        final Class<?> rsCls = Class.forName("com.live2d.graphics3d.rendering.e", false, loader);
        final Class<?> sgCls = Class.forName("com.live2d.graphics3d.sceneGraph.GSceneGraph", true, loader);
        final Class<?> aGCls = Class.forName("com.live2d.cubism.view.context.actionManager.aG", true, loader);
        final Class<?> nCls = Class.forName("com.live2d.cubism.view.context.actionManager.N", false, loader);
        final Class<?> lazyKtCls = Class.forName("kotlin.LazyKt", true, loader);
        final Class<?> lazyCls = Class.forName("kotlin.Lazy", true, loader);

        final Object pack = allocate.invoke(unsafe, packCls);
        final Object doc = docCls.getConstructor(packCls, modelSrcCls).newInstance(pack, modelSource);
        final Object editMode = editModeCls.getConstructor(packCls, docCls).newInstance(pack, doc);
        final Object sceneGraph = sgCls.getConstructor().newInstance();
        final Object camMgr = allocate.invoke(unsafe, camCls);
        final Object renderSys = allocate.invoke(unsafe, rsCls);
        final Object view = allocate.invoke(unsafe, viewCls);
        setDeclaredField(absViewCls, view, "doc", doc);
        setDeclaredField(absViewCls, view, "currentEditMode", editMode);
        setDeclaredField(absViewCls, view, "cameraManager", camMgr);
        setDeclaredField(absViewCls, view, "renderSystem", renderSys);

        final Object packAction = allocate.invoke(unsafe, aGCls);
        setDeclaredField(nCls, packAction, "c", view);
        setDeclaredField(aGCls, packAction, "b", editMode);
        final Object emptyLazy = java.lang.reflect.Proxy.newProxyInstance(
                loader,
                new Class<?>[] {lazyCls},
                (proxy, method, args) -> "isInitialized".equals(method.getName()) ? Boolean.TRUE : List.of());
        for (Class<?> cur = aGCls; cur != Object.class; cur = cur.getSuperclass()) {
            for (final Field f : cur.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                if (lazyCls.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    f.set(packAction, emptyLazy);
                }
            }
        }
        // Populate view's model field if declared so aG.i() resolves cleanly if queried.
        for (Class<?> cur = viewCls; cur != Object.class; cur = cur.getSuperclass()) {
            for (final Field f : cur.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) && f.getType() == modelCls) {
                    f.setAccessible(true);
                    f.set(view, model);
                }
            }
        }

        final Class<?> kbCls = Class.forName("com.live2d.cubism.view.context.K$b", true, loader);
        final Object kbInstance = kbCls.getField("a").get(null);
        final Class<?> rotModeCls = Class.forName("com.live2d.cubism.setting.AppSetting$RotationDeformer$Mode", true, loader);
        final Object rotModeAuto = rotModeCls.getField("a").get(null);
        final Class<?> fn1Cls = Class.forName("kotlin.jvm.functions.Function1", true, loader);
        final Object falsePred = java.lang.reflect.Proxy.newProxyInstance(
                loader, new Class<?>[] {fn1Cls}, (proxy, method, args) -> Boolean.FALSE);
        final Class<?> acDefCls = Class.forName("com.live2d.cubism.doc.model.deformer.ACDeformer", false, loader);
        final Method ovMethod = kbCls.getMethod(
                "a", acDefCls, sgCls, boolean.class, boolean.class, rotModeCls, aGCls, fn1Cls);

        // Pass 1: native overlay builds Draw child & marks volatile.
        ovMethod.invoke(kbInstance, rotDeformer, sceneGraph, false, true, rotModeAuto, packAction, falsePred);
        final long updatesAfterPass1 = bridge.snapshot().get("overlayUpdates");
        assertTrue(updatesAfterPass1 >= 1L, "expected initial K$b.a overlay update");

        // Clear SceneGraph volatile set and invoke Pass 2 unchanged -> must elide update & keep setVolatile!
        final Field willRenderF = findFieldOwner(sgCls, "volatileEntities_willRender")
                .getDeclaredField("volatileEntities_willRender");
        willRenderF.setAccessible(true);
        @SuppressWarnings("unchecked")
        final Set<Object> willRender = (Set<Object>) willRenderF.get(sceneGraph);
        willRender.clear();

        ovMethod.invoke(kbInstance, rotDeformer, sceneGraph, false, true, rotModeAuto, packAction, falsePred);
        final Map<String, Long> afterElide = bridge.snapshot();
        assertTrue(afterElide.get("overlayElisions") >= 1L, () -> "expected overlayElisions, got " + afterElide);
        assertEquals(updatesAfterPass1, afterElide.get("overlayUpdates"));
        assertEquals(1, willRender.size(), "elided overlay must preserve GSceneGraph.setVolatile registration");

        // Mutate calculatedForm angle and invoke Pass 3 -> must detect geometry drift and re-update!
        final Object calcForm = rotDefCls.getMethod("getCalculatedForm").invoke(rotDeformer);
        calcForm.getClass().getMethod("setAngle", float.class).invoke(calcForm, 25.0f);
        ovMethod.invoke(kbInstance, rotDeformer, sceneGraph, false, true, rotModeAuto, packAction, falsePred);
        assertTrue(bridge.snapshot().get("overlayUpdates") > updatesAfterPass1, "dirty geometry must invalidate overlay elision");
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

    private static void verifyFormReinitParityAndReuse(final ClassLoader origLoader, final ClassLoader rewLoader)
            throws Exception {
        final Class<?> rewFormCls =
                Class.forName(WarpDeformEditTarget.WARP_FORM_OWNER.replace('/', '.'), true, rewLoader);
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
        final Object copyParam = Class.forName("com.live2d.core.a", true, rewLoader)
                .getConstructor()
                .newInstance();

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
        final Class<?> rewFormCls =
                Class.forName(WarpDeformEditTarget.WARP_FORM_OWNER.replace('/', '.'), true, rewLoader);
        final Object[] forms = createInitializedForms(rewLoader, rewFormCls);
        final Object a = forms[0];
        final Object b = forms[1];
        final Method setPositions = rewFormCls.getMethod("setPositions", float[].class);
        final Method getPositions = rewFormCls.getMethod("getPositions");
        final Method reinit = rewFormCls.getMethod(
                "reinit",
                Class.forName("com.live2d.core.ICopyable", false, rewLoader),
                Class.forName("com.live2d.core.a", false, rewLoader));
        final Object copyParam = Class.forName("com.live2d.core.a", true, rewLoader)
                .getConstructor()
                .newInstance();

        setPositions.invoke(a, new float[] {5, 6, 7, 8});
        final float[] oldB = new float[4];
        setPositions.invoke(b, oldB);
        reinit.invoke(b, a, copyParam);
        assertNotSame(oldB, getPositions.invoke(b), "closed bridge must fall back to Arrays.copyOf");
        assertArrayEquals(new float[] {5, 6, 7, 8}, (float[]) getPositions.invoke(b), 0.0f);
    }

    private static void verifySimpleGridAndTransformParity(final ClassLoader origLoader, final ClassLoader rewLoader)
            throws Exception {
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
            final DrawResult origRes,
            final ClassLoader rewLoader,
            final float[] gridPos,
            final WarpDeformEditBridge bridge)
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
        final Object sg =
                sgCls.getConstructor(int.class, int.class, float[].class).newInstance(col, row, pts);
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
        final Class<?> icGrid = Class.forName("com.live2d.cubism.doc.model.deformer.warp.grid.ICGrid", true, loader);
        final Class<?> vCls = Class.forName(WarpDeformEditTarget.GVECTOR2_OWNER.replace('/', '.'), true, loader);
        final Object sg =
                sgCls.getConstructor(int.class, int.class, float[].class).newInstance(col, row, pts);
        final Object rect = rectCls.getConstructor(float.class, float.class, float.class, float.class)
                .newInstance(0f, 0f, 100f, 200f);
        final Object gt =
                gtCls.getConstructor(rectCls, icGrid, int.class, int.class).newInstance(rect, sg, col, row);
        final Object src = vCls.getConstructor(float.class, float.class).newInstance(x, y);
        final Object dst = vCls.getConstructor().newInstance();
        gtCls.getMethod("inverseTransform_3_3_Quad", vCls, vCls).invoke(gt, src, dst);
        return new float[] {
            (float) vCls.getMethod("getX").invoke(dst),
            (float) vCls.getMethod("getY").invoke(dst)
        };
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
        final Class<?> icGrid = Class.forName("com.live2d.cubism.doc.model.deformer.warp.grid.ICGrid", true, loader);
        final Class<?> vCls = Class.forName(WarpDeformEditTarget.GVECTOR2_OWNER.replace('/', '.'), true, loader);
        final Object sg =
                sgCls.getConstructor(int.class, int.class, float[].class).newInstance(col, row, pts);
        final Object rect = rectCls.getConstructor(float.class, float.class, float.class, float.class)
                .newInstance(0f, 0f, 100f, 100f);
        final Object gt =
                gtCls.getConstructor(rectCls, icGrid, int.class, int.class).newInstance(rect, sg, col, row);
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
                refs[ix][iy] =
                        bpCls.getConstructor(vCls, vCls, vCls, vCls, vCls).newInstance(anchor, cn, cs, cw, ce);
            }
        }
        return (float[])
                bgCls.getMethod("createPointsOnBezier", int.class, int.class).invoke(bg, col, row);
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
        final Object color = cColorCls
                .getConstructor(float.class, float.class, float.class, float.class)
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
        final Object lineNormalRenderer = lineNormalField
                .get(draw)
                .getClass()
                .getMethod("getLineRenderer")
                .invoke(lineNormalField.get(draw));

        final Field lineDetailField = drawCls.getDeclaredField("eWarpGridLineSelected");
        lineDetailField.setAccessible(true);
        final Object lineDetailRenderer = lineDetailField
                .get(draw)
                .getClass()
                .getMethod("getLineRenderer")
                .invoke(lineDetailField.get(draw));

        final Field selectedField = drawCls.getDeclaredField("eWarpGridSelectedPoint");
        selectedField.setAccessible(true);
        final Object selectedRenderer =
                selectedField.get(draw).getClass().getMethod("getPointRenderer").invoke(selectedField.get(draw));

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
                                && p.getFileName().toString().endsWith(".jar"))
                        .toList()) {
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
                                && p.getFileName().toString().endsWith(".jar"))
                        .toList()) {
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
            if (name.startsWith("com.jogamp.")
                    && super.findResource(name.replace('.', '/') + CLASS_EXTENSION) != null) {
                return true;
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
