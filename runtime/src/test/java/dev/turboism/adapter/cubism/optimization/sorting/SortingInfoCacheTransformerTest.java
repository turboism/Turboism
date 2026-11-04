package dev.turboism.adapter.cubism.optimization.sorting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.objectweb.asm.Opcodes.*;

import dev.turboism.adapter.cubism.optimization.modelupdate.ModelUpdateSkipBridge;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.Permissions;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

/**
 * Exercises the injected sorting-info guard on a synthetic class carrying the exact
 * reviewed owner, method name and descriptor. The body mirrors the reviewed fields and
 * the calls whose elimination is being tested; the reviewed artifact path is the only
 * admission gate the test needs.
 */
public class SortingInfoCacheTransformerTest {

    private static final String OWNER = SortingInfoCacheTarget.SORTING_OWNER;
    private static final String RENDERER = "com/live2d/graphics3d/component/AGRenderer";
    private static final String CAMERA = "com/live2d/graphics3d/GCamera";

    private static final class Loader extends ClassLoader {
        private final ProtectionDomain domain;

        Loader(final Path artifact) throws Exception {
            super(SortingInfoCacheTransformerTest.class.getClassLoader());
            domain = new ProtectionDomain(
                    new CodeSource(artifact.toUri().toURL(), (java.security.CodeSigner[]) null), new Permissions());
        }

        Class<?> define(final String name, final byte[] bytes) {
            return defineClass(name.replace('/', '.'), bytes, 0, bytes.length, domain);
        }
    }

    @AfterEach
    void clearSlot() {
        System.getProperties().remove(ModelUpdateSkipBridge.SKIPPED_FRAME_PROPERTY);
    }

