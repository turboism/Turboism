package dev.turboism.adapter.cubism.optimization.animation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.management.ThreadMXBean;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.AWTEventListenerProxy;
import java.awt.image.BufferedImage;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeListenerProxy;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import javax.swing.JViewport;
import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.UIDefaults;
import javax.swing.UIManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies bytecode rewriting across reviewed Cubism releases, formatting/context pooling parity,
 * and Graph Editor horizontal viewport culling pixel parity.
 */
class AnimationAccelerationArtifactTest {

    private static final String REF_DIR_PROPERTY = "turboism.test.cubismRefDir";
    private static final String REF_DIR_ENV = "TURBOISM_CUBISM_REF";
    private static final String USER_HOME_PROPERTY = "user.home";
    private static final String ARCH_REFERENCE_SUFFIX = ".local/share/cubism-reference";
    private static final String AWT_HEADLESS_PROPERTY = "java.awt.headless";
    private static final String UI_CLASS_LOADER_KEY = "ClassLoader";
    private static final String APPLE_MENU_BAR_PROPERTY = "apple.laf.useScreenMenuBar";
    private static final String FLATLAF_MENU_BAR_PROPERTY = "flatlaf.menuBarEmbedded";
    private static final String CUBISM_JAR = "Live2D_Cubism.jar";
    private static final String JAR_EXTENSION = ".jar";
    private static final String CLASS_EXTENSION = ".class";
    private static final String VERIFY_MANIFEST_FILE = "patched-animation-targets.txt";
    private static final String XVERIFY_ALL_FLAG = "-Xverify:all";
    private static final String JAVA_BIN = "java";
    private static final String CLASSPATH_OPTION = "-cp";
    private static final String JAVA_HOME_PROPERTY = "java.home";
    private static final String JAVA_CLASS_PATH_PROPERTY = "java.class.path";
    private static final String BIN_DIR = "bin";
    private static final String TAB = "\t";
    private static final int MAX_WALK_DEPTH = 10;
    private static final int VERIFY_TIMEOUT_SECONDS = 120;

    private static final int[] SUBFRAME_FPS_SAMPLES = {1, 8, 10, 24, 30, 60, 100, 120, 240, 1000};
    private static final int[] FRAME_SAMPLES = {-300, -121, -60, -15, -1, 0, 1, 14, 59, 60, 125, 2999, 10000};
    private static final int WARMUP_ITERS = 12_000;
    private static final int MEASURED_ITERS = 600;
    private static final int ALLOC_MEASURE_ATTEMPTS = 3;
    private static final int CURVE_POINTS = 1500;
    private static final int CANVAS_WIDTH = 280;
    private static final int CANVAS_HEIGHT = 220;
    private static final int POOL_OVERFLOW_DEPTH = 40;
    private static final int PARITY_FRAME_POS = 99_999;

    private static final String SCENE_SOURCE_OWNER = "com/live2d/cubism/doc/animation/CSceneSource";
    private static final String ANIMATION_OWNER = "com/live2d/cubism/doc/animation/CAnimation";
    private static final String TRACK_GROUP_SOURCE_OWNER =
            "com/live2d/cubism/doc/animation/movie/track/CMvTrack_Group_Source";
    private static final String TRACK_MODEL_SOURCE_OWNER =
            "com/live2d/cubism/doc/animation/movie/track/CMvTrack_Live2DModel_Source";
    private static final String ATTR_F_OWNER = "com/live2d/cubism/doc/animation/movie/effect/attr/CMvAttrF";
    private static final String ATTR_ID_OWNER = "com/live2d/cubism/doc/model/id/CAttrId";
    private static final String TRACK_SOURCE_INTERFACE = "com/live2d/cubism/doc/animation/movie/track/ICMvTrack_Source";
    private static final String UI_GRAPHICS_OWNER = "com/live2d/ui/g";
    private static final String UI_WIDGET_OWNER = "com/live2d/ui/CWidget";
    private static final String VIEW_CONTEXT_OWNER = "com/live2d/cubism/view/context/CEViewContext";

    @Test
    void everyConfiguredReviewedArtifactRewritesAnimationClasses(@TempDir final Path tempDir) throws Exception {
        final Path root = referenceRoot();
        final Path extraLibsDir = findThirdPartyLibsDir(root);
        final Path manifestFile = tempDir.resolve(VERIFY_MANIFEST_FILE);
        final List<String> manifestLines = new ArrayList<>();
        int rewritten = 0;

        for (final String version : ReviewedHostArtifacts.reviewedCubismVersions()) {
            final Path artifact = resolveVersionArtifact(root, version);
            if (artifact == null) {
                continue;
            }
            final HostArtifactDigest digest = HostArtifactDigest.from(artifact);
            final AnimationAccelerationTarget target = AnimationAccelerationTarget.of(digest)
                    .orElseThrow(() -> new AssertionError("reviewed artifact refused: " + version));
            final List<String> owners = target.rewrittenOwners();
            final Map<String, byte[]> references = new LinkedHashMap<>();
            for (final String owner : owners) {
                references.put(owner, readClassBytes(artifact, owner));
            }
            try (URLClassLoader loader = hostLoader(artifact.getParent(), extraLibsDir);
                    URLClassLoader wrongLoader = hostLoader(artifact.getParent(), extraLibsDir)) {
                final AnimationAccelerationTransformer transformer =
                        new AnimationAccelerationTransformer(loader, artifact, references, target);
                final ProtectionDomain domain = protectionDomain(artifact);
                final Path versionDir = tempDir.resolve(version);

                for (final String owner : owners) {
                    final byte[] original = references.get(owner);
                    assertNull(transformer.transform(null, wrongLoader, owner, null, domain, original));
                    final byte[] patched = transformer.transform(null, loader, owner, null, domain, original);
                    assertNotNull(patched, version + " owner=" + owner + " failure=" + transformer.failure());
                    assertNull(transformer.failure(), version);
                    assertEquals(transformer.expectedMatchesFor(owner), transformer.matchesFor(owner), version);
                    writePatchedClass(versionDir, owner, patched);
                    manifestLines.add(version
                            + TAB
                            + artifact
                            + TAB
                            + versionDir
                            + TAB
                            + owner
                            + (extraLibsDir == null ? "" : TAB + extraLibsDir));
                }
                assertEquals(transformer.expectedTotalSites(), transformer.sites(), version);
                rewritten++;
            }
        }

        assertEquals(ReviewedHostArtifacts.reviewedCubismVersions().size(), rewritten, "all reviewed JARs checked");
        Files.write(manifestFile, manifestLines);
        runStrictVerifyAllSubprocess(manifestFile, manifestLines.size());
    }

