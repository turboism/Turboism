package dev.turboism.adapter.cubism.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the injected dispatch guard against a synthetic host class.
 *
 * <p>The fixture's {@code private final void a(String, Object)} stands in for
 * {@code l.a(String, WebSocket)}: it records the raw message into its own static counters so
 * the test can prove whether the native body ran. The transformer is pointed at the fixture's
 * owner name and a {@code (String, Object)V} descriptor; the installer pins the real
 * {@code (String, WebSocket)V} pair.</p>
 */
class EditApiDispatchTransformerTest {

    private static final String KEY = "test.integration.edit-protocol.dispatch";
    private static final String OWNER = "fixture/DispatchEntry";
    private static final String NAME = "a";
    private static final String DESCRIPTOR = "(Ljava/lang/String;Ljava/lang/Object;)V";
    private static final int ACCESS = Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL;

    @AfterEach
    void clearReceiver() {
        System.getProperties().remove(KEY);
    }

    @Test
    void aTrueAnswerSkipsTheNativeBody() throws Exception {
        final List<Object[]> seen = new ArrayList<>();
        System.getProperties().put(KEY,
            (BiFunction<Object, Object, Object>) (raw, ws) -> {
                seen.add(new Object[]{raw, ws});
                return Boolean.TRUE;
            });

        final Object socket = new Object();
        final Class<?> type = loadTransformed(ACCESS, DESCRIPTOR);
        invoke(type, "{\"Method\":\"EditBegin\"}", socket);

        assertEquals(1, seen.size());
        assertEquals("{\"Method\":\"EditBegin\"}", seen.get(0)[0]);
        assertEquals(socket, seen.get(0)[1]);
        assertEquals(0, fixtureCalls(type), "a claimed message must not reach the native body");
    }

    @Test
    void aFalseAnswerRunsTheNativeBody() throws Exception {
        System.getProperties().put(KEY,
            (BiFunction<Object, Object, Object>) (raw, ws) -> Boolean.FALSE);

        final Class<?> type = loadTransformed(ACCESS, DESCRIPTOR);
        invoke(type, "{\"Method\":\"GetParameters\"}", new Object());

        assertEquals(1, fixtureCalls(type));
        assertEquals("{\"Method\":\"GetParameters\"}", fixtureMessages(type).get(0));
    }

    @Test
    void missingForeignOrFailingReceiversLeaveTheNativeBodyUntouched() throws Exception {
        final Class<?> type = loadTransformed(ACCESS, DESCRIPTOR);

        invoke(type, "m1", new Object());
        assertEquals(1, fixtureCalls(type), "no receiver -> native");

        System.getProperties().put(KEY, "not a bifunction");
        invoke(type, "m2", new Object());
        assertEquals(2, fixtureCalls(type), "foreign receiver -> native");

        System.getProperties().put(KEY,
            (BiFunction<Object, Object, Object>) (raw, ws) -> "not a boolean");
        invoke(type, "m3", new Object());
        assertEquals(3, fixtureCalls(type), "non-Boolean result -> native");

        System.getProperties().put(KEY,
            (BiFunction<Object, Object, Object>) (raw, ws) -> {
                throw new AssertionError("receiver failure");
            });
        invoke(type, "m4", new Object());
        assertEquals(4, fixtureCalls(type), "a receiver failure must not reach the host");
    }

    @Test
    void anotherClassDescriptorOrLoaderIsLeftAlone() {
        final List<Object[]> seen = new ArrayList<>();
        System.getProperties().put(KEY,
            (BiFunction<Object, Object, Object>) (raw, ws) -> {
                seen.add(new Object[]{raw, ws});
                return Boolean.TRUE;
            });
        final EditApiDispatchTransformer transformer = transformer();

        assertNull(transformer.transform(null, null, "fixture/Other", null, null,
            fixtureClass(ACCESS, DESCRIPTOR)), "another class must not be transformed");
        assertNull(transformer.transform(null, null, OWNER, null, null,
            fixtureClass(ACCESS, "(Ljava/lang/String;)V")), "another descriptor is refused");
        assertNull(transformer.transform(null, null, OWNER, null, null, null),
            "null bytes are refused");

        final ClassLoader hostLoader = new ClassLoader() { };
        final EditApiDispatchTransformer bound = new EditApiDispatchTransformer(
            OWNER, NAME, DESCRIPTOR, hostLoader, KEY);
        assertNull(bound.transform(null, new ClassLoader() { }, OWNER, null, null,
            fixtureClass(ACCESS, DESCRIPTOR)), "another loader must not be transformed");
        assertNotNull(bound.transform(null, hostLoader, OWNER, null, null,
            fixtureClass(ACCESS, DESCRIPTOR)), "the bound loader is transformed");
        assertTrue(seen.isEmpty(), "no method ran, so nothing was reported");
    }

    @Test
    void aMismatchedShapeIsRejectedNotPatched() {
        // public instead of private final
        final EditApiDispatchTransformer publicShape = transformer();
        assertNull(publicShape.transform(null, null, OWNER, null, null,
            fixtureClass(Opcodes.ACC_PUBLIC, DESCRIPTOR)));
        assertEquals(EditApiDispatchTransformer.Outcome.SHAPE_REJECTED, publicShape.outcome());
        // static is forbidden outright
        final EditApiDispatchTransformer staticShape = transformer();
        assertNull(staticShape.transform(null, null, OWNER, null, null,
            fixtureClass(ACCESS | Opcodes.ACC_STATIC, DESCRIPTOR)));
        assertEquals(EditApiDispatchTransformer.Outcome.SHAPE_REJECTED, staticShape.outcome());
        // no matching method at all
        final EditApiDispatchTransformer missing = transformer();
        assertNull(missing.transform(null, null, OWNER, null, null,
            fixtureClass(ACCESS, DESCRIPTOR, false)));
        assertEquals(EditApiDispatchTransformer.Outcome.SHAPE_REJECTED, missing.outcome());
    }

