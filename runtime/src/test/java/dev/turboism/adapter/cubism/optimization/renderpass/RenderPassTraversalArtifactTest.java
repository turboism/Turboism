package dev.turboism.adapter.cubism.optimization.renderpass;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import dev.turboism.adapter.cubism.optimization.clipmask.ClipMaskDeduplicationTarget;
import dev.turboism.adapter.cubism.optimization.clipmask.ClipMaskDeduplicationTransformer;
import dev.turboism.adapter.cubism.performance.ClipMaskProbeMethodTransformer;
import dev.turboism.adapter.cubism.performance.ClipMaskProbeTargets;
import dev.turboism.adapter.cubism.performance.PerformanceProbeMethodTransformer;
import dev.turboism.adapter.cubism.performance.PerformanceProbeTargets;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
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
import java.util.concurrent.CompletableFuture;
import java.util.jar.JarFile;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Verifies 20-version bytecode rewriting, multi-transformer composition, fail-closed guards,
 * allocation reduction, and behavioral parity for {@link RenderPassTraversalTransformer}.
 */
class RenderPassTraversalArtifactTest {

    private static final String REF_DIR_PROPERTY = "turboism.test.cubismRefDir";
    private static final String REF_DIR_ENV = "TURBOISM_CUBISM_REF";
    private static final String CUBISM_JAR = "Live2D_Cubism.jar";
    private static final String JAR_EXTENSION = ".jar";
    private static final String CLASS_EXTENSION = ".class";
    private static final String LIVE2D_PACKAGE_PREFIX = "com.live2d.";
    private static final String NOIDS_PACKAGE_PREFIX = "jp.";
    private static final String JOGAMP_PACKAGE_PREFIX = "com.jogamp.";
    private static final String TEST_RENDERER_INTERNAL = "com/live2d/graphics3d/component/TestStubRenderer";
    private static final String TEST_RENDERER_DOT = TEST_RENDERER_INTERNAL.replace('/', '.');
    private static final String TEST_GL3_INTERNAL = "com/live2d/graphics3d/TestStubGl3";
    private static final String TEST_GL3_DOT = TEST_GL3_INTERNAL.replace('/', '.');
    private static final String GROUP_RENDERER_DOT = RenderPassTraversalTarget.GROUP_RENDERER_OWNER.replace('/', '.');
    private static final String GENTITY_DOT = RenderPassTraversalTarget.ENTITY_OWNER.replace('/', '.');
    private static final String GCAMERA_DOT = "com.live2d.graphics3d.GCamera";
    private static final String RENDER_CONTEXT_DOT = "com.live2d.graphics3d.a";
    private static final String CRECT_DOT = "com.live2d.type.CRect";
    private static final String GMATERIAL_DOT = "com.live2d.graphics3d.material.GMaterial";
    private static final String GSHADER_DOT = "com.live2d.graphics3d.shader.GShader";
    private static final String TRANSPARENT_DOT = "com.live2d.graphics3d.shader.Transparent";
    private static final String GL3_DOT = "com.jogamp.opengl.GL3";
    private static final String ITERABLE_OWNER = "java/lang/Iterable";
    private static final String ITERATOR_METHOD = "iterator";
    private static final String COLLECTIONS_KT_OWNER = "kotlin/collections/CollectionsKt";
    private static final String LIST_OF_METHOD = "listOf";
    private static final String THROW_INDEX_OVERFLOW_METHOD = "throwIndexOverflow";
    private static final String GET_LIST_METHOD = "getList";
    private static final String GET_RENDERER_METHOD = "getRenderer";
    private static final int MAX_WALK_DEPTH = 2;
    private static final int EXPECTED_REVIEWED_VERSIONS =
            ReviewedHostArtifacts.reviewedCubismVersions().size();
    private static final int MAX_TRAVERSE_GET_RENDERER = 2;
    private static final int BENCH_ENTITY_COUNT = 48;
    private static final int WARMUP_FRAMES = 80;
    private static final int MEASURE_FRAMES = 60;
    private static final int GL_NO_ERROR = 0;
    private static final int GL_INVALID_OPERATION = 1282;

    @Test
    void everyReviewedArtifactRewritesFailClosedAndVerifiesBytecode() throws Exception {
        final Path root = referenceRoot();
        final List<RenderPassTraversalTarget> targets = RenderPassTraversalTarget.all();
        assertEquals(EXPECTED_REVIEWED_VERSIONS, targets.size());
        int checked = 0;

        for (final RenderPassTraversalTarget target : targets) {
            final Path artifact = resolveArtifact(root, target.version());
            if (!Files.isRegularFile(artifact)) {
                continue;
            }
            final HostArtifactDigest digest = HostArtifactDigest.from(artifact);
            assertEquals(target, RenderPassTraversalTarget.of(digest).orElseThrow(), target.version());

            try (HostClassLoader loader = hostLoader(artifact.getParent())) {
                final byte[] original = readClassBytes(artifact, target.renderSystemOwner());
                final ProtectionDomain domain = protectionDomain(artifact);
                final RenderPassTraversalTransformer transformer =
                        new RenderPassTraversalTransformer(loader, artifact, original, target);

                final byte[] rewritten =
                        transformer.transform(null, loader, target.renderSystemOwner(), null, domain, original);
                assertNotNull(rewritten, target.version() + ": " + transformer.failure());
                assertEquals(1, transformer.matches(), target.version());
                assertEquals(target.expectedSites(), transformer.sites(), target.version());
                assertNull(transformer.failure(), target.version());

                verifyRewrittenBytecode(target, rewritten);

                final ProtectionDomain signedDomain = Class.forName(
                                target.renderSystemOwner().replace('/', '.'), false, loader)
                        .getProtectionDomain();
                try (HostClassLoader verifyLoader = hostLoader(artifact.getParent())) {
                    verifyLoader.installOverride(target.renderSystemOwner().replace('/', '.'), rewritten, signedDomain);
                    final Class<?> verifiedClass =
                            Class.forName(target.renderSystemOwner().replace('/', '.'), true, verifyLoader);
                    assertTrue(verifiedClass.getDeclaredMethods().length > 0, target.version());
                }

                verifyFailClosedGuards(target, artifact, loader, domain, original);
            }
            checked++;
        }
        assertEquals(EXPECTED_REVIEWED_VERSIONS, checked, "expected every reviewed artifact under " + root);
    }

    @Test
    void composesWithClipMaskDeduplicationClipMaskProbeAndPerformanceProbeInAllOrders() throws Exception {
        final Path root = referenceRoot();
        int checked = 0;

        for (final String version : ReviewedHostArtifacts.reviewedCubismVersions()) {
            final Path artifact = resolveArtifact(root, version);
            if (!Files.isRegularFile(artifact)) {
                continue;
            }
            final HostArtifactDigest digest = HostArtifactDigest.from(artifact);
            final RenderPassTraversalTarget renderTarget =
                    RenderPassTraversalTarget.of(digest).orElseThrow();
            final String owner = renderTarget.renderSystemOwner();
            final ProtectionDomain domain = protectionDomain(artifact);

            try (HostClassLoader loader = hostLoader(artifact.getParent())) {
                final byte[] original = readClassBytes(artifact, owner);
                final Map<String, byte[]> refMap = Map.of(owner, original);
                final String topRenderDesc = ClipMaskDeduplicationTarget.renderMethodDescriptor(version);

                // Order 1: ClipMaskDeduplication -> RenderPassTraversal
                final ClipMaskDeduplicationTransformer clipFirst =
                        new ClipMaskDeduplicationTransformer(loader, artifact, owner, topRenderDesc, refMap);
                final RenderPassTraversalTransformer renderSecond =
                        new RenderPassTraversalTransformer(loader, artifact, original, renderTarget);
                final byte[] afterClip = clipFirst.transform(null, loader, owner, null, domain, original);
                assertNotNull(afterClip, version + " clip-first: " + clipFirst.failure());
                final byte[] clipThenRender = renderSecond.transform(null, loader, owner, null, domain, afterClip);
                assertNotNull(clipThenRender, version + " render-second: " + renderSecond.failure());
                assertEquals(renderTarget.expectedSites(), renderSecond.sites(), version);

                // Order 2: RenderPassTraversal -> ClipMaskDeduplication
                final RenderPassTraversalTransformer renderFirst =
                        new RenderPassTraversalTransformer(loader, artifact, original, renderTarget);
                final ClipMaskDeduplicationTransformer clipSecond =
                        new ClipMaskDeduplicationTransformer(loader, artifact, owner, topRenderDesc, refMap);
                final byte[] afterRender = renderFirst.transform(null, loader, owner, null, domain, original);
                assertNotNull(afterRender, version + " render-first: " + renderFirst.failure());
                final byte[] renderThenClip = clipSecond.transform(null, loader, owner, null, domain, afterRender);
                assertNotNull(renderThenClip, version + " clip-second: " + clipSecond.failure());
                assertEquals(3, clipSecond.sites(), version);

                // Order 3: ClipMaskProbe -> RenderPassTraversal and RenderPassTraversal -> ClipMaskProbe
                final ClipMaskProbeMethodTransformer probe = new ClipMaskProbeMethodTransformer(
                        loader, artifact, refMap, ClipMaskProbeTargets.forVersion(version));
                final byte[] afterProbe = probe.transform(null, loader, owner, null, domain, original);
                assertNotNull(afterProbe, version + " clip-mask-probe: " + probe.failure());
                final RenderPassTraversalTransformer renderAfterProbe =
                        new RenderPassTraversalTransformer(loader, artifact, original, renderTarget);
                final byte[] probeThenRender =
                        renderAfterProbe.transform(null, loader, owner, null, domain, afterProbe);
                assertNotNull(probeThenRender, version + " probe-then-render: " + renderAfterProbe.failure());
                final ClipMaskProbeMethodTransformer probeAfterRender = new ClipMaskProbeMethodTransformer(
                        loader, artifact, refMap, ClipMaskProbeTargets.forVersion(version));
                assertNotNull(
                        probeAfterRender.transform(null, loader, owner, null, domain, afterRender),
                        version + " render-then-probe: " + probeAfterRender.failure());

                // Order 4: RenderPassTraversal -> PerformanceProbe on 5.3.x
                byte[] finalVerifyBytes = clipThenRender;
                if (version.startsWith("5.3.")) {
                    final PerformanceProbeMethodTransformer perfProbe = new PerformanceProbeMethodTransformer(
                            loader, artifact, PerformanceProbeTargets.cubism5302());
                    final byte[] perfThenDone = perfProbe.transform(null, loader, owner, null, domain, clipThenRender);
                    assertNotNull(perfThenDone, version + " perf-probe-composition");
                    finalVerifyBytes = perfThenDone;
                }

                // Verify composed bytecode loads and passes JVM verification cleanly.
                final ProtectionDomain signedDomain =
                        Class.forName(owner.replace('/', '.'), false, loader).getProtectionDomain();
                try (HostClassLoader verifyLoader = hostLoader(artifact.getParent())) {
                    verifyLoader.installOverride(owner.replace('/', '.'), finalVerifyBytes, signedDomain);
                    final Class<?> verifiedClass = Class.forName(owner.replace('/', '.'), true, verifyLoader);
                    assertTrue(verifiedClass.getDeclaredMethods().length > 0, version);
                }
            }
            checked++;
        }
        assertEquals(EXPECTED_REVIEWED_VERSIONS, checked);
    }

