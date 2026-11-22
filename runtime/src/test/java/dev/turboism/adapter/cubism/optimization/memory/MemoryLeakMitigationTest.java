package dev.turboism.adapter.cubism.optimization.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.objectweb.asm.Opcodes.*;

import dev.turboism.adapter.cubism.optimization.uploadelision.SkippedFrameUploadElisionTarget;
import dev.turboism.adapter.cubism.optimization.uploadelision.SkippedFrameUploadElisionTransformer;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.awt.AWTEvent;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.Permissions;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JTree;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

class MemoryLeakMitigationTest {

    private static final String ARTIFACTS_DIR_NAME = "cubism-host-artifacts";
    private static final Path DEFAULT_LOCAL_ARTIFACTS =
            Path.of(System.getProperty("user.home"), ARTIFACTS_DIR_NAME);
    private static final int EXPECTED_REWRITTEN_OWNERS = 20;
    private static final int DETACHED_REUSE_VBO_ID = 73;
    private static final int LARGE_SUBTREE_CHILD_COUNT = 600;
    private static final int LARGE_SUBTREE_VBO_BASE = 1000;

    private MemoryLeakMitigationBridge activeBridge;
    private boolean registeredAwtGuard;

    @AfterEach
    void tearDown() {
        if (activeBridge != null) {
            activeBridge.close();
            activeBridge = null;
        }
        registeredAwtGuard = false;
    }

    @Test
    void everyReviewedArtifactHasAMemoryLeakTargetAndUnknownIsRejected() {
        final List<MemoryLeakMitigationTarget> all = MemoryLeakMitigationTarget.all();
        assertEquals(ReviewedHostArtifacts.all().size(), all.size());
        for (final MemoryLeakMitigationTarget target : all) {
            assertEquals(EXPECTED_REWRITTEN_OWNERS, target.rewrittenOwners().size());
            assertSame(target, MemoryLeakMitigationTarget.of(target.digest()).orElseThrow());
        }
        assertTrue(MemoryLeakMitigationTarget.of(new HostArtifactDigest(1L, "9".repeat(64)))
                .isEmpty());
    }

    @Test
    void transformerFailsClosedOnWrongLoaderWrongSourceOrMissingAnchor() throws Exception {
        final MemoryLeakMitigationTarget target =
                MemoryLeakMitigationTarget.of(ReviewedHostArtifacts.CUBISM_5_3_04).orElseThrow();
        final Path fakeJar = Files.createTempFile("memory-leak-wrong", ".jar");
        final Path otherJar = Files.createTempFile("memory-leak-other", ".jar");
        try {
            final SyntheticHost host = new SyntheticHost(fakeJar);
            final MemoryLeakMitigationTransformer transformer =
                    new MemoryLeakMitigationTransformer(host.loader, fakeJar, target);

            final byte[] writableBytes = host.rawClasses.get(MemoryLeakMitigationTarget.WRITABLE_IMAGE_OWNER);
            assertNull(
                    transformer.transform(
                            null,
                            MemoryLeakMitigationTest.class.getClassLoader(),
                            MemoryLeakMitigationTarget.WRITABLE_IMAGE_OWNER,
                            null,
                            host.loader.domain,
                            writableBytes));

            final ProtectionDomain wrongDomain = new ProtectionDomain(
                    new CodeSource(otherJar.toUri().toURL(), (java.security.CodeSigner[]) null), new Permissions());
            assertNull(
                    transformer.transform(
                            null,
                            host.loader,
                            MemoryLeakMitigationTarget.WRITABLE_IMAGE_OWNER,
                            null,
                            wrongDomain,
                            writableBytes));
            assertNotNull(transformer.failure());

            final byte[] drifted = buildSimpleVoidClass(MemoryLeakMitigationTarget.WRITABLE_IMAGE_OWNER, "flush");
            assertNull(
                    transformer.transform(
                            null,
                            host.loader,
                            MemoryLeakMitigationTarget.WRITABLE_IMAGE_OWNER,
                            null,
                            host.loader.domain,
                            drifted));
            assertTrue(transformer.failure().contains("anchor drift"));
            assertEquals(0, transformer.matches());
        } finally {
            Files.deleteIfExists(fakeJar);
            Files.deleteIfExists(otherJar);
        }
    }

    @Test
    void weavesAllAvailableLocalCubismJarsBindsHandlesAndComposesWithUploadElision() throws Exception {
        final List<Path> jars = findLocalCubismJars();
        assumeTrue(!jars.isEmpty(), "no local Cubism Editor JARs present");

        int checked = 0;
        for (int i = 0; i < jars.size(); i++) {
            final Path jarPath = jars.get(i);
            final HostArtifactDigest digest = HostArtifactDigest.cached(jarPath);
            final MemoryLeakMitigationTarget target =
                    MemoryLeakMitigationTarget.of(digest).orElse(null);
            if (target == null) {
                continue;
            }
            checked++;
            try (var jar = new JarFile(jarPath.toFile());
                    var urlLoader = openHostJarLoader(jarPath)) {
                final ProtectionDomain domain = new ProtectionDomain(
                        new CodeSource(jarPath.toUri().toURL(), (java.security.cert.Certificate[]) null), null);
                final MemoryLeakMitigationTransformer transformer =
                        new MemoryLeakMitigationTransformer(urlLoader, jarPath, target);

                for (final String owner : target.rewrittenOwners()) {
                    final byte[] raw = readJarEntry(jar, owner + ".class");
                    assertNotNull(raw, "missing " + owner + " in " + target.version());
                    final byte[] woven = transformer.transform(null, urlLoader, owner, null, domain, raw);
                    assertNotNull(woven, () -> "transform failed on " + target.version() + " " + owner + ": "
                            + transformer.failure());
                    new ClassReader(woven).accept(new org.objectweb.asm.ClassVisitor(ASM9) {}, 0);
                }
                assertEquals(EXPECTED_REWRITTEN_OWNERS, transformer.matches(), target.version());
                assertEquals(MemoryLeakMitigationTransformer.EXPECTED_SITES, transformer.sites(), target.version());
                assertNull(transformer.failure(), target.version());

                if (i == 0 || i == jars.size() - 1) {
                    try (MemoryLeakMitigationBridge bridge = new MemoryLeakMitigationBridge(urlLoader, target)) {
                        bridge.install();
                        assertNotNull(bridge.snapshot());
                    }

                    // Verify composition when SkippedFrameUploadElisionTransformer runs before us on mesh/a/{b,c}.
                    for (final String vboOwner : SkippedFrameUploadElisionTarget.OWNERS) {
                        final byte[] vboBytes = readJarEntry(jar, vboOwner + ".class");
                        final SkippedFrameUploadElisionTransformer uploadTx =
                                new SkippedFrameUploadElisionTransformer(urlLoader, jarPath, vboBytes, vboOwner);
                        final byte[] afterUpload = uploadTx.transform(null, urlLoader, vboOwner, null, domain, vboBytes);
                        assertNotNull(afterUpload, uploadTx.failure());
                        final MemoryLeakMitigationTransformer composedTx =
                                new MemoryLeakMitigationTransformer(urlLoader, jarPath, target);
                        final byte[] afterLeakFix =
                                composedTx.transform(null, urlLoader, vboOwner, null, domain, afterUpload);
                        assertNotNull(afterLeakFix, composedTx.failure());
                    }
                }
            }
        }
        assertTrue(checked > 0, "expected at least one reviewed JAR to match");
    }

    @Test
    void partsTreeReplacementAndRebuildReuseEliminateListenerLeakAndAllocStorm() throws Exception {
        final Path fakeJar = Files.createTempFile("memory-leak-parts", ".jar");
        try {
            final SyntheticHost host = weaveAndBootBridge(fakeJar);

            final Class<?> keyRegCls = host.load(MemoryLeakMitigationTarget.KEY_REGISTRY_OWNER_53_PLUS);
            final Map<?, ?> keyRegistry = (Map<?, ?>) keyRegCls.getField("e").get(null);
            final Class<?> docCls = host.load("com/live2d/cubism/doc/modeling/CModelingDocument");
            final Object doc = docCls.getConstructor().newInstance();
            final Object model = host.load("com/live2d/cubism/doc/model/CModel").getConstructor().newInstance();
            final Object vc = host.load(MemoryLeakMitigationTarget.VIEW_CONTEXT_OWNER).getConstructor().newInstance();
            final Object pack = host.load("com/live2d/cubism/pack/CECompletePack").getConstructor().newInstance();

            final Class<?> panelCls = host.load(MemoryLeakMitigationTarget.PARTS_PANEL_OWNER);
            final Object panel = panelCls.getConstructor(pack.getClass(), docCls, model.getClass(), vc.getClass())
                    .newInstance(pack, doc, model, vc);

            // Initial build creates Tree1 + KeyListener registered in keyRegistry.e, and reuses it on unchanged rebuilds.
            final WeakReference<Object> tree1Ref = buildAndVerifyInitialPartsTree(panel, panelCls, keyRegistry);

            // Changing part name invalidates fingerprint -> rebuilds Tree2 and detaches Tree1 KeyListener.
            host.renameFirstPart(panel, "RenamedPart");
            panelCls.getMethod("b", boolean.class, boolean.class).invoke(panel, false, false);
            final Object tree2 = panelCls.getField("b").get(panel);
            assertNotNull(tree2);
            assertFalse(tree2 == tree1Ref.get());
            assertEquals(1, keyRegistry.size());
            assertTrue(activeBridge.snapshot().get("partsTreesDetached") >= 1L);

            forceGcUntilCollected(tree1Ref);
            assertNull(tree1Ref.get(), "old CPartsTreeTable must be collectable after replacement");

            // Closing parts panel detaches the remaining tree's KeyListener cleanly.
            panelCls.getMethod("y").invoke(panel);
            assertEquals(0, keyRegistry.size());
        } finally {
            Files.deleteIfExists(fakeJar);
        }
    }

    @Test
    void vboReuseContextQueueDrainSubtreeReleaseAndContextPruningPreventGlLeaks() throws Exception {
        final Path fakeJar = Files.createTempFile("memory-leak-gl", ".jar");
        try {
            final SyntheticHost host = weaveAndBootBridge(fakeJar);

            final Class<?> ctxCls = host.load("com/jogamp/opengl/GLContext");
            final Field currentCtxField = ctxCls.getField("CURRENT");
            final Object ctx1 = ctxCls.getConstructor(boolean.class).newInstance(true);
            final Object ctx2 = ctxCls.getConstructor(boolean.class).newInstance(true);
            final Class<?> glImplCls = host.load("TestGL3");
            final Object gl1 = glImplCls.getConstructor(ctxCls).newInstance(ctx1);
            final Object gl2 = glImplCls.getConstructor(ctxCls).newInstance(ctx2);

            // Activate ctx1 and create/upload VBO on gl1.
            currentCtxField.set(null, ctx1);
            final Class<?> floatBufCls = host.load(MemoryLeakMitigationTarget.GL_BUFFER_FLOAT_OWNER);
            final Object vbo = floatBufCls.getConstructor().newInstance();
            final Method uploadMethod = floatBufCls.getDeclaredMethod("b", host.load(MemoryLeakMitigationTarget.GL2ES2_OWNER), int.class);
            uploadMethod.setAccessible(true);

            // Initial upload generates buffer #1 on gl1.
            uploadMethod.invoke(vbo, gl1, 1);
            assertEquals(1, glImplCls.getField("gens").getInt(gl1));
            assertEquals(0, glImplCls.getField("deletes").getInt(gl1));
            floatBufCls.getMethod("a", host.load(MemoryLeakMitigationTarget.GL2ES2_OWNER)).invoke(vbo, gl1);

            // Reallocate trigger (j() == true) with same GL1 reuses VBO ID without glDeleteBuffers/glGenBuffers.
            floatBufCls.getMethod("c", boolean.class).invoke(vbo, true);
            uploadMethod.invoke(vbo, gl1, 1);
            assertEquals(1, glImplCls.getField("gens").getInt(gl1));
            assertEquals(0, glImplCls.getField("deletes").getInt(gl1));
            assertEquals(1L, activeBridge.snapshot().get("vbosReused"));

            // Context switch to ctx2 queues gl1's VBO cleanly instead of calling gl1.glDeleteBuffers on ctx2!
            currentCtxField.set(null, ctx2);
            uploadMethod.invoke(vbo, gl2, 1);
            assertEquals(1L, activeBridge.snapshot().get("vbosQueued"));
            assertEquals(0, glImplCls.getField("deletes").getInt(gl2));
            assertEquals(0, glImplCls.getField("deletes").getInt(gl1));

            // Returning to ctx1 drains ctx1's deferred queue via gl1.glDeleteBuffers.
            currentCtxField.set(null, ctx1);
            final Object otherVbo = floatBufCls.getConstructor().newInstance();
            uploadMethod.invoke(otherVbo, gl1, 1);
            assertTrue(glImplCls.getField("deletes").getInt(gl1) >= 1);
            assertTrue(activeBridge.snapshot().get("vbosDeleted") >= 1L);

            // Multi-batch drain (> 64 queued VBOs) deletes and counts both full 64-batches and the tail batch.
            final long queuedBeforeBatch = activeBridge.snapshot().get("vbosQueued");
            final long deletedBeforeBatch = activeBridge.snapshot().get("vbosDeleted");
            final List<Object> batchVbos = new ArrayList<>();
            for (int k = 0; k < 70; k++) {
                final Object bv = floatBufCls.getConstructor().newInstance();
                uploadMethod.invoke(bv, gl1, 1);
                floatBufCls.getMethod("a", host.load(MemoryLeakMitigationTarget.GL2ES2_OWNER)).invoke(bv, gl1);
                batchVbos.add(bv);
            }
            currentCtxField.set(null, ctx2);
            for (final Object bv : batchVbos) {
                uploadMethod.invoke(bv, gl2, 1);
            }
            assertEquals(70L, activeBridge.snapshot().get("vbosQueued") - queuedBeforeBatch);
            assertEquals(deletedBeforeBatch, activeBridge.snapshot().get("vbosDeleted"));
            currentCtxField.set(null, ctx1);
            uploadMethod.invoke(otherVbo, gl1, 1);
            assertEquals(70L, activeBridge.snapshot().get("vbosDeleted") - deletedBeforeBatch);

            // Detaching persistent reusable entities (e.g. WarpDeformer_Draw.eTriangleOnCornerBlack) via
            // Entities.removeAll() must preserve their live VBO state without deleting or corrupting reinit flags.
            final Class<?> entitiesCls = host.load(MemoryLeakMitigationTarget.ENTITIES_OWNER);
            final Object entitiesObj = entitiesCls.getConstructor().newInstance();
            entitiesCls.getMethod("removeAll").invoke(entitiesObj);
            final Class<?> entityCls = host.load(MemoryLeakMitigationTarget.G_ENTITY_OWNER);
            final Class<?> meshCls = host.load("com/live2d/graphics3d/mesh/GMesh2");
            final Class<?> meshContainerCls = host.load("com/live2d/graphics3d/component/GMeshContainer");
            final Class<?> meshArgCls = host.load("com/live2d/graphics3d/mesh/a");
            final Class<?> meshRendererCls = host.load(MemoryLeakMitigationTarget.G_MESH_RENDERER_OWNER);
            final var setGlMethod = floatBufCls.getMethod("a", host.load(MemoryLeakMitigationTarget.GL2ES2_OWNER));
            final var getGlMethod = floatBufCls.getMethod("h");
            final var getReinitMethod = floatBufCls.getMethod("j");
            final var getIntBufMethod = floatBufCls.getMethod("i");
            final Object subVbo = floatBufCls.getConstructor().newInstance();
            setGlMethod.invoke(subVbo, gl1);
            ((IntBuffer) getIntBufMethod.invoke(subVbo)).put(0, DETACHED_REUSE_VBO_ID);
            final Object subEntity = entityCls.getConstructor().newInstance();
            final Object subMesh = meshCls.getConstructor().newInstance();
            meshCls.getField("posBuffer").set(subMesh, subVbo);
            final Object subCont = meshContainerCls.getConstructor(meshArgCls).newInstance(subMesh);
            final Object subRend = meshRendererCls.getConstructor(meshContainerCls).newInstance(subCont);
            ((List<Object>) entityCls.getMethod("getComponents").invoke(subEntity)).add(subRend);
            ((List<Object>) entitiesObj).add(subEntity);

            final long queuedBeforeDetach = activeBridge.snapshot().get("vbosQueued");
            final long deletedBeforeDetach = activeBridge.snapshot().get("vbosDeleted");
            final int deletesBeforeDetach = glImplCls.getField("deletes").getInt(gl1);
            entitiesCls.getMethod("removeAll").invoke(entitiesObj);
            assertEquals(DETACHED_REUSE_VBO_ID, ((IntBuffer) getIntBufMethod.invoke(subVbo)).get(0));
            assertSame(gl1, getGlMethod.invoke(subVbo));
            assertEquals(false, getReinitMethod.invoke(subVbo));
            assertEquals(queuedBeforeDetach, activeBridge.snapshot().get("vbosQueued"));
            assertEquals(deletedBeforeDetach, activeBridge.snapshot().get("vbosDeleted"));
            assertEquals(deletesBeforeDetach, glImplCls.getField("deletes").getInt(gl1));

            // Re-attaching and uploading after parent removeAll() cleanly reuses the existing VBO handle.
            ((List<Object>) entitiesObj).add(subEntity);
            uploadMethod.invoke(subVbo, gl1, 1);
            assertEquals(DETACHED_REUSE_VBO_ID, ((IntBuffer) getIntBufMethod.invoke(subVbo)).get(0));
            assertSame(gl1, getGlMethod.invoke(subVbo));
            assertEquals(false, getReinitMethod.invoke(subVbo));
            assertEquals(queuedBeforeDetach, activeBridge.snapshot().get("vbosQueued"));
            assertEquals(deletedBeforeDetach, activeBridge.snapshot().get("vbosDeleted"));

            // Actual destruction via GEntity.dispose() sets reinit=true and gl=null before clearing VBO ID and deleting.
            entityCls.getMethod("dispose").invoke(subEntity);
            assertEquals(0, ((IntBuffer) getIntBufMethod.invoke(subVbo)).get(0));
            assertNull(getGlMethod.invoke(subVbo));
            assertEquals(true, getReinitMethod.invoke(subVbo));
            assertEquals(deletedBeforeDetach + 1L, activeBridge.snapshot().get("vbosDeleted"));
            assertEquals(deletesBeforeDetach + 1, glImplCls.getField("deletes").getInt(gl1));

            // Re-uploading a disposed-then-reinitialized VBO regenerates a valid > 0 handle without leaving vboId == 0.
            uploadMethod.invoke(subVbo, gl1, 1);
            assertTrue(((IntBuffer) getIntBufMethod.invoke(subVbo)).get(0) > 0);
            assertSame(gl1, getGlMethod.invoke(subVbo));
            assertEquals(false, getReinitMethod.invoke(subVbo));

            // Disposing repeated large (>512 node) subtrees via EditableMeshSelectionEntity.reset() reuses TraversalScratch cleanly.
            final Class<?> selectionCls = host.load(MemoryLeakMitigationTarget.EDITABLE_MESH_SEL_OWNER);
            final var getChildrenMethod = entityCls.getMethod("getChildren");
            final var getComponentsMethod = entityCls.getMethod("getComponents");
            final long deletedBeforeLarge = activeBridge.snapshot().get("vbosDeleted");
            for (int cycle = 0; cycle < 2; cycle++) {
                final Object bigSelection = selectionCls.getConstructor().newInstance();
                final List<Object> bigChildren = (List<Object>) getChildrenMethod.invoke(bigSelection);
                for (int childIdx = 0; childIdx < LARGE_SUBTREE_CHILD_COUNT; childIdx++) {
                    final Object childVbo = floatBufCls.getConstructor().newInstance();
                    setGlMethod.invoke(childVbo, gl1);
                    ((IntBuffer) getIntBufMethod.invoke(childVbo))
                            .put(0, LARGE_SUBTREE_VBO_BASE + cycle * LARGE_SUBTREE_CHILD_COUNT + childIdx);
                    final Object childEntity = entityCls.getConstructor().newInstance();
                    final Object childMesh = meshCls.getConstructor().newInstance();
                    meshCls.getField("posBuffer").set(childMesh, childVbo);
                    final Object childCont = meshContainerCls.getConstructor(meshArgCls).newInstance(childMesh);
                    final Object childRend = meshRendererCls.getConstructor(meshContainerCls).newInstance(childCont);
                    ((List<Object>) getComponentsMethod.invoke(childEntity)).add(childRend);
                    bigChildren.add(childEntity);
                }
                selectionCls.getMethod("reset").invoke(bigSelection);
            }
            assertEquals(
                    deletedBeforeLarge + 2L * LARGE_SUBTREE_CHILD_COUNT,
                    activeBridge.snapshot().get("vbosDeleted"));

            // Entity subtree disposal under CEViewContext_MeshEditor releases nested mesh VBO.
            final Object entityVbo = floatBufCls.getConstructor().newInstance();
            floatBufCls.getMethod("a", host.load(MemoryLeakMitigationTarget.GL2ES2_OWNER)).invoke(entityVbo, gl1);
            ((IntBuffer) floatBufCls.getMethod("i").invoke(entityVbo)).put(0, 42);
            final Object meshEditorVc = host.buildMeshEditorWithBuffer(entityVbo);
            host.load(MemoryLeakMitigationTarget.MESH_EDITOR_VC_OWNER).getMethod("deactivateView").invoke(meshEditorVc);
            assertEquals(0, ((IntBuffer) floatBufCls.getMethod("i").invoke(entityVbo)).get(0));
            assertTrue(glImplCls.getField("deletes").getInt(gl1) >= 2);

            // GTexture2D.setup_exe + dispose releases CImageResource first, then queues GL texture/sampler & clears _preBuffer.
            final Object graphicsA = host.load("com/live2d/graphics3d/a").getConstructor(host.load("com/jogamp/opengl/GL3")).newInstance(gl1);
            final Object res = host.load(MemoryLeakMitigationTarget.IMAGE_RESOURCE_OWNER).getConstructor().newInstance();
            final Object tex2d = host.load(MemoryLeakMitigationTarget.G_TEXTURE_2D_OWNER).getConstructor(res.getClass()).newInstance(res);
            tex2d.getClass().getField("glTextureName").setInt(tex2d, 77);
            tex2d.getClass().getField("glSamplerName").setInt(tex2d, 88);
            tex2d.getClass().getField("initializedGl").set(tex2d, gl1);
            tex2d.getClass().getField("_preBuffer").set(tex2d, new byte[16]);
            tex2d.getClass().getMethod("setup_exe", graphicsA.getClass()).invoke(tex2d, graphicsA);
            tex2d.getClass().getMethod("dispose").invoke(tex2d);
            assertTrue(res.getClass().getField("releasedCalls").getInt(res) >= 1);
            assertNull(tex2d.getClass().getField("_preBuffer").get(tex2d));
            assertNull(tex2d.getClass().getField("initializedGl").get(tex2d));
            assertEquals(-1, tex2d.getClass().getField("glTextureName").getInt(tex2d));

            // glWrapper.a$b.dispose + glWrapper.a.c() prunes dead rendering/b.c entries and clears wrapper fields.
            final Map<Object, Object> renderMap =
                    (Map<Object, Object>) host.load(MemoryLeakMitigationTarget.RENDERING_CACHE_OWNER_53_PLUS).getField("c").get(null);
            final Object deadCtx = ctxCls.getConstructor(boolean.class).newInstance(false);
            final Object deadGl = glImplCls.getConstructor(ctxCls).newInstance(deadCtx);
            renderMap.put(deadGl, "staleFbo");
            renderMap.put(gl1, "liveFbo");

            final Object wrapper = host.load(MemoryLeakMitigationTarget.GL_WRAPPER_OWNER).getConstructor().newInstance();
            ((List<Object>) wrapper.getClass().getField("k").get(wrapper)).add("callback");
            wrapper.getClass().getField("o").set(wrapper, new Object());
            final Object listener = host.load(MemoryLeakMitigationTarget.GL_WRAPPER_LISTENER_OWNER)
                    .getConstructor(wrapper.getClass())
                    .newInstance(wrapper);
            final Object drawable = host.load("TestDrawable").getConstructor(host.load("com/jogamp/opengl/GL")).newInstance(deadGl);
            listener.getClass().getMethod("dispose", host.load("com/jogamp/opengl/GLAutoDrawable")).invoke(listener, drawable);
            wrapper.getClass().getMethod("c").invoke(wrapper);
            assertFalse(renderMap.containsKey(deadGl));
            assertTrue(renderMap.containsKey(gl1));
            assertTrue(((List<?>) wrapper.getClass().getField("k").get(wrapper)).isEmpty());
            assertNull(wrapper.getClass().getField("o").get(wrapper));
        } finally {
            Files.deleteIfExists(fakeJar);
        }
    }