    private static byte[] emptyClass(final String name) {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC, name, null, "java/lang/Object", null);
        final MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] rendererClass() {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC, RENDERER, null, "java/lang/Object", null);
        writer.visitField(ACC_PUBLIC | ACC_STATIC, "zCalls", "I", null, null).visitEnd();

        MethodVisitor method = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        method.visitCode();
        method.visitVarInsn(ALOAD, 0);
        method.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        method.visitInsn(RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();

        method = writer.visitMethod(ACC_PUBLIC, "getSortingZOrder__testImpl", "(L" + CAMERA + ";)F", null, null);
        method.visitCode();
        method.visitFieldInsn(GETSTATIC, RENDERER, "zCalls", "I");
        method.visitInsn(ICONST_1);
        method.visitInsn(IADD);
        method.visitFieldInsn(PUTSTATIC, RENDERER, "zCalls", "I");
        method.visitInsn(FCONST_0);
        method.visitInsn(FRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /**
     * The reviewed cache layout: {@code a} renderer, {@code b} boolean, {@code c} long,
     * {@code d} float, {@code e} int. {@code a(GCamera)} calls the renderer's Z-order
     * calculation and writes all four cache fields; the {@code drift} NOP makes the
     * method shape differ from the reference.
     */
    private static byte[] sortingClass(final boolean drift) {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC, OWNER, null, "java/lang/Object", null);
        writer.visitField(ACC_PRIVATE, "a", "L" + RENDERER + ";", null, null).visitEnd();
        writer.visitField(ACC_PRIVATE, "b", "Z", null, null).visitEnd();
        writer.visitField(ACC_PRIVATE, "c", "J", null, null).visitEnd();
        writer.visitField(ACC_PRIVATE, "d", "F", null, null).visitEnd();
        writer.visitField(ACC_PRIVATE, "e", "I", null, null).visitEnd();
        writer.visitField(ACC_PUBLIC | ACC_STATIC, "calls", "I", null, null).visitEnd();

        MethodVisitor method = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        method.visitCode();
        method.visitVarInsn(ALOAD, 0);
        method.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        method.visitVarInsn(ALOAD, 0);
        method.visitTypeInsn(NEW, RENDERER);
        method.visitInsn(DUP);
        method.visitMethodInsn(INVOKESPECIAL, RENDERER, "<init>", "()V", false);
        method.visitFieldInsn(PUTFIELD, OWNER, "a", "L" + RENDERER + ";");
        method.visitVarInsn(ALOAD, 0);
        method.visitInsn(ICONST_1);
        method.visitFieldInsn(PUTFIELD, OWNER, "b", "Z");
        method.visitInsn(RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();

        method = writer.visitMethod(
                ACC_PUBLIC,
                SortingInfoCacheTarget.SORTING_METHOD,
                SortingInfoCacheTarget.SORTING_DESCRIPTOR,
                null,
                null);
        method.visitCode();
        if (drift) method.visitInsn(NOP);
        method.visitVarInsn(ALOAD, 0);
        method.visitFieldInsn(GETFIELD, OWNER, "a", "L" + RENDERER + ";");
        method.visitVarInsn(ALOAD, 1);
        method.visitMethodInsn(INVOKEVIRTUAL, RENDERER, "getSortingZOrder__testImpl", "(L" + CAMERA + ";)F", false);
        method.visitInsn(POP);
        method.visitFieldInsn(GETSTATIC, OWNER, "calls", "I");
        method.visitInsn(ICONST_1);
        method.visitInsn(IADD);
        method.visitFieldInsn(PUTSTATIC, OWNER, "calls", "I");
        method.visitVarInsn(ALOAD, 0);
        method.visitInsn(LCONST_1);
        method.visitFieldInsn(PUTFIELD, OWNER, "c", "J");
        method.visitVarInsn(ALOAD, 0);
        method.visitInsn(FCONST_0);
        method.visitFieldInsn(PUTFIELD, OWNER, "d", "F");
        method.visitVarInsn(ALOAD, 0);
        method.visitInsn(ICONST_0);
        method.visitFieldInsn(PUTFIELD, OWNER, "e", "I");
        method.visitVarInsn(ALOAD, 0);
        method.visitInsn(ICONST_1);
        method.visitFieldInsn(PUTFIELD, OWNER, "b", "Z");
        method.visitInsn(RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static final class Environment {
        final Loader loader;
        final Class<?> type;
        final Class<?> renderer;
        final Class<?> camera;
        final Method method;

        Environment(final Path artifact) throws Exception {
            loader = new Loader(artifact);
            camera = loader.define(CAMERA, emptyClass(CAMERA));
            renderer = loader.define(RENDERER, rendererClass());
            final byte[] reference = sortingClass(false);
            final SortingInfoCacheTarget target = SortingInfoCacheTarget.of(ReviewedHostArtifacts.CUBISM_5_3_04)
                    .orElseThrow();
            final SortingInfoCacheTransformer transformer =
                    new SortingInfoCacheTransformer(loader, artifact, reference, target);
            final byte[] output = transformer.transform(null, loader, OWNER, null, loader.domain, reference);
            assertNotNull(output, transformer.failure());
            type = loader.define(OWNER, output);
            method = type.getMethod(SortingInfoCacheTarget.SORTING_METHOD, camera);
        }

        Object instance() throws Exception {
            return type.getDeclaredConstructor().newInstance();
        }

        int calls() throws Exception {
            return type.getField("calls").getInt(null);
        }

        int zCalls() throws Exception {
            return renderer.getField("zCalls").getInt(null);
        }

        void resetCounters() throws Exception {
            type.getField("calls").setInt(null, 0);
            renderer.getField("zCalls").setInt(null, 0);
        }

        Object camera() throws Exception {
            return camera.getDeclaredConstructor().newInstance();
        }
    }

    private static void setField(final Object instance, final String name, final Object value) throws Exception {
        final Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(instance, value);
    }

    private static Object getField(final Object instance, final String name) throws Exception {
        final Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }

    private static Path artifact() throws Exception {
        return Files.createTempFile("sorting-host", ".jar");
    }

    @Test
    void skippedFrameReusesCachedSortingInfo() throws Exception {
        final Environment environment = new Environment(artifact());
        final Object instance = environment.instance();
        environment.resetCounters();
        setField(instance, "c", 7L);
        setField(instance, "d", 2.5f);
        setField(instance, "e", 3);
        System.getProperties().put(ModelUpdateSkipBridge.SKIPPED_FRAME_PROPERTY, new AtomicBoolean(true));
        try {
            environment.method.invoke(instance, new Object[] {null});
            assertEquals(0, environment.zCalls(), "sorting Z calculation must be skipped");
            assertEquals(0, environment.calls(), "native body must be skipped");
            assertEquals(7L, getField(instance, "c"));
            assertEquals(2.5f, getField(instance, "d"));
            assertEquals(3, getField(instance, "e"));
        } finally {
            System.getProperties().remove(ModelUpdateSkipBridge.SKIPPED_FRAME_PROPERTY);
        }
    }

    @Test
    void nonSkippedFrameRecalculatesSortingInfo() throws Exception {
        final Environment environment = new Environment(artifact());
        final Object instance = environment.instance();
        environment.resetCounters();
        setField(instance, "c", 7L);
        setField(instance, "e", 3);
        System.getProperties().put(ModelUpdateSkipBridge.SKIPPED_FRAME_PROPERTY, new AtomicBoolean(false));
        try {
            environment.method.invoke(instance, new Object[] {null});
            assertEquals(1, environment.zCalls());
            assertEquals(1, environment.calls());
            assertEquals(1L, getField(instance, "c"));
            assertEquals(0f, getField(instance, "d"));
            assertEquals(0, getField(instance, "e"));
        } finally {
            System.getProperties().remove(ModelUpdateSkipBridge.SKIPPED_FRAME_PROPERTY);
        }
    }

    @Test
    void cameraDependentSortingStillRuns() throws Exception {
        final Environment environment = new Environment(artifact());
        final Object instance = environment.instance();
        environment.resetCounters();
        setField(instance, "c", 7L);
        setField(instance, "e", 3);
        System.getProperties().put(ModelUpdateSkipBridge.SKIPPED_FRAME_PROPERTY, new AtomicBoolean(true));
        try {
            environment.method.invoke(instance, environment.camera());
            assertEquals(1, environment.zCalls());
            assertEquals(1, environment.calls());
        } finally {
            System.getProperties().remove(ModelUpdateSkipBridge.SKIPPED_FRAME_PROPERTY);
        }
    }

    @Test
    void unpopulatedOrNonModelCacheRunsNative() throws Exception {
        final Environment environment = new Environment(artifact());
        System.getProperties().put(ModelUpdateSkipBridge.SKIPPED_FRAME_PROPERTY, new AtomicBoolean(true));
        try {
            final Object unpopulated = environment.instance();
            environment.resetCounters();
            setField(unpopulated, "c", 0L);
            setField(unpopulated, "e", 3);
            environment.method.invoke(unpopulated, new Object[] {null});
            assertEquals(1, environment.calls());

            final Object nonModel = environment.instance();
            environment.resetCounters();
            setField(nonModel, "c", 7L);
            setField(nonModel, "e", -1);
            environment.method.invoke(nonModel, new Object[] {null});
            assertEquals(1, environment.calls());
        } finally {
            System.getProperties().remove(ModelUpdateSkipBridge.SKIPPED_FRAME_PROPERTY);
        }
    }

    @Test
    void absentOrMistypedSlotFallsThrough() throws Exception {
        final Environment environment = new Environment(artifact());
        final Object instance = environment.instance();
        setField(instance, "c", 7L);
        setField(instance, "e", 3);
        environment.resetCounters();
        environment.method.invoke(instance, new Object[] {null});
        assertEquals(1, environment.calls());

        environment.resetCounters();
        System.getProperties().put(ModelUpdateSkipBridge.SKIPPED_FRAME_PROPERTY, "not an atomic boolean");
        try {
            environment.method.invoke(instance, new Object[] {null});
            assertEquals(1, environment.calls());
        } finally {
            System.getProperties().remove(ModelUpdateSkipBridge.SKIPPED_FRAME_PROPERTY);
        }
    }

    @Test
    void shapeDriftIsRejected() throws Exception {
        final Path artifact = artifact();
        final Loader loader = new Loader(artifact);
        final byte[] reference = sortingClass(false);
        final byte[] drifted = sortingClass(true);
        final SortingInfoCacheTransformer transformer = new SortingInfoCacheTransformer(
                loader,
                artifact,
                reference,
                SortingInfoCacheTarget.of(ReviewedHostArtifacts.CUBISM_5_3_04).orElseThrow());
        assertNull(transformer.transform(null, loader, OWNER, null, loader.domain, drifted));
        assertTrue(transformer.failure().contains("shape"));
    }

    @Test
    void foreignArtifactIsRejected() throws Exception {
        final Loader loader = new Loader(artifact());
        final byte[] reference = sortingClass(false);
        final SortingInfoCacheTransformer transformer = new SortingInfoCacheTransformer(
                loader,
                artifact(),
                reference,
                SortingInfoCacheTarget.of(ReviewedHostArtifacts.CUBISM_5_3_04).orElseThrow());
        assertNull(transformer.transform(null, loader, OWNER, null, loader.domain, reference));
        assertTrue(transformer.failure().contains("source"));
    }

    @Test
    void missingMethodIsRejectedAtConstruction() throws Exception {
        final Loader loader = new Loader(artifact());
        assertThrows(
                IllegalArgumentException.class,
                () -> new SortingInfoCacheTransformer(
                        loader,
                        artifact(),
                        emptyClass(OWNER),
                        SortingInfoCacheTarget.of(ReviewedHostArtifacts.CUBISM_5_3_04)
                                .orElseThrow()));
    }

    @Test
    void transformedClassKeepsObjectIdentityAndFields() throws Exception {
        final Environment environment = new Environment(artifact());
        final Object instance = environment.instance();
        assertNotNull(getField(instance, "a"));
        assertEquals(Boolean.TRUE, getField(instance, "b"));
    }
}
