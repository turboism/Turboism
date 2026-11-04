package dev.turboism.adapter.cubism.optimization.meshsetup;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.Permissions;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

/**
 * Verifies the skipped-frame {@code GMesh2.setup} transform on a synthetic
 * class carrying the exact reviewed owner/method/descriptor. The body is a
 * single counter increment; the test proves the entry predicate can return
 * before it, the completion callback follows a native return, a throwing body
 * notifies the failure slot and rethrows unchanged, and any shape drift leaves
 * the class untouched.
 */
class SkippedFrameMeshSetupElisionTransformerTest {

    private static final String OWNER = "com/live2d/graphics3d/mesh/GMesh2";
    private static final String FRAME = "com/live2d/graphics3d/a";
    private static final String DESCRIPTOR = "(Lcom/live2d/graphics3d/a;)V";

    @TempDir
    Path directory;

    @AfterEach
    void clearSlots() {
        final var properties = System.getProperties();
        properties.remove(SkippedFrameMeshSetupElisionBridge.PREDICATE_PROPERTY);
        properties.remove(SkippedFrameMeshSetupElisionBridge.COMPLETE_PROPERTY);
        properties.remove(SkippedFrameMeshSetupElisionBridge.FAILURE_PROPERTY);
        properties.remove(SkippedFrameMeshSetupElisionBridge.STATS_PROPERTY);
    }

    private static final class Loader extends ClassLoader {
        private final ProtectionDomain domain;

        Loader(Path artifact) throws Exception {
            super(SkippedFrameMeshSetupElisionTransformerTest.class.getClassLoader());
            domain = new ProtectionDomain(
                    new CodeSource(artifact.toUri().toURL(), (java.security.CodeSigner[]) null), new Permissions());
        }

        Class<?> define(String name, byte[] bytes) {
            return defineClass(name.replace('/', '.'), bytes, 0, bytes.length, domain);
        }
    }

