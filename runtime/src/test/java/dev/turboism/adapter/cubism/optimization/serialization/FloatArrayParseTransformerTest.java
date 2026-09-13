package dev.turboism.adapter.cubism.optimization.serialization;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import static org.junit.jupiter.api.Assertions.*;

class FloatArrayParseTransformerTest {
    @Test void executesCacheAndPreservesNativeFallbackAndFreshArrays() throws Exception {
        byte[] original = fixture(false);
        var loader = new FixtureLoader();
        var transformer = new FloatArrayParseTransformer(loader, null, original);
        byte[] changed = transformer.transform(null, loader, FloatArrayParseTransformer.TARGET, null, null, original);
        assertNotNull(changed);
        Class<?> type = loader.define(changed);
        Object instance = type.getConstructor().newInstance();
        var method = type.getMethod("a", int.class, List.class);
        String previous = System.getProperty(FloatArrayParseBridge.ENABLE_PROPERTY);
        try (var bridge = new FloatArrayParseBridge()) {
            System.setProperty(FloatArrayParseBridge.ENABLE_PROPERTY, "true");
            bridge.install();
            var tokens = List.of("1.25", "-0.0", "0x0.000002p-126");
            float[] first = (float[]) method.invoke(instance, 3, tokens);
            first[0] = 99;
            float[] second = (float[]) method.invoke(instance, 3, tokens);
            assertEquals(1.25f, second[0]);
            assertEquals(Float.floatToRawIntBits(-0.0f), Float.floatToRawIntBits(second[1]));
            assertEquals(Float.floatToRawIntBits(Float.MIN_VALUE), Float.floatToRawIntBits(second[2]));
            assertNotSame(first, second);
            assertEquals(3L, bridge.snapshot().get("hits").longValue());
            var invalid = assertThrows(InvocationTargetException.class, () -> method.invoke(instance, 1, List.of("bad")));
            assertInstanceOf(NumberFormatException.class, invalid.getCause());
            System.setProperty(FloatArrayParseBridge.ENABLE_PROPERTY, "false");
            assertArrayEquals(new float[]{2}, (float[]) method.invoke(instance, 1, List.of("2")));
            assertEquals(0L, bridge.snapshot().get("entries").longValue());
            Object callback = System.getProperties().get(FloatArrayParseBridge.CALLBACK_PROPERTY);
            for (BiFunction<Object, Object, Object> broken : List.<BiFunction<Object, Object, Object>>of(
                (a,b) -> new float[0], (a,b) -> "wrong type", (a,b) -> {throw new IllegalStateException("test callback");})) {
                System.getProperties().put(FloatArrayParseBridge.CALLBACK_PROPERTY, broken);
                assertArrayEquals(new float[]{3}, (float[]) method.invoke(instance, 1, List.of("3")));
            }
            System.getProperties().put(FloatArrayParseBridge.CALLBACK_PROPERTY, callback);
        } finally {
            if (previous == null) System.clearProperty(FloatArrayParseBridge.ENABLE_PROPERTY);
            else System.setProperty(FloatArrayParseBridge.ENABLE_PROPERTY, previous);
        }
        assertNull(System.getProperties().get(FloatArrayParseBridge.CALLBACK_PROPERTY));
        assertNull(System.getProperties().get(FloatArrayParseBridge.STATS_PROPERTY));
        assertArrayEquals(new float[]{4}, (float[]) method.invoke(instance, 1, List.of("4")));
    }

    @Test void rejectsChangedMethodBodyAndWrongLoader() {
        byte[] original = fixture(false);
        var loader = new FixtureLoader();
        var transformer = new FloatArrayParseTransformer(loader, null, original);
        assertNull(transformer.transform(null, new FixtureLoader(), FloatArrayParseTransformer.TARGET, null, null, original));
        assertNull(transformer.transform(null, loader, FloatArrayParseTransformer.TARGET, null, null, fixture(true)));
        assertNotNull(transformer.failure());
        assertEquals(0, transformer.matches());
    }

    @Test void bridgeRejectsOccupiedSlotsAndDoesNotRemoveAnotherOwnersCallback() {
        Object other = new Object();
        var properties = System.getProperties();
        try {
            properties.put(FloatArrayParseBridge.CALLBACK_PROPERTY, other);
            try (var bridge = new FloatArrayParseBridge()) {
                assertThrows(IllegalStateException.class, bridge::install);
            }
            assertSame(other, properties.get(FloatArrayParseBridge.CALLBACK_PROPERTY));
        } finally { properties.remove(FloatArrayParseBridge.CALLBACK_PROPERTY, other); }
        try (var bridge = new FloatArrayParseBridge()) {
            bridge.install();
            properties.put(FloatArrayParseBridge.CALLBACK_PROPERTY, other);
        }
        assertSame(other, properties.get(FloatArrayParseBridge.CALLBACK_PROPERTY));
        properties.remove(FloatArrayParseBridge.CALLBACK_PROPERTY, other);
    }

    static byte[] fixture(boolean changed) {
        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, FloatArrayParseTransformer.TARGET, null, "java/lang/Object", null);
        var ctor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode(); ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN); ctor.visitMaxs(0,0); ctor.visitEnd();
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC, "a", "(ILjava/util/List;)Ljava/lang/Object;", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 2);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Objects", "requireNonNull", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        method.visitInsn(Opcodes.POP);
        method.visitVarInsn(Opcodes.ILOAD, 1); method.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_FLOAT); method.visitVarInsn(Opcodes.ASTORE, 3);
        method.visitInsn(Opcodes.ICONST_0); method.visitVarInsn(Opcodes.ISTORE, 4);
        Label loop = new Label(), end = new Label(); method.visitLabel(loop);
        method.visitVarInsn(Opcodes.ILOAD, 4); method.visitVarInsn(Opcodes.ILOAD, 1); method.visitJumpInsn(Opcodes.IF_ICMPGE, end);
        method.visitVarInsn(Opcodes.ALOAD, 3); method.visitVarInsn(Opcodes.ILOAD, 4);
        method.visitVarInsn(Opcodes.ALOAD, 2); method.visitVarInsn(Opcodes.ILOAD, 4);
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true);
        method.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String");
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Float", "parseFloat", "(Ljava/lang/String;)F", false);
        if (changed) {method.visitInsn(Opcodes.FCONST_1); method.visitInsn(Opcodes.FADD);}
        method.visitInsn(Opcodes.FASTORE); method.visitIincInsn(4, 1); method.visitJumpInsn(Opcodes.GOTO, loop);
        method.visitLabel(end); method.visitVarInsn(Opcodes.ALOAD, 3); method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0,0); method.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }

    private static final class FixtureLoader extends ClassLoader {
        Class<?> define(byte[] bytes) {return defineClass(FloatArrayParseTransformer.TARGET.replace('/','.'), bytes, 0, bytes.length);}
    }
}
