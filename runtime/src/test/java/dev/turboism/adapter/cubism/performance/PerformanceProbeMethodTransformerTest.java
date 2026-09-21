package dev.turboism.adapter.cubism.performance;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PerformanceProbeMethodTransformerTest {

    @Test
    void recordsCallsForTheExactMethodAndTimesNormalCompletionOnly() throws Exception {
        final PerformanceProbeRecorder recorder = new PerformanceProbeRecorder();
        installCarrier(recorder);
        recorder.startCapture();
        try {
            final PerformanceProbeMethodTransformer transformer = new PerformanceProbeMethodTransformer(
                null,
                null,
                List.of(new PerformanceProbeMethodTransformer.Target(
                    "fixture/RenderTarget",
                    "render",
                    "(Z)V",
                    PerformanceProbeMetric.RENDER_SCENE
                ))
            );
            final byte[] transformed = transformer.transform(
                null, null, "fixture/RenderTarget", null, null, fixtureClass()
            );
            assertNotNull(transformed);
            final Class<?> type = new FixtureLoader().define("fixture.RenderTarget", transformed);
            final Object instance = type.getConstructor().newInstance();

            type.getMethod("render", boolean.class).invoke(instance, false);
            assertThrows(InvocationTargetException.class, () ->
                type.getMethod("render", boolean.class).invoke(instance, true)
            );
            type.getMethod("other").invoke(instance);

            final PerformanceProbeRecorder.MetricSnapshot snapshot = recorder.snapshot()
                .metrics().get(PerformanceProbeMetric.RENDER_SCENE);
            assertEquals(2L, snapshot.calls());
            assertEquals(2L, snapshot.sampled());
        } finally {
            recorder.stopCapture();
            clearCarrier();
        }
    }

    @Test
    void verifiesCaughtExceptionAndControlFlowJoin() throws Exception {
        final PerformanceProbeRecorder recorder = new PerformanceProbeRecorder();
        installCarrier(recorder);
        recorder.startCapture();
        try {
            final PerformanceProbeMethodTransformer transformer = new PerformanceProbeMethodTransformer(
                null,
                null,
                List.of(new PerformanceProbeMethodTransformer.Target(
                    "fixture/CaughtTarget", "render", "(Z)V", PerformanceProbeMetric.RENDER_SCENE
                ))
            );
            final byte[] transformed = transformer.transform(
                null, null, "fixture/CaughtTarget", null, null, caughtFixtureClass()
            );
            final Class<?> type = new FixtureLoader().define("fixture.CaughtTarget", transformed);
            final Object instance = type.getConstructor().newInstance();
            type.getMethod("render", boolean.class).invoke(instance, false);
            type.getMethod("render", boolean.class).invoke(instance, true);
            assertEquals(2L, recorder.snapshot().metrics().get(PerformanceProbeMetric.RENDER_SCENE).calls());
        } finally {
            recorder.stopCapture();
            clearCarrier();
        }
    }

    @Test
    void preservesReferenceAndPrimitiveResultsAndAccountsForExceptionalExit() throws Exception {
        final Object[][] cases = {
            {"Ljava/lang/String;", "decoded", Opcodes.ARETURN},
            {"I", 17, Opcodes.IRETURN},
            {"J", 1234567890123L, Opcodes.LRETURN},
            {"F", 1.25F, Opcodes.FRETURN},
            {"D", 2.5D, Opcodes.DRETURN}
        };
        for (Object[] value : cases) {
            final PerformanceProbeRecorder recorder = new PerformanceProbeRecorder();
            installCarrier(recorder);
            recorder.startCapture();
            try {
                final String descriptor = "(Z)" + value[0];
                final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
                writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "fixture/ValueTarget", null, "java/lang/Object", null);
                constructor(writer);
                final MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "read", descriptor, null, null);
                method.visitCode();
                final org.objectweb.asm.Label normal = new org.objectweb.asm.Label();
                method.visitVarInsn(Opcodes.ILOAD, 1);
                method.visitJumpInsn(Opcodes.IFEQ, normal);
                method.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException");
                method.visitInsn(Opcodes.DUP);
                method.visitLdcInsn("original failure");
                method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>",
                    "(Ljava/lang/String;)V", false);
                method.visitInsn(Opcodes.ATHROW);
                method.visitLabel(normal);
                method.visitLdcInsn(value[1]);
                method.visitInsn((Integer) value[2]);
                method.visitMaxs(0, 0);
                method.visitEnd();
                writer.visitEnd();
                final var target = new PerformanceProbeMethodTransformer.Target(
                    "fixture/ValueTarget", "read", descriptor, PerformanceProbeMetric.RENDER_SCENE);
                final var transformer = new PerformanceProbeMethodTransformer(null, null, List.of(target));
                final byte[] bytes = transformer.transform(null, null, "fixture/ValueTarget", null, null, writer.toByteArray());
                final Class<?> type = new FixtureLoader().define("fixture.ValueTarget", bytes);
                final Object instance = type.getConstructor().newInstance();
                assertEquals(value[1], type.getMethod("read", boolean.class).invoke(instance, false));
                final InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                    () -> type.getMethod("read", boolean.class).invoke(instance, true));
                assertEquals("original failure", failure.getCause().getMessage());
                recorder.stopCapture();
                org.junit.jupiter.api.Assertions.assertTrue(recorder.awaitQuiescence(100));
                assertEquals(2L, recorder.snapshot().metrics().get(PerformanceProbeMetric.RENDER_SCENE).calls());
            } finally {
                recorder.stopCapture();
                clearCarrier();
            }
        }
    }

    private static void installCarrier(final PerformanceProbeRecorder recorder) throws Exception {
        final Class<?> callback = Class.forName("dev.turboism.bootstrap.carrier.PerformanceProbeCallback");
        final Object proxy = Proxy.newProxyInstance(callback.getClassLoader(), new Class<?>[]{callback}, (ignored, method, args) -> {
            if (method.getName().equals("enter")) {
                return NativePerformanceProbeBridge.enter(recorder, (int) args[0]);
            }
            NativePerformanceProbeBridge.exit(recorder, (int) args[0], (long) args[1]);
            return null;
        });
        Class.forName("dev.turboism.bootstrap.carrier.PerformanceProbeCarrier")
            .getMethod("install", callback).invoke(null, proxy);
        CallbackHolder.callback = proxy;
        Class.forName("dev.turboism.bootstrap.carrier.PerformanceProbeCarrier")
            .getMethod("enable", long.class).invoke(null, ~0L);
    }

    private static void clearCarrier() throws Exception {
        final Class<?> callback = Class.forName("dev.turboism.bootstrap.carrier.PerformanceProbeCallback");
        Class.forName("dev.turboism.bootstrap.carrier.PerformanceProbeCarrier")
            .getMethod("clear", callback).invoke(null, CallbackHolder.callback);
        CallbackHolder.callback = null;
    }

    private static final class CallbackHolder {
        private static Object callback;
    }

    private static byte[] fixtureClass() {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "fixture/RenderTarget", null, "java/lang/Object", null);
        constructor(writer);
        final MethodVisitor render = writer.visitMethod(Opcodes.ACC_PUBLIC, "render", "(Z)V", null, null);
        render.visitCode();
        final org.objectweb.asm.Label normal = new org.objectweb.asm.Label();
        render.visitVarInsn(Opcodes.ILOAD, 1);
        render.visitJumpInsn(Opcodes.IFEQ, normal);
        render.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException");
        render.visitInsn(Opcodes.DUP);
        render.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "()V", false);
        render.visitInsn(Opcodes.ATHROW);
        render.visitLabel(normal);
        render.visitInsn(Opcodes.RETURN);
        render.visitMaxs(0, 0);
        render.visitEnd();
        final MethodVisitor other = writer.visitMethod(Opcodes.ACC_PUBLIC, "other", "()V", null, null);
        other.visitCode();
        other.visitInsn(Opcodes.RETURN);
        other.visitMaxs(0, 0);
        other.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] caughtFixtureClass() {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "fixture/CaughtTarget", null, "java/lang/Object", null);
        constructor(writer);
        final MethodVisitor render = writer.visitMethod(Opcodes.ACC_PUBLIC, "render", "(Z)V", null, null);
        final org.objectweb.asm.Label start = new org.objectweb.asm.Label();
        final org.objectweb.asm.Label end = new org.objectweb.asm.Label();
        final org.objectweb.asm.Label handler = new org.objectweb.asm.Label();
        final org.objectweb.asm.Label done = new org.objectweb.asm.Label();
        render.visitCode();
        render.visitTryCatchBlock(start, end, handler, "java/lang/RuntimeException");
        render.visitLabel(start);
        render.visitVarInsn(Opcodes.ILOAD, 1);
        render.visitJumpInsn(Opcodes.IFEQ, end);
        render.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException");
        render.visitInsn(Opcodes.DUP);
        render.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "()V", false);
        render.visitInsn(Opcodes.ATHROW);
        render.visitLabel(end);
        render.visitJumpInsn(Opcodes.GOTO, done);
        render.visitLabel(handler);
        render.visitInsn(Opcodes.POP);
        render.visitLabel(done);
        render.visitInsn(Opcodes.RETURN);
        render.visitMaxs(0, 0);
        render.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void constructor(final ClassWriter writer) {
        final MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
    }

    private static final class FixtureLoader extends ClassLoader {
        private Class<?> define(final String name, final byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
