package dev.turboism.adapter.cubism.textureatlas;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextureAtlasAutoLayoutTransformerTest {

    @Test
    void returnsHandledOnlyWhenTheLoaderNeutralCallbackSucceeds() throws Exception {
        final String key = "test.texture-atlas.auto-layout.callback";
        final TextureAtlasAutoLayoutTransformer transformer = new TextureAtlasAutoLayoutTransformer(
            "fixture/AutoLayout",
            "a",
            "(Ljava/lang/Object;)Z",
            null,
            key
        );
        final byte[] transformed = transformer.transform(
            null, null, "fixture/AutoLayout", null, null, fixtureClass()
        );
        final FixtureLoader loader = new FixtureLoader(null);
        final Class<?> type = loader.define("fixture.AutoLayout", transformed);
        final Object instance = type.getConstructor().newInstance();

        try {
            assertFalse((Boolean) type.getMethod("a", Object.class).invoke(instance, new Object()));
            System.getProperties().put(key, (BooleanSupplier) () -> false);
            assertFalse((Boolean) type.getMethod("a", Object.class).invoke(instance, new Object()));
            System.getProperties().put(key, (BooleanSupplier) () -> true);
            assertTrue((Boolean) type.getMethod("a", Object.class).invoke(instance, new Object()));
            System.getProperties().put(key, (BooleanSupplier) () -> {
                throw new AssertionError("callback failure");
            });
            assertFalse((Boolean) type.getMethod("a", Object.class).invoke(instance, new Object()));
            assertFalse(java.util.Arrays.toString(transformed).contains("dev/turboism"));
        } finally {
            System.getProperties().remove(key);
        }
    }

    private static byte[] fixtureClass() {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "fixture/AutoLayout", null, "java/lang/Object", null);
        final MethodVisitor constructor = writer.visitMethod(
            Opcodes.ACC_PUBLIC, "<init>", "()V", null, null
        );
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        final MethodVisitor method = writer.visitMethod(
            Opcodes.ACC_PUBLIC, "a", "(Ljava/lang/Object;)Z", null, null
        );
        method.visitCode();
        method.visitInsn(Opcodes.ICONST_0);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
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