    @Test
    void liveExecutionMatchesOriginalTraversalDrawOrderErrorParityAndReducesAllocations() throws Throwable {
        final Path root = referenceRoot();
        final Path jogl = joglArtifact(root);
        assumeTrue(jogl != null, "JOGL artifact unavailable for live render-system invocation");
        for (final String version : List.of("5.0.00", "5.2.03", "5.3.04", "5.3.05")) {
            final Path artifact = resolveArtifact(root, version);
            if (!Files.isRegularFile(artifact)) {
                continue;
            }
            verifyLiveParityOnVersion(artifact, jogl, version);
        }
    }

    private static void verifyLiveParityOnVersion(final Path artifact, final Path jogl, final String version)
            throws Throwable {
        final HostArtifactDigest digest = HostArtifactDigest.from(artifact);
        final RenderPassTraversalTarget target =
                RenderPassTraversalTarget.of(digest).orElseThrow();
        final byte[] originalBytes = readClassBytes(artifact, target.renderSystemOwner());
        final String ownerDot = target.renderSystemOwner().replace('/', '.');

        try (HostClassLoader origLoader = hostLoader(artifact.getParent(), jogl);
                HostClassLoader rewrLoader = hostLoader(artifact.getParent(), jogl);
                RenderPassTraversalBridge bridge = new RenderPassTraversalBridge()) {
            final RenderPassTraversalTransformer transformer =
                    new RenderPassTraversalTransformer(rewrLoader, artifact, originalBytes, target);
            final byte[] rewrittenBytes = transformer.transform(
                    null, rewrLoader, target.renderSystemOwner(), null, protectionDomain(artifact), originalBytes);
            assertNotNull(rewrittenBytes, version + ": " + transformer.failure());
            final ProtectionDomain signedDomain =
                    Class.forName(ownerDot, false, origLoader).getProtectionDomain();
            rewrLoader.installOverride(ownerDot, rewrittenBytes, signedDomain);

            final byte[] stubBytesOrig = buildTestRendererStub(origLoader);
            final byte[] stubBytesRewr = buildTestRendererStub(rewrLoader);
            final byte[] glStubOrig = buildTestGl3Stub();
            final byte[] glStubRewr = buildTestGl3Stub();
            origLoader.installExtraOverride(TEST_RENDERER_DOT, stubBytesOrig, signedDomain);
            rewrLoader.installExtraOverride(TEST_RENDERER_DOT, stubBytesRewr, signedDomain);
            origLoader.installExtraOverride(TEST_GL3_DOT, glStubOrig, signedDomain);
            rewrLoader.installExtraOverride(TEST_GL3_DOT, glStubRewr, signedDomain);
            final byte[] shaderDefaultsStub = buildShaderDefaultsStub();
            origLoader.installExtraOverride("com.live2d.graphics3d.shader.a", shaderDefaultsStub, signedDomain);
            rewrLoader.installExtraOverride("com.live2d.graphics3d.shader.a", shaderDefaultsStub, signedDomain);

            bridge.install();
            assertTrue(bridge.active());

            runOnEdt(() -> {
                for (final boolean singleEntityFlag : new boolean[] {false, true}) {
                    for (final boolean ignoreGroupBranch : new boolean[] {false, true}) {
                        final List<String> origLog =
                                executeScenario(origLoader, target, singleEntityFlag, ignoreGroupBranch, GL_NO_ERROR);
                        final List<String> rewrLog =
                                executeScenario(rewrLoader, target, singleEntityFlag, ignoreGroupBranch, GL_NO_ERROR);
                        assertEquals(
                                origLog,
                                rewrLog,
                                version + " d=" + singleEntityFlag + " ignoreGroup=" + ignoreGroupBranch);
                    }
                }

                // Verify fallback path when bridge is closed (properties absent).
                bridge.close();
                final List<String> origLogNoBridge = executeScenario(origLoader, target, false, false, GL_NO_ERROR);
                final List<String> rewrLogNoBridge = executeScenario(rewrLoader, target, false, false, GL_NO_ERROR);
                assertEquals(origLogNoBridge, rewrLogNoBridge, version + " fallback-without-bridge");

                // Verify exact GL error formatting parity when bridge is active.
                bridge.install();
                final List<String> origErr = executeScenario(origLoader, target, false, false, GL_INVALID_OPERATION);
                final List<String> rewrErr = executeScenario(rewrLoader, target, false, false, GL_INVALID_OPERATION);
                assertEquals(origErr, rewrErr, version + " glError-parity");

                if ("5.3.04".equals(version)) {
                    verifyAllocationReduction(origLoader, rewrLoader, target);
                }
            });
        }
    }

    private static List<String> executeScenario(
            final HostClassLoader loader,
            final RenderPassTraversalTarget target,
            final boolean singleEntityFlag,
            final boolean testIgnoreGroup,
            final int glErrorCode)
            throws Exception {
        final List<String> events = new ArrayList<>();
        final Class<?> renderSysClass = Class.forName(target.renderSystemOwner().replace('/', '.'), true, loader);
        final Class<?> entityClass = Class.forName(GENTITY_DOT, true, loader);
        final Class<?> groupRendererClass = Class.forName(GROUP_RENDERER_DOT, true, loader);
        final Class<?> stubClass = Class.forName(TEST_RENDERER_DOT, true, loader);
        final Class<?> materialClass = Class.forName(GMATERIAL_DOT, true, loader);
        final Class<?> shaderClass = Class.forName(GSHADER_DOT, true, loader);
        final Class<?> transparentEnum = Class.forName(TRANSPARENT_DOT, true, loader);
        final Class<?> cameraClass = Class.forName(GCAMERA_DOT, true, loader);
        final Class<?> ctxClass = Class.forName(RENDER_CONTEXT_DOT, true, loader);
        final Class<?> crectClass = Class.forName(CRECT_DOT, true, loader);
        Class.forName(GL3_DOT, false, loader);

        final Object opaqueEnum = Enum.valueOf(transparentEnum.asSubclass(Enum.class), "OPAQUE");
        final Object transEnum = Enum.valueOf(transparentEnum.asSubclass(Enum.class), "TRANSPARENT");

        final Object opaqueMat = createMaterial(materialClass, shaderClass, opaqueEnum);
        final Object transMat = createMaterial(materialClass, shaderClass, transEnum);

        final Object root = entityClass.getConstructor().newInstance();
        final Method unaryPlusChild = entityClass.getMethod("unaryPlus", entityClass);
        final Method setEnabled = entityClass.getMethod("setEnabled", boolean.class);
        final Method setRenderer = entityClass.getMethod(
                "setRenderer", Class.forName(RenderPassTraversalTarget.RENDERER_OWNER.replace('/', '.'), true, loader));

        // Build scene graph covering opaque, transparent, disabled subtree, disabled renderer, and groups.
        final Object e1 = createEntityWithRenderer(entityClass, stubClass, "opaque-1", opaqueMat, 1.0f, true, events);
        final Object e2 = createEntityWithRenderer(entityClass, stubClass, "trans-1", transMat, 2.0f, true, events);
        final Object eDisabled =
                createEntityWithRenderer(entityClass, stubClass, "disabled-parent", opaqueMat, 3.0f, true, events);
        setEnabled.invoke(eDisabled, false);
        final Object eDisabledChild =
                createEntityWithRenderer(entityClass, stubClass, "child-of-disabled", opaqueMat, 4.0f, true, events);
        unaryPlusChild.invoke(eDisabled, eDisabledChild);

        final Object eDisabledRenderer =
                createEntityWithRenderer(entityClass, stubClass, "disabled-renderer", opaqueMat, 5.0f, false, events);
        final Object eVisibleChild = createEntityWithRenderer(
                entityClass, stubClass, "child-of-disabled-renderer", transMat, 6.0f, true, events);
        unaryPlusChild.invoke(eDisabledRenderer, eVisibleChild);

        final Class<?> agrClass =
                Class.forName(RenderPassTraversalTarget.RENDERER_OWNER.replace('/', '.'), true, loader);
        final Constructor<?> groupSerializationCtor = sun.reflect.ReflectionFactory.getReflectionFactory()
                .newConstructorForSerialization(groupRendererClass, agrClass.getConstructor());
        final Object eGroupEnabled = entityClass.getConstructor().newInstance();
        final Object groupRendererEnabled = groupSerializationCtor.newInstance();
        setRenderer.invoke(eGroupEnabled, groupRendererEnabled);
        entityClass.getMethod("setSkipDrawing_testImpl", boolean.class).invoke(eGroupEnabled, true);
        final Object insideEnabledGroup =
                createEntityWithRenderer(entityClass, stubClass, "inside-enabled-group", opaqueMat, 7.0f, true, events);
        if (testIgnoreGroup && target.hasRecursiveTraverseMethod()) {
            entityClass.getMethod("setIgnoreGroupRenderer", boolean.class).invoke(insideEnabledGroup, true);
        }
        unaryPlusChild.invoke(eGroupEnabled, insideEnabledGroup);

        final Object eGroupDisabled = entityClass.getConstructor().newInstance();
        final Object groupRendererDisabled = groupSerializationCtor.newInstance();
        groupRendererDisabled.getClass().getMethod("setEnabled", boolean.class).invoke(groupRendererDisabled, false);
        setRenderer.invoke(eGroupDisabled, groupRendererDisabled);
        entityClass.getMethod("setSkipDrawing_testImpl", boolean.class).invoke(eGroupDisabled, true);
        final Object insideDisabledGroup = createEntityWithRenderer(
                entityClass, stubClass, "inside-disabled-group", opaqueMat, 8.0f, true, events);
        unaryPlusChild.invoke(eGroupDisabled, insideDisabledGroup);

        unaryPlusChild.invoke(root, e1);
        unaryPlusChild.invoke(root, e2);
        unaryPlusChild.invoke(root, eDisabled);
        unaryPlusChild.invoke(root, eDisabledRenderer);
        unaryPlusChild.invoke(root, eGroupEnabled);
        unaryPlusChild.invoke(root, eGroupDisabled);

        final Object targetRoot = singleEntityFlag ? e1 : root;
        final Constructor<?> sysCtor = renderSysClass.getConstructor(entityClass, boolean.class);
        final Object renderSys = sysCtor.newInstance(targetRoot, singleEntityFlag);

        final Class<?> stubGlClass = Class.forName(TEST_GL3_DOT, true, loader);
        final Object glProxy = stubGlClass.getConstructor(int.class).newInstance(glErrorCode);

        final Object camera = cameraClass.getConstructor(String.class).newInstance("test-cam");
        final Object renderCtx = findRenderContextCtor(ctxClass).newInstance(glProxy, camera, null);
        final Object crect = crectClass
                .getConstructor(int.class, int.class, int.class, int.class)
                .newInstance(0, 0, 100, 100);

        // Disable m flag (clipping pass) since no real GL texture/FBO is bound.
        renderSysClass.getMethod("d", boolean.class).invoke(null, false);
        // Test sorting flag k=false (runs sorting-info prep loop).
        renderSysClass.getMethod("b", boolean.class).invoke(null, false);

        final java.io.ByteArrayOutputStream errCapture = new java.io.ByteArrayOutputStream();
        final java.io.PrintStream previousErr = System.err;
        if (glErrorCode != GL_NO_ERROR) {
            System.setErr(new java.io.PrintStream(errCapture, true, java.nio.charset.StandardCharsets.UTF_8));
        }
        try {
            if (target.hasRecursiveTraverseMethod()) {
                final Method renderMethod =
                        renderSysClass.getMethod("a", ctxClass, crectClass, boolean.class, boolean.class);
                renderMethod.invoke(renderSys, renderCtx, crect, false, false);
            } else {
                final Method renderMethod = renderSysClass.getMethod("a", ctxClass, crectClass, boolean.class);
                renderMethod.invoke(renderSys, renderCtx, crect, false);
            }
        } finally {
            if (glErrorCode != GL_NO_ERROR) {
                System.setErr(previousErr);
                final String errText = errCapture.toString(java.nio.charset.StandardCharsets.UTF_8);
                final int lineEnd = errText.indexOf('\n');
                final String firstLine =
                        lineEnd >= 0 ? errText.substring(0, lineEnd).trim() : errText.trim();
                assertTrue(firstLine.contains("draw["), "expected draw label in GL error:\n" + errText);
                events.add(firstLine);
            }
        }
        return events;
    }

