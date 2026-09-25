package dev.turboism.adapter.cubism.integration;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bytecode-contract tests for {@link NativeEditToggleShowTransformer}: an ASM-crafted
 * fixture mirroring {@code y.b(owner)}'s shape (private final instance method with two
 * return paths) proves the epilogue fires the published {@link Runnable} on every exit,
 * stays silent without an ingress, and never lets a throwing ingress break the host body.
 */
class NativeEditToggleShowTransformerTest {

    private static final String OWNER = "fixture/ExternalAppSettingsDialog";
    private static final String DESCRIPTOR = "(Ljava/lang/Object;)V";
    private static final String KEY = "test.edit-toggle.show-ingress";

    @Test
    void theIngressRunsWhenTheBuildMethodReturns() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        System.getProperties().put(KEY, (Runnable) calls::incrementAndGet);
        try {
            final Class<?> type = transformedFixture();
            invokeBuild(type, false);
            assertEquals(1, calls.get());
            invokeBuild(type, true);
            assertEquals(2, calls.get(), "every return path fires the ingress");
        } finally {
            System.getProperties().remove(KEY);
        }
    }

    @Test
    void aMissingIngressLeavesTheMethodWorking() throws Exception {
        invokeBuild(transformedFixture(), false);  // must simply complete
    }

    @Test
    void aThrowingIngressCannotBreakTheHostMethod() throws Exception {
        System.getProperties().put(KEY, (Runnable) () -> {
            throw new IllegalStateException("boom");
        });
        try {
            invokeBuild(transformedFixture(), false);
        } finally {
            System.getProperties().remove(KEY);
        }
    }

    @Test
    void nonRunnablePropertyValuesAreIgnored() throws Exception {
        System.getProperties().put(KEY, "not-a-runnable");
        try {
            invokeBuild(transformedFixture(), false);
        } finally {
            System.getProperties().remove(KEY);
        }
    }

    @Test
    void theExactMethodShapeIsRequired() {
        final NativeEditToggleShowTransformer transformer = transformer(KEY);

        // Static method: refused — not the reviewed shape.
        assertNull(transformer.transform(null, null, OWNER, null, null,
            fixtureClass(Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)));
        assertEquals(NativeEditToggleShowTransformer.Outcome.SHAPE_REJECTED,
            transformer.outcome());
        assertTrue(transformer.diagnostic().contains("patched=false"));
    }

    @Test
    void unrelatedClassesAndMethodsPassThrough() {
        final NativeEditToggleShowTransformer transformer = transformer(KEY);
        assertNull(transformer.transform(null, null, "fixture/SomethingElse",
            null, null, fixtureClass(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL)));
    }

    private static NativeEditToggleShowTransformer transformer(final String key) {
        return new NativeEditToggleShowTransformer(OWNER, "b", DESCRIPTOR, null, key);
    }

    private static Class<?> transformedFixture() throws Exception {
        final byte[] transformed = transformer(KEY).transform(
            null, null, OWNER, null, null,
            fixtureClass(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL));
        assertNotNull(transformed, "the exact method shape must be patched");
        return new FixtureLoader().define(OWNER.replace('/', '.'), transformed);
    }

    private static void invokeBuild(final Class<?> type, final boolean branch)
        throws Exception {
        type.getField("flag").setBoolean(null, branch);
        final Object instance = type.getDeclaredConstructor().newInstance();
        final java.lang.reflect.Method build =
            type.getDeclaredMethod("b", Object.class);
        build.setAccessible(true);
        build.invoke(instance, new Object());
    }

    /**
     * {@code private final void b(Object owner)} with two RETURN paths driven by a static
     * flag — mirrors {@code y.b}'s private-final multi-exit shape.
     */
    private static byte[] fixtureClass(final int access) {
        final ClassWriter writer = new ClassWriter(
            ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, OWNER, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "flag", "Z",
            null, null).visitEnd();
        final MethodVisitor init = writer.visitMethod(
            Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        final MethodVisitor method = writer.visitMethod(
            access, "b", DESCRIPTOR, null, null);
        method.visitCode();
        method.visitFieldInsn(Opcodes.GETSTATIC, OWNER, "flag", "Z");
        final Label tail = new Label();
        method.visitJumpInsn(Opcodes.IFEQ, tail);
        method.visitInsn(Opcodes.RETURN);
        method.visitLabel(tail);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static final class FixtureLoader extends ClassLoader {
        private Class<?> define(final String name, final byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