    @Test
    void formattingCachesAndContextPoolingMatchHostAndAllocateZeroSteadyState() throws Throwable {
        final Path root = referenceRoot();
        final Path extraLibsDir = findThirdPartyLibsDir(root);
        final Path modernArtifact =
                findNewestArtifactForUtils(root, AnimationAccelerationTarget.TIMELINE_UTILS_V_OWNER);
        final Path legacyArtifact =
                findNewestArtifactForUtils(root, AnimationAccelerationTarget.TIMELINE_UTILS_S_OWNER);
        assertNotNull(modernArtifact, "modern animation artifact missing");
        verifyFormattingAndPoolingOnArtifact(modernArtifact, extraLibsDir);
        if (legacyArtifact != null) {
            verifyFormattingAndPoolingOnArtifact(legacyArtifact, extraLibsDir);
        }
    }

    @Test
    void graphEditorDrawCurveCullingMatchesUnmodifiedPixels() throws Exception {
        final String prevHeadless = System.getProperty(AWT_HEADLESS_PROPERTY);
        System.setProperty(AWT_HEADLESS_PROPERTY, Boolean.TRUE.toString());
        try {
            final Path root = referenceRoot();
            final Path extraLibsDir = findThirdPartyLibsDir(root);
            final Path modernArtifact =
                    findNewestArtifactForUtils(root, AnimationAccelerationTarget.TIMELINE_UTILS_V_OWNER);
            assertNotNull(modernArtifact, "modern animation artifact missing");
            verifyGraphEditorCurveParity(modernArtifact, extraLibsDir);
        } finally {
            if (prevHeadless == null) {
                System.clearProperty(AWT_HEADLESS_PROPERTY);
            } else {
                System.setProperty(AWT_HEADLESS_PROPERTY, prevHeadless);
            }
        }
    }

    private static void verifyFormattingAndPoolingOnArtifact(final Path artifact, final Path extraLibsDir)
            throws Throwable {
        final HostArtifactDigest digest = HostArtifactDigest.from(artifact);
        final AnimationAccelerationTarget target = AnimationAccelerationTarget.of(digest)
                .orElseThrow(() -> new AssertionError("missing target for " + artifact));
        final Map<String, byte[]> refs = readAllTargetReferences(artifact, target);
        final ProtectionDomain domain = protectionDomain(artifact);
        final URL[] urls = hostClasspathUrls(artifact.getParent(), extraLibsDir);

        final ClassLoader parent = ClassLoader.getPlatformClassLoader();
        try (URLClassLoader originalLoader = new URLClassLoader(urls, parent);
                RewrittenHostClassLoader rewrittenLoader = new RewrittenHostClassLoader(urls, parent);
                AnimationAccelerationBridge bridge = new AnimationAccelerationBridge(rewrittenLoader, target);
                SwingGlobalStateGuard swingGuard = SwingGlobalStateGuard.capture()) {
            installTransformedOwners(originalLoader, rewrittenLoader, artifact, domain, refs, target);
            bridge.install();
            final String prevValidation = System.getProperty(AnimationAccelerationBridge.VALIDATION_PROPERTY);
            System.setProperty(AnimationAccelerationBridge.VALIDATION_PROPERTY, Boolean.TRUE.toString());
            try {
                assertBridgeHelperContracts(bridge);
                assertMovieInfoSubFrameParity(originalLoader, rewrittenLoader, target.version(), true);
                assertTrackEvaluationAndPoolingParity(originalLoader, rewrittenLoader, bridge, target);
                bridge.close();
                assertMovieInfoSubFrameParity(originalLoader, rewrittenLoader, target.version() + " rollback", false);
                assertTrackEvaluationRollbackParity(
                        originalLoader, rewrittenLoader, target, target.version() + " rollback");
            } finally {
                if (prevValidation == null) {
                    System.clearProperty(AnimationAccelerationBridge.VALIDATION_PROPERTY);
                } else {
                    System.setProperty(AnimationAccelerationBridge.VALIDATION_PROPERTY, prevValidation);
                }
            }
        }
    }

