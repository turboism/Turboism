package dev.turboism.adapter.cubism.optimization.clipmask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.objectweb.asm.Opcodes.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.Permissions;
import java.security.ProtectionDomain;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

final class ClipMaskDeduplicationTransformerTest {

    private static final String RENDER_SYSTEM = "com/live2d/graphics3d/rendering/e";
    private static final String RENDER_DESC = "(Lcom/live2d/graphics3d/a;Lcom/live2d/type/CRect;ZZ)V";
    private static final String GL3 = "com/jogamp/opengl/GL3";
    private static final String FAKE_GL = "com/live2d/test/FakeGl";

    private Path artifact;
    private TestLoader loader;

    private static final class TestLoader extends ClassLoader {
        private final ProtectionDomain domain;

        TestLoader(final Path artifact) throws Exception {
            super(ClipMaskDeduplicationTransformerTest.class.getClassLoader());
            domain = new ProtectionDomain(
                    new CodeSource(artifact.toUri().toURL(), (java.security.CodeSigner[]) null), new Permissions());
        }

        Class<?> define(final String name, final byte[] bytes) {
            return defineClass(name.replace('/', '.'), bytes, 0, bytes.length, domain);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        artifact = Files.createTempFile("clip-mask-dedupe-test", ".jar");
        loader = new TestLoader(artifact);
    }

    @AfterEach
    void tearDown() {
        System.getProperties().remove(ClipMaskDeduplicationBridge.DEDUPE_PROPERTY);
        System.getProperties().remove(ClipMaskDeduplicationBridge.RECORD_PROPERTY);
        System.getProperties().remove(ClipMaskDeduplicationBridge.RELEASE_PROPERTY);
        System.getProperties().remove(ClipMaskDeduplicationBridge.FRAME_PROPERTY);
        System.getProperties().remove(ClipMaskScissorBridge.CLEAR_PROPERTY);
        try {
            Files.deleteIfExists(artifact);
        } catch (Exception ignored) {
        }
    }

    @Test
    void transformerWeavesDedupeConsultRecordAndFrameCleanup() throws Exception {
        // Define parameter types first
        loader.define(
                "com/live2d/graphics3d/component/AGRenderer", plainClass("com/live2d/graphics3d/component/AGRenderer"));
        loader.define("com/live2d/graphics3d/a", plainClass("com/live2d/graphics3d/a"));
        loader.define("com/live2d/type/CRect", plainClass("com/live2d/type/CRect"));
        loader.define(GL3, glInterface());
        loader.define(FAKE_GL, fakeGl());

        final byte[] renderSystemBytes = buildSyntheticRenderSystem();
        final Map<String, byte[]> references = Map.of(RENDER_SYSTEM, renderSystemBytes);

        final ClipMaskDeduplicationTransformer transformer =
                new ClipMaskDeduplicationTransformer(loader, artifact, RENDER_SYSTEM, RENDER_DESC, references);

        final byte[] transformedSystem =
                transformer.transform(null, loader, RENDER_SYSTEM, null, loader.domain, renderSystemBytes);
        assertNotNull(transformedSystem);

        assertEquals(3, transformer.sites());
        assertNull(transformer.failure());

        // Define transformed class
        final Class<?> systemClass = loader.define(RENDER_SYSTEM, transformedSystem);
        final Object system = systemClass.getConstructor().newInstance();

        final Class<?> agRendererClass = loader.loadClass("com.live2d.graphics3d.component.AGRenderer");
        final Class<?> graphicsAClass = loader.loadClass("com.live2d.graphics3d.a");
        final Class<?> cRectClass = loader.loadClass("com.live2d.type.CRect");

        // 1. Verify dedupe hit short-circuits mask pass
        final AtomicInteger dedupeHits = new AtomicInteger();
        final AtomicInteger records = new AtomicInteger();
        System.getProperties()
                .put(ClipMaskDeduplicationBridge.DEDUPE_PROPERTY, (BiPredicate<Object, Object>) (sys, rend) -> {
                    dedupeHits.incrementAndGet();
                    return true; // Hit!
                });
        System.getProperties().put(ClipMaskDeduplicationBridge.RECORD_PROPERTY, (BiConsumer<Object, Object>)
                (sys, rend) -> records.incrementAndGet());

        final java.lang.reflect.Method maskMethod =
                systemClass.getDeclaredMethod("a", agRendererClass, graphicsAClass, cRectClass);
        maskMethod.setAccessible(true);
        maskMethod.invoke(system, agRendererClass.getConstructor().newInstance(), null, null);

        assertEquals(1, dedupeHits.get(), "Consult must be called at entry");
        assertEquals(0, records.get(), "Record must not be called when entry returns true");

        // 2. Verify dedupe miss falls through and calls record
        System.getProperties()
                .put(ClipMaskDeduplicationBridge.DEDUPE_PROPERTY, (BiPredicate<Object, Object>) (sys, rend) -> false);
        maskMethod.invoke(system, agRendererClass.getConstructor().newInstance(), null, null);
        assertEquals(1, records.get(), "Record must be called on miss completion");

        // 3. Verify endClipping release suppression
        final AtomicInteger releases = new AtomicInteger();
        System.getProperties().put(ClipMaskDeduplicationBridge.RELEASE_PROPERTY, (Predicate<Object>) sys -> {
            releases.incrementAndGet();
            return true; // Held!
        });
        final java.lang.reflect.Method endMethod = systemClass.getDeclaredMethod("a", agRendererClass, graphicsAClass);
        endMethod.setAccessible(true);
        endMethod.invoke(system, agRendererClass.getConstructor().newInstance(), null);
        assertEquals(1, releases.get(), "Release consult must be called");

        // 4. Verify frame boundary cleanup
        final AtomicBoolean frameEnded = new AtomicBoolean();
        System.getProperties().put(ClipMaskDeduplicationBridge.FRAME_PROPERTY, (Runnable) () -> frameEnded.set(true));
        final java.lang.reflect.Method renderMethod =
                systemClass.getDeclaredMethod("a", graphicsAClass, cRectClass, boolean.class, boolean.class);
        renderMethod.invoke(system, null, null, false, false);
        assertTrue(frameEnded.get(), "Frame cleanup must be executed at exit of render method");
    }

