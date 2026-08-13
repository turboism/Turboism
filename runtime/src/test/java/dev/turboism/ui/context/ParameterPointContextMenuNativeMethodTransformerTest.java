package dev.turboism.ui.context;

import dev.turboism.sdk.plugin.Registration;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ParameterPointContextMenuNativeMethodTransformerTest {

    @Test
    void reportsExactCurrentContextAfterTheNativeShowMethod() throws Exception {
        final Loader loader = new Loader();
        final ParameterPointContextMenuNativeMethodTransformer transformer =
            new ParameterPointContextMenuNativeMethodTransformer(
                "fixture/Q", "a", "(Ljava/lang/Object;II)V", "i", loader
            );
        assertNull(transformer.transform(null, loader, "fixture/Q", null, null, qClass(2)));
        final byte[] transformed = transformer.transform(null, loader, "fixture/Q", null, null, qClass(1));
        assertNotNull(transformed);
        loader.define("com.live2d.ui.menu.k", menuClass());
        final Class<?> type = loader.define("fixture.Q", transformed);
        final Object q = type.getConstructor().newInstance();
        final Object context = new Object();
        final List<Object> observed = new ArrayList<>();
        try (Registration ignored = NativeParameterPointContextMenuBridge.install(
            (primary, secondary, actual) -> {
                observed.add(primary);
                observed.add(secondary);
                observed.add(actual);
            }
        )) {
            type.getMethod("a", Object.class, int.class, int.class).invoke(q, context, 1, 2);
        }
        assertSame(type.getMethod("i").invoke(null), observed.get(0));
        assertEquals(null, observed.get(1));
        assertSame(context, observed.get(2));
    }

    private static byte[] qClass(final int matchingMethods) {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "fixture/Q", null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
            "MENU", "Lcom/live2d/ui/menu/k;", null, null).visitEnd();
        final MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        final MethodVisitor staticInit = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        staticInit.visitCode();
        staticInit.visitTypeInsn(Opcodes.NEW, "com/live2d/ui/menu/k");
        staticInit.visitInsn(Opcodes.DUP);
        staticInit.visitMethodInsn(Opcodes.INVOKESPECIAL, "com/live2d/ui/menu/k", "<init>", "()V", false);
        staticInit.visitFieldInsn(Opcodes.PUTSTATIC, "fixture/Q", "MENU", "Lcom/live2d/ui/menu/k;");
        staticInit.visitInsn(Opcodes.RETURN);
        staticInit.visitMaxs(0, 0);
        staticInit.visitEnd();
        final MethodVisitor getter = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "i", "()Lcom/live2d/ui/menu/k;", null, null);
        getter.visitCode();
        getter.visitFieldInsn(Opcodes.GETSTATIC, "fixture/Q", "MENU", "Lcom/live2d/ui/menu/k;");
        getter.visitInsn(Opcodes.ARETURN);
        getter.visitMaxs(0, 0);
        getter.visitEnd();
        for (int index = 0; index < matchingMethods; index++) {
            final MethodVisitor show = writer.visitMethod(Opcodes.ACC_PUBLIC,
                "a", "(Ljava/lang/Object;II)V", null, null);
            show.visitCode();
            show.visitInsn(Opcodes.RETURN);
            show.visitMaxs(0, 0);
            show.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] menuClass() {
        final ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/live2d/ui/menu/k", null, "java/lang/Object", null);
        final MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static final class Loader extends ClassLoader {
        private Loader() { super(ParameterPointContextMenuNativeMethodTransformerTest.class.getClassLoader()); }
        private Class<?> define(final String name, final byte[] bytes) { return defineClass(name, bytes, 0, bytes.length); }
    }
}