    private static void verifyAllocationReduction(
            final HostClassLoader origLoader, final HostClassLoader rewrLoader, final RenderPassTraversalTarget target)
            throws Exception {
        final com.sun.management.ThreadMXBean threadMx =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        assumeTrue(threadMx.isThreadAllocatedMemorySupported(), "ThreadMXBean allocation tracking unsupported");
        threadMx.setThreadAllocatedMemoryEnabled(true);

        final BenchScene origScene = new BenchScene(origLoader, target, BENCH_ENTITY_COUNT);
        final BenchScene rewrScene = new BenchScene(rewrLoader, target, BENCH_ENTITY_COUNT);

        for (int i = 0; i < WARMUP_FRAMES; i++) {
            origScene.renderOnce();
            rewrScene.renderOnce();
        }

        final long tid = Thread.currentThread().getId();
        final long origBefore = threadMx.getThreadAllocatedBytes(tid);
        for (int i = 0; i < MEASURE_FRAMES; i++) {
            origScene.renderOnce();
        }
        final long origBytes = threadMx.getThreadAllocatedBytes(tid) - origBefore;

        final long rewrBefore = threadMx.getThreadAllocatedBytes(tid);
        for (int i = 0; i < MEASURE_FRAMES; i++) {
            rewrScene.renderOnce();
        }
        final long rewrBytes = threadMx.getThreadAllocatedBytes(tid) - rewrBefore;

        assertTrue(
                rewrBytes * 2 < origBytes,
                "expected >50% allocation drop: origBytes=" + origBytes + " rewrBytes=" + rewrBytes);
    }

    private static final class BenchScene {
        private final Object renderSys;
        private final Method renderMethod;
        private final Object renderCtx;
        private final Object crect;

        private BenchScene(final HostClassLoader loader, final RenderPassTraversalTarget target, final int entityCount)
                throws Exception {
            final Class<?> renderSysClass =
                    Class.forName(target.renderSystemOwner().replace('/', '.'), true, loader);
            final Class<?> entityClass = Class.forName(GENTITY_DOT, true, loader);
            final Class<?> stubClass = Class.forName(TEST_RENDERER_DOT, true, loader);
            final Class<?> materialClass = Class.forName(GMATERIAL_DOT, true, loader);
            final Class<?> shaderClass = Class.forName(GSHADER_DOT, true, loader);
            final Class<?> transparentEnum = Class.forName(TRANSPARENT_DOT, true, loader);
            final Class<?> cameraClass = Class.forName(GCAMERA_DOT, true, loader);
            final Class<?> ctxClass = Class.forName(RENDER_CONTEXT_DOT, true, loader);
            final Class<?> crectClass = Class.forName(CRECT_DOT, true, loader);
            Class.forName(GL3_DOT, false, loader);

            final Object opaqueEnum = Enum.valueOf(transparentEnum.asSubclass(Enum.class), "OPAQUE");
            final Object mat = createMaterial(materialClass, shaderClass, opaqueEnum);
            final Object root = entityClass.getConstructor().newInstance();
            final Method unaryPlus = entityClass.getMethod("unaryPlus", entityClass);
            final List<String> sink = new ArrayList<>();
            for (int i = 0; i < entityCount; i++) {
                final Object parent =
                        createEntityWithRenderer(entityClass, stubClass, "p-" + i, mat, (float) i, true, sink);
                final Object child =
                        createEntityWithRenderer(entityClass, stubClass, "c-" + i, mat, (float) i + 0.5f, true, sink);
                unaryPlus.invoke(parent, child);
                unaryPlus.invoke(root, parent);
            }
            renderSysClass.getMethod("d", boolean.class).invoke(null, false);
            renderSysClass.getMethod("b", boolean.class).invoke(null, false);
            this.renderSys =
                    renderSysClass.getConstructor(entityClass, boolean.class).newInstance(root, false);
            this.renderMethod = renderSysClass.getMethod("a", ctxClass, crectClass, boolean.class, boolean.class);
            final Object camera = cameraClass.getConstructor(String.class).newInstance("bench-cam");
            final Class<?> stubGlClass = Class.forName(TEST_GL3_DOT, true, loader);
            final Object glProxy = stubGlClass.getConstructor(int.class).newInstance(GL_NO_ERROR);
            this.renderCtx = findRenderContextCtor(ctxClass).newInstance(glProxy, camera, null);
            this.crect = crectClass
                    .getConstructor(int.class, int.class, int.class, int.class)
                    .newInstance(0, 0, 100, 100);
        }

        private void renderOnce() throws Exception {
            renderMethod.invoke(renderSys, renderCtx, crect, false, false);
        }
    }

    private static Constructor<?> findRenderContextCtor(final Class<?> ctxClass) {
        for (final Constructor<?> ctor : ctxClass.getConstructors()) {
            if (ctor.getParameterCount() == 3) {
                return ctor;
            }
        }
        throw new IllegalStateException("3-arg constructor absent on " + ctxClass.getName());
    }

    private static Object createMaterial(
            final Class<?> materialClass, final Class<?> shaderClass, final Object transparentMode) throws Exception {
        final Object shader = shaderClass.getConstructor().newInstance();
        shaderClass.getMethod("setTransparent", transparentMode.getClass()).invoke(shader, transparentMode);
        final Object material = materialClass.getConstructor(String.class).newInstance("test-mat");
        materialClass.getMethod("setShader", shaderClass).invoke(material, shader);
        return material;
    }

    private static Object createEntityWithRenderer(
            final Class<?> entityClass,
            final Class<?> stubClass,
            final String label,
            final Object material,
            final float zOrder,
            final boolean rendererEnabled,
            final List<String> eventSink)
            throws Exception {
        final Object entity = entityClass.getConstructor().newInstance();
        final Object renderer = stubClass
                .getConstructor(String.class, List.class, float.class, Object.class)
                .newInstance(label, eventSink, zOrder, material);
        stubClass.getMethod("setEnabled", boolean.class).invoke(renderer, rendererEnabled);
        entityClass.getMethod("setRenderer", stubClass.getSuperclass()).invoke(entity, renderer);
        return entity;
    }

