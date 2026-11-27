package dev.turboism.adapter.cubism.optimization.atlas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.objectweb.asm.Opcodes.*;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

/**
 * Verifies {@link AtlasDirectCompositingPatcher} and {@link AtlasPageRebuildCoordinator} 3-phase
 * multi-page execution using synthetic Cubism host bytecode in an isolated classloader:
 * owner-thread prewarm, parallel CPU tile compositing, and strictly owner-thread resource/cache/progress commits.
 */
final class AtlasPageRebuildCoordinatorTest {

    @Test
    void multiPageTextureUpdateBakesOnWorkersAndCommitsOnOwnerThread() throws Exception {
        final SyntheticHostEnv env = SyntheticHostEnv.create();
        final List<String> events = Collections.synchronizedList(new ArrayList<>());
        final Set<Thread> workerThreads = ConcurrentHashMap.newKeySet();
        final AtomicBoolean allowBakeCancel = new AtomicBoolean(false);
        env.configure(Thread.currentThread(), events, workerThreads, allowBakeCancel);

        final Object atlas0 = env.newAtlas("page0", 64, 64, 0xFF102030);
        final Object atlas1 = env.newAtlas("page1", 64, 64, 0xFF405060);
        final Object atlas2 = env.newAtlas("page2", 64, 64, 0xFF708090);
        final List<Object> atlases = List.of(atlas0, atlas1, atlas2);

        final Object gLoop = env.newTextureUpdateLoop(atlases);
        final Object progress = env.newProgress(false);
        env.runTextureUpdateLoop(gLoop, progress);

        assertFalse(workerThreads.isEmpty(), "Expected parallel worker threads during phase 2");
        for (final Object atlas : atlases) {
            final BufferedImage committed = env.getAtlasCommittedImage(atlas);
            assertNotNull(committed, "Committed image must exist for atlas");
            assertFalse(env.isDirty(atlas), "Committed atlas must mark cachedAtlasImage clean");
            assertEquals(1, env.getAtlasVersion(atlas));
        }
        assertEquals(atlases.size(), env.cacheRegistrations());
        assertEquals(0, env.offOwnerResourceCount(), "CImageResource must only construct on owner thread");
        assertEquals(0, env.offOwnerProgressCount(), "Progress callbacks must only run on owner thread");
        assertEquals(0, env.offOwnerTransformPrewarmCount(), "Prewarm must finish before worker fan-out");
    }

    @Test
    void confirmLoopBatchesPagesIntoThreePhaseParallelRebuildAndHandlesCancel() throws Exception {
        final SyntheticHostEnv env = SyntheticHostEnv.create();
        final List<String> events = Collections.synchronizedList(new ArrayList<>());
        final Set<Thread> workerThreads = ConcurrentHashMap.newKeySet();
        final AtomicBoolean cancelFlag = new AtomicBoolean(false);
        env.configure(Thread.currentThread(), events, workerThreads, cancelFlag);

        final Object atlas0 = env.newAtlas("confirm0", 64, 64, 0xFFAA2211);
        final Object atlas1 = env.newAtlas("confirm1", 64, 64, 0xFF1122AA);
        final Object confirmLoop = env.newConfirmLoop(List.of(atlas0, atlas1), true);
        final Object progress = env.newProgress(false);
        env.runConfirmLoop(confirmLoop, progress);

        assertNotNull(env.getAtlasCommittedImage(atlas0));
        assertNotNull(env.getAtlasCommittedImage(atlas1));
        assertEquals(2, env.cacheRegistrations());
        assertEquals(0, env.offOwnerResourceCount());
        assertEquals(0, env.offOwnerProgressCount());

        // Canceled rebuilds abort cleanly without committing half-baked resources or leaking writable buffers.
        env.resetCounters();
        cancelFlag.set(true);
        final Object cancelAtlas0 = env.newAtlas("c0", 32, 32, 0xFF00FF00);
        final Object cancelAtlas1 = env.newAtlas("c1", 32, 32, 0xFF0000FF);
        env.runConfirmLoop(env.newConfirmLoop(List.of(cancelAtlas0, cancelAtlas1), true), env.newProgress(true));
        assertEquals(0, env.cacheRegistrations());
    }

    /** Defines minimal bytecode classes mirroring the Cubism contracts scanned by the patchers. */
    private static final class SyntheticHostEnv {
        private final PatchedSyntheticLoader loader;
        private final Class<?> trackerClass;
        private final Class<?> atlasClass;
        private final Class<?> entryClass;
        private final Class<?> modelImageClass;
        private final Class<?> writableClass;
        private final Class<?> texMgrClass;
        private final Class<?> modelSourceClass;
        private final Class<?> progressClass;
        private final Class<?> gClass;
        private final Class<?> taeModelClass;
        private final Class<?> confirmClass;

        private SyntheticHostEnv(final PatchedSyntheticLoader loader) throws Exception {
            this.loader = loader;
            this.trackerClass = loader.loadClass("dev.synthetic.Tracker");
            this.atlasClass = loader.loadClass("com.live2d.cubism.doc.model.texture.textureAtlas.CTextureAtlas");
            this.entryClass =
                    loader.loadClass("com.live2d.cubism.doc.model.texture.textureAtlas.CTextureAtlas$ModelImageEntry");
            this.modelImageClass = loader.loadClass("com.live2d.cubism.doc.model.texture.modelImage.CModelImage");
            this.writableClass = loader.loadClass("com.live2d.graphics.CWritableImage");
            this.texMgrClass = loader.loadClass("com.live2d.cubism.doc.model.texture.CTextureManager");
            this.modelSourceClass = loader.loadClass("com.live2d.cubism.doc.model.CModelSource");
            this.progressClass = loader.loadClass("com.live2d.util.a.a");
            this.gClass = loader.loadClass("com.live2d.cubism.doc.model.texture.G");
            this.taeModelClass = loader.loadClass("com.live2d.cubism.doc.modeling.ui.atlasEditor.impl.TAE_DataModel");
            this.confirmClass = loader.loadClass("com.live2d.cubism.doc.modeling.ui.atlasEditor.impl.c");
        }

        static SyntheticHostEnv create() throws Exception {
            final Map<String, byte[]> classes = buildClasses();
            classes.computeIfPresent(
                    AtlasDirectCompositingPatcher.TEXTURE_ATLAS, (k, v) -> AtlasDirectCompositingPatcher.patch(k, v));
            classes.computeIfPresent(
                    AtlasDirectCompositingPatcher.CONFIRM_LOOP, (k, v) -> AtlasDirectCompositingPatcher.patch(k, v));
            classes.computeIfPresent(
                    AtlasDirectCompositingPatcher.TEXTURE_UPDATE_LOOP,
                    (k, v) -> AtlasDirectCompositingPatcher.patch(k, v));
            return new SyntheticHostEnv(new PatchedSyntheticLoader(classes));
        }

        void configure(
                final Thread owner, final List<String> events, final Set<Thread> workers, final AtomicBoolean cancel)
                throws Exception {
            trackerClass.getField("ownerThread").set(null, owner);
            trackerClass.getField("events").set(null, events);
            trackerClass.getField("workerThreads").set(null, workers);
            trackerClass.getField("canceled").set(null, cancel);
            resetCounters();
        }

        void resetCounters() throws Exception {
            ((AtomicInteger) trackerClass.getField("cacheRegistrations").get(null)).set(0);
            ((AtomicInteger) trackerClass.getField("offOwnerResourceCount").get(null)).set(0);
            ((AtomicInteger) trackerClass.getField("offOwnerProgressCount").get(null)).set(0);
            ((AtomicInteger) trackerClass.getField("offOwnerPrewarmCount").get(null)).set(0);
        }

        Object newAtlas(final String name, final int w, final int h, final int color) throws Exception {
            final Object source = modelSourceClass.getConstructor().newInstance();
            final Object mgr = texMgrClass.getConstructor().newInstance();
            modelSourceClass.getField("textureManager").set(source, mgr);

            final BufferedImage srcBi = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            final Graphics2D g = srcBi.createGraphics();
            try {
                g.setColor(new Color(color, true));
                g.fillRect(0, 0, 16, 16);
            } finally {
                g.dispose();
            }
            final Object srcWi =
                    writableClass.getConstructor(BufferedImage.class).newInstance(srcBi);
            final Object mi =
                    modelImageClass.getConstructor(String.class, writableClass).newInstance(name + "-tile", srcWi);
            texMgrClass.getMethod("put", String.class, modelImageClass).invoke(mgr, name + "-guid", mi);

            final Object atlas = atlasClass
                    .getConstructor(String.class, int.class, int.class, modelSourceClass)
                    .newInstance(name, w, h, source);
            final Object entry = entryClass.getConstructor(String.class).newInstance(name + "-guid");
            atlasClass.getMethod("addEntry", entryClass).invoke(atlas, entry);
            return atlas;
        }

