package dev.turboism.adapter.cubism.optimization.inputpath;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.Permissions;
import java.security.ProtectionDomain;
import java.util.Properties;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

/**
 * Verifies the test-only input-path elision on synthetic classes carrying the
 * exact reviewed owner {@code com/live2d/ui/CWidget} and the two reviewed
 * forwarder methods. The synthetic bodies stand in for the official host
 * method shapes (a field increment plays the role of the native-reaching
 * forward); the transformer admits them only through the reviewed artifact
 * path and must rewrite both methods or neither.
 */
public class InputPathElisionTransformerTest {

    private static final String OWNER = "com/live2d/ui/CWidget";
    private static final String CCURSOR = "com/live2d/type/CCursor";

    private static final class Loader extends ClassLoader {
        private final ProtectionDomain domain;

        Loader(Path artifact) throws Exception {
            super(InputPathElisionTransformerTest.class.getClassLoader());
            domain = new ProtectionDomain(
                    new CodeSource(artifact.toUri().toURL(), (java.security.CodeSigner[]) null), new Permissions());
        }

        Class<?> define(String name, byte[] bytes) {
            return defineClass(name.replace('/', '.'), bytes, 0, bytes.length, domain);
        }
    }

    @AfterEach
    void clearSlots() {
        final Properties properties = System.getProperties();
        properties.remove(InputPathElisionBridge.FOCUS_PROPERTY);
        properties.remove(InputPathElisionBridge.CURSOR_PROPERTY);
    }

