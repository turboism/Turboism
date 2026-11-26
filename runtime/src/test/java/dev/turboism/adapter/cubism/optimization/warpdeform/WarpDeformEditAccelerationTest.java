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
import dev.turboism.runtime.exec.RuntimeThreads;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.AWTEventListenerProxy;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeListenerProxy;
import java.io.File;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.UIDefaults;
import javax.swing.UIManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

/**
 * Unit, bytecode-admission, live runtime parity, and overlay dirty-gating tests for edit-mode warp acceleration.
 */
class WarpDeformEditAccelerationTest {

    private static final String CLASS_EXTENSION = ".class";
    private static final String TEST_JAR_PROPERTY = "turboism.test.cubismEditorJar";
    private static final long BOOTSTRAP_CHILD_TIMEOUT_SECONDS = 15L;
    private static final String JAVA_HOME_PROPERTY = "java.home";
    private static final String JAVA_BIN_DIR = "bin";
    private static final String JAVA_EXECUTABLE = "java";
    private static final String BOOTCLASSPATH_ARG_PREFIX = "-Xbootclasspath/a:";
    private static final String CLASSPATH_FLAG = "-classpath";
    private static final String JAVA_CLASS_PATH_PROPERTY = "java.class.path";
    private static final String UI_CLASS_LOADER_KEY = "ClassLoader";
    private static final String APPLE_MENU_BAR_PROPERTY = "apple.laf.useScreenMenuBar";
    private static final String FLATLAF_MENU_BAR_PROPERTY = "flatlaf.menuBarEmbedded";
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
    void subtreeParallelExecutorHandlesNullAndBootstrapClassLoaders() throws Exception {
        final ClassLoader fallbackLoader = RuntimeThreads.contextClassLoader(SubtreeParallelExecutor.class);
        try (SubtreeParallelExecutor defaultExec = new SubtreeParallelExecutor();
                SubtreeParallelExecutor nullExec = new SubtreeParallelExecutor(null);
                URLClassLoader customLoader = new URLClassLoader(new URL[0], fallbackLoader);
                SubtreeParallelExecutor customExec = new SubtreeParallelExecutor(customLoader)) {
            BootstrapSubtreeExecutorChild.runAndVerify(defaultExec, fallbackLoader);
            BootstrapSubtreeExecutorChild.runAndVerify(nullExec, fallbackLoader);
            BootstrapSubtreeExecutorChild.runAndVerify(customExec, customLoader);
        }

        final String runtimeClasses = Path.of(SubtreeParallelExecutor.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI())
                .toString();
        final String testClasses = Path.of(BootstrapSubtreeExecutorChild.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI())
                .toString();
        final Process child = new ProcessBuilder(
                        Path.of(System.getProperty(JAVA_HOME_PROPERTY), JAVA_BIN_DIR, JAVA_EXECUTABLE)
                                .toString(),
                        BOOTCLASSPATH_ARG_PREFIX + runtimeClasses + File.pathSeparator + testClasses,
                        CLASSPATH_FLAG,
                        System.getProperty(JAVA_CLASS_PATH_PROPERTY),
                        BootstrapSubtreeExecutorChild.class.getName())
                .redirectErrorStream(true)
                .start();
        assertTrue(child.waitFor(BOOTSTRAP_CHILD_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        final String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, child.exitValue(), output);
    }

    @Test
    void transformsAndDefinesBytecodeAcrossAllAvailableReviewedCubismJars(@TempDir final Path tempDir)
            throws Exception {
        final List<Path> jars = availableReviewedJars();
        assumeTrue(!jars.isEmpty(), "no reviewed Cubism Editor JARs found for matrix verification");

        final List<String> strictManifestLines = new ArrayList<>();
        for (final Path jar : jars) {
            final HostArtifactDigest digest = HostArtifactDigest.cached(jar);
            final WarpDeformEditTarget target = WarpDeformEditTarget.of(digest)
                    .orElseThrow(() -> new AssertionError("missing target table entry for " + jar));
            final Map<String, byte[]> refs = readTargetBytes(jar, target.targetClasses());
            final ProtectionDomain validDomain = protectionDomain(jar);
            final ProtectionDomain wrongDomain = protectionDomain(Path.of("/tmp/wrong-source.jar"));

            final URL[] urls = hostUrls(jar);
            final Map<String, byte[]> rewrittenMap = new LinkedHashMap<>();
            final Path patchRoot = tempDir.resolve(target.version());
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
                    final Path patchFile = patchRoot.resolve(internalName + CLASS_EXTENSION);
                    Files.createDirectories(patchFile.getParent());
                    Files.write(patchFile, rewritten);
                    strictManifestLines.add(target.version() + "\t" + jar + "\t" + patchRoot + "\t" + internalName);
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
        final Path manifest = tempDir.resolve("strict-verify-manifest.txt");
        Files.write(manifest, strictManifestLines);
        runStrictVerifyAllSubprocess(manifest, strictManifestLines.size());
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
                        new RewrittenHostClassLoader(jar, urls, getClass().getClassLoader());
                SwingGlobalStateGuard ignored = SwingGlobalStateGuard.capture()) {
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
                verifyWarpSourceReinitPosReuseAndNoUndoAliasing(origLoader, rewLoader, bridge);
                verifyRotationTransformScratchParity(origLoader, rewLoader, bridge);
                verifySimpleGridAndTransformParity(origLoader, rewLoader);
                verifyBezierPointsParity(origLoader, rewLoader);
                verifyWarpDrawParityAndDirtyGating(origRes, rewLoader, gridPos, bridge);
                verifyActionFloatLoopParity(origLoader, rewLoader, target);
                verifyExtensionBezierParity(origLoader, rewLoader, target, bridge);

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
        final Path legacyJar500 = LOCAL_ARTIFACTS_ROOT.resolve("5.0.00").resolve("Live2D_Cubism.jar");
        if (Files.isRegularFile(legacyJar500)) {
            verifyKeyformParityStandaloneJar(legacyJar500);
        }
    }

    private void verifyEditSubtreeParallelParityAndCompositionForJar(final Path jar) throws Throwable {
        final HostArtifactDigest digest = HostArtifactDigest.cached(jar);
        final WarpDeformEditTarget warpEditTarget =
                WarpDeformEditTarget.of(digest).orElseThrow();
        final ModelUpdateSkipTarget updateSkipTarget =
                ModelUpdateSkipTarget.of(digest).orElseThrow();
        final WarpGridInterpolationTarget warpGridTarget =
                WarpGridInterpolationTarget.of(digest).orElseThrow();
        final MemoryLeakMitigationTarget memoryTarget =
                MemoryLeakMitigationTarget.of(digest).orElseThrow();
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
            final byte[] afterWarp =
                    firstWarp.transform(null, reverseLoader, updaterOwner, null, domain, refs.get(updaterOwner));
            assertNotNull(afterWarp, firstWarp::failure);
            final byte[] afterBoth =
                    secondSkip.transform(null, reverseLoader, updateSkipTarget.owner(), null, domain, afterWarp);
            assertNotNull(afterBoth, secondSkip::failure);
            assertEquals(1, secondSkip.matches());
            assertEquals(2, firstWarp.matchesFor(updaterOwner));
        }

        // Compose all five default-ON transformers in DeclarativeHooks order and load with -Xverify:all semantics.
        final Map<String, byte[]> composedOverrides = new LinkedHashMap<>();
        try (RewrittenHostClassLoader txLoader =
                new RewrittenHostClassLoader(jar, urls, getClass().getClassLoader())) {
            final ModelUpdateSkipTransformer updateSkipTx =
                    new ModelUpdateSkipTransformer(txLoader, jar, refs.get(updateSkipTarget.owner()), updateSkipTarget);
            final WarpGridInterpolationTransformer warpGridTx = new WarpGridInterpolationTransformer(
                    txLoader, jar, refs.get(WarpGridInterpolationTarget.OWNER), warpGridTarget);
            final WarpDeformEditTransformer warpEditTx =
                    new WarpDeformEditTransformer(txLoader, jar, refs, warpEditTarget);
            final MemoryLeakMitigationTransformer memoryTx =
                    new MemoryLeakMitigationTransformer(txLoader, jar, memoryTarget);
            final List<SkippedFrameUploadElisionTransformer> uploadTxs = new ArrayList<>();
            for (final String vboOwner : SkippedFrameUploadElisionTarget.OWNERS) {
                uploadTxs.add(new SkippedFrameUploadElisionTransformer(txLoader, jar, refs.get(vboOwner), vboOwner));
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
        try (RewrittenHostClassLoader origLoader =
                        new RewrittenHostClassLoader(jar, urls, getClass().getClassLoader());
                RewrittenHostClassLoader rewLoader =
                        new RewrittenHostClassLoader(jar, urls, getClass().getClassLoader());
                SwingGlobalStateGuard ignored = SwingGlobalStateGuard.capture()) {
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

                runSyntheticSubtreeDifferentialParity(origLoader, rewLoader, warpEditTarget, warpEditBridge);
                runSelectorScopedAndEditingTransitionParity(origLoader, rewLoader, warpEditTarget, warpEditBridge);
                runOverlayElisionParityOnComposedLoader(rewLoader, warpEditBridge);
                runDeepDagAndMorphTargetParity(origLoader, rewLoader, warpEditTarget, warpEditBridge);
                runPartVisibilityAndMeshKeyformInvalidationParity(
                        origLoader, rewLoader, warpEditTarget, warpEditBridge);
                runLocalMeshEpochFastSkipAndMutationParity(origLoader, rewLoader, warpEditTarget, warpEditBridge);
                runSelectionDrawDirtyGatingParityOnComposedLoader(rewLoader, warpEditTarget, warpEditBridge);
                runKeyformGridAndBindingParity(origLoader, rewLoader, warpEditBridge);
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
            invokeUpdate(null, null);
        }

        void invokeUpdate(final Object doc, final Object viewCtx) throws Exception {
            if (hasConflictFlag) {
                updateModelMethod.invoke(updater, model, updCtx, doc, viewCtx, null, false);
            } else {
                updateModelMethod.invoke(updater, model, updCtx, doc, viewCtx, null);
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
        final Class<?> warpSrcCls =
                Class.forName(WarpDeformEditTarget.WARP_SOURCE_OWNER.replace('/', '.'), true, loader);
        final Class<?> warpFormCls =
                Class.forName(WarpDeformEditTarget.WARP_FORM_OWNER.replace('/', '.'), true, loader);
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
        final Class<?> glueFormCls = Class.forName("com.live2d.cubism.doc.model.affecter.glue.CGlueForm", true, loader);
        final Class<?> renderTypeCls = Class.forName("com.live2d.graphics3d.rendering.RenderType", true, loader);
        final Class<?> acDefSrcCls =
                Class.forName("com.live2d.cubism.doc.model.deformer.ACDeformerSource", true, loader);
        final Class<?> acDrawSrcCls =
                Class.forName("com.live2d.cubism.doc.model.drawable.ACDrawableSource", true, loader);
        final Class<?> acAffSrcCls =
                Class.forName("com.live2d.cubism.doc.model.affecter.ACAffecterSource", true, loader);
        final Class<?> acCtrlSrcCls =
                Class.forName("com.live2d.cubism.doc.model.ACParameterControllableSource", true, loader);

        final Object coordLocal = coordCls.getMethod("b").invoke(null);
        final Object coordCanvas = coordCls.getMethod("c").invoke(null);
        final Object modelSource = modelSrcCls.getConstructor().newInstance();
        final Object rootPart = modelSrcCls.getMethod("getRootPart").invoke(modelSource);
        final Object defSet = modelSrcCls.getMethod("getDeformerSourceSet").invoke(modelSource);
        final Object drawSet = modelSrcCls.getMethod("getDrawableSourceSet").invoke(modelSource);
        final Object affSet = modelSrcCls.getMethod("getAffecterSourceSet").invoke(modelSource);
        final Object paramSet = modelSrcCls.getMethod("getParameterSourceSet").invoke(modelSource);

        final Object paramGuid =
                paramGuidCls.getConstructor(UUID.class).newInstance(UUID.nameUUIDFromBytes("pX".getBytes()));
        final Object paramSrc = paramSrcCls.getConstructor().newInstance();
        paramSrcCls.getMethod("setGuid", paramGuidCls).invoke(paramSrc, paramGuid);
        paramSrcCls
                .getMethod("setId", paramIdCls)
                .invoke(paramSrc, paramIdCls.getConstructor(String.class).newInstance("ParamX"));
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
        final Object rootWarpGuid =
                defGuidCls.getConstructor(UUID.class).newInstance(UUID.nameUUIDFromBytes("rootWarp".getBytes()));
        final Object rootWarpSrc = createWarpDeformerSource(
                modelSrcCls,
                modelSource,
                warpSrcCls,
                warpFormCls,
                defGuidCls,
                defIdCls,
                formGuidCls,
                coordCls,
                rootWarpGuid,
                "RootWarp",
                null,
                coordLocal,
                baseGrid3x3);
        addDef.invoke(defSet, rootWarpSrc, -1);
        addPartChild.invoke(rootPart, rootWarpSrc, -1);

        // Bind two keys on ParamX on rootWarp so parameter changes mutate geometry cleanly.
        final Object rootKfGrid = warpSrcCls.getMethod("getKeyformGridSource").invoke(rootWarpSrc);
        rootKfGrid.getClass().getMethod("addKey", float.class, paramGuidCls).invoke(rootKfGrid, 0.0f, paramGuid);
        rootKfGrid.getClass().getMethod("addKey", float.class, paramGuidCls).invoke(rootKfGrid, 30.0f, paramGuid);
        @SuppressWarnings("unchecked")
        final List<Object> rootKeys =
                (List<Object>) warpSrcCls.getMethod("getKeyforms").invoke(rootWarpSrc);
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
            final Object cGuid =
                    defGuidCls.getConstructor(UUID.class).newInstance(UUID.nameUUIDFromBytes(("lvl1_" + c).getBytes()));
            final Object childWarp = createWarpDeformerSource(
                    modelSrcCls,
                    modelSource,
                    warpSrcCls,
                    warpFormCls,
                    defGuidCls,
                    defIdCls,
                    formGuidCls,
                    coordCls,
                    cGuid,
                    "ChildWarp_" + c,
                    rootWarpGuid,
                    coordLocal,
                    baseGrid3x3);
            addDef.invoke(defSet, childWarp, -1);
            addPartChild.invoke(rootPart, childWarp, -1);
            lvl1WarpGuids.add(cGuid);

            for (int m = 0; m < 2; m++) {
                final String mName = "MeshA_" + c + "_" + m;
                final Object mGuid =
                        drawGuidCls.getConstructor(UUID.class).newInstance(UUID.nameUUIDFromBytes(mName.getBytes()));
                final Object ms = createArtMeshSource(
                        modelSrcCls,
                        modelSource,
                        meshSrcCls,
                        meshFormCls,
                        defGuidCls,
                        drawGuidCls,
                        drawIdCls,
                        formGuidCls,
                        coordCls,
                        mGuid,
                        mName,
                        cGuid,
                        coordCanvas,
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
        final Object glueGuid =
                affGuidCls.getConstructor(UUID.class).newInstance(UUID.nameUUIDFromBytes("GlueAB".getBytes()));
        glueSrcCls.getMethod("setGuid", affGuidCls).invoke(glueSrc, glueGuid);
        glueSrcCls
                .getMethod("setId", affIdCls)
                .invoke(glueSrc, affIdCls.getConstructor(String.class).newInstance("GlueAB"));
        final Object glueForm = glueFormCls
                .getConstructor(
                        glueSrcCls, Class.forName("com.live2d.cubism.doc.model.affecter.glue.CGlue", false, loader))
                .newInstance(glueSrc, null);
        final Object glueFormGuid =
                formGuidCls.getConstructor(UUID.class).newInstance(UUID.nameUUIDFromBytes("gf0".getBytes()));
        glueFormCls.getMethod("setGuid", formGuidCls).invoke(glueForm, glueFormGuid);
        @SuppressWarnings("unchecked")
        final List<Object> glueKfs =
                (List<Object>) glueSrcCls.getMethod("getKeyforms").invoke(glueSrc);
        glueKfs.add(glueForm);
        final Object glueKfGrid = glueSrcCls.getMethod("getKeyformGridSource").invoke(glueSrc);
        glueKfGrid.getClass().getMethod("setInitialKeyform", formGuidCls).invoke(glueKfGrid, glueFormGuid);
        glueSrcCls
                .getMethod(
                        "addBindVertex_forMeshEditor",
                        meshSrcCls,
                        long.class,
                        float.class,
                        meshSrcCls,
                        long.class,
                        float.class)
                .invoke(glueSrc, subtreeAMeshSources.get(0), 0L, 0.5f, subtreeAMeshSources.get(2), 0L, 0.5f);
        addAff.invoke(affSet, glueSrc, -1);
        addPartChild.invoke(rootPart, glueSrc, -1);

        // Subtree B: depth 0 Warp -> mid-tree CRotationDeformer -> 4 ArtMeshes.
        final Object sibWarpGuid =
                defGuidCls.getConstructor(UUID.class).newInstance(UUID.nameUUIDFromBytes("sibWarp".getBytes()));
        final Object sibWarpSrc = createWarpDeformerSource(
                modelSrcCls,
                modelSource,
                warpSrcCls,
                warpFormCls,
                defGuidCls,
                defIdCls,
                formGuidCls,
                coordCls,
                sibWarpGuid,
                "SiblingRootWarp",
                null,
                coordLocal,
                baseGrid3x3);
        addDef.invoke(defSet, sibWarpSrc, -1);
        addPartChild.invoke(rootPart, sibWarpSrc, -1);

        final Object midRotGuid =
                defGuidCls.getConstructor(UUID.class).newInstance(UUID.nameUUIDFromBytes("midRot".getBytes()));
        final Object midRotSrc = rotSrcCls.getConstructor(modelSrcCls).newInstance(modelSource);
        rotSrcCls.getMethod("setGuid", defGuidCls).invoke(midRotSrc, midRotGuid);
        rotSrcCls
                .getMethod("setId", defIdCls)
                .invoke(midRotSrc, defIdCls.getConstructor(String.class).newInstance("MidRot"));
        rotSrcCls.getMethod("setTargetDeformerGuid", defGuidCls).invoke(midRotSrc, sibWarpGuid);
        final Object rotDefForm = rotFormCls
                .getConstructor(
                        rotSrcCls,
                        Class.forName("com.live2d.cubism.doc.model.deformer.rotation.CRotationDeformer", false, loader),
                        coordCls)
                .newInstance(midRotSrc, null, coordLocal);
        final Object rotFormGuid =
                formGuidCls.getConstructor(UUID.class).newInstance(UUID.nameUUIDFromBytes("rf0".getBytes()));
        rotFormCls.getMethod("setGuid", formGuidCls).invoke(rotDefForm, rotFormGuid);
        rotFormCls.getMethod("setOriginX", float.class).invoke(rotDefForm, 12.0f);
        rotFormCls.getMethod("setOriginY", float.class).invoke(rotDefForm, 8.0f);
        rotFormCls.getMethod("setScale", float.class).invoke(rotDefForm, 1.0f);
        @SuppressWarnings("unchecked")
        final List<Object> rotKfs =
                (List<Object>) rotSrcCls.getMethod("getKeyforms").invoke(midRotSrc);
        rotKfs.add(rotDefForm);
        final Object rotKfGrid = rotSrcCls.getMethod("getKeyformGridSource").invoke(midRotSrc);
        rotKfGrid.getClass().getMethod("setInitialKeyform", formGuidCls).invoke(rotKfGrid, rotFormGuid);
        addDef.invoke(defSet, midRotSrc, -1);
        addPartChild.invoke(rootPart, midRotSrc, -1);

        final List<Object> subtreeBMeshSources = new ArrayList<>();
        for (int m = 0; m < 4; m++) {
            final String mName = "MeshB_" + m;
            final Object mGuid =
                    drawGuidCls.getConstructor(UUID.class).newInstance(UUID.nameUUIDFromBytes(mName.getBytes()));
            final Object ms = createArtMeshSource(
                    modelSrcCls,
                    modelSource,
                    meshSrcCls,
                    meshFormCls,
                    defGuidCls,
                    drawGuidCls,
                    drawIdCls,
                    formGuidCls,
                    coordCls,
                    mGuid,
                    mName,
                    midRotGuid,
                    coordCanvas,
                    new float[] {20f + m, 20f, 40f + m, 20f, 40f + m, 40f, 20f + m, 40f});
            addDraw.invoke(drawSet, ms, -1);
            addPartChild.invoke(rootPart, ms, -1);
            subtreeBMeshSources.add(ms);
        }

        rootPart.getClass().getMethod("setVisibleAndLockInHierarchy").invoke(rootPart);
        modelSrcCls.getMethod("setModelEditing", boolean.class).invoke(modelSource, true);

        final Object renderNormal = Enum.valueOf((Class) renderTypeCls, "NORMAL");
        final Object model =
                modelSrcCls.getMethod("createModelInstance", renderTypeCls).invoke(modelSource, renderNormal);
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
        final List<Object> allMeshes =
                (List<Object>) modelCls.getMethod("getAllArtMeshes").invoke(model);

        final Object paramSetInst = modelCls.getMethod("getParameterSet").invoke(model);
        @SuppressWarnings("unchecked")
        final List<Object> params = (List<Object>)
                paramSetInst.getClass().getMethod("getParameters").invoke(paramSetInst);
        final Object param = params.get(0);

        final String updaterDot = target.updaterOwner().replace('/', '.');
        final Class<?> updaterCls = Class.forName(updaterDot, true, loader);
        final Object updater = updaterCls.getField("a").get(null);
        final String updCtxDot = resolveUpdaterContextClass(loader, target).getName();
        final Class<?> updCtxCls = Class.forName(updCtxDot, true, loader);
        final Class<?> docCls = Class.forName("com.live2d.cubism.doc.modeling.CModelingDocument", false, loader);
        final Class<?> viewCls = Class.forName("com.live2d.cubism.view.context.CEViewContext", false, loader);
        final Class<?> paramCtxCls = resolveContextParamClass(updaterCls, modelCls, updCtxCls, docCls, viewCls);
        final boolean conflictFlag = updater.getClass().getName() != null
                && hasUpdateModelConflictFlag(updaterCls, modelCls, updCtxCls, docCls, viewCls, paramCtxCls);
        final Method updateModelMethod = conflictFlag
                ? updaterCls.getMethod("a", modelCls, updCtxCls, docCls, viewCls, paramCtxCls, boolean.class)
                : updaterCls.getMethod("a", modelCls, updCtxCls, docCls, viewCls, paramCtxCls);
        final Object updCtx = updCtxCls.getConstructor(viewCls).newInstance((Object) null);

        final List<float[]> baseGrids = List.of(baseGrid3x3);
        return new SyntheticModelHarness(
                loader,
                modelSource,
                model,
                rootWarpSrc,
                rootWarp,
                midRotSrc,
                midRot,
                paramSrc,
                param,
                updater,
                updateModelMethod,
                updCtx,
                subtreeAMeshes,
                subtreeBMeshes,
                allMeshes,
                baseGrids,
                conflictFlag);
    }

    private static Class<?> resolveUpdaterContextClass(final ClassLoader loader, final WarpDeformEditTarget target)
            throws ClassNotFoundException {
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
        warpSrcCls
                .getMethod("setId", defIdCls)
                .invoke(ws, defIdCls.getConstructor(String.class).newInstance(id));
        warpSrcCls.getMethod("setCol", int.class).invoke(ws, 2);
        warpSrcCls.getMethod("setRow", int.class).invoke(ws, 2);
        warpSrcCls.getMethod("setQuadTransform", boolean.class).invoke(ws, true);
        if (parentGuid != null) {
            warpSrcCls.getMethod("setTargetDeformerGuid", defGuidCls).invoke(ws, parentGuid);
        }
        final Object wf = warpFormCls
                .getConstructor(
                        warpSrcCls,
                        Class.forName(
                                WarpDeformEditTarget.WARP_DEFORMER_OWNER.replace('/', '.'),
                                false,
                                warpSrcCls.getClassLoader()),
                        coordCls)
                .newInstance(ws, null, coordLocal);
        final Object fGuid =
                formGuidCls.getConstructor(UUID.class).newInstance(UUID.nameUUIDFromBytes((id + "_kf").getBytes()));
        warpFormCls.getMethod("setGuid", formGuidCls).invoke(wf, fGuid);
        warpFormCls.getMethod("setPositions", float[].class).invoke(wf, gridPos.clone());
        @SuppressWarnings("unchecked")
        final List<Object> kfs =
                (List<Object>) warpSrcCls.getMethod("getKeyforms").invoke(ws);
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
        meshSrcCls
                .getMethod("setId", drawIdCls)
                .invoke(ms, drawIdCls.getConstructor(String.class).newInstance(id));
        meshSrcCls.getMethod("setTargetDeformerGuid", defGuidCls).invoke(ms, parentGuid);
        meshSrcCls.getMethod("setPositions", float[].class).invoke(ms, positions.clone());
        meshSrcCls.getMethod("setIndices", int[].class).invoke(ms, new int[] {0, 1, 2, 0, 2, 3});
        meshSrcCls.getMethod("setUvs", float[].class).invoke(ms, new float[] {0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f});
        final Object mf = meshFormCls
                .getConstructor(
                        meshSrcCls,
                        Class.forName(
                                "com.live2d.cubism.doc.model.drawable.artMesh.CArtMesh",
                                false,
                                meshSrcCls.getClassLoader()),
                        coordCls)
                .newInstance(ms, null, coordCanvas);
        final Object fGuid =
                formGuidCls.getConstructor(UUID.class).newInstance(UUID.nameUUIDFromBytes((id + "_kf").getBytes()));
        meshFormCls.getMethod("setGuid", formGuidCls).invoke(mf, fGuid);
        meshFormCls.getMethod("setPositions", float[].class).invoke(mf, positions.clone());
        @SuppressWarnings("unchecked")
        final List<Object> kfs =
                (List<Object>) meshSrcCls.getMethod("getKeyforms").invoke(ms);
        kfs.add(mf);
        final Object kfGrid = meshSrcCls.getMethod("getKeyformGridSource").invoke(ms);
        kfGrid.getClass().getMethod("setInitialKeyform", formGuidCls).invoke(kfGrid, fGuid);
        final ClassLoader mLoader = meshSrcCls.getClassLoader();
        final Class<?> extMeshCls = Class.forName(
                "com.live2d.cubism.doc.model.extension.editableMesh.CEditableMeshExtension", true, mLoader);
        final Class<?> ptTypeCls = Class.forName("com.live2d.graphics3d.editableMesh.MPoint2$PointType", true, mLoader);
        final Class<?> acExtCls = Class.forName("com.live2d.cubism.doc.model.extension.ACExtension", false, mLoader);
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
        assertEquals(3L * 36L, midRotStats.get("appearanceSkipped"), () -> "stats=" + midRotStats);
        assertEquals(0L, midRotStats.get("fullArrayHashes"), () -> "stats=" + midRotStats);

        final int[] vboAfterMidSubtreeA = readMeshPositionVboCounters(rew.subtreeAMeshes);
        final int[] vboAfterMidSubtreeB = readMeshPositionVboCounters(rew.subtreeBMeshes);
        for (int i = 0; i < vboBeforeSubtreeA.length; i++) {
            assertEquals(vboBeforeSubtreeA[i], vboAfterMidSubtreeA[i], "untouched Subtree A VBO dirtied at " + i);
        }
        for (int i = 0; i < vboBeforeSubtreeB.length; i++) {
            assertEquals(
                    vboBeforeSubtreeB[i] + 3, vboAfterMidSubtreeB[i], "dirty Subtree B VBO missing updates at " + i);
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
        assertEquals(3L * 36L + 2L * 4L, rootStats.get("appearanceSkipped"), () -> "stats=" + rootStats);
        assertEquals(0L, rootStats.get("fullArrayHashes"), () -> "stats=" + rootStats);
        final int[] vboAfterRootSubtreeB = readMeshPositionVboCounters(rew.subtreeBMeshes);
        assertArrayEquals(
                vboAfterMidSubtreeB, vboAfterRootSubtreeB, "untouched Subtree B VBO dirtied during root drag");

        // Frame 6: parameter change forces seamless fail-closed fallback + exact parity.
        orig.param.getClass().getMethod("setValue", float.class).invoke(orig.param, 18.0f);
        rew.param.getClass().getMethod("setValue", float.class).invoke(rew.param, 18.0f);
        orig.invokeUpdate();
        rew.invokeUpdate();
        assertAllMeshesRawFloatParity(orig, rew, "param-fallback");
        assertEquals(baselineFallbacks + 1L, bridge.snapshot().get("fallbackModelUpdates"));

        // Frame 7: Undo/Redo reinitInstance on a child deformer clears deformedForm and forces fallback,
        // then Frame 8 resumes fast drag cleanly.
        orig.midRotSrc
                .getClass()
                .getMethod(
                        "reinitInstance",
                        Class.forName("com.live2d.cubism.doc.model.ACParameterControllable", false, orig.loader))
                .invoke(orig.midRotSrc, orig.midRot);
        rew.midRotSrc
                .getClass()
                .getMethod(
                        "reinitInstance",
                        Class.forName("com.live2d.cubism.doc.model.ACParameterControllable", false, rew.loader))
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

    private static void runSelectorScopedAndEditingTransitionParity(
            final ClassLoader origLoader,
            final ClassLoader rewLoader,
            final WarpDeformEditTarget target,
            final WarpDeformEditBridge bridge)
            throws Exception {
        final SyntheticModelHarness orig = buildSyntheticHierarchy(origLoader, target);
        final SyntheticModelHarness rew = buildSyntheticHierarchy(rewLoader, target);
        orig.modelSource.getClass().getMethod("setModelEditing", boolean.class).invoke(orig.modelSource, false);
        rew.modelSource.getClass().getMethod("setModelEditing", boolean.class).invoke(rew.modelSource, false);

        final Object[] origCtx = createEditViewContext(orig.loader, orig.modelSource, orig.model);
        final Object[] rewCtx = createEditViewContext(rew.loader, rew.modelSource, rew.model);
        final Object origDoc = origCtx[0];
        final Object origView = origCtx[1];
        final Object origSelector = origCtx[2];
        final Object rewDoc = rewCtx[0];
        final Object rewView = rewCtx[1];
        final Object rewSelector = rewCtx[2];

        // Frame 0: capture clean idle baseline with isModelEditing == false.
        orig.invokeUpdate(origDoc, origView);
        rew.invokeUpdate(rewDoc, rewView);
        assertAllMeshesRawFloatParity(orig, rew, "selector-idle-baseline");

        final Map<String, Long> s0 = bridge.snapshot();
        final long fastBase = s0.get("fastModelUpdates");
        final long fallbackBase = s0.get("fallbackModelUpdates");
        final long appearSkipBase = s0.get("appearanceSkipped");
        final int[] vboBeforeA = readMeshPositionVboCounters(rew.subtreeAMeshes);
        final int[] vboBeforeB = readMeshPositionVboCounters(rew.subtreeBMeshes);

        // Transition isModelEditing false -> true on Tick 1 and drag selected midRot WITHOUT
        // setDirtyDeformedForm/setUpdated.
        orig.modelSource.getClass().getMethod("setModelEditing", boolean.class).invoke(orig.modelSource, true);
        rew.modelSource.getClass().getMethod("setModelEditing", boolean.class).invoke(rew.modelSource, true);
        final Class<?> acCtrlSrcClsOrig =
                Class.forName("com.live2d.cubism.doc.model.ACParameterControllableSource", false, orig.loader);
        final Class<?> acCtrlSrcClsRew =
                Class.forName("com.live2d.cubism.doc.model.ACParameterControllableSource", false, rew.loader);
        origSelector
                .getClass()
                .getMethod("addSelected", acCtrlSrcClsOrig, int.class)
                .invoke(origSelector, orig.midRotSrc, 0);
        rewSelector
                .getClass()
                .getMethod("addSelected", acCtrlSrcClsRew, int.class)
                .invoke(rewSelector, rew.midRotSrc, 0);

        final long hashesBeforeRot = bridge.snapshot().get("fullArrayHashes");
        for (int tick = 1; tick <= 2; tick++) {
            dragRotationDeformerUnversioned(orig.midRot, 14.0f * tick, 3.0f * tick);
            dragRotationDeformerUnversioned(rew.midRot, 14.0f * tick, 3.0f * tick);
            orig.invokeUpdate(origDoc, origView);
            rew.invokeUpdate(rewDoc, rewView);
            assertAllMeshesRawFloatParity(orig, rew, "selector-rot-tick-" + tick);
            if (tick == 1) {
                final Map<String, Long> sTick1 = bridge.snapshot();
                assertEquals(
                        fastBase + 1L, sTick1.get("fastModelUpdates"), "Tick 1 must not fall back on isEditing change");
                assertEquals(fallbackBase, sTick1.get("fallbackModelUpdates"));
                assertEquals(appearSkipBase + 36L, sTick1.get("appearanceSkipped"));
            }
        }
        final Map<String, Long> sAfterRot = bridge.snapshot();
        assertEquals(fastBase + 2L, sAfterRot.get("fastModelUpdates"));
        assertEquals(fallbackBase, sAfterRot.get("fallbackModelUpdates"));
        assertEquals(appearSkipBase + 2L * 36L, sAfterRot.get("appearanceSkipped"));
        assertEquals(
                hashesBeforeRot,
                sAfterRot.get("fullArrayHashes"),
                "rotation drag + 40 meshes must perform 0 float[] hashes");

        final int[] vboAfterRotA = readMeshPositionVboCounters(rew.subtreeAMeshes);
        final int[] vboAfterRotB = readMeshPositionVboCounters(rew.subtreeBMeshes);
        assertArrayEquals(vboBeforeA, vboAfterRotA);
        for (int i = 0; i < vboBeforeB.length; i++) {
            assertEquals(vboBeforeB[i] + 2, vboAfterRotB[i]);
        }

        // Switch selection to rootWarpSrc and drag WITHOUT setDirtyDeformedForm/setUpdated: hashes strictly 1 deformer!
        origSelector.getClass().getMethod("clearSelection").invoke(origSelector);
        rewSelector.getClass().getMethod("clearSelection").invoke(rewSelector);
        origSelector
                .getClass()
                .getMethod("addSelected", acCtrlSrcClsOrig, int.class)
                .invoke(origSelector, orig.rootWarpSrc, 0);
        rewSelector
                .getClass()
                .getMethod("addSelected", acCtrlSrcClsRew, int.class)
                .invoke(rewSelector, rew.rootWarpSrc, 0);

        final float[] baseGrid = rew.baseRootGridPositions.get(0);
        final float[] gridTick3 = baseGrid.clone();
        gridTick3[0] += 5.5f;
        dragWarpDeformerUnversioned(orig.rootWarp, gridTick3);
        dragWarpDeformerUnversioned(rew.rootWarp, gridTick3);
        orig.invokeUpdate(origDoc, origView);
        rew.invokeUpdate(rewDoc, rewView);
        assertAllMeshesRawFloatParity(orig, rew, "selector-warp-switch-tick-3");

        final long hashesBeforeTick4 = bridge.snapshot().get("fullArrayHashes");
        final float[] gridTick4 = baseGrid.clone();
        gridTick4[0] += 11.0f;
        dragWarpDeformerUnversioned(orig.rootWarp, gridTick4);
        dragWarpDeformerUnversioned(rew.rootWarp, gridTick4);
        orig.invokeUpdate(origDoc, origView);
        rew.invokeUpdate(rewDoc, rewView);
        assertAllMeshesRawFloatParity(orig, rew, "selector-warp-tick-4");

        final Map<String, Long> sAfterTick4 = bridge.snapshot();
        assertEquals(fallbackBase, sAfterTick4.get("fallbackModelUpdates"));
        final long tick4Hashes = sAfterTick4.get("fullArrayHashes") - hashesBeforeTick4;
        // Strictly 1 selected 2-keyform deformer (diff + snapshot capture = 4, vs 40 meshes + 19 warps).
        assertEquals(4L, tick4Hashes, () -> "unexpected float[] hashes on tick 4: " + sAfterTick4);

        // Verify K$b.a overlay elision with active EditModelTopologyCache performs 0 float[] hashes.
        final Object rewEditMode = rewCtx[3];
        final Object rewSceneGraph = rewCtx[4];
        final Object rewPackAction = rewCtx[5];
        final Class<?> kbCls = Class.forName("com.live2d.cubism.view.context.K$b", true, rew.loader);
        final Object kbInstance = kbCls.getField("a").get(null);
        final Class<?> rotModeCls =
                Class.forName("com.live2d.cubism.setting.AppSetting$RotationDeformer$Mode", true, rew.loader);
        final Object rotModeAuto = rotModeCls.getField("a").get(null);
        final Class<?> fn1Cls = Class.forName("kotlin.jvm.functions.Function1", true, rew.loader);
        final Object falsePred = java.lang.reflect.Proxy.newProxyInstance(
                rew.loader, new Class<?>[] {fn1Cls}, (proxy, method, args) -> Boolean.FALSE);
        final Class<?> acDefCls = Class.forName("com.live2d.cubism.doc.model.deformer.ACDeformer", false, rew.loader);
        final Method ovMethod = kbCls.getMethod(
                "a",
                acDefCls,
                rewSceneGraph.getClass(),
                boolean.class,
                boolean.class,
                rotModeCls,
                rewPackAction.getClass(),
                fn1Cls);
        assertNotNull(rewEditMode);
        @SuppressWarnings("unchecked")
        final List<Object> allRewDefs =
                (List<Object>) rew.model.getClass().getMethod("getAllDeformers").invoke(rew.model);
        final Class<?> warpDefCls =
                Class.forName(WarpDeformEditTarget.WARP_DEFORMER_OWNER.replace('/', '.'), false, rew.loader);
        Object unselectedWarp = null;
        for (final Object d : allRewDefs) {
            if (warpDefCls.isInstance(d) && d != rew.rootWarp) {
                unselectedWarp = d;
                break;
            }
        }
        assertNotNull(unselectedWarp);
        final long hashesBeforeOvUpdate = bridge.snapshot().get("fullArrayHashes");
        ovMethod.invoke(kbInstance, unselectedWarp, rewSceneGraph, false, true, rotModeAuto, rewPackAction, falsePred);
        final long hashesBeforeOvElide = bridge.snapshot().get("fullArrayHashes");
        assertEquals(
                hashesBeforeOvUpdate,
                hashesBeforeOvElide,
                "WarpDeformer overlay update under active epoch must skip float[] hashing");
        final long elisionsBefore = bridge.snapshot().get("overlayElisions");
        ovMethod.invoke(kbInstance, unselectedWarp, rewSceneGraph, false, true, rotModeAuto, rewPackAction, falsePred);
        final Map<String, Long> afterOv = bridge.snapshot();
        assertTrue(afterOv.get("overlayElisions") > elisionsBefore);
        assertEquals(
                hashesBeforeOvElide,
                afterOv.get("fullArrayHashes"),
                "WarpDeformer overlay elision under active epoch must skip float[] hashing");
    }

    private static Object[] createEditViewContext(
            final ClassLoader loader, final Object modelSource, final Object model) throws Exception {
        final Class<?> unsafeCls = Class.forName("sun.misc.Unsafe");
        final Field theUnsafe = unsafeCls.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        final Object unsafe = theUnsafe.get(null);
        final Method allocate = unsafeCls.getMethod("allocateInstance", Class.class);

        final Class<?> modelSrcCls = Class.forName("com.live2d.cubism.doc.model.CModelSource", true, loader);
        final Class<?> modelCls = Class.forName("com.live2d.cubism.doc.model.CModel", true, loader);
        final Class<?> packCls = Class.forName("com.live2d.cubism.pack.CECompletePack", false, loader);
        final Class<?> docCls = Class.forName("com.live2d.cubism.doc.modeling.CModelingDocument", true, loader);
        final Class<?> editModeCls =
                Class.forName("com.live2d.cubism.doc.modeling.CModelingEditMode_Main", true, loader);
        final Class<?> viewCls =
                Class.forName("com.live2d.cubism.view.context.CEViewContext_ModelingView", false, loader);
        final Class<?> absViewCls = Class.forName("com.live2d.cubism.view.context.CEViewContext", false, loader);
        final Class<?> camCls = Class.forName("com.live2d.cubism.view.context.CECameraManager", false, loader);
        final Class<?> rsCls = Class.forName("com.live2d.graphics3d.rendering.e", false, loader);
        final Class<?> sgCls = Class.forName("com.live2d.graphics3d.sceneGraph.GSceneGraph", true, loader);
        final Class<?> aGCls = Class.forName("com.live2d.cubism.view.context.actionManager.aG", true, loader);
        final Class<?> nCls = Class.forName("com.live2d.cubism.view.context.actionManager.N", false, loader);
        final Class<?> lazyCls = Class.forName("kotlin.Lazy", true, loader);

        final Object pack = allocate.invoke(unsafe, packCls);
        final Object doc = docCls.getConstructor(packCls, modelSrcCls).newInstance(pack, modelSource);
        final Object editMode = editModeCls.getConstructor(packCls, docCls).newInstance(pack, doc);
        final Object selector = editModeCls.getMethod("getSelector").invoke(editMode);
        final Object sceneGraph = sgCls.getConstructor().newInstance();
        final Object camMgr = allocate.invoke(unsafe, camCls);
        final Object renderSys = allocate.invoke(unsafe, rsCls);
        final Object view = allocate.invoke(unsafe, viewCls);
        setDeclaredField(absViewCls, view, "doc", doc);
        setDeclaredField(absViewCls, view, "currentEditMode", editMode);
        setDeclaredField(absViewCls, view, "cameraManager", camMgr);
        setDeclaredField(absViewCls, view, "renderSystem", renderSys);
        for (Class<?> cur = viewCls; cur != Object.class; cur = cur.getSuperclass()) {
            for (final Field f : cur.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) && f.getType() == modelCls) {
                    f.setAccessible(true);
                    f.set(view, model);
                }
            }
        }
        final Object packAction = allocate.invoke(unsafe, aGCls);
        setDeclaredField(nCls, packAction, "c", view);
        setDeclaredField(aGCls, packAction, "b", editMode);
        final Object emptyLazy = java.lang.reflect.Proxy.newProxyInstance(
                loader,
                new Class<?>[] {lazyCls},
                (proxy, method, args) -> "isInitialized".equals(method.getName()) ? Boolean.TRUE : List.of());
        for (Class<?> cur = aGCls; cur != Object.class; cur = cur.getSuperclass()) {
            for (final Field f : cur.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) && lazyCls.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    f.set(packAction, emptyLazy);
                }
            }
        }
        return new Object[] {doc, view, selector, editMode, sceneGraph, packAction};
    }

    private static void dragRotationDeformerUnversioned(
            final Object deformer, final float angleDeg, final float originShift) throws Exception {
        final Object src = deformer.getClass().getMethod("getSource").invoke(deformer);
        @SuppressWarnings("unchecked")
        final List<Object> kfs =
                (List<Object>) src.getClass().getMethod("getKeyforms").invoke(src);
        final Object kf0 = kfs.get(0);
        kf0.getClass().getMethod("setAngle", float.class).invoke(kf0, angleDeg);
        kf0.getClass().getMethod("setOriginX", float.class).invoke(kf0, 12.0f + originShift);
        final Object interp =
                deformer.getClass().getMethod("getInterpolatedForm").invoke(deformer);
        interp.getClass().getMethod("setAngle", float.class).invoke(interp, angleDeg);
        interp.getClass().getMethod("setOriginX", float.class).invoke(interp, 12.0f + originShift);
    }

    private static void dragWarpDeformerUnversioned(final Object deformer, final float[] newPositions)
            throws Exception {
        final Object src = deformer.getClass().getMethod("getSource").invoke(deformer);
        @SuppressWarnings("unchecked")
        final List<Object> kfs =
                (List<Object>) src.getClass().getMethod("getKeyforms").invoke(src);
        final Object kf0 = kfs.get(0);
        final float[] kfPos = (float[]) kf0.getClass().getMethod("getPositions").invoke(kf0);
        System.arraycopy(newPositions, 0, kfPos, 0, newPositions.length);
        final Object interp =
                deformer.getClass().getMethod("getInterpolatedForm").invoke(deformer);
        interp.getClass().getMethod("setPositions", float[].class).invoke(interp, newPositions.clone());
    }

    private static void dragRotationDeformer(final Object deformer, final float angleDeg, final float originShift)
            throws Exception {
        dragRotationDeformerUnversioned(deformer, angleDeg, originShift);
        deformer.getClass().getMethod("setDirtyDeformedForm", boolean.class).invoke(deformer, true);
    }

    private static void dragWarpDeformer(final Object deformer, final float[] newPositions) throws Exception {
        dragWarpDeformerUnversioned(deformer, newPositions);
        deformer.getClass().getMethod("setDirtyDeformedForm", boolean.class).invoke(deformer, true);
    }

    private static int[] readMeshPositionVboCounters(final List<Object> meshes) throws Exception {
        final int[] counters = new int[meshes.size()];
        for (int i = 0; i < meshes.size(); i++) {
            final Object m = meshes.get(i);
            final Object container = m.getClass().getMethod("getMeshContainer").invoke(m);
            final Object gmesh = container.getClass().getMethod("getMesh").invoke(container);
            final Object posBuf =
                    gmesh.getClass().getMethod("getPositionsBuffer").invoke(gmesh);
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
            final float[] oPos =
                    (float[]) oForm.getClass().getMethod("getPositions").invoke(oForm);
            final float[] rPos =
                    (float[]) rForm.getClass().getMethod("getPositions").invoke(rForm);
            assertRawFloatArraysEqual(oPos, rPos, phase + " form mesh#" + i);

            final Object oCont = om.getClass().getMethod("getMeshContainer").invoke(om);
            final Object rCont = rm.getClass().getMethod("getMeshContainer").invoke(rm);
            final Object oGMesh = oCont.getClass().getMethod("getMesh").invoke(oCont);
            final Object rGMesh = rCont.getClass().getMethod("getMesh").invoke(rCont);
            final float[] oGPos =
                    (float[]) oGMesh.getClass().getMethod("getPositions").invoke(oGMesh);
            final float[] rGPos =
                    (float[]) rGMesh.getClass().getMethod("getPositions").invoke(rGMesh);
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
                    () -> context + " bit mismatch at [" + idx + "] expected=" + expected[idx] + " actual="
                            + actual[idx]);
        }
    }

    private static void runOverlayElisionParityOnComposedLoader(
            final ClassLoader loader, final WarpDeformEditBridge bridge) throws Exception {
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
        final Class<?> acDefSrcCls =
                Class.forName("com.live2d.cubism.doc.model.deformer.ACDeformerSource", true, loader);
        final Class<?> acCtrlSrcCls =
                Class.forName("com.live2d.cubism.doc.model.ACParameterControllableSource", true, loader);
        final Class<?> renderTypeCls = Class.forName("com.live2d.graphics3d.rendering.RenderType", true, loader);

        final Object coordLocal = coordCls.getMethod("b").invoke(null);
        final Object modelSource = modelSrcCls.getConstructor().newInstance();
        final Object rootPart = modelSrcCls.getMethod("getRootPart").invoke(modelSource);
        final Object defSet = modelSrcCls.getMethod("getDeformerSourceSet").invoke(modelSource);
        final Method addDef = defSet.getClass().getMethod("add", acDefSrcCls, int.class);
        final Method addPart = rootPart.getClass().getMethod("addChild", acCtrlSrcCls, int.class);

        final Object rotAGuid =
                defGuidCls.getConstructor(UUID.class).newInstance(UUID.nameUUIDFromBytes("ovA".getBytes()));
        final Object rotASrc = rotSrcCls.getConstructor(modelSrcCls).newInstance(modelSource);
        rotSrcCls.getMethod("setGuid", defGuidCls).invoke(rotASrc, rotAGuid);
        rotSrcCls
                .getMethod("setId", defIdCls)
                .invoke(rotASrc, defIdCls.getConstructor(String.class).newInstance("OvRotA"));
        final Object rotForm =
                rotFormCls.getConstructor(rotSrcCls, rotDefCls, coordCls).newInstance(rotASrc, null, coordLocal);
        final Object rfGuid =
                formGuidCls.getConstructor(UUID.class).newInstance(UUID.nameUUIDFromBytes("ovRf".getBytes()));
        rotFormCls.getMethod("setGuid", formGuidCls).invoke(rotForm, rfGuid);
        @SuppressWarnings("unchecked")
        final List<Object> rKfs =
                (List<Object>) rotSrcCls.getMethod("getKeyforms").invoke(rotASrc);
        rKfs.add(rotForm);
        final Object kfGrid = rotSrcCls.getMethod("getKeyformGridSource").invoke(rotASrc);
        kfGrid.getClass().getMethod("setInitialKeyform", formGuidCls).invoke(kfGrid, rfGuid);
        addDef.invoke(defSet, rotASrc, -1);
        addPart.invoke(rootPart, rotASrc, -1);

        final Object rotBGuid =
                defGuidCls.getConstructor(UUID.class).newInstance(UUID.nameUUIDFromBytes("ovB".getBytes()));
        final Object rotBSrc = rotSrcCls.getConstructor(modelSrcCls).newInstance(modelSource);
        rotSrcCls.getMethod("setGuid", defGuidCls).invoke(rotBSrc, rotBGuid);
        rotSrcCls
                .getMethod("setId", defIdCls)
                .invoke(rotBSrc, defIdCls.getConstructor(String.class).newInstance("OvRotB"));
        final Object rotBForm =
                rotFormCls.getConstructor(rotSrcCls, rotDefCls, coordCls).newInstance(rotBSrc, null, coordLocal);
        final Object rfBGuid =
                formGuidCls.getConstructor(UUID.class).newInstance(UUID.nameUUIDFromBytes("ovRfB".getBytes()));
        rotFormCls.getMethod("setGuid", formGuidCls).invoke(rotBForm, rfBGuid);
        @SuppressWarnings("unchecked")
        final List<Object> rBKfs =
                (List<Object>) rotSrcCls.getMethod("getKeyforms").invoke(rotBSrc);
        rBKfs.add(rotBForm);
        final Object kfGridB = rotSrcCls.getMethod("getKeyformGridSource").invoke(rotBSrc);
        kfGridB.getClass().getMethod("setInitialKeyform", formGuidCls).invoke(kfGridB, rfBGuid);
        addDef.invoke(defSet, rotBSrc, -1);
        addPart.invoke(rootPart, rotBSrc, -1);

        rootPart.getClass().getMethod("setVisibleAndLockInHierarchy").invoke(rootPart);

        final Object renderNormal = Enum.valueOf((Class) renderTypeCls, "NORMAL");
        final Object model =
                modelSrcCls.getMethod("createModelInstance", renderTypeCls).invoke(modelSource, renderNormal);
        final Object rotDeformer = modelCls.getMethod("getDeformer", defGuidCls).invoke(model, rotAGuid);
        final Object rotBDeformer =
                modelCls.getMethod("getDeformer", defGuidCls).invoke(model, rotBGuid);

        final Object[] ctx = createEditViewContext(loader, modelSource, model);
        final Object sceneGraph = ctx[4];
        final Object packAction = ctx[5];

        final Class<?> kbCls = Class.forName("com.live2d.cubism.view.context.K$b", true, loader);
        final Object kbInstance = kbCls.getField("a").get(null);
        final Class<?> rotModeCls =
                Class.forName("com.live2d.cubism.setting.AppSetting$RotationDeformer$Mode", true, loader);
        final Object rotModeAuto = rotModeCls.getField("a").get(null);
        final Class<?> fn1Cls = Class.forName("kotlin.jvm.functions.Function1", true, loader);
        final Object falsePred = java.lang.reflect.Proxy.newProxyInstance(
                loader, new Class<?>[] {fn1Cls}, (proxy, method, args) -> Boolean.FALSE);
        final Class<?> acDefCls = Class.forName("com.live2d.cubism.doc.model.deformer.ACDeformer", false, loader);
        final Method ovMethod = kbCls.getMethod(
                "a",
                acDefCls,
                sceneGraph.getClass(),
                boolean.class,
                boolean.class,
                rotModeCls,
                packAction.getClass(),
                fn1Cls);

        // Pass 1: native overlay builds Draw children for both deformers in same frame (memoizes FrameGlobalState).
        final long updatesBeforePass1 = bridge.snapshot().get("overlayUpdates");
        ovMethod.invoke(kbInstance, rotDeformer, sceneGraph, false, true, rotModeAuto, packAction, falsePred);
        ovMethod.invoke(kbInstance, rotBDeformer, sceneGraph, false, true, rotModeAuto, packAction, falsePred);
        final long updatesAfterPass1 = bridge.snapshot().get("overlayUpdates");
        assertTrue(
                updatesAfterPass1 >= updatesBeforePass1 + 2L,
                () -> "expected initial K$b.a overlay updates for two deformers, stats=" + bridge.snapshot());

        // Clear SceneGraph volatile set and invoke Pass 2 unchanged -> must elide updates & keep setVolatile!
        final Field willRenderF = findFieldOwner(sceneGraph.getClass(), "volatileEntities_willRender")
                .getDeclaredField("volatileEntities_willRender");
        willRenderF.setAccessible(true);
        @SuppressWarnings("unchecked")
        final Set<Object> willRender = (Set<Object>) willRenderF.get(sceneGraph);
        willRender.clear();

        final long elisionsBeforePass2 = bridge.snapshot().get("overlayElisions");
        ovMethod.invoke(kbInstance, rotDeformer, sceneGraph, false, true, rotModeAuto, packAction, falsePred);
        ovMethod.invoke(kbInstance, rotBDeformer, sceneGraph, false, true, rotModeAuto, packAction, falsePred);
        final Map<String, Long> afterElide = bridge.snapshot();
        assertTrue(
                afterElide.get("overlayElisions") >= elisionsBeforePass2 + 2L,
                () -> "expected overlayElisions across memoized frame, got " + afterElide);
        assertEquals(updatesAfterPass1, afterElide.get("overlayUpdates"));
        assertEquals(2, willRender.size(), "elided overlay must preserve GSceneGraph.setVolatile registration");

        // Mutate calculatedForm angle and invoke Pass 3 -> must detect geometry drift and re-update!
        final Object calcForm = rotDefCls.getMethod("getCalculatedForm").invoke(rotDeformer);
        calcForm.getClass().getMethod("setAngle", float.class).invoke(calcForm, 25.0f);
        ovMethod.invoke(kbInstance, rotDeformer, sceneGraph, false, true, rotModeAuto, packAction, falsePred);
        assertTrue(
                bridge.snapshot().get("overlayUpdates") > updatesAfterPass1,
                () -> "dirty geometry must invalidate overlay elision, stats=" + bridge.snapshot());

        // Verify preference epoch cache reacts cleanly when configured default toggles and restores.
        final String prevProp = System.getProperty(WarpDeformEditBridge.ENABLE_PROPERTY);
        System.clearProperty(WarpDeformEditBridge.ENABLE_PROPERTY);
        try {
            WarpDeformEditBridge.setConfiguredDefault(false);
            assertFalse(WarpDeformEditBridge.enabledByPreference());
            ovMethod.invoke(kbInstance, rotBDeformer, sceneGraph, false, true, rotModeAuto, packAction, falsePred);
            assertEquals(0L, bridge.snapshot().get("failures"));
        } finally {
            WarpDeformEditBridge.setConfiguredDefault(true);
            if (prevProp != null) {
                System.setProperty(WarpDeformEditBridge.ENABLE_PROPERTY, prevProp);
            }
        }
    }

    private static void runDeepDagAndMorphTargetParity(
            final ClassLoader origLoader,
            final ClassLoader rewLoader,
            final WarpDeformEditTarget target,
            final WarpDeformEditBridge bridge)
            throws Exception {
        final Object[] origDag = buildDeepDag24WithMorphTarget(origLoader, target);
        final Object[] rewDag = buildDeepDag24WithMorphTarget(rewLoader, target);

        final SyntheticModelHarness origHarness = (SyntheticModelHarness) origDag[0];
        final SyntheticModelHarness rewHarness = (SyntheticModelHarness) rewDag[0];
        final Object origMorphParam = origDag[1];
        final Object rewMorphParam = rewDag[1];
        final Object origMorphSrc = origDag[2];
        final Object rewMorphSrc = rewDag[2];
        final Object origMorphDef = origDag[3];
        final Object rewMorphDef = rewDag[3];

        origHarness.invokeUpdate();
        rewHarness.invokeUpdate();
        assertAllMeshesRawFloatParity(origHarness, rewHarness, "deep-dag-baseline");

        final long beforeFast = bridge.snapshot().get("fastModelUpdates");
        final long beforeFallback = bridge.snapshot().get("fallbackModelUpdates");
        final long beforeLevels = bridge.snapshot().get("parallelDeformerLevels");
        final long beforeRotScratch = bridge.snapshot().get("rotScratchReused");

        final float[] baseGrid = rewHarness.baseRootGridPositions.get(0);
        for (int tick = 1; tick <= 3; tick++) {
            final float[] dragged = baseGrid.clone();
            for (int p = 0; p < dragged.length; p += 2) {
                dragged[p] += 3.25f * tick;
                dragged[p + 1] -= 1.75f * tick;
            }
            dragWarpDeformer(origHarness.rootWarp, dragged);
            dragWarpDeformer(rewHarness.rootWarp, dragged);
            origHarness.invokeUpdate();
            rewHarness.invokeUpdate();
            assertAllMeshesRawFloatParity(origHarness, rewHarness, "deep-dag-drag-" + tick);
        }

        final Map<String, Long> dragStats = bridge.snapshot();
        assertEquals(beforeFast + 3L, dragStats.get("fastModelUpdates"), () -> "stats=" + dragStats);
        assertEquals(beforeFallback, dragStats.get("fallbackModelUpdates"), () -> "stats=" + dragStats);
        // Single-dispatch DAG scheduler emits strictly 1 barrier per frame instead of 24 depth barriers.
        assertEquals(beforeLevels + 3L, dragStats.get("parallelDeformerLevels"), () -> "stats=" + dragStats);
        assertTrue(dragStats.get("rotScratchReused") > beforeRotScratch, () -> "stats=" + dragStats);

        // Drive MorphTarget + BlendWeightConstraint across heterogeneous warp grid dimensions (18 and 32 floats) ->
        // verify exact raw float parity and in-place preClampMorphedForm identity reuse.
        origMorphParam.getClass().getMethod("setValue", float.class).invoke(origMorphParam, 18.0f);
        rewMorphParam.getClass().getMethod("setValue", float.class).invoke(rewMorphParam, 18.0f);
        origHarness.param.getClass().getMethod("setValue", float.class).invoke(origHarness.param, 12.0f);
        rewHarness.param.getClass().getMethod("setValue", float.class).invoke(rewHarness.param, 12.0f);
        origHarness.invokeUpdate();
        rewHarness.invokeUpdate();
        assertAllMeshesRawFloatParity(origHarness, rewHarness, "deep-dag-morph-target-param");

        final Method getPreClampM = rewMorphDef.getClass().getMethod("getPreClampMorphedForm");
        final Object preClampAfterFirst = getPreClampM.invoke(rewMorphDef);
        assertNotNull(preClampAfterFirst, "rewritten morph target step must populate preClampMorphedForm");

        origMorphParam.getClass().getMethod("setValue", float.class).invoke(origMorphParam, 23.5f);
        rewMorphParam.getClass().getMethod("setValue", float.class).invoke(rewMorphParam, 23.5f);
        origHarness.param.getClass().getMethod("setValue", float.class).invoke(origHarness.param, 16.5f);
        rewHarness.param.getClass().getMethod("setValue", float.class).invoke(rewHarness.param, 16.5f);
        origHarness.invokeUpdate();
        rewHarness.invokeUpdate();
        assertAllMeshesRawFloatParity(origHarness, rewHarness, "deep-dag-morph-target-reuse");
        assertSame(
                preClampAfterFirst,
                getPreClampM.invoke(rewMorphDef),
                "second morph target update must reuse preClampMorphedForm instance in place");

        // Trigger CWarpDeformerSource.reinitInstance (undo simulation) and verify buffer reuse + exact parity.
        final long beforeReinitReused = bridge.snapshot().get("warpReinitPosReused");
        origMorphSrc
                .getClass()
                .getMethod(
                        "reinitInstance",
                        Class.forName("com.live2d.cubism.doc.model.ACParameterControllable", false, origLoader))
                .invoke(origMorphSrc, origMorphDef);
        rewMorphSrc
                .getClass()
                .getMethod(
                        "reinitInstance",
                        Class.forName("com.live2d.cubism.doc.model.ACParameterControllable", false, rewLoader))
                .invoke(rewMorphSrc, rewMorphDef);
        assertTrue(bridge.snapshot().get("warpReinitPosReused") > beforeReinitReused);
        origHarness.invokeUpdate();
        rewHarness.invokeUpdate();
        assertAllMeshesRawFloatParity(origHarness, rewHarness, "deep-dag-warp-reinit-undo");

        // Fast drag resumes cleanly on depth-24 DAG after reinitInstance.
        final long fallbackBeforeResume = bridge.snapshot().get("fallbackModelUpdates");
        final float[] resumedGrid = baseGrid.clone();
        resumedGrid[0] += 11.5f;
        dragWarpDeformer(origHarness.rootWarp, resumedGrid);
        dragWarpDeformer(rewHarness.rootWarp, resumedGrid);
        origHarness.invokeUpdate();
        rewHarness.invokeUpdate();
        assertAllMeshesRawFloatParity(origHarness, rewHarness, "deep-dag-post-reinit-resume");
        assertEquals(beforeFast + 4L, bridge.snapshot().get("fastModelUpdates"));
        assertEquals(fallbackBeforeResume, bridge.snapshot().get("fallbackModelUpdates"));
    }

    private static Object[] buildDeepDag24WithMorphTarget(final ClassLoader loader, final WarpDeformEditTarget target)
            throws Exception {
        final Class<?> modelSrcCls = Class.forName("com.live2d.cubism.doc.model.CModelSource", true, loader);
        final Class<?> modelCls = Class.forName("com.live2d.cubism.doc.model.CModel", true, loader);
        final Class<?> paramSrcCls = Class.forName("com.live2d.cubism.doc.model.param.CParameterSource", true, loader);
        final Class<?> paramGuidCls = Class.forName("com.live2d.type.CParameterGuid", true, loader);
        final Class<?> paramIdCls = Class.forName("com.live2d.cubism.doc.model.id.CParameterId", true, loader);
        final Class<?> defGuidCls = Class.forName("com.live2d.type.CDeformerGuid", true, loader);
        final Class<?> defIdCls = Class.forName("com.live2d.cubism.doc.model.id.CDeformerId", true, loader);
        final Class<?> drawGuidCls = Class.forName("com.live2d.type.CDrawableGuid", true, loader);
        final Class<?> drawIdCls = Class.forName("com.live2d.cubism.doc.model.id.CDrawableId", true, loader);
        final Class<?> formGuidCls = Class.forName("com.live2d.type.CFormGuid", true, loader);
        final Class<?> coordCls = Class.forName("com.live2d.doc.CoordType", true, loader);
        final Class<?> warpSrcCls =
                Class.forName(WarpDeformEditTarget.WARP_SOURCE_OWNER.replace('/', '.'), true, loader);
        final Class<?> warpFormCls =
                Class.forName(WarpDeformEditTarget.WARP_FORM_OWNER.replace('/', '.'), true, loader);
        final Class<?> rotSrcCls =
                Class.forName("com.live2d.cubism.doc.model.deformer.rotation.CRotationDeformerSource", true, loader);
        final Class<?> rotDefCls =
                Class.forName("com.live2d.cubism.doc.model.deformer.rotation.CRotationDeformer", true, loader);
        final Class<?> rotFormCls =
                Class.forName("com.live2d.cubism.doc.model.deformer.rotation.CRotationDeformerForm", true, loader);
        final Class<?> meshSrcCls =
                Class.forName("com.live2d.cubism.doc.model.drawable.artMesh.CArtMeshSource", true, loader);
        final Class<?> meshFormCls =
                Class.forName("com.live2d.cubism.doc.model.drawable.artMesh.CArtMeshForm", true, loader);
        final Class<?> morphCls =
                Class.forName("com.live2d.cubism.doc.model.morphTarget.KeyFormMorphTarget", true, loader);
        final Class<?> acFormCls = Class.forName("com.live2d.cubism.doc.model.ACForm", true, loader);
        final Class<?> renderTypeCls = Class.forName("com.live2d.graphics3d.rendering.RenderType", true, loader);
        final Class<?> acDefSrcCls =
                Class.forName("com.live2d.cubism.doc.model.deformer.ACDeformerSource", true, loader);
        final Class<?> acDrawSrcCls =
                Class.forName("com.live2d.cubism.doc.model.drawable.ACDrawableSource", true, loader);
        final Class<?> acCtrlSrcCls =
                Class.forName("com.live2d.cubism.doc.model.ACParameterControllableSource", true, loader);

        final Object coordLocal = coordCls.getMethod("b").invoke(null);
        final Object coordCanvas = coordCls.getMethod("c").invoke(null);
        final Object modelSource = modelSrcCls.getConstructor().newInstance();
        final Object rootPart = modelSrcCls.getMethod("getRootPart").invoke(modelSource);
        final Object defSet = modelSrcCls.getMethod("getDeformerSourceSet").invoke(modelSource);
        final Object drawSet = modelSrcCls.getMethod("getDrawableSourceSet").invoke(modelSource);
        final Object paramSet = modelSrcCls.getMethod("getParameterSourceSet").invoke(modelSource);

        final Object paramXGuid =
                paramGuidCls.getConstructor(UUID.class).newInstance(UUID.nameUUIDFromBytes("dagPX".getBytes()));
        final Object paramXSrc = paramSrcCls.getConstructor().newInstance();
        paramSrcCls.getMethod("setGuid", paramGuidCls).invoke(paramXSrc, paramXGuid);
        paramSrcCls
                .getMethod("setId", paramIdCls)
                .invoke(paramXSrc, paramIdCls.getConstructor(String.class).newInstance("DagPX"));
        paramSrcCls.getMethod("setMinValue", float.class).invoke(paramXSrc, -30.0f);
        paramSrcCls.getMethod("setMaxValue", float.class).invoke(paramXSrc, 30.0f);
        paramSrcCls.getMethod("setDefaultValue", float.class).invoke(paramXSrc, 0.0f);
        paramSet.getClass().getMethod("add", paramSrcCls, int.class).invoke(paramSet, paramXSrc, -1);

        final Object paramCGuid =
                paramGuidCls.getConstructor(UUID.class).newInstance(UUID.nameUUIDFromBytes("dagPC".getBytes()));
        final Object paramCSrc = paramSrcCls.getConstructor().newInstance();
        paramSrcCls.getMethod("setGuid", paramGuidCls).invoke(paramCSrc, paramCGuid);
        paramSrcCls
                .getMethod("setId", paramIdCls)
                .invoke(paramCSrc, paramIdCls.getConstructor(String.class).newInstance("DagPC"));
        paramSrcCls.getMethod("setMinValue", float.class).invoke(paramCSrc, 0.0f);
        paramSrcCls.getMethod("setMaxValue", float.class).invoke(paramCSrc, 30.0f);
        paramSrcCls.getMethod("setDefaultValue", float.class).invoke(paramCSrc, 10.0f);
        paramSet.getClass().getMethod("add", paramSrcCls, int.class).invoke(paramSet, paramCSrc, -1);

        final Method addDef = defSet.getClass().getMethod("add", acDefSrcCls, int.class);
        final Method addDraw = drawSet.getClass().getMethod("add", acDrawSrcCls, int.class);
        final Method addPartChild = rootPart.getClass().getMethod("addChild", acCtrlSrcCls, int.class);

        final float[] baseGrid = {
            -120f, -120f, 0f, -120f, 120f, -120f,
            -120f, 0f, 0f, 0f, 120f, 0f,
            -120f, 120f, 0f, 120f, 120f, 120f
        };

        final Object rootGuid =
                defGuidCls.getConstructor(UUID.class).newInstance(UUID.nameUUIDFromBytes("dagRoot".getBytes()));
        final Object rootSrc = createWarpDeformerSource(
                modelSrcCls,
                modelSource,
                warpSrcCls,
                warpFormCls,
                defGuidCls,
                defIdCls,
                formGuidCls,
                coordCls,
                rootGuid,
                "DagRoot",
                null,
                coordLocal,
                baseGrid);
        addDef.invoke(defSet, rootSrc, -1);
        addPartChild.invoke(rootPart, rootSrc, -1);

        // Configure ParamX + KeyFormMorphTarget + Constraint on a child warp deformer at depth 1.
        final Object morphGuid =
                defGuidCls.getConstructor(UUID.class).newInstance(UUID.nameUUIDFromBytes("dagMorph".getBytes()));
        final Object morphSrc = createWarpDeformerSource(
                modelSrcCls,
                modelSource,
                warpSrcCls,
                warpFormCls,
                defGuidCls,
                defIdCls,
                formGuidCls,
                coordCls,
                morphGuid,
                "DagMorph",
                rootGuid,
                coordLocal,
                baseGrid);
        final Object morphFormA = warpFormCls
                .getConstructor(
                        warpSrcCls,
                        Class.forName(WarpDeformEditTarget.WARP_DEFORMER_OWNER.replace('/', '.'), false, loader),
                        coordCls)
                .newInstance(morphSrc, null, coordLocal);
        warpFormCls
                .getMethod("setGuid", formGuidCls)
                .invoke(
                        morphFormA,
                        formGuidCls.getConstructor(UUID.class).newInstance(UUID.nameUUIDFromBytes("mfA".getBytes())));
        final float[] shifted = baseGrid.clone();
        shifted[0] += 18.0f;
        shifted[5] -= 9.0f;
        warpFormCls.getMethod("setPositions", float[].class).invoke(morphFormA, shifted);
        @SuppressWarnings("unchecked")
        final List<Object> mKfs =
                (List<Object>) warpSrcCls.getMethod("getKeyforms").invoke(morphSrc);
        mKfs.add(morphFormA);
        final Object morphFormB = warpFormCls
                .getConstructor(
                        warpSrcCls,
                        Class.forName(WarpDeformEditTarget.WARP_DEFORMER_OWNER.replace('/', '.'), false, loader),
                        coordCls)
                .newInstance(morphSrc, null, coordLocal);
        warpFormCls
                .getMethod("setGuid", formGuidCls)
                .invoke(
                        morphFormB,
                        formGuidCls.getConstructor(UUID.class).newInstance(UUID.nameUUIDFromBytes("mfB".getBytes())));
        final float[] shiftedB = baseGrid.clone();
        shiftedB[0] -= 6.0f;
        shiftedB[5] += 4.5f;
        warpFormCls.getMethod("setPositions", float[].class).invoke(morphFormB, shiftedB);
        mKfs.add(morphFormB);
        final Object morphObj = morphCls.getConstructor(acFormCls, paramGuidCls, float.class)
                .newInstance(morphFormA, paramCGuid, 20.0f);
        final Object morphObjLow =
                morphCls.getConstructor(acFormCls, paramGuidCls, float.class).newInstance(morphFormB, paramCGuid, 5.0f);
        final Object morphSet = warpSrcCls.getMethod("getKeyformMorphTargetSet").invoke(morphSrc);
        morphSet.getClass().getMethod("add", morphCls).invoke(morphSet, morphObjLow);
        morphSet.getClass().getMethod("add", morphCls).invoke(morphSet, morphObj);
        final Object constraintSet =
                morphSet.getClass().getMethod("getBlendWeightConstraintSet").invoke(morphSet);
        constraintSet
                .getClass()
                .getMethod("tryAdd", paramGuidCls, paramGuidCls, float.class, float.class)
                .invoke(constraintSet, paramCGuid, paramXGuid, 0.0f, 0.5f);
        constraintSet
                .getClass()
                .getMethod("tryAdd", paramGuidCls, paramGuidCls, float.class, float.class)
                .invoke(constraintSet, paramCGuid, paramXGuid, 30.0f, 1.0f);
        final Object morphKfGrid = warpSrcCls.getMethod("getKeyformGridSource").invoke(morphSrc);
        morphKfGrid.getClass().getMethod("addKey", float.class, paramGuidCls).invoke(morphKfGrid, 0.0f, paramXGuid);
        morphKfGrid.getClass().getMethod("addKey", float.class, paramGuidCls).invoke(morphKfGrid, 30.0f, paramXGuid);
        addDef.invoke(defSet, morphSrc, -1);
        addPartChild.invoke(rootPart, morphSrc, -1);

        // Configure a 3x3 (32-float) sibling morph warp deformer so MorphScratch handles heterogeneous buffer sizes.
        final float[] largeGrid = new float[32];
        for (int row = 0; row <= 3; row++) {
            for (int col = 0; col <= 3; col++) {
                final int idx = (row * 4 + col) * 2;
                largeGrid[idx] = -100.0f + col * 66.0f;
                largeGrid[idx + 1] = -100.0f + row * 66.0f;
            }
        }
        final Object morphLargeGuid =
                defGuidCls.getConstructor(UUID.class).newInstance(UUID.nameUUIDFromBytes("dagMorphLarge".getBytes()));
        final Object morphLargeSrc = createWarpDeformerSource(
                modelSrcCls,
                modelSource,
                warpSrcCls,
                warpFormCls,
                defGuidCls,
                defIdCls,
                formGuidCls,
                coordCls,
                morphLargeGuid,
                "DagMorphLarge",
                rootGuid,
                coordLocal,
                largeGrid);
        warpSrcCls.getMethod("setCol", int.class).invoke(morphLargeSrc, 3);
        warpSrcCls.getMethod("setRow", int.class).invoke(morphLargeSrc, 3);
        final Object morphLargeForm = warpFormCls
                .getConstructor(
                        warpSrcCls,
                        Class.forName(WarpDeformEditTarget.WARP_DEFORMER_OWNER.replace('/', '.'), false, loader),
                        coordCls)
                .newInstance(morphLargeSrc, null, coordLocal);
        warpFormCls
                .getMethod("setGuid", formGuidCls)
                .invoke(
                        morphLargeForm,
                        formGuidCls
                                .getConstructor(UUID.class)
                                .newInstance(UUID.nameUUIDFromBytes("mfLarge".getBytes())));
        final float[] shiftedLarge = largeGrid.clone();
        shiftedLarge[0] += 11.0f;
        shiftedLarge[shiftedLarge.length - 1] -= 7.5f;
        warpFormCls.getMethod("setPositions", float[].class).invoke(morphLargeForm, shiftedLarge);
        @SuppressWarnings("unchecked")
        final List<Object> mLargeKfs =
                (List<Object>) warpSrcCls.getMethod("getKeyforms").invoke(morphLargeSrc);
        mLargeKfs.add(morphLargeForm);
        final Object morphLargeTarget = morphCls.getConstructor(acFormCls, paramGuidCls, float.class)
                .newInstance(morphLargeForm, paramCGuid, 25.0f);
        final Object morphLargeSet =
                warpSrcCls.getMethod("getKeyformMorphTargetSet").invoke(morphLargeSrc);
        morphLargeSet.getClass().getMethod("add", morphCls).invoke(morphLargeSet, morphLargeTarget);
        final Object morphLargeKfGrid =
                warpSrcCls.getMethod("getKeyformGridSource").invoke(morphLargeSrc);
        morphLargeKfGrid
                .getClass()
                .getMethod("addKey", float.class, paramGuidCls)
                .invoke(morphLargeKfGrid, 0.0f, paramXGuid);
        morphLargeKfGrid
                .getClass()
                .getMethod("addKey", float.class, paramGuidCls)
                .invoke(morphLargeKfGrid, 30.0f, paramXGuid);
        addDef.invoke(defSet, morphLargeSrc, -1);
        addPartChild.invoke(rootPart, morphLargeSrc, -1);

        final Object largeMeshGuid =
                drawGuidCls.getConstructor(UUID.class).newInstance(UUID.nameUUIDFromBytes("DagMesh_Large".getBytes()));
        final Object largeMeshSrc = createArtMeshSource(
                modelSrcCls,
                modelSource,
                meshSrcCls,
                meshFormCls,
                defGuidCls,
                drawGuidCls,
                drawIdCls,
                formGuidCls,
                coordCls,
                largeMeshGuid,
                "DagMesh_Large",
                morphLargeGuid,
                coordCanvas,
                new float[] {-20f, -20f, 20f, -20f, 20f, 20f, -20f, 20f});
        addDraw.invoke(drawSet, largeMeshSrc, -1);
        addPartChild.invoke(rootPart, largeMeshSrc, -1);

        // Build a 24-deep alternating Warp/Rotation chain + side branch of 6 deformers (total 30 deformers, 36
        // ArtMeshes).
        Object parentGuid = morphGuid;
        Object branch2Guid = null;
        for (int depth = 2; depth <= 23; depth++) {
            final Object dGuid = defGuidCls
                    .getConstructor(UUID.class)
                    .newInstance(UUID.nameUUIDFromBytes(("chain-" + depth).getBytes()));
            if ((depth & 1) == 0) {
                final Object ws = createWarpDeformerSource(
                        modelSrcCls,
                        modelSource,
                        warpSrcCls,
                        warpFormCls,
                        defGuidCls,
                        defIdCls,
                        formGuidCls,
                        coordCls,
                        dGuid,
                        "ChainW_" + depth,
                        parentGuid,
                        coordLocal,
                        baseGrid);
                addDef.invoke(defSet, ws, -1);
                addPartChild.invoke(rootPart, ws, -1);
            } else {
                final Object rs = rotSrcCls.getConstructor(modelSrcCls).newInstance(modelSource);
                rotSrcCls.getMethod("setGuid", defGuidCls).invoke(rs, dGuid);
                rotSrcCls
                        .getMethod("setId", defIdCls)
                        .invoke(rs, defIdCls.getConstructor(String.class).newInstance("ChainR_" + depth));
                rotSrcCls.getMethod("setTargetDeformerGuid", defGuidCls).invoke(rs, parentGuid);
                final Object rf = rotFormCls
                        .getConstructor(rotSrcCls, rotDefCls, coordCls)
                        .newInstance(rs, null, coordLocal);
                final Object rfGuid = formGuidCls
                        .getConstructor(UUID.class)
                        .newInstance(UUID.nameUUIDFromBytes(("cr-" + depth).getBytes()));
                rotFormCls.getMethod("setGuid", formGuidCls).invoke(rf, rfGuid);
                rotFormCls.getMethod("setOriginX", float.class).invoke(rf, 4.0f);
                rotFormCls.getMethod("setAngle", float.class).invoke(rf, 1.5f);
                @SuppressWarnings("unchecked")
                final List<Object> rKfs =
                        (List<Object>) rotSrcCls.getMethod("getKeyforms").invoke(rs);
                rKfs.add(rf);
                if (depth == 3) {
                    final Object rotMorphLow = rotFormCls
                            .getConstructor(rotSrcCls, rotDefCls, coordCls)
                            .newInstance(rs, null, coordLocal);
                    rotFormCls
                            .getMethod("setGuid", formGuidCls)
                            .invoke(
                                    rotMorphLow,
                                    formGuidCls
                                            .getConstructor(UUID.class)
                                            .newInstance(UUID.nameUUIDFromBytes("cr-morph-low".getBytes())));
                    rotFormCls.getMethod("setOriginX", float.class).invoke(rotMorphLow, 6.0f);
                    rotFormCls.getMethod("setAngle", float.class).invoke(rotMorphLow, 4.0f);
                    final Object rotMorphHigh = rotFormCls
                            .getConstructor(rotSrcCls, rotDefCls, coordCls)
                            .newInstance(rs, null, coordLocal);
                    rotFormCls
                            .getMethod("setGuid", formGuidCls)
                            .invoke(
                                    rotMorphHigh,
                                    formGuidCls
                                            .getConstructor(UUID.class)
                                            .newInstance(UUID.nameUUIDFromBytes("cr-morph-high".getBytes())));
                    rotFormCls.getMethod("setOriginX", float.class).invoke(rotMorphHigh, 1.5f);
                    rotFormCls.getMethod("setAngle", float.class).invoke(rotMorphHigh, -7.0f);
                    rKfs.add(rotMorphLow);
                    rKfs.add(rotMorphHigh);
                    final Object rMorphSet =
                            rotSrcCls.getMethod("getKeyformMorphTargetSet").invoke(rs);
                    rMorphSet
                            .getClass()
                            .getMethod("add", morphCls)
                            .invoke(
                                    rMorphSet,
                                    morphCls.getConstructor(acFormCls, paramGuidCls, float.class)
                                            .newInstance(rotMorphLow, paramCGuid, 5.0f));
                    rMorphSet
                            .getClass()
                            .getMethod("add", morphCls)
                            .invoke(
                                    rMorphSet,
                                    morphCls.getConstructor(acFormCls, paramGuidCls, float.class)
                                            .newInstance(rotMorphHigh, paramCGuid, 20.0f));
                }
                final Object rKfGrid =
                        rotSrcCls.getMethod("getKeyformGridSource").invoke(rs);
                rKfGrid.getClass().getMethod("setInitialKeyform", formGuidCls).invoke(rKfGrid, rfGuid);
                addDef.invoke(defSet, rs, -1);
                addPartChild.invoke(rootPart, rs, -1);
            }
            if (depth == 2) {
                branch2Guid = dGuid;
            }
            parentGuid = dGuid;
        }
        Object sideParent = branch2Guid;
        for (int s = 0; s < 6; s++) {
            final Object sGuid =
                    defGuidCls.getConstructor(UUID.class).newInstance(UUID.nameUUIDFromBytes(("side-" + s).getBytes()));
            final Object ws = createWarpDeformerSource(
                    modelSrcCls,
                    modelSource,
                    warpSrcCls,
                    warpFormCls,
                    defGuidCls,
                    defIdCls,
                    formGuidCls,
                    coordCls,
                    sGuid,
                    "SideW_" + s,
                    sideParent,
                    coordLocal,
                    baseGrid);
            addDef.invoke(defSet, ws, -1);
            addPartChild.invoke(rootPart, ws, -1);
            sideParent = sGuid;
        }

        final List<Object> meshSources = new ArrayList<>();
        for (int m = 0; m < 36; m++) {
            final Object attachGuid = (m & 1) == 0 ? parentGuid : sideParent;
            final String mName = "DagMesh_" + m;
            final Object mGuid =
                    drawGuidCls.getConstructor(UUID.class).newInstance(UUID.nameUUIDFromBytes(mName.getBytes()));
            final Object ms = createArtMeshSource(
                    modelSrcCls,
                    modelSource,
                    meshSrcCls,
                    meshFormCls,
                    defGuidCls,
                    drawGuidCls,
                    drawIdCls,
                    formGuidCls,
                    coordCls,
                    mGuid,
                    mName,
                    attachGuid,
                    coordCanvas,
                    new float[] {-10f + m, -10f, 10f + m, -10f, 10f + m, 10f, -10f + m, 10f});
            addDraw.invoke(drawSet, ms, -1);
            addPartChild.invoke(rootPart, ms, -1);
            meshSources.add(ms);
        }

        rootPart.getClass().getMethod("setVisibleAndLockInHierarchy").invoke(rootPart);
        modelSrcCls.getMethod("setModelEditing", boolean.class).invoke(modelSource, true);
        final Object renderNormal = Enum.valueOf((Class) renderTypeCls, "NORMAL");
        final Object model =
                modelSrcCls.getMethod("createModelInstance", renderTypeCls).invoke(modelSource, renderNormal);
        final Object rootWarp = modelCls.getMethod("getDeformer", defGuidCls).invoke(model, rootGuid);
        final Object morphDef = modelCls.getMethod("getDeformer", defGuidCls).invoke(model, morphGuid);
        @SuppressWarnings("unchecked")
        final List<Object> allMeshes =
                (List<Object>) modelCls.getMethod("getAllArtMeshes").invoke(model);

        final Object paramSetInst = modelCls.getMethod("getParameterSet").invoke(model);
        @SuppressWarnings("unchecked")
        final List<Object> params = (List<Object>)
                paramSetInst.getClass().getMethod("getParameters").invoke(paramSetInst);
        final Object paramX = params.get(0);
        final Object paramC = params.get(1);

        final Class<?> updaterCls = Class.forName(target.updaterOwner().replace('/', '.'), true, loader);
        final Object updater = updaterCls.getField("a").get(null);
        final Class<?> updCtxCls = resolveUpdaterContextClass(loader, target);
        final Class<?> docCls = Class.forName("com.live2d.cubism.doc.modeling.CModelingDocument", false, loader);
        final Class<?> viewCls = Class.forName("com.live2d.cubism.view.context.CEViewContext", false, loader);
        final Class<?> paramCtxCls = resolveContextParamClass(updaterCls, modelCls, updCtxCls, docCls, viewCls);
        final boolean conflictFlag =
                hasUpdateModelConflictFlag(updaterCls, modelCls, updCtxCls, docCls, viewCls, paramCtxCls);
        final Method updateModelMethod = conflictFlag
                ? updaterCls.getMethod("a", modelCls, updCtxCls, docCls, viewCls, paramCtxCls, boolean.class)
                : updaterCls.getMethod("a", modelCls, updCtxCls, docCls, viewCls, paramCtxCls);
        final Object updCtx = updCtxCls.getConstructor(viewCls).newInstance((Object) null);

        final SyntheticModelHarness harness = new SyntheticModelHarness(
                loader,
                modelSource,
                model,
                rootSrc,
                rootWarp,
                morphSrc,
                morphDef,
                paramXSrc,
                paramX,
                updater,
                updateModelMethod,
                updCtx,
                allMeshes,
                List.of(),
                allMeshes,
                List.of(baseGrid),
                conflictFlag);
        return new Object[] {harness, paramC, morphSrc, morphDef};
    }

    private static void runSelectionDrawDirtyGatingParityOnComposedLoader(
            final ClassLoader rewLoader, final WarpDeformEditTarget target, final WarpDeformEditBridge bridge)
            throws Exception {
        final SyntheticModelHarness rew = buildSyntheticHierarchy(rewLoader, target);
        final Object[] ctx = createEditViewContext(rew.loader, rew.modelSource, rew.model);
        final Object doc = ctx[0];
        final Object view = ctx[1];
        final Object selector = ctx[2];
        final Object packAction = ctx[5];

        final Class<?> absViewCls = Class.forName("com.live2d.cubism.view.context.CEViewContext", false, rewLoader);
        final Class<?> modelingViewCls =
                Class.forName("com.live2d.cubism.view.context.CEViewContext_ModelingView", false, rewLoader);
        final Class<?> packCls = Class.forName("com.live2d.cubism.pack.CECompletePack", false, rewLoader);
        final Class<?> sceneExCls = Class.forName("com.live2d.cubism.view.context.CESceneGraphEx", true, rewLoader);
        final Class<?> actionNCls = Class.forName("com.live2d.cubism.view.context.actionManager.N", false, rewLoader);
        final Class<?> lazyCls = Class.forName("kotlin.Lazy", true, rewLoader);
        final Class<?> acCtrlSrcCls =
                Class.forName("com.live2d.cubism.doc.model.ACParameterControllableSource", false, rewLoader);

        final Object sceneGraph = sceneExCls.getConstructor().newInstance();
        final Object devSetting = modelingViewCls
                .getMethod("getDevelopSetting")
                .getReturnType()
                .getConstructor()
                .newInstance();
        final Object appSetting = modelingViewCls
                .getMethod("getAppearanceSetting")
                .getReturnType()
                .getConstructor()
                .newInstance();
        setDeclaredField(appSetting.getClass(), appSetting, "a", true);
        setDeclaredField(absViewCls, view, "developSetting", devSetting);
        setDeclaredField(absViewCls, view, "appearanceSetting", appSetting);
        setDeclaredField(absViewCls, view, "sceneGraph", sceneGraph);
        setDeclaredField(absViewCls, view, "_lastActionPack", packAction);
        setDeclaredField(absViewCls, view, "_optionOfIOption", new HashMap<>());
        setDeclaredField(absViewCls, view, "isDrawableVisible", true);
        setDeclaredField(absViewCls, view, "isDrawableLocked", false);
        setDeclaredField(absViewCls, view, "cameraManager", null);
        final Field docEditModeF =
                findFieldOwner(doc.getClass(), "_curEditMode").getDeclaredField("_curEditMode");
        docEditModeF.setAccessible(true);
        docEditModeF.set(doc, ctx[3]);

        final Object completePack = doc.getClass().getMethod("getCompletePack").invoke(doc);
        setDeclaredField(absViewCls, view, "completePack", completePack);
        final Class<?> unsafeCls = Class.forName("sun.misc.Unsafe");
        final Field theUnsafe = unsafeCls.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        final Object unsafe = theUnsafe.get(null);
        final Class<?> ctrlCls = Class.forName("com.live2d.cubism.CEAppCtrl", true, rewLoader);
        final Object appCtrl =
                unsafeCls.getMethod("allocateInstance", Class.class).invoke(unsafe, ctrlCls);
        setDeclaredField(ctrlCls, appCtrl, "_currentViewContext", view);
        invokeOnEdtWithLoader(rewLoader, () -> {
            final Class<?> toolModeCls = Class.forName(
                    "com.live2d.cubism.view.palette.tool.toolMode.deformBrush.ToolMode_DeformBrush", false, rewLoader);
            setDeclaredField(
                    ctrlCls,
                    appCtrl,
                    "_toolMode",
                    unsafeCls.getMethod("allocateInstance", Class.class).invoke(unsafe, toolModeCls));
        });
        setDeclaredField(packCls, completePack, "ctrl", appCtrl);
        setDeclaredField(actionNCls, packAction, "b", completePack);

        // Bind drawables list, Set, and HashSet Lazy fields on actionPack so K.E() executes cleanly across 5.3.x.
        final List<Object> twoMeshes = List.of(rew.subtreeAMeshes.get(0), rew.subtreeBMeshes.get(0));
        final Object lazyDrawables = java.lang.reflect.Proxy.newProxyInstance(
                rewLoader,
                new Class<?>[] {lazyCls},
                (proxy, m, args) -> "isInitialized".equals(m.getName()) ? Boolean.TRUE : twoMeshes);
        final Object lazyEmptySet = java.lang.reflect.Proxy.newProxyInstance(
                rewLoader,
                new Class<?>[] {lazyCls},
                (proxy, m, args) -> "isInitialized".equals(m.getName()) ? Boolean.TRUE : Set.of());
        final Object lazyEmptyHashSet = java.lang.reflect.Proxy.newProxyInstance(
                rewLoader,
                new Class<?>[] {lazyCls},
                (proxy, m, args) -> "isInitialized".equals(m.getName()) ? Boolean.TRUE : new java.util.HashSet<>());
        final Class<?> actionBaseCls =
                Class.forName("com.live2d.cubism.view.context.actionManager.b", false, rewLoader);
        setDeclaredField(actionBaseCls, packAction, "h", lazyEmptySet);
        setDeclaredField(actionBaseCls, packAction, "i", lazyEmptySet);
        setDeclaredField(actionBaseCls, packAction, "C", lazyEmptyHashSet);
        setDeclaredField(actionBaseCls, packAction, "E", lazyDrawables);
        setDeclaredField(actionNCls, packAction, "c", view);

        final Class<?> kCls = Class.forName(WarpDeformEditTarget.OVERLAY_VIEW_OWNER.replace('/', '.'), true, rewLoader);
        final Object viewHolder =
                unsafeCls.getMethod("allocateInstance", Class.class).invoke(unsafe, kCls);
        setDeclaredField(kCls, viewHolder, "b", view);
        setDeclaredField(kCls, viewHolder, "x", "selectionRoot");
        final Method updateSelM = kCls.getDeclaredMethod("E");
        updateSelM.setAccessible(true);

        // Select first mesh and establish native selectionDraw baseline (Pass 1).
        final Object mesh0Src =
                rew.subtreeAMeshes.get(0).getClass().getMethod("getSource").invoke(rew.subtreeAMeshes.get(0));
        selector.getClass().getMethod("addSelected", acCtrlSrcCls, int.class).invoke(selector, mesh0Src, 0);
        rew.invokeUpdate(doc, view);

        final long updatesBefore1 = bridge.snapshot().get("selectionDrawUpdates");
        invokeOnEdtWithLoader(rewLoader, () -> updateSelM.invoke(viewHolder));
        final Map<String, Long> afterPass1Stats = bridge.snapshot();
        assertTrue(
                afterPass1Stats.get("selectionDrawUpdates") > updatesBefore1,
                () -> "Pass 1 must record selectionDrawUpdates, stats=" + afterPass1Stats);
        assertTrue(
                afterPass1Stats.get("selectionMeshCreateElisions") > 0L,
                () -> "Pass 1 unselected mesh must elide DrawEntity_ArtMesh_Selection creation, stats="
                        + afterPass1Stats);

        final Object mesh0Sel = findSelectionChildEntity(rewLoader, rew.subtreeAMeshes.get(0));
        assertNotNull(mesh0Sel, "selected ArtMesh must lazily create DrawEntity_ArtMesh_Selection");
        assertNull(
                findSelectionChildEntity(rewLoader, rew.subtreeBMeshes.get(0)),
                "unselected ArtMesh must lazily skip DrawEntity_ArtMesh_Selection creation");

        final Field willRenderF = findFieldOwner(sceneGraph.getClass(), "volatileEntities_willRender")
                .getDeclaredField("volatileEntities_willRender");
        willRenderF.setAccessible(true);
        @SuppressWarnings("unchecked")
        final Set<Object> willRender = (Set<Object>) willRenderF.get(sceneGraph);
        final int expectedVolatileCount = willRender.size();
        assertTrue(expectedVolatileCount >= 1, "Pass 1 must mark selection entity volatile");
        assertTrue(willRender.contains(mesh0Sel));
        assertTrue((boolean) mesh0Sel.getClass().getMethod("getEnabled").invoke(mesh0Sel));
        willRender.clear();

        // Pass 2 unchanged: must elide full K.E() scan while preserving volatile entities.
        final long elisionsBefore2 = bridge.snapshot().get("selectionDrawElisions");
        final long updatesAfter1 = bridge.snapshot().get("selectionDrawUpdates");
        invokeOnEdtWithLoader(rewLoader, () -> updateSelM.invoke(viewHolder));
        assertTrue(
                bridge.snapshot().get("selectionDrawElisions") > elisionsBefore2,
                () -> "Pass 2 unchanged must increment selectionDrawElisions, stats=" + bridge.snapshot());
        assertEquals(updatesAfter1, bridge.snapshot().get("selectionDrawUpdates"));
        assertEquals(expectedVolatileCount, willRender.size(), "elided K.E() must restore volatileEntities_willRender");

        // Mutate selection -> Pass 3 must lazily create mesh1 selection entity and invalidate K.E().
        final Object mesh1Src =
                rew.subtreeBMeshes.get(0).getClass().getMethod("getSource").invoke(rew.subtreeBMeshes.get(0));
        selector.getClass().getMethod("addSelected", acCtrlSrcCls, int.class).invoke(selector, mesh1Src, 0);
        invokeOnEdtWithLoader(rewLoader, () -> updateSelM.invoke(viewHolder));
        final long updatesAfter3 = bridge.snapshot().get("selectionDrawUpdates");
        assertTrue(updatesAfter3 > updatesAfter1, "selection change must invalidate K.E()");
        final Object mesh1Sel = findSelectionChildEntity(rewLoader, rew.subtreeBMeshes.get(0));
        assertNotNull(mesh1Sel, "selecting mesh1 must lazily create its DrawEntity_ArtMesh_Selection");

        // Run real GSceneGraph postRender -> preRender so preRender disables both volatile entities before K.E():
        // selected mesh0 MUST still get setVolatile + enabled=true while unselected mesh1 skips setVolatile!
        selector.getClass().getMethod("clearSelection").invoke(selector);
        selector.getClass().getMethod("addSelected", acCtrlSrcCls, int.class).invoke(selector, mesh0Src, 0);
        dragRotationDeformer(rew.midRot, 18.0f, 3.0f);
        rew.invokeUpdate(doc, view);
        final Method postRenderM = sceneGraph.getClass().getMethod("postRender");
        final Method preRenderM = sceneGraph.getClass().getMethod("preRender");
        postRenderM.invoke(sceneGraph);
        preRenderM.invoke(sceneGraph);
        final long setVolElideBefore4 = bridge.snapshot().get("selectionSetVolatileElisions");
        invokeOnEdtWithLoader(rewLoader, () -> updateSelM.invoke(viewHolder));
        final Map<String, Long> afterPass4Stats = bridge.snapshot();
        assertTrue(afterPass4Stats.get("selectionDrawUpdates") > updatesAfter3, "model update must invalidate K.E()");
        assertTrue(willRender.contains(mesh0Sel), "selected mesh0 must stay registered volatile after preRender");
        assertTrue(
                (boolean) mesh0Sel.getClass().getMethod("getEnabled").invoke(mesh0Sel),
                "selected mesh0 selection entity must be re-enabled by setVolatile");
        assertFalse(willRender.contains(mesh1Sel), "inactive mesh1 must skip redundant setVolatile");
        assertTrue(
                afterPass4Stats.get("selectionSetVolatileElisions") > setVolElideBefore4,
                () -> "expected inactive setVolatile elision, stats=" + afterPass4Stats);

        dragRotationDeformer(rew.midRot, 24.0f, 4.0f);
        rew.invokeUpdate(doc, view);
        invokeOnEdtWithLoader(rewLoader, () -> updateSelM.invoke(viewHolder));
        assertTrue(
                bridge.snapshot().get("selectionMeshElisions") > 0L,
                () -> "expected DrawEntity_ArtMesh_Selection per-mesh elision on untouched disabled mesh, stats="
                        + bridge.snapshot());

        // Re-selecting mesh1 after preRender disabled it must immediately re-enable and register volatile.
        selector.getClass().getMethod("addSelected", acCtrlSrcCls, int.class).invoke(selector, mesh1Src, 0);
        postRenderM.invoke(sceneGraph);
        preRenderM.invoke(sceneGraph);
        invokeOnEdtWithLoader(rewLoader, () -> updateSelM.invoke(viewHolder));
        assertTrue(willRender.contains(mesh1Sel), "re-selected mesh1 must call setVolatile");
        assertTrue(
                (boolean) mesh1Sel.getClass().getMethod("getEnabled").invoke(mesh1Sel),
                "re-selected mesh1 selection entity must become enabled");
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }

    private static void invokeOnEdtWithLoader(final ClassLoader loader, final ThrowingAction action) throws Exception {
        final java.util.concurrent.CompletableFuture<Void> done = new java.util.concurrent.CompletableFuture<>();
        javax.swing.SwingUtilities.invokeLater(() -> {
            final Thread current = Thread.currentThread();
            final ClassLoader prev = current.getContextClassLoader();
            current.setContextClassLoader(loader);
            try {
                action.run();
                done.complete(null);
            } catch (final Exception failure) {
                done.completeExceptionally(failure);
            } finally {
                current.setContextClassLoader(prev);
            }
        });
        try {
            done.get(BOOTSTRAP_CHILD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (final java.util.concurrent.ExecutionException wrapped) {
            if (wrapped.getCause() instanceof Exception ex) {
                throw ex;
            }
            throw new IllegalStateException("EDT selection-draw task failed", wrapped.getCause());
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

    private static void verifyWarpSourceReinitPosReuseAndNoUndoAliasing(
            final ClassLoader origLoader, final ClassLoader rewLoader, final WarpDeformEditBridge bridge)
            throws Exception {
        final Object[] origPair = buildSingleWarpDeformerModel(origLoader);
        final Object[] rewPair = buildSingleWarpDeformerModel(rewLoader);
        final Object origSrc = origPair[0];
        final Object origDef = origPair[1];
        final Object rewSrc = rewPair[0];
        final Object rewDef = rewPair[1];

        final Method reinitOrig = origSrc.getClass()
                .getMethod(
                        "reinitInstance",
                        Class.forName("com.live2d.cubism.doc.model.ACParameterControllable", false, origLoader));
        final Method reinitRew = rewSrc.getClass()
                .getMethod(
                        "reinitInstance",
                        Class.forName("com.live2d.cubism.doc.model.ACParameterControllable", false, rewLoader));

        final Object rewInterp =
                rewDef.getClass().getMethod("getInterpolatedForm").invoke(rewDef);
        final Method getPosRew = rewInterp.getClass().getMethod("getPositions");
        final float[] initialBuf = (float[]) getPosRew.invoke(rewInterp);
        final long beforeReused = bridge.snapshot().get("warpReinitPosReused");

        reinitOrig.invoke(origSrc, origDef);
        reinitRew.invoke(rewSrc, rewDef);

        final float[] afterBuf = (float[]) getPosRew.invoke(rewInterp);
        assertSame(initialBuf, afterBuf, "same-length CWarpDeformerSource.reinitInstance must reuse float[] buffer");
        assertTrue(bridge.snapshot().get("warpReinitPosReused") > beforeReused);

        final Object origInterp =
                origDef.getClass().getMethod("getInterpolatedForm").invoke(origDef);
        final float[] origPos =
                (float[]) origInterp.getClass().getMethod("getPositions").invoke(origInterp);
        assertRawFloatArraysEqual(origPos, afterBuf, "reinitInstance first pass");

        // Second reinitInstance and undo/default-keyform isolation: mutating default keyform must never alias.
        final Object rewDefaultKf =
                rewSrc.getClass().getMethod("getDefaultKeyForm").invoke(rewSrc);
        final float[] kfPos =
                (float[]) rewDefaultKf.getClass().getMethod("getPositions").invoke(rewDefaultKf);
        kfPos[0] = 77.25f;
        final Object origDefaultKf =
                origSrc.getClass().getMethod("getDefaultKeyForm").invoke(origSrc);
        final float[] origKfPos =
                (float[]) origDefaultKf.getClass().getMethod("getPositions").invoke(origDefaultKf);
        origKfPos[0] = 77.25f;
        reinitOrig.invoke(origSrc, origDef);
        reinitRew.invoke(rewSrc, rewDef);
        kfPos[0] = -123.5f;
        assertEquals(77.25f, afterBuf[0], 0.0f, "mutating source keyform must not alias deformer positions");

        // Mismatched destination buffer length must reallocate cleanly without ArrayIndexOutOfBoundsException.
        rewInterp.getClass().getMethod("setPositions", float[].class).invoke(rewInterp, new float[2]);
        kfPos[0] = 9.5f;
        origKfPos[0] = 9.5f;
        reinitOrig.invoke(origSrc, origDef);
        reinitRew.invoke(rewSrc, rewDef);
        final float[] reallocated = (float[]) getPosRew.invoke(rewInterp);
        final float[] origRealloc =
                (float[]) origInterp.getClass().getMethod("getPositions").invoke(origInterp);
        assertEquals(origRealloc.length, reallocated.length);
        assertRawFloatArraysEqual(origRealloc, reallocated, "reinitInstance mismatched-length fallback");
    }

    private static Object[] buildSingleWarpDeformerModel(final ClassLoader loader) throws Exception {
        final Class<?> modelSrcCls = Class.forName("com.live2d.cubism.doc.model.CModelSource", true, loader);
        final Class<?> modelCls = Class.forName("com.live2d.cubism.doc.model.CModel", true, loader);
        final Class<?> defGuidCls = Class.forName("com.live2d.type.CDeformerGuid", true, loader);
        final Class<?> defIdCls = Class.forName("com.live2d.cubism.doc.model.id.CDeformerId", true, loader);
        final Class<?> formGuidCls = Class.forName("com.live2d.type.CFormGuid", true, loader);
        final Class<?> coordCls = Class.forName("com.live2d.doc.CoordType", true, loader);
        final Class<?> warpSrcCls =
                Class.forName(WarpDeformEditTarget.WARP_SOURCE_OWNER.replace('/', '.'), true, loader);
        final Class<?> warpFormCls =
                Class.forName(WarpDeformEditTarget.WARP_FORM_OWNER.replace('/', '.'), true, loader);
        final Class<?> renderTypeCls = Class.forName("com.live2d.graphics3d.rendering.RenderType", true, loader);
        final Class<?> acDefSrcCls =
                Class.forName("com.live2d.cubism.doc.model.deformer.ACDeformerSource", true, loader);
        final Class<?> acCtrlSrcCls =
                Class.forName("com.live2d.cubism.doc.model.ACParameterControllableSource", true, loader);

        final Object coordLocal = coordCls.getMethod("b").invoke(null);
        final Object modelSource = modelSrcCls.getConstructor().newInstance();
        final Object rootPart = modelSrcCls.getMethod("getRootPart").invoke(modelSource);
        final Object defSet = modelSrcCls.getMethod("getDeformerSourceSet").invoke(modelSource);
        final Object guid =
                defGuidCls.getConstructor(UUID.class).newInstance(UUID.nameUUIDFromBytes("reinitW".getBytes()));
        final float[] grid = {-10f, -10f, 0f, -10f, 10f, -10f, -10f, 0f, 0f, 0f, 10f, 0f, -10f, 10f, 0f, 10f, 10f, 10f};
        final Object src = createWarpDeformerSource(
                modelSrcCls,
                modelSource,
                warpSrcCls,
                warpFormCls,
                defGuidCls,
                defIdCls,
                formGuidCls,
                coordCls,
                guid,
                "ReinitW",
                null,
                coordLocal,
                grid);
        defSet.getClass().getMethod("add", acDefSrcCls, int.class).invoke(defSet, src, -1);
        rootPart.getClass().getMethod("addChild", acCtrlSrcCls, int.class).invoke(rootPart, src, -1);
        final Object renderNormal = Enum.valueOf((Class) renderTypeCls, "NORMAL");
        final Object model =
                modelSrcCls.getMethod("createModelInstance", renderTypeCls).invoke(modelSource, renderNormal);
        final Object def = modelCls.getMethod("getDeformer", defGuidCls).invoke(model, guid);
        return new Object[] {src, def};
    }

    private static void verifyRotationTransformScratchParity(
            final ClassLoader origLoader, final ClassLoader rewLoader, final WarpDeformEditBridge bridge)
            throws Exception {
        final long beforeRotScratch = bridge.snapshot().get("rotScratchReused");
        final boolean[][] reflects = {{false, false}, {true, false}, {false, true}, {true, true}};
        for (final boolean[] ref : reflects) {
            final float[] origWithParent = invokeRotationTransformSample(origLoader, true, ref[0], ref[1]);
            final float[] rewWithParent = invokeRotationTransformSample(rewLoader, true, ref[0], ref[1]);
            assertRawFloatArraysEqual(
                    origWithParent, rewWithParent, "rot transform with parent reflect=" + ref[0] + "/" + ref[1]);

            final float[] origNoParent = invokeRotationTransformSample(origLoader, false, ref[0], ref[1]);
            final float[] rewNoParent = invokeRotationTransformSample(rewLoader, false, ref[0], ref[1]);
            assertRawFloatArraysEqual(
                    origNoParent, rewNoParent, "rot transform without parent reflect=" + ref[0] + "/" + ref[1]);
        }
        assertTrue(
                bridge.snapshot().get("rotScratchReused") - beforeRotScratch >= 9L,
                () -> "expected rotScratchReused >= 9, stats=" + bridge.snapshot());
    }

    private static float[] invokeRotationTransformSample(
            final ClassLoader loader, final boolean withParent, final boolean reflectX, final boolean reflectY)
            throws Exception {
        final Class<?> rotFormCls = Class.forName(WarpDeformEditTarget.ROT_FORM_OWNER.replace('/', '.'), true, loader);
        final Class<?> rotDefCls =
                Class.forName("com.live2d.cubism.doc.model.deformer.rotation.CRotationDeformer", true, loader);
        final Class<?> rotSrcCls =
                Class.forName("com.live2d.cubism.doc.model.deformer.rotation.CRotationDeformerSource", true, loader);
        final Class<?> modelSrcCls = Class.forName("com.live2d.cubism.doc.model.CModelSource", true, loader);
        final Class<?> coordCls = Class.forName("com.live2d.doc.CoordType", true, loader);
        final Class<?> trCls = Class.forName("com.live2d.doc.selection.d", true, loader);
        final Class<?> acFormCls = Class.forName("com.live2d.cubism.doc.model.ACForm", true, loader);
        final Class<?> sgCls = Class.forName(WarpDeformEditTarget.SIMPLE_GRID_OWNER.replace('/', '.'), true, loader);
        final Class<?> gtCls = Class.forName(WarpDeformEditTarget.GRID_TRANSFORM_OWNER.replace('/', '.'), true, loader);
        final Class<?> rectCls = Class.forName(WarpDeformEditTarget.GRECTF_OWNER.replace('/', '.'), true, loader);
        final Class<?> icGrid = Class.forName("com.live2d.cubism.doc.model.deformer.warp.grid.ICGrid", true, loader);

        final Object modelSource = modelSrcCls.getConstructor().newInstance();
        final Object rotSrc = rotSrcCls.getConstructor(modelSrcCls).newInstance(modelSource);
        final Object coordLocal = coordCls.getMethod("b").invoke(null);
        final Object form =
                rotFormCls.getConstructor(rotSrcCls, rotDefCls, coordCls).newInstance(rotSrc, null, coordLocal);
        final Object out =
                rotFormCls.getConstructor(rotSrcCls, rotDefCls, coordCls).newInstance(rotSrc, null, coordLocal);
        rotFormCls.getMethod("setOriginX", float.class).invoke(form, 14.5f);
        rotFormCls.getMethod("setOriginY", float.class).invoke(form, -7.25f);
        rotFormCls.getMethod("setAngle", float.class).invoke(form, 37.5f);
        rotFormCls.getMethod("setScale", float.class).invoke(form, 1.35f);
        rotFormCls.getMethod("setReflectX", boolean.class).invoke(form, reflectX);
        rotFormCls.getMethod("setReflectY", boolean.class).invoke(form, reflectY);

        final float[] pts = {
            -10f, -15f, 45f, -5f, 110f, 0f, -15f, 45f, 52f, 55f, 120f, 60f, 0f, 105f, 55f, 115f, 130f, 125f
        };
        final Object sg =
                sgCls.getConstructor(int.class, int.class, float[].class).newInstance(2, 2, pts);
        final Object rect = rectCls.getConstructor(float.class, float.class, float.class, float.class)
                .newInstance(0f, 0f, 100f, 100f);
        final Object gridTr =
                gtCls.getConstructor(rectCls, icGrid, int.class, int.class).newInstance(rect, sg, 2, 2);

        final Object parentTr = withParent ? gridTr : null;
        final Method exeM = rotFormCls.getDeclaredMethod("transform_exe", trCls, trCls, acFormCls);
        exeM.setAccessible(true);
        final Object result = exeM.invoke(form, gridTr, parentTr, out);
        return new float[] {
            (float) rotFormCls.getMethod("getOriginX").invoke(result),
            (float) rotFormCls.getMethod("getOriginY").invoke(result),
            (float) rotFormCls.getMethod("getAngle").invoke(result),
            (float) rotFormCls.getMethod("getScale").invoke(result),
            ((boolean) rotFormCls.getMethod("isReflectX").invoke(result)) ? 1.0f : 0.0f,
            ((boolean) rotFormCls.getMethod("isReflectY").invoke(result)) ? 1.0f : 0.0f
        };
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
        public URL findResource(final String name) {
            final URL found = super.findResource(name);
            if (found != null) {
                return found;
            }
            if (name != null && name.startsWith("res/image/")) {
                return super.findResource("res/image_dark/" + name.substring("res/image/".length()));
            }
            return null;
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
            if ((name.startsWith("com.jogamp.")
                            || name.startsWith("com.formdev.flatlaf.")
                            || name.startsWith("kotlin."))
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

    /** Child-JVM fixture running SubtreeParallelExecutor from the bootstrap loader. */
    public static final class BootstrapSubtreeExecutorChild {

        private static final int BATCH_ITEMS = 64;
        private static final String WORKER_PREFIX = "turboism-deform-worker-";

        private BootstrapSubtreeExecutorChild() {}

        /** Verifies no-arg and explicit host-loader construction when bootstrap-loaded. */
        public static void main(final String[] ignored) throws Exception {
            if (SubtreeParallelExecutor.class.getClassLoader() != null) {
                throw new AssertionError("SubtreeParallelExecutor did not load on bootstrap");
            }
            final ClassLoader systemLoader = ClassLoader.getSystemClassLoader();
            try (SubtreeParallelExecutor exec = new SubtreeParallelExecutor()) {
                runAndVerify(exec, systemLoader);
            }
            try (URLClassLoader hostLoader = new URLClassLoader(new URL[0], systemLoader);
                    SubtreeParallelExecutor exec = new SubtreeParallelExecutor(hostLoader)) {
                runAndVerify(exec, hostLoader);
            }
        }

        static void runAndVerify(final SubtreeParallelExecutor exec, final ClassLoader expectedWorkerLoader) {
            final AtomicIntegerArray hits = new AtomicIntegerArray(BATCH_ITEMS);
            final AtomicReference<Throwable> mismatch = new AtomicReference<>();
            exec.executeBatch(BATCH_ITEMS, SubtreeParallelExecutor.MIN_PARALLEL_MESHES, index -> {
                final Thread current = Thread.currentThread();
                if (current.getName().startsWith(WORKER_PREFIX)
                        && (current.getContextClassLoader() == null
                                || current.getContextClassLoader() != expectedWorkerLoader)) {
                    mismatch.compareAndSet(
                            null,
                            new AssertionError(
                                    "worker inherited wrong context loader: " + current.getContextClassLoader()));
                }
                hits.incrementAndGet(index);
            });
            if (mismatch.get() != null) {
                throw new AssertionError("worker verification failed", mismatch.get());
            }
            for (int i = 0; i < BATCH_ITEMS; i++) {
                if (hits.get(i) != 1) {
                    throw new AssertionError("missing execution for index " + i);
                }
            }
        }
    }

    private static void runStrictVerifyAllSubprocess(final Path manifestFile, final int expectedClasses)
            throws Exception {
        final String javaExe = Path.of(System.getProperty(JAVA_HOME_PROPERTY), JAVA_BIN_DIR, JAVA_EXECUTABLE)
                .toString();
        final Process process = new ProcessBuilder(
                        javaExe,
                        "-Xverify:all",
                        CLASSPATH_FLAG,
                        System.getProperty(JAVA_CLASS_PATH_PROPERTY),
                        StrictWarpDeformVerifyChild.class.getName(),
                        manifestFile.toString(),
                        Integer.toString(expectedClasses))
                .redirectErrorStream(true)
                .start();
        final String output;
        try (InputStream stream = process.getInputStream()) {
            output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(process.waitFor(BOOTSTRAP_CHILD_TIMEOUT_SECONDS * 4L, TimeUnit.SECONDS), "-Xverify:all timed out");
        assertEquals(0, process.exitValue(), "-Xverify:all verification failed:\n" + output);
    }

    /**
     * Snapshots and restores JVM-global Swing LookAndFeel and Toolkit listeners before closing host classloaders.
     *
     * <p>Host view initialization installs FlatLaf and Toolkit listeners from the temporary {@link
     * RewrittenHostClassLoader}, which otherwise break later Swing UI tests once the loader closes.
     */
    private static final class SwingGlobalStateGuard implements AutoCloseable {

        private final LookAndFeel previousLaf;
        private final Object previousUiClassLoader;
        private final Set<AWTEventListener> previousAwtListeners;
        private final Set<PropertyChangeListener> previousToolkitPropertyListeners;
        private final Set<PropertyChangeListener> previousUiManagerListeners;
        private final Set<PropertyChangeListener> previousUiDefaultsListeners;
        private final Set<PropertyChangeListener> previousLafDefaultsListeners;
        private final String previousAppleMenuBar;
        private final String previousFlatLafMenuBar;

        private SwingGlobalStateGuard(
                final LookAndFeel previousLaf,
                final Object previousUiClassLoader,
                final Set<AWTEventListener> previousAwtListeners,
                final Set<PropertyChangeListener> previousToolkitPropertyListeners,
                final Set<PropertyChangeListener> previousUiManagerListeners,
                final Set<PropertyChangeListener> previousUiDefaultsListeners,
                final Set<PropertyChangeListener> previousLafDefaultsListeners,
                final String previousAppleMenuBar,
                final String previousFlatLafMenuBar) {
            this.previousLaf = previousLaf;
            this.previousUiClassLoader = previousUiClassLoader;
            this.previousAwtListeners = previousAwtListeners;
            this.previousToolkitPropertyListeners = previousToolkitPropertyListeners;
            this.previousUiManagerListeners = previousUiManagerListeners;
            this.previousUiDefaultsListeners = previousUiDefaultsListeners;
            this.previousLafDefaultsListeners = previousLafDefaultsListeners;
            this.previousAppleMenuBar = previousAppleMenuBar;
            this.previousFlatLafMenuBar = previousFlatLafMenuBar;
        }

        static SwingGlobalStateGuard capture() throws Exception {
            final AtomicReference<SwingGlobalStateGuard> holder = new AtomicReference<>();
            runOnEdtAndWait(() -> {
                final Toolkit toolkit = Toolkit.getDefaultToolkit();
                final UIDefaults uiDefaults = UIManager.getDefaults();
                final UIDefaults lafDefaults = UIManager.getLookAndFeelDefaults();
                holder.set(new SwingGlobalStateGuard(
                        UIManager.getLookAndFeel(),
                        uiDefaults.get(UI_CLASS_LOADER_KEY),
                        snapshotAwtListeners(toolkit),
                        snapshotPropertyListeners(toolkit.getPropertyChangeListeners()),
                        snapshotPropertyListeners(UIManager.getPropertyChangeListeners()),
                        snapshotPropertyListeners(uiDefaults.getPropertyChangeListeners()),
                        snapshotPropertyListeners(lafDefaults.getPropertyChangeListeners()),
                        System.getProperty(APPLE_MENU_BAR_PROPERTY),
                        System.getProperty(FLATLAF_MENU_BAR_PROPERTY)));
            });
            return holder.get();
        }

        @Override
        public void close() throws Exception {
            try {
                // Restore LookAndFeel while RewrittenHostClassLoader remains open so FlatLaf uninitializes cleanly.
                runOnEdtAndWait(() -> {
                    final Toolkit toolkit = Toolkit.getDefaultToolkit();
                    final UIDefaults uiDefaults = UIManager.getDefaults();
                    final UIDefaults lafDefaults = UIManager.getLookAndFeelDefaults();
                    removeAddedAwtListeners(toolkit);
                    removeAddedToolkitPropertyListeners(toolkit);
                    // Remove FlatLaf UIScale UIManager/UIDefaults listeners before LookAndFeel reset fires events.
                    removeAddedPropertyListeners(
                            UIManager.getPropertyChangeListeners(),
                            previousUiManagerListeners,
                            UIManager::removePropertyChangeListener);
                    removeAddedPropertyListeners(
                            uiDefaults.getPropertyChangeListeners(),
                            previousUiDefaultsListeners,
                            uiDefaults::removePropertyChangeListener);
                    removeAddedPropertyListeners(
                            lafDefaults.getPropertyChangeListeners(),
                            previousLafDefaultsListeners,
                            lafDefaults::removePropertyChangeListener);
                    if (UIManager.getLookAndFeel() != previousLaf) {
                        UIManager.setLookAndFeel(previousLaf);
                    }
                    final Object currentUiClassLoader = uiDefaults.get(UI_CLASS_LOADER_KEY);
                    if (previousUiClassLoader == null) {
                        uiDefaults.remove(UI_CLASS_LOADER_KEY);
                    } else if (currentUiClassLoader != previousUiClassLoader) {
                        UIManager.put(UI_CLASS_LOADER_KEY, previousUiClassLoader);
                    }
                });
                // Flush follow-up EDT runnables queued during LookAndFeel uninitialization before loader close.
                runOnEdtAndWait(() -> {});
            } finally {
                restoreSystemProperty(APPLE_MENU_BAR_PROPERTY, previousAppleMenuBar);
                restoreSystemProperty(FLATLAF_MENU_BAR_PROPERTY, previousFlatLafMenuBar);
            }
        }

        private void removeAddedAwtListeners(final Toolkit toolkit) {
            for (final AWTEventListener listener : toolkit.getAWTEventListeners()) {
                final AWTEventListener raw = unwrapAwtListener(listener);
                if (!previousAwtListeners.contains(raw)) {
                    toolkit.removeAWTEventListener(raw);
                }
            }
        }

        private void removeAddedToolkitPropertyListeners(final Toolkit toolkit) {
            for (final PropertyChangeListener listener : toolkit.getPropertyChangeListeners()) {
                if (listener instanceof PropertyChangeListenerProxy proxy
                        && !previousToolkitPropertyListeners.contains(proxy.getListener())) {
                    toolkit.removePropertyChangeListener(proxy.getPropertyName(), proxy.getListener());
                }
            }
        }

        private static void removeAddedPropertyListeners(
                final PropertyChangeListener[] listeners,
                final Set<PropertyChangeListener> baseline,
                final Consumer<PropertyChangeListener> remover) {
            for (final PropertyChangeListener listener : listeners) {
                final PropertyChangeListener raw = unwrapPropertyListener(listener);
                if (!baseline.contains(raw)) {
                    remover.accept(raw);
                }
            }
        }

        private static Set<AWTEventListener> snapshotAwtListeners(final Toolkit toolkit) {
            final Set<AWTEventListener> snapshot = Collections.newSetFromMap(new IdentityHashMap<>());
            for (final AWTEventListener listener : toolkit.getAWTEventListeners()) {
                snapshot.add(unwrapAwtListener(listener));
            }
            return snapshot;
        }

        private static Set<PropertyChangeListener> snapshotPropertyListeners(final PropertyChangeListener[] listeners) {
            final Set<PropertyChangeListener> snapshot = Collections.newSetFromMap(new IdentityHashMap<>());
            for (final PropertyChangeListener listener : listeners) {
                snapshot.add(unwrapPropertyListener(listener));
            }
            return snapshot;
        }

        private static AWTEventListener unwrapAwtListener(final AWTEventListener listener) {
            return listener instanceof AWTEventListenerProxy proxy ? proxy.getListener() : listener;
        }

        private static PropertyChangeListener unwrapPropertyListener(final PropertyChangeListener listener) {
            return listener instanceof PropertyChangeListenerProxy proxy ? proxy.getListener() : listener;
        }

        private static void runOnEdtAndWait(final ThrowingAction action) throws Exception {
            if (SwingUtilities.isEventDispatchThread()) {
                action.run();
                return;
            }
            final AtomicReference<Exception> failure = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                try {
                    action.run();
                } catch (final Exception exception) {
                    failure.set(exception);
                }
            });
            if (failure.get() != null) {
                throw failure.get();
            }
        }

        private static void restoreSystemProperty(final String key, final String previous) {
            if (previous == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, previous);
            }
        }
    }

    private static Object findSelectionChildEntity(final ClassLoader loader, final Object artMesh) throws Exception {
        final Class<?> selCls = Class.forName(
                WarpDeformEditTarget.DRAW_ENTITY_ARTMESH_SELECTION_OWNER.replace('/', '.'), false, loader);
        final Object entity = artMesh.getClass().getMethod("getEntity").invoke(artMesh);
        final Iterable<?> children =
                (Iterable<?>) entity.getClass().getMethod("getChildren").invoke(entity);
        for (final Object child : children) {
            if (selCls.isInstance(child)) {
                return child;
            }
        }
        return null;
    }

    private static void runPartVisibilityAndMeshKeyformInvalidationParity(
            final ClassLoader origLoader,
            final ClassLoader rewLoader,
            final WarpDeformEditTarget target,
            final WarpDeformEditBridge bridge)
            throws Exception {
        final SyntheticModelHarness orig = buildSyntheticHierarchy(origLoader, target);
        final SyntheticModelHarness rew = buildSyntheticHierarchy(rewLoader, target);
        orig.invokeUpdate();
        rew.invokeUpdate();
        assertAllMeshesRawFloatParity(orig, rew, "part-vis-baseline");

        // Warm one fast drag frame so part hierarchy visibility cache is active.
        final float[] dragged = rew.baseRootGridPositions.get(0).clone();
        dragged[0] += 6.0f;
        dragWarpDeformer(orig.rootWarp, dragged);
        dragWarpDeformer(rew.rootWarp, dragged);
        final long fastBefore = bridge.snapshot().get("fastModelUpdates");
        orig.invokeUpdate();
        rew.invokeUpdate();
        assertTrue(bridge.snapshot().get("fastModelUpdates") > fastBefore);
        assertAllMeshesRawFloatParity(orig, rew, "part-vis-warm-drag");

        // Hide the root part and verify invisibility propagates identically to all ArtMeshes.
        final Object origRootPart =
                orig.modelSource.getClass().getMethod("getRootPart").invoke(orig.modelSource);
        final Object rewRootPart =
                rew.modelSource.getClass().getMethod("getRootPart").invoke(rew.modelSource);
        final Method origSetVis = origRootPart.getClass().getMethod("setVisible", boolean.class);
        final Method origLockVis = origRootPart.getClass().getMethod("setVisibleAndLockInHierarchy");
        final Method rewSetVis = rewRootPart.getClass().getMethod("setVisible", boolean.class);
        final Method rewLockVis = rewRootPart.getClass().getMethod("setVisibleAndLockInHierarchy");

        origSetVis.invoke(origRootPart, false);
        origLockVis.invoke(origRootPart);
        rewSetVis.invoke(rewRootPart, false);
        rewLockVis.invoke(rewRootPart);
        orig.invokeUpdate();
        rew.invokeUpdate();
        assertAllMeshesRawFloatParity(orig, rew, "part-hidden-parity");
        for (int i = 0; i < orig.allMeshes.size(); i++) {
            final boolean origInv = (boolean)
                    orig.allMeshes.get(i).getClass().getMethod("isInvisible").invoke(orig.allMeshes.get(i));
            final boolean rewInv = (boolean)
                    rew.allMeshes.get(i).getClass().getMethod("isInvisible").invoke(rew.allMeshes.get(i));
            assertEquals(origInv, rewInv, "isInvisible mismatch at mesh " + i);
            assertTrue(rewInv, "hiding root part must make child mesh invisible");
        }

        // Restore part visibility and mutate a local ArtMesh keyform + ACForm.setUpdated() -> must invalidate epochs!
        origSetVis.invoke(origRootPart, true);
        origLockVis.invoke(origRootPart);
        rewSetVis.invoke(rewRootPart, true);
        rewLockVis.invoke(rewRootPart);
        final Object origMesh0Src =
                orig.subtreeAMeshes.get(0).getClass().getMethod("getSource").invoke(orig.subtreeAMeshes.get(0));
        final Object rewMesh0Src =
                rew.subtreeAMeshes.get(0).getClass().getMethod("getSource").invoke(rew.subtreeAMeshes.get(0));
        final List<?> origMeshKfs =
                (List<?>) origMesh0Src.getClass().getMethod("getKeyforms").invoke(origMesh0Src);
        final List<?> rewMeshKfs =
                (List<?>) rewMesh0Src.getClass().getMethod("getKeyforms").invoke(rewMesh0Src);
        final Object origMeshForm = origMeshKfs.get(0);
        final Object rewMeshForm = rewMeshKfs.get(0);
        final float[] editedMeshPos =
                ((float[]) rewMeshForm.getClass().getMethod("getPositions").invoke(rewMeshForm)).clone();
        editedMeshPos[0] += 7.25f;
        editedMeshPos[1] -= 3.5f;
        origMeshForm.getClass().getMethod("setPositions", float[].class).invoke(origMeshForm, editedMeshPos.clone());
        origMeshForm.getClass().getMethod("setUpdated").invoke(origMeshForm);
        rewMeshForm.getClass().getMethod("setPositions", float[].class).invoke(rewMeshForm, editedMeshPos.clone());
        rewMeshForm.getClass().getMethod("setUpdated").invoke(rewMeshForm);

        orig.invokeUpdate();
        rew.invokeUpdate();
        assertAllMeshesRawFloatParity(orig, rew, "part-restored-mesh-keyform-updated");
    }

    private static void verifyExtensionBezierParity(
            final ClassLoader origLoader,
            final ClassLoader rewLoader,
            final WarpDeformEditTarget target,
            final WarpDeformEditBridge bridge)
            throws Exception {
        if (!target.targetClasses().contains(WarpDeformEditTarget.EXTENSION_BEZIER_EVAL_OWNER)) {
            return;
        }
        final Map<String, Long> beforeStats = bridge.snapshot();
        final String extDName = WarpDeformEditTarget.EXTENSION_BEZIER_EVAL_OWNER.replace('/', '.');
        final String extFName = WarpDeformEditTarget.EXTENSION_BEZIER_MATH_OWNER.replace('/', '.');
        final String extCName = extDName.substring(0, extDName.length() - 1) + "c";
        final String extIName = extDName.substring(0, extDName.length() - 1) + "i";
        final String vecName = WarpDeformEditTarget.GVECTOR2_OWNER.replace('/', '.');

        final Class<?> origDCls = Class.forName(extDName, true, origLoader);
        final Class<?> rewDCls = Class.forName(extDName, true, rewLoader);
        final Class<?> origFCls = Class.forName(extFName, true, origLoader);
        final Class<?> rewFCls = Class.forName(extFName, true, rewLoader);
        final Class<?> origCCls = Class.forName(extCName, true, origLoader);
        final Class<?> rewCCls = Class.forName(extCName, true, rewLoader);
        final Class<?> origICls = Class.forName(extIName, true, origLoader);
        final Class<?> rewICls = Class.forName(extIName, true, rewLoader);
        final Class<?> origVecCls = Class.forName(vecName, true, origLoader);
        final Class<?> rewVecCls = Class.forName(vecName, true, rewLoader);

        final int divCol = 8;
        final int divRow = 6;
        final int bezCol = 2;
        final int bezRow = 2;
        final Object origD0 = origDCls.getConstructor(
                        Class.forName(WarpDeformEditTarget.BEZIER_GRID_OWNER.replace('/', '.'), true, origLoader))
                .newInstance(buildSampleBezierGrid(origLoader, 0.0f, 0.0f));
        final Object rewD0 = rewDCls.getConstructor(
                        Class.forName(WarpDeformEditTarget.BEZIER_GRID_OWNER.replace('/', '.'), true, rewLoader))
                .newInstance(buildSampleBezierGrid(rewLoader, 0.0f, 0.0f));
        final Object origD1 = origDCls.getConstructor(
                        Class.forName(WarpDeformEditTarget.BEZIER_GRID_OWNER.replace('/', '.'), true, origLoader))
                .newInstance(buildSampleBezierGrid(origLoader, 14.5f, -9.25f));
        final Object rewD1 = rewDCls.getConstructor(
                        Class.forName(WarpDeformEditTarget.BEZIER_GRID_OWNER.replace('/', '.'), true, rewLoader))
                .newInstance(buildSampleBezierGrid(rewLoader, 14.5f, -9.25f));

        final Object origPrevI = origICls.getConstructor(int.class, int.class, int.class, int.class)
                .newInstance(divCol, divRow, bezCol, bezRow);
        final Object rewPrevI = rewICls.getConstructor(int.class, int.class, int.class, int.class)
                .newInstance(divCol, divRow, bezCol, bezRow);
        final Object origCurI = origICls.getConstructor(int.class, int.class, int.class, int.class)
                .newInstance(divCol, divRow, bezCol, bezRow);
        final Object rewCurI = rewICls.getConstructor(int.class, int.class, int.class, int.class)
                .newInstance(divCol, divRow, bezCol, bezRow);

        final int gridCount = (divCol + 1) * (divRow + 1);
        final float[] baseParams = new float[gridCount * 2];
        for (int idx = 0; idx < gridCount; idx++) {
            final int colIdx = idx % (divCol + 1);
            final int rowIdx = idx / (divCol + 1);
            baseParams[idx * 2] = colIdx * 24.0f + (rowIdx % 2 == 0 ? 1.5f : -2.25f);
            baseParams[idx * 2 + 1] = rowIdx * 26.0f + (colIdx % 2 == 0 ? -1.0f : 3.0f);
        }
        final float[] origCurParams = baseParams.clone();
        final float[] rewCurParams = baseParams.clone();

        final Method origDrag =
                origDCls.getMethod("a", origDCls, origICls, origICls, int.class, int.class, float[].class);
        final Method rewDrag = rewDCls.getMethod("a", rewDCls, rewICls, rewICls, int.class, int.class, float[].class);
        final List<?> origDragOut =
                (List<?>) origDrag.invoke(origD1, origD0, origPrevI, origCurI, divCol, divRow, origCurParams);
        final List<?> rewDragOut =
                (List<?>) rewDrag.invoke(rewD1, rewD0, rewPrevI, rewCurI, divCol, divRow, rewCurParams);
        assertEquals(origDragOut.size(), rewDragOut.size(), "extension drag output size mismatch");
        assertRawFloatArraysEqual(origCurParams, rewCurParams, "extension drag curParams");
        for (int i = 0; i < origDragOut.size(); i++) {
            assertVectorRawBitParity(origDragOut.get(i), rewDragOut.get(i), "extension drag vec " + i);
        }

        // Verify Coons evaluation & Nelder-Mead inverse (including exterior shrink target) + bicubic grid eval.
        final Field origDNodesField = origDCls.getDeclaredField("d");
        final Field rewDNodesField = rewDCls.getDeclaredField("d");
        origDNodesField.setAccessible(true);
        rewDNodesField.setAccessible(true);
        final List<?> origNodes = (List<?>) origDNodesField.get(origD1);
        final List<?> rewNodes = (List<?>) rewDNodesField.get(rewD1);

        final Method origCoonsEval =
                origFCls.getDeclaredMethod("a", origCCls, origCCls, origCCls, origCCls, float.class, float.class);
        final Method rewCoonsEval =
                rewFCls.getDeclaredMethod("a", rewCCls, rewCCls, rewCCls, rewCCls, float.class, float.class);
        for (final float[] uv : new float[][] {{0.0f, 0.0f}, {1.0f, 1.0f}, {0.37f, 0.61f}, {0.82f, 0.19f}}) {
            final Object origCoons = origCoonsEval.invoke(
                    null, origNodes.get(0), origNodes.get(3), origNodes.get(1), origNodes.get(4), uv[0], uv[1]);
            final Object rewCoons = rewCoonsEval.invoke(
                    null, rewNodes.get(0), rewNodes.get(3), rewNodes.get(1), rewNodes.get(4), uv[0], uv[1]);
            assertVectorRawBitParity(origCoons, rewCoons, "extension coons eval (" + uv[0] + "," + uv[1] + ")");
        }

        final Method origCoonsInvert = origFCls.getDeclaredMethod(
                "a", origCCls, origCCls, origCCls, origCCls, origVecCls, float.class, float.class);
        final Method rewCoonsInvert =
                rewFCls.getDeclaredMethod("a", rewCCls, rewCCls, rewCCls, rewCCls, rewVecCls, float.class, float.class);
        final Constructor<?> origVecCtor = origVecCls.getConstructor(float.class, float.class);
        final Constructor<?> rewVecCtor = rewVecCls.getConstructor(float.class, float.class);
        for (final float[] tgt : new float[][] {{45.0f, 35.0f}, {260.0f, 195.0f}}) {
            final Object origPair = origCoonsInvert.invoke(
                    null,
                    origNodes.get(0),
                    origNodes.get(3),
                    origNodes.get(1),
                    origNodes.get(4),
                    origVecCtor.newInstance(tgt[0], tgt[1]),
                    0.45f,
                    0.55f);
            final Object rewPair = rewCoonsInvert.invoke(
                    null,
                    rewNodes.get(0),
                    rewNodes.get(3),
                    rewNodes.get(1),
                    rewNodes.get(4),
                    rewVecCtor.newInstance(tgt[0], tgt[1]),
                    0.45f,
                    0.55f);
            final Method origPairGetK = origPair.getClass().getMethod("a");
            final Method rewPairGetK = rewPair.getClass().getMethod("a");
            final Method origPairGetVec = origPair.getClass().getMethod("b");
            final Method rewPairGetVec = rewPair.getClass().getMethod("b");
            origPairGetK.setAccessible(true);
            rewPairGetK.setAccessible(true);
            origPairGetVec.setAccessible(true);
            rewPairGetVec.setAccessible(true);
            final Object origK = origPairGetK.invoke(origPair);
            final Object rewK = rewPairGetK.invoke(rewPair);
            assertEquals(
                    Float.floatToRawIntBits(
                            (float) origK.getClass().getMethod("a").invoke(origK)),
                    Float.floatToRawIntBits(
                            (float) rewK.getClass().getMethod("a").invoke(rewK)),
                    "extension coons invert u bit mismatch for (" + tgt[0] + "," + tgt[1] + ")");
            assertEquals(
                    Float.floatToRawIntBits(
                            (float) origK.getClass().getMethod("b").invoke(origK)),
                    Float.floatToRawIntBits(
                            (float) rewK.getClass().getMethod("b").invoke(rewK)),
                    "extension coons invert v bit mismatch for (" + tgt[0] + "," + tgt[1] + ")");
            assertVectorRawBitParity(
                    origPairGetVec.invoke(origPair), rewPairGetVec.invoke(rewPair), "extension coons invert residual");
        }

        final Method origBicubic =
                origFCls.getDeclaredMethod("a", float[].class, int.class, int.class, float.class, float.class);
        final Method rewBicubic =
                rewFCls.getDeclaredMethod("a", float[].class, int.class, int.class, float.class, float.class);
        final float[] bicubicPts = new float[5 * 4 * 2];
        for (int r = 0; r <= 3; r++) {
            for (int c = 0; c <= 4; c++) {
                final int idx = (r * 5 + c) * 2;
                bicubicPts[idx] = c * 30.0f + r * 4.5f;
                bicubicPts[idx + 1] = r * 25.0f - c * 2.25f;
            }
        }
        for (final float[] uv : new float[][] {{0.0f, 0.0f}, {1.0f, 1.0f}, {0.31f, 0.67f}, {-0.25f, 1.35f}}) {
            final Object origVec = origBicubic.invoke(null, bicubicPts, 4, 3, uv[0], uv[1]);
            final Object rewVec = rewBicubic.invoke(null, bicubicPts, 4, 3, uv[0], uv[1]);
            assertVectorRawBitParity(origVec, rewVec, "extension bicubic (" + uv[0] + "," + uv[1] + ")");
        }

        final Map<String, Long> afterStats = bridge.snapshot();
        assertTrue(afterStats.get("extBezierDragCalls") > beforeStats.get("extBezierDragCalls"));
        assertTrue(afterStats.get("extCoonsEvalCalls") > beforeStats.get("extCoonsEvalCalls"));
        assertTrue(afterStats.get("extCoonsInvertCalls") > beforeStats.get("extCoonsInvertCalls"));
        assertTrue(afterStats.get("extBicubicEvalCalls") > beforeStats.get("extBicubicEvalCalls"));
    }

    private static Object buildSampleBezierGrid(final ClassLoader loader, final float shiftX, final float shiftY)
            throws Exception {
        final Class<?> bgCls = Class.forName(WarpDeformEditTarget.BEZIER_GRID_OWNER.replace('/', '.'), true, loader);
        final Class<?> bpCls = Class.forName(WarpDeformEditTarget.BEZIER_PT_OWNER.replace('/', '.'), true, loader);
        final Class<?> vCls = Class.forName(WarpDeformEditTarget.GVECTOR2_OWNER.replace('/', '.'), true, loader);
        final Constructor<?> vCtor = vCls.getConstructor(float.class, float.class);
        final Object bg = bgCls.getConstructor(int.class, int.class).newInstance(2, 2);
        final Object[][] refs = (Object[][]) bgCls.getMethod("getBezierPtRef").invoke(bg);
        for (int ix = 0; ix <= 2; ix++) {
            for (int iy = 0; iy <= 2; iy++) {
                final float dx = (ix == 1 && iy == 1) ? shiftX : shiftX * 0.25f;
                final float dy = (ix == 1 && iy == 1) ? shiftY : shiftY * 0.25f;
                final float ax = ix * 100f + (iy == 1 ? 15f : 0f) + dx;
                final float ay = iy * 80f + (ix == 1 ? -12f : 0f) + dy;
                refs[ix][iy] = bpCls.getConstructor(vCls, vCls, vCls, vCls, vCls)
                        .newInstance(
                                vCtor.newInstance(ax, ay),
                                vCtor.newInstance(ax + 4f, ay - 26f),
                                vCtor.newInstance(ax - 3f, ay + 26f),
                                vCtor.newInstance(ax - 32f, ay + 5f),
                                vCtor.newInstance(ax + 32f, ay - 4f));
            }
        }
        return bg;
    }

    private static void assertVectorRawBitParity(final Object expected, final Object actual, final String context)
            throws Exception {
        final float ex = (float) expected.getClass().getMethod("getX").invoke(expected);
        final float ey = (float) expected.getClass().getMethod("getY").invoke(expected);
        final float ax = (float) actual.getClass().getMethod("getX").invoke(actual);
        final float ay = (float) actual.getClass().getMethod("getY").invoke(actual);
        assertEquals(Float.floatToRawIntBits(ex), Float.floatToRawIntBits(ax), context + " X bit mismatch");
        assertEquals(Float.floatToRawIntBits(ey), Float.floatToRawIntBits(ay), context + " Y bit mismatch");
    }

    private static void runLocalMeshEpochFastSkipAndMutationParity(
            final ClassLoader origLoader,
            final ClassLoader rewLoader,
            final WarpDeformEditTarget target,
            final WarpDeformEditBridge bridge)
            throws Exception {
        final SyntheticModelHarness orig = buildSyntheticHierarchy(origLoader, target);
        final SyntheticModelHarness rew = buildSyntheticHierarchy(rewLoader, target);
        orig.invokeUpdate();
        rew.invokeUpdate();
        assertAllMeshesRawFloatParity(orig, rew, "local-mesh-epoch-baseline");

        final float[] dragged = rew.baseRootGridPositions.get(0).clone();
        dragged[0] += 4.0f;
        dragWarpDeformer(orig.rootWarp, dragged.clone());
        dragWarpDeformer(rew.rootWarp, dragged.clone());
        orig.invokeUpdate();
        rew.invokeUpdate();
        assertAllMeshesRawFloatParity(orig, rew, "local-mesh-epoch-warm");

        // Consecutive parent-deformer drag ticks must keep fastModelUpdates advancing with zero fullArrayHashes growth.
        for (int tick = 1; tick <= 3; tick++) {
            dragged[0] += 1.5f;
            dragged[1] -= 0.75f;
            final Map<String, Long> before = bridge.snapshot();
            dragWarpDeformer(orig.rootWarp, dragged.clone());
            dragWarpDeformer(rew.rootWarp, dragged.clone());
            orig.invokeUpdate();
            rew.invokeUpdate();
            final Map<String, Long> after = bridge.snapshot();
            assertEquals(
                    before.get("fastModelUpdates") + 1L,
                    after.get("fastModelUpdates"),
                    "tick " + tick + " must take fastModelUpdates");
            assertEquals(
                    before.get("fullArrayHashes"),
                    after.get("fullArrayHashes"),
                    "tick " + tick + " must fast-skip full array hashing");
            assertAllMeshesRawFloatParity(orig, rew, "local-mesh-epoch-drag-tick-" + tick);
        }

        // Mutate a sibling ArtMesh form directly via setPositions() without setUpdated() -> epoch must detect change.
        final Object origSibling = orig.subtreeBMeshes.get(0);
        final Object rewSibling = rew.subtreeBMeshes.get(0);
        final Object origSiblingSrc =
                origSibling.getClass().getMethod("getSource").invoke(origSibling);
        final Object rewSiblingSrc =
                rewSibling.getClass().getMethod("getSource").invoke(rewSibling);
        final Object origMeshForm =
                ((List<?>) origSiblingSrc.getClass().getMethod("getKeyforms").invoke(origSiblingSrc)).get(0);
        final Object rewMeshForm =
                ((List<?>) rewSiblingSrc.getClass().getMethod("getKeyforms").invoke(rewSiblingSrc)).get(0);
        final float[] editedMeshPos =
                ((float[]) rewMeshForm.getClass().getMethod("getPositions").invoke(rewMeshForm)).clone();
        editedMeshPos[0] += 9.5f;
        editedMeshPos[1] -= 5.25f;
        origMeshForm.getClass().getMethod("setPositions", float[].class).invoke(origMeshForm, editedMeshPos.clone());
        rewMeshForm.getClass().getMethod("setPositions", float[].class).invoke(rewMeshForm, editedMeshPos.clone());
        orig.invokeUpdate();
        rew.invokeUpdate();
        assertAllMeshesRawFloatParity(orig, rew, "direct-mesh-form-setPositions-without-setUpdated");

        // Mutate draw order directly on ACDrawableForm -> must propagate during warm drag.
        origMeshForm.getClass().getMethod("setDrawOrder", int.class).invoke(origMeshForm, 77);
        rewMeshForm.getClass().getMethod("setDrawOrder", int.class).invoke(rewMeshForm, 77);
        orig.invokeUpdate();
        rew.invokeUpdate();
        assertEquals(
                origSibling.getClass().getMethod("getDrawOrder").invoke(origSibling),
                rewSibling.getClass().getMethod("getDrawOrder").invoke(rewSibling),
                "drawOrder mismatch after direct ACDrawableForm.setDrawOrder");
        assertAllMeshesRawFloatParity(orig, rew, "direct-draw-order-mutation");

        // Toggle visibility directly on CArtMeshSource -> must detect visibility change & restoration.
        origSiblingSrc.getClass().getMethod("setVisible", boolean.class).invoke(origSiblingSrc, false);
        rewSiblingSrc.getClass().getMethod("setVisible", boolean.class).invoke(rewSiblingSrc, false);
        orig.invokeUpdate();
        rew.invokeUpdate();
        assertEquals(
                origSibling.getClass().getMethod("isInvisible").invoke(origSibling),
                rewSibling.getClass().getMethod("isInvisible").invoke(rewSibling),
                "isInvisible mismatch when mesh source hidden");
        assertAllMeshesRawFloatParity(orig, rew, "direct-mesh-source-hide");

        origSiblingSrc.getClass().getMethod("setVisible", boolean.class).invoke(origSiblingSrc, true);
        rewSiblingSrc.getClass().getMethod("setVisible", boolean.class).invoke(rewSiblingSrc, true);
        orig.invokeUpdate();
        rew.invokeUpdate();
        assertAllMeshesRawFloatParity(orig, rew, "direct-mesh-source-unhide");

        // Simulate Undo/Redo keyform replacement via CWarpDeformerSource.setKeyforms().
        final List<?> origKfs =
                (List<?>) orig.rootWarpSrc.getClass().getMethod("getKeyforms").invoke(orig.rootWarpSrc);
        final List<?> rewKfs =
                (List<?>) rew.rootWarpSrc.getClass().getMethod("getKeyforms").invoke(rew.rootWarpSrc);
        final float[] revertedPos = orig.baseRootGridPositions.get(0).clone();
        revertedPos[0] -= 3.0f;
        origKfs.get(0).getClass().getMethod("setPositions", float[].class).invoke(origKfs.get(0), revertedPos.clone());
        rewKfs.get(0).getClass().getMethod("setPositions", float[].class).invoke(rewKfs.get(0), revertedPos.clone());
        final Class<?> origCListCls = Class.forName("com.live2d.type.CArrayList", true, origLoader);
        final Class<?> rewCListCls = Class.forName("com.live2d.type.CArrayList", true, rewLoader);
        orig.rootWarpSrc
                .getClass()
                .getMethod("setKeyforms", origCListCls)
                .invoke(
                        orig.rootWarpSrc,
                        origCListCls.getConstructor(Collection.class).newInstance(origKfs));
        rew.rootWarpSrc
                .getClass()
                .getMethod("setKeyforms", rewCListCls)
                .invoke(
                        rew.rootWarpSrc,
                        rewCListCls.getConstructor(Collection.class).newInstance(rewKfs));
        orig.invokeUpdate();
        rew.invokeUpdate();
        assertAllMeshesRawFloatParity(orig, rew, "undo-redo-warp-setKeyforms");
    }

    private static void verifyKeyformParityStandaloneJar(final Path jar) throws Throwable {
        final HostArtifactDigest digest = HostArtifactDigest.cached(jar);
        final WarpDeformEditTarget target = WarpDeformEditTarget.of(digest).orElseThrow();
        final Map<String, byte[]> refs = readTargetBytes(jar, target.targetClasses());
        final ProtectionDomain domain = protectionDomain(jar);
        final URL[] urls = hostUrls(jar);
        final Map<String, byte[]> overrides = new LinkedHashMap<>();
        try (RewrittenHostClassLoader txLoader =
                new RewrittenHostClassLoader(jar, urls, WarpDeformEditAccelerationTest.class.getClassLoader())) {
            final WarpDeformEditTransformer tx = new WarpDeformEditTransformer(txLoader, jar, refs, target);
            for (final String owner : target.targetClasses()) {
                final byte[] next = tx.transform(null, txLoader, owner, null, domain, refs.get(owner));
                assertNotNull(next, tx::failure);
                overrides.put(owner.replace('/', '.'), next);
            }
            assertEquals(tx.expectedTotalSites(), tx.sites(), target.version());
        }
        final String prevEditProp = System.getProperty(WarpDeformEditBridge.ENABLE_PROPERTY);
        System.setProperty(WarpDeformEditBridge.ENABLE_PROPERTY, "true");
        try (RewrittenHostClassLoader origLoader =
                        new RewrittenHostClassLoader(jar, urls, WarpDeformEditAccelerationTest.class.getClassLoader());
                RewrittenHostClassLoader rewLoader = new RewrittenHostClassLoader(
                        jar, urls, WarpDeformEditAccelerationTest.class.getClassLoader())) {
            rewLoader.installOverrides(overrides, domain);
            for (final String owner : overrides.keySet()) {
                Class.forName(owner, false, rewLoader);
            }
            try (WarpDeformEditBridge bridge = new WarpDeformEditBridge(rewLoader)) {
                bridge.install();
                runKeyformGridAndBindingParity(origLoader, rewLoader, bridge);
            }
        } finally {
            if (prevEditProp == null) {
                System.clearProperty(WarpDeformEditBridge.ENABLE_PROPERTY);
            } else {
                System.setProperty(WarpDeformEditBridge.ENABLE_PROPERTY, prevEditProp);
            }
        }
    }

    private static void runKeyformGridAndBindingParity(
            final ClassLoader origLoader, final ClassLoader rewLoader, final WarpDeformEditBridge bridge)
            throws Exception {
        final Object[] origEnv = buildKeyformParityHarness(origLoader);
        final Object[] rewEnv = buildKeyformParityHarness(rewLoader);

        final Object origGrid = origEnv[0];
        final Object rewGrid = rewEnv[0];
        final Object origParam1Src = origEnv[1];
        final Object rewParam1Src = rewEnv[1];
        final Object origParam1Guid = origEnv[2];
        final Object rewParam1Guid = rewEnv[2];
        final Object origParamSet = origEnv[3];
        final Object rewParamSet = rewEnv[3];
        final Object origParam1 = origEnv[4];
        final Object rewParam1 = rewEnv[4];
        final Object origParam2 = origEnv[5];
        final Object rewParam2 = rewEnv[5];
        final Object origMissingSet = origEnv[6];
        final Object rewMissingSet = rewEnv[6];

        final Class<?> origSearchCls =
                Class.forName(WarpDeformEditTarget.PARAMETER_KEY_SEARCH_TYPE_OWNER.replace('/', '.'), true, origLoader);
        final Class<?> rewSearchCls =
                Class.forName(WarpDeformEditTarget.PARAMETER_KEY_SEARCH_TYPE_OWNER.replace('/', '.'), true, rewLoader);
        final Class<?> origParamSetCls =
                Class.forName("com.live2d.cubism.doc.model.param.CParameterSet", false, origLoader);
        final Class<?> rewParamSetCls =
                Class.forName("com.live2d.cubism.doc.model.param.CParameterSet", false, rewLoader);

        final Method origGetBindingsM = origGrid.getClass().getMethod("getKeyformBindings");
        final Method rewGetBindingsM = rewGrid.getClass().getMethod("getKeyformBindings");
        final Method origFindDefaultM = origGrid.getClass().getMethod("findKeyform", origParamSetCls);
        final Method rewFindDefaultM = rewGrid.getClass().getMethod("findKeyform", rewParamSetCls);
        final Method origFindSearchM = origGrid.getClass().getMethod("findKeyform", origParamSetCls, origSearchCls);
        final Method rewFindSearchM = rewGrid.getClass().getMethod("findKeyform", rewParamSetCls, rewSearchCls);

        final long indicesBefore = bridge.snapshot().get("keyIndicesInRangeFastCalls");
        final long findBefore = bridge.snapshot().get("findKeyformFastCalls");

        assertBindingIndicesParity(origGetBindingsM, rewGetBindingsM, origGrid, rewGrid, "initial");

        final float[] p1Samples = {
            -50.0f, -40.0f, -20.0f, -10.0f, -5.0f, -0.0005f, -0.0f, +0.0f, 0.0005f, 5.0f, 10.0f, 20.0f, 35.0f, 50.0f
        };
        final float[] p2Samples = {-1.5f, -1.0f, -0.5f, 0.0f, 0.5f, 1.0f};
        assertKeyformSweepParity(
                origGrid,
                rewGrid,
                origParam1,
                rewParam1,
                origParam2,
                rewParam2,
                origParamSet,
                rewParamSet,
                origFindDefaultM,
                rewFindDefaultM,
                origFindSearchM,
                rewFindSearchM,
                origSearchCls,
                rewSearchCls,
                p1Samples,
                p2Samples,
                "non-repeat");

        // Missing parameter set must return identically (null) without throwing.
        assertKeyformResultParity(
                origGrid,
                rewGrid,
                () -> origFindDefaultM.invoke(origGrid, origMissingSet),
                () -> rewFindDefaultM.invoke(rewGrid, rewMissingSet),
                "missing-param-default");

        // Repeat wrapping & tie-breaking check (negative loop & positive overshoot).
        origParam1Src.getClass().getMethod("setRepeat", boolean.class).invoke(origParam1Src, true);
        rewParam1Src.getClass().getMethod("setRepeat", boolean.class).invoke(rewParam1Src, true);
        final float[] repeatSamples = {-60.0f, -35.0f, -25.0f, -20.0f, -15.0f, -0.0f, 20.0f, 25.0f, 45.0f};
        assertKeyformSweepParity(
                origGrid,
                rewGrid,
                origParam1,
                rewParam1,
                origParam2,
                rewParam2,
                origParamSet,
                rewParamSet,
                origFindDefaultM,
                rewFindDefaultM,
                origFindSearchM,
                rewFindSearchM,
                origSearchCls,
                rewSearchCls,
                repeatSamples,
                new float[] {-1.0f, 0.0f},
                "repeat-on");
        origParam1Src.getClass().getMethod("setRepeat", boolean.class).invoke(origParam1Src, false);
        rewParam1Src.getClass().getMethod("setRepeat", boolean.class).invoke(rewParam1Src, false);

        // Mutate keys via addKey, removeKey, reverseKeyformsOnParameter, direct list set(), setKeys(), and clear().
        origGrid.getClass()
                .getMethod("addKey", float.class, origParam1Guid.getClass())
                .invoke(origGrid, 15.0f, origParam1Guid);
        rewGrid.getClass()
                .getMethod("addKey", float.class, rewParam1Guid.getClass())
                .invoke(rewGrid, 15.0f, rewParam1Guid);
        assertBindingIndicesParity(origGetBindingsM, rewGetBindingsM, origGrid, rewGrid, "after-addKey");
        assertKeyformSweepParity(
                origGrid,
                rewGrid,
                origParam1,
                rewParam1,
                origParam2,
                rewParam2,
                origParamSet,
                rewParamSet,
                origFindDefaultM,
                rewFindDefaultM,
                origFindSearchM,
                rewFindSearchM,
                origSearchCls,
                rewSearchCls,
                new float[] {10.0f, 15.0f, 17.5f},
                new float[] {0.0f},
                "after-addKey");

        origGrid.getClass()
                .getMethod("removeKey", float.class, origParam1Guid.getClass())
                .invoke(origGrid, 15.0f, origParam1Guid);
        rewGrid.getClass()
                .getMethod("removeKey", float.class, rewParam1Guid.getClass())
                .invoke(rewGrid, 15.0f, rewParam1Guid);
        assertBindingIndicesParity(origGetBindingsM, rewGetBindingsM, origGrid, rewGrid, "after-removeKey");

        // Mutate binding.getKeys() content in place without touching gridSize -> must invalidate zero-stale hashes!
        @SuppressWarnings("unchecked")
        final List<Float> origKeys = (List<Float>) ((List<?>) origGetBindingsM.invoke(origGrid))
                .get(0)
                .getClass()
                .getMethod("getKeys")
                .invoke(((List<?>) origGetBindingsM.invoke(origGrid)).get(0));
        @SuppressWarnings("unchecked")
        final List<Float> rewKeys = (List<Float>) ((List<?>) rewGetBindingsM.invoke(rewGrid))
                .get(0)
                .getClass()
                .getMethod("getKeys")
                .invoke(((List<?>) rewGetBindingsM.invoke(rewGrid)).get(0));
        origKeys.set(1, -12.0f);
        rewKeys.set(1, -12.0f);
        assertBindingIndicesParity(origGetBindingsM, rewGetBindingsM, origGrid, rewGrid, "after-in-place-set");
        assertKeyformSweepParity(
                origGrid,
                rewGrid,
                origParam1,
                rewParam1,
                origParam2,
                rewParam2,
                origParamSet,
                rewParamSet,
                origFindDefaultM,
                rewFindDefaultM,
                origFindSearchM,
                rewFindSearchM,
                origSearchCls,
                rewSearchCls,
                new float[] {-12.0f, -10.0f, -6.0f},
                new float[] {0.0f},
                "after-in-place-set");

        // Test reverseKeyformsOnParameter & empty keys exception/null parity.
        origGrid.getClass()
                .getMethod("reverseKeyformsOnParameter", origParam1Guid.getClass())
                .invoke(origGrid, origParam1Guid);
        rewGrid.getClass()
                .getMethod("reverseKeyformsOnParameter", rewParam1Guid.getClass())
                .invoke(rewGrid, rewParam1Guid);
        assertBindingIndicesParity(origGetBindingsM, rewGetBindingsM, origGrid, rewGrid, "after-reverse");

        final Object origBinding0 = ((List<?>) origGetBindingsM.invoke(origGrid)).get(0);
        final Object rewBinding0 = ((List<?>) rewGetBindingsM.invoke(rewGrid)).get(0);
        origBinding0.getClass().getMethod("setKeys", List.class).invoke(origBinding0, new ArrayList<Float>());
        rewBinding0.getClass().getMethod("setKeys", List.class).invoke(rewBinding0, new ArrayList<Float>());
        assertBindingIndicesParity(origGetBindingsM, rewGetBindingsM, origGrid, rewGrid, "after-empty-setKeys");
        assertKeyformSweepParity(
                origGrid,
                rewGrid,
                origParam1,
                rewParam1,
                origParam2,
                rewParam2,
                origParamSet,
                rewParamSet,
                origFindDefaultM,
                rewFindDefaultM,
                origFindSearchM,
                rewFindSearchM,
                origSearchCls,
                rewSearchCls,
                new float[] {0.0f},
                new float[] {0.0f},
                "empty-keys");

        final Map<String, Long> stats = bridge.snapshot();
        final String keyformDiag = " hasKeyformOps="
                + bridge.hasKeyformOps()
                + " findSlot="
                + (System.getProperties().get(WarpDeformEditBridge.KEYFORM_FIND_PROPERTY) != null)
                + " activeSlot="
                + System.getProperties().get(WarpDeformEditBridge.ACTIVE_PROPERTY);
        assertTrue(
                stats.get("keyIndicesInRangeFastCalls") > indicesBefore,
                () -> "expected keyIndicesInRangeFastCalls > 0, stats=" + stats + keyformDiag);
        assertTrue(
                stats.get("findKeyformFastCalls") > findBefore,
                () -> "expected findKeyformFastCalls > 0, stats=" + stats + keyformDiag);
        assertEquals(0L, stats.get("failures"), () -> "stats=" + stats);
    }

    private static Object[] buildKeyformParityHarness(final ClassLoader loader) throws Exception {
        final Class<?> modelSrcCls = Class.forName("com.live2d.cubism.doc.model.CModelSource", true, loader);
        final Class<?> modelCls = Class.forName("com.live2d.cubism.doc.model.CModel", true, loader);
        final Class<?> paramSrcCls = Class.forName("com.live2d.cubism.doc.model.param.CParameterSource", true, loader);
        final Class<?> paramGuidCls = Class.forName("com.live2d.type.CParameterGuid", true, loader);
        final Class<?> paramIdCls = Class.forName("com.live2d.cubism.doc.model.id.CParameterId", true, loader);
        final Class<?> defGuidCls = Class.forName("com.live2d.type.CDeformerGuid", true, loader);
        final Class<?> defIdCls = Class.forName("com.live2d.cubism.doc.model.id.CDeformerId", true, loader);
        final Class<?> formGuidCls = Class.forName("com.live2d.type.CFormGuid", true, loader);
        final Class<?> coordCls = Class.forName("com.live2d.doc.CoordType", true, loader);
        final Class<?> warpSrcCls =
                Class.forName(WarpDeformEditTarget.WARP_SOURCE_OWNER.replace('/', '.'), true, loader);
        final Class<?> warpFormCls =
                Class.forName(WarpDeformEditTarget.WARP_FORM_OWNER.replace('/', '.'), true, loader);
        final Class<?> acDefSrcCls =
                Class.forName("com.live2d.cubism.doc.model.deformer.ACDeformerSource", true, loader);
        final Class<?> acCtrlSrcCls =
                Class.forName("com.live2d.cubism.doc.model.ACParameterControllableSource", true, loader);
        final Class<?> renderTypeCls = Class.forName("com.live2d.graphics3d.rendering.RenderType", true, loader);

        final Object coordLocal = coordCls.getMethod("b").invoke(null);
        final Object modelSource = modelSrcCls.getConstructor().newInstance();
        final Object emptyModelSource = modelSrcCls.getConstructor().newInstance();
        final Object rootPart = modelSrcCls.getMethod("getRootPart").invoke(modelSource);
        final Object defSet = modelSrcCls.getMethod("getDeformerSourceSet").invoke(modelSource);
        final Object paramSetSrc =
                modelSrcCls.getMethod("getParameterSourceSet").invoke(modelSource);

        final Object p1Guid =
                paramGuidCls.getConstructor(UUID.class).newInstance(UUID.nameUUIDFromBytes("kfP1".getBytes()));
        final Object p1Src = paramSrcCls.getConstructor().newInstance();
        paramSrcCls.getMethod("setGuid", paramGuidCls).invoke(p1Src, p1Guid);
        paramSrcCls
                .getMethod("setId", paramIdCls)
                .invoke(p1Src, paramIdCls.getConstructor(String.class).newInstance("KfP1"));
        paramSrcCls.getMethod("setMinValue", float.class).invoke(p1Src, -50.0f);
        paramSrcCls.getMethod("setMaxValue", float.class).invoke(p1Src, 50.0f);
        paramSrcCls.getMethod("setDefaultValue", float.class).invoke(p1Src, 0.0f);
        paramSetSrc.getClass().getMethod("add", paramSrcCls, int.class).invoke(paramSetSrc, p1Src, -1);

        final Object p2Guid =
                paramGuidCls.getConstructor(UUID.class).newInstance(UUID.nameUUIDFromBytes("kfP2".getBytes()));
        final Object p2Src = paramSrcCls.getConstructor().newInstance();
        paramSrcCls.getMethod("setGuid", paramGuidCls).invoke(p2Src, p2Guid);
        paramSrcCls
                .getMethod("setId", paramIdCls)
                .invoke(p2Src, paramIdCls.getConstructor(String.class).newInstance("KfP2"));
        paramSrcCls.getMethod("setMinValue", float.class).invoke(p2Src, -1.0f);
        paramSrcCls.getMethod("setMaxValue", float.class).invoke(p2Src, 1.0f);
        paramSrcCls.getMethod("setDefaultValue", float.class).invoke(p2Src, 0.0f);
        paramSetSrc.getClass().getMethod("add", paramSrcCls, int.class).invoke(paramSetSrc, p2Src, -1);

        final float[] gridPos = new float[18];
        final Object wGuid =
                defGuidCls.getConstructor(UUID.class).newInstance(UUID.nameUUIDFromBytes("kfWarp".getBytes()));
        final Object warpSrc = createWarpDeformerSource(
                modelSrcCls,
                modelSource,
                warpSrcCls,
                warpFormCls,
                defGuidCls,
                defIdCls,
                formGuidCls,
                coordCls,
                wGuid,
                "KfWarp",
                null,
                coordLocal,
                gridPos);
        final Object grid = warpSrcCls.getMethod("getKeyformGridSource").invoke(warpSrc);
        final Method addKeyM = grid.getClass().getMethod("addKey", float.class, paramGuidCls);
        addKeyM.invoke(grid, -40.0f, p1Guid);
        addKeyM.invoke(grid, -10.0f, p1Guid);
        addKeyM.invoke(grid, -0.0f, p1Guid);
        addKeyM.invoke(grid, 10.0f, p1Guid);
        addKeyM.invoke(grid, 40.0f, p1Guid);
        addKeyM.invoke(grid, -1.0f, p2Guid);
        addKeyM.invoke(grid, 0.0f, p2Guid);
        addKeyM.invoke(grid, 1.0f, p2Guid);

        // Tighten p1 range to [-20, 20] so -40/+40 exercise getKeyIndicesInRange() out-of-range exclusion.
        paramSrcCls.getMethod("setMinValue", float.class).invoke(p1Src, -20.0f);
        paramSrcCls.getMethod("setMaxValue", float.class).invoke(p1Src, 20.0f);

        defSet.getClass().getMethod("add", acDefSrcCls, int.class).invoke(defSet, warpSrc, -1);
        rootPart.getClass().getMethod("addChild", acCtrlSrcCls, int.class).invoke(rootPart, warpSrc, -1);
        rootPart.getClass().getMethod("setVisibleAndLockInHierarchy").invoke(rootPart);

        final Object renderNormal = Enum.valueOf((Class) renderTypeCls, "NORMAL");
        final Object model =
                modelSrcCls.getMethod("createModelInstance", renderTypeCls).invoke(modelSource, renderNormal);
        final Object emptyModel =
                modelSrcCls.getMethod("createModelInstance", renderTypeCls).invoke(emptyModelSource, renderNormal);
        final Object paramSet = modelCls.getMethod("getParameterSet").invoke(model);
        final Object missingParamSet = modelCls.getMethod("getParameterSet").invoke(emptyModel);
        final List<?> params =
                (List<?>) paramSet.getClass().getMethod("getParameters").invoke(paramSet);
        return new Object[] {grid, p1Src, p1Guid, paramSet, params.get(0), params.get(1), missingParamSet};
    }

    private static void assertBindingIndicesParity(
            final Method origGetBindingsM,
            final Method rewGetBindingsM,
            final Object origGrid,
            final Object rewGrid,
            final String phase)
            throws Exception {
        final List<?> origBindings = (List<?>) origGetBindingsM.invoke(origGrid);
        final List<?> rewBindings = (List<?>) rewGetBindingsM.invoke(rewGrid);
        assertEquals(origBindings.size(), rewBindings.size(), phase);
        for (int i = 0; i < origBindings.size(); i++) {
            final Method origM = origBindings.get(i).getClass().getMethod("getKeyIndicesInRange");
            final Method rewM = rewBindings.get(i).getClass().getMethod("getKeyIndicesInRange");
            assertEquals(origM.invoke(origBindings.get(i)), rewM.invoke(rewBindings.get(i)), phase + " binding#" + i);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void assertKeyformSweepParity(
            final Object origGrid,
            final Object rewGrid,
            final Object origP1,
            final Object rewP1,
            final Object origP2,
            final Object rewP2,
            final Object origParamSet,
            final Object rewParamSet,
            final Method origFindDefaultM,
            final Method rewFindDefaultM,
            final Method origFindSearchM,
            final Method rewFindSearchM,
            final Class<?> origSearchCls,
            final Class<?> rewSearchCls,
            final float[] p1Values,
            final float[] p2Values,
            final String phase)
            throws Exception {
        final Method origSetVal = origP1.getClass().getMethod("setValue", float.class);
        final Method rewSetVal = rewP1.getClass().getMethod("setValue", float.class);
        for (final float v1 : p1Values) {
            origSetVal.invoke(origP1, v1);
            rewSetVal.invoke(rewP1, v1);
            for (final float v2 : p2Values) {
                origSetVal.invoke(origP2, v2);
                rewSetVal.invoke(rewP2, v2);
                final String label = phase + " p1=" + v1 + " p2=" + v2;
                assertKeyformResultParity(
                        origGrid,
                        rewGrid,
                        () -> origFindDefaultM.invoke(origGrid, origParamSet),
                        () -> rewFindDefaultM.invoke(rewGrid, rewParamSet),
                        label + " default");
                for (final String mode : new String[] {"EQUAL", "LESS_OR_EQUAL", "NEAREST"}) {
                    final Object origEnum = Enum.valueOf((Class) origSearchCls, mode);
                    final Object rewEnum = Enum.valueOf((Class) rewSearchCls, mode);
                    assertKeyformResultParity(
                            origGrid,
                            rewGrid,
                            () -> origFindSearchM.invoke(origGrid, origParamSet, origEnum),
                            () -> rewFindSearchM.invoke(rewGrid, rewParamSet, rewEnum),
                            label + " " + mode);
                }
            }
        }
    }

    @FunctionalInterface
    private interface HostCall {
        Object invoke() throws Exception;
    }

    private static void assertKeyformResultParity(
            final Object origGrid,
            final Object rewGrid,
            final HostCall origCall,
            final HostCall rewCall,
            final String label)
            throws Exception {
        Object origRes = null;
        Throwable origErr = null;
        try {
            origRes = origCall.invoke();
        } catch (final java.lang.reflect.InvocationTargetException ite) {
            origErr = ite.getCause();
        }
        Object rewRes = null;
        Throwable rewErr = null;
        try {
            rewRes = rewCall.invoke();
        } catch (final java.lang.reflect.InvocationTargetException ite) {
            rewErr = ite.getCause();
        }
        if (origErr != null || rewErr != null) {
            final Throwable finalOrigErr = origErr;
            final Throwable finalRewErr = rewErr;
            final Object finalRewRes = rewRes;
            assertNotNull(origErr, () -> label + " unexpected rew exception " + finalRewErr);
            assertNotNull(rewErr, () -> label + " expected exception " + finalOrigErr + " but got " + finalRewRes);
            assertEquals(origErr.getClass().getName(), rewErr.getClass().getName(), label);
            return;
        }
        final List<?> origGridList =
                (List<?>) origGrid.getClass().getMethod("getKeyformsOnGrid").invoke(origGrid);
        final List<?> rewGridList =
                (List<?>) rewGrid.getClass().getMethod("getKeyformsOnGrid").invoke(rewGrid);
        final int origIdx = origRes == null ? -1 : identityIndexOf(origGridList, origRes);
        final int rewIdx = rewRes == null ? -1 : identityIndexOf(rewGridList, rewRes);
        assertEquals(origIdx, rewIdx, label);
    }

    private static int identityIndexOf(final List<?> list, final Object item) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == item) {
                return i;
            }
        }
        return -1;
    }

    /** Child entrypoint verifying all rewritten host classes across all JARs under {@code -Xverify:all}. */
    public static final class StrictWarpDeformVerifyChild {

        private StrictWarpDeformVerifyChild() {}

        /** Loads and links every patched class listed in the manifest under strict bytecode verification. */
        public static void main(final String[] args) throws Exception {
            final List<String> lines = Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8);
            final int expected = Integer.parseInt(args[1]);
            final Map<String, Map<String, byte[]>> byTag = new LinkedHashMap<>();
            final Map<String, Path> jarByTag = new LinkedHashMap<>();
            int readCount = 0;
            for (final String line : lines) {
                if (line.isBlank()) {
                    continue;
                }
                final String[] parts = line.split("\t");
                final String tag = parts[0];
                final Path jar = Path.of(parts[1]);
                final Path patchRoot = Path.of(parts[2]);
                final String internalName = parts[3];
                jarByTag.putIfAbsent(tag, jar);
                byTag.computeIfAbsent(tag, ignored -> new LinkedHashMap<>())
                        .put(
                                internalName.replace('/', '.'),
                                Files.readAllBytes(patchRoot.resolve(internalName + CLASS_EXTENSION)));
                readCount++;
            }
            if (readCount != expected) {
                throw new AssertionError("expected " + expected + " rows but read " + readCount);
            }
            for (final Map.Entry<String, Map<String, byte[]>> entry : byTag.entrySet()) {
                final Path jar = jarByTag.get(entry.getKey());
                try (RewrittenHostClassLoader loader = new RewrittenHostClassLoader(
                        jar, hostUrls(jar), StrictWarpDeformVerifyChild.class.getClassLoader())) {
                    loader.installOverrides(entry.getValue(), protectionDomain(jar));
                    for (final String binaryName : entry.getValue().keySet()) {
                        Class.forName(binaryName, false, loader).getDeclaredMethods();
                    }
                    if (!loader.remainingOverrides().isEmpty()) {
                        throw new AssertionError("unloaded overrides for " + entry.getKey());
                    }
                }
            }
        }
    }
}
