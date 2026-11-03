package dev.turboism.adapter.cubism.optimization.extendedinterpolation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.objectweb.asm.Opcodes.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.Permissions;
import java.security.ProtectionDomain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

final class ExtendedInterpolationSetupTransformerTest {

    private static final String EXTENSION = ExtendedInterpolationSetupTarget.EXTENSION;
    private static final String MODEL_SOURCE = ExtendedInterpolationSetupTarget.MODEL_SOURCE;

    private Path artifact;
    private TestLoader loader;

    private final class TestLoader extends ClassLoader {
        private final ProtectionDomain domain;

        TestLoader(final Path artifact) throws Exception {
            super(ExtendedInterpolationSetupTransformerTest.class.getClassLoader());
            domain = new ProtectionDomain(
                    new CodeSource(artifact.toUri().toURL(), (java.security.CodeSigner[]) null), new Permissions());
        }

        Class<?> define(final String name, final byte[] bytes) {
            return defineClass(name.replace('/', '.'), bytes, 0, bytes.length, domain);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        artifact = Files.createTempFile("incremental-setup-test", ".jar");
        loader = new TestLoader(artifact);
        loader.define(EXTENSION, fixtureExtension());
    }

    @AfterEach
    void tearDown() {
        System.getProperties().remove(ExtendedInterpolationSetupBridge.CONSULT_PROPERTY);
        System.getProperties().remove(ExtendedInterpolationSetupBridge.STATS_PROPERTY);
        ExtendedInterpolationSetupBridge.clearNativeEditIntent();
        try {
            Files.deleteIfExists(artifact);
        } catch (Exception ignored) {
        }
    }

    @Test
    void rewritesBothForcedCallSitesWithoutChangingTheSchema() throws Exception {
        final byte[] source = fixtureModelSource(3);
        final ExtendedInterpolationSetupTransformer transformer =
                new ExtendedInterpolationSetupTransformer(loader, artifact, source);

        final byte[] woven = transformer.transform(null, loader, MODEL_SOURCE, null, loader.domain, source);

        assertNotNull(woven, String.valueOf(transformer.failure()));
        assertEquals(2, transformer.sites());
        assertNotNull(transformer.beforeSha256());
        assertNotNull(transformer.instrumentedSha256());
        final Class<?> type = loader.define(MODEL_SOURCE, woven);
        // Retransformation cannot add methods, so the rewrite must not declare one.
        assertEquals(
                0,
                java.util.Arrays.stream(type.getDeclaredMethods())
                        .filter(method -> method.getName().startsWith("turboism$"))
                        .count());
        assertNotNull(type.getDeclaredMethod("setup"));
    }

    @Test
    void withoutConsultSlotTheForcedCallRuns() throws Exception {
        final Class<?> type = transformedSource();
        type.getMethod("setup").invoke(type.getDeclaredConstructor().newInstance());
        assertEquals(2, forcedCalls());
    }

    @Test
    void trueConsultSkipsTheForcedCallWithTheExtensionArgument() throws Exception {
        final java.util.List<Object> received = new java.util.ArrayList<>();
        System.getProperties()
                .put(ExtendedInterpolationSetupBridge.CONSULT_PROPERTY, (java.util.function.Function<Object, Object>)
                        extension -> {
                            received.add(extension);
                            return Boolean.TRUE;
                        });
        final Class<?> type = transformedSource();
        type.getMethod("setup").invoke(type.getDeclaredConstructor().newInstance());
        assertEquals(0, forcedCalls());
        assertEquals(2, received.size());
        assertTrue(extensionClass().isInstance(received.get(0)));
    }

    @Test
    void falseConsultFallsBackToForcedCall() throws Exception {
        System.getProperties()
                .put(ExtendedInterpolationSetupBridge.CONSULT_PROPERTY, (java.util.function.Function<Object, Object>)
                        extension -> Boolean.FALSE);
        final Class<?> type = transformedSource();
        type.getMethod("setup").invoke(type.getDeclaredConstructor().newInstance());
        assertEquals(2, forcedCalls());
    }

    @Test
    void throwingConsultIsIsolatedAndFallsBack() throws Exception {
        System.getProperties()
                .put(ExtendedInterpolationSetupBridge.CONSULT_PROPERTY, (java.util.function.Function<Object, Object>)
                        extension -> {
                            throw new IllegalStateException("boom");
                        });
        final Class<?> type = transformedSource();
        type.getMethod("setup").invoke(type.getDeclaredConstructor().newInstance());
        assertEquals(2, forcedCalls());
    }

    @Test
    void wrongSlotTypeFallsBack() throws Exception {
        System.getProperties().put(ExtendedInterpolationSetupBridge.CONSULT_PROPERTY, "not-a-function");
        final Class<?> type = transformedSource();
        type.getMethod("setup").invoke(type.getDeclaredConstructor().newInstance());
        assertEquals(2, forcedCalls());
    }

    @Test
    void changedDefaultArgumentsRefuseAdmission() {
        final byte[] tampered = fixtureModelSource(7);
        assertFalse(ExtendedInterpolationSetupTarget.verify(tampered).isEmpty());
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExtendedInterpolationSetupTransformer(loader, artifact, tampered));
    }

    @Test
    void unrelatedClassIsIgnored() throws Exception {
        final ExtendedInterpolationSetupTransformer transformer =
                new ExtendedInterpolationSetupTransformer(loader, artifact, fixtureModelSource(3));
        final byte[] untouched = transformer.transform(
                null, loader, "com/live2d/cubism/doc/model/CModel", null, loader.domain, fixtureModelSource(3));
        assertNull(untouched);
        assertEquals(0, transformer.sites());
    }

