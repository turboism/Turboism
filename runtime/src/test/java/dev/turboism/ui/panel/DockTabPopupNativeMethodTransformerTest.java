package dev.turboism.ui.panel;

import dev.turboism.sdk.plugin.Registration;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class DockTabPopupNativeMethodTransformerTest {

    @Test
    void augmentsOnlyTheExactPopupAppendPointAfterTheNativeItemWasAdded() throws Exception {
        final FixtureLoader loader = new FixtureLoader();
        final DockTabPopupNativeMethodTransformer transformer = new DockTabPopupNativeMethodTransformer(
            "fixture/Popup",
            "open",
            "(Ljava/lang/Object;)V",
            loader,
            "fixture/Menu",
            "append",
            "(Ljava/lang/Object;)V",
            "palette",
            "Ljava/lang/Object;"
        );

        assertNull(transformer.transform(null, getClass().getClassLoader(), "fixture/Popup", null, null, popupClass()));
        assertNull(transformer.transform(null, loader, "fixture/Other", null, null, popupClass()));

        final byte[] transformed = transformer.transform(
            null, loader, "fixture/Popup", null, null, popupClass()
        );
        assertNotNull(transformed);

        final Class<?> menuType = loader.define("fixture.Menu", menuClass());
        final Class<?> popupType = loader.define("fixture.Popup", transformed);
        final Object palette = new Object();
        final Object popup = popupType.getConstructor(Object.class).newInstance(palette);
        final List<Object> observed = new ArrayList<>();

        try (Registration ignored = NativeDockTabPopupBridge.install((menu, actualPalette) -> {
            try {
                observed.add(menuType.getField("count").getInt(menu));
                observed.add(actualPalette);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(failure);
            }
        })) {
            final Method open = popupType.getMethod("open", Object.class);
            open.invoke(popup, new Object());
        }

        assertEquals(1, observed.get(0));
        assertSame(palette, observed.get(1));
    }

    private static byte[] popupClass() {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "fixture/Popup", null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "palette", "Ljava/lang/Object;", null, null)
            .visitEnd();

        final MethodVisitor constructor = writer.visitMethod(
            Opcodes.ACC_PUBLIC, "<init>", "(Ljava/lang/Object;)V", null, null
        );
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitVarInsn(Opcodes.ALOAD, 1);
        constructor.visitFieldInsn(Opcodes.PUTFIELD, "fixture/Popup", "palette", "Ljava/lang/Object;");
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        final MethodVisitor open = writer.visitMethod(
            Opcodes.ACC_PUBLIC, "open", "(Ljava/lang/Object;)V", null, null
        );
        open.visitCode();
        open.visitTypeInsn(Opcodes.NEW, "fixture/Menu");
        open.visitInsn(Opcodes.DUP);
        open.visitMethodInsn(Opcodes.INVOKESPECIAL, "fixture/Menu", "<init>", "()V", false);
        open.visitVarInsn(Opcodes.ASTORE, 2);
        open.visitVarInsn(Opcodes.ALOAD, 2);
        open.visitVarInsn(Opcodes.ALOAD, 1);
        open.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL, "fixture/Menu", "append", "(Ljava/lang/Object;)V", false
        );
        open.visitInsn(Opcodes.RETURN);
        open.visitMaxs(0, 0);
        open.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] menuClass() {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "fixture/Menu", null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC, "count", "I", null, null).visitEnd();

        final MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        final MethodVisitor append = writer.visitMethod(
            Opcodes.ACC_PUBLIC, "append", "(Ljava/lang/Object;)V", null, null
        );
        append.visitCode();
        append.visitVarInsn(Opcodes.ALOAD, 0);
        append.visitInsn(Opcodes.DUP);
        append.visitFieldInsn(Opcodes.GETFIELD, "fixture/Menu", "count", "I");
        append.visitInsn(Opcodes.ICONST_1);
        append.visitInsn(Opcodes.IADD);
        append.visitFieldInsn(Opcodes.PUTFIELD, "fixture/Menu", "count", "I");
        append.visitInsn(Opcodes.RETURN);
        append.visitMaxs(0, 0);
        append.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static final class FixtureLoader extends ClassLoader {
        private FixtureLoader() {
            super(DockTabPopupNativeMethodTransformerTest.class.getClassLoader());
        }

        private Class<?> define(final String name, final byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
