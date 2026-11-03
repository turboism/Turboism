package dev.turboism.adapter.cubism.optimization.extendedinterpolation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.objectweb.asm.Opcodes.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

final class ExtendedInterpolationSetupBridgeTest {

    private static final String EXTENSION = ExtendedInterpolationSetupTarget.EXTENSION;

    private FixtureLoader loader;
    private ExtendedInterpolationSetupBridge bridge;

    private static final class FixtureLoader extends ClassLoader {
        FixtureLoader() {
            super(ExtendedInterpolationSetupBridgeTest.class.getClassLoader());
        }

        Class<?> define(final String name, final byte[] bytes) {
            return defineClass(name.replace('/', '.'), bytes, 0, bytes.length);
        }

        Class<?> type(final String name) throws ClassNotFoundException {
            return Class.forName(name.replace('/', '.'), true, this);
        }

        void setStatic(final String name, final String field, final Object value) throws Exception {
            type(name).getField(field).set(null, value);
        }

        Object getStatic(final String name, final String field) throws Exception {
            return type(name).getField(field).get(null);
        }
    }

    @BeforeEach
    void setUp() {
        loader = new FixtureLoader();
        loader.define(EXTENSION, fixtureExtension());
        bridge = newBridge();
        bridge.install();
    }

    @AfterEach
    void tearDown() {
        if (bridge != null) bridge.close();
        ExtendedInterpolationSetupBridge.clearNativeEditIntent();
        System.getProperties().remove(ExtendedInterpolationSetupBridge.CONSULT_PROPERTY);
        System.getProperties().remove(ExtendedInterpolationSetupBridge.STATS_PROPERTY);
        System.getProperties().remove(ExtendedInterpolationSetupBridge.GENERAL_INCREMENTAL_PROPERTY);
    }

    @Test
    void withoutEditIntentTheForcedRebuildIsKept() throws Exception {
        assertEquals(Boolean.FALSE, consult(currentExtension()));
        assertEquals(0, updates());
    }

    @Test
    void editIntentCallsTheHostIncrementalUpdate() throws Exception {
        ExtendedInterpolationSetupBridge.noteNativeEditIntent();
        assertEquals(Boolean.TRUE, consult(currentExtension()));
        assertEquals(1, updates());
        assertEquals(0, lastForce());
        assertEquals(1L, bridge.snapshot().get("incremental"));
    }

    @Test
    void generalIncrementalModeCallsTheHostIncrementalUpdateWithoutIntent() throws Exception {
        System.setProperty(ExtendedInterpolationSetupBridge.GENERAL_INCREMENTAL_PROPERTY, "true");
        assertEquals(Boolean.TRUE, consult(currentExtension()));
        assertEquals(1, updates());
    }

    @Test
    void hostRebuildStillSkipsTheForcedCall() throws Exception {
        loader.setStatic(EXTENSION, "rebuilds", Boolean.TRUE);
        ExtendedInterpolationSetupBridge.noteNativeEditIntent();
        assertEquals(Boolean.TRUE, consult(currentExtension()));
        assertEquals(1, updates());
        assertEquals(1L, bridge.snapshot().get("hostRebuilds"));
        assertEquals(0L, bridge.snapshot().get("failures"));
    }

    @Test
    void thrownIncrementalUpdateFallsBack() throws Exception {
        loader.setStatic(EXTENSION, "throwsOnUpdate", Boolean.TRUE);
        ExtendedInterpolationSetupBridge.noteNativeEditIntent();
        assertEquals(Boolean.FALSE, consult(currentExtension()));
        assertEquals(0, updates());
        assertEquals(1L, bridge.snapshot().get("failures"));
        assertEquals(1L, bridge.snapshot().get("nativeFallbacks"));
    }

    @Test
    void unknownObjectFallsBack() throws Exception {
        ExtendedInterpolationSetupBridge.noteNativeEditIntent();
        assertEquals(Boolean.FALSE, consult(new Object()));
        assertEquals(0L, bridge.snapshot().get("consults"));
    }