    @Test
    void offThreadDisposeAndResetDeferVboResetToGlThreadWithoutZeroingLiveHandles() throws Exception {
        final Path fakeJar = Files.createTempFile("memory-leak-offthread-gl", ".jar");
        try {
            final SyntheticHost host = weaveAndBootBridge(fakeJar);

            final Class<?> ctxCls = host.load("com/jogamp/opengl/GLContext");
            final Field currentCtxField = ctxCls.getField("CURRENT");
            final Object ctx1 = ctxCls.getConstructor(boolean.class).newInstance(true);
            final Object ctx2 = ctxCls.getConstructor(boolean.class).newInstance(true);
            final Class<?> glImplCls = host.load("TestGL3");
            final Object gl1 = glImplCls.getConstructor(ctxCls).newInstance(ctx1);
            final Object gl2 = glImplCls.getConstructor(ctxCls).newInstance(ctx2);

            final Class<?> floatBufCls = host.load(MemoryLeakMitigationTarget.GL_BUFFER_FLOAT_OWNER);
            final Method uploadMethod =
                    floatBufCls.getDeclaredMethod("b", host.load(MemoryLeakMitigationTarget.GL2ES2_OWNER), int.class);
            uploadMethod.setAccessible(true);
            final var getGlMethod = floatBufCls.getMethod("h");
            final var getReinitMethod = floatBufCls.getMethod("j");
            final var getIntBufMethod = floatBufCls.getMethod("i");

            final Class<?> entityCls = host.load(MemoryLeakMitigationTarget.G_ENTITY_OWNER);
            final Class<?> meshCls = host.load("com/live2d/graphics3d/mesh/GMesh2");
            final Class<?> meshContainerCls = host.load("com/live2d/graphics3d/component/GMeshContainer");
            final Class<?> meshArgCls = host.load("com/live2d/graphics3d/mesh/a");
            final Class<?> meshRendererCls = host.load(MemoryLeakMitigationTarget.G_MESH_RENDERER_OWNER);
            final Class<?> selectionCls = host.load(MemoryLeakMitigationTarget.EDITABLE_MESH_SEL_OWNER);

            // Prepare VBOs on active ctx1.
            currentCtxField.set(null, ctx1);
            final Object parentEntity = entityCls.getConstructor().newInstance();
            final Object childEntity = entityCls.getConstructor().newInstance();
            final Object childMesh = meshCls.getConstructor().newInstance();
            final Object childVbo = floatBufCls.getConstructor().newInstance();
            meshCls.getField("posBuffer").set(childMesh, childVbo);
            uploadMethod.invoke(childVbo, gl1, 1);
            final int childVboId = ((IntBuffer) getIntBufMethod.invoke(childVbo)).get(0);
            assertTrue(childVboId > 0);
            final Object childRend =
                    meshRendererCls.getConstructor(meshContainerCls).newInstance(meshContainerCls.getConstructor(meshArgCls).newInstance(childMesh));
            ((List<Object>) entityCls.getMethod("getComponents").invoke(childEntity)).add(childRend);
            ((List<Object>) entityCls.getMethod("getChildren").invoke(parentEntity)).add(childEntity);

            final Object selection = selectionCls.getConstructor().newInstance();
            final Object selEntity = entityCls.getConstructor().newInstance();
            final Object selMesh = meshCls.getConstructor().newInstance();
            final Object selVbo = floatBufCls.getConstructor().newInstance();
            meshCls.getField("posBuffer").set(selMesh, selVbo);
            uploadMethod.invoke(selVbo, gl1, 1);
            final int selVboId = ((IntBuffer) getIntBufMethod.invoke(selVbo)).get(0);
            assertTrue(selVboId > 0);
            final Object selRend =
                    meshRendererCls.getConstructor(meshContainerCls).newInstance(meshContainerCls.getConstructor(meshArgCls).newInstance(selMesh));
            ((List<Object>) entityCls.getMethod("getComponents").invoke(selEntity)).add(selRend);
            ((List<Object>) entityCls.getMethod("getChildren").invoke(selection)).add(selEntity);

            // Simulate EDT teardown (no current GLContext on calling thread).
            currentCtxField.set(null, null);
            final long queuedBefore = activeBridge.snapshot().get("vbosQueued");
            final long deletedBefore = activeBridge.snapshot().get("vbosDeleted");

            // Disposing parent alone must not release child entity VBOs prematurely.
            entityCls.getMethod("dispose").invoke(parentEntity);
            assertEquals(childVboId, ((IntBuffer) getIntBufMethod.invoke(childVbo)).get(0));
            assertSame(gl1, getGlMethod.invoke(childVbo));
            assertEquals(queuedBefore, activeBridge.snapshot().get("vbosQueued"));

            // Disposing child entity off-thread defers VBO reset/delete to ctx1 without zeroing live handles on EDT.
            entityCls.getMethod("dispose").invoke(childEntity);
            assertEquals(childVboId, ((IntBuffer) getIntBufMethod.invoke(childVbo)).get(0));
            assertSame(gl1, getGlMethod.invoke(childVbo));
            assertEquals(false, getReinitMethod.invoke(childVbo));
            assertEquals(queuedBefore + 1L, activeBridge.snapshot().get("vbosQueued"));
            assertEquals(deletedBefore, activeBridge.snapshot().get("vbosDeleted"));

            // Resetting EditableMeshSelectionEntity twice off-thread queues once and keeps selVboId valid until GL drain.
            final Method resetMethod = selectionCls.getMethod("reset");
            resetMethod.invoke(selection);
            resetMethod.invoke(selection);
            assertEquals(selVboId, ((IntBuffer) getIntBufMethod.invoke(selVbo)).get(0));
            assertSame(gl1, getGlMethod.invoke(selVbo));
            assertEquals(false, getReinitMethod.invoke(selVbo));
            assertEquals(queuedBefore + 2L, activeBridge.snapshot().get("vbosQueued"));
            assertEquals(deletedBefore, activeBridge.snapshot().get("vbosDeleted"));
            assertEquals(0, glImplCls.getField("deletes").getInt(gl1));

            // Re-uploading selVbo on another context (ctx2) before ctx1 drains must not re-queue selVboId.
            currentCtxField.set(null, ctx2);
            uploadMethod.invoke(selVbo, gl2, 1);
            final int selVboIdOnGl2 = ((IntBuffer) getIntBufMethod.invoke(selVbo)).get(0);
            assertTrue(selVboIdOnGl2 > 0);
            assertSame(gl2, getGlMethod.invoke(selVbo));
            assertEquals(queuedBefore + 2L, activeBridge.snapshot().get("vbosQueued"));

            // Returning to ctx1 drains both queued releases via drainForGl before uploading childVbo,
            // deleting old IDs cleanly, re-initializing childVbo, and preserving selVbo's live gl2 state.
            currentCtxField.set(null, ctx1);
            uploadMethod.invoke(childVbo, gl1, 1);
            assertEquals(deletedBefore + 2L, activeBridge.snapshot().get("vbosDeleted"));
            assertEquals(1, glImplCls.getField("deletes").getInt(gl1));
            assertTrue(((IntBuffer) getIntBufMethod.invoke(childVbo)).get(0) > 0);
            assertTrue(((IntBuffer) getIntBufMethod.invoke(childVbo)).get(0) != childVboId);
            assertSame(gl1, getGlMethod.invoke(childVbo));
            assertEquals(false, getReinitMethod.invoke(childVbo));

            assertSame(gl2, getGlMethod.invoke(selVbo));
            assertEquals(selVboIdOnGl2, ((IntBuffer) getIntBufMethod.invoke(selVbo)).get(0));
            assertEquals(false, getReinitMethod.invoke(selVbo));
        } finally {
            Files.deleteIfExists(fakeJar);
        }
    }

    @Test
    void undoLimitDocumentCloseStaticHoldersImageCacheFlushInspectorAndAwtRenameAreFixed() throws Exception {
        final Path fakeJar = Files.createTempFile("memory-leak-lifecycle", ".jar");
        try {
            final SyntheticHost host = weaveAndBootBridge(fakeJar);

            // 1. Bounded CUndoManager enforces limit, calls die(), and shifts currentPos.
            final Class<?> undoCls = host.load(MemoryLeakMitigationTarget.UNDO_MANAGER_OWNER);
            final Object undoMgr = undoCls.getConstructor().newInstance();
            undoCls.getField("limit").setInt(undoMgr, 2);
            final Class<?> undoableCls = host.load("com/live2d/undo/ACUndoable");
            final List<Object> added = new ArrayList<>();
            final Method addEdit = undoCls.getMethod("addEdit", undoableCls);
            for (int i = 0; i < 4; i++) {
                final Object item = undoableCls.getConstructor().newInstance();
                added.add(item);
                addEdit.invoke(undoMgr, item);
            }
            assertEquals(2, ((List<?>) undoCls.getField("_undoList").get(undoMgr)).size());
            assertEquals(2, undoCls.getField("currentPos").getInt(undoMgr));
            assertTrue(undoableCls.getField("died").getBoolean(added.get(0)));
            assertTrue(undoableCls.getField("died").getBoolean(added.get(1)));
            assertFalse(undoableCls.getField("died").getBoolean(added.get(2)));
            assertEquals(2L, activeBridge.snapshot().get("undoEntriesEvicted"));

            // Unbounded CUndoManager (limit <= 0, matching MeshEditor -1) falls back to DEFAULT_UNDO_LIMIT (100).
            final Object unboundedMgr = undoCls.getConstructor().newInstance();
            undoCls.getField("limit").setInt(unboundedMgr, -1);
            final List<Object> unboundedItems = new ArrayList<>();
            for (int i = 0; i < 103; i++) {
                final Object item = undoableCls.getConstructor().newInstance();
                unboundedItems.add(item);
                addEdit.invoke(unboundedMgr, item);
            }
            assertEquals(100, ((List<?>) undoCls.getField("_undoList").get(unboundedMgr)).size());
            assertEquals(100, undoCls.getField("currentPos").getInt(unboundedMgr));
            assertTrue(undoableCls.getField("died").getBoolean(unboundedItems.get(0)));
            assertTrue(undoableCls.getField("died").getBoolean(unboundedItems.get(2)));
            assertFalse(undoableCls.getField("died").getBoolean(unboundedItems.get(3)));
            assertEquals(5L, activeBridge.snapshot().get("undoEntriesEvicted"));

            // 2. Cancelled closeFile(false, false) keeps undo and holders; confirmed closeFile(true, false) clears matching only.
            final Class<?> modelDocCls = host.load(MemoryLeakMitigationTarget.MODELING_DOC_OWNER);
            final Object doc1 = modelDocCls.getConstructor().newInstance();
            final Object doc2 = modelDocCls.getConstructor().newInstance();
            final Object editMode1 = host.load("com/live2d/cubism/doc/ACEditMode").getConstructor(undoCls).newInstance(undoMgr);
            ((List<Object>) doc1.getClass().getMethod("getAllEditModes").invoke(doc1)).add(editMode1);

            final Field holderField1 = host.load("com/live2d/cubism/view/palette/parts/a/a").getField("d");
            final Field holderField2 = host.load("com/live2d/cubism/appCtrlImpl/ui/a/a").getField("d");
            holderField1.set(null, modelDocCls.getMethod("getModelSource").invoke(doc1));
            holderField2.set(null, modelDocCls.getMethod("getModelSource").invoke(doc2));

            assertFalse((Boolean) modelDocCls.getMethod("closeFile", boolean.class, boolean.class).invoke(doc1, false, false));
            assertEquals(2, ((List<?>) undoCls.getField("_undoList").get(undoMgr)).size());
            assertNotNull(holderField1.get(null));

            assertTrue((Boolean) modelDocCls.getMethod("closeFile", boolean.class, boolean.class).invoke(doc1, true, false));
            assertTrue(((List<?>) undoCls.getField("_undoList").get(undoMgr)).isEmpty());
            assertNull(holderField1.get(null), "closed document static source holder must clear");
            assertNotNull(holderField2.get(null), "unrelated open document holder must stay intact");

            // 3. CImageResource: debug list not retained, CImageResource$a.f() drains ALL idle items, CWritableImage.flush() fixed.
            final Class<?> imgResCls = host.load(MemoryLeakMitigationTarget.IMAGE_RESOURCE_OWNER);
            final Object imgRes1 = imgResCls.getConstructor().newInstance();
            final Object imgRes2 = imgResCls.getConstructor().newInstance();
            final Class<?> userIf = host.load("com/live2d/graphics/ICImageResourceUser");
            final Object user = java.lang.reflect.Proxy.newProxyInstance(
                    host.loader, new Class<?>[] {userIf}, (proxy, method, args) -> null);
            imgResCls.getMethod("releaseResource", userIf).invoke(imgRes1, user);
            assertTrue(((List<?>) imgResCls.getField("releasedRetainUserData_forDebug").get(imgRes1)).isEmpty());

            final List<Object> cacheList = (List<Object>) imgResCls.getMethod("access$getCacheList$cp").invoke(null);
            final Class<?> wrapperBCls = host.load("com/live2d/graphics/CImageResource$b");
            cacheList.add(wrapperBCls.getConstructor(imgResCls).newInstance(imgRes1));
            cacheList.add(wrapperBCls.getConstructor(imgResCls).newInstance(imgRes2));
            host.load(MemoryLeakMitigationTarget.IMAGE_RESOURCE_COMPANION_OWNER)
                    .getMethod("f")
                    .invoke(imgResCls.getField("Companion").get(null));
            assertTrue(imgResCls.getField("archived").getBoolean(imgRes1));
            assertTrue(imgResCls.getField("archived").getBoolean(imgRes2));

            final Class<?> writableCls = host.load(MemoryLeakMitigationTarget.WRITABLE_IMAGE_OWNER);
            final Class<?> cImageCls = host.load("com/live2d/graphics/CImage");
            final AtomicBoolean sentinelFlushed = new AtomicBoolean();
            final AtomicBoolean realFlushed = new AtomicBoolean();
            final TrackedBufferedImage sentinelBuf = new TrackedBufferedImage(1, sentinelFlushed);
            cImageCls.getField("_INTERNAL_NOT_INITIALIZED_J_IMAGE_").set(null, sentinelBuf);
            final Object sentinelImg = writableCls.getConstructor(java.awt.Image.class).newInstance(sentinelBuf);
            final Object realImg = writableCls.getConstructor(java.awt.Image.class).newInstance(
                    new TrackedBufferedImage(2, realFlushed));
            writableCls.getMethod("flush").invoke(realImg);
            assertTrue(realFlushed.get(), "real CWritableImage must be flushed after branch fix");
            writableCls.getMethod("flush").invoke(sentinelImg);
            assertFalse(sentinelFlushed.get(), "uninitialized sentinel must NOT be flushed after branch fix");

            // 4. palette/inspector/d.a(emptyList) clears h, i, j cached inspector targets.
            final Class<?> inspCtrlCls = host.load(MemoryLeakMitigationTarget.INSPECTOR_CTRL_OWNER);
            final Object inspCtrl = inspCtrlCls.getConstructor().newInstance();
            final Object ciInsp = host.load("com/live2d/inspector/simpleInspectors/ci").getConstructor().newInstance();
            inspCtrlCls.getField("h").set(inspCtrl, String.class);
            inspCtrlCls.getField("i").set(inspCtrl, List.of("retained"));
            ((Map<Object, Object>) inspCtrlCls.getField("j").get(inspCtrl)).put("k", ciInsp);
            inspCtrlCls.getMethod("a", List.class).invoke(inspCtrl, List.of());
            assertNull(inspCtrlCls.getField("h").get(inspCtrl));
            assertNull(inspCtrlCls.getField("i").get(inspCtrl));
            assertTrue((Boolean) ciInsp.getClass().getField("editModeCleared").get(ciInsp));
            assertTrue((Boolean) ciInsp.getClass().getField("valuesCleared").get(ciInsp));

            // 5. ParameterGroupRowWidget.renameFolder() cleans up Toolkit AWTEventListener on closeFile.
            final Class<?> rowWidgetCls = host.load(MemoryLeakMitigationTarget.PARAM_GROUP_ROW_WIDGET_OWNER);
            final Object rowWidget = rowWidgetCls.getConstructor().newInstance();
            rowWidgetCls.getMethod("renameFolder").invoke(rowWidget);
            registeredAwtGuard = true;
            assertTrue(activeBridge.snapshot().get("renameListenersCleaned") >= 0L);
            // Triggering doc close prunes the active listener from Toolkit safely.
            assertTrue((Boolean) modelDocCls.getMethod("closeFile", boolean.class, boolean.class).invoke(doc2, true, false));
            assertTrue(activeBridge.snapshot().get("renameListenersCleaned") >= 1L);
        } finally {
            Files.deleteIfExists(fakeJar);
        }
    }

