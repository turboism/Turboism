package dev.turboism.adapter.cubism.editor.history;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the injected {@code beginEdit} entry against a synthetic native class.
 *
 * <p>The fixture returns its own argument so the return value is observable, and counts its own
 * invocations in a static field so the test can prove the injection neither replaced, skipped nor
 * duplicated the native body.</p>
 */
class NativeEditBeginTransformerTest {

    private static final String KEY = "test.editor-history.begin-edit.ingress";
    private static final String OWNER = "fixture/EditEntry";
    private static final String NAME = "beginEdit";
    private static final String DESCRIPTOR = "(Ljava/lang/String;)Ljava/lang/String;";

    @AfterEach
    void clearReceiver() {
        System.getProperties().remove(KEY);
        Fixture.reset();
    }

    @Test
    void theEntryReportsTheEditNameAndPreservesTheReturnValue() throws Exception {
        final List<String> observed = new ArrayList<>();
        System.getProperties().put(KEY, (Consumer<String>) observed::add);

        final Object returned = invoke(loadTransformed(), "Add Part");

        assertEquals("Add Part", returned, "the native return value must survive the injection");
        assertEquals(List.of("Add Part"), observed);
        assertEquals(1, Fixture.calls());
    }

    @Test
    void aReceiverThatThrowsCannotReachTheHostEditEntry() throws Exception {
        System.getProperties().put(KEY, (Consumer<String>) name -> {
            throw new AssertionError("receiver failure");
        });

        final Object returned = invoke(loadTransformed(), "Add Part");

        assertEquals("Add Part", returned, "a receiver failure must not change the edit entry");
        assertEquals(1, Fixture.calls());
    }

    @Test
    void aMissingOrForeignReceiverLeavesTheNativeBodyUntouched() throws Exception {
        final Class<?> type = loadTransformed();

        assertEquals("Add Part", invoke(type, "Add Part"));
        assertEquals(1, Fixture.calls());

        System.getProperties().put(KEY, "not a consumer");
        assertEquals("Add Part", invoke(type, "Add Part"));
        assertEquals(2, Fixture.calls());
    }

    @Test
    void anotherClassOrAnotherDescriptorIsLeftAlone() {
        Fixture.reset();
        final List<String> received = new ArrayList<>();
        System.getProperties().put(KEY, (Consumer<String>) received::add);
        final NativeEditBeginTransformer transformer = transformer(DESCRIPTOR);

        assertNull(
            transformer.transform(null, null, "fixture/Other", null, null, fixtureClass(DESCRIPTOR)),
            "another class must not be transformed"
        );
        assertNull(
            transformer.transform(null, null, OWNER, null, null, fixtureClass("(I)Ljava/lang/String;")),
            "another descriptor must not be transformed"
        );
        final ClassLoader hostLoader = new ClassLoader() { };
        final NativeEditBeginTransformer loaderBound = new NativeEditBeginTransformer(
            OWNER, NAME, DESCRIPTOR, hostLoader, KEY
        );
        assertNull(
            loaderBound.transform(
                null, new ClassLoader() { }, OWNER, null, null, fixtureClass(DESCRIPTOR)
            ),
            "another loader must not be transformed"
        );
        assertNotNull(
            loaderBound.transform(null, hostLoader, OWNER, null, null, fixtureClass(DESCRIPTOR)),
            "the bound loader must still be transformed"
        );
        assertTrue(received.isEmpty(), "no method was ever invoked, so no name was reported");
    }

    @Test
    void theTransformerRefusesADescriptorThatCannotCarryAnEditName() {
        assertThrows(
            IllegalArgumentException.class,
            () -> transformer("()Ljava/lang/String;")
        );
    }

    private static NativeEditBeginTransformer transformer(final String descriptor) {
        return new NativeEditBeginTransformer(OWNER, NAME, descriptor, null, KEY);
    }

    private static Object invoke(final Class<?> type, final String editName) throws Exception {
        return type.getMethod(NAME, String.class).invoke(type.getConstructor().newInstance(), editName);
    }

    private static Class<?> loadTransformed() {
        final byte[] transformed = transformer(DESCRIPTOR).transform(
            null, null, OWNER, null, null, fixtureClass(DESCRIPTOR)
        );
        assertNotNull(transformed, "the exact entry must be transformed");
        return new FixtureLoader().define("fixture.EditEntry", transformed);
    }

    private static byte[] fixtureClass(final String descriptor) {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, OWNER, null, "java/lang/Object", null);
        writer.visitField(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "calls", "I", null, null
        ).visitEnd();
        final MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        final MethodVisitor method = writer.visitMethod(
            Opcodes.ACC_PUBLIC, NAME, descriptor, null, null
        );
        method.visitCode();
        method.visitFieldInsn(Opcodes.GETSTATIC, OWNER, "calls", "I");
        method.visitInsn(Opcodes.ICONST_1);
        method.visitInsn(Opcodes.IADD);
        method.visitFieldInsn(Opcodes.PUTSTATIC, OWNER, "calls", "I");
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** Reads the fixture's own invocation counter out of the loader that defined it. */
    private static final class Fixture {
        private static Class<?> defined;

        static void reset() {
            defined = null;
        }

        static int calls() {
            try {
                return defined.getField("calls").getInt(null);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(failure);
            }
        }
    }

    private static final class FixtureLoader extends ClassLoader {
        private FixtureLoader() {
            super(NativeEditBeginTransformerTest.class.getClassLoader());
        }

        private Class<?> define(final String name, final byte[] bytes) {
            final Class<?> type = defineClass(name, bytes, 0, bytes.length);
            Fixture.defined = type;
            return type;
        }
    }
}