    @Test
    void theTransformerRefusesADescriptorThatCannotCarryTheDispatchArguments() {
        assertThrows(IllegalArgumentException.class,
            () -> new EditApiDispatchTransformer(OWNER, NAME, "()V", null, KEY));
        assertThrows(IllegalArgumentException.class,
            () -> new EditApiDispatchTransformer(OWNER, NAME,
                "(Ljava/lang/Object;Ljava/lang/Object;)V", null, KEY));
    }

    @Test
    void patchingReportsOutcome() {
        final EditApiDispatchTransformer transformer = transformer();
        final byte[] result = transformer.transform(null, null, OWNER, null, null,
            fixtureClass(ACCESS, DESCRIPTOR));
        assertNotNull(result);
        assertEquals(EditApiDispatchTransformer.Outcome.PATCHED, transformer.outcome());
    }

    private static EditApiDispatchTransformer transformer() {
        return new EditApiDispatchTransformer(OWNER, NAME, DESCRIPTOR, null, KEY);
    }

    private static void invoke(final Class<?> type, final String message, final Object socket)
        throws Exception {
        final java.lang.reflect.Method method =
            type.getDeclaredMethod(NAME, String.class, Object.class);
        method.setAccessible(true);
        method.invoke(type.getDeclaredConstructor().newInstance(), message, socket);
    }

    private static Class<?> loadTransformed(final int access, final String descriptor) {
        final byte[] bytes = transformer().transform(null, null, OWNER, null, null,
            fixtureClass(access, descriptor));
        assertNotNull(bytes, "fixture must be patched");
        final class Loader extends ClassLoader {
            Class<?> define() {
                return defineClass(OWNER.replace('/', '.'), bytes, 0, bytes.length);
            }
        }
        return new Loader().define();
    }

    private static int fixtureCalls(final Class<?> type) throws Exception {
        return ((AtomicInteger) type.getField("nativeCalls").get(null)).get();
    }

    @SuppressWarnings("unchecked")
    private static List<String> fixtureMessages(final Class<?> type) throws Exception {
        return (List<String>) type.getField("nativeMessages").get(null);
    }

    private static byte[] fixtureClass(final int access, final String descriptor) {
        return fixtureClass(access, descriptor, true);
    }

    /**
     * Generates {@code fixture.DispatchEntry} with an optional private
     * {@code a(String, Object)} that counts invocations, plus a public {@code b(String)} so a
     * wrong-shape member exists in the same class.
     */
    private static byte[] fixtureClass(
        final int access,
        final String descriptor,
        final boolean includeTarget
    ) {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, OWNER, null, "java/lang/Object", null);

        final MethodVisitor ctor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V",
            null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
            "nativeCalls", "Ljava/util/concurrent/atomic/AtomicInteger;", null, null);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
            "nativeMessages", "Ljava/util/List;", null, null);

        final MethodVisitor clinit = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>",
            "()V", null, null);
        clinit.visitCode();
        clinit.visitTypeInsn(Opcodes.NEW, "java/util/concurrent/atomic/AtomicInteger");
        clinit.visitInsn(Opcodes.DUP);
        clinit.visitMethodInsn(Opcodes.INVOKESPECIAL,
            "java/util/concurrent/atomic/AtomicInteger", "<init>", "()V", false);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, OWNER,
            "nativeCalls", "Ljava/util/concurrent/atomic/AtomicInteger;");
        clinit.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList");
        clinit.visitInsn(Opcodes.DUP);
        clinit.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/ArrayList",
            "<init>", "()V", false);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, OWNER,
            "nativeMessages", "Ljava/util/List;");
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();

        if (includeTarget) {
            final MethodVisitor method = writer.visitMethod(access, NAME, descriptor,
                null, null);
            method.visitCode();
            // native body: nativeCalls.incrementAndGet(); nativeMessages.add(arg1)
            method.visitFieldInsn(Opcodes.GETSTATIC, OWNER,
                "nativeCalls", "Ljava/util/concurrent/atomic/AtomicInteger;");
            method.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "java/util/concurrent/atomic/AtomicInteger", "incrementAndGet", "()I", false);
            method.visitInsn(Opcodes.POP);
            method.visitFieldInsn(Opcodes.GETSTATIC, OWNER,
                "nativeMessages", "Ljava/util/List;");
            method.visitVarInsn(Opcodes.ALOAD, 1);
            method.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "add",
                "(Ljava/lang/Object;)Z", true);
            method.visitInsn(Opcodes.POP);
            method.visitInsn(Opcodes.RETURN);
            method.visitMaxs(0, 0);
            method.visitEnd();
        }

        final MethodVisitor other = writer.visitMethod(Opcodes.ACC_PUBLIC, "b",
            "(Ljava/lang/String;)V", null, null);
        other.visitCode();
        other.visitInsn(Opcodes.RETURN);
        other.visitMaxs(0, 0);
        other.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }
}