    private WeakReference<Object> buildAndVerifyInitialPartsTree(
            final Object panel, final Class<?> panelCls, final Map<?, ?> keyRegistry) throws Exception {
        final Method updateMethod = panelCls.getMethod("b", boolean.class, boolean.class);
        final Field treeField = panelCls.getField("b");
        updateMethod.invoke(panel, false, false);
        final Object tree1 = treeField.get(panel);
        assertNotNull(tree1);
        assertEquals(1, keyRegistry.size());
        updateMethod.invoke(panel, false, false);
        updateMethod.invoke(panel, false, false);
        assertSame(tree1, treeField.get(panel));
        assertTrue(activeBridge.snapshot().get("partsTreesReused") >= 2L);
        return new WeakReference<>(tree1);
    }

    private SyntheticHost weaveAndBootBridge(final Path artifact) throws Exception {
        final SyntheticHost host = new SyntheticHost(artifact);
        final MemoryLeakMitigationTarget target =
                MemoryLeakMitigationTarget.of(ReviewedHostArtifacts.CUBISM_5_3_04).orElseThrow();
        final MemoryLeakMitigationTransformer transformer =
                new MemoryLeakMitigationTransformer(host.loader, artifact, target);
        host.defineAll(transformer);
        assertEquals(EXPECTED_REWRITTEN_OWNERS, transformer.matches(), transformer.failure());
        assertEquals(MemoryLeakMitigationTransformer.EXPECTED_SITES, transformer.sites(), transformer.failure());
        assertNull(transformer.failure());
        activeBridge = new MemoryLeakMitigationBridge(host.loader, target);
        activeBridge.install();
        return host;
    }

