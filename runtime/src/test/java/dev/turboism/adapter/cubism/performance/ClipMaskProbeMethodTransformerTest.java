package dev.turboism.adapter.cubism.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.objectweb.asm.Opcodes.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.Permissions;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

/**
 * Verifies the measurement-only clipping-mask probe end to end on synthetic classes
 * carrying the exact reviewed owners, descriptors and call ordering.
 *
 * <p>The synthetic mask dispatch mirrors the reviewed body: an early return before any
 * mask work, then the trimmed mask-set accessor, a pooled render-texture bind, and a
 * second return path. The probe must count exactly one pass per accessor call, classify
 * the bind as a mask attachment, time only the dispatches that reached the mask work,
 * and leave the host observable behaviour untouched.</p>
 */
class ClipMaskProbeMethodTransformerTest {

    private static final String RENDER_SYSTEM = "com/live2d/graphics3d/rendering/e";
    private static final String CLIPPING = "com/live2d/graphics3d/component/GClipping";
    private static final String REMOVABLE_CLIPPING = "com/live2d/graphics3d/component/GRemovableClipping";
    private static final String TEXTURE = "com/live2d/graphics3d/texture/GRenderTexture";
    private static final String GROUP = "com/live2d/graphics3d/component/GGroupRenderer";
    private static final String VIEW_CONTEXT = "com/live2d/cubism/view/context/CEViewContext";

    private Path artifact;
    private ProbeLoader loader;
    private ClipMaskProbeBridge bridge;

    /** Host-loader stand-in that pins a code source the transformer can attest. */
    private static final class ProbeLoader extends ClassLoader {
        private final ProtectionDomain domain;

        ProbeLoader(final Path artifact) throws Exception {
            super(ClipMaskProbeMethodTransformerTest.class.getClassLoader());
            domain = new ProtectionDomain(
                    new CodeSource(artifact.toUri().toURL(), (java.security.CodeSigner[]) null), new Permissions());
        }

        Class<?> define(final String name, final byte[] bytes) {
            return defineClass(name.replace('/', '.'), bytes, 0, bytes.length, domain);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        artifact = Files.createTempFile("cubism-clip-mask-probe", ".jar");
        loader = new ProbeLoader(artifact);
    }

    @AfterEach
    void tearDown() {
        if (bridge != null) bridge.close();
        bridge = null;
        final Properties properties = System.getProperties();
        for (String key : List.of(
                ClipMaskProbeBridge.FRAME_PROPERTY,
                ClipMaskProbeBridge.MASK_DISPATCH_ENTER_PROPERTY,
                ClipMaskProbeBridge.MASK_DISPATCH_EXIT_PROPERTY,
                ClipMaskProbeBridge.MASK_SET_PROPERTY,
                ClipMaskProbeBridge.BIND_PROPERTY,
                ClipMaskProbeBridge.GROUP_ENTER_PROPERTY,
                ClipMaskProbeBridge.GROUP_EXIT_PROPERTY,
                ClipMaskProbeBridge.STATS_PROPERTY)) {
            properties.remove(key);
        }
    }

    @Test
    void countsMaskPassesDistinctSetsAndAttachmentTexels() throws Exception {
        final Probe probe = install(false);
        final Object firstMask = new Object();
        final Object secondMask = new Object();
        final Object texture = probe.texture(2100, 1200);

        // Frame 1: two passes over the same mask set, then one over a different set.
        probe.frame();
        probe.dispatch(probe.clipping(List.of(firstMask, secondMask)), texture);
        probe.dispatch(probe.clipping(List.of(firstMask, secondMask)), texture);
        probe.dispatch(probe.clipping(List.of(secondMask)), texture);
        // Frame 2: the same set again is a new frame, so it counts as distinct again.
        probe.frame();
        probe.dispatch(probe.clipping(List.of(firstMask, secondMask)), texture);

        final Map<String, Long> stats = bridge.snapshot();
        assertEquals(2L, counter(stats, "frames"));
        assertEquals(4L, counter(stats, "maskPasses"));
        assertEquals(4L, counter(stats, "maskDispatches"));
        assertEquals(7L, counter(stats, "maskEntities"));
        assertEquals(3L, counter(stats, "distinctMaskSets"));
        assertEquals(1L, counter(stats, "dedupeSavings"));
        assertEquals(3L, counter(stats, "maskPassesPerFramePeak"));
        assertEquals(4L, counter(stats, "maskFboBinds"));
        assertEquals(4L * 2100L * 1200L, counter(stats, "maskFboPixels"));
        assertEquals(0L, counter(stats, "observerFailures"));
    }