    private static byte[] frame() {
        final ClassWriter writer = new ClassWriter(0);
        writer.visit(V17, ACC_PUBLIC | ACC_FINAL, FRAME, null, "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] mesh(final boolean drift) {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_FINAL, OWNER, null, "java/lang/Object", null);
        writer.visitField(ACC_PUBLIC, "setups", "I", null, null).visitEnd();
        writer.visitField(ACC_PUBLIC, "throwNow", "Z", null, null).visitEnd();
        MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC, "setup", DESCRIPTOR, null, null);
        method.visitCode();
        if (drift) method.visitInsn(NOP);
        method.visitVarInsn(ALOAD, 0);
        method.visitInsn(DUP);
        method.visitFieldInsn(GETFIELD, OWNER, "setups", "I");
        method.visitInsn(ICONST_1);
        method.visitInsn(IADD);
        method.visitFieldInsn(PUTFIELD, OWNER, "setups", "I");
        final Label keep = new Label();
        method.visitVarInsn(ALOAD, 0);
        method.visitFieldInsn(GETFIELD, OWNER, "throwNow", "Z");
        method.visitJumpInsn(IFEQ, keep);
        method.visitTypeInsn(NEW, "java/lang/IllegalStateException");
        method.visitInsn(DUP);
        method.visitMethodInsn(INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "()V", false);
        method.visitInsn(ATHROW);
        method.visitLabel(keep);
        method.visitInsn(RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private SkippedFrameMeshSetupElisionTransformer transformer(
            final Loader loader, final Path artifact, final byte[] reference) {
        return new SkippedFrameMeshSetupElisionTransformer(loader, artifact, reference);
    }

    private static Object instance(final Class<?> type) throws Exception {
        return type.getDeclaredConstructor().newInstance();
    }

    private static void invoke(final Class<?> type, final Object instance, final Class<?> frameType) throws Exception {
        type.getMethod("setup", frameType).invoke(instance, (Object) null);
    }

    @Test
    void withoutSlotsTheNativeBodyRunsAndTheClassIsRewritten() throws Exception {
        final Path artifact = Files.createTempFile("host", ".jar");
        final Loader loader = new Loader(artifact);
        final byte[] reference = mesh(false);
        final var transformer = transformer(loader, artifact, reference);
        final byte[] output = transformer.transform(null, loader, OWNER, null, loader.domain, reference);
        assertNotNull(output, transformer.failure());
        assertEquals(1, transformer.matches());
        assertEquals(1, transformer.guarded());
        assertNotNull(transformer.beforeSha256());

        final Class<?> frameType = loader.define(FRAME, frame());
        final Class<?> type = loader.define(OWNER, output);
        final Object instance = instance(type);
        invoke(type, instance, frameType);
        assertEquals(1, type.getField("setups").getInt(instance));
    }

    @Test
    void truePredicateReturnsBeforeAnySetupWorkAndDoesNotComplete() throws Exception {
        final Path artifact = Files.createTempFile("host", ".jar");
        final Loader loader = new Loader(artifact);
        final byte[] reference = mesh(false);
        final var transformer = transformer(loader, artifact, reference);
        final byte[] output = transformer.transform(null, loader, OWNER, null, loader.domain, reference);
        assertNotNull(output, transformer.failure());
        final Class<?> frameType = loader.define(FRAME, frame());
        final Class<?> type = loader.define(OWNER, output);
        final Object instance = instance(type);
        final AtomicInteger completed = new AtomicInteger();
        System.getProperties().put(SkippedFrameMeshSetupElisionBridge.PREDICATE_PROPERTY, (BiPredicate<Object, Object>)
                (mesh, frame) -> true);
        System.getProperties().put(SkippedFrameMeshSetupElisionBridge.COMPLETE_PROPERTY, (Consumer<Object>)
                ignored -> completed.incrementAndGet());
        invoke(type, instance, frameType);
        assertEquals(0, type.getField("setups").getInt(instance));
        assertEquals(0, completed.get(), "an elided return is not a native completion");
    }

    @Test
    void falseOrThrowingPredicateFallsThroughAndCompletes() throws Exception {
        final Path artifact = Files.createTempFile("host", ".jar");
        final Loader loader = new Loader(artifact);
        final byte[] reference = mesh(false);
        final var transformer = transformer(loader, artifact, reference);
        final byte[] output = transformer.transform(null, loader, OWNER, null, loader.domain, reference);
        assertNotNull(output, transformer.failure());
        final Class<?> frameType = loader.define(FRAME, frame());
        final Class<?> type = loader.define(OWNER, output);
        final Object instance = instance(type);
        final AtomicInteger completed = new AtomicInteger();
        System.getProperties().put(SkippedFrameMeshSetupElisionBridge.PREDICATE_PROPERTY, (BiPredicate<Object, Object>)
                (mesh, frame) -> false);
        System.getProperties().put(SkippedFrameMeshSetupElisionBridge.COMPLETE_PROPERTY, (Consumer<Object>)
                ignored -> completed.incrementAndGet());
        invoke(type, instance, frameType);
        assertEquals(1, type.getField("setups").getInt(instance));
        assertEquals(1, completed.get());

        System.getProperties().put(SkippedFrameMeshSetupElisionBridge.PREDICATE_PROPERTY, (BiPredicate<Object, Object>)
                (mesh, frame) -> {
                    throw new IllegalStateException("observer");
                });
        invoke(type, instance, frameType);
        assertEquals(2, type.getField("setups").getInt(instance));
        assertEquals(2, completed.get());
    }

    @Test
    void throwingBodyNotifiesFailureAndRethrowsWithoutCompleting() throws Exception {
        final Path artifact = Files.createTempFile("host", ".jar");
        final Loader loader = new Loader(artifact);
        final byte[] reference = mesh(false);
        final var transformer = transformer(loader, artifact, reference);
        final byte[] output = transformer.transform(null, loader, OWNER, null, loader.domain, reference);
        assertNotNull(output, transformer.failure());
        final Class<?> frameType = loader.define(FRAME, frame());
        final Class<?> type = loader.define(OWNER, output);
        final Object instance = instance(type);
        type.getField("throwNow").setBoolean(instance, true);
        final AtomicInteger completed = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();
        System.getProperties().put(SkippedFrameMeshSetupElisionBridge.PREDICATE_PROPERTY, (BiPredicate<Object, Object>)
                (mesh, frame) -> false);
        System.getProperties().put(SkippedFrameMeshSetupElisionBridge.COMPLETE_PROPERTY, (Consumer<Object>)
                ignored -> completed.incrementAndGet());
        System.getProperties().put(SkippedFrameMeshSetupElisionBridge.FAILURE_PROPERTY, (Consumer<Object>)
                ignored -> failed.incrementAndGet());
        final InvocationTargetException thrown =
                assertThrows(InvocationTargetException.class, () -> invoke(type, instance, frameType));
        assertInstanceOf(IllegalStateException.class, thrown.getCause());
        assertEquals(1, failed.get());
        assertEquals(0, completed.get());
    }

    @Test
    void shapeDriftLeavesTheClassUntouched() throws Exception {
        final Path artifact = Files.createTempFile("host", ".jar");
        final Loader loader = new Loader(artifact);
        final byte[] reference = mesh(false);
        final var transformer = transformer(loader, artifact, reference);
        assertNull(transformer.transform(null, loader, OWNER, null, loader.domain, mesh(true)));
        assertNotNull(transformer.failure());
        assertEquals(0, transformer.matches());
    }
}