    @Test
    void scissorClearBracketRunsOnceAroundTheMaskClear() throws Exception {
        loader.define(
                "com/live2d/graphics3d/component/AGRenderer", plainClass("com/live2d/graphics3d/component/AGRenderer"));
        loader.define("com/live2d/graphics3d/a", plainClass("com/live2d/graphics3d/a"));
        loader.define("com/live2d/type/CRect", plainClass("com/live2d/type/CRect"));
        loader.define(GL3, glInterface());
        loader.define(FAKE_GL, fakeGl());

        final byte[] renderSystemBytes = buildSyntheticRenderSystem();
        final ClipMaskDeduplicationTransformer transformer = new ClipMaskDeduplicationTransformer(
                loader, artifact, RENDER_SYSTEM, RENDER_DESC, Map.of(RENDER_SYSTEM, renderSystemBytes));
        final byte[] transformedSystem =
                transformer.transform(null, loader, RENDER_SYSTEM, null, loader.domain, renderSystemBytes);
        assertNotNull(transformedSystem);

        final Class<?> systemClass = loader.define(RENDER_SYSTEM, transformedSystem);
        final Object system = systemClass.getConstructor().newInstance();
        final java.lang.reflect.Method maskMethod = systemClass.getDeclaredMethod(
                "a",
                loader.loadClass("com.live2d.graphics3d.component.AGRenderer"),
                loader.loadClass("com.live2d.graphics3d.a"),
                loader.loadClass("com.live2d.type.CRect"));
        maskMethod.setAccessible(true);

        final java.util.List<String> order = new java.util.ArrayList<>();
        System.getProperties().put(ClipMaskDeduplicationBridge.DEDUPE_PROPERTY, (BiPredicate<Object, Object>)
                (systemArg, renderer) -> {
                    assertNotNull(systemArg);
                    assertNotNull(renderer);
                    order.add("entry");
                    return false;
                });
        System.getProperties()
                .put(ClipMaskScissorBridge.CLEAR_PROPERTY, (java.util.function.Function<Object, Runnable>) context -> {
                    assertNotNull(context);
                    order.add("before");
                    return () -> order.add("after");
                });
        System.getProperties().put(ClipMaskDeduplicationBridge.RECORD_PROPERTY, (BiConsumer<Object, Object>)
                (systemArg, renderer) -> order.add("exit"));

        final Object renderer = loader.loadClass("com.live2d.graphics3d.component.AGRenderer")
                .getConstructor()
                .newInstance();
        final Object context =
                loader.loadClass("com.live2d.graphics3d.a").getConstructor().newInstance();
        maskMethod.invoke(system, renderer, context, null);

        assertEquals(java.util.List.of("entry", "before", "after", "exit"), order);
    }

