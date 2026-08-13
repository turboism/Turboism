package dev.turboism.ui.panel;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class FloatingFrameDisposeNativeMethodTransformerTest {

    @Test
    void notifiesAfterDisposeExactlyOnceOnTheExactTargetMethod() throws Exception {
        final FixtureLoader loader = new FixtureLoader();
        final FloatingFrameDisposeNativeMethodTransformer transformer = new FloatingFrameDisposeNativeMethodTransformer(
            "fixture/Frame",
            "disposeFrame",
            "()V",
            loader
        );

        assertNull(transformer.transform(null, getClass().getClassLoader(), "fixture/Frame", null, null, frameClass()));
        assertNull(transformer.transform(null, loader, "fixture/Other", null, null, frameClass()));

        final byte[] transformed = transformer.transform(
            null, loader, "fixture/Frame", null, null, frameClass()
        );
        assertNotNull(transformed);

        final Class<?> frameType = loader.define("fixture.Frame", transformed);
        final Object frame = frameType.getConstructor().newInstance();
        final List<Object> observed = new ArrayList<>();

        final AtomicReference<NativeFloatingFrameDisposeBridge.Handler> installed =
            new AtomicReference<>();
        final NativeFloatingFrameDisposeBridge.Handler handler = observed::add;
        NativeFloatingFrameDisposeBridge.install(handler);
        installed.set(handler);
        try {
            final Method dispose = frameType.getMethod("disposeFrame");
            dispose.invoke(frame);
        } finally {
            NativeFloatingFrameDisposeBridge.uninstall(installed.get());
        }

        assertEquals(1, observed.size());
        assertSame(frame, observed.get(0));
    }

    private static byte[] frameClass() {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "fixture/Frame", null, "java/lang/Object", null);

        final MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        final MethodVisitor dispose = writer.visitMethod(
            Opcodes.ACC_PUBLIC, "disposeFrame", "()V", null, null
        );
        dispose.visitCode();
        dispose.visitInsn(Opcodes.RETURN);
        dispose.visitMaxs(0, 0);
        dispose.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static final class FixtureLoader extends ClassLoader {
        private FixtureLoader() {
            super(FloatingFrameDisposeNativeMethodTransformerTest.class.getClassLoader());
        }

        private Class<?> define(final String name, final byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