    @Test
    void timesOnlyDispatchesThatReachedTheMaskWork() throws Exception {
        final Probe probe = install(false);
        final Object texture = probe.texture(64, 64);
        probe.frame();

        // The early return happens before any mask work, so it must not add mask time.
        probe.dispatchWithoutClipping();
        assertEquals(0L, counter(bridge.snapshot(), "maskPathNanos"));
        assertEquals(1L, counter(bridge.snapshot(), "maskDispatches"));
        assertEquals(0L, counter(bridge.snapshot(), "maskPasses"));

        probe.dispatch(probe.clipping(List.of(new Object())), texture);
        assertTrue(counter(bridge.snapshot(), "maskPathNanos") > 0L);
        assertEquals(1L, counter(bridge.snapshot(), "maskPasses"));
    }

    @Test
    void classifiesGroupAttachmentSeparatelyFromMaskAttachment() throws Exception {
        final Probe probe = install(false);
        final Object texture = probe.texture(1400, 800);
        probe.frame();
        probe.groupRender(texture);

        final Map<String, Long> stats = bridge.snapshot();
        assertEquals(1L, counter(stats, "groupPasses"));
        assertEquals(1L, counter(stats, "groupFboBinds"));
        assertEquals(1400L * 800L, counter(stats, "groupFboPixels"));
        assertEquals(0L, counter(stats, "maskFboBinds"));
        assertEquals(0L, counter(stats, "maskPasses"));
        assertTrue(counter(stats, "groupPathNanos") >= 0L);
    }

    @Test
    void leavesTheClassUntouchedWhenASelectorShapeDrifted() throws Exception {
        final Map<String, byte[]> references = referenceBytes();
        final ClipMaskProbeMethodTransformer transformer = transformer(references);
        final byte[] drifted = renderSystem(true);
        assertNull(transformer.transform(
                null,
                loader,
                RENDER_SYSTEM,
                null,
                new ProtectionDomain(
                        new CodeSource(artifact.toUri().toURL(), (java.security.CodeSigner[]) null), new Permissions()),
                drifted));
        assertNotNull(transformer.failure());
        assertTrue(transformer.failure().contains("selector shape changed"));
        assertEquals(0, transformer.sites());
    }

    @Test
    void refusesAReplicaFromAnotherArtifact() throws Exception {
        final Map<String, byte[]> references = referenceBytes();
        final ClipMaskProbeMethodTransformer transformer = transformer(references);
        final Path other = Files.createTempFile("other-artifact", ".jar");
        assertNull(transformer.transform(
                null,
                loader,
                RENDER_SYSTEM,
                null,
                new ProtectionDomain(
                        new CodeSource(other.toUri().toURL(), (java.security.CodeSigner[]) null), new Permissions()),
                renderSystem(false)));
        assertNotNull(transformer.failure());
        assertTrue(transformer.failure().contains("source mismatch"));
    }

    @Test
    void measuresNothingWhenTheSlotsAreAbsent() throws Exception {
        final Probe probe = install(true);
        final Object texture = probe.texture(32, 32);
        probe.frame();
        probe.dispatch(probe.clipping(List.of(new Object())), texture);
        // A refused installation leaves the host methods byte-identical and the
        // counters empty; the dispatch still ran (behaviour is never changed).
        assertEquals(1, probe.renderSystemDispatches());
        assertEquals(0L, probe.dispatchCount());
    }

    private static long counter(final Map<String, Long> stats, final String key) {
        final Long value = stats.get(key);
        assertNotNull(value, "missing counter " + key);
        return value;
    }

    private ClipMaskProbeMethodTransformer transformer(final Map<String, byte[]> references) {
        return new ClipMaskProbeMethodTransformer(
                loader, artifact, references, ClipMaskProbeTargets.forVersion("5.3.04"));
    }

    private Map<String, byte[]> referenceBytes() {
        // Definition order matters: GRemovableClipping extends GClipping.
        final Map<String, byte[]> references = new java.util.LinkedHashMap<>();
        references.put(CLIPPING, clippingClass());
        references.put(REMOVABLE_CLIPPING, removableClippingClass());
        references.put(TEXTURE, textureClass());
        references.put(RENDER_SYSTEM, renderSystem(false));
        references.put(GROUP, groupClass());
        references.put(VIEW_CONTEXT, viewContextClass());
        references.put(
                "com/live2d/graphics3d/component/AGRenderer", plainClass("com/live2d/graphics3d/component/AGRenderer"));
        references.put("com/live2d/graphics3d/a", plainClass("com/live2d/graphics3d/a"));
        references.put("com/live2d/type/CRect", plainClass("com/live2d/type/CRect"));
        return references;
    }