    @Test
    void closeStopsTheIncrementalPath() throws Exception {
        bridge.close();
        ExtendedInterpolationSetupBridge.noteNativeEditIntent();
        assertEquals(Boolean.FALSE, consult(currentExtension()));
        assertEquals(0, updates());
    }

    @Test
    void installsAndRemovesItsSlots() {
        assertInstanceOf(
                java.util.function.Function.class,
                System.getProperties().get(ExtendedInterpolationSetupBridge.CONSULT_PROPERTY));
        assertInstanceOf(
                java.util.function.Supplier.class,
                System.getProperties().get(ExtendedInterpolationSetupBridge.STATS_PROPERTY));
        bridge.close();
        assertEquals(null, System.getProperties().get(ExtendedInterpolationSetupBridge.CONSULT_PROPERTY));
        assertEquals(null, System.getProperties().get(ExtendedInterpolationSetupBridge.STATS_PROPERTY));
    }

    private ExtendedInterpolationSetupBridge newBridge() {
        try {
            return new ExtendedInterpolationSetupBridge(loader);
        } catch (ReflectiveOperationException unavailable) {
            throw new AssertionError(unavailable);
        }
    }

    private Object consult(final Object extension) throws Exception {
        final var method = ExtendedInterpolationSetupBridge.class.getDeclaredMethod("onConsult", Object.class);
        method.setAccessible(true);
        return method.invoke(bridge, extension);
    }

    private Object currentExtension() throws Exception {
        return loader.type(EXTENSION).getDeclaredConstructor().newInstance();
    }

    private int updates() throws Exception {
        return ((Number) loader.getStatic(EXTENSION, "updates")).intValue();
    }

    private int lastForce() throws Exception {
        return ((Number) loader.getStatic(EXTENSION, "lastForce")).intValue();
    }

    private static ClassWriter writer() {
        return new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
    }

    /** Minimal extension fixture exposing the reviewed incremental entry and its counters. */
    private static byte[] fixtureExtension() {
        final ClassWriter writer = writer();
        writer.visit(V17, ACC_PUBLIC | ACC_FINAL, EXTENSION, null, "java/lang/Object", null);
        writer.visitField(ACC_PUBLIC | ACC_STATIC, "updates", "I", null, null).visitEnd();
        writer.visitField(ACC_PUBLIC | ACC_STATIC, "lastForce", "I", null, null).visitEnd();
        writer.visitField(ACC_PUBLIC | ACC_STATIC, "rebuilds", "Z", null, null).visitEnd();
        writer.visitField(ACC_PUBLIC | ACC_STATIC, "throwsOnUpdate", "Z", null, null)
                .visitEnd();

        final MethodVisitor init = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        final MethodVisitor update = writer.visitMethod(ACC_PUBLIC, "updateInterpolatedForms", "(Z)Z", null, null);
        update.visitCode();
        final Label noThrow = new Label();
        update.visitFieldInsn(GETSTATIC, EXTENSION, "throwsOnUpdate", "Z");
        update.visitJumpInsn(IFEQ, noThrow);
        update.visitTypeInsn(NEW, "java/lang/IllegalStateException");
        update.visitInsn(DUP);
        update.visitLdcInsn("boom");
        update.visitMethodInsn(
                INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;)V", false);
        update.visitInsn(ATHROW);
        update.visitLabel(noThrow);
        update.visitFieldInsn(GETSTATIC, EXTENSION, "updates", "I");
        update.visitInsn(ICONST_1);
        update.visitInsn(IADD);
        update.visitFieldInsn(PUTSTATIC, EXTENSION, "updates", "I");
        update.visitVarInsn(ILOAD, 1);
        update.visitInsn(ICONST_1);
        update.visitInsn(IAND);
        update.visitFieldInsn(PUTSTATIC, EXTENSION, "lastForce", "I");
        update.visitFieldInsn(GETSTATIC, EXTENSION, "rebuilds", "Z");
        update.visitInsn(IRETURN);
        update.visitMaxs(0, 0);
        update.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
