package dev.turboism.adapter.cubism.textureatlas;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.EventQueue;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class TextureAtlasDataModelTransformerTest {

    @Test
    void capturesOnlyAfterSuccessfulExactInitializationAndClearsOnClose() throws Exception {
        final TextureAtlasDataModelCapture capture = new TextureAtlasDataModelCapture();
        final TextureAtlasDataModelTransformer transformer = new TextureAtlasDataModelTransformer(
                "fixture/ModelImageList",
                "initGui",
                "()V",
                null,
                "getTaeDataModel",
                "()Ljava/lang/Object;",
                capture.key());
        final byte[] transformed =
                transformer.transform(null, null, "fixture/ModelImageList", null, null, fixtureClass());
        final FixtureLoader loader = new FixtureLoader(null);
        final Class<?> type = loader.define("fixture.ModelImageList", transformed);
        final Object instance = type.getConstructor().newInstance();
        final Object dataModel = type.getField("dataModel").get(instance);
        try {
            type.getMethod("initGui").invoke(instance);
            assertSame(dataModel, capture.current().orElseThrow());

            final Object replacement = new Object();
            type.getField("dataModel").set(instance, replacement);
            type.getField("fail").setBoolean(instance, true);
            org.junit.jupiter.api.Assertions.assertThrows(
                    InvocationTargetException.class,
                    () -> type.getMethod("initGui").invoke(instance));
            assertSame(dataModel, capture.current().orElseThrow());

            capture.close();
            assertTrue(capture.current().isEmpty());
        } finally {
            capture.close();
        }
    }

    @Test
    void atlasLoadingClearsItsScopeWhenNativeInitializationFails() throws Exception {
        final var initializer = MethodHandles.lookup()
                .findStatic(
                        TextureAtlasDataModelTransformerTest.class,
                        "failWhileLoading",
                        MethodType.methodType(void.class, Object.class));
        final var failure = new IllegalStateException("Native initialization failed");
        EventQueue.invokeAndWait(() -> {
            assertSame(
                    failure,
                    assertThrows(IllegalStateException.class, () -> TextureAtlasLoading.run(failure, initializer)));
            assertFalse(TextureAtlasLoading.isInitializing());
        });
    }

    private static void failWhileLoading(final Object failure) {
        assertTrue(TextureAtlasLoading.isInitializing());
        TextureAtlasLoading.layerReady();
        throw (IllegalStateException) failure;
    }

    @Test
    void nativeSolverKeepsItsCallerThreadOutsideAtlasLoading() throws Throwable {
        final var solver = new AtomicReference<Thread>();
        final var nativeSolve = MethodHandles.lookup()
                .findStatic(
                        TextureAtlasDataModelTransformerTest.class,
                        "recordSolveThread",
                        MethodType.methodType(void.class, AtomicReference.class, Object.class));
        assertFalse(TextureAtlasParallelMeshes.capture(solver, null, nativeSolve));
        assertSame(solver, TextureAtlasParallelMeshes.solve(solver, null, null, nativeSolve));
        assertSame(Thread.currentThread(), solver.get());
    }

    private static void recordSolveThread(final AtomicReference<Thread> solver, final Object progress) {
        solver.set(Thread.currentThread());
    }

    private static byte[] fixtureClass() {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "fixture/ModelImageList", null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC, "dataModel", "Ljava/lang/Object;", null, null)
                .visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC, "fail", "Z", null, null).visitEnd();
        final MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitTypeInsn(Opcodes.NEW, "java/lang/Object");
        constructor.visitInsn(Opcodes.DUP);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitFieldInsn(Opcodes.PUTFIELD, "fixture/ModelImageList", "dataModel", "Ljava/lang/Object;");
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        final MethodVisitor getter =
                writer.visitMethod(Opcodes.ACC_PUBLIC, "getTaeDataModel", "()Ljava/lang/Object;", null, null);
        getter.visitCode();
        getter.visitVarInsn(Opcodes.ALOAD, 0);
        getter.visitFieldInsn(Opcodes.GETFIELD, "fixture/ModelImageList", "dataModel", "Ljava/lang/Object;");
        getter.visitInsn(Opcodes.ARETURN);
        getter.visitMaxs(0, 0);
        getter.visitEnd();

        final MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "initGui", "()V", null, null);
        init.visitCode();
        final org.objectweb.asm.Label success = new org.objectweb.asm.Label();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitFieldInsn(Opcodes.GETFIELD, "fixture/ModelImageList", "fail", "Z");
        init.visitJumpInsn(Opcodes.IFEQ, success);
        init.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException");
        init.visitInsn(Opcodes.DUP);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "()V", false);
        init.visitInsn(Opcodes.ATHROW);
        init.visitLabel(success);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static final class FixtureLoader extends ClassLoader {
        private FixtureLoader(final ClassLoader parent) {
            super(parent);
        }

        private Class<?> define(final String name, final byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