    private static void assertBridgeHelperContracts(final AnimationAccelerationBridge bridge) {
        final DecimalFormat df = new DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.US));
        final double[] vals = {-0.0d, 0.0d, 1.0d, -1.25d, 12.3456d, Double.NaN, Double.POSITIVE_INFINITY};
        for (final double v : vals) {
            assertEquals(df.format(v), bridge.formatDecimal(df, v), "decimal parity for " + v);
        }
        // Mutating DecimalFormat settings must invalidate cached entries immediately.
        df.setMaximumFractionDigits(4);
        assertEquals(df.format(12.3456d), bridge.formatDecimal(df, 12.3456d));

        for (final int fps : SUBFRAME_FPS_SAMPLES) {
            for (final int frame : FRAME_SAMPLES) {
                final String fmt;
                if (fps >= 10 && fps < 100) {
                    fmt = String.format("%02d", frame < 0 ? -frame : frame);
                    assertEquals(fmt, bridge.formatSubFrame(frame, fps, true));
                    assertEquals(String.format("%02d", frame), bridge.formatSubFrame(frame, fps, false));
                } else if (fps >= 100 && fps < 1000) {
                    assertEquals(
                            String.format("%03d", frame < 0 ? -frame : frame), bridge.formatSubFrame(frame, fps, true));
                } else {
                    assertEquals(String.valueOf(frame), bridge.formatSubFrame(frame, fps, true));
                }
            }
        }

        assertEquals("00:05:12", bridge.formatTimecode(5, false, "12"));
        assertEquals("-01:05:12", bridge.formatTimecode(-65, true, "12"));
        assertEquals("-0:04", bridge.formatRulerTimecode(-4, 0, true, "04"));
        assertEquals("0:04", bridge.formatRulerTimecode(4, 0, false, "04"));
        assertEquals("00123", bridge.formatPaddedFrame(123, false));
        assertEquals("000123", bridge.formatPaddedFrame(123, true));
        assertEquals("42", bridge.formatRulerFrame(42));
    }

    private static void assertMovieInfoSubFrameParity(
            final ClassLoader originalLoader,
            final ClassLoader rewrittenLoader,
            final String label,
            final boolean expectZeroAlloc)
            throws Throwable {
        final Class<?> origClass = load(originalLoader, AnimationAccelerationTarget.MOVIE_INFO_OWNER);
        final Class<?> rewrClass = load(rewrittenLoader, AnimationAccelerationTarget.MOVIE_INFO_OWNER);
        final Object origInfo = origClass.getConstructor().newInstance();
        final Object rewrInfo = rewrClass.getConstructor().newInstance();
        final Method origSetFps = origClass.getMethod("setFps", double.class);
        final Method rewrSetFps = rewrClass.getMethod("setFps", double.class);
        final Method origStr = origClass.getMethod("getDisplayTimeSubFrameString", int.class);
        final Method rewrStr = rewrClass.getMethod("getDisplayTimeSubFrameString", int.class);

        for (final int fps : SUBFRAME_FPS_SAMPLES) {
            origSetFps.invoke(origInfo, (double) fps);
            rewrSetFps.invoke(rewrInfo, (double) fps);
            for (final int frame : FRAME_SAMPLES) {
                assertEquals(
                        origStr.invoke(origInfo, frame),
                        rewrStr.invoke(rewrInfo, frame),
                        label + " fps=" + fps + " frame=" + frame);
            }
        }

        // Warmed cache lookup allocates zero bytes when bridge is installed and falls back cleanly on close.
        if (AnimationAccelerationBridge.isEnabled()) {
            origSetFps.invoke(origInfo, 60.0d);
            rewrSetFps.invoke(rewrInfo, 60.0d);
            final ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
            assumeTrue(bean.isThreadAllocatedMemorySupported());
            bean.setThreadAllocatedMemoryEnabled(true);
            final long tid = Thread.currentThread().getId();
            final MethodHandle origMh = MethodHandles.lookup()
                    .unreflect(origStr)
                    .bindTo(origInfo)
                    .asType(MethodType.methodType(String.class, int.class));
            final MethodHandle rewrMh = MethodHandles.lookup()
                    .unreflect(rewrStr)
                    .bindTo(rewrInfo)
                    .asType(MethodType.methodType(String.class, int.class));
            for (int i = 0; i < WARMUP_ITERS; i++) {
                final String ignoredOrig = (String) origMh.invokeExact(14);
                final String ignoredRewr = (String) rewrMh.invokeExact(14);
            }
            final long beforeOrig = bean.getThreadAllocatedBytes(tid);
            for (int i = 0; i < MEASURED_ITERS; i++) {
                final String ignoredOrig = (String) origMh.invokeExact(14);
            }
            final long origDelta = bean.getThreadAllocatedBytes(tid) - beforeOrig;
            long rewrDelta = Long.MAX_VALUE;
            for (int attempt = 0; attempt < ALLOC_MEASURE_ATTEMPTS; attempt++) {
                for (int i = 0; i < MEASURED_ITERS; i++) {
                    final String ignoredWarm = (String) rewrMh.invokeExact(14);
                }
                bean.getThreadAllocatedBytes(tid);
                final long beforeRewr = bean.getThreadAllocatedBytes(tid);
                for (int i = 0; i < MEASURED_ITERS; i++) {
                    final String ignoredRewr = (String) rewrMh.invokeExact(14);
                }
                final long candidateDelta = bean.getThreadAllocatedBytes(tid) - beforeRewr;
                rewrDelta = Math.min(rewrDelta, candidateDelta);
            }
            assertTrue(origDelta > 0L, label + " unmodified host should allocate during String.format");
            if (expectZeroAlloc) {
                assertEquals(
                        0L,
                        rewrDelta,
                        label + " cached sub-frame string should allocate 0 bytes (origDelta=" + origDelta + ")");
            } else {
                assertTrue(
                        rewrDelta > 0L,
                        label + " rolled-back sub-frame string should resume host String.format allocation");
            }
        }
    }

    private static void assertTrackEvaluationAndPoolingParity(
            final ClassLoader originalLoader,
            final ClassLoader rewrittenLoader,
            final AnimationAccelerationBridge bridge,
            final AnimationAccelerationTarget target)
            throws Exception {
        final String version = target.version();
        // Pool uses a LIFO per-thread stack, capping retained depth cleanly.
        final Object ctx1 = bridge.acquireTrackContext();
        final Object ctx2 = bridge.acquireTrackContext();
        assertNotSame(ctx1, ctx2);
        bridge.releaseTrackContext(ctx2);
        final Object ctx2Again = bridge.acquireTrackContext();
        assertSame(ctx2, ctx2Again, version + " track context LIFO pool reuse (top)");
        bridge.releaseTrackContext(ctx2Again);
        bridge.releaseTrackContext(ctx1);
        final Object ctx1Again = bridge.acquireTrackContext();
        assertSame(ctx1, ctx1Again, version + " track context LIFO pool reuse (base)");
        bridge.releaseTrackContext(ctx1Again);

        final List<Object> overflow = new ArrayList<>();
        for (int i = 0; i < POOL_OVERFLOW_DEPTH; i++) {
            overflow.add(bridge.acquireTrackContext());
        }
        for (int i = overflow.size() - 1; i >= 0; i--) {
            bridge.releaseTrackContext(overflow.get(i));
        }

        final Object ft1 = bridge.acquireFrameTime(12);
        final Field fieldA = ft1.getClass().getDeclaredField("a");
        fieldA.setAccessible(true);
        assertEquals(12, fieldA.getInt(ft1));
        bridge.releaseFrameTime(ft1);
        final Object ft2 = bridge.acquireFrameTime(99);
        assertSame(ft1, ft2, version + " frame time pool reuse");
        assertEquals(99, fieldA.getInt(ft2));
        bridge.releaseFrameTime(ft2);

        assertTrackEvaluationParity(originalLoader, rewrittenLoader, bridge, target, version);
    }

    private static void assertTrackEvaluationRollbackParity(
            final ClassLoader originalLoader,
            final ClassLoader rewrittenLoader,
            final AnimationAccelerationTarget target,
            final String label)
            throws Exception {
        assertTrackEvaluationParity(originalLoader, rewrittenLoader, null, target, label);
    }

    private static void assertTrackEvaluationParity(
            final ClassLoader originalLoader,
            final ClassLoader rewrittenLoader,
            final AnimationAccelerationBridge bridge,
            final AnimationAccelerationTarget target,
            final String label)
            throws Exception {
        final Class<?> origFaCls = load(originalLoader, AnimationAccelerationTarget.FORM_ANIMATION_SET_OWNER);
        final Class<?> rewrFaCls = load(rewrittenLoader, AnimationAccelerationTarget.FORM_ANIMATION_SET_OWNER);
        final Object origFa = newWiredFormAnimationSet(originalLoader, origFaCls);
        final Object rewrFa = newWiredFormAnimationSet(rewrittenLoader, rewrFaCls);
        final Method origUpdateFa = origFaCls.getMethod("updateTrackInstance", int.class, boolean.class);
        final Method rewrUpdateFa = rewrFaCls.getMethod("updateTrackInstance", int.class, boolean.class);

        final Object expectedTopCtx = bridge == null ? null : bridge.acquireTrackContext();
        final Object expectedFrameTime = bridge == null ? null : bridge.acquireFrameTime(0);
        if (bridge != null) {
            bridge.releaseTrackContext(expectedTopCtx);
            bridge.releaseFrameTime(expectedFrameTime);
        }

        // Verify cache-hit skip path on wired FormAnimationSet preserves identical state without error.
        seedCachedFrameState(origFaCls, origFa);
        seedCachedFrameState(rewrFaCls, rewrFa);
        origUpdateFa.invoke(origFa, PARITY_FRAME_POS, false);
        rewrUpdateFa.invoke(rewrFa, PARITY_FRAME_POS, false);
        assertFormAnimationStateEqual(origFaCls, origFa, rewrFaCls, rewrFa, label + " cache-hit eval");

        // Fast path enters trackInstance.updateTrackInstance_testImpl; without workbench UI singleton both
        // throw identical UninitializedPropertyAccessException, and catch handler returns pooled handles.
        final InvocationTargetException origFastEx = assertThrows(
                InvocationTargetException.class, () -> origUpdateFa.invoke(origFa, PARITY_FRAME_POS + 1, false));
        final InvocationTargetException rewrFastEx = assertThrows(
                InvocationTargetException.class, () -> rewrUpdateFa.invoke(rewrFa, PARITY_FRAME_POS + 1, false));
        assertEquals(
                origFastEx.getCause().getClass().getName(),
                rewrFastEx.getCause().getClass().getName(),
                label + " fast-path cause");
        assertFormAnimationStateEqual(origFaCls, origFa, rewrFaCls, rewrFa, label + " fast-path exception");
        assertPooledHandlesReturned(bridge, expectedTopCtx, expectedFrameTime, label + " after fast-path exception");

        // Verify null trackInstance throws identically and catch block still cleans up flags and pooled handles.
        final Object origUnwired = origFaCls.getConstructor().newInstance();
        final Object rewrUnwired = rewrFaCls.getConstructor().newInstance();
        final InvocationTargetException origNpe =
                assertThrows(InvocationTargetException.class, () -> origUpdateFa.invoke(origUnwired, 17, false));
        final InvocationTargetException rewrNpe =
                assertThrows(InvocationTargetException.class, () -> rewrUpdateFa.invoke(rewrUnwired, 17, false));
        assertTrue(origNpe.getCause() instanceof NullPointerException, label);
        assertTrue(rewrNpe.getCause() instanceof NullPointerException, label);
        assertFormAnimationStateEqual(origFaCls, origUnwired, rewrFaCls, rewrUnwired, label + " exception rollback");
        assertPooledHandlesReturned(bridge, expectedTopCtx, expectedFrameTime, label + " after NPE exception");

        if (target.hasSceneParamCtrlReference()) {
            verifySceneParamCtrlTraversal(originalLoader, rewrittenLoader, target, label);
            assertPooledHandlesReturned(bridge, expectedTopCtx, expectedFrameTime, label + " after scene update");
        }

        // Verify CMvTrack_Group_Instance indexed traversal & child state match unmodified host.
        verifyGroupTraversal(originalLoader, rewrittenLoader, label);
    }

    private static void seedCachedFrameState(final Class<?> faCls, final Object fa) throws Exception {
        final Field frameField = faCls.getDeclaredField("lastTrackUpdateFramePosForFormAnimation");
        frameField.setAccessible(true);
        frameField.setInt(fa, PARITY_FRAME_POS);
        final Field paramField = faCls.getDeclaredField("last_trackUpdateParamVersion");
        paramField.setAccessible(true);
        paramField.setInt(fa, 0);
    }

    private static void assertPooledHandlesReturned(
            final AnimationAccelerationBridge bridge,
            final Object expectedTopCtx,
            final Object expectedFrameTime,
            final String label) {
        if (bridge == null) {
            return;
        }
        final Object reacquiredCtx = bridge.acquireTrackContext();
        final Object reacquiredTime = bridge.acquireFrameTime(0);
        assertSame(expectedTopCtx, reacquiredCtx, label + " track context");
        assertSame(expectedFrameTime, reacquiredTime, label + " frame time");
        bridge.releaseTrackContext(reacquiredCtx);
        bridge.releaseFrameTime(reacquiredTime);
    }

    private static void verifySceneParamCtrlTraversal(
            final ClassLoader origLoader,
            final ClassLoader rewrLoader,
            final AnimationAccelerationTarget target,
            final String label)
            throws Exception {
        invokeSceneParamCtrlUpdate(origLoader, target, 5);
        invokeSceneParamCtrlUpdate(rewrLoader, target, 5);
        assertNotEquals(origLoader, rewrLoader, label);
    }

    private static void invokeSceneParamCtrlUpdate(
            final ClassLoader loader, final AnimationAccelerationTarget target, final int frame) throws Exception {
        final Class<?> sceneCls = load(loader, AnimationAccelerationTarget.SCENE_INSTANCE_OWNER);
        final Class<?> groupCls = load(loader, AnimationAccelerationTarget.TRACK_GROUP_INSTANCE_OWNER);
        final Class<?> ctxCls = load(loader, target.trackContextOwner());
        final Object scene = sceneCls.getConstructor().newInstance();
        final Object rootGroup = buildNestedGroup(loader);
        final Method setRootTrack = sceneCls.getDeclaredMethod("set_rootTrack", groupCls);
        setRootTrack.setAccessible(true);
        setRootTrack.invoke(scene, rootGroup);
        final Object ctx = ctxCls.getConstructor(boolean.class).newInstance(false);
        sceneCls.getMethod("updateAllParamCtrlReference", int.class, ctxCls).invoke(scene, frame, ctx);
    }

    private static Object newWiredFormAnimationSet(final ClassLoader loader, final Class<?> faCls) throws Exception {
        final Object fa = faCls.getConstructor().newInstance();
        final Object model = load(loader, AnimationAccelerationTarget.MODEL_OWNER)
                .getConstructor()
                .newInstance();
        final Object trackSrc =
                load(loader, TRACK_MODEL_SOURCE_OWNER).getConstructor().newInstance();
        final Class<?> trackInstCls = load(loader, AnimationAccelerationTarget.TRACK_MODEL_INSTANCE_OWNER);
        final Object trackInst = trackInstCls.getConstructor().newInstance();
        final Field modelField = trackInstCls.getDeclaredField("_modelInstance");
        modelField.setAccessible(true);
        modelField.set(trackInst, model);
        final Field sourceField = trackInstCls.getDeclaredField("_source");
        sourceField.setAccessible(true);
        sourceField.set(trackInst, trackSrc);
        final Field trackField = faCls.getDeclaredField("trackInstance");
        trackField.setAccessible(true);
        trackField.set(fa, trackInst);
        return fa;
    }

    private static void assertFormAnimationStateEqual(
            final Class<?> origFaCls,
            final Object origFa,
            final Class<?> rewrFaCls,
            final Object rewrFa,
            final String label)
            throws Exception {
        assertEquals(
                origFaCls
                        .getMethod("getLastTrackUpdateFramePosForFormAnimation")
                        .invoke(origFa),
                rewrFaCls
                        .getMethod("getLastTrackUpdateFramePosForFormAnimation")
                        .invoke(rewrFa),
                label + " FormAnimationSet frame pos");
        final Field origParamVer = origFaCls.getDeclaredField("last_trackUpdateParamVersion");
        final Field rewrParamVer = rewrFaCls.getDeclaredField("last_trackUpdateParamVersion");
        origParamVer.setAccessible(true);
        rewrParamVer.setAccessible(true);
        assertEquals(origParamVer.getInt(origFa), rewrParamVer.getInt(rewrFa), label + " paramVersion");
        final Field origUpdating = origFaCls.getDeclaredField("isTrackUpdating");
        final Field rewrUpdating = rewrFaCls.getDeclaredField("isTrackUpdating");
        origUpdating.setAccessible(true);
        rewrUpdating.setAccessible(true);
        assertEquals(origUpdating.getBoolean(origFa), rewrUpdating.getBoolean(rewrFa), label + " isTrackUpdating");
    }

    private static void verifyGroupTraversal(
            final ClassLoader origLoader, final ClassLoader rewrLoader, final String label) throws Exception {
        final Object origGroup = buildNestedGroup(origLoader);
        final Object rewrGroup = buildNestedGroup(rewrLoader);
        invokeGroupUpdate(origLoader, origGroup, 5);
        invokeGroupUpdate(rewrLoader, rewrGroup, 5);
        assertEquals(
                origGroup.getClass().getMethod("getEnabled").invoke(origGroup),
                rewrGroup.getClass().getMethod("getEnabled").invoke(rewrGroup),
                label + " group enabled parity");
        assertNotEquals(
                origGroup.getClass().getClassLoader(), rewrGroup.getClass().getClassLoader(), label);
    }

    private static Object buildNestedGroup(final ClassLoader loader) throws Exception {
        final Class<?> groupCls = load(loader, AnimationAccelerationTarget.TRACK_GROUP_INSTANCE_OWNER);
        final Class<?> groupSrcCls = load(loader, TRACK_GROUP_SOURCE_OWNER);
        final Constructor<?> ctor = groupCls.getConstructor(groupSrcCls);
        final Object parent = ctor.newInstance(groupSrcCls.getConstructor().newInstance());
        final Object child1 = ctor.newInstance(groupSrcCls.getConstructor().newInstance());
        final Object child2 = ctor.newInstance(groupSrcCls.getConstructor().newInstance());
        @SuppressWarnings("unchecked")
        final List<Object> children =
                (List<Object>) groupCls.getMethod("getTrackChildren").invoke(parent);
        children.add(child1);
        children.add(child2);
        return parent;
    }

    private static void invokeGroupUpdate(final ClassLoader loader, final Object group, final int frame)
            throws Exception {
        final Class<?> groupCls = group.getClass();
        final Class<?> ftCls = load(loader, AnimationAccelerationTarget.FRAME_TIME_OWNER);
        final Class<?> viewCtxCls = load(loader, VIEW_CONTEXT_OWNER);
        final Object frameTime = ftCls.getConstructor(int.class).newInstance(frame);
        Method targetMethod = null;
        for (final Method m : groupCls.getMethods()) {
            if ("updateTrackInstance_testImpl".equals(m.getName()) && m.getParameterCount() == 3) {
                targetMethod = m;
                break;
            }
        }
        assertNotNull(targetMethod);
        final Class<?> trackCtxCls = targetMethod.getParameterTypes()[2];
        final Object trackCtx = trackCtxCls.getConstructor(boolean.class).newInstance(false);
        targetMethod.invoke(group, viewCtxCls.cast(null), frameTime, trackCtx);
    }

    private static void verifyGraphEditorCurveParity(final Path artifact, final Path extraLibsDir) throws Exception {
        final HostArtifactDigest digest = HostArtifactDigest.from(artifact);
        final AnimationAccelerationTarget target = AnimationAccelerationTarget.of(digest)
                .orElseThrow(() -> new AssertionError("missing target for " + artifact));
        final Map<String, byte[]> refs = readAllTargetReferences(artifact, target);
        final ProtectionDomain domain = protectionDomain(artifact);
        final URL[] urls = hostClasspathUrls(artifact.getParent(), extraLibsDir);

        final ClassLoader parent = ClassLoader.getPlatformClassLoader();
        try (URLClassLoader originalLoader = new URLClassLoader(urls, parent);
                RewrittenHostClassLoader rewrittenLoader = new RewrittenHostClassLoader(urls, parent);
                AnimationAccelerationBridge bridge = new AnimationAccelerationBridge(rewrittenLoader, target);
                SwingGlobalStateGuard swingGuard = SwingGlobalStateGuard.capture()) {
            installTransformedOwners(originalLoader, rewrittenLoader, artifact, domain, refs, target);
            bridge.install();
            final String prevValidation = System.getProperty(AnimationAccelerationBridge.VALIDATION_PROPERTY);
            System.setProperty(AnimationAccelerationBridge.VALIDATION_PROPERTY, Boolean.TRUE.toString());
            try {
                final CurveScene origScene = setupGraphEditorScene(originalLoader);
                final CurveScene rewrScene = setupGraphEditorScene(rewrittenLoader);

                // Case 1: Mid-timeline viewport with Bezier/Smooth edges straddling both clip bounds.
                renderAndAssertPixelsEqual(origScene, rewrScene, 300, 180, true, false, false, -1, "middle-clip");
                // Case 2: Labels enabled, offscreen keyframe selected so handles stay visible cleanly.
                renderAndAssertPixelsEqual(origScene, rewrScene, 420, 180, true, true, true, 5, "selected-offscreen");
                // Case 3: Entirely offscreen viewport.
                renderAndAssertPixelsEqual(origScene, rewrScene, 90000, 200, true, false, false, -1, "offscreen-right");
                // Case 4: Non-monotonic / collapsed zoom fallback path.
                renderAndAssertPixelsEqual(origScene, rewrScene, 0, 0, false, false, false, -1, "empty-clip-fallback");
            } finally {
                if (prevValidation == null) {
                    System.clearProperty(AnimationAccelerationBridge.VALIDATION_PROPERTY);
                } else {
                    System.setProperty(AnimationAccelerationBridge.VALIDATION_PROPERTY, prevValidation);
                }
            }
        }
    }

    private static CurveScene setupGraphEditorScene(final ClassLoader loader) throws Exception {
        final Class<?> utilsCls = load(loader, AnimationAccelerationTarget.TIMELINE_UTILS_V_OWNER);
        final Object utils = utilsCls.getField("a").get(null);
        final JViewport viewport = new JViewport();
        viewport.setSize(CANVAS_WIDTH, CANVAS_HEIGHT);
        viewport.setViewPosition(new Point(0, 0));
        utilsCls.getMethod("a", JViewport.class).invoke(utils, viewport);
        utilsCls.getMethod("a", float.class).invoke(utils, 2.0f);

        final Class<?> geCls = load(loader, AnimationAccelerationTarget.GRAPH_EDITOR_OWNER);
        final Object editor = geCls.getConstructor().newInstance();
        final Method setCurveMag = geCls.getDeclaredMethod("setCurveMagnification", float.class);
        setCurveMag.setAccessible(true);
        setCurveMag.invoke(editor, 20.0f);
        final Method setMidPt = geCls.getDeclaredMethod("setMiddlePoint", int.class);
        setMidPt.setAccessible(true);
        setMidPt.invoke(editor, CANVAS_HEIGHT / 2);

        final Class<?> animCls = load(loader, ANIMATION_OWNER);
        final Object anim = animCls.getConstructor().newInstance();
        final Class<?> sceneCls = load(loader, SCENE_SOURCE_OWNER);
        final Object sceneSource = sceneCls.getConstructor().newInstance();
        animCls.getMethod("addScene", sceneCls).invoke(anim, sceneSource);
        final Field sceneField = utilsCls.getDeclaredField("k");
        sceneField.setAccessible(true);
        sceneField.set(null, sceneSource);
        final Class<?> attrFCls = load(loader, ATTR_F_OWNER);
        final Class<?> attrIdCls = load(loader, ATTR_ID_OWNER);
        final Class<?> trackSrcIf = load(loader, TRACK_SOURCE_INTERFACE);
        final Object rootTrack = sceneCls.getMethod("getRootTrack").invoke(sceneSource);
        final Object attrId = attrIdCls.getConstructor(String.class).newInstance("ATTR_TEST");
        final Object attrF = attrFCls.getConstructor(trackSrcIf, attrIdCls, String.class, double.class)
                .newInstance(rootTrack, attrId, "Curve", 0.0d);

        final Class<?> seqCls = load(loader, AnimationAccelerationTarget.MUTABLE_SEQUENCE_OWNER);
        final Class<?> curveTypeCls = load(loader, AnimationAccelerationTarget.CURVE_TYPE_OWNER);
        final Object seq = seqCls.getConstructor(attrFCls, double.class).newInstance(attrF, 0.0d);

        @SuppressWarnings({"unchecked", "rawtypes"})
        final Object[] curveTypes = {
            Enum.valueOf((Class<Enum>) curveTypeCls, "LINEAR"),
            Enum.valueOf((Class<Enum>) curveTypeCls, "BEZIER"),
            Enum.valueOf((Class<Enum>) curveTypeCls, "SMOOTH"),
            Enum.valueOf((Class<Enum>) curveTypeCls, "STEP"),
            Enum.valueOf((Class<Enum>) curveTypeCls, "INVERSE_STEP")
        };

        final Method setAuto = attrFCls.getMethod("setValueAndCurveType", int.class, double.class, curveTypeCls);
        for (int i = 0; i < CURVE_POINTS; i++) {
            final int frame = i * 4;
            final double val = Math.sin(i * 0.23) * 3.0;
            setAuto.invoke(attrF, frame, val, curveTypes[i % curveTypes.length]);
        }
        final Object realSeq = attrFCls.getMethod("getValueData").invoke(attrF);
        seqCls.getMethod("forceUpdate").invoke(realSeq);

        final Class<?> uiGCls = load(loader, UI_GRAPHICS_OWNER);
        final Class<?> widgetCls = load(loader, UI_WIDGET_OWNER);
        final Constructor<?> uiGCtor = uiGCls.getConstructor(java.awt.Graphics.class, widgetCls);
        final Method drawCurve = geCls.getDeclaredMethod("drawCurve", uiGCls, seqCls, boolean.class);
        drawCurve.setAccessible(true);

        return new CurveScene(utilsCls, utils, viewport, geCls, editor, attrF, realSeq, uiGCtor, drawCurve);
    }

    private static void renderAndAssertPixelsEqual(
            final CurveScene orig,
            final CurveScene rewr,
            final int viewX,
            final int viewWidth,
            final boolean showDots,
            final boolean showValues,
            final boolean showAttrName,
            final int selectedIdx,
            final String caseLabel)
            throws Exception {
        final BufferedImage origImg = new BufferedImage(CANVAS_WIDTH, CANVAS_HEIGHT, BufferedImage.TYPE_INT_ARGB);
        final BufferedImage rewrImg = new BufferedImage(CANVAS_WIDTH, CANVAS_HEIGHT, BufferedImage.TYPE_INT_ARGB);
        renderSingleScene(orig, origImg, viewX, viewWidth, showDots, showValues, showAttrName, selectedIdx);
        renderSingleScene(rewr, rewrImg, viewX, viewWidth, showDots, showValues, showAttrName, selectedIdx);

        final int[] origPixels = origImg.getRGB(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT, null, 0, CANVAS_WIDTH);
        final int[] rewrPixels = rewrImg.getRGB(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT, null, 0, CANVAS_WIDTH);
        assertArrayEquals(origPixels, rewrPixels, "pixel mismatch on GraphEditor case=" + caseLabel);
    }

    private static void renderSingleScene(
            final CurveScene scene,
            final BufferedImage image,
            final int viewX,
            final int viewWidth,
            final boolean showDots,
            final boolean showValues,
            final boolean showAttrName,
            final int selectedIdx)
            throws Exception {
        scene.viewport.setSize(viewWidth, CANVAS_HEIGHT);
        scene.viewport.setViewPosition(new Point(viewX, 0));

        final Field showValField = scene.geCls.getDeclaredField("enableShowKeyframesValue");
        showValField.setAccessible(true);
        showValField.setBoolean(null, showValues);
        final Field showAttrField = scene.geCls.getDeclaredField("enableDisplayAttributeName");
        showAttrField.setAccessible(true);
        showAttrField.setBoolean(null, showAttrName);
        final Field selField = scene.geCls.getDeclaredField("selectedKeyframe");
        selField.setAccessible(true);
        final Object pair = selectedIdx >= 0
                ? load(scene.geCls.getClassLoader(), "kotlin/Pair")
                        .getConstructor(Object.class, Object.class)
                        .newInstance(scene.attrF, selectedIdx)
                : null;
        selField.set(scene.editor, pair);

        final Graphics2D g2d = image.createGraphics();
        try {
            g2d.setClip(new Rectangle(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT));
            final Object uiG = scene.uiGCtor.newInstance(g2d, null);
            scene.drawCurve.invoke(scene.editor, uiG, scene.sequence, showDots);
        } finally {
            g2d.dispose();
        }
    }

    private static void installTransformedOwners(
            final ClassLoader originalLoader,
            final RewrittenHostClassLoader rewrittenLoader,
            final Path artifact,
            final ProtectionDomain domain,
            final Map<String, byte[]> refs,
            final AnimationAccelerationTarget target)
            throws ClassNotFoundException, IOException {
        final Map<String, byte[]> patchedByOwner = new LinkedHashMap<>();
        try (URLClassLoader inspectLoader =
                new URLClassLoader(rewrittenLoader.getURLs(), ClassLoader.getPlatformClassLoader())) {
            final AnimationAccelerationTransformer transformer =
                    new AnimationAccelerationTransformer(inspectLoader, artifact, refs, target);
            for (final String owner : target.rewrittenOwners()) {
                final byte[] patched = transformer.transform(null, inspectLoader, owner, null, domain, refs.get(owner));
                assertNotNull(patched, target.version() + " owner=" + owner + " failure=" + transformer.failure());
                patchedByOwner.put(owner, patched);
            }
            assertEquals(transformer.expectedTotalSites(), transformer.sites());
        }
        for (final Map.Entry<String, byte[]> entry : patchedByOwner.entrySet()) {
            final String owner = entry.getKey();
            final ProtectionDomain signedDomain = Class.forName(owner.replace('/', '.'), false, originalLoader)
                    .getProtectionDomain();
            rewrittenLoader.installOverride(owner.replace('/', '.'), entry.getValue(), signedDomain);
        }
    }

    private static Map<String, byte[]> readAllTargetReferences(
            final Path artifact, final AnimationAccelerationTarget target) throws IOException {
        final Map<String, byte[]> refs = new LinkedHashMap<>();
        for (final String owner : target.rewrittenOwners()) {
            refs.put(owner, readClassBytes(artifact, owner));
        }
        return refs;
    }

    private static Path findNewestArtifactForUtils(final Path root, final String utilsOwner) throws Exception {
        final List<String> versions = sortedVersions();
        for (int i = versions.size() - 1; i >= 0; i--) {
            final Path artifact = resolveVersionArtifact(root, versions.get(i));
            if (artifact == null) {
                continue;
            }
            final HostArtifactDigest digest = HostArtifactDigest.from(artifact);
            final AnimationAccelerationTarget target =
                    AnimationAccelerationTarget.of(digest).orElse(null);
            if (target != null && utilsOwner.equals(target.timelineUtilsOwner())) {
                return artifact;
            }
        }
        return null;
    }

    private static List<String> sortedVersions() {
        final List<String> versions = new ArrayList<>(ReviewedHostArtifacts.reviewedCubismVersions());
        versions.sort(String::compareTo);
        return versions;
    }

    private static Class<?> load(final ClassLoader loader, final String internalName) throws Exception {
        return Class.forName(internalName.replace('/', '.'), true, loader);
    }

    private static Path referenceRoot() {
        final String configured =
                System.getProperty(REF_DIR_PROPERTY, System.getenv().getOrDefault(REF_DIR_ENV, ""));
        assumeTrue(!configured.isBlank(), "exact Cubism reference directory not configured");
        final Path root = Path.of(configured);
        assumeTrue(Files.isDirectory(root), "reference directory absent: " + root);
        return root;
    }

    private static Path resolveVersionArtifact(final Path root, final String version) throws IOException {
        final Path direct = root.resolve(version).resolve(CUBISM_JAR);
        if (Files.isRegularFile(direct)) {
            return direct;
        }
        final Path referenceBase = Path.of(System.getProperty(USER_HOME_PROPERTY), ARCH_REFERENCE_SUFFIX, version);
        if (!Files.isDirectory(referenceBase)) {
            return null;
        }
        try (var stream = Files.walk(referenceBase, MAX_WALK_DEPTH)) {
            return stream.filter(path -> Files.isRegularFile(path)
                            && CUBISM_JAR.equals(path.getFileName().toString()))
                    .findFirst()
                    .orElse(null);
        }
    }

    private static Path findThirdPartyLibsDir(final Path root) throws IOException {
        final List<String> versions = sortedVersions();
        for (int i = versions.size() - 1; i >= 0; i--) {
            final Path artifact = resolveVersionArtifact(root, versions.get(i));
            if (artifact != null && Files.isRegularFile(artifact.getParent().resolve("jdom-1.1.jar"))) {
                return artifact.getParent();
            }
        }
        return null;
    }

    private static URL[] hostClasspathUrls(final Path jarDir, final Path extraLibsDir) throws IOException {
        final List<URL> urls = new ArrayList<>();
        try (var files = Files.walk(jarDir, 2)) {
            for (final Path file : files.toList()) {
                if (Files.isRegularFile(file) && file.toString().endsWith(JAR_EXTENSION)) {
                    urls.add(file.toUri().toURL());
                }
            }
        }
        if (extraLibsDir != null && !extraLibsDir.equals(jarDir)) {
            try (var files = Files.walk(extraLibsDir, 2)) {
                for (final Path file : files.toList()) {
                    final String fileName = file.getFileName().toString();
                    if (Files.isRegularFile(file)
                            && fileName.endsWith(JAR_EXTENSION)
                            && !fileName.startsWith("Live2D")) {
                        urls.add(file.toUri().toURL());
                    }
                }
            }
        }
        return urls.toArray(URL[]::new);
    }

    private static URLClassLoader hostLoader(final Path jarDir, final Path extraLibsDir) throws IOException {
        return new URLClassLoader(hostClasspathUrls(jarDir, extraLibsDir), ClassLoader.getPlatformClassLoader());
    }

    private static byte[] readClassBytes(final Path jarPath, final String internalName) throws IOException {
        final String entryName = internalName + CLASS_EXTENSION;
        try (JarFile jarFile = new JarFile(jarPath.toFile(), false)) {
            final JarEntry entry = jarFile.getJarEntry(entryName);
            assertNotNull(entry, "missing class entry " + entryName + " in " + jarPath);
            try (InputStream in = jarFile.getInputStream(entry)) {
                return in.readAllBytes();
            }
        }
    }

    private static ProtectionDomain protectionDomain(final Path artifact) throws IOException {
        final URL url = artifact.toAbsolutePath().normalize().toUri().toURL();
        return new ProtectionDomain(new CodeSource(url, (Certificate[]) null), null);
    }

    private static void writePatchedClass(final Path outDir, final String internalName, final byte[] bytes)
            throws IOException {
        final Path classFile = outDir.resolve(internalName + CLASS_EXTENSION);
        Files.createDirectories(classFile.getParent());
        Files.write(classFile, bytes);
    }

    private static void runStrictVerifyAllSubprocess(final Path manifestFile, final int expectedCount)
            throws Exception {
        final Path javaExe = Path.of(System.getProperty(JAVA_HOME_PROPERTY), BIN_DIR, JAVA_BIN);
        final ProcessBuilder pb = new ProcessBuilder(
                javaExe.toString(),
                XVERIFY_ALL_FLAG,
                CLASSPATH_OPTION,
                System.getProperty(JAVA_CLASS_PATH_PROPERTY),
                StrictChildVerifier.class.getName(),
                manifestFile.toString(),
                Integer.toString(expectedCount));
        pb.redirectErrorStream(true);
        final Process process = pb.start();
        final String output = new String(process.getInputStream().readAllBytes());
        assertTrue(process.waitFor(VERIFY_TIMEOUT_SECONDS, TimeUnit.SECONDS), "verifier timed out");
        assertEquals(0, process.exitValue(), "strict verification failed:\n" + output);
    }

    /** Loads every patched animation owner class under {@code -Xverify:all} inside a fresh JVM. */
    public static final class StrictChildVerifier {

        private StrictChildVerifier() {}

        /**
         * Verifies every class entry listed in {@code args[0]} equals {@code args[1]} count.
         *
         * @param args manifest path and expected entry count
         */
        public static void main(final String[] args) throws Exception {
            final Path manifest = Path.of(args[0]);
            final int expected = Integer.parseInt(args[1]);
            final List<String> lines = Files.readAllLines(manifest);
            if (lines.size() != expected) {
                throw new AssertionError("expected " + expected + " lines, got " + lines.size());
            }
            final ClassLoader appLoader = StrictChildVerifier.class.getClassLoader();
            for (final String line : lines) {
                final String[] parts = line.split(TAB);
                final Path jarPath = Path.of(parts[1]);
                final Path patchRoot = Path.of(parts[2]);
                final String internalName = parts[3];
                final Path extraLibsDir = parts.length > 4 ? Path.of(parts[4]) : null;
                try (PatchedJarLoader loader = new PatchedJarLoader(jarPath, patchRoot, extraLibsDir, appLoader)) {
                    final Class<?> loaded = Class.forName(internalName.replace('/', '.'), false, loader);
                    loaded.getDeclaredMethods();
                }
            }
        }
    }

    private static final class PatchedJarLoader extends URLClassLoader {
        private final JarFile hostJar;
        private final Path patchRoot;
        private final ClassLoader fallbackParent;

        private PatchedJarLoader(
                final Path jarPath, final Path patchRoot, final Path extraLibsDir, final ClassLoader fallbackParent)
                throws IOException {
            super(siblingUrls(jarPath, extraLibsDir), ClassLoader.getPlatformClassLoader());
            this.hostJar = new JarFile(jarPath.toFile(), false);
            this.patchRoot = patchRoot;
            this.fallbackParent = fallbackParent;
        }

        private static URL[] siblingUrls(final Path jarPath, final Path extraLibsDir) throws IOException {
            final List<URL> urls = new ArrayList<>();
            try (var files = Files.walk(jarPath.getParent(), 2)) {
                for (final Path file : files.toList()) {
                    if (Files.isRegularFile(file) && file.toString().endsWith(JAR_EXTENSION) && !file.equals(jarPath)) {
                        urls.add(file.toUri().toURL());
                    }
                }
            }
            if (extraLibsDir != null && !extraLibsDir.equals(jarPath.getParent())) {
                try (var files = Files.walk(extraLibsDir, 2)) {
                    for (final Path file : files.toList()) {
                        final String fileName = file.getFileName().toString();
                        if (Files.isRegularFile(file)
                                && fileName.endsWith(JAR_EXTENSION)
                                && !fileName.startsWith("Live2D")) {
                            urls.add(file.toUri().toURL());
                        }
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

    private record CurveScene(
            Class<?> utilsCls,
            Object utils,
            JViewport viewport,
            Class<?> geCls,
            Object editor,
            Object attrF,
            Object sequence,
            Constructor<?> uiGCtor,
            Method drawCurve) {}

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }

    /**
     * Snapshots and restores Swing global state before closing isolated host classloaders so bundled
     * FlatLaf UI delegates do not remain registered after their loader is closed.
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
                runOnEdtAndWait(() -> {
                    final Toolkit toolkit = Toolkit.getDefaultToolkit();
                    final UIDefaults uiDefaults = UIManager.getDefaults();
                    final UIDefaults lafDefaults = UIManager.getLookAndFeelDefaults();
                    removeAddedAwtListeners(toolkit);
                    removeAddedToolkitPropertyListeners(toolkit);
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
}