        Object newTextureUpdateLoop(final List<Object> atlases) throws Exception {
            return gClass.getConstructor(List.class).newInstance(atlases);
        }

        void runTextureUpdateLoop(final Object gLoop, final Object progress) throws Exception {
            gClass.getMethod("a", progressClass).invoke(gLoop, progress);
        }

        Object newConfirmLoop(final List<Object> atlases, final boolean edgeFill) throws Exception {
            final Object tae =
                    taeModelClass.getConstructor(List.class, boolean.class).newInstance(atlases, edgeFill);
            return confirmClass.getConstructor(taeModelClass).newInstance(tae);
        }

        void runConfirmLoop(final Object confirm, final Object progress) throws Exception {
            confirmClass.getMethod("a", progressClass).invoke(confirm, progress);
        }

        Object newProgress(final boolean canceled) throws Exception {
            return progressClass.getConstructor(boolean.class).newInstance(canceled);
        }

        BufferedImage getAtlasCommittedImage(final Object atlas) throws Exception {
            final Object res = atlasClass.getField("cachedAtlasImage").get(atlas);
            if (res == null) {
                return null;
            }
            final Object wi = res.getClass().getMethod("getImage").invoke(res);
            return (BufferedImage) writableClass.getMethod("getJBufferedImage").invoke(wi);
        }

        boolean isDirty(final Object atlas) throws Exception {
            return atlasClass.getField("isDirty_cachedAtlasImage").getBoolean(atlas);
        }

        int getAtlasVersion(final Object atlas) throws Exception {
            return atlasClass.getField("atlasVersion").getInt(atlas);
        }

        int cacheRegistrations() throws Exception {
            return ((AtomicInteger) trackerClass.getField("cacheRegistrations").get(null)).get();
        }

        int offOwnerResourceCount() throws Exception {
            return ((AtomicInteger)
                            trackerClass.getField("offOwnerResourceCount").get(null))
                    .get();
        }

        int offOwnerProgressCount() throws Exception {
            return ((AtomicInteger)
                            trackerClass.getField("offOwnerProgressCount").get(null))
                    .get();
        }

        int offOwnerTransformPrewarmCount() throws Exception {
            return ((AtomicInteger)
                            trackerClass.getField("offOwnerPrewarmCount").get(null))
                    .get();
        }

        private static Map<String, byte[]> buildClasses() {
            final Map<String, byte[]> out = new HashMap<>();
            out.put("kotlin/Unit", emitKotlinUnit());
            out.put("kotlin/jvm/functions/Function1", emitFunction1());
            out.put("kotlin/jvm/internal/DefaultConstructorMarker", emitSimpleMarker());
            out.put("dev/synthetic/Tracker", emitTracker());
            out.put("com/live2d/type/CAffine", emitCAffine());
            out.put("com/live2d/graphics/n", emitColorType());
            out.put("com/live2d/graphics/CImage", emitCImage());
            out.put("com/live2d/graphics/UiG", emitUiG());
            out.put("com/live2d/graphics/CWritableImage", emitWritableImage());
            out.put("com/live2d/graphics/CImageResource", emitImageResource());
            out.put("com/live2d/graphics/cachedImage/CCachedImageManager", emitCacheManager());
            out.put("com/live2d/util/a/a$Companion", emitProgressCompanion());
            out.put("com/live2d/util/a/a", emitProgress());
            out.put("com/live2d/util/D", emitEdgeHelper());
            out.put("com/live2d/cubism/doc/model/texture/modelImage/CModelImage", emitModelImage());
            out.put("com/live2d/cubism/doc/model/texture/CTextureManager", emitTextureManager());
            out.put("com/live2d/cubism/doc/model/CModelSource", emitModelSource());
            out.put("com/live2d/cubism/doc/model/texture/textureAtlas/CTextureAtlas$ModelImageEntry", emitEntry());
            out.put("com/live2d/cubism/doc/model/texture/textureAtlas/CTextureAtlas", emitAtlas());
            out.put("com/live2d/cubism/doc/model/texture/H", emitH());
            out.put("com/live2d/cubism/doc/model/texture/G", emitG());
            out.put("com/live2d/cubism/doc/modeling/ui/atlasEditor/impl/TAE_DataModel", emitTaeModel());
            out.put("com/live2d/cubism/doc/modeling/ui/atlasEditor/impl/d", emitConfirmTask());
            out.put("com/live2d/cubism/doc/modeling/ui/atlasEditor/impl/c", emitConfirmLoop());
            return out;
        }

        private static byte[] emitKotlinUnit() {
            final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(V17, ACC_PUBLIC | ACC_FINAL, "kotlin/Unit", null, "java/lang/Object", null);
            cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "INSTANCE", "Lkotlin/Unit;", null, null)
                    .visitEnd();
            final MethodVisitor clinit = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
            clinit.visitTypeInsn(NEW, "kotlin/Unit");
            clinit.visitInsn(DUP);
            clinit.visitMethodInsn(INVOKESPECIAL, "kotlin/Unit", "<init>", "()V", false);
            clinit.visitFieldInsn(PUTSTATIC, "kotlin/Unit", "INSTANCE", "Lkotlin/Unit;");
            clinit.visitInsn(RETURN);
            clinit.visitMaxs(0, 0);
            clinit.visitEnd();
            emitDefaultCtor(cw, "java/lang/Object");
            cw.visitEnd();
            return cw.toByteArray();
        }