    private static List<Path> findLocalCubismJars() throws IOException {
        final String prop = System.getProperty("turboism.test.cubismRefDir", System.getenv().getOrDefault("TURBOISM_CUBISM_REF", ""));
        final Path root = !prop.isBlank() ? Path.of(prop) : DEFAULT_LOCAL_ARTIFACTS;
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var stream = Files.list(root)) {
            return stream.filter(Files::isDirectory)
                    .sorted()
                    .map(dir -> dir.resolve("Live2D_Cubism.jar"))
                    .filter(Files::isRegularFile)
                    .toList();
        }
    }

    private static URLClassLoader openHostJarLoader(final Path jarPath) throws IOException {
        final List<URL> urls = new ArrayList<>();
        try (var files = Files.walk(jarPath.getParent())) {
            files.filter(p -> p.toString().endsWith(".jar"))
                    .map(MemoryLeakMitigationTest::toUrlUnchecked)
                    .forEach(urls::add);
        }
        for (final Path extra : List.of(
                jarPath.getParent().resolve("jogl-all.jar"),
                jarPath.getParent().resolve("jogl/jogl-all.jar"),
                jarPath.getParent().getParent().resolve("5.3.03/jogl-all.jar"),
                jarPath.getParent().resolve("gluegen-rt.jar"),
                jarPath.getParent().resolve("jogl/gluegen-rt.jar"),
                jarPath.getParent().getParent().resolve("5.3.03/gluegen-rt.jar"))) {
            if (Files.isRegularFile(extra)) {
                final URL url = toUrlUnchecked(extra);
                if (!urls.contains(url)) {
                    urls.add(url);
                }
            }
        }
        return new URLClassLoader(urls.toArray(URL[]::new), MemoryLeakMitigationTest.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(final String name) throws ClassNotFoundException {
                try {
                    return super.findClass(name);
                } catch (final ClassNotFoundException missing) {
                    if (name.startsWith("com.jogamp.common.")
                            || "org.jdom.Element".equals(name)
                            || name.startsWith("com.formdev.flatlaf.")) {
                        final String internal = name.replace('.', '/');
                        final boolean isInterface = name.endsWith("WriteCloneable") || name.endsWith("Lock");
                        final String superOwner;
                        if ("com.jogamp.common.util.VersionNumberString".equals(name)) {
                            superOwner = "com/jogamp/common/util/VersionNumber";
                        } else if (name.endsWith("CheckBox")) {
                            superOwner = "javax/swing/JCheckBox";
                        } else {
                            superOwner = "java/lang/Object";
                        }
                        final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                        final int acc = isInterface ? (ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT) : ACC_PUBLIC;
                        cw.visit(V17, acc, internal, null, superOwner, null);
                        if (!isInterface) {
                            emitDefaultCtor(cw, superOwner);
                        }
                        cw.visitEnd();
                        final byte[] stub = cw.toByteArray();
                        return defineClass(name, stub, 0, stub.length);
                    }
                    throw missing;
                }
            }

            @Override
            protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null && (name.startsWith("com.jogamp.") || name.startsWith("com.live2d."))) {
                        try {
                            loaded = findClass(name);
                        } catch (final ClassNotFoundException ignored) {
                            // Fall through to parent loader.
                        }
                    }
                    if (loaded != null) {
                        if (resolve) {
                            resolveClass(loaded);
                        }
                        return loaded;
                    }
                }
                return super.loadClass(name, resolve);
            }
        };
    }

    private static URL toUrlUnchecked(final Path path) {
        try {
            return path.toUri().toURL();
        } catch (final IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static byte[] readJarEntry(final JarFile jar, final String entryName) throws IOException {
        final JarEntry entry = jar.getJarEntry(entryName);
        if (entry == null) {
            return null;
        }
        try (InputStream in = jar.getInputStream(entry)) {
            return in.readAllBytes();
        }
    }

    private static void forceGcUntilCollected(final WeakReference<Object> ref) {
        for (int attempt = 0; attempt < 20 && ref.get() != null; attempt++) {
            final byte[] scratch = new byte[2 * 1024 * 1024];
            if (scratch.length == 0) {
                throw new AssertionError();
            }
            System.gc();
            try {
                Thread.sleep(10L);
            } catch (final InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private static byte[] buildSimpleVoidClass(final String owner, final String methodName) {
        final ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        w.visit(V17, ACC_PUBLIC, owner, null, "java/lang/Object", null);
        emitDefaultCtor(w, "java/lang/Object");
        final MethodVisitor m = w.visitMethod(ACC_PUBLIC, methodName, "()V", null, null);
        m.visitCode();
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    private static void emitDefaultCtor(final ClassWriter w, final String superOwner) {
        final MethodVisitor m = w.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKESPECIAL, superOwner, "<init>", "()V", false);
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    private static final class TrackedBufferedImage extends BufferedImage {
        private final AtomicBoolean flushed;

        private TrackedBufferedImage(final int size, final AtomicBoolean flushed) {
            super(size, size, BufferedImage.TYPE_INT_ARGB);
            this.flushed = flushed;
        }

        @Override
        public void flush() {
            super.flush();
            if (flushed != null) {
                flushed.set(true);
            }
        }
    }

    private static final class StubLoader extends URLClassLoader {
        private final ProtectionDomain domain;
        private final Map<String, byte[]> classes = new HashMap<>();

        private StubLoader(final Path artifact) throws IOException {
            super(new URL[0], MemoryLeakMitigationTest.class.getClassLoader());
            this.domain = new ProtectionDomain(
                    new CodeSource(artifact.toUri().toURL(), (java.security.CodeSigner[]) null), new Permissions());
        }

        private void registerAll(final Map<String, byte[]> defs) {
            classes.putAll(defs);
        }

        @Override
        protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null && classes.containsKey(name.replace('.', '/'))) {
                    loaded = findClass(name);
                }
                if (loaded != null) {
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
            final String internal = name.replace('.', '/');
            final byte[] bytes = classes.remove(internal);
            if (bytes != null) {
                return defineClass(name, bytes, 0, bytes.length, domain);
            }
            return super.findClass(name);
        }
    }

    private static final class SyntheticHost {
        private final StubLoader loader;
        private final Map<String, byte[]> rawClasses = new HashMap<>();

        private SyntheticHost(final Path artifact) throws IOException {
            this.loader = new StubLoader(artifact);
            buildAll();
        }

        private void defineAll(final MemoryLeakMitigationTransformer transformer) {
            final Map<String, byte[]> finalDefs = new HashMap<>();
            for (final Map.Entry<String, byte[]> entry : rawClasses.entrySet()) {
                final byte[] woven = transformer.transform(
                        null, loader, entry.getKey(), null, loader.domain, entry.getValue());
                finalDefs.put(entry.getKey(), woven != null ? woven : entry.getValue());
            }
            loader.registerAll(finalDefs);
        }

        private Class<?> load(final String internalName) throws ClassNotFoundException {
            return loader.loadClass(internalName.replace('/', '.'));
        }

        private void renameFirstPart(final Object panel, final String newName) throws Exception {
            final List<?> parts = (List<?>) panel.getClass().getMethod("t").invoke(panel);
            final Object part = parts.get(0);
            part.getClass().getField("localName").set(part, newName);
        }

        private Object buildMeshEditorWithBuffer(final Object vbo) throws Exception {
            final Class<?> vcCls = load(MemoryLeakMitigationTarget.MESH_EDITOR_VC_OWNER);
            final Object vc = vcCls.getConstructor().newInstance();
            final Object root = load(MemoryLeakMitigationTarget.G_ENTITY_OWNER).getConstructor().newInstance();
            final Object child = load(MemoryLeakMitigationTarget.G_ENTITY_OWNER).getConstructor().newInstance();
            final Object mesh = load("com/live2d/graphics3d/mesh/GMesh2").getConstructor().newInstance();
            mesh.getClass().getField("posBuffer").set(mesh, vbo);
            final Object container = load("com/live2d/graphics3d/component/GMeshContainer")
                    .getConstructor(load("com/live2d/graphics3d/mesh/a"))
                    .newInstance(mesh);
            final Object renderer = load(MemoryLeakMitigationTarget.G_MESH_RENDERER_OWNER).getConstructor(container.getClass()).newInstance(container);
            ((List<Object>) child.getClass().getMethod("getComponents").invoke(child)).add(renderer);
            ((List<Object>) root.getClass().getMethod("getChildren").invoke(root)).add(child);
            vcCls.getField("parentOfMeshes_entity").set(vc, root);
            return vc;
        }

        private void buildAll() {
            addSimpleArrayList("com/live2d/type/CArrayList");
            addSimpleInterface("com/live2d/core/a");
            addSimpleInterface("com/live2d/doc/IEditMode");
            addSimpleInterface("com/live2d/graphics/ICImageResourceUser");
            addSimpleClass("com/live2d/util/f/n", null, new String[] {"e:Ljava/util/HashMap;:S"}, true);
            addSimpleClass("com/live2d/graphics3d/rendering/b", null, new String[] {"b:Ljava/lang/Object;:S", "c:Ljava/util/HashMap;:S"}, true);
            addSimpleClass("com/live2d/type/CGuid", null, new String[0], false);
            addSimpleClass("com/live2d/cubism/doc/model/drawable/ACDrawable", null, new String[0], false, "isClipper:()Z:false");
            addSimpleClass("com/live2d/cubism/doc/model/ACParameterControllableSource", null, new String[] {"id:Lcom/live2d/type/CGuid;:I", "localName:Ljava/lang/String;:I"}, false,
                    "getLocalName:()Ljava/lang/String;:field:localName");
            addControllableMethods();
            addPartsHierarchyTypes();
            addGlTypes();
            addEntityAndMeshTypes();
            addTextureTypes();
            addWrapperAndContextTypes();
            addUndoAndDocumentTypes();
            addImageTypes();
            addInspectorAndParamWidgetTypes();
        }

        private void addControllableMethods() {
            final ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            w.visit(V17, ACC_PUBLIC, "com/live2d/type/CGuid", null, "java/lang/Object", null);
            w.visitField(ACC_PUBLIC, "idStr", "Ljava/lang/String;", null, "id-1").visitEnd();
            emitDefaultCtor(w, "java/lang/Object");
            final MethodVisitor m = w.visitMethod(ACC_PUBLIC, "getIdString", "()Ljava/lang/String;", null, null);
            m.visitCode();
            m.visitVarInsn(ALOAD, 0);
            m.visitFieldInsn(GETFIELD, "com/live2d/type/CGuid", "idStr", "Ljava/lang/String;");
            m.visitInsn(ARETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
            w.visitEnd();
            rawClasses.put("com/live2d/type/CGuid", w.toByteArray());

            final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            cw.visit(V17, ACC_PUBLIC, "com/live2d/cubism/doc/model/ACParameterControllableSource", null, "java/lang/Object", null);
            cw.visitField(ACC_PUBLIC, "id", "Lcom/live2d/type/CGuid;", null, null).visitEnd();
            cw.visitField(ACC_PUBLIC, "localName", "Ljava/lang/String;", null, null).visitEnd();
            final MethodVisitor ctor = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
            ctor.visitCode();
            ctor.visitVarInsn(ALOAD, 0);
            ctor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            ctor.visitVarInsn(ALOAD, 0);
            ctor.visitTypeInsn(NEW, "com/live2d/type/CGuid");
            ctor.visitInsn(DUP);
            ctor.visitMethodInsn(INVOKESPECIAL, "com/live2d/type/CGuid", "<init>", "()V", false);
            ctor.visitFieldInsn(PUTFIELD, "com/live2d/cubism/doc/model/ACParameterControllableSource", "id", "Lcom/live2d/type/CGuid;");
            ctor.visitVarInsn(ALOAD, 0);
            ctor.visitLdcInsn("PartA");
            ctor.visitFieldInsn(PUTFIELD, "com/live2d/cubism/doc/model/ACParameterControllableSource", "localName", "Ljava/lang/String;");
            ctor.visitInsn(RETURN);
            ctor.visitMaxs(0, 0);
            ctor.visitEnd();
            emitFieldGetter(cw, "com/live2d/cubism/doc/model/ACParameterControllableSource", "getId", "()Lcom/live2d/type/CGuid;", "id");
            emitFieldGetter(cw, "com/live2d/cubism/doc/model/ACParameterControllableSource", "getLocalName", "()Ljava/lang/String;", "localName");
            cw.visitEnd();
            rawClasses.put("com/live2d/cubism/doc/model/ACParameterControllableSource", cw.toByteArray());
        }

        private void addPartsHierarchyTypes() {
            final ClassWriter ps = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            ps.visit(V17, ACC_PUBLIC, "com/live2d/cubism/doc/model/parts/CPartSource", null, "com/live2d/cubism/doc/model/ACParameterControllableSource", null);
            ps.visitField(ACC_PUBLIC, "children", "Ljava/util/List;", null, null).visitEnd();
            final MethodVisitor psCtor = ps.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
            psCtor.visitCode();
            psCtor.visitVarInsn(ALOAD, 0);
            psCtor.visitMethodInsn(INVOKESPECIAL, "com/live2d/cubism/doc/model/ACParameterControllableSource", "<init>", "()V", false);
            psCtor.visitVarInsn(ALOAD, 0);
            psCtor.visitTypeInsn(NEW, "java/util/ArrayList");
            psCtor.visitInsn(DUP);
            psCtor.visitMethodInsn(INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
            psCtor.visitFieldInsn(PUTFIELD, "com/live2d/cubism/doc/model/parts/CPartSource", "children", "Ljava/util/List;");
            psCtor.visitInsn(RETURN);
            psCtor.visitMaxs(0, 0);
            psCtor.visitEnd();
            emitFieldGetter(ps, "com/live2d/cubism/doc/model/parts/CPartSource", "getChildren", "()Ljava/util/List;", "children");
            ps.visitEnd();
            rawClasses.put("com/live2d/cubism/doc/model/parts/CPartSource", ps.toByteArray());

            final ClassWriter am = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            am.visit(V17, ACC_PUBLIC, "com/live2d/cubism/doc/model/drawable/artMesh/CArtMeshSource", null, "com/live2d/cubism/doc/model/ACParameterControllableSource", null);
            emitDefaultCtor(am, "com/live2d/cubism/doc/model/ACParameterControllableSource");
            emitReturnNull(am, "getAlphaComposition", "()Ljava/lang/Object;");
            emitReturnNewList(am, "getClipGuidList");
            emitBooleanGetter(am, "getInvertClippingMask", false);
            emitReturnNull(am, "getGuid", "()Lcom/live2d/type/CGuid;");
            am.visitEnd();
            rawClasses.put("com/live2d/cubism/doc/model/drawable/artMesh/CArtMeshSource", am.toByteArray());

            final ClassWriter cm = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            cm.visit(V17, ACC_PUBLIC, "com/live2d/cubism/doc/model/CModel", null, "java/lang/Object", null);
            emitDefaultCtor(cm, "java/lang/Object");
            final MethodVisitor gd = cm.visitMethod(ACC_PUBLIC, "getDrawable", "(Lcom/live2d/type/CGuid;)Lcom/live2d/cubism/doc/model/drawable/ACDrawable;", null, null);
            gd.visitCode();
            gd.visitInsn(ACONST_NULL);
            gd.visitInsn(ARETURN);
            gd.visitMaxs(0, 0);
            gd.visitEnd();
            cm.visitEnd();
            rawClasses.put("com/live2d/cubism/doc/model/CModel", cm.toByteArray());

            final ClassWriter ms = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            ms.visit(V17, ACC_PUBLIC, MemoryLeakMitigationTarget.MODEL_SOURCE_OWNER, null, "java/lang/Object", null);
            emitDefaultCtor(ms, "java/lang/Object");
            ms.visitEnd();
            rawClasses.put(MemoryLeakMitigationTarget.MODEL_SOURCE_OWNER, ms.toByteArray());

            final ClassWriter rt = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            rt.visit(V17, ACC_PUBLIC, "com/live2d/cubism/CEUpdateManager$b", null, "java/lang/Object", null);
            rt.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "b", "Lcom/live2d/cubism/CEUpdateManager$b;", null, null).visitEnd();
            emitDefaultCtor(rt, "java/lang/Object");
            final MethodVisitor clinit = rt.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
            clinit.visitCode();
            clinit.visitTypeInsn(NEW, "com/live2d/cubism/CEUpdateManager$b");
            clinit.visitInsn(DUP);
            clinit.visitMethodInsn(INVOKESPECIAL, "com/live2d/cubism/CEUpdateManager$b", "<init>", "()V", false);
            clinit.visitFieldInsn(PUTSTATIC, "com/live2d/cubism/CEUpdateManager$b", "b", "Lcom/live2d/cubism/CEUpdateManager$b;");
            clinit.visitInsn(RETURN);
            clinit.visitMaxs(0, 0);
            clinit.visitEnd();
            rt.visitEnd();
            rawClasses.put("com/live2d/cubism/CEUpdateManager$b", rt.toByteArray());

            final ClassWriter um = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            um.visit(V17, ACC_PUBLIC, "com/live2d/cubism/CEUpdateManager", null, "java/lang/Object", null);
            emitDefaultCtor(um, "java/lang/Object");
            emitReturnNewList(um, "getSelectionGuidList");
            emitVoidMethod(um, "expandByObjectGuidPartsPalette", "(Ljava/util/List;)V");
            um.visitEnd();
            rawClasses.put("com/live2d/cubism/CEUpdateManager", um.toByteArray());

            final ClassWriter pk = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            pk.visit(V17, ACC_PUBLIC, "com/live2d/cubism/pack/CECompletePack", null, "java/lang/Object", null);
            pk.visitField(ACC_PUBLIC, "um", "Lcom/live2d/cubism/CEUpdateManager;", null, null).visitEnd();
            final MethodVisitor pkc = pk.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
            pkc.visitCode();
            pkc.visitVarInsn(ALOAD, 0);
            pkc.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            pkc.visitVarInsn(ALOAD, 0);
            pkc.visitTypeInsn(NEW, "com/live2d/cubism/CEUpdateManager");
            pkc.visitInsn(DUP);
            pkc.visitMethodInsn(INVOKESPECIAL, "com/live2d/cubism/CEUpdateManager", "<init>", "()V", false);
            pkc.visitFieldInsn(PUTFIELD, "com/live2d/cubism/pack/CECompletePack", "um", "Lcom/live2d/cubism/CEUpdateManager;");
            pkc.visitInsn(RETURN);
            pkc.visitMaxs(0, 0);
            pkc.visitEnd();
            emitFieldGetter(pk, "com/live2d/cubism/pack/CECompletePack", "getUpdateManager", "()Lcom/live2d/cubism/CEUpdateManager;", "um");
            emitVoidMethod(pk, "repaintCanvas", "(Lcom/live2d/cubism/CEUpdateManager$b;)V");
            pk.visitEnd();
            rawClasses.put("com/live2d/cubism/pack/CECompletePack", pk.toByteArray());

            addUiWidgetAndPartsPanel();
        }

        private void addUiWidgetAndPartsPanel() {
            // Controls extending simple stubs for PartsPaletteHelper.
            for (final String ctrl : List.of("com/live2d/ui/control/CTextField", "com/live2d/ui/control/CTwoStateIconButton", "com/live2d/ui/control/CCheckBox")) {
                final ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
                w.visit(V17, ACC_PUBLIC, ctrl, null, "com/live2d/ui/CWidget", null);
                emitDefaultCtor(w, "com/live2d/ui/CWidget");
                if (ctrl.endsWith("CTextField")) {
                    final MethodVisitor gt = w.visitMethod(ACC_PUBLIC, "getText", "()Ljava/lang/String;", null, null);
                    gt.visitCode();
                    gt.visitLdcInsn("");
                    gt.visitInsn(ARETURN);
                    gt.visitMaxs(0, 0);
                    gt.visitEnd();
                } else if (ctrl.endsWith("CTwoStateIconButton")) {
                    final MethodVisitor gs = w.visitMethod(ACC_PUBLIC, "getState", "()Ljava/lang/Object;", null, null);
                    gs.visitCode();
                    gs.visitLdcInsn("A");
                    gs.visitInsn(ARETURN);
                    gs.visitMaxs(0, 0);
                    gs.visitEnd();
                } else {
                    emitBooleanGetter(w, "isSelected", false);
                }
                w.visitEnd();
                rawClasses.put(ctrl, w.toByteArray());
            }

            // com/live2d/ui/m holding KeyListener & FocusListener
            final ClassWriter uim = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            uim.visit(V17, ACC_PUBLIC, MemoryLeakMitigationTarget.UI_EVENT_HANDLER_OWNER, null, "java/lang/Object", null);
            uim.visitField(ACC_PUBLIC, "kl", "Ljava/awt/event/KeyListener;", null, null).visitEnd();
            uim.visitField(ACC_PUBLIC, "fl", "Ljava/awt/event/FocusListener;", null, null).visitEnd();
            emitDefaultCtor(uim, "java/lang/Object");
            uim.visitEnd();
            rawClasses.put(MemoryLeakMitigationTarget.UI_EVENT_HANDLER_OWNER, uim.toByteArray());

            // com/live2d/ui/CWidget
            final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            cw.visit(V17, ACC_PUBLIC, MemoryLeakMitigationTarget.C_WIDGET_OWNER, null, "java/lang/Object", null);
            cw.visitField(ACC_PUBLIC, "eventImpl", "L" + MemoryLeakMitigationTarget.UI_EVENT_HANDLER_OWNER + ";", null, null).visitEnd();
            cw.visitField(ACC_PUBLIC, "comp", "Ljavax/swing/JComponent;", null, null).visitEnd();
            final MethodVisitor cwc = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
            cwc.visitCode();
            cwc.visitVarInsn(ALOAD, 0);
            cwc.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            cwc.visitVarInsn(ALOAD, 0);
            cwc.visitTypeInsn(NEW, MemoryLeakMitigationTarget.UI_EVENT_HANDLER_OWNER);
            cwc.visitInsn(DUP);
            cwc.visitMethodInsn(INVOKESPECIAL, MemoryLeakMitigationTarget.UI_EVENT_HANDLER_OWNER, "<init>", "()V", false);
            cwc.visitFieldInsn(PUTFIELD, MemoryLeakMitigationTarget.C_WIDGET_OWNER, "eventImpl", "L" + MemoryLeakMitigationTarget.UI_EVENT_HANDLER_OWNER + ";");
            cwc.visitVarInsn(ALOAD, 0);
            cwc.visitTypeInsn(NEW, "javax/swing/JPanel");
            cwc.visitInsn(DUP);
            cwc.visitMethodInsn(INVOKESPECIAL, "javax/swing/JPanel", "<init>", "()V", false);
            cwc.visitFieldInsn(PUTFIELD, MemoryLeakMitigationTarget.C_WIDGET_OWNER, "comp", "Ljavax/swing/JComponent;");
            cwc.visitInsn(RETURN);
            cwc.visitMaxs(0, 0);
            cwc.visitEnd();
            emitFieldGetter(cw, MemoryLeakMitigationTarget.C_WIDGET_OWNER, "getJComponent", "()Ljavax/swing/JComponent;", "comp");
            cw.visitEnd();
            rawClasses.put(MemoryLeakMitigationTarget.C_WIDGET_OWNER, cw.toByteArray());

            final ClassWriter vbox = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            vbox.visit(V17, ACC_PUBLIC, MemoryLeakMitigationTarget.CVBOX_OWNER, null, "com/live2d/ui/CWidget", null);
            emitDefaultCtor(vbox, "com/live2d/ui/CWidget");
            emitVoidMethod(vbox, MemoryLeakMitigationTarget.REMOVE_ALL_METHOD, "()V");
            vbox.visitEnd();
            rawClasses.put(MemoryLeakMitigationTarget.CVBOX_OWNER, vbox.toByteArray());

            // CTreeTable extends CWidget
            final ClassWriter tt = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            tt.visit(V17, ACC_PUBLIC, MemoryLeakMitigationTarget.TREE_TABLE_OWNER, null, MemoryLeakMitigationTarget.C_WIDGET_OWNER, null);
            tt.visitField(ACC_PUBLIC, "jTree", "Ljavax/swing/JTree;", null, null).visitEnd();
            tt.visitField(ACC_PUBLIC, "table", "Ljavax/swing/JTable;", null, null).visitEnd();
            final MethodVisitor ttc = tt.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
            ttc.visitCode();
            ttc.visitVarInsn(ALOAD, 0);
            ttc.visitMethodInsn(INVOKESPECIAL, MemoryLeakMitigationTarget.C_WIDGET_OWNER, "<init>", "()V", false);
            ttc.visitVarInsn(ALOAD, 0);
            ttc.visitTypeInsn(NEW, "javax/swing/JTree");
            ttc.visitInsn(DUP);
            ttc.visitMethodInsn(INVOKESPECIAL, "javax/swing/JTree", "<init>", "()V", false);
            ttc.visitFieldInsn(PUTFIELD, MemoryLeakMitigationTarget.TREE_TABLE_OWNER, "jTree", "Ljavax/swing/JTree;");
            ttc.visitVarInsn(ALOAD, 0);
            ttc.visitTypeInsn(NEW, "javax/swing/JTable");
            ttc.visitInsn(DUP);
            ttc.visitMethodInsn(INVOKESPECIAL, "javax/swing/JTable", "<init>", "()V", false);
            ttc.visitFieldInsn(PUTFIELD, MemoryLeakMitigationTarget.TREE_TABLE_OWNER, "table", "Ljavax/swing/JTable;");
            ttc.visitInsn(RETURN);
            ttc.visitMaxs(0, 0);
            ttc.visitEnd();
            emitFieldGetter(tt, MemoryLeakMitigationTarget.TREE_TABLE_OWNER, "getJTable", "()Ljavax/swing/JTable;", "table");
            tt.visitEnd();
            rawClasses.put(MemoryLeakMitigationTarget.TREE_TABLE_OWNER, tt.toByteArray());

            addPartsPanelClass();
        }

        private void addPartsPanelClass() {
            final String owner = MemoryLeakMitigationTarget.PARTS_PANEL_OWNER;
            final ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            w.visit(V17, ACC_PUBLIC, owner, null, "java/lang/Object", null);
            w.visitField(ACC_PUBLIC, "c", "Lcom/live2d/cubism/pack/CECompletePack;", null, null).visitEnd();
            w.visitField(ACC_PUBLIC, "doc", "Lcom/live2d/cubism/doc/modeling/CModelingDocument;", null, null).visitEnd();
            w.visitField(ACC_PUBLIC, "model", "Lcom/live2d/cubism/doc/model/CModel;", null, null).visitEnd();
            w.visitField(ACC_PUBLIC, "vc", "Lcom/live2d/cubism/view/context/CEViewContext;", null, null).visitEnd();
            w.visitField(ACC_PUBLIC, "b", "L" + MemoryLeakMitigationTarget.TREE_TABLE_OWNER + ";", null, null).visitEnd();
            w.visitField(ACC_PUBLIC, "f", "L" + MemoryLeakMitigationTarget.CVBOX_OWNER + ";", null, null).visitEnd();
            w.visitField(ACC_PUBLIC, "topParts", "Ljava/util/List;", null, null).visitEnd();
            w.visitField(ACC_PUBLIC, "B", "Z", null, null).visitEnd(); // Last boolean field = init flag

            final MethodVisitor ctor = w.visitMethod(
                    ACC_PUBLIC,
                    "<init>",
                    "(Lcom/live2d/cubism/pack/CECompletePack;Lcom/live2d/cubism/doc/modeling/CModelingDocument;"
                            + "Lcom/live2d/cubism/doc/model/CModel;Lcom/live2d/cubism/view/context/CEViewContext;)V",
                    null,
                    null);
            ctor.visitCode();
            ctor.visitVarInsn(ALOAD, 0);
            ctor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            ctor.visitVarInsn(ALOAD, 0); ctor.visitVarInsn(ALOAD, 1); ctor.visitFieldInsn(PUTFIELD, owner, "c", "Lcom/live2d/cubism/pack/CECompletePack;");
            ctor.visitVarInsn(ALOAD, 0); ctor.visitVarInsn(ALOAD, 2); ctor.visitFieldInsn(PUTFIELD, owner, "doc", "Lcom/live2d/cubism/doc/modeling/CModelingDocument;");
            ctor.visitVarInsn(ALOAD, 0); ctor.visitVarInsn(ALOAD, 3); ctor.visitFieldInsn(PUTFIELD, owner, "model", "Lcom/live2d/cubism/doc/model/CModel;");
            ctor.visitVarInsn(ALOAD, 0); ctor.visitVarInsn(ALOAD, 4); ctor.visitFieldInsn(PUTFIELD, owner, "vc", "Lcom/live2d/cubism/view/context/CEViewContext;");
            ctor.visitVarInsn(ALOAD, 0);
            ctor.visitTypeInsn(NEW, MemoryLeakMitigationTarget.CVBOX_OWNER);
            ctor.visitInsn(DUP);
            ctor.visitMethodInsn(INVOKESPECIAL, MemoryLeakMitigationTarget.CVBOX_OWNER, "<init>", "()V", false);
            ctor.visitFieldInsn(PUTFIELD, owner, "f", "L" + MemoryLeakMitigationTarget.CVBOX_OWNER + ";");
            ctor.visitVarInsn(ALOAD, 0);
            ctor.visitTypeInsn(NEW, "java/util/ArrayList");
            ctor.visitInsn(DUP);
            ctor.visitMethodInsn(INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
            ctor.visitFieldInsn(PUTFIELD, owner, "topParts", "Ljava/util/List;");
            ctor.visitVarInsn(ALOAD, 0);
            ctor.visitFieldInsn(GETFIELD, owner, "topParts", "Ljava/util/List;");
            ctor.visitTypeInsn(NEW, "com/live2d/cubism/doc/model/parts/CPartSource");
            ctor.visitInsn(DUP);
            ctor.visitMethodInsn(INVOKESPECIAL, "com/live2d/cubism/doc/model/parts/CPartSource", "<init>", "()V", false);
            ctor.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true);
            ctor.visitInsn(POP);
            ctor.visitInsn(RETURN);
            ctor.visitMaxs(0, 0);
            ctor.visitEnd();

            emitFieldGetter(w, owner, "p", "()Lcom/live2d/cubism/doc/modeling/CModelingDocument;", "doc");
            emitFieldGetter(w, owner, "s", "()Lcom/live2d/cubism/doc/model/CModel;", "model");
            emitFieldGetter(w, owner, "v", "()Lcom/live2d/cubism/view/context/CEViewContext;", "vc");
            emitFieldGetter(w, owner, "t", "()Ljava/util/List;", "topParts");

            // a(CTreeTable)
            final MethodVisitor setTree = w.visitMethod(ACC_PUBLIC, "a", MemoryLeakMitigationTarget.PARTS_SET_TREE_DESCRIPTOR, null, null);
            setTree.visitCode();
            setTree.visitVarInsn(ALOAD, 0);
            setTree.visitVarInsn(ALOAD, 1);
            setTree.visitFieldInsn(PUTFIELD, owner, "b", "L" + MemoryLeakMitigationTarget.TREE_TABLE_OWNER + ";");
            setTree.visitInsn(RETURN);
            setTree.visitMaxs(0, 0);
            setTree.visitEnd();

            // b(ZZ): builds a new CTreeTable, registers KeyListener in f/n.e, calls a(CTreeTable), sets B=true.
            final MethodVisitor upd = w.visitMethod(ACC_PUBLIC, "b", "(ZZ)V", null, null);
            upd.visitCode();
            upd.visitTypeInsn(NEW, MemoryLeakMitigationTarget.TREE_TABLE_OWNER);
            upd.visitInsn(DUP);
            upd.visitMethodInsn(INVOKESPECIAL, MemoryLeakMitigationTarget.TREE_TABLE_OWNER, "<init>", "()V", false);
            upd.visitVarInsn(ASTORE, 3);
            upd.visitMethodInsn(INVOKESTATIC, "TestKeyListener", "create", "()Ljava/awt/event/KeyListener;", false);
            upd.visitVarInsn(ASTORE, 4);
            upd.visitVarInsn(ALOAD, 3);
            upd.visitFieldInsn(GETFIELD, MemoryLeakMitigationTarget.TREE_TABLE_OWNER, "eventImpl", "L" + MemoryLeakMitigationTarget.UI_EVENT_HANDLER_OWNER + ";");
            upd.visitVarInsn(ALOAD, 4);
            upd.visitFieldInsn(PUTFIELD, MemoryLeakMitigationTarget.UI_EVENT_HANDLER_OWNER, "kl", "Ljava/awt/event/KeyListener;");
            upd.visitFieldInsn(GETSTATIC, MemoryLeakMitigationTarget.KEY_REGISTRY_OWNER_53_PLUS, "e", "Ljava/util/HashMap;");
            upd.visitVarInsn(ALOAD, 4);
            upd.visitVarInsn(ALOAD, 4);
            upd.visitMethodInsn(INVOKEVIRTUAL, "java/util/HashMap", "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
            upd.visitInsn(POP);
            upd.visitVarInsn(ALOAD, 0);
            upd.visitVarInsn(ALOAD, 3);
            upd.visitMethodInsn(INVOKEVIRTUAL, owner, "a", MemoryLeakMitigationTarget.PARTS_SET_TREE_DESCRIPTOR, false);
            upd.visitVarInsn(ALOAD, 0);
            upd.visitInsn(ICONST_1);
            upd.visitFieldInsn(PUTFIELD, owner, "B", "Z");
            upd.visitInsn(RETURN);
            upd.visitMaxs(0, 0);
            upd.visitEnd();

            // y() calls CVBox.removeAll()
            final MethodVisitor closeM = w.visitMethod(ACC_PUBLIC, MemoryLeakMitigationTarget.PARTS_CLOSE_METHOD_MODERN, "()V", null, null);
            closeM.visitCode();
            closeM.visitVarInsn(ALOAD, 0);
            closeM.visitFieldInsn(GETFIELD, owner, "f", "L" + MemoryLeakMitigationTarget.CVBOX_OWNER + ";");
            closeM.visitMethodInsn(INVOKEVIRTUAL, MemoryLeakMitigationTarget.CVBOX_OWNER, MemoryLeakMitigationTarget.REMOVE_ALL_METHOD, "()V", false);
            closeM.visitInsn(RETURN);
            closeM.visitMaxs(0, 0);
            closeM.visitEnd();

            w.visitEnd();
            rawClasses.put(owner, w.toByteArray());

            // TestKeyListener factory
            final ClassWriter tkl = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            tkl.visit(V17, ACC_PUBLIC, "TestKeyListener", null, "java/lang/Object", new String[] {"java/awt/event/KeyListener"});
            emitDefaultCtor(tkl, "java/lang/Object");
            for (final String mName : List.of("keyTyped", "keyPressed", "keyReleased")) {
                emitVoidMethod(tkl, mName, "(Ljava/awt/event/KeyEvent;)V");
            }
            final MethodVisitor cr = tkl.visitMethod(ACC_PUBLIC | ACC_STATIC, "create", "()Ljava/awt/event/KeyListener;", null, null);
            cr.visitCode();
            cr.visitTypeInsn(NEW, "TestKeyListener");
            cr.visitInsn(DUP);
            cr.visitMethodInsn(INVOKESPECIAL, "TestKeyListener", "<init>", "()V", false);
            cr.visitInsn(ARETURN);
            cr.visitMaxs(0, 0);
            cr.visitEnd();
            tkl.visitEnd();
            rawClasses.put("TestKeyListener", tkl.toByteArray());
        }

        private void addGlTypes() {
            // GLContext
            final ClassWriter ctx = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            ctx.visit(V17, ACC_PUBLIC, "com/jogamp/opengl/GLContext", null, "java/lang/Object", null);
            ctx.visitField(ACC_PUBLIC | ACC_STATIC, "CURRENT", "Lcom/jogamp/opengl/GLContext;", null, null).visitEnd();
            ctx.visitField(ACC_PUBLIC, "created", "Z", null, null).visitEnd();
            final MethodVisitor cc = ctx.visitMethod(ACC_PUBLIC, "<init>", "(Z)V", null, null);
            cc.visitCode();
            cc.visitVarInsn(ALOAD, 0);
            cc.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            cc.visitVarInsn(ALOAD, 0);
            cc.visitVarInsn(ILOAD, 1);
            cc.visitFieldInsn(PUTFIELD, "com/jogamp/opengl/GLContext", "created", "Z");
            cc.visitInsn(RETURN);
            cc.visitMaxs(0, 0);
            cc.visitEnd();
            final MethodVisitor gc = ctx.visitMethod(ACC_PUBLIC | ACC_STATIC, "getCurrent", "()Lcom/jogamp/opengl/GLContext;", null, null);
            gc.visitCode();
            gc.visitFieldInsn(GETSTATIC, "com/jogamp/opengl/GLContext", "CURRENT", "Lcom/jogamp/opengl/GLContext;");
            gc.visitInsn(ARETURN);
            gc.visitMaxs(0, 0);
            gc.visitEnd();
            final MethodVisitor ic = ctx.visitMethod(ACC_PUBLIC, "isCreated", "()Z", null, null);
            ic.visitCode();
            ic.visitVarInsn(ALOAD, 0);
            ic.visitFieldInsn(GETFIELD, "com/jogamp/opengl/GLContext", "created", "Z");
            ic.visitInsn(IRETURN);
            ic.visitMaxs(0, 0);
            ic.visitEnd();
            ctx.visitEnd();
            rawClasses.put("com/jogamp/opengl/GLContext", ctx.toByteArray());

            // GL / GL2ES2 / GL3 / GLAutoDrawable interfaces
            final ClassWriter glIf = new ClassWriter(0);
            glIf.visit(V17, ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT, "com/jogamp/opengl/GL", null, "java/lang/Object", null);
            glIf.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "getContext", "()Lcom/jogamp/opengl/GLContext;", null, null).visitEnd();
            glIf.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "getRootGL", "()Lcom/jogamp/opengl/GL;", null, null).visitEnd();
            glIf.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "isGL3", "()Z", null, null).visitEnd();
            glIf.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "getGL3", "()Lcom/jogamp/opengl/GL3;", null, null).visitEnd();
            glIf.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "glDeleteTextures", "(I[II)V", null, null).visitEnd();
            glIf.visitEnd();
            rawClasses.put("com/jogamp/opengl/GL", glIf.toByteArray());

            final ClassWriter gl2 = new ClassWriter(0);
            gl2.visit(V17, ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT, MemoryLeakMitigationTarget.GL2ES2_OWNER, null, "java/lang/Object", new String[] {"com/jogamp/opengl/GL"});
            gl2.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "glDeleteBuffers", "(I[II)V", null, null).visitEnd();
            gl2.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "glGenBuffers", "(I[II)V", null, null).visitEnd();
            gl2.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "glDeleteBuffers", "(ILjava/nio/IntBuffer;)V", null, null).visitEnd();
            gl2.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "glGenBuffers", "(ILjava/nio/IntBuffer;)V", null, null).visitEnd();
            gl2.visitEnd();
            rawClasses.put(MemoryLeakMitigationTarget.GL2ES2_OWNER, gl2.toByteArray());

            final ClassWriter gl3 = new ClassWriter(0);
            gl3.visit(V17, ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT, "com/jogamp/opengl/GL3", null, "java/lang/Object", new String[] {MemoryLeakMitigationTarget.GL2ES2_OWNER});
            gl3.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "glDeleteSamplers", "(I[II)V", null, null).visitEnd();
            gl3.visitEnd();
            rawClasses.put("com/jogamp/opengl/GL3", gl3.toByteArray());

            final ClassWriter drwIf = new ClassWriter(0);
            drwIf.visit(V17, ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT, "com/jogamp/opengl/GLAutoDrawable", null, "java/lang/Object", null);
            drwIf.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "getGL", "()Lcom/jogamp/opengl/GL;", null, null).visitEnd();
            drwIf.visitEnd();
            rawClasses.put("com/jogamp/opengl/GLAutoDrawable", drwIf.toByteArray());

            // TestGL3 implementation
            final ClassWriter impl = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            impl.visit(V17, ACC_PUBLIC, "TestGL3", null, "java/lang/Object", new String[] {"com/jogamp/opengl/GL3"});
            impl.visitField(ACC_PUBLIC, "ctx", "Lcom/jogamp/opengl/GLContext;", null, null).visitEnd();
            impl.visitField(ACC_PUBLIC, "gens", "I", null, null).visitEnd();
            impl.visitField(ACC_PUBLIC, "deletes", "I", null, null).visitEnd();
            impl.visitField(ACC_PUBLIC, "nextId", "I", null, 1).visitEnd();
            final MethodVisitor ic2 = impl.visitMethod(ACC_PUBLIC, "<init>", "(Lcom/jogamp/opengl/GLContext;)V", null, null);
            ic2.visitCode();
            ic2.visitVarInsn(ALOAD, 0);
            ic2.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            ic2.visitVarInsn(ALOAD, 0); ic2.visitVarInsn(ALOAD, 1); ic2.visitFieldInsn(PUTFIELD, "TestGL3", "ctx", "Lcom/jogamp/opengl/GLContext;");
            ic2.visitVarInsn(ALOAD, 0); ic2.visitInsn(ICONST_1); ic2.visitFieldInsn(PUTFIELD, "TestGL3", "nextId", "I");
            ic2.visitInsn(RETURN);
            ic2.visitMaxs(0, 0);
            ic2.visitEnd();
            emitFieldGetter(impl, "TestGL3", "getContext", "()Lcom/jogamp/opengl/GLContext;", "ctx");
            final MethodVisitor gr = impl.visitMethod(ACC_PUBLIC, "getRootGL", "()Lcom/jogamp/opengl/GL;", null, null);
            gr.visitCode(); gr.visitVarInsn(ALOAD, 0); gr.visitInsn(ARETURN); gr.visitMaxs(0, 0); gr.visitEnd();
            emitBooleanGetter(impl, "isGL3", true);
            final MethodVisitor g3 = impl.visitMethod(ACC_PUBLIC, "getGL3", "()Lcom/jogamp/opengl/GL3;", null, null);
            g3.visitCode(); g3.visitVarInsn(ALOAD, 0); g3.visitInsn(ARETURN); g3.visitMaxs(0, 0); g3.visitEnd();
            emitVoidMethod(impl, "glDeleteTextures", "(I[II)V");
            emitVoidMethod(impl, "glDeleteSamplers", "(I[II)V");
            // glDeleteBuffers(I[II)V -> deletes++
            final MethodVisitor dbArr = impl.visitMethod(ACC_PUBLIC, "glDeleteBuffers", "(I[II)V", null, null);
            dbArr.visitCode();
            dbArr.visitVarInsn(ALOAD, 0); dbArr.visitInsn(DUP); dbArr.visitFieldInsn(GETFIELD, "TestGL3", "deletes", "I");
            dbArr.visitInsn(ICONST_1); dbArr.visitInsn(IADD); dbArr.visitFieldInsn(PUTFIELD, "TestGL3", "deletes", "I");
            dbArr.visitInsn(RETURN); dbArr.visitMaxs(0, 0); dbArr.visitEnd();
            final MethodVisitor dbBuf = impl.visitMethod(ACC_PUBLIC, "glDeleteBuffers", "(ILjava/nio/IntBuffer;)V", null, null);
            dbBuf.visitCode();
            dbBuf.visitVarInsn(ALOAD, 0); dbBuf.visitInsn(DUP); dbBuf.visitFieldInsn(GETFIELD, "TestGL3", "deletes", "I");
            dbBuf.visitInsn(ICONST_1); dbBuf.visitInsn(IADD); dbBuf.visitFieldInsn(PUTFIELD, "TestGL3", "deletes", "I");
            dbBuf.visitInsn(RETURN); dbBuf.visitMaxs(0, 0); dbBuf.visitEnd();
            // glGenBuffers(I[II)V -> arr[off] = nextId++; gens++
            final MethodVisitor gbArr = impl.visitMethod(ACC_PUBLIC, "glGenBuffers", "(I[II)V", null, null);
            gbArr.visitCode();
            gbArr.visitVarInsn(ALOAD, 2); gbArr.visitVarInsn(ILOAD, 3);
            gbArr.visitVarInsn(ALOAD, 0); gbArr.visitFieldInsn(GETFIELD, "TestGL3", "nextId", "I");
            gbArr.visitInsn(DUP); gbArr.visitInsn(ICONST_1); gbArr.visitInsn(IADD);
            gbArr.visitVarInsn(ALOAD, 0); gbArr.visitInsn(SWAP); gbArr.visitFieldInsn(PUTFIELD, "TestGL3", "nextId", "I");
            gbArr.visitInsn(IASTORE);
            gbArr.visitVarInsn(ALOAD, 0); gbArr.visitInsn(DUP); gbArr.visitFieldInsn(GETFIELD, "TestGL3", "gens", "I");
            gbArr.visitInsn(ICONST_1); gbArr.visitInsn(IADD); gbArr.visitFieldInsn(PUTFIELD, "TestGL3", "gens", "I");
            gbArr.visitInsn(RETURN); gbArr.visitMaxs(0, 0); gbArr.visitEnd();
            // glGenBuffers(I, IntBuffer)
            final MethodVisitor gbBuf = impl.visitMethod(ACC_PUBLIC, "glGenBuffers", "(ILjava/nio/IntBuffer;)V", null, null);
            gbBuf.visitCode();
            gbBuf.visitVarInsn(ALOAD, 2); gbBuf.visitInsn(ICONST_0);
            gbBuf.visitVarInsn(ALOAD, 0); gbBuf.visitFieldInsn(GETFIELD, "TestGL3", "nextId", "I");
            gbBuf.visitInsn(DUP); gbBuf.visitInsn(ICONST_1); gbBuf.visitInsn(IADD);
            gbBuf.visitVarInsn(ALOAD, 0); gbBuf.visitInsn(SWAP); gbBuf.visitFieldInsn(PUTFIELD, "TestGL3", "nextId", "I");
            gbBuf.visitMethodInsn(INVOKEVIRTUAL, "java/nio/IntBuffer", "put", "(II)Ljava/nio/IntBuffer;", false);
            gbBuf.visitInsn(POP);
            gbBuf.visitVarInsn(ALOAD, 0); gbBuf.visitInsn(DUP); gbBuf.visitFieldInsn(GETFIELD, "TestGL3", "gens", "I");
            gbBuf.visitInsn(ICONST_1); gbBuf.visitInsn(IADD); gbBuf.visitFieldInsn(PUTFIELD, "TestGL3", "gens", "I");
            gbBuf.visitInsn(RETURN); gbBuf.visitMaxs(0, 0); gbBuf.visitEnd();
            impl.visitEnd();
            rawClasses.put("TestGL3", impl.toByteArray());

            // TestDrawable
            final ClassWriter td = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            td.visit(V17, ACC_PUBLIC, "TestDrawable", null, "java/lang/Object", new String[] {"com/jogamp/opengl/GLAutoDrawable"});
            td.visitField(ACC_PUBLIC, "gl", "Lcom/jogamp/opengl/GL;", null, null).visitEnd();
            final MethodVisitor tdc = td.visitMethod(ACC_PUBLIC, "<init>", "(Lcom/jogamp/opengl/GL;)V", null, null);
            tdc.visitCode(); tdc.visitVarInsn(ALOAD, 0); tdc.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            tdc.visitVarInsn(ALOAD, 0); tdc.visitVarInsn(ALOAD, 1); tdc.visitFieldInsn(PUTFIELD, "TestDrawable", "gl", "Lcom/jogamp/opengl/GL;");
            tdc.visitInsn(RETURN); tdc.visitMaxs(0, 0); tdc.visitEnd();
            emitFieldGetter(td, "TestDrawable", "getGL", "()Lcom/jogamp/opengl/GL;", "gl");
            td.visitEnd();
            rawClasses.put("TestDrawable", td.toByteArray());

            // graphics3d/a
            final ClassWriter ga = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            ga.visit(V17, ACC_PUBLIC, "com/live2d/graphics3d/a", null, "java/lang/Object", null);
            ga.visitField(ACC_PUBLIC, "gl3", "Lcom/jogamp/opengl/GL3;", null, null).visitEnd();
            final MethodVisitor gac = ga.visitMethod(ACC_PUBLIC, "<init>", "(Lcom/jogamp/opengl/GL3;)V", null, null);
            gac.visitCode(); gac.visitVarInsn(ALOAD, 0); gac.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            gac.visitVarInsn(ALOAD, 0); gac.visitVarInsn(ALOAD, 1); gac.visitFieldInsn(PUTFIELD, "com/live2d/graphics3d/a", "gl3", "Lcom/jogamp/opengl/GL3;");
            gac.visitInsn(RETURN); gac.visitMaxs(0, 0); gac.visitEnd();
            emitFieldGetter(ga, "com/live2d/graphics3d/a", "a", "()Lcom/jogamp/opengl/GL3;", "gl3");
            ga.visitEnd();
            rawClasses.put("com/live2d/graphics3d/a", ga.toByteArray());
        }

        private void addEntityAndMeshTypes() {
            // mesh/a/a base
            final ClassWriter mba = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            mba.visit(V17, ACC_PUBLIC | ACC_ABSTRACT, MemoryLeakMitigationTarget.GL_BUFFER_BASE_OWNER, null, "java/lang/Object", null);
            mba.visitField(ACC_PUBLIC, "gl", "Lcom/jogamp/opengl/GL2ES2;", null, null).visitEnd();
            mba.visitField(ACC_PUBLIC, "buf", "Ljava/nio/IntBuffer;", null, null).visitEnd();
            mba.visitField(ACC_PUBLIC, "reinit", "Z", null, null).visitEnd();
            mba.visitField(ACC_PUBLIC, "dirty", "Z", null, null).visitEnd();
            final MethodVisitor mbc = mba.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
            mbc.visitCode();
            mbc.visitVarInsn(ALOAD, 0); mbc.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            mbc.visitVarInsn(ALOAD, 0); mbc.visitInsn(ICONST_1);
            mbc.visitMethodInsn(INVOKESTATIC, "java/nio/IntBuffer", "allocate", "(I)Ljava/nio/IntBuffer;", false);
            mbc.visitFieldInsn(PUTFIELD, MemoryLeakMitigationTarget.GL_BUFFER_BASE_OWNER, "buf", "Ljava/nio/IntBuffer;");
            mbc.visitInsn(RETURN); mbc.visitMaxs(0, 0); mbc.visitEnd();
            emitFieldGetter(mba, MemoryLeakMitigationTarget.GL_BUFFER_BASE_OWNER, "h", "()Lcom/jogamp/opengl/GL2ES2;", "gl");
            emitFieldSetter(mba, MemoryLeakMitigationTarget.GL_BUFFER_BASE_OWNER, "a", "(Lcom/jogamp/opengl/GL2ES2;)V", "gl");
            emitFieldGetter(mba, MemoryLeakMitigationTarget.GL_BUFFER_BASE_OWNER, "i", "()Ljava/nio/IntBuffer;", "buf");
            emitBooleanFieldSetter(mba, MemoryLeakMitigationTarget.GL_BUFFER_BASE_OWNER, "c", "(Z)V", "reinit");
            emitBooleanFieldSetter(mba, MemoryLeakMitigationTarget.GL_BUFFER_BASE_OWNER, "d", "(Z)V", "dirty");
            emitBooleanFieldGetter(mba, MemoryLeakMitigationTarget.GL_BUFFER_BASE_OWNER, "j", "reinit");
            mba.visitEnd();
            rawClasses.put(MemoryLeakMitigationTarget.GL_BUFFER_BASE_OWNER, mba.toByteArray());

            for (final String vboSub : List.of(MemoryLeakMitigationTarget.GL_BUFFER_FLOAT_OWNER, MemoryLeakMitigationTarget.GL_BUFFER_INT_OWNER)) {
                final ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
                w.visit(V17, ACC_PUBLIC, vboSub, null, MemoryLeakMitigationTarget.GL_BUFFER_BASE_OWNER, null);
                emitDefaultCtor(w, MemoryLeakMitigationTarget.GL_BUFFER_BASE_OWNER);
                // b(GL2ES2, int): if (h()!=gl || j()) { gl.glDeleteBuffers(1, i()); gl.glGenBuffers(1, i()); } a(gl); c(false);
                final MethodVisitor m = w.visitMethod(ACC_PRIVATE | ACC_FINAL, "b", "(Lcom/jogamp/opengl/GL2ES2;I)V", null, null);
                m.visitCode();
                final Label lSkip = new Label();
                final Label lDo = new Label();
                m.visitVarInsn(ALOAD, 0);
                m.visitMethodInsn(INVOKEVIRTUAL, vboSub, "h", "()Lcom/jogamp/opengl/GL2ES2;", false);
                m.visitVarInsn(ALOAD, 1);
                m.visitJumpInsn(IF_ACMPNE, lDo);
                m.visitVarInsn(ALOAD, 0);
                m.visitMethodInsn(INVOKEVIRTUAL, vboSub, "j", "()Z", false);
                m.visitJumpInsn(IFEQ, lSkip);
                m.visitLabel(lDo);
                m.visitVarInsn(ALOAD, 1);
                m.visitInsn(ICONST_1);
                m.visitVarInsn(ALOAD, 0);
                m.visitMethodInsn(INVOKEVIRTUAL, vboSub, "i", "()Ljava/nio/IntBuffer;", false);
                m.visitMethodInsn(INVOKEINTERFACE, MemoryLeakMitigationTarget.GL2ES2_OWNER, "glDeleteBuffers", "(ILjava/nio/IntBuffer;)V", true);
                m.visitVarInsn(ALOAD, 1);
                m.visitInsn(ICONST_1);
                m.visitVarInsn(ALOAD, 0);
                m.visitMethodInsn(INVOKEVIRTUAL, vboSub, "i", "()Ljava/nio/IntBuffer;", false);
                m.visitMethodInsn(INVOKEINTERFACE, MemoryLeakMitigationTarget.GL2ES2_OWNER, "glGenBuffers", "(ILjava/nio/IntBuffer;)V", true);
                m.visitLabel(lSkip);
                m.visitVarInsn(ALOAD, 0); m.visitVarInsn(ALOAD, 1);
                m.visitMethodInsn(INVOKEVIRTUAL, vboSub, "a", "(Lcom/jogamp/opengl/GL2ES2;)V", false);
                m.visitVarInsn(ALOAD, 0); m.visitInsn(ICONST_0);
                m.visitMethodInsn(INVOKEVIRTUAL, vboSub, "c", "(Z)V", false);
                m.visitInsn(RETURN);
                m.visitMaxs(0, 0);
                m.visitEnd();
                w.visitEnd();
                rawClasses.put(vboSub, w.toByteArray());
            }

            addSimpleArrayList("com/live2d/graphics3d/entity/Components");

            // Entities (extends ArrayList, has removeAll()V)
            final ClassWriter ents = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            ents.visit(V17, ACC_PUBLIC, MemoryLeakMitigationTarget.ENTITIES_OWNER, null, "java/util/ArrayList", null);
            emitDefaultCtor(ents, "java/util/ArrayList");
            final MethodVisitor ra = ents.visitMethod(ACC_PUBLIC, "removeAll", "()V", null, null);
            ra.visitCode();
            ra.visitVarInsn(ALOAD, 0);
            ra.visitMethodInsn(INVOKESPECIAL, "java/util/ArrayList", "clear", "()V", false);
            ra.visitInsn(RETURN);
            ra.visitMaxs(0, 0);
            ra.visitEnd();
            ents.visitEnd();
            rawClasses.put(MemoryLeakMitigationTarget.ENTITIES_OWNER, ents.toByteArray());

            // GEntity
            final ClassWriter ge = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            ge.visit(V17, ACC_PUBLIC, MemoryLeakMitigationTarget.G_ENTITY_OWNER, null, "java/lang/Object", null);
            ge.visitField(ACC_PUBLIC, "children", "L" + MemoryLeakMitigationTarget.ENTITIES_OWNER + ";", null, null).visitEnd();
            ge.visitField(ACC_PUBLIC, "components", "Lcom/live2d/graphics3d/entity/Components;", null, null).visitEnd();
            final MethodVisitor gec = ge.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
            gec.visitCode();
            gec.visitVarInsn(ALOAD, 0); gec.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            gec.visitVarInsn(ALOAD, 0); gec.visitTypeInsn(NEW, MemoryLeakMitigationTarget.ENTITIES_OWNER); gec.visitInsn(DUP);
            gec.visitMethodInsn(INVOKESPECIAL, MemoryLeakMitigationTarget.ENTITIES_OWNER, "<init>", "()V", false);
            gec.visitFieldInsn(PUTFIELD, MemoryLeakMitigationTarget.G_ENTITY_OWNER, "children", "L" + MemoryLeakMitigationTarget.ENTITIES_OWNER + ";");
            gec.visitVarInsn(ALOAD, 0); gec.visitTypeInsn(NEW, "com/live2d/graphics3d/entity/Components"); gec.visitInsn(DUP);
            gec.visitMethodInsn(INVOKESPECIAL, "com/live2d/graphics3d/entity/Components", "<init>", "()V", false);
            gec.visitFieldInsn(PUTFIELD, MemoryLeakMitigationTarget.G_ENTITY_OWNER, "components", "Lcom/live2d/graphics3d/entity/Components;");
            gec.visitInsn(RETURN); gecMax(gec);
            emitFieldGetter(ge, MemoryLeakMitigationTarget.G_ENTITY_OWNER, "getChildren", "()L" + MemoryLeakMitigationTarget.ENTITIES_OWNER + ";", "children");
            emitFieldGetter(ge, MemoryLeakMitigationTarget.G_ENTITY_OWNER, "getComponents", "()Lcom/live2d/graphics3d/entity/Components;", "components");
            emitVoidMethod(ge, "dispose", "()V");
            ge.visitEnd();
            rawClasses.put(MemoryLeakMitigationTarget.G_ENTITY_OWNER, ge.toByteArray());

            // mesh/a, GMesh2, GMeshContainer, GMeshRenderer, EditableMeshSelectionEntity
            final ClassWriter gma = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            gma.visit(V17, ACC_PUBLIC | ACC_ABSTRACT, "com/live2d/graphics3d/mesh/a", null, "java/lang/Object", null);
            emitDefaultCtor(gma, "java/lang/Object");
            gma.visitEnd();
            rawClasses.put("com/live2d/graphics3d/mesh/a", gma.toByteArray());

            final ClassWriter gm2 = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            gm2.visit(V17, ACC_PUBLIC, "com/live2d/graphics3d/mesh/GMesh2", null, "com/live2d/graphics3d/mesh/a", null);
            gm2.visitField(ACC_PUBLIC, "posBuffer", "L" + MemoryLeakMitigationTarget.GL_BUFFER_FLOAT_OWNER + ";", null, null).visitEnd();
            emitDefaultCtor(gm2, "com/live2d/graphics3d/mesh/a");
            gm2.visitEnd();
            rawClasses.put("com/live2d/graphics3d/mesh/GMesh2", gm2.toByteArray());

            final ClassWriter gmc = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            gmc.visit(V17, ACC_PUBLIC, "com/live2d/graphics3d/component/GMeshContainer", null, "java/lang/Object", null);
            gmc.visitField(ACC_PUBLIC, "mesh", "Lcom/live2d/graphics3d/mesh/a;", null, null).visitEnd();
            final MethodVisitor gmcc = gmc.visitMethod(ACC_PUBLIC, "<init>", "(Lcom/live2d/graphics3d/mesh/a;)V", null, null);
            gmcc.visitCode(); gmcc.visitVarInsn(ALOAD, 0); gmcc.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            gmcc.visitVarInsn(ALOAD, 0); gmcc.visitVarInsn(ALOAD, 1); gmcc.visitFieldInsn(PUTFIELD, "com/live2d/graphics3d/component/GMeshContainer", "mesh", "Lcom/live2d/graphics3d/mesh/a;");
            gmcc.visitInsn(RETURN); gmcc.visitMaxs(0, 0); gmcc.visitEnd();
            emitFieldGetter(gmc, "com/live2d/graphics3d/component/GMeshContainer", "getMesh", "()Lcom/live2d/graphics3d/mesh/a;", "mesh");
            gmc.visitEnd();
            rawClasses.put("com/live2d/graphics3d/component/GMeshContainer", gmc.toByteArray());

            final ClassWriter gmr = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            gmr.visit(V17, ACC_PUBLIC, MemoryLeakMitigationTarget.G_MESH_RENDERER_OWNER, null, "java/lang/Object", null);
            gmr.visitField(ACC_PUBLIC, "container", "Lcom/live2d/graphics3d/component/GMeshContainer;", null, null).visitEnd();
            final MethodVisitor gmrc = gmr.visitMethod(ACC_PUBLIC, "<init>", "(Lcom/live2d/graphics3d/component/GMeshContainer;)V", null, null);
            gmrc.visitCode(); gmrc.visitVarInsn(ALOAD, 0); gmrc.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            gmrc.visitVarInsn(ALOAD, 0); gmrc.visitVarInsn(ALOAD, 1); gmrc.visitFieldInsn(PUTFIELD, MemoryLeakMitigationTarget.G_MESH_RENDERER_OWNER, "container", "Lcom/live2d/graphics3d/component/GMeshContainer;");
            gmrc.visitInsn(RETURN); gmrc.visitMaxs(0, 0); gmrc.visitEnd();
            emitFieldGetter(gmr, MemoryLeakMitigationTarget.G_MESH_RENDERER_OWNER, "getMeshContainer", "()Lcom/live2d/graphics3d/component/GMeshContainer;", "container");
            gmr.visitEnd();
            rawClasses.put(MemoryLeakMitigationTarget.G_MESH_RENDERER_OWNER, gmr.toByteArray());

            final ClassWriter ems = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            ems.visit(V17, ACC_PUBLIC, MemoryLeakMitigationTarget.EDITABLE_MESH_SEL_OWNER, null, MemoryLeakMitigationTarget.G_ENTITY_OWNER, null);
            emitDefaultCtor(ems, MemoryLeakMitigationTarget.G_ENTITY_OWNER);
            emitVoidMethod(ems, "reset", "()V");
            ems.visitEnd();
            rawClasses.put(MemoryLeakMitigationTarget.EDITABLE_MESH_SEL_OWNER, ems.toByteArray());
        }

        private void addTextureTypes() {
            final ClassWriter gt = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            gt.visit(V17, ACC_PUBLIC, MemoryLeakMitigationTarget.G_TEXTURE_OWNER, null, "java/lang/Object", null);
            gt.visitField(ACC_PUBLIC, "glTextureName", "I", null, null).visitEnd();
            gt.visitField(ACC_PUBLIC, "glSamplerName", "I", null, null).visitEnd();
            gt.visitField(ACC_PUBLIC, "reinitTextureLater", "Z", null, null).visitEnd();
            gt.visitField(ACC_PUBLIC, "reinitSamplerLater", "Z", null, null).visitEnd();
            emitDefaultCtor(gt, "java/lang/Object");
            emitVoidMethod(gt, MemoryLeakMitigationTarget.TEXTURE_RELEASE_GL_METHOD, MemoryLeakMitigationTarget.TEXTURE_RELEASE_GL_DESCRIPTOR);
            emitVoidMethod(gt, "dispose", "()V");
            gt.visitEnd();
            rawClasses.put(MemoryLeakMitigationTarget.G_TEXTURE_OWNER, gt.toByteArray());

            final ClassWriter g2 = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            g2.visit(V17, ACC_PUBLIC, MemoryLeakMitigationTarget.G_TEXTURE_2D_OWNER, null, MemoryLeakMitigationTarget.G_TEXTURE_OWNER, new String[] {"com/live2d/graphics/ICImageResourceUser"});
            g2.visitField(ACC_PUBLIC, "initializedGl", "Lcom/jogamp/opengl/GL;", null, null).visitEnd();
            g2.visitField(ACC_PUBLIC, "_preBuffer", "Ljava/lang/Object;", null, null).visitEnd();
            g2.visitField(ACC_PUBLIC, "srcImageResource", "L" + MemoryLeakMitigationTarget.IMAGE_RESOURCE_OWNER + ";", null, null).visitEnd();
            final MethodVisitor g2c = g2.visitMethod(ACC_PUBLIC, "<init>", "(L" + MemoryLeakMitigationTarget.IMAGE_RESOURCE_OWNER + ";)V", null, null);
            g2c.visitCode(); g2c.visitVarInsn(ALOAD, 0); g2c.visitMethodInsn(INVOKESPECIAL, MemoryLeakMitigationTarget.G_TEXTURE_OWNER, "<init>", "()V", false);
            g2c.visitVarInsn(ALOAD, 0); g2c.visitVarInsn(ALOAD, 1); g2c.visitFieldInsn(PUTFIELD, MemoryLeakMitigationTarget.G_TEXTURE_2D_OWNER, "srcImageResource", "L" + MemoryLeakMitigationTarget.IMAGE_RESOURCE_OWNER + ";");
            g2c.visitInsn(RETURN); g2c.visitMaxs(0, 0); g2c.visitEnd();
            emitVoidMethod(g2, MemoryLeakMitigationTarget.TEXTURE_SETUP_EXE_METHOD, MemoryLeakMitigationTarget.TEXTURE_SETUP_EXE_DESCRIPTOR);
            final MethodVisitor disp = g2.visitMethod(ACC_PUBLIC, "dispose", "()V", null, null);
            disp.visitCode();
            final Label lNull = new Label();
            disp.visitVarInsn(ALOAD, 0);
            disp.visitFieldInsn(GETFIELD, MemoryLeakMitigationTarget.G_TEXTURE_2D_OWNER, "srcImageResource", "L" + MemoryLeakMitigationTarget.IMAGE_RESOURCE_OWNER + ";");
            disp.visitInsn(DUP);
            disp.visitJumpInsn(IFNULL, lNull);
            disp.visitVarInsn(ALOAD, 0);
            disp.visitMethodInsn(INVOKEVIRTUAL, MemoryLeakMitigationTarget.IMAGE_RESOURCE_OWNER, "releaseResource", MemoryLeakMitigationTarget.RELEASE_RESOURCE_DESCRIPTOR, false);
            final Label lEnd = new Label();
            disp.visitJumpInsn(GOTO, lEnd);
            disp.visitLabel(lNull);
            disp.visitInsn(POP);
            disp.visitLabel(lEnd);
            disp.visitInsn(RETURN);
            disp.visitMaxs(0, 0);
            disp.visitEnd();
            g2.visitEnd();
            rawClasses.put(MemoryLeakMitigationTarget.G_TEXTURE_2D_OWNER, g2.toByteArray());
        }

        private void addWrapperAndContextTypes() {
            final ClassWriter gw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            gw.visit(V17, ACC_PUBLIC, MemoryLeakMitigationTarget.GL_WRAPPER_OWNER, null, "java/lang/Object", null);
            gw.visitField(ACC_PUBLIC, "k", "Ljava/util/ArrayList;", null, null).visitEnd();
            gw.visitField(ACC_PUBLIC, "o", "Ljava/lang/Object;", null, null).visitEnd();
            final MethodVisitor gwc = gw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
            gwc.visitCode(); gwc.visitVarInsn(ALOAD, 0); gwc.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            gwc.visitVarInsn(ALOAD, 0); gwc.visitTypeInsn(NEW, "java/util/ArrayList"); gwc.visitInsn(DUP);
            gwc.visitMethodInsn(INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
            gwc.visitFieldInsn(PUTFIELD, MemoryLeakMitigationTarget.GL_WRAPPER_OWNER, "k", "Ljava/util/ArrayList;");
            gwc.visitInsn(RETURN); gwc.visitMaxs(0, 0); gwc.visitEnd();
            emitVoidMethod(gw, "c", "()V");
            gw.visitEnd();
            rawClasses.put(MemoryLeakMitigationTarget.GL_WRAPPER_OWNER, gw.toByteArray());

            final ClassWriter gwl = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            gwl.visit(V17, ACC_PUBLIC, MemoryLeakMitigationTarget.GL_WRAPPER_LISTENER_OWNER, null, "java/lang/Object", null);
            gwl.visitField(ACC_PUBLIC, "a", "L" + MemoryLeakMitigationTarget.GL_WRAPPER_OWNER + ";", null, null).visitEnd();
            final MethodVisitor gwlc = gwl.visitMethod(ACC_PUBLIC, "<init>", "(L" + MemoryLeakMitigationTarget.GL_WRAPPER_OWNER + ";)V", null, null);
            gwlc.visitCode(); gwlc.visitVarInsn(ALOAD, 0); gwlc.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            gwlc.visitVarInsn(ALOAD, 0); gwlc.visitVarInsn(ALOAD, 1); gwlc.visitFieldInsn(PUTFIELD, MemoryLeakMitigationTarget.GL_WRAPPER_LISTENER_OWNER, "a", "L" + MemoryLeakMitigationTarget.GL_WRAPPER_OWNER + ";");
            gwlc.visitInsn(RETURN); gwlc.visitMaxs(0, 0); gwlc.visitEnd();
            emitVoidMethod(gwl, "dispose", MemoryLeakMitigationTarget.GL_EVENT_DISPOSE_DESCRIPTOR);
            gwl.visitEnd();
            rawClasses.put(MemoryLeakMitigationTarget.GL_WRAPPER_LISTENER_OWNER, gwl.toByteArray());

            final ClassWriter vc = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            vc.visit(V17, ACC_PUBLIC, MemoryLeakMitigationTarget.VIEW_CONTEXT_OWNER, null, "java/lang/Object", null);
            emitDefaultCtor(vc, "java/lang/Object");
            emitVoidMethod(vc, "closeView", "()V");
            vc.visitEnd();
            rawClasses.put(MemoryLeakMitigationTarget.VIEW_CONTEXT_OWNER, vc.toByteArray());

            final ClassWriter mvc = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            mvc.visit(V17, ACC_PUBLIC, MemoryLeakMitigationTarget.MESH_EDITOR_VC_OWNER, null, MemoryLeakMitigationTarget.VIEW_CONTEXT_OWNER, null);
            mvc.visitField(ACC_PUBLIC, "parentOfMeshes_entity", "L" + MemoryLeakMitigationTarget.G_ENTITY_OWNER + ";", null, null).visitEnd();
            emitDefaultCtor(mvc, MemoryLeakMitigationTarget.VIEW_CONTEXT_OWNER);
            emitVoidMethod(mvc, "deactivateView", "()V");
            mvc.visitEnd();
            rawClasses.put(MemoryLeakMitigationTarget.MESH_EDITOR_VC_OWNER, mvc.toByteArray());

            final ClassWriter mem = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            mem.visit(V17, ACC_PUBLIC, MemoryLeakMitigationTarget.MESH_EDITOR_EM_OWNER, null, "com/live2d/cubism/doc/ACEditMode", null);
            mem.visitField(ACC_PUBLIC, "doc", "L" + MemoryLeakMitigationTarget.MODELING_DOC_OWNER + ";", null, null).visitEnd();
            final MethodVisitor memc = mem.visitMethod(ACC_PUBLIC, "<init>", "(L" + MemoryLeakMitigationTarget.UNDO_MANAGER_OWNER + ";)V", null, null);
            memc.visitCode(); memc.visitVarInsn(ALOAD, 0); memc.visitVarInsn(ALOAD, 1);
            memc.visitMethodInsn(INVOKESPECIAL, "com/live2d/cubism/doc/ACEditMode", "<init>", "(L" + MemoryLeakMitigationTarget.UNDO_MANAGER_OWNER + ";)V", false);
            memc.visitInsn(RETURN); memc.visitMaxs(0, 0); memc.visitEnd();
            emitFieldGetter(mem, MemoryLeakMitigationTarget.MESH_EDITOR_EM_OWNER, "getDoc", "()L" + MemoryLeakMitigationTarget.MODELING_DOC_OWNER + ";", "doc");
            emitVoidMethod(mem, "endMode", "()V");
            mem.visitEnd();
            rawClasses.put(MemoryLeakMitigationTarget.MESH_EDITOR_EM_OWNER, mem.toByteArray());
        }

        private void addUndoAndDocumentTypes() {
            // ACUndoable
            final ClassWriter ua = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            ua.visit(V17, ACC_PUBLIC, "com/live2d/undo/ACUndoable", null, "java/lang/Object", null);
            ua.visitField(ACC_PUBLIC, "died", "Z", null, null).visitEnd();
            emitDefaultCtor(ua, "java/lang/Object");
            final MethodVisitor dieM = ua.visitMethod(ACC_PUBLIC, "die", "()V", null, null);
            dieM.visitCode();
            dieM.visitVarInsn(ALOAD, 0); dieM.visitInsn(ICONST_1); dieM.visitFieldInsn(PUTFIELD, "com/live2d/undo/ACUndoable", "died", "Z");
            dieM.visitInsn(RETURN); dieM.visitMaxs(0, 0); dieM.visitEnd();
            ua.visitEnd();
            rawClasses.put("com/live2d/undo/ACUndoable", ua.toByteArray());

            // CUndoManager
            final ClassWriter um = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            um.visit(V17, ACC_PUBLIC, MemoryLeakMitigationTarget.UNDO_MANAGER_OWNER, null, "java/lang/Object", null);
            um.visitField(ACC_PUBLIC, "_undoList", "Lcom/live2d/type/CArrayList;", null, null).visitEnd();
            um.visitField(ACC_PUBLIC, "currentPos", "I", null, null).visitEnd();
            um.visitField(ACC_PUBLIC, "limit", "I", null, 100).visitEnd();
            final MethodVisitor umc = um.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
            umc.visitCode(); umc.visitVarInsn(ALOAD, 0); umc.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            umc.visitVarInsn(ALOAD, 0); umc.visitTypeInsn(NEW, "com/live2d/type/CArrayList"); umc.visitInsn(DUP);
            umc.visitMethodInsn(INVOKESPECIAL, "com/live2d/type/CArrayList", "<init>", "()V", false);
            umc.visitFieldInsn(PUTFIELD, MemoryLeakMitigationTarget.UNDO_MANAGER_OWNER, "_undoList", "Lcom/live2d/type/CArrayList;");
            umc.visitVarInsn(ALOAD, 0); umc.visitIntInsn(BIPUSH, 100);
            umc.visitFieldInsn(PUTFIELD, MemoryLeakMitigationTarget.UNDO_MANAGER_OWNER, "limit", "I");
            umc.visitInsn(RETURN); umc.visitMaxs(0, 0); umc.visitEnd();
            final MethodVisitor cl = um.visitMethod(ACC_PUBLIC, "clear", "()V", null, null);
            cl.visitCode();
            cl.visitVarInsn(ALOAD, 0); cl.visitFieldInsn(GETFIELD, MemoryLeakMitigationTarget.UNDO_MANAGER_OWNER, "_undoList", "Lcom/live2d/type/CArrayList;");
            cl.visitMethodInsn(INVOKEVIRTUAL, "com/live2d/type/CArrayList", "clear", "()V", false);
            cl.visitVarInsn(ALOAD, 0); cl.visitInsn(ICONST_0); cl.visitFieldInsn(PUTFIELD, MemoryLeakMitigationTarget.UNDO_MANAGER_OWNER, "currentPos", "I");
            cl.visitInsn(RETURN); cl.visitMaxs(0, 0); cl.visitEnd();
            emitVoidMethod(um, "cleanup", "()V");
            final MethodVisitor ae = um.visitMethod(ACC_PUBLIC, "addEdit", "(Lcom/live2d/undo/ACUndoable;)Z", null, null);
            ae.visitCode();
            ae.visitVarInsn(ALOAD, 0); ae.visitFieldInsn(GETFIELD, MemoryLeakMitigationTarget.UNDO_MANAGER_OWNER, "_undoList", "Lcom/live2d/type/CArrayList;");
            ae.visitVarInsn(ALOAD, 1); ae.visitMethodInsn(INVOKEVIRTUAL, "com/live2d/type/CArrayList", "add", "(Ljava/lang/Object;)Z", false); ae.visitInsn(POP);
            ae.visitVarInsn(ALOAD, 0); ae.visitInsn(DUP); ae.visitFieldInsn(GETFIELD, MemoryLeakMitigationTarget.UNDO_MANAGER_OWNER, "currentPos", "I");
            ae.visitInsn(ICONST_1); ae.visitInsn(IADD); ae.visitFieldInsn(PUTFIELD, MemoryLeakMitigationTarget.UNDO_MANAGER_OWNER, "currentPos", "I");
            ae.visitVarInsn(ALOAD, 0); ae.visitMethodInsn(INVOKEVIRTUAL, MemoryLeakMitigationTarget.UNDO_MANAGER_OWNER, "cleanup", "()V", false);
            ae.visitInsn(ICONST_1); ae.visitInsn(IRETURN); ae.visitMaxs(0, 0); ae.visitEnd();
            um.visitEnd();
            rawClasses.put(MemoryLeakMitigationTarget.UNDO_MANAGER_OWNER, um.toByteArray());

            // ACEditMode
            final ClassWriter em = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            em.visit(V17, ACC_PUBLIC, "com/live2d/cubism/doc/ACEditMode", null, "java/lang/Object", new String[] {"com/live2d/doc/IEditMode"});
            em.visitField(ACC_PUBLIC, "um", "L" + MemoryLeakMitigationTarget.UNDO_MANAGER_OWNER + ";", null, null).visitEnd();
            final MethodVisitor emc = em.visitMethod(ACC_PUBLIC, "<init>", "(L" + MemoryLeakMitigationTarget.UNDO_MANAGER_OWNER + ";)V", null, null);
            emc.visitCode(); emc.visitVarInsn(ALOAD, 0); emc.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            emc.visitVarInsn(ALOAD, 0); emc.visitVarInsn(ALOAD, 1); emc.visitFieldInsn(PUTFIELD, "com/live2d/cubism/doc/ACEditMode", "um", "L" + MemoryLeakMitigationTarget.UNDO_MANAGER_OWNER + ";");
            emc.visitInsn(RETURN); emc.visitMaxs(0, 0); emc.visitEnd();
            emitFieldGetter(em, "com/live2d/cubism/doc/ACEditMode", "getUndoManager", "()L" + MemoryLeakMitigationTarget.UNDO_MANAGER_OWNER + ";", "um");
            em.visitEnd();
            rawClasses.put("com/live2d/cubism/doc/ACEditMode", em.toByteArray());

            // CModelingDocument
            final ClassWriter md = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            md.visit(V17, ACC_PUBLIC, MemoryLeakMitigationTarget.MODELING_DOC_OWNER, null, "java/lang/Object", null);
            md.visitField(ACC_PUBLIC, "src", "L" + MemoryLeakMitigationTarget.MODEL_SOURCE_OWNER + ";", null, null).visitEnd();
            md.visitField(ACC_PUBLIC, "modes", "Ljava/util/List;", null, null).visitEnd();
            md.visitField(ACC_PUBLIC, "vcs", "Lcom/live2d/type/CArrayList;", null, null).visitEnd();
            final MethodVisitor mdc = md.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
            mdc.visitCode(); mdc.visitVarInsn(ALOAD, 0); mdc.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            mdc.visitVarInsn(ALOAD, 0); mdc.visitTypeInsn(NEW, MemoryLeakMitigationTarget.MODEL_SOURCE_OWNER); mdc.visitInsn(DUP);
            mdc.visitMethodInsn(INVOKESPECIAL, MemoryLeakMitigationTarget.MODEL_SOURCE_OWNER, "<init>", "()V", false);
            mdc.visitFieldInsn(PUTFIELD, MemoryLeakMitigationTarget.MODELING_DOC_OWNER, "src", "L" + MemoryLeakMitigationTarget.MODEL_SOURCE_OWNER + ";");
            mdc.visitVarInsn(ALOAD, 0); mdc.visitTypeInsn(NEW, "java/util/ArrayList"); mdc.visitInsn(DUP);
            mdc.visitMethodInsn(INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
            mdc.visitFieldInsn(PUTFIELD, MemoryLeakMitigationTarget.MODELING_DOC_OWNER, "modes", "Ljava/util/List;");
            mdc.visitVarInsn(ALOAD, 0); mdc.visitTypeInsn(NEW, "com/live2d/type/CArrayList"); mdc.visitInsn(DUP);
            mdc.visitMethodInsn(INVOKESPECIAL, "com/live2d/type/CArrayList", "<init>", "()V", false);
            mdc.visitFieldInsn(PUTFIELD, MemoryLeakMitigationTarget.MODELING_DOC_OWNER, "vcs", "Lcom/live2d/type/CArrayList;");
            mdc.visitInsn(RETURN); mdc.visitMaxs(0, 0); mdc.visitEnd();
            emitFieldGetter(md, MemoryLeakMitigationTarget.MODELING_DOC_OWNER, "getModelSource", "()L" + MemoryLeakMitigationTarget.MODEL_SOURCE_OWNER + ";", "src");
            emitFieldGetter(md, MemoryLeakMitigationTarget.MODELING_DOC_OWNER, "getAllEditModes", "()Ljava/util/List;", "modes");
            emitFieldGetter(md, MemoryLeakMitigationTarget.MODELING_DOC_OWNER, "getViewContexts", "()Lcom/live2d/type/CArrayList;", "vcs");
            final MethodVisitor cf = md.visitMethod(ACC_PUBLIC, "closeFile", "(ZZ)Z", null, null);
            cf.visitCode();
            final Label ok = new Label();
            cf.visitVarInsn(ILOAD, 1);
            cf.visitJumpInsn(IFNE, ok);
            cf.visitInsn(ICONST_0);
            cf.visitInsn(IRETURN);
            cf.visitLabel(ok);
            cf.visitInsn(ICONST_1);
            cf.visitInsn(IRETURN);
            cf.visitMaxs(0, 0);
            cf.visitEnd();
            md.visitEnd();
            rawClasses.put(MemoryLeakMitigationTarget.MODELING_DOC_OWNER, md.toByteArray());

            // CSceneSource & CSceneDocument & static holders
            final ClassWriter ss = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            ss.visit(V17, ACC_PUBLIC, MemoryLeakMitigationTarget.SCENE_SOURCE_OWNER, null, "java/lang/Object", null);
            emitDefaultCtor(ss, "java/lang/Object");
            ss.visitEnd();
            rawClasses.put(MemoryLeakMitigationTarget.SCENE_SOURCE_OWNER, ss.toByteArray());

            final ClassWriter sd = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            sd.visit(V17, ACC_PUBLIC, MemoryLeakMitigationTarget.SCENE_DOC_OWNER, null, "java/lang/Object", null);
            sd.visitField(ACC_PUBLIC, "um", "L" + MemoryLeakMitigationTarget.UNDO_MANAGER_OWNER + ";", null, null).visitEnd();
            sd.visitField(ACC_PUBLIC, "src", "L" + MemoryLeakMitigationTarget.SCENE_SOURCE_OWNER + ";", null, null).visitEnd();
            emitDefaultCtor(sd, "java/lang/Object");
            emitFieldGetter(sd, MemoryLeakMitigationTarget.SCENE_DOC_OWNER, "getUndoManager", "()L" + MemoryLeakMitigationTarget.UNDO_MANAGER_OWNER + ";", "um");
            emitFieldGetter(sd, MemoryLeakMitigationTarget.SCENE_DOC_OWNER, "getSceneSource", "()L" + MemoryLeakMitigationTarget.SCENE_SOURCE_OWNER + ";", "src");
            emitVoidMethod(sd, "closeDocument", "()V");
            sd.visitEnd();
            rawClasses.put(MemoryLeakMitigationTarget.SCENE_DOC_OWNER, sd.toByteArray());

            for (final String owner : List.of("com/live2d/cubism/view/palette/parts/a/a", "com/live2d/cubism/appCtrlImpl/ui/a/a", "com/live2d/cubism/view/palette/parameter/dialog/G", "com/live2d/cubism/view/palette/parameter/dialog/af")) {
                addSimpleClass(owner, null, new String[] {"d:L" + MemoryLeakMitigationTarget.MODEL_SOURCE_OWNER + ";:S", "i:L" + MemoryLeakMitigationTarget.MODEL_SOURCE_OWNER + ";:S"}, false);
            }
            addSimpleClass("com/live2d/cubism/doc/animation/ui/a", null, new String[] {"u:L" + MemoryLeakMitigationTarget.SCENE_SOURCE_OWNER + ";:S"}, false);
            addSimpleClass("com/live2d/cubism/view/palette/timeline/Utils/v", null, new String[] {"k:L" + MemoryLeakMitigationTarget.SCENE_SOURCE_OWNER + ";:S"}, false);
        }

        private void addImageTypes() {
            // CImageResource$b (holds SoftReference)
            final ClassWriter irb = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            irb.visit(V17, ACC_PUBLIC, "com/live2d/graphics/CImageResource$b", null, "java/lang/Object", null);
            irb.visitField(ACC_PUBLIC, "ref", "Ljava/lang/ref/SoftReference;", null, null).visitEnd();
            final MethodVisitor irbc = irb.visitMethod(ACC_PUBLIC, "<init>", "(L" + MemoryLeakMitigationTarget.IMAGE_RESOURCE_OWNER + ";)V", null, null);
            irbc.visitCode(); irbc.visitVarInsn(ALOAD, 0); irbc.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            irbc.visitVarInsn(ALOAD, 0); irbc.visitTypeInsn(NEW, "java/lang/ref/SoftReference"); irbc.visitInsn(DUP);
            irbc.visitVarInsn(ALOAD, 1); irbc.visitMethodInsn(INVOKESPECIAL, "java/lang/ref/SoftReference", "<init>", "(Ljava/lang/Object;)V", false);
            irbc.visitFieldInsn(PUTFIELD, "com/live2d/graphics/CImageResource$b", "ref", "Ljava/lang/ref/SoftReference;");
            irbc.visitInsn(RETURN); irbc.visitMaxs(0, 0); irbc.visitEnd();
            emitFieldGetter(irb, "com/live2d/graphics/CImageResource$b", "a", "()Ljava/lang/ref/SoftReference;", "ref");
            irb.visitEnd();
            rawClasses.put("com/live2d/graphics/CImageResource$b", irb.toByteArray());

            // CImageResource$a matching exact loop pattern (archiveIfIdle()Z -> IOR -> ISTORE 1 -> ILOAD 1 -> IFEQ loop -> GOTO after)
            final ClassWriter ira = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            ira.visit(V17, ACC_PUBLIC, MemoryLeakMitigationTarget.IMAGE_RESOURCE_COMPANION_OWNER, null, "java/lang/Object", null);
            emitDefaultCtor(ira, "java/lang/Object");
            final MethodVisitor fm = ira.visitMethod(ACC_PUBLIC | ACC_FINAL, "f", "()V", null, null);
            fm.visitCode();
            final Label lLoop = new Label();
            final Label lAfter = new Label();
            fm.visitInsn(ICONST_0);
            fm.visitVarInsn(ISTORE, 1);
            fm.visitMethodInsn(INVOKESTATIC, MemoryLeakMitigationTarget.IMAGE_RESOURCE_OWNER, "access$getCacheList$cp", "()Lcom/live2d/type/CArrayList;", false);
            fm.visitMethodInsn(INVOKEVIRTUAL, "com/live2d/type/CArrayList", "iterator", "()Ljava/util/Iterator;", false);
            fm.visitVarInsn(ASTORE, 2);
            fm.visitLabel(lLoop);
            fm.visitVarInsn(ALOAD, 2);
            fm.visitMethodInsn(INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);
            fm.visitJumpInsn(IFEQ, lAfter);
            fm.visitVarInsn(ALOAD, 2);
            fm.visitMethodInsn(INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true);
            fm.visitTypeInsn(CHECKCAST, "com/live2d/graphics/CImageResource$b");
            fm.visitMethodInsn(INVOKEVIRTUAL, "com/live2d/graphics/CImageResource$b", "a", "()Ljava/lang/ref/SoftReference;", false);
            fm.visitMethodInsn(INVOKEVIRTUAL, "java/lang/ref/SoftReference", "get", "()Ljava/lang/Object;", false);
            fm.visitTypeInsn(CHECKCAST, MemoryLeakMitigationTarget.IMAGE_RESOURCE_OWNER);
            fm.visitVarInsn(ASTORE, 4);
            fm.visitVarInsn(ALOAD, 4);
            fm.visitJumpInsn(IFNULL, lLoop);
            fm.visitVarInsn(ILOAD, 1);
            fm.visitVarInsn(ALOAD, 4);
            fm.visitMethodInsn(INVOKEVIRTUAL, MemoryLeakMitigationTarget.IMAGE_RESOURCE_OWNER, "archiveIfIdle", "()Z", false);
            fm.visitInsn(IOR);
            fm.visitVarInsn(ISTORE, 1);
            fm.visitVarInsn(ILOAD, 1);
            fm.visitJumpInsn(IFEQ, lLoop);
            fm.visitJumpInsn(GOTO, lAfter);
            fm.visitLabel(lAfter);
            fm.visitInsn(RETURN);
            fm.visitMaxs(0, 0);
            fm.visitEnd();
            ira.visitEnd();
            rawClasses.put(MemoryLeakMitigationTarget.IMAGE_RESOURCE_COMPANION_OWNER, ira.toByteArray());

            // CImageResource
            final ClassWriter ir = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            ir.visit(V17, ACC_PUBLIC, MemoryLeakMitigationTarget.IMAGE_RESOURCE_OWNER, null, "java/lang/Object", null);
            ir.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "Companion", "L" + MemoryLeakMitigationTarget.IMAGE_RESOURCE_COMPANION_OWNER + ";", null, null).visitEnd();
            ir.visitField(ACC_PRIVATE | ACC_STATIC, "cacheList", "Lcom/live2d/type/CArrayList;", null, null).visitEnd();
            ir.visitField(ACC_PUBLIC, "releasedRetainUserData_forDebug", "Ljava/util/ArrayList;", null, null).visitEnd();
            ir.visitField(ACC_PUBLIC, "releasedCalls", "I", null, null).visitEnd();
            ir.visitField(ACC_PUBLIC, "archived", "Z", null, null).visitEnd();
            final MethodVisitor ircl = ir.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
            ircl.visitCode();
            ircl.visitTypeInsn(NEW, MemoryLeakMitigationTarget.IMAGE_RESOURCE_COMPANION_OWNER); ircl.visitInsn(DUP);
            ircl.visitMethodInsn(INVOKESPECIAL, MemoryLeakMitigationTarget.IMAGE_RESOURCE_COMPANION_OWNER, "<init>", "()V", false);
            ircl.visitFieldInsn(PUTSTATIC, MemoryLeakMitigationTarget.IMAGE_RESOURCE_OWNER, "Companion", "L" + MemoryLeakMitigationTarget.IMAGE_RESOURCE_COMPANION_OWNER + ";");
            ircl.visitTypeInsn(NEW, "com/live2d/type/CArrayList"); ircl.visitInsn(DUP);
            ircl.visitMethodInsn(INVOKESPECIAL, "com/live2d/type/CArrayList", "<init>", "()V", false);
            ircl.visitFieldInsn(PUTSTATIC, MemoryLeakMitigationTarget.IMAGE_RESOURCE_OWNER, "cacheList", "Lcom/live2d/type/CArrayList;");
            ircl.visitInsn(RETURN); ircl.visitMaxs(0, 0); ircl.visitEnd();
            final MethodVisitor irc = ir.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
            irc.visitCode(); irc.visitVarInsn(ALOAD, 0); irc.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            irc.visitVarInsn(ALOAD, 0); irc.visitTypeInsn(NEW, "java/util/ArrayList"); irc.visitInsn(DUP);
            irc.visitMethodInsn(INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
            irc.visitFieldInsn(PUTFIELD, MemoryLeakMitigationTarget.IMAGE_RESOURCE_OWNER, "releasedRetainUserData_forDebug", "Ljava/util/ArrayList;");
            irc.visitInsn(RETURN); irc.visitMaxs(0, 0); irc.visitEnd();
            final MethodVisitor acc = ir.visitMethod(ACC_PUBLIC | ACC_STATIC, "access$getCacheList$cp", "()Lcom/live2d/type/CArrayList;", null, null);
            acc.visitCode(); acc.visitFieldInsn(GETSTATIC, MemoryLeakMitigationTarget.IMAGE_RESOURCE_OWNER, "cacheList", "Lcom/live2d/type/CArrayList;");
            acc.visitInsn(ARETURN); acc.visitMaxs(0, 0); acc.visitEnd();
            final MethodVisitor aii = ir.visitMethod(ACC_PUBLIC, "archiveIfIdle", "()Z", null, null);
            aii.visitCode(); aii.visitVarInsn(ALOAD, 0); aii.visitInsn(ICONST_1);
            aii.visitFieldInsn(PUTFIELD, MemoryLeakMitigationTarget.IMAGE_RESOURCE_OWNER, "archived", "Z");
            aii.visitInsn(ICONST_1); aii.visitInsn(IRETURN); aii.visitMaxs(0, 0); aii.visitEnd();
            final MethodVisitor rr = ir.visitMethod(ACC_PUBLIC, "releaseResource", MemoryLeakMitigationTarget.RELEASE_RESOURCE_DESCRIPTOR, null, null);
            rr.visitCode();
            rr.visitVarInsn(ALOAD, 0); rr.visitInsn(DUP); rr.visitFieldInsn(GETFIELD, MemoryLeakMitigationTarget.IMAGE_RESOURCE_OWNER, "releasedCalls", "I");
            rr.visitInsn(ICONST_1); rr.visitInsn(IADD); rr.visitFieldInsn(PUTFIELD, MemoryLeakMitigationTarget.IMAGE_RESOURCE_OWNER, "releasedCalls", "I");
            rr.visitVarInsn(ALOAD, 1); rr.visitVarInsn(ASTORE, 4);
            rr.visitVarInsn(ALOAD, 0); rr.visitFieldInsn(GETFIELD, MemoryLeakMitigationTarget.IMAGE_RESOURCE_OWNER, "releasedRetainUserData_forDebug", "Ljava/util/ArrayList;");
            rr.visitVarInsn(ALOAD, 4); rr.visitMethodInsn(INVOKEVIRTUAL, "java/util/ArrayList", "add", "(Ljava/lang/Object;)Z", false); rr.visitInsn(POP);
            rr.visitInsn(RETURN); rr.visitMaxs(0, 0); rr.visitEnd();
            ir.visitEnd();
            rawClasses.put(MemoryLeakMitigationTarget.IMAGE_RESOURCE_OWNER, ir.toByteArray());

            // CImage$a & CImage & CWritableImage
            final ClassWriter cia = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            cia.visit(V17, ACC_PUBLIC, "com/live2d/graphics/CImage$a", null, "java/lang/Object", null);
            emitDefaultCtor(cia, "java/lang/Object");
            final MethodVisitor ca = cia.visitMethod(ACC_PUBLIC, "a", "()Ljava/awt/image/BufferedImage;", null, null);
            ca.visitCode(); ca.visitMethodInsn(INVOKESTATIC, "com/live2d/graphics/CImage", "access$get_INTERNAL_NOT_INITIALIZED_J_IMAGE_$cp", "()Ljava/awt/image/BufferedImage;", false);
            ca.visitInsn(ARETURN); ca.visitMaxs(0, 0); ca.visitEnd();
            cia.visitEnd();
            rawClasses.put("com/live2d/graphics/CImage$a", cia.toByteArray());

            final ClassWriter ci = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            ci.visit(V17, ACC_PUBLIC, "com/live2d/graphics/CImage", null, "java/lang/Object", null);
            ci.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "Companion", "Lcom/live2d/graphics/CImage$a;", null, null).visitEnd();
            ci.visitField(ACC_PUBLIC | ACC_STATIC, "_INTERNAL_NOT_INITIALIZED_J_IMAGE_", "Ljava/awt/image/BufferedImage;", null, null).visitEnd();
            ci.visitField(ACC_PUBLIC, "jImage", "Ljava/awt/Image;", null, null).visitEnd();
            final MethodVisitor cicl = ci.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
            cicl.visitCode();
            cicl.visitTypeInsn(NEW, "com/live2d/graphics/CImage$a"); cicl.visitInsn(DUP);
            cicl.visitMethodInsn(INVOKESPECIAL, "com/live2d/graphics/CImage$a", "<init>", "()V", false);
            cicl.visitFieldInsn(PUTSTATIC, "com/live2d/graphics/CImage", "Companion", "Lcom/live2d/graphics/CImage$a;");
            cicl.visitTypeInsn(NEW, "java/awt/image/BufferedImage"); cicl.visitInsn(DUP);
            cicl.visitInsn(ICONST_1); cicl.visitInsn(ICONST_1); cicl.visitInsn(ICONST_2);
            cicl.visitMethodInsn(INVOKESPECIAL, "java/awt/image/BufferedImage", "<init>", "(III)V", false);
            cicl.visitFieldInsn(PUTSTATIC, "com/live2d/graphics/CImage", "_INTERNAL_NOT_INITIALIZED_J_IMAGE_", "Ljava/awt/image/BufferedImage;");
            cicl.visitInsn(RETURN); cicl.visitMaxs(0, 0); cicl.visitEnd();
            final MethodVisitor cic = ci.visitMethod(ACC_PUBLIC, "<init>", "(Ljava/awt/Image;)V", null, null);
            cic.visitCode(); cic.visitVarInsn(ALOAD, 0); cic.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            cic.visitVarInsn(ALOAD, 0); cic.visitVarInsn(ALOAD, 1); cic.visitFieldInsn(PUTFIELD, "com/live2d/graphics/CImage", "jImage", "Ljava/awt/Image;");
            cic.visitInsn(RETURN); cic.visitMaxs(0, 0); cic.visitEnd();
            emitFieldGetter(ci, "com/live2d/graphics/CImage", "getJImage", "()Ljava/awt/Image;", "jImage");
            final MethodVisitor snt = ci.visitMethod(ACC_PUBLIC | ACC_STATIC, "access$get_INTERNAL_NOT_INITIALIZED_J_IMAGE_$cp", "()Ljava/awt/image/BufferedImage;", null, null);
            snt.visitCode(); snt.visitFieldInsn(GETSTATIC, "com/live2d/graphics/CImage", "_INTERNAL_NOT_INITIALIZED_J_IMAGE_", "Ljava/awt/image/BufferedImage;");
            snt.visitInsn(ARETURN); snt.visitMaxs(0, 0); snt.visitEnd();
            ci.visitEnd();
            rawClasses.put("com/live2d/graphics/CImage", ci.toByteArray());

            final ClassWriter wi = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            wi.visit(V17, ACC_PUBLIC, MemoryLeakMitigationTarget.WRITABLE_IMAGE_OWNER, null, "com/live2d/graphics/CImage", null);
            final MethodVisitor wic = wi.visitMethod(ACC_PUBLIC, "<init>", "(Ljava/awt/Image;)V", null, null);
            wic.visitCode(); wic.visitVarInsn(ALOAD, 0); wic.visitVarInsn(ALOAD, 1);
            wic.visitMethodInsn(INVOKESPECIAL, "com/live2d/graphics/CImage", "<init>", "(Ljava/awt/Image;)V", false);
            wic.visitInsn(RETURN); wic.visitMaxs(0, 0); wic.visitEnd();
            final MethodVisitor fl = wi.visitMethod(ACC_PUBLIC, "flush", "()V", null, null);
            fl.visitCode();
            final Label lSkip = new Label();
            fl.visitVarInsn(ALOAD, 0);
            fl.visitMethodInsn(INVOKEVIRTUAL, "com/live2d/graphics/CImage", "getJImage", "()Ljava/awt/Image;", false);
            fl.visitFieldInsn(GETSTATIC, "com/live2d/graphics/CImage", "Companion", "Lcom/live2d/graphics/CImage$a;");
            fl.visitMethodInsn(INVOKEVIRTUAL, "com/live2d/graphics/CImage$a", "a", "()Ljava/awt/image/BufferedImage;", false);
            fl.visitJumpInsn(IF_ACMPNE, lSkip); // Exact stock bug! Transformer flips to IF_ACMPEQ.
            fl.visitVarInsn(ALOAD, 0);
            fl.visitMethodInsn(INVOKEVIRTUAL, "com/live2d/graphics/CImage", "getJImage", "()Ljava/awt/Image;", false);
            fl.visitMethodInsn(INVOKEVIRTUAL, "java/awt/Image", "flush", "()V", false);
            fl.visitLabel(lSkip);
            fl.visitInsn(RETURN);
            fl.visitMaxs(0, 0);
            fl.visitEnd();
            wi.visitEnd();
            rawClasses.put(MemoryLeakMitigationTarget.WRITABLE_IMAGE_OWNER, wi.toByteArray());
        }

        private void addInspectorAndParamWidgetTypes() {
            final ClassWriter ici = new ClassWriter(0);
            ici.visit(V17, ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT, "com/live2d/inspector/ICInspector", null, "java/lang/Object", null);
            ici.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "setValueList", "(Ljava/util/List;Lcom/live2d/core/a;ZZ)V", null, null).visitEnd();
            ici.visitEnd();
            rawClasses.put("com/live2d/inspector/ICInspector", ici.toByteArray());

            final ClassWriter ci = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            ci.visit(V17, ACC_PUBLIC, "com/live2d/inspector/simpleInspectors/ci", null, "java/lang/Object", new String[] {"com/live2d/inspector/ICInspector"});
            ci.visitField(ACC_PUBLIC, "valuesCleared", "Z", null, null).visitEnd();
            ci.visitField(ACC_PUBLIC, "editModeCleared", "Z", null, null).visitEnd();
            emitDefaultCtor(ci, "java/lang/Object");
            final MethodVisitor svl = ci.visitMethod(ACC_PUBLIC, "setValueList", "(Ljava/util/List;Lcom/live2d/core/a;ZZ)V", null, null);
            svl.visitCode();
            svl.visitVarInsn(ALOAD, 0); svl.visitInsn(ICONST_1);
            svl.visitFieldInsn(PUTFIELD, "com/live2d/inspector/simpleInspectors/ci", "valuesCleared", "Z");
            svl.visitInsn(RETURN); svl.visitMaxs(0, 0); svl.visitEnd();
            final MethodVisitor sem = ci.visitMethod(ACC_PUBLIC, "setEditMode", "(Lcom/live2d/doc/IEditMode;)V", null, null);
            sem.visitCode();
            sem.visitVarInsn(ALOAD, 0); sem.visitInsn(ICONST_1);
            sem.visitFieldInsn(PUTFIELD, "com/live2d/inspector/simpleInspectors/ci", "editModeCleared", "Z");
            sem.visitInsn(RETURN); sem.visitMaxs(0, 0); sem.visitEnd();
            ci.visitEnd();
            rawClasses.put("com/live2d/inspector/simpleInspectors/ci", ci.toByteArray());

            final ClassWriter id = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            id.visit(V17, ACC_PUBLIC, MemoryLeakMitigationTarget.INSPECTOR_CTRL_OWNER, null, "java/lang/Object", null);
            id.visitField(ACC_PUBLIC, "h", "Ljava/lang/Class;", null, null).visitEnd();
            id.visitField(ACC_PUBLIC, "i", "Ljava/util/List;", null, null).visitEnd();
            id.visitField(ACC_PUBLIC, "j", "Ljava/util/Map;", null, null).visitEnd();
            final MethodVisitor idc = id.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
            idc.visitCode(); idc.visitVarInsn(ALOAD, 0); idc.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            idc.visitVarInsn(ALOAD, 0); idc.visitTypeInsn(NEW, "java/util/HashMap"); idc.visitInsn(DUP);
            idc.visitMethodInsn(INVOKESPECIAL, "java/util/HashMap", "<init>", "()V", false);
            idc.visitFieldInsn(PUTFIELD, MemoryLeakMitigationTarget.INSPECTOR_CTRL_OWNER, "j", "Ljava/util/Map;");
            idc.visitInsn(RETURN); idc.visitMaxs(0, 0); idc.visitEnd();
            final MethodVisitor upd = id.visitMethod(ACC_PUBLIC, "a", "(Ljava/util/List;)V", null, null);
            upd.visitCode();
            final Label lNonEmpty = new Label();
            upd.visitVarInsn(ALOAD, 1);
            upd.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "isEmpty", "()Z", true);
            upd.visitJumpInsn(IFEQ, lNonEmpty);
            upd.visitInsn(RETURN);
            upd.visitLabel(lNonEmpty);
            upd.visitInsn(RETURN);
            upd.visitMaxs(0, 0);
            upd.visitEnd();
            id.visitEnd();
            rawClasses.put(MemoryLeakMitigationTarget.INSPECTOR_CTRL_OWNER, id.toByteArray());

            // TestAwtListener & ParameterGroupRowWidget
            final ClassWriter tal = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            tal.visit(V17, ACC_PUBLIC, "TestAwtListener", null, "java/lang/Object", new String[] {"java/awt/event/AWTEventListener"});
            emitDefaultCtor(tal, "java/lang/Object");
            emitVoidMethod(tal, "eventDispatched", "(Ljava/awt/AWTEvent;)V");
            tal.visitEnd();
            rawClasses.put("TestAwtListener", tal.toByteArray());

            final ClassWriter pr = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            pr.visit(V17, ACC_PUBLIC, MemoryLeakMitigationTarget.PARAM_GROUP_ROW_WIDGET_OWNER, null, "java/lang/Object", null);
            pr.visitField(ACC_PUBLIC, "mainWidget", "L" + MemoryLeakMitigationTarget.C_WIDGET_OWNER + ";", null, null).visitEnd();
            pr.visitField(ACC_PUBLIC, "textFieldListener", "Ljava/awt/event/AWTEventListener;", null, null).visitEnd();
            pr.visitField(ACC_PUBLIC, "rename", "Z", null, null).visitEnd();
            final MethodVisitor prc = pr.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
            prc.visitCode(); prc.visitVarInsn(ALOAD, 0); prc.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            prc.visitVarInsn(ALOAD, 0); prc.visitTypeInsn(NEW, MemoryLeakMitigationTarget.C_WIDGET_OWNER); prc.visitInsn(DUP);
            prc.visitMethodInsn(INVOKESPECIAL, MemoryLeakMitigationTarget.C_WIDGET_OWNER, "<init>", "()V", false);
            prc.visitFieldInsn(PUTFIELD, MemoryLeakMitigationTarget.PARAM_GROUP_ROW_WIDGET_OWNER, "mainWidget", "L" + MemoryLeakMitigationTarget.C_WIDGET_OWNER + ";");
            prc.visitVarInsn(ALOAD, 0); prc.visitTypeInsn(NEW, "TestAwtListener"); prc.visitInsn(DUP);
            prc.visitMethodInsn(INVOKESPECIAL, "TestAwtListener", "<init>", "()V", false);
            prc.visitFieldInsn(PUTFIELD, MemoryLeakMitigationTarget.PARAM_GROUP_ROW_WIDGET_OWNER, "textFieldListener", "Ljava/awt/event/AWTEventListener;");
            prc.visitInsn(RETURN); prc.visitMaxs(0, 0); prc.visitEnd();
            final MethodVisitor rnf = pr.visitMethod(ACC_PUBLIC, "renameFolder", "()V", null, null);
            rnf.visitCode();
            rnf.visitVarInsn(ALOAD, 0); rnf.visitInsn(ICONST_1);
            rnf.visitFieldInsn(PUTFIELD, MemoryLeakMitigationTarget.PARAM_GROUP_ROW_WIDGET_OWNER, "rename", "Z");
            rnf.visitInsn(RETURN); rnf.visitMaxs(0, 0); rnf.visitEnd();
            pr.visitEnd();
            rawClasses.put(MemoryLeakMitigationTarget.PARAM_GROUP_ROW_WIDGET_OWNER, pr.toByteArray());
        }

        private void addSimpleInterface(final String owner) {
            final ClassWriter w = new ClassWriter(0);
            w.visit(V17, ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT, owner, null, "java/lang/Object", null);
            w.visitEnd();
            rawClasses.put(owner, w.toByteArray());
        }

        private void addSimpleArrayList(final String owner) {
            final ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            w.visit(V17, ACC_PUBLIC, owner, null, "java/util/ArrayList", null);
            emitDefaultCtor(w, "java/util/ArrayList");
            w.visitEnd();
            rawClasses.put(owner, w.toByteArray());
        }

        private void addSimpleClass(
                final String owner, final String superName, final String[] fields, final boolean initMaps, final String... extra) {
            final String sup = superName == null ? "java/lang/Object" : superName;
            final ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            w.visit(V17, ACC_PUBLIC, owner, null, sup, null);
            for (final String spec : fields) {
                final String[] parts = spec.split(":");
                final int acc = ACC_PUBLIC | ("S".equals(parts[2]) ? ACC_STATIC : 0);
                w.visitField(acc, parts[0], parts[1], null, null).visitEnd();
            }
            emitDefaultCtor(w, sup);
            if (initMaps) {
                final MethodVisitor cl = w.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
                cl.visitCode();
                for (final String spec : fields) {
                    final String[] parts = spec.split(":");
                    if ("S".equals(parts[2])) {
                        final String typeOwner = parts[1].substring(1, parts[1].length() - 1);
                        cl.visitTypeInsn(NEW, typeOwner);
                        cl.visitInsn(DUP);
                        cl.visitMethodInsn(INVOKESPECIAL, typeOwner, "<init>", "()V", false);
                        cl.visitFieldInsn(PUTSTATIC, owner, parts[0], parts[1]);
                    }
                }
                cl.visitInsn(RETURN);
                cl.visitMaxs(0, 0);
                cl.visitEnd();
            }
            for (final String mSpec : extra) {
                if ("isClipper:()Z:false".equals(mSpec)) {
                    emitBooleanGetter(w, "isClipper", false);
                }
            }
            w.visitEnd();
            rawClasses.put(owner, w.toByteArray());
        }

        private static void gecMax(final MethodVisitor m) {
            m.visitMaxs(0, 0);
            m.visitEnd();
        }

        private static void emitFieldGetter(
                final ClassWriter w, final String owner, final String method, final String desc, final String field) {
            final MethodVisitor m = w.visitMethod(ACC_PUBLIC, method, desc, null, null);
            m.visitCode();
            m.visitVarInsn(ALOAD, 0);
            m.visitFieldInsn(GETFIELD, owner, field, desc.substring(2));
            m.visitInsn(ARETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        }

        private static void emitFieldSetter(
                final ClassWriter w, final String owner, final String method, final String desc, final String field) {
            final String fDesc = desc.substring(1, desc.length() - 2);
            final MethodVisitor m = w.visitMethod(ACC_PUBLIC, method, desc, null, null);
            m.visitCode();
            m.visitVarInsn(ALOAD, 0);
            m.visitVarInsn(ALOAD, 1);
            m.visitFieldInsn(PUTFIELD, owner, field, fDesc);
            m.visitInsn(RETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        }

        private static void emitBooleanFieldSetter(
                final ClassWriter w, final String owner, final String method, final String desc, final String field) {
            final MethodVisitor m = w.visitMethod(ACC_PUBLIC, method, desc, null, null);
            m.visitCode();
            m.visitVarInsn(ALOAD, 0);
            m.visitVarInsn(ILOAD, 1);
            m.visitFieldInsn(PUTFIELD, owner, field, "Z");
            m.visitInsn(RETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        }

        private static void emitBooleanFieldGetter(
                final ClassWriter w, final String owner, final String method, final String field) {
            final MethodVisitor m = w.visitMethod(ACC_PUBLIC, method, "()Z", null, null);
            m.visitCode();
            m.visitVarInsn(ALOAD, 0);
            m.visitFieldInsn(GETFIELD, owner, field, "Z");
            m.visitInsn(IRETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        }

        private static void emitBooleanGetter(final ClassWriter w, final String method, final boolean value) {
            final MethodVisitor m = w.visitMethod(ACC_PUBLIC, method, "()Z", null, null);
            m.visitCode();
            m.visitInsn(value ? ICONST_1 : ICONST_0);
            m.visitInsn(IRETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        }

        private static void emitVoidMethod(final ClassWriter w, final String method, final String desc) {
            final MethodVisitor m = w.visitMethod(ACC_PUBLIC, method, desc, null, null);
            m.visitCode();
            m.visitInsn(RETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        }

        private static void emitReturnNull(final ClassWriter w, final String method, final String desc) {
            final MethodVisitor m = w.visitMethod(ACC_PUBLIC, method, desc, null, null);
            m.visitCode();
            m.visitInsn(ACONST_NULL);
            m.visitInsn(ARETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        }

        private static void emitReturnNewList(final ClassWriter w, final String method) {
            final MethodVisitor m = w.visitMethod(ACC_PUBLIC, method, "()Ljava/util/List;", null, null);
            m.visitCode();
            m.visitTypeInsn(NEW, "java/util/ArrayList");
            m.visitInsn(DUP);
            m.visitMethodInsn(INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
            m.visitInsn(ARETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        }
    }
}
