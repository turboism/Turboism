package dev.turboism.adapter.cubism.optimization.clipmask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

final class ClipMaskDeduplicationTest {

    @Test
    void digestDistinguishesIdentityAndOrder() {
        final Object entity1 = new Object();
        final Object entity2 = new Object();
        final Object entity3 = new Object();

        final long d1 = ClipMaskDeduplicationBridge.digestOf(List.of(entity1, entity2));
        final long d2 = ClipMaskDeduplicationBridge.digestOf(List.of(entity1, entity2));
        final long dReversed = ClipMaskDeduplicationBridge.digestOf(List.of(entity2, entity1));
        final long dDifferent = ClipMaskDeduplicationBridge.digestOf(List.of(entity1, entity3));
        final long dSingle = ClipMaskDeduplicationBridge.digestOf(List.of(entity1));

        assertEquals(d1, d2, "Identical entity lists must have identical digests");
        assertTrue(d1 != dReversed, "Order reversal must produce different digest");
        assertTrue(d1 != dDifferent, "Different entities must produce different digest");
        assertTrue(d1 != dSingle, "Different length must produce different digest");
    }

    @Test
    void targetOwnerResolvesBothMajorVersionFamilies() {
        assertEquals(
                ClipMaskDeduplicationTarget.RENDER_SYSTEM_53, ClipMaskDeduplicationTarget.renderSystemOwner("5.3.00"));
        assertEquals(
                ClipMaskDeduplicationTarget.RENDER_SYSTEM_53, ClipMaskDeduplicationTarget.renderSystemOwner("5.3.04"));
        assertEquals(
                ClipMaskDeduplicationTarget.RENDER_SYSTEM_50, ClipMaskDeduplicationTarget.renderSystemOwner("5.0.01"));
        assertEquals(
                ClipMaskDeduplicationTarget.RENDER_SYSTEM_50, ClipMaskDeduplicationTarget.renderSystemOwner("5.2.03"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void nestedFrameBoundariesPreserveCachesIsolateRenderSystemsAndAvoidIterators() throws Exception {
        final NoIteratorList<Object> components = new NoIteratorList<>();
        final NoIteratorList<Object> masks = new NoIteratorList<>();
        masks.add(new Object());
        masks.add(new Object());

        final byte[] hostBytes = buildHostStubJar();
        try (var bridge = instantiateBridgeWithStubs(hostBytes)) {
            bridge.install();
            final Class<?> clippingClass = bridgeLoader.get().loadClass("com.live2d.graphics3d.component.GClipping");
            final Class<?> entityClass = bridgeLoader.get().loadClass("com.live2d.graphics3d.entity.GEntity");
            final Class<?> materialClass = bridgeLoader.get().loadClass("com.live2d.graphics3d.material.GMaterial");
            final Class<?> rendererClass = bridgeLoader.get().loadClass("com.live2d.graphics3d.component.AGRenderer");

            final Object clipping = clippingClass.getConstructor().newInstance();
            clippingClass.getField("validMasks").set(clipping, masks);
            components.add(clipping);

            final Object entity = entityClass.getConstructor().newInstance();
            entityClass.getField("components").set(entity, new StubComponents(components));
            final Object material = materialClass.getConstructor().newInstance();
            final Object renderer = rendererClass.getConstructor().newInstance();
            rendererClass.getField("entity").set(renderer, entity);
            rendererClass.getField("material").set(renderer, material);
            final var consult = (java.util.function.BiPredicate<Object, Object>)
                    System.getProperties().get(ClipMaskDeduplicationBridge.DEDUPE_PROPERTY);
            final var record = (java.util.function.BiConsumer<Object, Object>)
                    System.getProperties().get(ClipMaskDeduplicationBridge.RECORD_PROPERTY);
            final var release = (java.util.function.Predicate<Object>)
                    System.getProperties().get(ClipMaskDeduplicationBridge.RELEASE_PROPERTY);
            final var enter = (Runnable) System.getProperties().get(ClipMaskDeduplicationBridge.FRAME_ENTER_PROPERTY);
            final var exit = (Runnable) System.getProperties().get(ClipMaskDeduplicationBridge.FRAME_PROPERTY);

            final Class<?> renderSysClass = bridgeLoader.get().loadClass("com.live2d.graphics3d.rendering.e");
            final Class<?> leaseClass = bridgeLoader.get().loadClass("com.live2d.graphics3d.rendering.a");
            final Object outerRenderSystem =
                    renderSysClass.getDeclaredConstructor().newInstance();
            final Object innerRenderSystem =
                    renderSysClass.getDeclaredConstructor().newInstance();
            final Object outerLease = leaseClass.getDeclaredConstructor().newInstance();
            final Object innerLease = leaseClass.getDeclaredConstructor().newInstance();
            final var leaseField = renderSysClass.getField("f");

            // Outer frame begins -> depth = 1
            enter.run();
            assertEquals(false, consult.test(outerRenderSystem, renderer));
            leaseField.set(outerRenderSystem, outerLease);
            record.accept(outerRenderSystem, renderer);
            assertEquals(true, release.test(outerRenderSystem));

            // Nested GGroupRenderer pass begins -> depth = 2
            enter.run();
            // Different renderSystem viewport must not collide with outer mask entry.
            assertEquals(false, consult.test(innerRenderSystem, renderer));
            leaseField.set(innerRenderSystem, innerLease);
            record.accept(innerRenderSystem, renderer);
            assertEquals(true, consult.test(innerRenderSystem, renderer));
            // Inner exits -> depth returns to 1; caches and outer leases MUST remain alive.
            exit.run();
            assertEquals(0L, bridge.snapshot().get("frames"));
            assertEquals(0, StubPool.releasedCount.get());

            // Sibling renderer after nested GGroupRenderer still hits outer mask cache!
            assertEquals(true, consult.test(outerRenderSystem, renderer));
            assertEquals(2L, bridge.snapshot().get("dedupeHits"));

            // Outer exits -> depth = 0 -> releases both held leases exactly once.
            exit.run();
            assertEquals(1L, bridge.snapshot().get("frames"));
            assertEquals(2, StubPool.releasedCount.get());
        } finally {
            bridgeLoader.set(null);
            StubPool.releasedCount.set(0);
        }
    }

    private static final ThreadLocal<ClassLoader> bridgeLoader = new ThreadLocal<>();

    public static class StubEntity {
        public StubComponents components;

        protected StubEntity() {
            this.components = null;
        }

        public StubEntity(final StubComponents components) {
            this.components = components;
        }

        public StubComponents getComponents() {
            return components;
        }
    }

    public static class StubComponents {
        private final List<Object> list;

        public StubComponents(final List<Object> list) {
            this.list = list;
        }

        public List<Object> a() {
            return list;
        }
    }

    public static class StubClipping {
        public List<Object> validMasks = List.of();

        public List<?> getClippingVaildMasks() {
            return validMasks;
        }

        public boolean isRemove() {
            return false;
        }

        public boolean getInvert() {
            return false;
        }
    }

    public static class StubMaterial {
        public void setTexture(final String n, final Object t) {}

        public void setVector2(final String n, final Object v, final Integer u) {}

        public void setFloat(final String n, final float v, final Integer u) {}
    }

    public static class StubRenderer {
        public StubEntity entity;
        public StubMaterial material;

        protected StubRenderer() {
            this.entity = null;
            this.material = null;
        }

        public StubRenderer(final StubEntity entity, final StubMaterial material) {
            this.entity = entity;
            this.material = material;
        }

        public StubEntity getEntity() {
            return entity;
        }

        public StubMaterial getCurMaterial() {
            return material;
        }
    }

    public static class StubPool {
        public static final StubPool INSTANCE = new StubPool();
        public static final java.util.concurrent.atomic.AtomicInteger releasedCount =
                new java.util.concurrent.atomic.AtomicInteger();

        public void a(final Object lease) {
            releasedCount.incrementAndGet();
        }
    }

    private static final class NoIteratorList<T> extends java.util.ArrayList<T> {
        @Override
        public java.util.Iterator<T> iterator() {
            throw new AssertionError("iterator allocation forbidden on hot path");
        }

        @Override
        public java.util.ListIterator<T> listIterator() {
            throw new AssertionError("listIterator allocation forbidden on hot path");
        }

        @Override
        public java.util.ListIterator<T> listIterator(final int index) {
            throw new AssertionError("listIterator allocation forbidden on hot path");
        }

        @Override
        public boolean equals(final Object o) {
            throw new AssertionError("List.equals forbidden on hot path");
        }
    }

    private static byte[] buildHostStubJar() {
        return new byte[0];
    }

    private static ClipMaskDeduplicationBridge instantiateBridgeWithStubs(final byte[] ignored) throws Exception {
        final class HostStubLoader extends ClassLoader {
            HostStubLoader() {
                super(ClipMaskDeduplicationTest.class.getClassLoader());
            }

            Class<?> define(final String name, final byte[] bytes) {
                return defineClass(name.replace('/', '.'), bytes, 0, bytes.length);
            }
        }
        final HostStubLoader loader = new HostStubLoader();
        bridgeLoader.set(loader);
        loader.define(
                "com/live2d/graphics3d/texture/GTexture",
                emptyHostType("com/live2d/graphics3d/texture/GTexture", null));
        loader.define("com/live2d/graphics3d/texture/GRenderTexture", renderTextureStub());
        loader.define("com/live2d/graphics3d/type/GVector2", gVector2Stub());
        loader.define("com/live2d/graphics3d/rendering/a", leaseStub());
        loader.define("com/live2d/graphics3d/rendering/b", poolStub());
        loader.define("com/live2d/graphics3d/rendering/e", renderSystemStub());
        loader.define("com/live2d/graphics3d/component/GClipping", clippingAdapter());
        loader.define("com/live2d/graphics3d/component/AGRenderer", rendererAdapter());
        loader.define("com/live2d/graphics3d/entity/GEntity", entityAdapter());
        loader.define("com/live2d/graphics3d/material/GMaterial", materialAdapter());
        return new ClipMaskDeduplicationBridge(loader, "5.3.05");
    }

    private static byte[] emptyHostType(final String name, final String superName) {
        final String resolvedSuper = superName == null ? "java/lang/Object" : superName;
        final var cw = new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_FRAMES);
        cw.visit(org.objectweb.asm.Opcodes.V17, org.objectweb.asm.Opcodes.ACC_PUBLIC, name, null, resolvedSuper, null);
        final var mv = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        mv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, resolvedSuper, "<init>", "()V", false);
        mv.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] renderTextureStub() {
        final String owner = "com/live2d/graphics3d/texture/GRenderTexture";
        final var cw = new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_FRAMES);
        cw.visit(
                org.objectweb.asm.Opcodes.V17,
                org.objectweb.asm.Opcodes.ACC_PUBLIC,
                owner,
                null,
                "com/live2d/graphics3d/texture/GTexture",
                null);
        final var init = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        init.visitMethodInsn(
                org.objectweb.asm.Opcodes.INVOKESPECIAL,
                "com/live2d/graphics3d/texture/GTexture",
                "<init>",
                "()V",
                false);
        init.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();
        for (final String m : new String[] {"getWidth", "getHeight"}) {
            final var mv = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, m, "()I", null, null);
            mv.visitCode();
            mv.visitIntInsn(org.objectweb.asm.Opcodes.SIPUSH, 256);
            mv.visitInsn(org.objectweb.asm.Opcodes.IRETURN);
            mv.visitMaxs(1, 1);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] gVector2Stub() {
        final String owner = "com/live2d/graphics3d/type/GVector2";
        final var cw = new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_FRAMES);
        cw.visit(
                org.objectweb.asm.Opcodes.V17,
                org.objectweb.asm.Opcodes.ACC_PUBLIC,
                owner,
                null,
                "java/lang/Object",
                null);
        final var mv = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "<init>", "(FF)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        mv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        mv.visitMaxs(1, 3);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] leaseStub() {
        final String owner = "com/live2d/graphics3d/rendering/a";
        final var cw = new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_FRAMES);
        cw.visit(
                org.objectweb.asm.Opcodes.V17,
                org.objectweb.asm.Opcodes.ACC_PUBLIC,
                owner,
                null,
                "java/lang/Object",
                null);
        final var init = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        init.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();
        final var b = cw.visitMethod(
                org.objectweb.asm.Opcodes.ACC_PUBLIC,
                "b",
                "()Lcom/live2d/graphics3d/texture/GRenderTexture;",
                null,
                null);
        b.visitCode();
        b.visitTypeInsn(org.objectweb.asm.Opcodes.NEW, "com/live2d/graphics3d/texture/GRenderTexture");
        b.visitInsn(org.objectweb.asm.Opcodes.DUP);
        b.visitMethodInsn(
                org.objectweb.asm.Opcodes.INVOKESPECIAL,
                "com/live2d/graphics3d/texture/GRenderTexture",
                "<init>",
                "()V",
                false);
        b.visitInsn(org.objectweb.asm.Opcodes.ARETURN);
        b.visitMaxs(2, 1);
        b.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] poolStub() {
        final String owner = "com/live2d/graphics3d/rendering/b";
        final String testOwner = ClipMaskDeduplicationTest.class.getName().replace('.', '/');
        final var cw = new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_FRAMES);
        cw.visit(
                org.objectweb.asm.Opcodes.V17,
                org.objectweb.asm.Opcodes.ACC_PUBLIC,
                owner,
                null,
                "java/lang/Object",
                null);
        cw.visitField(
                        org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC,
                        "a",
                        "L" + owner + ";",
                        null,
                        null)
                .visitEnd();
        final var init = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        init.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();
        final var clinit = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitTypeInsn(org.objectweb.asm.Opcodes.NEW, owner);
        clinit.visitInsn(org.objectweb.asm.Opcodes.DUP);
        clinit.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, owner, "<init>", "()V", false);
        clinit.visitFieldInsn(org.objectweb.asm.Opcodes.PUTSTATIC, owner, "a", "L" + owner + ";");
        clinit.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        clinit.visitMaxs(2, 0);
        clinit.visitEnd();
        final var rel = cw.visitMethod(
                org.objectweb.asm.Opcodes.ACC_PUBLIC, "a", "(Lcom/live2d/graphics3d/rendering/a;)V", null, null);
        rel.visitCode();
        rel.visitFieldInsn(
                org.objectweb.asm.Opcodes.GETSTATIC,
                testOwner + "$StubPool",
                "releasedCount",
                "Ljava/util/concurrent/atomic/AtomicInteger;");
        rel.visitMethodInsn(
                org.objectweb.asm.Opcodes.INVOKEVIRTUAL,
                "java/util/concurrent/atomic/AtomicInteger",
                "incrementAndGet",
                "()I",
                false);
        rel.visitInsn(org.objectweb.asm.Opcodes.POP);
        rel.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        rel.visitMaxs(1, 2);
        rel.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] renderSystemStub() {
        final String owner = "com/live2d/graphics3d/rendering/e";
        final var cw = new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_FRAMES);
        cw.visit(
                org.objectweb.asm.Opcodes.V17,
                org.objectweb.asm.Opcodes.ACC_PUBLIC,
                owner,
                null,
                "java/lang/Object",
                null);
        cw.visitField(org.objectweb.asm.Opcodes.ACC_PUBLIC, "f", "Lcom/live2d/graphics3d/rendering/a;", null, null)
                .visitEnd();
        final var mv = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        mv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] clippingAdapter() {
        return emptyHostType(
                "com/live2d/graphics3d/component/GClipping",
                StubClipping.class.getName().replace('.', '/'));
    }

    private static byte[] rendererAdapter() {
        return emptyHostType(
                "com/live2d/graphics3d/component/AGRenderer",
                StubRenderer.class.getName().replace('.', '/'));
    }

    private static byte[] entityAdapter() {
        return emptyHostType(
                "com/live2d/graphics3d/entity/GEntity",
                StubEntity.class.getName().replace('.', '/'));
    }

    private static byte[] materialAdapter() {
        final String owner = "com/live2d/graphics3d/material/GMaterial";
        final String superName = StubMaterial.class.getName().replace('.', '/');
        final var cw = new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_FRAMES);
        cw.visit(org.objectweb.asm.Opcodes.V17, org.objectweb.asm.Opcodes.ACC_PUBLIC, owner, null, superName, null);
        final var init = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        init.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false);
        init.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();
        final var setTex = cw.visitMethod(
                org.objectweb.asm.Opcodes.ACC_PUBLIC,
                "setTexture",
                "(Ljava/lang/String;Lcom/live2d/graphics3d/texture/GTexture;)V",
                null,
                null);
        setTex.visitCode();
        setTex.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        setTex.visitMaxs(0, 3);
        setTex.visitEnd();
        final var setVec = cw.visitMethod(
                org.objectweb.asm.Opcodes.ACC_PUBLIC,
                "setVector2",
                "(Ljava/lang/String;Lcom/live2d/graphics3d/type/GVector2;Ljava/lang/Integer;)V",
                null,
                null);
        setVec.visitCode();
        setVec.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        setVec.visitMaxs(0, 4);
        setVec.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