    @Test
    void throwingScissorConsultDoesNotSkipTheClearOrExit() throws Exception {
        loader.define(
                "com/live2d/graphics3d/component/AGRenderer", plainClass("com/live2d/graphics3d/component/AGRenderer"));
        loader.define("com/live2d/graphics3d/a", plainClass("com/live2d/graphics3d/a"));
        loader.define("com/live2d/type/CRect", plainClass("com/live2d/type/CRect"));
        loader.define(GL3, glInterface());
        loader.define(FAKE_GL, fakeGl());

        final byte[] renderSystemBytes = buildSyntheticRenderSystem();
        final ClipMaskDeduplicationTransformer transformer = new ClipMaskDeduplicationTransformer(
                loader, artifact, RENDER_SYSTEM, RENDER_DESC, Map.of(RENDER_SYSTEM, renderSystemBytes));
        final byte[] transformedSystem =
                transformer.transform(null, loader, RENDER_SYSTEM, null, loader.domain, renderSystemBytes);
        final Class<?> systemClass = loader.define(RENDER_SYSTEM, transformedSystem);
        final Object system = systemClass.getConstructor().newInstance();
        final java.lang.reflect.Method maskMethod = systemClass.getDeclaredMethod(
                "a",
                loader.loadClass("com.live2d.graphics3d.component.AGRenderer"),
                loader.loadClass("com.live2d.graphics3d.a"),
                loader.loadClass("com.live2d.type.CRect"));
        maskMethod.setAccessible(true);

        final AtomicInteger exit = new AtomicInteger();
        System.getProperties()
                .put(ClipMaskScissorBridge.CLEAR_PROPERTY, (java.util.function.Function<Object, Runnable>) context -> {
                    throw new IllegalStateException("consult failed");
                });
        System.getProperties().put(ClipMaskDeduplicationBridge.RECORD_PROPERTY, (BiConsumer<Object, Object>)
                (systemArg, renderer) -> exit.incrementAndGet());

        final Object renderer = loader.loadClass("com.live2d.graphics3d.component.AGRenderer")
                .getConstructor()
                .newInstance();
        final Object context =
                loader.loadClass("com.live2d.graphics3d.a").getConstructor().newInstance();
        maskMethod.invoke(system, renderer, context, null);

        assertEquals(1, exit.get(), "the throw is contained and the normal return still runs the exit consult");
    }

    private static byte[] glInterface() {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE, GL3, null, "java/lang/Object", null);
        final MethodVisitor clear = writer.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "glClear", "(I)V", null, null);
        clear.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] fakeGl() {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, FAKE_GL, null, "java/lang/Object", new String[] {GL3});
        final MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();
        final MethodVisitor clear = writer.visitMethod(ACC_PUBLIC, "glClear", "(I)V", null, null);
        clear.visitCode();
        clear.visitInsn(RETURN);
        clear.visitMaxs(0, 2);
        clear.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] buildSyntheticRenderSystem() {
        final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, RENDER_SYSTEM, null, "java/lang/Object", null);

        // empty ctor
        final MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();

        // a(AGRenderer, a, CRect)V
        final MethodVisitor mask = cw.visitMethod(
                ACC_PRIVATE,
                "a",
                "(Lcom/live2d/graphics3d/component/AGRenderer;Lcom/live2d/graphics3d/a;Lcom/live2d/type/CRect;)V",
                null,
                null);
        mask.visitCode();
        mask.visitTypeInsn(NEW, FAKE_GL);
        mask.visitInsn(DUP);
        mask.visitMethodInsn(INVOKESPECIAL, FAKE_GL, "<init>", "()V", false);
        mask.visitIntInsn(SIPUSH, 16384);
        mask.visitMethodInsn(INVOKEINTERFACE, GL3, "glClear", "(I)V", true);
        mask.visitInsn(RETURN);
        mask.visitMaxs(3, 4);
        mask.visitEnd();

        // a(AGRenderer, a)V
        final MethodVisitor end = cw.visitMethod(
                ACC_PRIVATE,
                "a",
                "(Lcom/live2d/graphics3d/component/AGRenderer;Lcom/live2d/graphics3d/a;)V",
                null,
                null);
        end.visitCode();
        end.visitInsn(NOP);
        end.visitInsn(RETURN);
        end.visitMaxs(1, 3);
        end.visitEnd();

        // a(a, CRect, ZZ)V
        final MethodVisitor render = cw.visitMethod(ACC_PUBLIC, "a", RENDER_DESC, null, null);
        render.visitCode();
        render.visitInsn(NOP);
        render.visitInsn(RETURN);
        render.visitMaxs(1, 5);
        render.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

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
}
