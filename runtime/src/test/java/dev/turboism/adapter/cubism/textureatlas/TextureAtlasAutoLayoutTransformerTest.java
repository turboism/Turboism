package dev.turboism.adapter.cubism.textureatlas;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextureAtlasAutoLayoutTransformerTest {

    @Test
    void passesTheReceiverToTheLoaderNeutralRuntimeIngressAndHandlesOnlyItsSuccess() throws Exception {
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

        final AtomicReference<Object> receiver = new AtomicReference<>();
        try {
            assertFalse((Boolean) type.getMethod("a", Object.class).invoke(instance, new Object()));
            assertTrue(type.getField("nativeCalls").getInt(null) == 1);
            System.getProperties().put(key, (Predicate<Object>) target -> {
                receiver.set(target);
                return false;
            });
            assertFalse((Boolean) type.getMethod("a", Object.class).invoke(instance, new Object()));
            assertTrue(type.getField("nativeCalls").getInt(null) == 2);
            assertTrue(receiver.get() == instance);
            System.getProperties().put(key, (Predicate<Object>) target -> {
                receiver.set(target);
                return true;
            });
            assertTrue((Boolean) type.getMethod("a", Object.class).invoke(instance, new Object()));
            assertTrue(type.getField("nativeCalls").getInt(null) == 2);
            assertTrue(receiver.get() == instance);
            System.getProperties().put(key, (Predicate<Object>) target -> {
                throw new AssertionError("runtime ingress failure");
            });
            assertFalse((Boolean) type.getMethod("a", Object.class).invoke(instance, new Object()));
            assertTrue(type.getField("nativeCalls").getInt(null) == 3);
            assertFalse(java.util.Arrays.toString(transformed).contains("dev/turboism"));
        } finally {
            System.getProperties().remove(key);
    }
    }

    private static byte[] fixtureClass() {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "fixture/AutoLayout", null, "java/lang/Object", null);
        writer.visitField(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "nativeCalls",
            "I",
            null,
            null
        ).visitEnd();
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
        method.visitFieldInsn(Opcodes.GETSTATIC, "fixture/AutoLayout", "nativeCalls", "I");
        method.visitInsn(Opcodes.ICONST_1);
        method.visitInsn(Opcodes.IADD);
        method.visitFieldInsn(Opcodes.PUTSTATIC, "fixture/AutoLayout", "nativeCalls", "I");
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