        private static byte[] emitFunction1() {
            final ClassWriter cw = new ClassWriter(0);
            cw.visit(
                    V17,
                    ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT,
                    "kotlin/jvm/functions/Function1",
                    null,
                    "java/lang/Object",
                    null);
            cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "invoke", "(Ljava/lang/Object;)Ljava/lang/Object;", null, null)
                    .visitEnd();
            cw.visitEnd();
            return cw.toByteArray();
        }

        private static byte[] emitSimpleMarker() {
            final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(
                    V17,
                    ACC_PUBLIC | ACC_FINAL,
                    "kotlin/jvm/internal/DefaultConstructorMarker",
                    null,
                    "java/lang/Object",
                    null);
            emitDefaultCtor(cw, "java/lang/Object");
            cw.visitEnd();
            return cw.toByteArray();
        }

        private static byte[] emitTracker() {
            final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(V17, ACC_PUBLIC | ACC_FINAL, "dev/synthetic/Tracker", null, "java/lang/Object", null);
            cw.visitField(ACC_PUBLIC | ACC_STATIC, "ownerThread", "Ljava/lang/Thread;", null, null)
                    .visitEnd();
            cw.visitField(ACC_PUBLIC | ACC_STATIC, "events", "Ljava/util/List;", null, null)
                    .visitEnd();
            cw.visitField(ACC_PUBLIC | ACC_STATIC, "workerThreads", "Ljava/util/Set;", null, null)
                    .visitEnd();
            cw.visitField(
                            ACC_PUBLIC | ACC_STATIC,
                            "canceled",
                            "Ljava/util/concurrent/atomic/AtomicBoolean;",
                            null,
                            null)
                    .visitEnd();
            cw.visitField(
                            ACC_PUBLIC | ACC_STATIC | ACC_FINAL,
                            "cacheRegistrations",
                            "Ljava/util/concurrent/atomic/AtomicInteger;",
                            null,
                            null)
                    .visitEnd();
            cw.visitField(
                            ACC_PUBLIC | ACC_STATIC | ACC_FINAL,
                            "offOwnerResourceCount",
                            "Ljava/util/concurrent/atomic/AtomicInteger;",
                            null,
                            null)
                    .visitEnd();
            cw.visitField(
                            ACC_PUBLIC | ACC_STATIC | ACC_FINAL,
                            "offOwnerProgressCount",
                            "Ljava/util/concurrent/atomic/AtomicInteger;",
                            null,
                            null)
                    .visitEnd();
            cw.visitField(
                            ACC_PUBLIC | ACC_STATIC | ACC_FINAL,
                            "offOwnerPrewarmCount",
                            "Ljava/util/concurrent/atomic/AtomicInteger;",
                            null,
                            null)
                    .visitEnd();

            final MethodVisitor cl = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
            for (final String f : List.of(
                    "cacheRegistrations", "offOwnerResourceCount", "offOwnerProgressCount", "offOwnerPrewarmCount")) {
                cl.visitTypeInsn(NEW, "java/util/concurrent/atomic/AtomicInteger");
                cl.visitInsn(DUP);
                cl.visitMethodInsn(INVOKESPECIAL, "java/util/concurrent/atomic/AtomicInteger", "<init>", "()V", false);
                cl.visitFieldInsn(PUTSTATIC, "dev/synthetic/Tracker", f, "Ljava/util/concurrent/atomic/AtomicInteger;");
            }
            cl.visitInsn(RETURN);
            cl.visitMaxs(0, 0);
            cl.visitEnd();

            for (final String method : List.of("onResourceCreated", "onProgress", "onPrewarm")) {
                final String counter = method.equals("onResourceCreated")
                        ? "offOwnerResourceCount"
                        : (method.equals("onProgress") ? "offOwnerProgressCount" : "offOwnerPrewarmCount");
                final MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, method, "()V", null, null);
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Thread", "currentThread", "()Ljava/lang/Thread;", false);
                mv.visitFieldInsn(GETSTATIC, "dev/synthetic/Tracker", "ownerThread", "Ljava/lang/Thread;");
                final org.objectweb.asm.Label same = new org.objectweb.asm.Label();
                mv.visitJumpInsn(IF_ACMPEQ, same);
                mv.visitFieldInsn(
                        GETSTATIC, "dev/synthetic/Tracker", counter, "Ljava/util/concurrent/atomic/AtomicInteger;");
                mv.visitMethodInsn(
                        INVOKEVIRTUAL, "java/util/concurrent/atomic/AtomicInteger", "incrementAndGet", "()I", false);
                mv.visitInsn(POP);
                mv.visitLabel(same);
                mv.visitFrame(F_SAME, 0, null, 0, null);
                mv.visitInsn(RETURN);
                mv.visitMaxs(0, 0);
                mv.visitEnd();
            }

            final MethodVisitor recW = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "recordWorker", "()V", null, null);
            recW.visitFieldInsn(GETSTATIC, "dev/synthetic/Tracker", "workerThreads", "Ljava/util/Set;");
            final org.objectweb.asm.Label doneW = new org.objectweb.asm.Label();
            recW.visitJumpInsn(IFNULL, doneW);
            recW.visitFieldInsn(GETSTATIC, "dev/synthetic/Tracker", "workerThreads", "Ljava/util/Set;");
            recW.visitMethodInsn(INVOKESTATIC, "java/lang/Thread", "currentThread", "()Ljava/lang/Thread;", false);
            recW.visitMethodInsn(INVOKEINTERFACE, "java/util/Set", "add", "(Ljava/lang/Object;)Z", true);
            recW.visitInsn(POP);
            recW.visitLabel(doneW);
            recW.visitFrame(F_SAME, 0, null, 0, null);
            recW.visitInsn(RETURN);
            recW.visitMaxs(0, 0);
            recW.visitEnd();

            emitDefaultCtor(cw, "java/lang/Object");
            cw.visitEnd();
            return cw.toByteArray();
        }

        private static byte[] emitCAffine() {
            final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(V17, ACC_PUBLIC, "com/live2d/type/CAffine", null, "java/awt/geom/AffineTransform", null);
            emitDefaultCtor(cw, "java/awt/geom/AffineTransform");
            cw.visitEnd();
            return cw.toByteArray();
        }

        private static byte[] emitColorType() {
            final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(V17, ACC_PUBLIC | ACC_FINAL, "com/live2d/graphics/n", null, "java/lang/Object", null);
            cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "c", "Lcom/live2d/graphics/n;", null, null)
                    .visitEnd();
            final MethodVisitor cl = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
            cl.visitTypeInsn(NEW, "com/live2d/graphics/n");
            cl.visitInsn(DUP);
            cl.visitMethodInsn(INVOKESPECIAL, "com/live2d/graphics/n", "<init>", "()V", false);
            cl.visitFieldInsn(PUTSTATIC, "com/live2d/graphics/n", "c", "Lcom/live2d/graphics/n;");
            cl.visitInsn(RETURN);
            cl.visitMaxs(0, 0);
            cl.visitEnd();
            emitDefaultCtor(cw, "java/lang/Object");
            cw.visitEnd();
            return cw.toByteArray();
        }

        private static byte[] emitCImage() {
            final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(V17, ACC_PUBLIC, "com/live2d/graphics/CImage", null, "java/lang/Object", null);
            emitDefaultCtor(cw, "java/lang/Object");
            cw.visitEnd();
            return cw.toByteArray();
        }

        private static byte[] emitUiG() {
            final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(V17, ACC_PUBLIC, "com/live2d/graphics/UiG", null, "java/lang/Object", null);
            cw.visitField(ACC_PRIVATE | ACC_FINAL, "g", "Ljava/awt/Graphics2D;", null, null)
                    .visitEnd();
            final MethodVisitor ctor = cw.visitMethod(ACC_PUBLIC, "<init>", "(Ljava/awt/Graphics2D;)V", null, null);
            ctor.visitVarInsn(ALOAD, 0);
            ctor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            ctor.visitVarInsn(ALOAD, 0);
            ctor.visitVarInsn(ALOAD, 1);
            ctor.visitFieldInsn(PUTFIELD, "com/live2d/graphics/UiG", "g", "Ljava/awt/Graphics2D;");
            ctor.visitInsn(RETURN);
            ctor.visitMaxs(0, 0);
            ctor.visitEnd();

            final MethodVisitor aM = cw.visitMethod(ACC_PUBLIC, "a", "()Ljava/awt/Graphics2D;", null, null);
            aM.visitVarInsn(ALOAD, 0);
            aM.visitFieldInsn(GETFIELD, "com/live2d/graphics/UiG", "g", "Ljava/awt/Graphics2D;");
            aM.visitInsn(ARETURN);
            aM.visitMaxs(0, 0);
            aM.visitEnd();

            final MethodVisitor eM = cw.visitMethod(ACC_PUBLIC, "e", "()V", null, null);
            eM.visitInsn(RETURN);
            eM.visitMaxs(0, 0);
            eM.visitEnd();

            final MethodVisitor tM = cw.visitMethod(ACC_PUBLIC, "a", "(Lcom/live2d/type/CAffine;)V", null, null);
            tM.visitVarInsn(ALOAD, 0);
            tM.visitFieldInsn(GETFIELD, "com/live2d/graphics/UiG", "g", "Ljava/awt/Graphics2D;");
            tM.visitVarInsn(ALOAD, 1);
            tM.visitMethodInsn(
                    INVOKEVIRTUAL, "java/awt/Graphics2D", "setTransform", "(Ljava/awt/geom/AffineTransform;)V", false);
            tM.visitInsn(RETURN);
            tM.visitMaxs(0, 0);
            tM.visitEnd();

            final MethodVisitor drawM =
                    cw.visitMethod(ACC_PUBLIC, "a", "(Lcom/live2d/graphics/CImage;II)V", null, null);
            drawM.visitInsn(RETURN);
            drawM.visitMaxs(0, 0);
            drawM.visitEnd();

            final MethodVisitor dM = cw.visitMethod(ACC_PUBLIC, "d", "()V", null, null);
            dM.visitVarInsn(ALOAD, 0);
            dM.visitFieldInsn(GETFIELD, "com/live2d/graphics/UiG", "g", "Ljava/awt/Graphics2D;");
            dM.visitMethodInsn(INVOKEVIRTUAL, "java/awt/Graphics2D", "dispose", "()V", false);
            dM.visitInsn(RETURN);
            dM.visitMaxs(0, 0);
            dM.visitEnd();

            cw.visitEnd();
            return cw.toByteArray();
        }

        private static byte[] emitWritableImage() {
            final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(V17, ACC_PUBLIC, "com/live2d/graphics/CWritableImage", null, "com/live2d/graphics/CImage", null);
            cw.visitField(ACC_PUBLIC | ACC_FINAL, "bi", "Ljava/awt/image/BufferedImage;", null, null)
                    .visitEnd();

            final MethodVisitor ctor1 =
                    cw.visitMethod(ACC_PUBLIC, "<init>", "(IILcom/live2d/graphics/n;)V", null, null);
            ctor1.visitVarInsn(ALOAD, 0);
            ctor1.visitMethodInsn(INVOKESPECIAL, "com/live2d/graphics/CImage", "<init>", "()V", false);
            ctor1.visitVarInsn(ALOAD, 0);
            ctor1.visitTypeInsn(NEW, "java/awt/image/BufferedImage");
            ctor1.visitInsn(DUP);
            ctor1.visitVarInsn(ILOAD, 1);
            ctor1.visitVarInsn(ILOAD, 2);
            ctor1.visitInsn(ICONST_2);
            ctor1.visitMethodInsn(INVOKESPECIAL, "java/awt/image/BufferedImage", "<init>", "(III)V", false);
            ctor1.visitFieldInsn(
                    PUTFIELD, "com/live2d/graphics/CWritableImage", "bi", "Ljava/awt/image/BufferedImage;");
            ctor1.visitMethodInsn(INVOKESTATIC, "dev/synthetic/Tracker", "recordWorker", "()V", false);
            ctor1.visitInsn(RETURN);
            ctor1.visitMaxs(0, 0);
            ctor1.visitEnd();

            final MethodVisitor ctor2 =
                    cw.visitMethod(ACC_PUBLIC, "<init>", "(Ljava/awt/image/BufferedImage;)V", null, null);
            ctor2.visitVarInsn(ALOAD, 0);
            ctor2.visitMethodInsn(INVOKESPECIAL, "com/live2d/graphics/CImage", "<init>", "()V", false);
            ctor2.visitVarInsn(ALOAD, 0);
            ctor2.visitVarInsn(ALOAD, 1);
            ctor2.visitFieldInsn(
                    PUTFIELD, "com/live2d/graphics/CWritableImage", "bi", "Ljava/awt/image/BufferedImage;");
            ctor2.visitInsn(RETURN);
            ctor2.visitMaxs(0, 0);
            ctor2.visitEnd();

            final MethodVisitor jbi =
                    cw.visitMethod(ACC_PUBLIC, "getJBufferedImage", "()Ljava/awt/image/BufferedImage;", null, null);
            jbi.visitVarInsn(ALOAD, 0);
            jbi.visitFieldInsn(GETFIELD, "com/live2d/graphics/CWritableImage", "bi", "Ljava/awt/image/BufferedImage;");
            jbi.visitInsn(ARETURN);
            jbi.visitMaxs(0, 0);
            jbi.visitEnd();

            final MethodVisitor fill = cw.visitMethod(ACC_PUBLIC, "fill", "(I)V", null, null);
            fill.visitInsn(RETURN);
            fill.visitMaxs(0, 0);
            fill.visitEnd();

            final MethodVisitor flush = cw.visitMethod(ACC_PUBLIC, "flush", "()V", null, null);
            flush.visitInsn(RETURN);
            flush.visitMaxs(0, 0);
            flush.visitEnd();

            final MethodVisitor gg =
                    cw.visitMethod(ACC_PUBLIC, "getGraphics", "()Lcom/live2d/graphics/UiG;", null, null);
            gg.visitTypeInsn(NEW, "com/live2d/graphics/UiG");
            gg.visitInsn(DUP);
            gg.visitVarInsn(ALOAD, 0);
            gg.visitFieldInsn(GETFIELD, "com/live2d/graphics/CWritableImage", "bi", "Ljava/awt/image/BufferedImage;");
            gg.visitMethodInsn(
                    INVOKEVIRTUAL, "java/awt/image/BufferedImage", "createGraphics", "()Ljava/awt/Graphics2D;", false);
            gg.visitMethodInsn(INVOKESPECIAL, "com/live2d/graphics/UiG", "<init>", "(Ljava/awt/Graphics2D;)V", false);
            gg.visitInsn(ARETURN);
            gg.visitMaxs(0, 0);
            gg.visitEnd();

            cw.visitEnd();
            return cw.toByteArray();
        }

        private static byte[] emitImageResource() {
            final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(V17, ACC_PUBLIC, "com/live2d/graphics/CImageResource", null, "java/lang/Object", null);
            cw.visitField(ACC_PUBLIC | ACC_FINAL, "img", "Lcom/live2d/graphics/CWritableImage;", null, null)
                    .visitEnd();

            final MethodVisitor c1 = cw.visitMethod(
                    ACC_PUBLIC,
                    "<init>",
                    "(Lcom/live2d/graphics/CWritableImage;Lcom/live2d/graphics/n;ZILkotlin/jvm/internal/DefaultConstructorMarker;)V",
                    null,
                    null);
            c1.visitVarInsn(ALOAD, 0);
            c1.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            c1.visitVarInsn(ALOAD, 0);
            c1.visitVarInsn(ALOAD, 1);
            c1.visitFieldInsn(
                    PUTFIELD, "com/live2d/graphics/CImageResource", "img", "Lcom/live2d/graphics/CWritableImage;");
            c1.visitMethodInsn(INVOKESTATIC, "dev/synthetic/Tracker", "onResourceCreated", "()V", false);
            c1.visitInsn(RETURN);
            c1.visitMaxs(0, 0);
            c1.visitEnd();

            final MethodVisitor c2 =
                    cw.visitMethod(ACC_PUBLIC, "<init>", "(Lcom/live2d/graphics/CWritableImage;Z)V", null, null);
            c2.visitVarInsn(ALOAD, 0);
            c2.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            c2.visitVarInsn(ALOAD, 0);
            c2.visitVarInsn(ALOAD, 1);
            c2.visitFieldInsn(
                    PUTFIELD, "com/live2d/graphics/CImageResource", "img", "Lcom/live2d/graphics/CWritableImage;");
            c2.visitInsn(RETURN);
            c2.visitMaxs(0, 0);
            c2.visitEnd();

            final MethodVisitor get =
                    cw.visitMethod(ACC_PUBLIC, "getImage", "()Lcom/live2d/graphics/CWritableImage;", null, null);
            get.visitVarInsn(ALOAD, 0);
            get.visitFieldInsn(
                    GETFIELD, "com/live2d/graphics/CImageResource", "img", "Lcom/live2d/graphics/CWritableImage;");
            get.visitInsn(ARETURN);
            get.visitMaxs(0, 0);
            get.visitEnd();

            cw.visitEnd();
            return cw.toByteArray();
        }

        private static byte[] emitCacheManager() {
            final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(
                    V17,
                    ACC_PUBLIC,
                    "com/live2d/graphics/cachedImage/CCachedImageManager",
                    null,
                    "java/lang/Object",
                    null);
            emitDefaultCtor(cw, "java/lang/Object");
            final MethodVisitor reg =
                    cw.visitMethod(ACC_PUBLIC, "a", "(Lcom/live2d/graphics/CImageResource;)V", null, null);
            reg.visitMethodInsn(INVOKESTATIC, "dev/synthetic/Tracker", "onResourceCreated", "()V", false);
            reg.visitFieldInsn(
                    GETSTATIC,
                    "dev/synthetic/Tracker",
                    "cacheRegistrations",
                    "Ljava/util/concurrent/atomic/AtomicInteger;");
            reg.visitMethodInsn(
                    INVOKEVIRTUAL, "java/util/concurrent/atomic/AtomicInteger", "incrementAndGet", "()I", false);
            reg.visitInsn(POP);
            reg.visitInsn(RETURN);
            reg.visitMaxs(0, 0);
            reg.visitEnd();
            cw.visitEnd();
            return cw.toByteArray();
        }

        private static byte[] emitProgressCompanion() {
            final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(V17, ACC_PUBLIC | ACC_FINAL, "com/live2d/util/a/a$Companion", null, "java/lang/Object", null);
            emitDefaultCtor(cw, "java/lang/Object");
            final MethodVisitor unwrap =
                    cw.visitMethod(ACC_PUBLIC, "a", "(Lcom/live2d/util/a/a;)Lcom/live2d/util/a/a;", null, null);
            unwrap.visitVarInsn(ALOAD, 1);
            unwrap.visitInsn(ARETURN);
            unwrap.visitMaxs(0, 0);
            unwrap.visitEnd();
            cw.visitEnd();
            return cw.toByteArray();
        }

        private static byte[] emitProgress() {
            final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(V17, ACC_PUBLIC, "com/live2d/util/a/a", null, "java/lang/Object", null);
            cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "a", "Lcom/live2d/util/a/a$Companion;", null, null)
                    .visitEnd();
            cw.visitField(ACC_PRIVATE | ACC_FINAL, "canceled", "Z", null, null).visitEnd();

            final MethodVisitor cl = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
            cl.visitTypeInsn(NEW, "com/live2d/util/a/a$Companion");
            cl.visitInsn(DUP);
            cl.visitMethodInsn(INVOKESPECIAL, "com/live2d/util/a/a$Companion", "<init>", "()V", false);
            cl.visitFieldInsn(PUTSTATIC, "com/live2d/util/a/a", "a", "Lcom/live2d/util/a/a$Companion;");
            cl.visitInsn(RETURN);
            cl.visitMaxs(0, 0);
            cl.visitEnd();

            final MethodVisitor ctor = cw.visitMethod(ACC_PUBLIC, "<init>", "(Z)V", null, null);
            ctor.visitVarInsn(ALOAD, 0);
            ctor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            ctor.visitVarInsn(ALOAD, 0);
            ctor.visitVarInsn(ILOAD, 1);
            ctor.visitFieldInsn(PUTFIELD, "com/live2d/util/a/a", "canceled", "Z");
            ctor.visitInsn(RETURN);
            ctor.visitMaxs(0, 0);
            ctor.visitEnd();

            final MethodVisitor start = cw.visitMethod(ACC_PUBLIC, "a", "(ILjava/lang/String;Z)V", null, null);
            start.visitMethodInsn(INVOKESTATIC, "dev/synthetic/Tracker", "onProgress", "()V", false);
            start.visitInsn(RETURN);
            start.visitMaxs(0, 0);
            start.visitEnd();

            final MethodVisitor text = cw.visitMethod(ACC_PUBLIC, "a", "(Ljava/lang/String;)V", null, null);
            text.visitMethodInsn(INVOKESTATIC, "dev/synthetic/Tracker", "onProgress", "()V", false);
            text.visitInsn(RETURN);
            text.visitMaxs(0, 0);
            text.visitEnd();

            final MethodVisitor step = cw.visitMethod(ACC_PUBLIC, "b", "(I)V", null, null);
            step.visitMethodInsn(INVOKESTATIC, "dev/synthetic/Tracker", "onProgress", "()V", false);
            step.visitInsn(RETURN);
            step.visitMaxs(0, 0);
            step.visitEnd();

            final MethodVisitor end = cw.visitMethod(ACC_PUBLIC, "b", "()V", null, null);
            end.visitMethodInsn(INVOKESTATIC, "dev/synthetic/Tracker", "onProgress", "()V", false);
            end.visitInsn(RETURN);
            end.visitMaxs(0, 0);
            end.visitEnd();

            final MethodVisitor sub =
                    cw.visitMethod(ACC_PUBLIC, "a", "(ILkotlin/jvm/functions/Function1;)V", null, null);
            sub.visitMethodInsn(INVOKESTATIC, "dev/synthetic/Tracker", "onProgress", "()V", false);
            sub.visitVarInsn(ALOAD, 2);
            sub.visitVarInsn(ALOAD, 0);
            sub.visitMethodInsn(
                    INVOKEINTERFACE,
                    "kotlin/jvm/functions/Function1",
                    "invoke",
                    "(Ljava/lang/Object;)Ljava/lang/Object;",
                    true);
            sub.visitInsn(POP);
            sub.visitInsn(RETURN);
            sub.visitMaxs(0, 0);
            sub.visitEnd();

            final MethodVisitor isCancel = cw.visitMethod(ACC_PUBLIC, "c", "()Z", null, null);
            isCancel.visitVarInsn(ALOAD, 0);
            isCancel.visitFieldInsn(GETFIELD, "com/live2d/util/a/a", "canceled", "Z");
            isCancel.visitInsn(IRETURN);
            isCancel.visitMaxs(0, 0);
            isCancel.visitEnd();

            cw.visitEnd();
            return cw.toByteArray();
        }

        private static byte[] emitEdgeHelper() {
            final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(V17, ACC_PUBLIC | ACC_FINAL, "com/live2d/util/D", null, "java/lang/Object", null);
            cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "a", "Lcom/live2d/util/D;", null, null)
                    .visitEnd();
            final MethodVisitor cl = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
            cl.visitTypeInsn(NEW, "com/live2d/util/D");
            cl.visitInsn(DUP);
            cl.visitMethodInsn(INVOKESPECIAL, "com/live2d/util/D", "<init>", "()V", false);
            cl.visitFieldInsn(PUTSTATIC, "com/live2d/util/D", "a", "Lcom/live2d/util/D;");
            cl.visitInsn(RETURN);
            cl.visitMaxs(0, 0);
            cl.visitEnd();
            emitDefaultCtor(cw, "java/lang/Object");

            cw.visitMethod(
                            ACC_PUBLIC,
                            "a",
                            "(Lcom/live2d/graphics/CWritableImage;Lcom/live2d/graphics/UiG;"
                                    + "Lcom/live2d/graphics/CWritableImage;IIZ)V",
                            null,
                            null)
                    .visitInsn(RETURN);
            cw.visitMethod(ACC_PUBLIC, "a", "(Ljava/awt/image/BufferedImage;I)V", null, null)
                    .visitInsn(RETURN);
            cw.visitEnd();
            return cw.toByteArray();
        }

        private static byte[] emitModelImage() {
            final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(
                    V17,
                    ACC_PUBLIC,
                    "com/live2d/cubism/doc/model/texture/modelImage/CModelImage",
                    null,
                    "java/lang/Object",
                    null);
            cw.visitField(ACC_PRIVATE | ACC_FINAL, "name", "Ljava/lang/String;", null, null)
                    .visitEnd();
            cw.visitField(ACC_PRIVATE | ACC_FINAL, "res", "Lcom/live2d/graphics/CImageResource;", null, null)
                    .visitEnd();

            final MethodVisitor ctor = cw.visitMethod(
                    ACC_PUBLIC, "<init>", "(Ljava/lang/String;Lcom/live2d/graphics/CWritableImage;)V", null, null);
            ctor.visitVarInsn(ALOAD, 0);
            ctor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            ctor.visitVarInsn(ALOAD, 0);
            ctor.visitVarInsn(ALOAD, 1);
            ctor.visitFieldInsn(
                    PUTFIELD,
                    "com/live2d/cubism/doc/model/texture/modelImage/CModelImage",
                    "name",
                    "Ljava/lang/String;");
            ctor.visitVarInsn(ALOAD, 0);
            ctor.visitTypeInsn(NEW, "com/live2d/graphics/CImageResource");
            ctor.visitInsn(DUP);
            ctor.visitVarInsn(ALOAD, 2);
            ctor.visitInsn(ICONST_0);
            ctor.visitMethodInsn(
                    INVOKESPECIAL,
                    "com/live2d/graphics/CImageResource",
                    "<init>",
                    "(Lcom/live2d/graphics/CWritableImage;Z)V",
                    false);
            ctor.visitFieldInsn(
                    PUTFIELD,
                    "com/live2d/cubism/doc/model/texture/modelImage/CModelImage",
                    "res",
                    "Lcom/live2d/graphics/CImageResource;");
            ctor.visitInsn(RETURN);
            ctor.visitMaxs(0, 0);
            ctor.visitEnd();

            final MethodVisitor gn = cw.visitMethod(ACC_PUBLIC, "getName", "()Ljava/lang/String;", null, null);
            gn.visitVarInsn(ALOAD, 0);
            gn.visitFieldInsn(
                    GETFIELD,
                    "com/live2d/cubism/doc/model/texture/modelImage/CModelImage",
                    "name",
                    "Ljava/lang/String;");
            gn.visitInsn(ARETURN);
            gn.visitMaxs(0, 0);
            gn.visitEnd();

            final MethodVisitor gf = cw.visitMethod(
                    ACC_PUBLIC, "getFilteredImage", "()Lcom/live2d/graphics/CImageResource;", null, null);
            gf.visitMethodInsn(INVOKESTATIC, "dev/synthetic/Tracker", "onPrewarm", "()V", false);
            gf.visitVarInsn(ALOAD, 0);
            gf.visitFieldInsn(
                    GETFIELD,
                    "com/live2d/cubism/doc/model/texture/modelImage/CModelImage",
                    "res",
                    "Lcom/live2d/graphics/CImageResource;");
            gf.visitInsn(ARETURN);
            gf.visitMaxs(0, 0);
            gf.visitEnd();

            cw.visitEnd();
            return cw.toByteArray();
        }

        private static byte[] emitTextureManager() {
            final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(
                    V17,
                    ACC_PUBLIC,
                    "com/live2d/cubism/doc/model/texture/CTextureManager",
                    null,
                    "java/lang/Object",
                    null);
            cw.visitField(ACC_PRIVATE | ACC_FINAL, "map", "Ljava/util/Map;", null, null)
                    .visitEnd();

            final MethodVisitor ctor = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
            ctor.visitVarInsn(ALOAD, 0);
            ctor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            ctor.visitVarInsn(ALOAD, 0);
            ctor.visitTypeInsn(NEW, "java/util/HashMap");
            ctor.visitInsn(DUP);
            ctor.visitMethodInsn(INVOKESPECIAL, "java/util/HashMap", "<init>", "()V", false);
            ctor.visitFieldInsn(
                    PUTFIELD, "com/live2d/cubism/doc/model/texture/CTextureManager", "map", "Ljava/util/Map;");
            ctor.visitInsn(RETURN);
            ctor.visitMaxs(0, 0);
            ctor.visitEnd();

            final MethodVisitor put = cw.visitMethod(
                    ACC_PUBLIC,
                    "put",
                    "(Ljava/lang/String;Lcom/live2d/cubism/doc/model/texture/modelImage/CModelImage;)V",
                    null,
                    null);
            put.visitVarInsn(ALOAD, 0);
            put.visitFieldInsn(
                    GETFIELD, "com/live2d/cubism/doc/model/texture/CTextureManager", "map", "Ljava/util/Map;");
            put.visitVarInsn(ALOAD, 1);
            put.visitVarInsn(ALOAD, 2);
            put.visitMethodInsn(
                    INVOKEINTERFACE,
                    "java/util/Map",
                    "put",
                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                    true);
            put.visitInsn(POP);
            put.visitInsn(RETURN);
            put.visitMaxs(0, 0);
            put.visitEnd();

            final MethodVisitor get = cw.visitMethod(
                    ACC_PUBLIC,
                    "getModelImage",
                    "(Ljava/lang/Object;)Lcom/live2d/cubism/doc/model/texture/modelImage/CModelImage;",
                    null,
                    null);
            get.visitVarInsn(ALOAD, 0);
            get.visitFieldInsn(
                    GETFIELD, "com/live2d/cubism/doc/model/texture/CTextureManager", "map", "Ljava/util/Map;");
            get.visitVarInsn(ALOAD, 1);
            get.visitMethodInsn(
                    INVOKEINTERFACE, "java/util/Map", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", true);
            get.visitTypeInsn(CHECKCAST, "com/live2d/cubism/doc/model/texture/modelImage/CModelImage");
            get.visitInsn(ARETURN);
            get.visitMaxs(0, 0);
            get.visitEnd();

            cw.visitEnd();
            return cw.toByteArray();
        }

        private static byte[] emitModelSource() {
            final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(V17, ACC_PUBLIC, "com/live2d/cubism/doc/model/CModelSource", null, "java/lang/Object", null);
            cw.visitField(
                            ACC_PUBLIC,
                            "textureManager",
                            "Lcom/live2d/cubism/doc/model/texture/CTextureManager;",
                            null,
                            null)
                    .visitEnd();
            emitDefaultCtor(cw, "java/lang/Object");
            final MethodVisitor gtm = cw.visitMethod(
                    ACC_PUBLIC,
                    "getTextureManager",
                    "()Lcom/live2d/cubism/doc/model/texture/CTextureManager;",
                    null,
                    null);
            gtm.visitVarInsn(ALOAD, 0);
            gtm.visitFieldInsn(
                    GETFIELD,
                    "com/live2d/cubism/doc/model/CModelSource",
                    "textureManager",
                    "Lcom/live2d/cubism/doc/model/texture/CTextureManager;");
            gtm.visitInsn(ARETURN);
            gtm.visitMaxs(0, 0);
            gtm.visitEnd();
            cw.visitEnd();
            return cw.toByteArray();
        }

        private static byte[] emitEntry() {
            final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(
                    V17,
                    ACC_PUBLIC,
                    "com/live2d/cubism/doc/model/texture/textureAtlas/CTextureAtlas$ModelImageEntry",
                    null,
                    "java/lang/Object",
                    null);
            cw.visitField(ACC_PRIVATE | ACC_FINAL, "guid", "Ljava/lang/String;", null, null)
                    .visitEnd();

            final MethodVisitor ctor = cw.visitMethod(ACC_PUBLIC, "<init>", "(Ljava/lang/String;)V", null, null);
            ctor.visitVarInsn(ALOAD, 0);
            ctor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            ctor.visitVarInsn(ALOAD, 0);
            ctor.visitVarInsn(ALOAD, 1);
            ctor.visitFieldInsn(
                    PUTFIELD,
                    "com/live2d/cubism/doc/model/texture/textureAtlas/CTextureAtlas$ModelImageEntry",
                    "guid",
                    "Ljava/lang/String;");
            ctor.visitInsn(RETURN);
            ctor.visitMaxs(0, 0);
            ctor.visitEnd();

            final MethodVisitor gg =
                    cw.visitMethod(ACC_PUBLIC, "getModelImageGuid", "()Ljava/lang/Object;", null, null);
            gg.visitVarInsn(ALOAD, 0);
            gg.visitFieldInsn(
                    GETFIELD,
                    "com/live2d/cubism/doc/model/texture/textureAtlas/CTextureAtlas$ModelImageEntry",
                    "guid",
                    "Ljava/lang/String;");
            gg.visitInsn(ARETURN);
            gg.visitMaxs(0, 0);
            gg.visitEnd();

            final MethodVisitor ct = cw.visitMethod(
                    ACC_PUBLIC, "calcModelImageLocalToAtlasTransform", "()Lcom/live2d/type/CAffine;", null, null);
            ct.visitMethodInsn(INVOKESTATIC, "dev/synthetic/Tracker", "onPrewarm", "()V", false);
            ct.visitTypeInsn(NEW, "com/live2d/type/CAffine");
            ct.visitInsn(DUP);
            ct.visitMethodInsn(INVOKESPECIAL, "com/live2d/type/CAffine", "<init>", "()V", false);
            ct.visitInsn(ARETURN);
            ct.visitMaxs(0, 0);
            ct.visitEnd();

            cw.visitEnd();
            return cw.toByteArray();
        }

        private static byte[] emitAtlas() {
            final String internal = AtlasDirectCompositingPatcher.TEXTURE_ATLAS;
            final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(V17, ACC_PUBLIC, internal, null, "java/lang/Object", null);
            cw.visitField(ACC_PUBLIC | ACC_FINAL, "name", "Ljava/lang/String;", null, null)
                    .visitEnd();
            cw.visitField(ACC_PUBLIC | ACC_FINAL, "width", "I", null, null).visitEnd();
            cw.visitField(ACC_PUBLIC | ACC_FINAL, "height", "I", null, null).visitEnd();
            cw.visitField(
                            ACC_PUBLIC | ACC_FINAL,
                            "modelSource",
                            "Lcom/live2d/cubism/doc/model/CModelSource;",
                            null,
                            null)
                    .visitEnd();
            cw.visitField(ACC_PUBLIC | ACC_FINAL, "modelImages", "Ljava/util/List;", null, null)
                    .visitEnd();
            cw.visitField(ACC_PUBLIC, "cachedAtlasImage", "Lcom/live2d/graphics/CImageResource;", null, null)
                    .visitEnd();
            cw.visitField(ACC_PUBLIC, "isDirty_cachedAtlasImage", "Z", null, null)
                    .visitEnd();
            cw.visitField(ACC_PUBLIC, "atlasVersion", "I", null, null).visitEnd();
            cw.visitField(
                            ACC_PUBLIC | ACC_FINAL,
                            "cachedImageManager",
                            "Lcom/live2d/graphics/cachedImage/CCachedImageManager;",
                            null,
                            null)
                    .visitEnd();

            final MethodVisitor ctor = cw.visitMethod(
                    ACC_PUBLIC,
                    "<init>",
                    "(Ljava/lang/String;IILcom/live2d/cubism/doc/model/CModelSource;)V",
                    null,
                    null);
            ctor.visitVarInsn(ALOAD, 0);
            ctor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            ctor.visitVarInsn(ALOAD, 0);
            ctor.visitVarInsn(ALOAD, 1);
            ctor.visitFieldInsn(PUTFIELD, internal, "name", "Ljava/lang/String;");
            ctor.visitVarInsn(ALOAD, 0);
            ctor.visitVarInsn(ILOAD, 2);
            ctor.visitFieldInsn(PUTFIELD, internal, "width", "I");
            ctor.visitVarInsn(ALOAD, 0);
            ctor.visitVarInsn(ILOAD, 3);
            ctor.visitFieldInsn(PUTFIELD, internal, "height", "I");
            ctor.visitVarInsn(ALOAD, 0);
            ctor.visitVarInsn(ALOAD, 4);
            ctor.visitFieldInsn(PUTFIELD, internal, "modelSource", "Lcom/live2d/cubism/doc/model/CModelSource;");
            ctor.visitVarInsn(ALOAD, 0);
            ctor.visitTypeInsn(NEW, "java/util/ArrayList");
            ctor.visitInsn(DUP);
            ctor.visitMethodInsn(INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
            ctor.visitFieldInsn(PUTFIELD, internal, "modelImages", "Ljava/util/List;");
            ctor.visitVarInsn(ALOAD, 0);
            ctor.visitInsn(ICONST_1);
            ctor.visitFieldInsn(PUTFIELD, internal, "isDirty_cachedAtlasImage", "Z");
            ctor.visitVarInsn(ALOAD, 0);
            ctor.visitTypeInsn(NEW, "com/live2d/graphics/cachedImage/CCachedImageManager");
            ctor.visitInsn(DUP);
            ctor.visitMethodInsn(
                    INVOKESPECIAL, "com/live2d/graphics/cachedImage/CCachedImageManager", "<init>", "()V", false);
            ctor.visitFieldInsn(
                    PUTFIELD, internal, "cachedImageManager", "Lcom/live2d/graphics/cachedImage/CCachedImageManager;");
            ctor.visitInsn(RETURN);
            ctor.visitMaxs(0, 0);
            ctor.visitEnd();

            emitGetter(cw, internal, "getName", "name", "Ljava/lang/String;", ARETURN);
            emitGetter(cw, internal, "getWidth", "width", "I", IRETURN);
            emitGetter(cw, internal, "getHeight", "height", "I", IRETURN);
            emitGetter(
                    cw,
                    internal,
                    "getModelSource",
                    "modelSource",
                    "Lcom/live2d/cubism/doc/model/CModelSource;",
                    ARETURN);
            emitGetter(cw, internal, "getModelImages", "modelImages", "Ljava/util/List;", ARETURN);

            final MethodVisitor addE = cw.visitMethod(
                    ACC_PUBLIC,
                    "addEntry",
                    "(Lcom/live2d/cubism/doc/model/texture/textureAtlas/CTextureAtlas$ModelImageEntry;)V",
                    null,
                    null);
            addE.visitVarInsn(ALOAD, 0);
            addE.visitFieldInsn(GETFIELD, internal, "modelImages", "Ljava/util/List;");
            addE.visitVarInsn(ALOAD, 1);
            addE.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true);
            addE.visitInsn(POP);
            addE.visitInsn(RETURN);
            addE.visitMaxs(0, 0);
            addE.visitEnd();

            final MethodVisitor setC = cw.visitMethod(
                    ACC_PUBLIC, "setCachedAtlasImage", "(Lcom/live2d/graphics/CImageResource;)V", null, null);
            setC.visitVarInsn(ALOAD, 0);
            setC.visitVarInsn(ALOAD, 1);
            setC.visitFieldInsn(PUTFIELD, internal, "cachedAtlasImage", "Lcom/live2d/graphics/CImageResource;");
            setC.visitInsn(RETURN);
            setC.visitMaxs(0, 0);
            setC.visitEnd();

            final MethodVisitor setD = cw.visitMethod(ACC_PUBLIC, "setDirty_cachedAtlasImage", "(Z)V", null, null);
            setD.visitVarInsn(ALOAD, 0);
            setD.visitVarInsn(ILOAD, 1);
            setD.visitFieldInsn(PUTFIELD, internal, "isDirty_cachedAtlasImage", "Z");
            setD.visitInsn(RETURN);
            setD.visitMaxs(0, 0);
            setD.visitEnd();

            final MethodVisitor setup =
                    cw.visitMethod(ACC_PUBLIC, "setupCacheImage$cubism", "(ZLcom/live2d/util/a/a;)V", null, null);
            setup.visitVarInsn(ALOAD, 0);
            setup.visitTypeInsn(NEW, "com/live2d/graphics/CImageResource");
            setup.visitInsn(DUP);
            setup.visitTypeInsn(NEW, "com/live2d/graphics/CWritableImage");
            setup.visitInsn(DUP);
            setup.visitInsn(ICONST_4);
            setup.visitInsn(ICONST_4);
            setup.visitInsn(ACONST_NULL);
            setup.visitMethodInsn(
                    INVOKESPECIAL,
                    "com/live2d/graphics/CWritableImage",
                    "<init>",
                    "(IILcom/live2d/graphics/n;)V",
                    false);
            setup.visitInsn(ACONST_NULL);
            setup.visitInsn(ICONST_0);
            setup.visitIntInsn(BIPUSH, 6);
            setup.visitInsn(ACONST_NULL);
            setup.visitMethodInsn(
                    INVOKESPECIAL,
                    "com/live2d/graphics/CImageResource",
                    "<init>",
                    "(Lcom/live2d/graphics/CWritableImage;Lcom/live2d/graphics/n;ZILkotlin/jvm/internal/DefaultConstructorMarker;)V",
                    false);
            setup.visitMethodInsn(
                    INVOKEVIRTUAL, internal, "setCachedAtlasImage", "(Lcom/live2d/graphics/CImageResource;)V", false);
            setup.visitVarInsn(ALOAD, 0);
            setup.visitInsn(ICONST_0);
            setup.visitMethodInsn(INVOKEVIRTUAL, internal, "setDirty_cachedAtlasImage", "(Z)V", false);
            setup.visitInsn(RETURN);
            setup.visitMaxs(0, 0);
            setup.visitEnd();

            final MethodVisitor upd =
                    cw.visitMethod(ACC_PUBLIC, "updateTexture", "(ZZLcom/live2d/util/a/a;)V", null, null);
            upd.visitVarInsn(ILOAD, 1);
            final org.objectweb.asm.Label skip = new org.objectweb.asm.Label();
            upd.visitJumpInsn(IFEQ, skip);
            upd.visitVarInsn(ALOAD, 0);
            upd.visitVarInsn(ILOAD, 2);
            upd.visitVarInsn(ALOAD, 3);
            upd.visitMethodInsn(INVOKEVIRTUAL, internal, "setupCacheImage$cubism", "(ZLcom/live2d/util/a/a;)V", false);
            upd.visitLabel(skip);
            upd.visitFrame(F_SAME, 0, null, 0, null);
            upd.visitVarInsn(ALOAD, 0);
            upd.visitFieldInsn(
                    GETFIELD, internal, "cachedImageManager", "Lcom/live2d/graphics/cachedImage/CCachedImageManager;");
            upd.visitVarInsn(ALOAD, 0);
            upd.visitFieldInsn(GETFIELD, internal, "cachedAtlasImage", "Lcom/live2d/graphics/CImageResource;");
            upd.visitMethodInsn(
                    INVOKEVIRTUAL,
                    "com/live2d/graphics/cachedImage/CCachedImageManager",
                    "a",
                    "(Lcom/live2d/graphics/CImageResource;)V",
                    false);
            upd.visitInsn(RETURN);
            upd.visitMaxs(0, 0);
            upd.visitEnd();

            cw.visitEnd();
            return cw.toByteArray();
        }

        private static byte[] emitH() {
            final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(V17, ACC_PUBLIC, "com/live2d/cubism/doc/model/texture/H", null, "java/lang/Object", null);
            emitDefaultCtor(cw, "java/lang/Object");
            cw.visitEnd();
            return cw.toByteArray();
        }

        private static byte[] emitG() {
            final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(
                    V17,
                    ACC_PUBLIC | ACC_FINAL,
                    AtlasDirectCompositingPatcher.TEXTURE_UPDATE_LOOP,
                    null,
                    "java/lang/Object",
                    null);
            cw.visitField(ACC_PUBLIC | ACC_FINAL, "a", "Ljava/util/List;", null, null)
                    .visitEnd();

            final MethodVisitor ctor = cw.visitMethod(ACC_PUBLIC, "<init>", "(Ljava/util/List;)V", null, null);
            ctor.visitVarInsn(ALOAD, 0);
            ctor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            ctor.visitVarInsn(ALOAD, 0);
            ctor.visitVarInsn(ALOAD, 1);
            ctor.visitFieldInsn(PUTFIELD, AtlasDirectCompositingPatcher.TEXTURE_UPDATE_LOOP, "a", "Ljava/util/List;");
            ctor.visitInsn(RETURN);
            ctor.visitMaxs(0, 0);
            ctor.visitEnd();

            // Stock fallback anchor mentions NEW H + has a(progress) signature.
            final MethodVisitor run =
                    cw.visitMethod(ACC_PUBLIC | ACC_FINAL, "a", "(Lcom/live2d/util/a/a;)V", null, null);
            run.visitTypeInsn(NEW, "com/live2d/cubism/doc/model/texture/H");
            run.visitInsn(DUP);
            run.visitMethodInsn(INVOKESPECIAL, "com/live2d/cubism/doc/model/texture/H", "<init>", "()V", false);
            run.visitInsn(POP);
            run.visitInsn(RETURN);
            run.visitMaxs(0, 0);
            run.visitEnd();

            cw.visitEnd();
            return cw.toByteArray();
        }

        private static byte[] emitTaeModel() {
            final String internal = "com/live2d/cubism/doc/modeling/ui/atlasEditor/impl/TAE_DataModel";
            final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(V17, ACC_PUBLIC, internal, null, "java/lang/Object", null);
            cw.visitField(ACC_PUBLIC | ACC_FINAL, "atlases", "Ljava/util/List;", null, null)
                    .visitEnd();
            cw.visitField(ACC_PUBLIC | ACC_FINAL, "edgeFill", "Z", null, null).visitEnd();

            final MethodVisitor ctor = cw.visitMethod(ACC_PUBLIC, "<init>", "(Ljava/util/List;Z)V", null, null);
            ctor.visitVarInsn(ALOAD, 0);
            ctor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            ctor.visitVarInsn(ALOAD, 0);
            ctor.visitVarInsn(ALOAD, 1);
            ctor.visitFieldInsn(PUTFIELD, internal, "atlases", "Ljava/util/List;");
            ctor.visitVarInsn(ALOAD, 0);
            ctor.visitVarInsn(ILOAD, 2);
            ctor.visitFieldInsn(PUTFIELD, internal, "edgeFill", "Z");
            ctor.visitInsn(RETURN);
            ctor.visitMaxs(0, 0);
            ctor.visitEnd();

            emitGetter(cw, internal, "g", "edgeFill", "Z", IRETURN);
            cw.visitEnd();
            return cw.toByteArray();
        }

        private static byte[] emitConfirmTask() {
            final String internal = "com/live2d/cubism/doc/modeling/ui/atlasEditor/impl/d";
            final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(V17, ACC_PUBLIC | ACC_FINAL, internal, null, "java/lang/Object", new String[] {
                "kotlin/jvm/functions/Function1"
            });
            cw.visitField(
                            ACC_FINAL,
                            "a",
                            "Lcom/live2d/cubism/doc/model/texture/textureAtlas/CTextureAtlas;",
                            null,
                            null)
                    .visitEnd();
            cw.visitField(
                            ACC_FINAL,
                            "b",
                            "Lcom/live2d/cubism/doc/modeling/ui/atlasEditor/impl/TAE_DataModel;",
                            null,
                            null)
                    .visitEnd();
            cw.visitField(ACC_FINAL, "c", "Lcom/live2d/util/a/a;", null, null).visitEnd();

            final MethodVisitor ctor = cw.visitMethod(
                    ACC_PUBLIC,
                    "<init>",
                    "(Lcom/live2d/cubism/doc/model/texture/textureAtlas/CTextureAtlas;"
                            + "Lcom/live2d/cubism/doc/modeling/ui/atlasEditor/impl/TAE_DataModel;"
                            + "Lcom/live2d/util/a/a;)V",
                    null,
                    null);
            ctor.visitVarInsn(ALOAD, 0);
            ctor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            ctor.visitVarInsn(ALOAD, 0);
            ctor.visitVarInsn(ALOAD, 1);
            ctor.visitFieldInsn(
                    PUTFIELD, internal, "a", "Lcom/live2d/cubism/doc/model/texture/textureAtlas/CTextureAtlas;");
            ctor.visitVarInsn(ALOAD, 0);
            ctor.visitVarInsn(ALOAD, 2);
            ctor.visitFieldInsn(
                    PUTFIELD, internal, "b", "Lcom/live2d/cubism/doc/modeling/ui/atlasEditor/impl/TAE_DataModel;");
            ctor.visitVarInsn(ALOAD, 0);
            ctor.visitVarInsn(ALOAD, 3);
            ctor.visitFieldInsn(PUTFIELD, internal, "c", "Lcom/live2d/util/a/a;");
            ctor.visitInsn(RETURN);
            ctor.visitMaxs(0, 0);
            ctor.visitEnd();

            final MethodVisitor invoke =
                    cw.visitMethod(ACC_PUBLIC, "invoke", "(Ljava/lang/Object;)Ljava/lang/Object;", null, null);
            invoke.visitVarInsn(ALOAD, 0);
            invoke.visitFieldInsn(
                    GETFIELD, internal, "a", "Lcom/live2d/cubism/doc/model/texture/textureAtlas/CTextureAtlas;");
            invoke.visitInsn(ICONST_1);
            invoke.visitVarInsn(ALOAD, 0);
            invoke.visitFieldInsn(
                    GETFIELD, internal, "b", "Lcom/live2d/cubism/doc/modeling/ui/atlasEditor/impl/TAE_DataModel;");
            invoke.visitMethodInsn(
                    INVOKEVIRTUAL,
                    "com/live2d/cubism/doc/modeling/ui/atlasEditor/impl/TAE_DataModel",
                    "g",
                    "()Z",
                    false);
            invoke.visitVarInsn(ALOAD, 0);
            invoke.visitFieldInsn(GETFIELD, internal, "c", "Lcom/live2d/util/a/a;");
            invoke.visitMethodInsn(
                    INVOKEVIRTUAL,
                    "com/live2d/cubism/doc/model/texture/textureAtlas/CTextureAtlas",
                    "updateTexture",
                    "(ZZLcom/live2d/util/a/a;)V",
                    false);
            invoke.visitFieldInsn(GETSTATIC, "kotlin/Unit", "INSTANCE", "Lkotlin/Unit;");
            invoke.visitInsn(ARETURN);
            invoke.visitMaxs(0, 0);
            invoke.visitEnd();

            cw.visitEnd();
            return cw.toByteArray();
        }

        private static byte[] emitConfirmLoop() {
            final String internal = AtlasDirectCompositingPatcher.CONFIRM_LOOP;
            final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(V17, ACC_PUBLIC | ACC_FINAL, internal, null, "java/lang/Object", null);
            cw.visitField(
                            ACC_FINAL,
                            "a",
                            "Lcom/live2d/cubism/doc/modeling/ui/atlasEditor/impl/TAE_DataModel;",
                            null,
                            null)
                    .visitEnd();

            final MethodVisitor ctor = cw.visitMethod(
                    ACC_PUBLIC,
                    "<init>",
                    "(Lcom/live2d/cubism/doc/modeling/ui/atlasEditor/impl/TAE_DataModel;)V",
                    null,
                    null);
            ctor.visitVarInsn(ALOAD, 0);
            ctor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            ctor.visitVarInsn(ALOAD, 0);
            ctor.visitVarInsn(ALOAD, 1);
            ctor.visitFieldInsn(
                    PUTFIELD, internal, "a", "Lcom/live2d/cubism/doc/modeling/ui/atlasEditor/impl/TAE_DataModel;");
            ctor.visitInsn(RETURN);
            ctor.visitMaxs(0, 0);
            ctor.visitEnd();

            final MethodVisitor run =
                    cw.visitMethod(ACC_PUBLIC | ACC_FINAL, "a", "(Lcom/live2d/util/a/a;)V", null, null);
            run.visitVarInsn(ALOAD, 0);
            run.visitFieldInsn(
                    GETFIELD, internal, "a", "Lcom/live2d/cubism/doc/modeling/ui/atlasEditor/impl/TAE_DataModel;");
            run.visitFieldInsn(
                    GETFIELD,
                    "com/live2d/cubism/doc/modeling/ui/atlasEditor/impl/TAE_DataModel",
                    "atlases",
                    "Ljava/util/List;");
            run.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "iterator", "()Ljava/util/Iterator;", true);
            run.visitVarInsn(ASTORE, 2);
            final org.objectweb.asm.Label loop = new org.objectweb.asm.Label();
            final org.objectweb.asm.Label end = new org.objectweb.asm.Label();
            run.visitLabel(loop);
            run.visitFrame(F_APPEND, 1, new Object[] {"java/util/Iterator"}, 0, null);
            run.visitVarInsn(ALOAD, 2);
            run.visitMethodInsn(INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);
            run.visitJumpInsn(IFEQ, end);
            run.visitVarInsn(ALOAD, 2);
            run.visitMethodInsn(INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true);
            run.visitTypeInsn(CHECKCAST, "com/live2d/cubism/doc/model/texture/textureAtlas/CTextureAtlas");
            run.visitVarInsn(ASTORE, 3);
            run.visitVarInsn(ALOAD, 1);
            run.visitInsn(ICONST_1);
            run.visitTypeInsn(NEW, "com/live2d/cubism/doc/modeling/ui/atlasEditor/impl/d");
            run.visitInsn(DUP);
            run.visitVarInsn(ALOAD, 3);
            run.visitVarInsn(ALOAD, 0);
            run.visitFieldInsn(
                    GETFIELD, internal, "a", "Lcom/live2d/cubism/doc/modeling/ui/atlasEditor/impl/TAE_DataModel;");
            run.visitVarInsn(ALOAD, 1);
            run.visitMethodInsn(
                    INVOKESPECIAL,
                    "com/live2d/cubism/doc/modeling/ui/atlasEditor/impl/d",
                    "<init>",
                    "(Lcom/live2d/cubism/doc/model/texture/textureAtlas/CTextureAtlas;"
                            + "Lcom/live2d/cubism/doc/modeling/ui/atlasEditor/impl/TAE_DataModel;"
                            + "Lcom/live2d/util/a/a;)V",
                    false);
            run.visitMethodInsn(
                    INVOKEVIRTUAL, "com/live2d/util/a/a", "a", "(ILkotlin/jvm/functions/Function1;)V", false);
            run.visitJumpInsn(GOTO, loop);
            run.visitLabel(end);
            run.visitFrame(F_CHOP, 1, null, 0, null);
            run.visitInsn(RETURN);
            run.visitMaxs(0, 0);
            run.visitEnd();

            cw.visitEnd();
            return cw.toByteArray();
        }

        private static void emitDefaultCtor(final ClassWriter cw, final String superInternal) {
            final MethodVisitor ctor = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
            ctor.visitVarInsn(ALOAD, 0);
            ctor.visitMethodInsn(INVOKESPECIAL, superInternal, "<init>", "()V", false);
            ctor.visitInsn(RETURN);
            ctor.visitMaxs(0, 0);
            ctor.visitEnd();
        }

        private static void emitGetter(
                final ClassWriter cw,
                final String owner,
                final String methodName,
                final String fieldName,
                final String fieldDesc,
                final int retOpcode) {
            final MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, methodName, "()" + fieldDesc, null, null);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, owner, fieldName, fieldDesc);
            mv.visitInsn(retOpcode);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
    }

    private static final class PatchedSyntheticLoader extends ClassLoader {
        private final Map<String, byte[]> classes;

        PatchedSyntheticLoader(final Map<String, byte[]> classes) {
            super(AtlasPageRebuildCoordinatorTest.class.getClassLoader());
            this.classes = classes;
        }

        @Override
        protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
            final Class<?> loaded = findLoadedClass(name);
            if (loaded != null) {
                return loaded;
            }
            final String internal = name.replace('.', '/');
            final byte[] bytes = classes.get(internal);
            if (bytes != null) {
                final Class<?> defined = defineClass(name, bytes, 0, bytes.length);
                if (resolve) {
                    resolveClass(defined);
                }
                return defined;
            }
            return super.loadClass(name, resolve);
        }
    }
}