    private static byte[] buildTestRendererStub(final ClassLoader loader) {
        final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected ClassLoader getClassLoader() {
                return loader;
            }
        };
        cw.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                TEST_RENDERER_INTERNAL,
                null,
                RenderPassTraversalTarget.RENDERER_OWNER,
                null);
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "label", "Ljava/lang/String;", null, null);
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "sink", "Ljava/util/List;", null, null);
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "zOrder", "F", null, null);

        final MethodVisitor init = cw.visitMethod(
                Opcodes.ACC_PUBLIC, "<init>", "(Ljava/lang/String;Ljava/util/List;FLjava/lang/Object;)V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitVarInsn(Opcodes.ALOAD, 4);
        init.visitTypeInsn(Opcodes.CHECKCAST, "com/live2d/graphics3d/material/GMaterial");
        init.visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                RenderPassTraversalTarget.RENDERER_OWNER,
                "<init>",
                "(Lcom/live2d/graphics3d/material/GMaterial;)V",
                false);
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitVarInsn(Opcodes.ALOAD, 1);
        init.visitFieldInsn(Opcodes.PUTFIELD, TEST_RENDERER_INTERNAL, "label", "Ljava/lang/String;");
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitVarInsn(Opcodes.ALOAD, 2);
        init.visitFieldInsn(Opcodes.PUTFIELD, TEST_RENDERER_INTERNAL, "sink", "Ljava/util/List;");
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitVarInsn(Opcodes.FLOAD, 3);
        init.visitFieldInsn(Opcodes.PUTFIELD, TEST_RENDERER_INTERNAL, "zOrder", "F");
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        final MethodVisitor zOrderMv = cw.visitMethod(
                Opcodes.ACC_PUBLIC, "getSortingZOrder__testImpl", "(Lcom/live2d/graphics3d/GCamera;)F", null, null);
        zOrderMv.visitCode();
        zOrderMv.visitVarInsn(Opcodes.ALOAD, 0);
        zOrderMv.visitFieldInsn(Opcodes.GETFIELD, TEST_RENDERER_INTERNAL, "zOrder", "F");
        zOrderMv.visitInsn(Opcodes.FRETURN);
        zOrderMv.visitMaxs(0, 0);
        zOrderMv.visitEnd();

        final MethodVisitor renderMv =
                cw.visitMethod(Opcodes.ACC_PUBLIC, "render", "(Lcom/live2d/graphics3d/a;)V", null, null);
        renderMv.visitCode();
        renderMv.visitVarInsn(Opcodes.ALOAD, 0);
        renderMv.visitFieldInsn(Opcodes.GETFIELD, TEST_RENDERER_INTERNAL, "sink", "Ljava/util/List;");
        renderMv.visitVarInsn(Opcodes.ALOAD, 0);
        renderMv.visitFieldInsn(Opcodes.GETFIELD, TEST_RENDERER_INTERNAL, "label", "Ljava/lang/String;");
        renderMv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true);
        renderMv.visitInsn(Opcodes.POP);
        renderMv.visitInsn(Opcodes.RETURN);
        renderMv.visitMaxs(0, 0);
        renderMv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] buildTestGl3Stub() {
        final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                TEST_GL3_INTERNAL,
                null,
                "java/lang/Object",
                new String[] {"com/jogamp/opengl/GL3"});
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "errorCode", "I", null, null);
        cw.visitField(Opcodes.ACC_PRIVATE, "calls", "I", null, null);

        final MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(I)V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitVarInsn(Opcodes.ILOAD, 1);
        init.visitFieldInsn(Opcodes.PUTFIELD, TEST_GL3_INTERNAL, "errorCode", "I");
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        final MethodVisitor isEnabled = cw.visitMethod(Opcodes.ACC_PUBLIC, "glIsEnabled", "(I)Z", null, null);
        isEnabled.visitCode();
        isEnabled.visitInsn(Opcodes.ICONST_1);
        isEnabled.visitInsn(Opcodes.IRETURN);
        isEnabled.visitMaxs(0, 0);
        isEnabled.visitEnd();

        final MethodVisitor getIntv = cw.visitMethod(Opcodes.ACC_PUBLIC, "glGetIntegerv", "(I[II)V", null, null);
        getIntv.visitCode();
        getIntv.visitVarInsn(Opcodes.ALOAD, 2);
        getIntv.visitVarInsn(Opcodes.ILOAD, 3);
        getIntv.visitInsn(Opcodes.ICONST_1);
        getIntv.visitInsn(Opcodes.IASTORE);
        getIntv.visitInsn(Opcodes.RETURN);
        getIntv.visitMaxs(0, 0);
        getIntv.visitEnd();

        for (final String intVoid : new String[] {"glEnable", "glDisable", "glClear"}) {
            final MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, intVoid, "(I)V", null, null);
            mv.visitCode();
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        final MethodVisitor getRootGl =
                cw.visitMethod(Opcodes.ACC_PUBLIC, "getRootGL", "()Lcom/jogamp/opengl/GL;", null, null);
        getRootGl.visitCode();
        getRootGl.visitVarInsn(Opcodes.ALOAD, 0);
        getRootGl.visitInsn(Opcodes.ARETURN);
        getRootGl.visitMaxs(0, 0);
        getRootGl.visitEnd();

        final MethodVisitor depthMask = cw.visitMethod(Opcodes.ACC_PUBLIC, "glDepthMask", "(Z)V", null, null);
        depthMask.visitCode();
        depthMask.visitInsn(Opcodes.RETURN);
        depthMask.visitMaxs(0, 0);
        depthMask.visitEnd();

        final MethodVisitor getError = cw.visitMethod(Opcodes.ACC_PUBLIC, "glGetError", "()I", null, null);
        getError.visitCode();
        final org.objectweb.asm.Label noErr = new org.objectweb.asm.Label();
        getError.visitVarInsn(Opcodes.ALOAD, 0);
        getError.visitInsn(Opcodes.DUP);
        getError.visitFieldInsn(Opcodes.GETFIELD, TEST_GL3_INTERNAL, "calls", "I");
        getError.visitInsn(Opcodes.ICONST_1);
        getError.visitInsn(Opcodes.IADD);
        getError.visitInsn(Opcodes.DUP_X1);
        getError.visitFieldInsn(Opcodes.PUTFIELD, TEST_GL3_INTERNAL, "calls", "I");
        getError.visitInsn(Opcodes.ICONST_2);
        getError.visitJumpInsn(Opcodes.IF_ICMPNE, noErr);
        getError.visitVarInsn(Opcodes.ALOAD, 0);
        getError.visitFieldInsn(Opcodes.GETFIELD, TEST_GL3_INTERNAL, "errorCode", "I");
        getError.visitInsn(Opcodes.IRETURN);
        getError.visitLabel(noErr);
        getError.visitInsn(Opcodes.ICONST_0);
        getError.visitInsn(Opcodes.IRETURN);
        getError.visitMaxs(0, 0);
        getError.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void verifyRewrittenBytecode(final RenderPassTraversalTarget target, final byte[] rewritten) {
        final int[] counters = new int[7];
        new ClassReader(rewritten)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    final int access,
                                    final String name,
                                    final String descriptor,
                                    final String signature,
                                    final String[] exceptions) {
                                final boolean isImpl = RenderPassTraversalTarget.RENDER_IMPL_METHOD.equals(name)
                                        && target.renderImplDescriptor().equals(descriptor);
                                final boolean isTrav = target.hasRecursiveTraverseMethod()
                                        && RenderPassTraversalTarget.TRAVERSE_METHOD.equals(name)
                                        && RenderPassTraversalTarget.TRAVERSE_DESCRIPTOR.equals(descriptor);
                                if (!isImpl && !isTrav) {
                                    return null;
                                }
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitLdcInsn(final Object value) {
                                        if (isImpl
                                                && RenderPassTraversalBridge.PRE_DRAW_LABELS_PROPERTY.equals(value)) {
                                            counters[5]++;
                                        } else if (isImpl
                                                && RenderPassTraversalBridge.POST_DRAW_LABELS_PROPERTY.equals(value)) {
                                            counters[6]++;
                                        }
                                    }

                                    @Override
                                    public void visitMethodInsn(
                                            final int opcode,
                                            final String owner,
                                            final String invoked,
                                            final String desc,
                                            final boolean itf) {
                                        if (ITERABLE_OWNER.equals(owner) && ITERATOR_METHOD.equals(invoked)) {
                                            counters[0]++;
                                        } else if (COLLECTIONS_KT_OWNER.equals(owner)
                                                && (LIST_OF_METHOD.equals(invoked)
                                                        || THROW_INDEX_OVERFLOW_METHOD.equals(invoked))) {
                                            counters[1]++;
                                        } else if (RenderPassTraversalTarget.ENTITIES_OWNER.equals(owner)
                                                && GET_LIST_METHOD.equals(invoked)) {
                                            counters[2]++;
                                        } else if (isTrav
                                                && RenderPassTraversalTarget.ENTITY_OWNER.equals(owner)
                                                && GET_RENDERER_METHOD.equals(invoked)) {
                                            counters[3]++;
                                        }
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        assertEquals(0, counters[0], target.version() + " must retain zero Iterable.iterator() calls");
        assertEquals(0, counters[1], target.version() + " must retain zero listOf/throwIndexOverflow calls");
        assertTrue(counters[2] >= 1, target.version() + " must call Entities.getList()");
        if (target.hasRecursiveTraverseMethod()) {
            assertTrue(
                    counters[3] <= MAX_TRAVERSE_GET_RENDERER,
                    target.version() + " traverse getRenderer sites: " + counters[3]);
        }
        assertEquals(1, counters[5], target.version() + " must fetch pre-draw labels property once");
        assertEquals(1, counters[6], target.version() + " must fetch post-draw labels property once");
    }

    private static void verifyFailClosedGuards(
            final RenderPassTraversalTarget target,
            final Path artifact,
            final HostClassLoader loader,
            final ProtectionDomain domain,
            final byte[] original)
            throws Exception {
        final RenderPassTraversalTransformer t = new RenderPassTraversalTransformer(loader, artifact, original, target);
        try (HostClassLoader wrongLoader = hostLoader(artifact.getParent())) {
            assertNull(t.transform(null, wrongLoader, target.renderSystemOwner(), null, domain, original));
            assertNotNull(t.failure());
        }
        final RenderPassTraversalTransformer tDomain =
                new RenderPassTraversalTransformer(loader, artifact, original, target);
        assertNull(tDomain.transform(null, loader, target.renderSystemOwner(), null, null, original));
        assertNotNull(tDomain.failure());

        final RenderPassTraversalTransformer tShapeImpl =
                new RenderPassTraversalTransformer(loader, artifact, original, target);
        assertNull(tShapeImpl.transform(
                null,
                loader,
                target.renderSystemOwner(),
                null,
                domain,
                injectNop(original, RenderPassTraversalTarget.RENDER_IMPL_METHOD, target.renderImplDescriptor())));
        assertEquals(0, tShapeImpl.matches());
        assertNotNull(tShapeImpl.failure());

        if (target.hasRecursiveTraverseMethod()) {
            final RenderPassTraversalTransformer tShapeTrav =
                    new RenderPassTraversalTransformer(loader, artifact, original, target);
            assertNull(tShapeTrav.transform(
                    null,
                    loader,
                    target.renderSystemOwner(),
                    null,
                    domain,
                    injectNop(
                            original,
                            RenderPassTraversalTarget.TRAVERSE_METHOD,
                            RenderPassTraversalTarget.TRAVERSE_DESCRIPTOR)));
            assertEquals(0, tShapeTrav.matches());
            assertNotNull(tShapeTrav.failure());
        }
    }

    @Test
    void colorPoolTransformerCachesPresetValuesAndEliminatesPoolArrayListAcrossAllVersions() throws Throwable {
        final Path root = referenceRoot();
        final Path jogl = joglArtifact(root);
        final List<RenderPassTraversalTarget> targets = RenderPassTraversalTarget.all();
        int checked = 0;

        for (final RenderPassTraversalTarget target : targets) {
            final Path artifact = resolveArtifact(root, target.version());
            if (!Files.isRegularFile(artifact)) {
                continue;
            }
            final byte[] renderTexOrig =
                    readClassBytes(artifact, RenderTextureColorPoolTransformer.RENDER_TEXTURE_OWNER);
            final byte[] poolOrig = target.hasRecursiveTraverseMethod()
                    ? readClassBytes(artifact, RenderTextureColorPoolTransformer.POOL_LEASE_OWNER)
                    : null;

            try (HostClassLoader baselineLoader = hostLoader(artifact.getParent(), jogl);
                    HostClassLoader transformLoader = hostLoader(artifact.getParent(), jogl);
                    HostClassLoader rewrittenLoader = hostLoader(artifact.getParent(), jogl)) {
                final ProtectionDomain domain = protectionDomain(artifact);
                final RenderTextureColorPoolTransformer transformer =
                        new RenderTextureColorPoolTransformer(transformLoader, artifact, renderTexOrig, poolOrig);
                final byte[] renderTexPatched = transformer.transform(
                        null,
                        transformLoader,
                        RenderTextureColorPoolTransformer.RENDER_TEXTURE_OWNER,
                        null,
                        domain,
                        renderTexOrig);
                assertNotNull(renderTexPatched, target.version() + ": " + transformer.failure());
                assertNull(transformer.failure(), target.version());

                byte[] poolPatched = null;
                if (poolOrig != null) {
                    poolPatched = transformer.transform(
                            null,
                            transformLoader,
                            RenderTextureColorPoolTransformer.POOL_LEASE_OWNER,
                            null,
                            domain,
                            poolOrig);
                    assertNotNull(poolPatched, target.version() + ": " + transformer.failure());
                    assertNull(transformer.failure(), target.version());
                }
                assertEquals(transformer.expectedMatches(), transformer.matches(), target.version());
                assertEquals(transformer.expectedMatches(), transformer.sites(), target.version());

                final String renderTexDot = RenderTextureColorPoolTransformer.RENDER_TEXTURE_OWNER.replace('/', '.');
                final ProtectionDomain signedDomain =
                        Class.forName(renderTexDot, false, baselineLoader).getProtectionDomain();
                rewrittenLoader.installOverride(renderTexDot, renderTexPatched, signedDomain);
                if (poolPatched != null) {
                    assertEquals(
                            0,
                            countNewType(
                                    poolPatched,
                                    RenderTextureColorPoolTransformer.ACQUIRE_LEASE_METHOD,
                                    RenderTextureColorPoolTransformer.ACQUIRE_LEASE_DESCRIPTOR,
                                    "java/util/ArrayList"),
                            target.version() + " rendering/b.a must allocate zero ArrayList");
                    final String poolDot = RenderTextureColorPoolTransformer.POOL_LEASE_OWNER.replace('/', '.');
                    final ProtectionDomain poolSigned =
                            Class.forName(poolDot, false, baselineLoader).getProtectionDomain();
                    rewrittenLoader.installOverride(poolDot, poolPatched, poolSigned);
                }

                verifyRenderTextureParityAndZeroAlloc(
                        baselineLoader,
                        rewrittenLoader,
                        target.version(),
                        checked == 0 || target.version().endsWith(".05"));
                if (poolPatched != null && jogl != null) {
                    verifyPoolLeaseParityAndReducedAlloc(baselineLoader, rewrittenLoader, target.version());
                }
            }
            checked++;
        }
        assertEquals(EXPECTED_REVIEWED_VERSIONS, checked, "expected every reviewed artifact under " + root);
    }

    @Test
    void entityMaterialTransformerRewritesAllVersionsOptimizesClearValuesAndVerifiesStrict() throws Throwable {
        final Path root = referenceRoot();
        final Path jogl = joglArtifact(root);
        final List<RenderPassTraversalTarget> targets = RenderPassTraversalTarget.all();
        final List<Path> verifiedArtifacts = new ArrayList<>();

        for (final RenderPassTraversalTarget target : targets) {
            final Path artifact = resolveArtifact(root, target.version());
            if (!Files.isRegularFile(artifact)) {
                continue;
            }
            verifiedArtifacts.add(artifact);
            final byte[] entityOrig = readClassBytes(artifact, RenderPassTraversalTarget.ENTITY_OWNER);
            final byte[] materialOrig = readClassBytes(artifact, RenderEntityMaterialTransformer.MATERIAL_OWNER);
            final boolean isCubism53OrNewer = target.hasRecursiveTraverseMethod();

            try (HostClassLoader baselineLoader = hostLoader(artifact.getParent(), jogl);
                    HostClassLoader transformLoader = hostLoader(artifact.getParent(), jogl);
                    HostClassLoader rewrittenLoader = hostLoader(artifact.getParent(), jogl)) {
                final ProtectionDomain domain = protectionDomain(artifact);
                final byte[] matArg = isCubism53OrNewer ? materialOrig : null;
                final RenderEntityMaterialTransformer guardCheck =
                        new RenderEntityMaterialTransformer(transformLoader, artifact, entityOrig, matArg);
                assertNull(guardCheck.transform(
                        null, baselineLoader, RenderPassTraversalTarget.ENTITY_OWNER, null, domain, entityOrig));
                assertNotNull(guardCheck.failure());
                assertNull(guardCheck.transform(
                        null, transformLoader, RenderPassTraversalTarget.ENTITY_OWNER, null, null, entityOrig));
                assertNull(guardCheck.transform(
                        null,
                        transformLoader,
                        RenderPassTraversalTarget.ENTITY_OWNER,
                        null,
                        domain,
                        injectNop(entityOrig, GET_RENDERER_METHOD, "()Lcom/live2d/graphics3d/component/AGRenderer;")));
                if (isCubism53OrNewer) {
                    assertNull(guardCheck.transform(
                            null,
                            transformLoader,
                            RenderEntityMaterialTransformer.MATERIAL_OWNER,
                            null,
                            domain,
                            injectNop(
                                    materialOrig,
                                    RenderEntityMaterialTransformer.CLEAR_VALUES_METHOD,
                                    RenderEntityMaterialTransformer.CLEAR_VALUES_DESCRIPTOR)));
                } else {
                    assertNull(
                            guardCheck.transform(
                                    null,
                                    transformLoader,
                                    RenderEntityMaterialTransformer.MATERIAL_OWNER,
                                    null,
                                    domain,
                                    materialOrig),
                            target.version() + " must keep GMaterial clearValues disabled before 5.3.00");
                }
                assertEquals(0, guardCheck.matches(), target.version());

                final RenderEntityMaterialTransformer transformer =
                        new RenderEntityMaterialTransformer(transformLoader, artifact, entityOrig, matArg);
                final byte[] entityPatched = transformer.transform(
                        null, transformLoader, RenderPassTraversalTarget.ENTITY_OWNER, null, domain, entityOrig);
                assertNotNull(entityPatched, target.version() + ": " + transformer.failure());
                assertEquals(0, countEntityGetRendererAreEqual(entityPatched), target.version());
                assertUnrelatedMethodsKeepShape(
                        entityOrig,
                        entityPatched,
                        RenderPassTraversalTarget.ENTITY_OWNER,
                        GET_RENDERER_METHOD,
                        "()Lcom/live2d/graphics3d/component/AGRenderer;",
                        target.version());

                final String entityDot = GENTITY_DOT;
                rewrittenLoader.installOverride(
                        entityDot,
                        entityPatched,
                        Class.forName(entityDot, false, baselineLoader).getProtectionDomain());

                byte[] materialPatched = null;
                if (isCubism53OrNewer) {
                    materialPatched = transformer.transform(
                            null,
                            transformLoader,
                            RenderEntityMaterialTransformer.MATERIAL_OWNER,
                            null,
                            domain,
                            materialOrig);
                    assertNotNull(materialPatched, target.version() + ": " + transformer.failure());
                    assertEquals(0, countCollectionsToListInClearValues(materialPatched), target.version());
                    assertUnrelatedMethodsKeepShape(
                            materialOrig,
                            materialPatched,
                            RenderEntityMaterialTransformer.MATERIAL_OWNER,
                            RenderEntityMaterialTransformer.CLEAR_VALUES_METHOD,
                            RenderEntityMaterialTransformer.CLEAR_VALUES_DESCRIPTOR,
                            target.version());
                    rewrittenLoader.installOverride(
                            GMATERIAL_DOT,
                            materialPatched,
                            Class.forName(GMATERIAL_DOT, false, baselineLoader).getProtectionDomain());
                }
                assertEquals(transformer.expectedMatches(), transformer.matches(), target.version());
                assertEquals(transformer.expectedMatches(), transformer.sites(), target.version());

                verifyEntityGetRendererParity(baselineLoader, rewrittenLoader, target.version());
                if (materialPatched != null) {
                    verifyMaterialClearValuesParityAndReducedAlloc(baselineLoader, rewrittenLoader, target.version());
                }
            }
        }
        assertEquals(
                EXPECTED_REVIEWED_VERSIONS, verifiedArtifacts.size(), "expected every reviewed artifact under " + root);
        verifyEntityMaterialStrictBytecode(verifiedArtifacts);
    }

    private static void verifyEntityGetRendererParity(
            final ClassLoader baseLoader, final ClassLoader patLoader, final String version) throws Exception {
        final Class<?> baseEntityClass = Class.forName(GENTITY_DOT, true, baseLoader);
        final Class<?> patEntityClass = Class.forName(GENTITY_DOT, true, patLoader);
        final Object baseEmpty = baseEntityClass.getConstructor().newInstance();
        final Object patEmpty = patEntityClass.getConstructor().newInstance();
        assertNull(baseEntityClass.getMethod(GET_RENDERER_METHOD).invoke(baseEmpty), version);
        assertNull(patEntityClass.getMethod(GET_RENDERER_METHOD).invoke(patEmpty), version);
        assertNull(patEntityClass.getMethod(GET_RENDERER_METHOD).invoke(patEmpty), version);

        final Object baseWithRenderer = baseEntityClass.getConstructor().newInstance();
        final Object patWithRenderer = patEntityClass.getConstructor().newInstance();
        final Class<?> baseCompClass = Class.forName("com.live2d.graphics3d.component.GComponent", true, baseLoader);
        final Class<?> patCompClass = Class.forName("com.live2d.graphics3d.component.GComponent", true, patLoader);
        final Object baseMeshRenderer = Class.forName("com.live2d.graphics3d.component.GMeshRenderer", true, baseLoader)
                .getConstructor()
                .newInstance();
        final Object patMeshRenderer = Class.forName("com.live2d.graphics3d.component.GMeshRenderer", true, patLoader)
                .getConstructor()
                .newInstance();
        baseEntityClass.getMethod("unaryPlus", baseCompClass).invoke(baseWithRenderer, baseMeshRenderer);
        patEntityClass.getMethod("unaryPlus", patCompClass).invoke(patWithRenderer, patMeshRenderer);
        assertEquals(
                baseEntityClass.getMethod(GET_RENDERER_METHOD).invoke(baseWithRenderer) != null,
                patEntityClass.getMethod(GET_RENDERER_METHOD).invoke(patWithRenderer) != null,
                version);
        assertEquals(
                patMeshRenderer, patEntityClass.getMethod(GET_RENDERER_METHOD).invoke(patWithRenderer), version);
    }

    @SuppressWarnings("unchecked")
    private static void verifyMaterialClearValuesParityAndReducedAlloc(
            final ClassLoader baseLoader, final ClassLoader patLoader, final String version) throws Throwable {
        final Class<?> baseMatClass = Class.forName(GMATERIAL_DOT, true, baseLoader);
        final Class<?> patMatClass = Class.forName(GMATERIAL_DOT, true, patLoader);
        final Object baseMat = baseMatClass.getConstructor().newInstance();
        final Object patMat = patMatClass.getConstructor().newInstance();
        final Method baseGetMap = baseMatClass.getMethod("getUniformMap$core");
        final Method patGetMap = patMatClass.getMethod("getUniformMap$core");
        final Method baseClear = baseMatClass.getMethod("clearValues", kotlin.jvm.functions.Function2.class);
        final Method patClear = patMatClass.getMethod("clearValues", kotlin.jvm.functions.Function2.class);

        final var baseMap = (java.util.HashMap<kotlin.Pair<String, Integer>, Object>) baseGetMap.invoke(baseMat);
        final var patMap = (java.util.HashMap<kotlin.Pair<String, Integer>, Object>) patGetMap.invoke(patMat);
        for (int i = 0; i < 16; i++) {
            final var key = new kotlin.Pair<>("u" + i, i - 8);
            baseMap.put(key, i);
            patMap.put(key, i);
        }
        final kotlin.jvm.functions.Function2<String, Integer, Boolean> removeNegative = (name, id) -> id < 0;
        baseClear.invoke(baseMat, removeNegative);
        patClear.invoke(patMat, removeNegative);
        assertEquals(baseMap, patMap, version + " clearValues parity");

        final var lookup = java.lang.invoke.MethodHandles.publicLookup();
        final var clearType =
                java.lang.invoke.MethodType.methodType(void.class, Object.class, kotlin.jvm.functions.Function2.class);
        final java.lang.invoke.MethodHandle baseClearHandle =
                lookup.unreflect(baseClear).asType(clearType);
        final java.lang.invoke.MethodHandle patClearHandle =
                lookup.unreflect(patClear).asType(clearType);
        final kotlin.jvm.functions.Function2<String, Integer, Boolean> removeNegativeIds = (n, id) -> id < 0;
        final List<kotlin.Pair<String, Integer>> keys = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            keys.add(new kotlin.Pair<>("u" + i, i - 12));
        }

        final com.sun.management.ThreadMXBean allocMx =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        final long tid = Thread.currentThread().getId();
        for (int w = 0; w < 200; w++) {
            baseMap.clear();
            patMap.clear();
            for (final var k : keys) {
                baseMap.put(k, 1);
                patMap.put(k, 1);
            }
            baseClearHandle.invokeExact(baseMat, removeNegativeIds);
            patClearHandle.invokeExact(patMat, removeNegativeIds);
        }
        allocMx.getThreadAllocatedBytes(tid);
        long baseAllocTotal = 0L;
        long patAllocTotal = 0L;
        for (int r = 0; r < 64; r++) {
            baseMap.clear();
            for (final var k : keys) baseMap.put(k, 1);
            final long b0 = allocMx.getThreadAllocatedBytes(tid);
            baseClearHandle.invokeExact(baseMat, removeNegativeIds);
            baseAllocTotal += allocMx.getThreadAllocatedBytes(tid) - b0;

            patMap.clear();
            for (final var k : keys) patMap.put(k, 1);
            final long p0 = allocMx.getThreadAllocatedBytes(tid);
            patClearHandle.invokeExact(patMat, removeNegativeIds);
            patAllocTotal += allocMx.getThreadAllocatedBytes(tid) - p0;
        }
        assertTrue(
                patAllocTotal < baseAllocTotal,
                version + " in-place clearValues must allocate less than CollectionsKt.toList baseline: pat="
                        + patAllocTotal + " base=" + baseAllocTotal);
    }

    private static void verifyEntityMaterialStrictBytecode(final List<Path> artifacts) throws Exception {
        final Path manifest = Files.createTempFile("entity-material-verify-all-", ".txt");
        try {
            Files.writeString(
                    manifest,
                    String.join(
                            "\n",
                            artifacts.stream()
                                    .map(p -> p.toAbsolutePath().toString())
                                    .toList()));
            final String javaExe =
                    Path.of(System.getProperty("java.home"), "bin", "java").toString();
            final Process process = new ProcessBuilder(
                            javaExe,
                            "-Xverify:all",
                            "-cp",
                            System.getProperty("java.class.path"),
                            StrictEntityMaterialVerifierMain.class.getName(),
                            manifest.toString())
                    .redirectErrorStream(true)
                    .start();
            final String output = new String(process.getInputStream().readAllBytes());
            assertTrue(process.waitFor(90L, java.util.concurrent.TimeUnit.SECONDS), "-Xverify:all timed out");
            assertEquals(0, process.exitValue(), "-Xverify:all failed:\n" + output);
            assertTrue(output.contains("VERIFIED_ENTITY_MATERIAL=" + artifacts.size()), output);
        } finally {
            Files.deleteIfExists(manifest);
        }
    }

    /** Child entrypoint verifying patched {@code GEntity} and {@code GMaterial} under {@code -Xverify:all}. */
    public static final class StrictEntityMaterialVerifierMain {
        public static void main(final String[] args) throws Exception {
            final List<String> lines = Files.readAllLines(Path.of(args[0]));
            int verified = 0;
            for (final String line : lines) {
                if (line.isBlank()) continue;
                final Path artifact = Path.of(line.trim());
                final RenderPassTraversalTarget target = RenderPassTraversalTarget.of(HostArtifactDigest.from(artifact))
                        .orElseThrow();
                final byte[] eOrig = readClassBytes(artifact, RenderPassTraversalTarget.ENTITY_OWNER);
                final byte[] mOrig = readClassBytes(artifact, RenderEntityMaterialTransformer.MATERIAL_OWNER);
                final byte[] mArg = target.hasRecursiveTraverseMethod() ? mOrig : null;
                try (HostClassLoader tLoader = hostLoader(artifact.getParent());
                        HostClassLoader runLoader = hostLoader(artifact.getParent())) {
                    final ProtectionDomain dom = protectionDomain(artifact);
                    final ProtectionDomain entitySignedDom =
                            Class.forName(GENTITY_DOT, false, tLoader).getProtectionDomain();
                    final RenderEntityMaterialTransformer transformer =
                            new RenderEntityMaterialTransformer(tLoader, artifact, eOrig, mArg);
                    final byte[] ePatched = transformer.transform(
                            null, tLoader, RenderPassTraversalTarget.ENTITY_OWNER, null, dom, eOrig);
                    if (ePatched == null) throw new AssertionError("GEntity failed " + artifact);
                    runLoader.installOverride(GENTITY_DOT, ePatched, entitySignedDom);
                    if (mArg != null) {
                        final ProtectionDomain matSignedDom =
                                Class.forName(GMATERIAL_DOT, false, tLoader).getProtectionDomain();
                        final byte[] mPatched = transformer.transform(
                                null, tLoader, RenderEntityMaterialTransformer.MATERIAL_OWNER, null, dom, mOrig);
                        if (mPatched == null) throw new AssertionError("GMaterial failed " + artifact);
                        runLoader.installOverride(GMATERIAL_DOT, mPatched, matSignedDom);
                        Class.forName(GMATERIAL_DOT, true, runLoader);
                    }
                    Class.forName(GENTITY_DOT, true, runLoader);
                }
                verified++;
            }
            System.out.println("VERIFIED_ENTITY_MATERIAL=" + verified);
        }
    }

    private static int countEntityGetRendererAreEqual(final byte[] bytes) {
        final int[] count = {0};
        new ClassReader(bytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access, String name, String desc, String sig, String[] ex) {
                                if (!GET_RENDERER_METHOD.equals(name)) return null;
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitMethodInsn(
                                            int op, String owner, String called, String d, boolean itf) {
                                        if ("kotlin/jvm/internal/Intrinsics".equals(owner)
                                                && "areEqual".equals(called)) {
                                            count[0]++;
                                        }
                                    }
                                };
                            }
                        },
                        0);
        return count[0];
    }

    private static int countCollectionsToListInClearValues(final byte[] bytes) {
        final int[] count = {0};
        new ClassReader(bytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access, String name, String desc, String sig, String[] ex) {
                                if (!RenderEntityMaterialTransformer.CLEAR_VALUES_METHOD.equals(name)) return null;
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitMethodInsn(
                                            int op, String owner, String called, String d, boolean itf) {
                                        if (COLLECTIONS_KT_OWNER.equals(owner) && "toList".equals(called)) {
                                            count[0]++;
                                        }
                                    }
                                };
                            }
                        },
                        0);
        return count[0];
    }

    private static void assertUnrelatedMethodsKeepShape(
            final byte[] original,
            final byte[] patched,
            final String owner,
            final String targetName,
            final String targetDesc,
            final String version) {
        new ClassReader(original)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access, String name, String desc, String sig, String[] ex) {
                                if (!targetName.equals(name) || !targetDesc.equals(desc)) {
                                    assertEquals(
                                            ReviewedMethodShape.read(original, owner, name, desc),
                                            ReviewedMethodShape.read(patched, owner, name, desc),
                                            version + ":" + owner + "." + name);
                                }
                                return null;
                            }
                        },
                        ClassReader.SKIP_DEBUG);
    }

    private static int countNewType(
            final byte[] bytes, final String methodName, final String methodDescriptor, final String typeInternalName) {
        final int[] count = {0};
        new ClassReader(bytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    final int access,
                                    final String name,
                                    final String descriptor,
                                    final String signature,
                                    final String[] exceptions) {
                                if (!methodName.equals(name) || !methodDescriptor.equals(descriptor)) {
                                    return null;
                                }
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitTypeInsn(final int opcode, final String type) {
                                        if (opcode == Opcodes.NEW && typeInternalName.equals(type)) {
                                            count[0]++;
                                        }
                                    }
                                };
                            }
                        },
                        0);
        return count[0];
    }

    private static void verifyRenderTextureParityAndZeroAlloc(
            final ClassLoader baselineLoader,
            final ClassLoader rewrittenLoader,
            final String version,
            final boolean measureAllocations)
            throws Throwable {
        final String renderTexDot = RenderTextureColorPoolTransformer.RENDER_TEXTURE_OWNER.replace('/', '.');
        final String enumDot = RenderTextureColorPoolTransformer.PRESET_COLOR_TYPE_OWNER.replace('/', '.');
        final Class<?> baseClass = Class.forName(renderTexDot, true, baselineLoader);
        final Class<?> patClass = Class.forName(renderTexDot, true, rewrittenLoader);
        final Class<?> baseEnum = Class.forName(enumDot, true, baselineLoader);
        final Class<?> patEnum = Class.forName(enumDot, true, rewrittenLoader);

        final Object baseTex = baseClass.getDeclaredConstructor().newInstance();
        final Object patTex = patClass.getDeclaredConstructor().newInstance();
        final Method baseGet = baseClass.getMethod("getPresetColorType");
        final Method patGet = patClass.getMethod("getPresetColorType");
        final Method baseSetPreset = baseClass.getMethod("setPresetColorType", baseEnum);
        final Method patSetPreset = patClass.getMethod("setPresetColorType", patEnum);

        final Object[] baseConsts = baseEnum.getEnumConstants();
        final Object[] patConsts = patEnum.getEnumConstants();
        assertEquals(baseConsts.length, patConsts.length, version);
        for (int i = 0; i < baseConsts.length; i++) {
            baseSetPreset.invoke(baseTex, baseConsts[i]);
            patSetPreset.invoke(patTex, patConsts[i]);
            final String baseName = ((Enum<?>) baseGet.invoke(baseTex)).name();
            final String patName = ((Enum<?>) patGet.invoke(patTex)).name();
            assertEquals(baseName, patName, version + " constant " + baseConsts[i]);
        }

        // Verify live mutation without touching setPresetColorType still falls back to NOT_SELECTED.
        final Class<?> internalFmtClass =
                Class.forName("com.live2d.graphics3d.texture.InternalFormatType", true, baselineLoader);
        final Class<?> patInternalFmtClass =
                Class.forName("com.live2d.graphics3d.texture.InternalFormatType", true, rewrittenLoader);
        final Object firstBaseFmt = internalFmtClass.getEnumConstants()[0];
        final Object firstPatFmt = patInternalFmtClass.getEnumConstants()[0];
        baseClass.getMethod("setInternalFormat", internalFmtClass).invoke(baseTex, firstBaseFmt);
        patClass.getMethod("setInternalFormat", patInternalFmtClass).invoke(patTex, firstPatFmt);
        assertEquals(
                ((Enum<?>) baseGet.invoke(baseTex)).name(),
                ((Enum<?>) patGet.invoke(patTex)).name(),
                version + " live internalFormat mutation parity");

        if (measureAllocations) {
            final com.sun.management.ThreadMXBean allocMx =
                    (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
            baseSetPreset.invoke(baseTex, baseConsts[0]);
            patSetPreset.invoke(patTex, patConsts[0]);
            final var lookup = java.lang.invoke.MethodHandles.publicLookup();
            final var getType = java.lang.invoke.MethodType.methodType(Object.class, Object.class);
            final java.lang.invoke.MethodHandle baseGetHandle =
                    lookup.unreflect(baseGet).asType(getType);
            final java.lang.invoke.MethodHandle patGetHandle =
                    lookup.unreflect(patGet).asType(getType);
            for (int w = 0; w < 5000; w++) {
                final Object bIgnored = (Object) baseGetHandle.invokeExact(baseTex);
                final Object pIgnored = (Object) patGetHandle.invokeExact(patTex);
            }
            final long tid = Thread.currentThread().getId();
            allocMx.getThreadAllocatedBytes(tid);
            final long baseBefore = allocMx.getThreadAllocatedBytes(tid);
            for (int i = 0; i < 1000; i++) {
                final Object bIgnored = (Object) baseGetHandle.invokeExact(baseTex);
            }
            final long baseAlloc = allocMx.getThreadAllocatedBytes(tid) - baseBefore;
            final long patBefore = allocMx.getThreadAllocatedBytes(tid);
            for (int i = 0; i < 1000; i++) {
                final Object pIgnored = (Object) patGetHandle.invokeExact(patTex);
            }
            final long patAlloc = allocMx.getThreadAllocatedBytes(tid) - patBefore;
            assertTrue(
                    baseAlloc > 0L,
                    version + " baseline GRenderTexture.getPresetColorType() should allocate PresetColorType[]");
            assertEquals(
                    0L,
                    patAlloc,
                    version + " GRenderTexture.getPresetColorType() must allocate 0 bytes (baseAlloc="
                            + baseAlloc
                            + ")");
        }
    }

    @SuppressWarnings("unchecked")
    private static void verifyPoolLeaseParityAndReducedAlloc(
            final HostClassLoader baselineLoader, final HostClassLoader rewrittenLoader, final String version)
            throws Throwable {
        final String poolDot = RenderTextureColorPoolTransformer.POOL_LEASE_OWNER.replace('/', '.');
        final Class<?> basePoolClass = Class.forName(poolDot, true, baselineLoader);
        final Class<?> patPoolClass = Class.forName(poolDot, true, rewrittenLoader);

        final Class<?> baseEnum = Class.forName(
                RenderTextureColorPoolTransformer.PRESET_COLOR_TYPE_OWNER.replace('/', '.'), true, baselineLoader);
        final Class<?> patEnum = Class.forName(
                RenderTextureColorPoolTransformer.PRESET_COLOR_TYPE_OWNER.replace('/', '.'), true, rewrittenLoader);

        final var baseArg = newPoolTestArgs(baselineLoader);
        final var patArg = newPoolTestArgs(rewrittenLoader);

        final Class<?> baseCtxType = Class.forName("com.live2d.graphics3d.a", true, baselineLoader);
        final Class<?> patCtxType = Class.forName("com.live2d.graphics3d.a", true, rewrittenLoader);
        final Method baseAcquire =
                basePoolClass.getMethod("a", baseCtxType, int.class, int.class, baseEnum, boolean.class);
        final Method patAcquire = patPoolClass.getMethod("a", patCtxType, int.class, int.class, patEnum, boolean.class);
        final Object basePool = basePoolClass.getField("a").get(null);
        final Object patPool = patPoolClass.getField("a").get(null);

        final Object baseArgb = baseEnum.getEnumConstants()[1];
        final Object patArgb = patEnum.getEnumConstants()[1];
        final int[][] seedDims = {
            {512, 512},
            {256, 256},
            {320, 320},
            {320, 320},
            {320, 320},
            {320, 320},
            {320, 320},
            {320, 320},
            {320, 320},
            {320, 320}
        };
        seedPoolCandidates(baselineLoader, baseArg.rootGl(), baseArgb, seedDims);
        seedPoolCandidates(rewrittenLoader, patArg.rootGl(), patArgb, seedDims);

        // Request 280x280: 256x256 is too small, first 320x320 has minimal excess (40*40=1600 < 232*232).
        final var lookup = java.lang.invoke.MethodHandles.publicLookup();
        final var acquireType = java.lang.invoke.MethodType.methodType(
                Object.class, Object.class, Object.class, int.class, int.class, Object.class, boolean.class);
        final java.lang.invoke.MethodHandle baseHandle =
                lookup.unreflect(baseAcquire).asType(acquireType);
        final java.lang.invoke.MethodHandle patHandle =
                lookup.unreflect(patAcquire).asType(acquireType);

        final Object baseLease = (Object) baseHandle.invokeExact(basePool, baseArg.context(), 280, 280, baseArgb, true);
        final Object patLease = (Object) patHandle.invokeExact(patPool, patArg.context(), 280, 280, patArgb, true);
        final Object baseTex = baseLease.getClass().getMethod("b").invoke(baseLease);
        final Object patTex = patLease.getClass().getMethod("b").invoke(patLease);
        assertEquals(
                baseTex.getClass().getMethod("getWidth").invoke(baseTex),
                patTex.getClass().getMethod("getWidth").invoke(patTex),
                version);
        assertEquals(
                baseTex.getClass().getMethod("getHeight").invoke(baseTex),
                patTex.getClass().getMethod("getHeight").invoke(patTex),
                version);

        // Warm up acquire MethodHandles before comparing single-pass vs baseline candidate-list allocations.
        for (int w = 0; w < 6; w++) {
            final Object bWarm = (Object) baseHandle.invokeExact(basePool, baseArg.context(), 280, 280, baseArgb, true);
            final Object pWarm = (Object) patHandle.invokeExact(patPool, patArg.context(), 280, 280, patArgb, true);
            assertNotNull(bWarm);
            assertNotNull(pWarm);
        }
        final com.sun.management.ThreadMXBean allocMx =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        final long tid = Thread.currentThread().getId();
        allocMx.getThreadAllocatedBytes(tid);
        final long baseStart = allocMx.getThreadAllocatedBytes(tid);
        final Object bMeasured = (Object) baseHandle.invokeExact(basePool, baseArg.context(), 280, 280, baseArgb, true);
        final long baseAlloc = allocMx.getThreadAllocatedBytes(tid) - baseStart;
        final long patStart = allocMx.getThreadAllocatedBytes(tid);
        final Object pMeasured = (Object) patHandle.invokeExact(patPool, patArg.context(), 280, 280, patArgb, true);
        final long patAlloc = allocMx.getThreadAllocatedBytes(tid) - patStart;
        assertNotNull(bMeasured);
        assertNotNull(pMeasured);
        assertTrue(
                patAlloc < baseAlloc,
                version + " pool lease must allocate less than ArrayList baseline: pat=" + patAlloc + " base="
                        + baseAlloc);
    }

    private static Path resolveArtifact(final Path root, final String version) {
        final String override = System.getProperty("turboism.test.cubism" + version.replace(".", "") + "Jar", "");
        if (!override.isBlank()) {
            return Path.of(override);
        }
        return root.resolve(version).resolve(CUBISM_JAR);
    }

    private record PoolTestArgs(Object context, Object rootGl) {}

    private static PoolTestArgs newPoolTestArgs(final HostClassLoader loader) throws Exception {
        final Class<?> gl3Interface = Class.forName("com.jogamp.opengl.GL3", true, loader);
        final ProtectionDomain domain = Class.forName(
                        RenderTextureColorPoolTransformer.POOL_LEASE_OWNER.replace('/', '.'), false, loader)
                .getProtectionDomain();
        loader.installExtraOverride(TEST_GL3_DOT, buildTestGl3Stub(), domain);
        final Object gl3 = Class.forName(TEST_GL3_DOT, true, loader)
                .getConstructor(int.class)
                .newInstance(GL_NO_ERROR);
        final Class<?> ctxClass = Class.forName("com.live2d.graphics3d.a", true, loader);
        final Object ctx = getUnsafe().allocateInstance(ctxClass);
        final var field = findFirstField(ctxClass, gl3Interface);
        field.setAccessible(true);
        field.set(ctx, gl3);
        return new PoolTestArgs(ctx, gl3);
    }

    private static sun.misc.Unsafe getUnsafe() throws Exception {
        final var f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (sun.misc.Unsafe) f.get(null);
    }

    private static java.lang.reflect.Field findFirstField(final Class<?> type, final Class<?> fieldType) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (final var f : c.getDeclaredFields()) {
                if (fieldType.isAssignableFrom(f.getType())) return f;
            }
        }
        throw new IllegalStateException("field missing: " + fieldType);
    }

    @SuppressWarnings("unchecked")
    private static void seedPoolCandidates(
            final ClassLoader loader, final Object rootGl, final Object presetColor, final int[][] dims)
            throws Exception {
        final Class<?> poolClass =
                Class.forName(RenderTextureColorPoolTransformer.POOL_LEASE_OWNER.replace('/', '.'), true, loader);
        final var mapField = poolClass.getDeclaredField("c");
        mapField.setAccessible(true);
        final var map = (java.util.HashMap<Object, Object>) mapField.get(null);
        final Class<?> entryClass = Class.forName("com.live2d.graphics3d.rendering.b$a", true, loader);
        final Class<?> glInterface = Class.forName("com.jogamp.opengl.GL", true, loader);
        final Object entry = entryClass.getDeclaredConstructor(glInterface).newInstance(rootGl);
        map.put(rootGl, entry);
        final var freeSet =
                (java.util.HashSet<Object>) entryClass.getMethod("e").invoke(entry);
        final Class<?> leaseClass = Class.forName("com.live2d.graphics3d.rendering.a", true, loader);
        final Class<?> presetClass = Class.forName(
                RenderTextureColorPoolTransformer.PRESET_COLOR_TYPE_OWNER.replace('/', '.'), true, loader);
        for (final int[] d : dims) {
            final Object lease = leaseClass.getDeclaredConstructor(glInterface).newInstance(rootGl);
            final Object tex = leaseClass.getMethod("b").invoke(lease);
            tex.getClass().getMethod("setWidth", int.class).invoke(tex, d[0]);
            tex.getClass().getMethod("setHeight", int.class).invoke(tex, d[1]);
            tex.getClass().getMethod("setPresetColorType", presetClass).invoke(tex, presetColor);
            tex.getClass().getMethod("setUseStencilBuffer", boolean.class).invoke(tex, true);
            freeSet.add(lease);
        }
    }

    private static byte[] injectNop(final byte[] original, final String targetMethod, final String targetDesc) {
        final ClassReader reader = new ClassReader(original);
        final ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(
                new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String descriptor,
                            final String signature,
                            final String[] exceptions) {
                        final MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                        if (!targetMethod.equals(name) || !targetDesc.equals(descriptor)) {
                            return mv;
                        }
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                super.visitInsn(Opcodes.NOP);
                            }
                        };
                    }
                },
                0);
        return writer.toByteArray();
    }

    private static void runOnEdt(final ThrowingRunnable task) throws Throwable {
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
            return;
        }
        final CompletableFuture<Void> done = new CompletableFuture<>();
        SwingUtilities.invokeLater(() -> {
            try {
                task.run();
                done.complete(null);
            } catch (final Throwable t) {
                done.completeExceptionally(t);
            }
        });
        try {
            done.get();
        } catch (final java.util.concurrent.ExecutionException ex) {
            throw ex.getCause();
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Throwable;
    }

    private static HostClassLoader hostLoader(final Path directory) throws Exception {
        return hostLoader(directory, null);
    }

    private static HostClassLoader hostLoader(final Path directory, final Path extraJar) throws Exception {
        return new HostClassLoader(
                urlsUnder(directory, extraJar), RenderPassTraversalArtifactTest.class.getClassLoader());
    }

    private static URL[] urlsUnder(final Path directory, final Path extraJar) throws Exception {
        final List<URL> urls = new ArrayList<>();
        try (var files = Files.walk(directory, MAX_WALK_DEPTH)) {
            for (final Path path :
                    files.filter(p -> p.toString().endsWith(JAR_EXTENSION)).toList()) {
                urls.add(path.toUri().toURL());
            }
        }
        if (extraJar != null) {
            urls.add(extraJar.toUri().toURL());
        }
        return urls.toArray(URL[]::new);
    }

    private static Path joglArtifact(final Path root) {
        final String supplied = System.getenv("TURBOISM_CUBISM_JOGL");
        if (supplied != null && !supplied.isBlank()) {
            final Path jogl = Path.of(supplied);
            assertTrue(Files.isRegularFile(jogl), "explicit JOGL artifact must exist: " + jogl);
            return jogl;
        }
        for (final String version : ReviewedHostArtifacts.reviewedCubismVersions()) {
            for (final String candidate : List.of("jogl/jogl-all.jar", "jogl-all.jar")) {
                final Path jogl = root.resolve(version).resolve(candidate);
                if (Files.isRegularFile(jogl)) {
                    return jogl;
                }
            }
        }
        return null;
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

    private static Path referenceRoot() {
        final String configured =
                System.getProperty(REF_DIR_PROPERTY, System.getenv().getOrDefault(REF_DIR_ENV, ""));
        assumeTrue(!configured.isBlank(), "exact Cubism reference directory not configured");
        final Path root = Path.of(configured);
        assumeTrue(Files.isDirectory(root), "reference directory absent: " + root);
        return root;
    }

    private static final class HostClassLoader extends URLClassLoader {
        private final Map<String, OverrideEntry> overrides = new LinkedHashMap<>();

        private record OverrideEntry(byte[] bytes, ProtectionDomain domain) {}

        private HostClassLoader(final URL[] urls, final ClassLoader parent) {
            super(urls, parent);
        }

        private void installOverride(final String name, final byte[] bytes, final ProtectionDomain domain) {
            overrides.put(name, new OverrideEntry(bytes, domain));
        }

        private void installExtraOverride(final String name, final byte[] bytes, final ProtectionDomain domain) {
            overrides.put(name, new OverrideEntry(bytes, domain));
        }

        @Override
        protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
            if (name.startsWith(LIVE2D_PACKAGE_PREFIX)
                    || name.startsWith(NOIDS_PACKAGE_PREFIX)
                    || name.startsWith(JOGAMP_PACKAGE_PREFIX)) {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) {
                        final OverrideEntry entry = overrides.get(name);
                        if (entry != null) {
                            loaded = defineClass(name, entry.bytes(), 0, entry.bytes().length, entry.domain());
                        } else {
                            loaded = findClass(name);
                        }
                    }
                    if (resolve) {
                        resolveClass(loaded);
                    }
                    return loaded;
                }
            }
            return super.loadClass(name, resolve);
        }

        @Override
        protected Class<?> findClass(final String name) throws ClassNotFoundException {
            if ("com.jogamp.opengl.util.GLBuffers".equals(name)) {
                final byte[] stub = buildGlBuffersStub();
                return defineClass(name, stub, 0, stub.length);
            }
            return super.findClass(name);
        }
    }

    private static byte[] buildGlBuffersStub() {
        final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                "com/jogamp/opengl/util/GLBuffers",
                null,
                "java/lang/Object",
                null);
        final MethodVisitor mv = cw.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "newDirectIntBuffer", "(I)Ljava/nio/IntBuffer;", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitInsn(Opcodes.ICONST_4);
        mv.visitInsn(Opcodes.IMUL);
        mv.visitMethodInsn(
                Opcodes.INVOKESTATIC, "java/nio/ByteBuffer", "allocateDirect", "(I)Ljava/nio/ByteBuffer;", false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/nio/ByteOrder", "nativeOrder", "()Ljava/nio/ByteOrder;", false);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/nio/ByteBuffer",
                "order",
                "(Ljava/nio/ByteOrder;)Ljava/nio/ByteBuffer;",
                false);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, "java/nio/ByteBuffer", "asIntBuffer", "()Ljava/nio/IntBuffer;", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] buildShaderDefaultsStub() {
        final String owner = "com/live2d/graphics3d/shader/a";
        final String gshader = "com/live2d/graphics3d/shader/GShader";
        final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, owner, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "a", "L" + owner + ";", null, null);

        final MethodVisitor init = cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        final MethodVisitor clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitTypeInsn(Opcodes.NEW, owner);
        clinit.visitInsn(Opcodes.DUP);
        clinit.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, "<init>", "()V", false);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, owner, "a", "L" + owner + ";");
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();

        for (final String name : new String[] {"a", "b", "c", "d"}) {
            final MethodVisitor mv =
                    cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, name, "()L" + gshader + ";", null, null);
            mv.visitCode();
            mv.visitTypeInsn(Opcodes.NEW, gshader);
            mv.visitInsn(Opcodes.DUP);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, gshader, "<init>", "()V", false);
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }
}