    @Test
    void alreadyWovenBytesAreRejected() throws Exception {
        final byte[] source = fixtureModelSource(3);
        final ExtendedInterpolationSetupTransformer transformer =
                new ExtendedInterpolationSetupTransformer(loader, artifact, source);
        final byte[] woven = transformer.transform(null, loader, MODEL_SOURCE, null, loader.domain, source);
        assertNotNull(woven, String.valueOf(transformer.failure()));
        assertFalse(ExtendedInterpolationSetupTarget.verify(woven).isEmpty());
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExtendedInterpolationSetupTransformer(loader, artifact, woven));
    }

    @Test
    void referenceWithoutAnchorsIsRejected() {
        assertTrue(
                ExtendedInterpolationSetupTarget.verify(fixtureModelSource(3)).isEmpty());
        assertFalse(ExtendedInterpolationSetupTarget.verify(WITHOUT_CALLS).isEmpty());
    }

    private static final byte[] WITHOUT_CALLS = emptySetupSource();

    private Class<?> transformedSource() throws Exception {
        final byte[] source = fixtureModelSource(3);
        final ExtendedInterpolationSetupTransformer transformer =
                new ExtendedInterpolationSetupTransformer(loader, artifact, source);
        final byte[] woven = transformer.transform(null, loader, MODEL_SOURCE, null, loader.domain, source);
        assertNotNull(woven, String.valueOf(transformer.failure()));
        return loader.define(MODEL_SOURCE, woven);
    }

    private int forcedCalls() throws Exception {
        return extensionClass().getField("forced").getInt(null);
    }

    private Class<?> extensionClass() throws Exception {
        return loader.loadClass(EXTENSION.replace('/', '.'));
    }

    private ClassWriter writer() {
        return new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected ClassLoader getClassLoader() {
                return loader;
            }
        };
    }

    /** {@code CModelSource.setup()} with two forced call sites whose third argument is {@code mask}. */
    private byte[] fixtureModelSource(final int mask) {
        final ClassWriter writer = writer();
        writer.visit(V17, ACC_PUBLIC | ACC_FINAL, MODEL_SOURCE, null, "java/lang/Object", null);
        final MethodVisitor init = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        final MethodVisitor setup = writer.visitMethod(ACC_PUBLIC | ACC_FINAL, "setup", "()V", null, null);
        setup.visitCode();
        for (int i = 0; i < 2; i++) {
            setup.visitTypeInsn(NEW, EXTENSION);
            setup.visitInsn(DUP);
            setup.visitMethodInsn(INVOKESPECIAL, EXTENSION, "<init>", "()V", false);
            setup.visitInsn(ICONST_0);
            setup.visitInsn(ICONST_0);
            if (mask >= 0 && mask <= 5) setup.visitInsn(ICONST_0 + mask);
            else setup.visitIntInsn(BIPUSH, mask);
            setup.visitInsn(ACONST_NULL);
            setup.visitMethodInsn(
                    INVOKESTATIC,
                    EXTENSION,
                    ExtendedInterpolationSetupTarget.FORCED_METHOD,
                    ExtendedInterpolationSetupTarget.FORCED_DESCRIPTOR,
                    false);
        }
        setup.visitInsn(RETURN);
        setup.visitMaxs(0, 0);
        setup.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** Minimal extension fixture exposing the forced entry and an incremental counter. */
    private byte[] fixtureExtension() {
        final ClassWriter writer = writer();
        writer.visit(V17, ACC_PUBLIC | ACC_FINAL, EXTENSION, null, "java/lang/Object", null);
        writer.visitField(ACC_PUBLIC | ACC_STATIC, "forced", "I", null, null).visitEnd();
        writer.visitField(ACC_PUBLIC | ACC_STATIC, "incremental", "I", null, null)
                .visitEnd();

        final MethodVisitor init = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        final MethodVisitor forced = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                ExtendedInterpolationSetupTarget.FORCED_METHOD,
                ExtendedInterpolationSetupTarget.FORCED_DESCRIPTOR,
                null,
                null);
        forced.visitCode();
        forced.visitFieldInsn(GETSTATIC, EXTENSION, "forced", "I");
        forced.visitInsn(ICONST_1);
        forced.visitInsn(IADD);
        forced.visitFieldInsn(PUTSTATIC, EXTENSION, "forced", "I");
        forced.visitInsn(RETURN);
        forced.visitMaxs(0, 0);
        forced.visitEnd();

        final MethodVisitor incremental = writer.visitMethod(ACC_PUBLIC, "updateInterpolatedForms", "(Z)Z", null, null);
        incremental.visitCode();
        incremental.visitFieldInsn(GETSTATIC, EXTENSION, "incremental", "I");
        incremental.visitInsn(ICONST_1);
        incremental.visitInsn(IADD);
        incremental.visitFieldInsn(PUTSTATIC, EXTENSION, "incremental", "I");
        incremental.visitInsn(ICONST_0);
        incremental.visitInsn(IRETURN);
        incremental.visitMaxs(0, 0);
        incremental.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] emptySetupSource() {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_FINAL, MODEL_SOURCE, null, "java/lang/Object", null);
        final MethodVisitor setup = writer.visitMethod(ACC_PUBLIC | ACC_FINAL, "setup", "()V", null, null);
        setup.visitCode();
        setup.visitInsn(RETURN);
        setup.visitMaxs(0, 0);
        setup.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