    /** Installs a probe over a synthetic host and returns its driver. */
    private Probe install(final boolean withoutSlots) throws Exception {
        final Map<String, byte[]> references = referenceBytes();
        final ClipMaskProbeMethodTransformer transformer = transformer(references);
        final Map<String, Class<?>> types = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : references.entrySet()) {
            final byte[] transformed = transformer.transform(
                    null,
                    loader,
                    entry.getKey(),
                    null,
                    new ProtectionDomain(
                            new CodeSource(artifact.toUri().toURL(), (java.security.CodeSigner[]) null),
                            new Permissions()),
                    entry.getValue());
            types.put(
                    entry.getKey(),
                    loader.define(entry.getKey(), transformed == null ? entry.getValue() : transformed));
        }
        if (withoutSlots) {
            return new Probe(types);
        }
        bridge = new ClipMaskProbeBridge(loader);
        bridge.install();
        return new Probe(types);
    }

    /** Reflective driver over the synthetic host. */
    private static final class Probe {
        private final Map<String, Class<?>> types;

        Probe(final Map<String, Class<?>> types) {
            this.types = types;
        }

        Object clipping(final List<Object> masks) throws Exception {
            final Object value = types.get(CLIPPING).getConstructor().newInstance();
            value.getClass().getField("masks").set(value, masks);
            return value;
        }

        Object texture(final int width, final int height) throws Exception {
            final Object value = types.get(TEXTURE).getConstructor().newInstance();
            value.getClass().getField("width").setInt(value, width);
            value.getClass().getField("height").setInt(value, height);
            return value;
        }

        void frame() throws Exception {
            types.get(VIEW_CONTEXT)
                    .getDeclaredMethod(
                            "renderScene_exe",
                            types.get("com/live2d/graphics3d/a"),
                            types.get("com/live2d/type/CRect"),
                            types.get("com/live2d/type/CRect"))
                    .invoke(types.get(VIEW_CONTEXT).getConstructor().newInstance(), null, null, null);
        }

        void dispatch(final Object clipping, final Object texture) throws Exception {
            types.get(RENDER_SYSTEM).getField("holder").set(null, clipping);
            types.get(RENDER_SYSTEM).getField("texture").set(null, texture);
            final java.lang.reflect.Method mask = types.get(RENDER_SYSTEM)
                    .getDeclaredMethod(
                            "a",
                            types.get("com/live2d/graphics3d/component/AGRenderer"),
                            types.get("com/live2d/graphics3d/a"),
                            types.get("com/live2d/type/CRect"));
            mask.setAccessible(true);
            mask.invoke(
                    types.get(RENDER_SYSTEM).getConstructor().newInstance(),
                    null,
                    null,
                    types.get("com/live2d/type/CRect").getConstructor().newInstance());
        }

        void dispatchWithoutClipping() throws Exception {
            final java.lang.reflect.Method mask = types.get(RENDER_SYSTEM)
                    .getDeclaredMethod(
                            "a",
                            types.get("com/live2d/graphics3d/component/AGRenderer"),
                            types.get("com/live2d/graphics3d/a"),
                            types.get("com/live2d/type/CRect"));
            mask.setAccessible(true);
            mask.invoke(types.get(RENDER_SYSTEM).getConstructor().newInstance(), null, null, null);
        }

        void groupRender(final Object texture) throws Exception {
            types.get(GROUP).getField("texture").set(null, texture);
            types.get(GROUP)
                    .getMethod("render", types.get("com/live2d/graphics3d/a"))
                    .invoke(
                            types.get(GROUP).getConstructor().newInstance(),
                            types.get("com/live2d/graphics3d/a")
                                    .getConstructor()
                                    .newInstance());
        }

        int renderSystemDispatches() throws Exception {
            return types.get(RENDER_SYSTEM).getField("dispatches").getInt(null);
        }

        long dispatchCount() {
            return System.getProperties().containsKey(ClipMaskProbeBridge.MASK_DISPATCH_ENTER_PROPERTY) ? 1L : 0L;
        }
    }

    // ------------------------------------------------------------------ fixtures

