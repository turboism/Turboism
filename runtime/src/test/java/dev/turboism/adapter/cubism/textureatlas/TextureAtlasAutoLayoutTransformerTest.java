package dev.turboism.adapter.cubism.textureatlas;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextureAtlasAutoLayoutTransformerTest {

    @Test
    void validationObserversDistinguishNativeBodyEntryFromHandledReturn() throws Exception {
        final String callbackKey = "test.texture-atlas.auto-layout.callback";
        final String handledReturnKey = "test.texture-atlas.auto-layout.handled-return";
        final String nativeBodyEntryKey = "test.texture-atlas.auto-layout.native-body-entry";
        final String completionKey = "test.texture-atlas.auto-layout.native-completion";
        final TextureAtlasAutoLayoutTransformer transformer = new TextureAtlasAutoLayoutTransformer(
            "fixture/AutoLayout",
            "a",
            "(Ljava/lang/Object;)Z",
            null,
            callbackKey,
            handledReturnKey,
            nativeBodyEntryKey,
            completionKey
        );
        final byte[] transformed = transformer.transform(
            null, null, "fixture/AutoLayout", null, null, fixtureClass()
        );
        final FixtureLoader loader = new FixtureLoader(null);
        final Class<?> type = loader.define("fixture.AutoLayout", transformed);
        final Object instance = type.getConstructor().newInstance();
        final AtomicInteger handledReturns = new AtomicInteger();
        final AtomicInteger entries = new AtomicInteger();
        final AtomicInteger completions = new AtomicInteger();
        System.getProperties().put(
            handledReturnKey,
            (Consumer<Object>) target -> handledReturns.incrementAndGet()
        );
        System.getProperties().put(
            nativeBodyEntryKey,
            (Consumer<Object>) target -> entries.incrementAndGet()
        );
        System.getProperties().put(
            completionKey,
            (Consumer<Object>) target -> completions.incrementAndGet()
        );
        try {
            assertFalse((Boolean) type.getMethod("a", Object.class).invoke(instance, new Object()));
            assertEquals(0, handledReturns.get());
            assertEquals(1, entries.get());
            assertEquals(1, completions.get());
            System.getProperties().put(callbackKey, (Predicate<Object>) target -> true);
            assertTrue((Boolean) type.getMethod("a", Object.class).invoke(instance, new Object()));
            assertEquals(1, handledReturns.get());
            assertEquals(1, entries.get());
            assertEquals(1, completions.get());
            System.getProperties().put(callbackKey, (Predicate<Object>) target -> false);
            assertFalse((Boolean) type.getMethod("a", Object.class).invoke(instance, new Object()));
            assertEquals(1, handledReturns.get());
            assertEquals(2, entries.get());
            assertEquals(2, completions.get());
            System.getProperties().put(callbackKey, (Predicate<Object>) target -> {
                throw new AssertionError("validation ingress failure");
            });
            assertFalse((Boolean) type.getMethod("a", Object.class).invoke(instance, new Object()));
            assertEquals(1, handledReturns.get());
            assertEquals(3, entries.get());
            assertEquals(3, completions.get());
        } finally {
            System.getProperties().remove(callbackKey);
            System.getProperties().remove(handledReturnKey);
            System.getProperties().remove(nativeBodyEntryKey);
            System.getProperties().remove(completionKey);
        }
    }

    @Test
    void validationCompletionIngressObservesOnlyOriginalNativeReturns() throws Exception {
        final String callbackKey = "test.texture-atlas.auto-layout.callback";
        final String completionKey = "test.texture-atlas.auto-layout.native-completion";
        final TextureAtlasAutoLayoutTransformer transformer = new TextureAtlasAutoLayoutTransformer(
            "fixture/AutoLayout",
            "a",
            "(Ljava/lang/Object;)Z",
            null,
            callbackKey,
            completionKey
        );
        final byte[] transformed = transformer.transform(
            null, null, "fixture/AutoLayout", null, null, fixtureClass()
        );
        final FixtureLoader loader = new FixtureLoader(null);
        final Class<?> type = loader.define("fixture.AutoLayout", transformed);
        final Object instance = type.getConstructor().newInstance();
        final AtomicInteger completions = new AtomicInteger();
        final AtomicReference<Object> receiver = new AtomicReference<>();
        System.getProperties().put(completionKey, (Consumer<Object>) target -> {
            completions.incrementAndGet();
            receiver.set(target);
        });
        try {
            assertFalse((Boolean) type.getMethod("a", Object.class).invoke(instance, new Object()));
            assertEquals(1, completions.get());
            assertSame(instance, receiver.get());
            System.getProperties().put(callbackKey, (Predicate<Object>) target -> true);
            assertTrue((Boolean) type.getMethod("a", Object.class).invoke(instance, new Object()));
            assertEquals(1, completions.get());
            System.getProperties().put(callbackKey, (Predicate<Object>) target -> false);
            assertFalse((Boolean) type.getMethod("a", Object.class).invoke(instance, new Object()));
            assertEquals(2, completions.get());
        } finally {
            System.getProperties().remove(callbackKey);
            System.getProperties().remove(completionKey);
        }
    }

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
