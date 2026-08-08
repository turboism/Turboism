package dev.turboism.ui.panel;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class FloatingTabCloseNativeMethodTransformerTest {

    @Test
    void interceptsCloseWhenBridgeRequestsItAndPassesThroughOtherwise() throws Exception {
        final FixtureLoader loader = new FixtureLoader();
        final FloatingTabCloseNativeMethodTransformer transformer = new FloatingTabCloseNativeMethodTransformer(
            "fixture/TabClose",
            "a",
            "(Ljava/lang/Object;)V",
            "palette",
            "Ljava/lang/Object;",
            loader
        );

        assertNull(transformer.transform(null, getClass().getClassLoader(), "fixture/TabClose", null, null, closeClass()));
        assertNull(transformer.transform(null, loader, "fixture/Other", null, null, closeClass()));

        final byte[] transformed = transformer.transform(
            null, loader, "fixture/TabClose", null, null, closeClass()
        );
        assertNotNull(transformed);

        final Class<?> closeType = loader.define("fixture.TabClose", transformed);
        final Object palette = new Object();
        final Object callback = closeType.getConstructor(Object.class).newInstance(palette);

        // 1. No handler installed: native close proceeds (close counter incremented).
        final Method close = closeType.getMethod("a", Object.class);
        close.invoke(callback, new Object());
        assertEquals(1, closeType.getField("closed").getInt(null));

        // 2. Handler returns true: native close cancelled.
        final AtomicReference<NativeFloatingTabCloseBridge.Handler> installed = new AtomicReference<>();
        final NativeFloatingTabCloseBridge.Handler cancel = observed -> {
            assertSame(palette, observed);
            return true;
        };
        NativeFloatingTabCloseBridge.install(cancel);
        installed.set(cancel);
        try {
            close.invoke(callback, new Object());
        } finally {
            NativeFloatingTabCloseBridge.uninstall(installed.get());
        }
        assertEquals(1, closeType.getField("closed").getInt(null));

        // 3. Handler returns false: native close proceeds again.
        final NativeFloatingTabCloseBridge.Handler pass = observed -> false;
        NativeFloatingTabCloseBridge.install(pass);
        installed.set(pass);
        try {
            close.invoke(callback, new Object());
        } finally {
            NativeFloatingTabCloseBridge.uninstall(installed.get());
        }
        assertEquals(2, closeType.getField("closed").getInt(null));
    }

    private static byte[] closeClass() {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "fixture/TabClose", null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "palette", "Ljava/lang/Object;", null, null)
            .visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "closed", "I", null, null).visitEnd();

        final MethodVisitor constructor = writer.visitMethod(
            Opcodes.ACC_PUBLIC, "<init>", "(Ljava/lang/Object;)V", null, null
        );
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitVarInsn(Opcodes.ALOAD, 1);
        constructor.visitFieldInsn(Opcodes.PUTFIELD, "fixture/TabClose", "palette", "Ljava/lang/Object;");
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        final MethodVisitor close = writer.visitMethod(
            Opcodes.ACC_PUBLIC, "a", "(Ljava/lang/Object;)V", null, null
        );
        close.visitCode();
        close.visitFieldInsn(Opcodes.GETSTATIC, "fixture/TabClose", "closed", "I");
        close.visitInsn(Opcodes.ICONST_1);
        close.visitInsn(Opcodes.IADD);
        close.visitFieldInsn(Opcodes.PUTSTATIC, "fixture/TabClose", "closed", "I");
        close.visitInsn(Opcodes.RETURN);
        close.visitMaxs(0, 0);
        close.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static final class FixtureLoader extends ClassLoader {
        private FixtureLoader() {
            super(FloatingTabCloseNativeMethodTransformerTest.class.getClassLoader());
        }

        private Class<?> define(final String name, final byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