    private static byte[] plainClass(final String name) {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC, name, null, "java/lang/Object", null);
        final MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] clippingClass() {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC, CLIPPING, null, "java/lang/Object", null);
        writer.visitField(ACC_PUBLIC, "masks", "Ljava/util/List;", null, null).visitEnd();
        final MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        final MethodVisitor masks =
                writer.visitMethod(ACC_PUBLIC, "getClippingMasks", "()Ljava/util/List;", null, null);
        masks.visitCode();
        masks.visitVarInsn(ALOAD, 0);
        masks.visitFieldInsn(GETFIELD, CLIPPING, "masks", "Ljava/util/List;");
        masks.visitInsn(ARETURN);
        masks.visitMaxs(0, 0);
        masks.visitEnd();
        final MethodVisitor valid =
                writer.visitMethod(ACC_PUBLIC, "getClippingVaildMasks", "()Ljava/util/List;", null, null);
        valid.visitCode();
        valid.visitVarInsn(ALOAD, 0);
        valid.visitMethodInsn(INVOKEVIRTUAL, CLIPPING, "getClippingMasks", "()Ljava/util/List;", false);
        valid.visitInsn(ARETURN);
        valid.visitMaxs(0, 0);
        valid.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] removableClippingClass() {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC, REMOVABLE_CLIPPING, null, CLIPPING, null);
        final MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, CLIPPING, "<init>", "()V", false);
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        final MethodVisitor valid =
                writer.visitMethod(ACC_PUBLIC, "getClippingVaildMasks", "()Ljava/util/List;", null, null);
        valid.visitCode();
        valid.visitVarInsn(ALOAD, 0);
        valid.visitMethodInsn(INVOKEVIRTUAL, CLIPPING, "getClippingMasks", "()Ljava/util/List;", false);
        valid.visitInsn(ARETURN);
        valid.visitMaxs(0, 0);
        valid.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] textureClass() {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC, TEXTURE, null, "java/lang/Object", null);
        writer.visitField(ACC_PUBLIC, "width", "I", null, null).visitEnd();
        writer.visitField(ACC_PUBLIC, "height", "I", null, null).visitEnd();
        writer.visitField(ACC_PUBLIC, "binds", "I", null, null).visitEnd();
        final MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        for (String name : List.of("getWidth", "getHeight")) {
            final MethodVisitor accessor = writer.visitMethod(ACC_PUBLIC, name, "()I", null, null);
            accessor.visitCode();
            accessor.visitVarInsn(ALOAD, 0);
            accessor.visitFieldInsn(GETFIELD, TEXTURE, name.equals("getWidth") ? "width" : "height", "I");
            accessor.visitInsn(IRETURN);
            accessor.visitMaxs(0, 0);
            accessor.visitEnd();
        }
        final MethodVisitor bind = writer.visitMethod(ACC_PUBLIC, "bind", "(Lcom/live2d/graphics3d/a;)V", null, null);
        bind.visitCode();
        bind.visitVarInsn(ALOAD, 0);
        bind.visitInsn(DUP);
        bind.visitFieldInsn(GETFIELD, TEXTURE, "binds", "I");
        bind.visitInsn(ICONST_1);
        bind.visitInsn(IADD);
        bind.visitFieldInsn(PUTFIELD, TEXTURE, "binds", "I");
        bind.visitInsn(RETURN);
        bind.visitMaxs(0, 0);
        bind.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /**
     * Synthetic mask dispatch with the reviewed ordering: an early return before any
     * mask work, the trimmed mask-set accessor, one pooled attachment bind, and a
     * second return when the set is empty.
     */
    private static byte[] renderSystem(final boolean drift) {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC, RENDER_SYSTEM, null, "java/lang/Object", null);
        writer.visitField(ACC_PUBLIC | ACC_STATIC, "holder", "Ljava/lang/Object;", null, null)
                .visitEnd();
        writer.visitField(ACC_PUBLIC | ACC_STATIC, "texture", "Ljava/lang/Object;", null, null)
                .visitEnd();
        writer.visitField(ACC_PUBLIC | ACC_STATIC, "dispatches", "I", null, null)
                .visitEnd();
        MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        final MethodVisitor dispatch = writer.visitMethod(
                ACC_PRIVATE,
                "a",
                "(Lcom/live2d/graphics3d/component/AGRenderer;Lcom/live2d/graphics3d/a;Lcom/live2d/type/CRect;)V",
                null,
                null);
        dispatch.visitCode();
        if (drift) dispatch.visitInsn(NOP);
        final org.objectweb.asm.Label early = new org.objectweb.asm.Label();
        final org.objectweb.asm.Label emptySet = new org.objectweb.asm.Label();
        dispatch.visitVarInsn(ALOAD, 3);
        dispatch.visitJumpInsn(IFNULL, early);
        dispatch.visitFieldInsn(GETSTATIC, RENDER_SYSTEM, "dispatches", "I");
        dispatch.visitInsn(ICONST_1);
        dispatch.visitInsn(IADD);
        dispatch.visitFieldInsn(PUTSTATIC, RENDER_SYSTEM, "dispatches", "I");
        dispatch.visitFieldInsn(GETSTATIC, RENDER_SYSTEM, "holder", "Ljava/lang/Object;");
        dispatch.visitTypeInsn(CHECKCAST, CLIPPING);
        dispatch.visitMethodInsn(INVOKEVIRTUAL, CLIPPING, "getClippingVaildMasks", "()Ljava/util/List;", false);
        dispatch.visitVarInsn(ASTORE, 4);
        dispatch.visitFieldInsn(GETSTATIC, RENDER_SYSTEM, "texture", "Ljava/lang/Object;");
        dispatch.visitTypeInsn(CHECKCAST, TEXTURE);
        dispatch.visitVarInsn(ALOAD, 2);
        dispatch.visitMethodInsn(INVOKEVIRTUAL, TEXTURE, "bind", "(Lcom/live2d/graphics3d/a;)V", false);
        dispatch.visitVarInsn(ALOAD, 4);
        dispatch.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "isEmpty", "()Z", true);
        dispatch.visitJumpInsn(IFNE, emptySet);
        dispatch.visitInsn(RETURN);
        dispatch.visitLabel(emptySet);
        dispatch.visitInsn(RETURN);
        dispatch.visitLabel(early);
        dispatch.visitInsn(RETURN);
        dispatch.visitMaxs(0, 0);
        dispatch.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] groupClass() {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC, GROUP, null, "java/lang/Object", null);
        writer.visitField(ACC_PUBLIC | ACC_STATIC, "texture", "Ljava/lang/Object;", null, null)
                .visitEnd();
        final MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        final MethodVisitor render =
                writer.visitMethod(ACC_PUBLIC, "render", "(Lcom/live2d/graphics3d/a;)V", null, null);
        render.visitCode();
        final org.objectweb.asm.Label early = new org.objectweb.asm.Label();
        render.visitVarInsn(ALOAD, 1);
        render.visitJumpInsn(IFNULL, early);
        render.visitFieldInsn(GETSTATIC, GROUP, "texture", "Ljava/lang/Object;");
        render.visitTypeInsn(CHECKCAST, TEXTURE);
        render.visitVarInsn(ALOAD, 1);
        render.visitMethodInsn(INVOKEVIRTUAL, TEXTURE, "bind", "(Lcom/live2d/graphics3d/a;)V", false);
        render.visitInsn(RETURN);
        render.visitLabel(early);
        render.visitInsn(RETURN);
        render.visitMaxs(0, 0);
        render.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] viewContextClass() {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC, VIEW_CONTEXT, null, "java/lang/Object", null);
        final MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        final MethodVisitor frame = writer.visitMethod(
                ACC_PUBLIC,
                "renderScene_exe",
                "(Lcom/live2d/graphics3d/a;Lcom/live2d/type/CRect;Lcom/live2d/type/CRect;)V",
                null,
                null);
        frame.visitCode();
        frame.visitInsn(RETURN);
        frame.visitMaxs(0, 0);
        frame.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** Asserts the probe never rewrote a class without a matching reviewed shape. */
    @Test
    void reportsEverySelectorExactlyOnceOnTheSyntheticHost() throws Exception {
        final ClipMaskProbeMethodTransformer transformer = transformer(referenceBytes());
        for (Map.Entry<String, byte[]> entry : referenceBytes().entrySet()) {
            transformer.transform(
                    null,
                    loader,
                    entry.getKey(),
                    null,
                    new ProtectionDomain(
                            new CodeSource(artifact.toUri().toURL(), (java.security.CodeSigner[]) null),
                            new Permissions()),
                    entry.getValue());
        }
        assertNull(transformer.failure());
        transformer
                .matchCounts()
                .forEach((slot, count) -> assertEquals(1, count, "selector " + slot.owner() + "." + slot.method()));
        assertFalse(transformer.beforeSha256().isEmpty());
        assertEquals(6, transformer.sites());
    }
}