    /** Empty public shell for the cursor argument type. */
    private static byte[] cursorStub() {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        w.visit(V17, ACC_PUBLIC, CCURSOR, null, "java/lang/Object", null);
        MethodVisitor m = w.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /**
     * The reviewed owner: {@code requestFocus()V} and
     * {@code setCursor(Lcom/live2d/type/CCursor;)V}, each incrementing a public
     * counter field as the observable "native" tail. {@code drift} adds a NOP
     * to the focus method so the reviewed shape no longer matches;
     * {@code missingCursor} drops the cursor forwarder entirely.
     */
    private static byte[] widget(boolean drift, boolean missingCursor) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        w.visit(V17, ACC_PUBLIC, OWNER, null, "java/lang/Object", null);
        w.visitField(ACC_PUBLIC, "focusCalls", "I", null, null).visitEnd();
        w.visitField(ACC_PUBLIC, "cursorCalls", "I", null, null).visitEnd();
        MethodVisitor m = w.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        m = w.visitMethod(ACC_PUBLIC, "requestFocus", "()V", null, null);
        m.visitCode();
        if (drift) m.visitInsn(NOP);
        increment(m, "focusCalls");
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        if (!missingCursor) {
            m = w.visitMethod(ACC_PUBLIC | ACC_FINAL, "setCursor", "(Lcom/live2d/type/CCursor;)V", null, null);
            m.visitCode();
            increment(m, "cursorCalls");
            m.visitInsn(RETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        }
        w.visitEnd();
        return w.toByteArray();
    }

    private static void increment(MethodVisitor m, String field) {
        m.visitVarInsn(ALOAD, 0);
        m.visitInsn(DUP);
        m.visitFieldInsn(GETFIELD, OWNER, field, "I");
        m.visitInsn(ICONST_1);
        m.visitInsn(IADD);
        m.visitFieldInsn(PUTFIELD, OWNER, field, "I");
    }

    @Test
    void matchingShapeInjectsBothSitesAndHonorsSlots() throws Exception {
        Path artifact = Files.createTempFile("host", ".jar");
        Loader loader = new Loader(artifact);
        byte[] reference = widget(false, false);
        var transformer = new InputPathElisionTransformer(loader, artifact, reference);
        byte[] output = transformer.transform(null, loader, OWNER, null, loader.domain, reference);
        assertNotNull(output, transformer.failure());
        assertEquals(1, transformer.matches());
        assertEquals(2, transformer.sites());
        assertNotNull(transformer.beforeSha256());
        loader.define(CCURSOR, cursorStub());
        Class<?> type = loader.define(OWNER, output);
        Object instance = type.getDeclaredConstructor().newInstance();
        Method focus = type.getMethod("requestFocus");
        Method setCursor = type.getMethod("setCursor", loader.loadClass(CCURSOR.replace('/', '.')));

        // No slots: untouched native path.
        focus.invoke(instance);
        assertEquals(1, type.getField("focusCalls").getInt(instance));

        final Properties properties = System.getProperties();
        properties.put(InputPathElisionBridge.FOCUS_PROPERTY, (Predicate<Object>) w -> true);
        properties.put(InputPathElisionBridge.CURSOR_PROPERTY, (BiPredicate<Object, Object>) (w, c) -> true);
        focus.invoke(instance);
        setCursor.invoke(instance, new Object[] {null});
        assertEquals(
                1, type.getField("focusCalls").getInt(instance), "armed focus consult must skip the native forward");
        assertEquals(
                0, type.getField("cursorCalls").getInt(instance), "armed cursor consult must skip the native forward");

        properties.put(InputPathElisionBridge.FOCUS_PROPERTY, (Predicate<Object>) w -> false);
        properties.put(InputPathElisionBridge.CURSOR_PROPERTY, (BiPredicate<Object, Object>) (w, c) -> false);
        focus.invoke(instance);
        setCursor.invoke(instance, new Object[] {null});
        assertEquals(2, type.getField("focusCalls").getInt(instance));
        assertEquals(1, type.getField("cursorCalls").getInt(instance));
    }

    @Test
    void mistypedOrThrowingSlotsFallThrough() throws Exception {
        Path artifact = Files.createTempFile("host", ".jar");
        Loader loader = new Loader(artifact);
        byte[] reference = widget(false, false);
        var transformer = new InputPathElisionTransformer(loader, artifact, reference);
        byte[] output = transformer.transform(null, loader, OWNER, null, loader.domain, reference);
        assertNotNull(output, transformer.failure());
        loader.define(CCURSOR, cursorStub());
        Class<?> type = loader.define(OWNER, output);
        Object instance = type.getDeclaredConstructor().newInstance();
        Method focus = type.getMethod("requestFocus");

        final Properties properties = System.getProperties();
        properties.put(InputPathElisionBridge.FOCUS_PROPERTY, "not-a-predicate");
        focus.invoke(instance);
        assertEquals(1, type.getField("focusCalls").getInt(instance));

        properties.put(InputPathElisionBridge.FOCUS_PROPERTY, (Predicate<Object>) w -> {
            throw new IllegalStateException("boom");
        });
        focus.invoke(instance);
        assertEquals(
                2, type.getField("focusCalls").getInt(instance), "a failing consult must fall back to the native path");
    }

    @Test
    void driftedMethodBodyIsRejected() throws Exception {
        Path artifact = Files.createTempFile("host", ".jar");
        Loader loader = new Loader(artifact);
        byte[] reference = widget(false, false);
        var transformer = new InputPathElisionTransformer(loader, artifact, reference);
        assertNull(transformer.transform(null, loader, OWNER, null, loader.domain, widget(true, false)));
        assertNotNull(transformer.failure());
        assertEquals(0, transformer.matches());
        assertEquals(0, transformer.sites());
    }

    @Test
    void missingReviewedMethodFailsConstructorAndTransform() throws Exception {
        Path artifact = Files.createTempFile("host", ".jar");
        Loader loader = new Loader(artifact);
        assertThrows(
                IllegalArgumentException.class,
                () -> new InputPathElisionTransformer(loader, artifact, widget(false, true)),
                "reference without the cursor forwarder is not a target");
        byte[] reference = widget(false, false);
        var transformer = new InputPathElisionTransformer(loader, artifact, reference);
        assertNull(
                transformer.transform(null, loader, OWNER, null, loader.domain, widget(false, true)),
                "a class missing the reviewed method fails the shape gate");
        assertNotNull(transformer.failure());
        assertEquals(0, transformer.matches());
    }

    @Test
    void foreignOwnerLoaderAndArtifactAreRejected() throws Exception {
        Path artifact = Files.createTempFile("host", ".jar");
        Loader loader = new Loader(artifact);
        byte[] reference = widget(false, false);
        var transformer = new InputPathElisionTransformer(loader, artifact, reference);
        assertNull(
                transformer.transform(null, loader, "com/live2d/ui/Other", null, loader.domain, reference),
                "foreign owner");
        assertNull(
                transformer.transform(null, new Loader(artifact), OWNER, null, loader.domain, reference),
                "foreign loader");
        assertNull(transformer.transform(null, loader, OWNER, null, null, reference), "absent protection domain");
        Loader alien = new Loader(Files.createTempFile("other", ".jar"));
        assertNull(
                transformer.transform(null, loader, OWNER, null, alien.domain, reference),
                "class from another artifact");
        assertNotNull(transformer.failure());
        assertEquals(0, transformer.matches());
        assertEquals(0, transformer.sites());
    }
}
