package dev.turboism.adapter.cubism.optimization.uploadelision;

import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.Permissions;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Verifies the test-only skipped-frame upload elision on synthetic classes
 * carrying the exact reviewed owners/method/descriptor. The synthetic
 * {@code b(GL2ES2,int)} mirrors the reviewed wrapper structure: bind, an
 * {@code ILOAD 3; IFEQ} pair, a reallocate block holding {@code glGenBuffers}
 * plus {@code glBufferData}, a dirty-flag-gated {@code glBufferSubData} block
 * and trailing flag clearing. A {@code FakeGL2ES2} counts every call so tests
 * can prove only the upload operation is suppressed while binding,
 * {@code glGenBuffers} and flag bookkeeping still run.
 */
public class SkippedFrameUploadElisionTransformerTest {

    private static final String FLOAT = "com/live2d/graphics3d/mesh/a/b";
    private static final String INDEX = "com/live2d/graphics3d/mesh/a/c";
    private static final String GL = "com/jogamp/opengl/GL2ES2";
    private static final String IMPL = "FakeGL2ES2";

    private static final class Loader extends ClassLoader {
        private final ProtectionDomain domain;
        Loader(Path artifact) throws Exception {
            super(SkippedFrameUploadElisionTransformerTest.class.getClassLoader());
            domain = new ProtectionDomain(
                new CodeSource(artifact.toUri().toURL(), (java.security.CodeSigner[]) null),
                new Permissions());
        }
        Class<?> define(String name, byte[] bytes) {
            return defineClass(name.replace('/', '.'), bytes, 0, bytes.length, domain);
        }
    }

    @AfterEach void clearSlots() {
        final var properties = System.getProperties();
        properties.remove(SkippedFrameUploadElisionBridge.PREDICATE_PROPERTY);
        properties.remove(SkippedFrameUploadElisionBridge.LIFECYCLE_PROPERTY);
        properties.remove(SkippedFrameUploadElisionBridge.FAILURE_PROPERTY);
    }

    private static byte[] glInterface() {
        ClassWriter w = new ClassWriter(0);
        w.visit(V17, ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT, GL, null,
            "java/lang/Object", null);
        w.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "glBindBuffer", "(II)V", null, null).visitEnd();
        w.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "glGenBuffers",
            "(ILjava/nio/IntBuffer;)V", null, null).visitEnd();
        w.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "glBufferData",
            "(IJLjava/nio/Buffer;I)V", null, null).visitEnd();
        w.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "glBufferSubData",
            "(IJJLjava/nio/Buffer;)V", null, null).visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** A {@code GL2ES2} stub counting each entry point, with an optional throw. */
    private static byte[] glImpl() {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        w.visit(V17, ACC_PUBLIC, IMPL, null, "java/lang/Object", new String[]{GL});
        for (String field : new String[]{"binds", "gens", "datas", "subs", "throwOnSub"}) {
            w.visitField(ACC_PUBLIC, field, "I", null, null).visitEnd();
        }
        MethodVisitor m = w.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        emitCounter(w, "glBindBuffer", "(II)V", "binds", false);
        emitCounter(w, "glGenBuffers", "(ILjava/nio/IntBuffer;)V", "gens", false);
        emitCounter(w, "glBufferData", "(IJLjava/nio/Buffer;I)V", "datas", false);
        emitCounter(w, "glBufferSubData", "(IJJLjava/nio/Buffer;)V", "subs", true);
        w.visitEnd();
        return w.toByteArray();
    }

    private static void emitCounter(final ClassWriter w, final String name, final String desc,
                                    final String field, final boolean throwable) {
        final MethodVisitor m = w.visitMethod(ACC_PUBLIC, name, desc, null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitInsn(DUP);
        m.visitFieldInsn(GETFIELD, IMPL, field, "I");
        m.visitInsn(ICONST_1);
        m.visitInsn(IADD);
        m.visitFieldInsn(PUTFIELD, IMPL, field, "I");
        if (throwable) {
            final Label keep = new Label();
            m.visitVarInsn(ALOAD, 0);
            m.visitFieldInsn(GETFIELD, IMPL, "throwOnSub", "I");
            m.visitJumpInsn(IFEQ, keep);
            m.visitTypeInsn(NEW, "java/lang/IllegalStateException");
            m.visitInsn(DUP);
            m.visitMethodInsn(INVOKESPECIAL, "java/lang/IllegalStateException", "<init>",
                "()V", false);
            m.visitInsn(ATHROW);
            m.visitLabel(keep);
        }
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    /**
     * The reviewed wrapper shape: fields {@code names/payload/dirty/cleared},
     * accessors {@code i()IntBuffer}, {@code b()Buffer}, {@code k()Z} and the
     * private upload method {@code b(GL2ES2,int)}. The body mirrors the real
     * method: a flag is computed into local 3 ({@code istore_3}; here simply
     * the {@code int} parameter), the first {@code ILOAD 3; IFEQ} pair gates
     * the regeneration block with {@code glGenBuffers}, the bind call runs
     * unconditionally, the second {@code ILOAD 3; IFEQ} pair gates the
     * {@code glBufferData} block, and {@code k(); IFEQ} gates the
     * {@code glBufferSubData} block:
     *
     * <pre>
     *   flag = p                                          // istore_3
     *   if (flag != 0) glGenBuffers                       // pair 1, regen block
     *   bind(34962, i()[0])
     *   if (flag != 0) { glBufferData }  goto join        // pair 2, reallocate
     *   if (k()) glBufferSubData                          // dirty block
     *   join: cleared = false                             // join bookkeeping
     * </pre>
     *
     * {@code drift} inserts a NOP so the reviewed shape mismatches;
     * {@code reducedSites} drops the reallocate block (still a self-consistent
     * shape, so the anchor inventory — not the shape gate — must reject it).
     */
    private static byte[] wrapper(final String owner, final boolean drift,
                                  final boolean reducedSites) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        w.visit(V17, ACC_PUBLIC, owner, null, "java/lang/Object", null);
        w.visitField(ACC_PRIVATE, "names", "Ljava/nio/IntBuffer;", null, null).visitEnd();
        w.visitField(ACC_PRIVATE, "payload", "Ljava/nio/Buffer;", null, null).visitEnd();
        w.visitField(ACC_PRIVATE, "dirty", "Z", null, null).visitEnd();
        w.visitField(ACC_PRIVATE, "cleared", "Z", null, null).visitEnd();

        MethodVisitor m = w.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        m.visitVarInsn(ALOAD, 0);
        m.visitInsn(ICONST_1);
        m.visitMethodInsn(INVOKESTATIC, "java/nio/IntBuffer", "allocate",
            "(I)Ljava/nio/IntBuffer;", false);
        m.visitFieldInsn(PUTFIELD, owner, "names", "Ljava/nio/IntBuffer;");
        m.visitVarInsn(ALOAD, 0);
        m.visitIntInsn(BIPUSH, 8);
        m.visitMethodInsn(INVOKESTATIC, "java/nio/IntBuffer", "allocate",
            "(I)Ljava/nio/IntBuffer;", false);
        m.visitFieldInsn(PUTFIELD, owner, "payload", "Ljava/nio/Buffer;");
        m.visitVarInsn(ALOAD, 0);
        m.visitInsn(ICONST_1);
        m.visitFieldInsn(PUTFIELD, owner, "dirty", "Z");
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();

        m = w.visitMethod(ACC_FINAL, "i", "()Ljava/nio/IntBuffer;", null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitFieldInsn(GETFIELD, owner, "names", "Ljava/nio/IntBuffer;");
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();

        m = w.visitMethod(ACC_PUBLIC, "b", "()Ljava/nio/Buffer;", null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitFieldInsn(GETFIELD, owner, "payload", "Ljava/nio/Buffer;");
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();

        m = w.visitMethod(ACC_FINAL, "k", "()Z", null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitFieldInsn(GETFIELD, owner, "dirty", "Z");
        m.visitInsn(IRETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();

        m = w.visitMethod(ACC_PRIVATE | ACC_FINAL, "b", "(Lcom/jogamp/opengl/GL2ES2;I)V",
            null, null);
        m.visitCode();
        if (drift) m.visitInsn(NOP);
        // flag = p (real code computes glChanged || forced into local 3)
        m.visitVarInsn(ILOAD, 2);
        m.visitVarInsn(ISTORE, 3);
        // pair 1: if (flag != 0) glGenBuffers  (regeneration block)
        Label regen = new Label();
        m.visitVarInsn(ILOAD, 3);
        m.visitJumpInsn(IFEQ, regen);
        m.visitVarInsn(ALOAD, 1);
        m.visitInsn(ICONST_1);
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKEVIRTUAL, owner, "i", "()Ljava/nio/IntBuffer;", false);
        m.visitMethodInsn(INVOKEINTERFACE, GL, "glGenBuffers",
            "(ILjava/nio/IntBuffer;)V", true);
        m.visitLabel(regen);
        // bind(target, names[0]) — unconditional
        m.visitVarInsn(ALOAD, 1);
        emitInt(m, 34962);
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKEVIRTUAL, owner, "i", "()Ljava/nio/IntBuffer;", false);
        m.visitInsn(ICONST_0);
        m.visitMethodInsn(INVOKEVIRTUAL, "java/nio/IntBuffer", "get", "(I)I", false);
        m.visitMethodInsn(INVOKEINTERFACE, GL, "glBindBuffer", "(II)V", true);
        // pair 2: if (flag != 0) glBufferData  (reallocate block)
        Label join = new Label();
        if (!reducedSites) {
            Label afterData = new Label();
            m.visitVarInsn(ILOAD, 3);
            m.visitJumpInsn(IFEQ, afterData);
            m.visitVarInsn(ALOAD, 1);
            emitInt(m, 34962);
            m.visitLdcInsn(16L);
            m.visitVarInsn(ALOAD, 0);
            m.visitMethodInsn(INVOKEVIRTUAL, owner, "b", "()Ljava/nio/Buffer;", false);
            emitInt(m, 35044);
            m.visitMethodInsn(INVOKEINTERFACE, GL, "glBufferData",
                "(IJLjava/nio/Buffer;I)V", true);
            m.visitJumpInsn(GOTO, join);
            m.visitLabel(afterData);
        }
        // dirty block: if (k()) bufferSubData
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKEVIRTUAL, owner, "k", "()Z", false);
        m.visitJumpInsn(IFEQ, join);
        m.visitVarInsn(ALOAD, 1);
        emitInt(m, 34962);
        m.visitInsn(LCONST_0);
        m.visitLdcInsn(16L);
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKEVIRTUAL, owner, "b", "()Ljava/nio/Buffer;", false);
        m.visitMethodInsn(INVOKEINTERFACE, GL, "glBufferSubData",
            "(IJJLjava/nio/Buffer;)V", true);
        m.visitLabel(join);
        // join bookkeeping: cleared = false
        m.visitVarInsn(ALOAD, 0);
        m.visitInsn(ICONST_0);
        m.visitFieldInsn(PUTFIELD, owner, "cleared", "Z");
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    private static void emitInt(final MethodVisitor m, final int value) {
        m.visitLdcInsn(value);
    }

    /** Same owner name but the upload method carries a foreign descriptor. */
    private static byte[] foreignDescriptor() {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        w.visit(V17, ACC_PUBLIC, FLOAT, null, "java/lang/Object", null);
        MethodVisitor m = w.visitMethod(ACC_PRIVATE, "b", "(I)V", null, null);
        m.visitCode();
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    private static SkippedFrameUploadElisionTransformer transformer(final Loader loader,
            final Path artifact, final byte[] reference, final String owner) {
        return new SkippedFrameUploadElisionTransformer(loader, artifact, reference, owner);
    }

    @Test void matchingShapeGuardsBothUploadSitesAndKeepsBindings() throws Exception {
        Path artifact = Files.createTempFile("host", ".jar");
        Loader loader = new Loader(artifact);
        byte[] reference = wrapper(FLOAT, false, false);
        var t = transformer(loader, artifact, reference, FLOAT);
        byte[] output = t.transform(null, loader, FLOAT, null, loader.domain, reference);
        assertNotNull(output, t.failure());
        assertEquals(1, t.matches());
        assertEquals(2, t.guarded());
        assertNotNull(t.beforeSha256());

        Class<?> glType = loader.define(GL, glInterface());
        Class<?> impl = loader.define(IMPL, glImpl());
        Class<?> type = loader.define(FLOAT, output);
        Object gl = impl.getDeclaredConstructor().newInstance();
        Object instance = type.getDeclaredConstructor().newInstance();
        Method upload = type.getDeclaredMethod("b", glType, int.class);
        upload.setAccessible(true);

        // No predicate installed: every path must execute natively.
        upload.invoke(instance, gl, 1);   // reallocate path skips the dirty block
        assertEquals(1, impl.getField("binds").getInt(gl), "bind retained");
        assertEquals(1, impl.getField("gens").getInt(gl), "glGenBuffers retained");
        assertEquals(1, impl.getField("datas").getInt(gl));
        assertEquals(0, impl.getField("subs").getInt(gl),
            "reallocate path jumps over the dirty block");
        upload.invoke(instance, gl, 0);   // dirty-only path
        assertEquals(2, impl.getField("binds").getInt(gl));
        assertEquals(1, impl.getField("gens").getInt(gl));
        assertEquals(1, impl.getField("subs").getInt(gl));
    }

    @Test void armedPredicateSuppressesOnlyTheUploadCall() throws Exception {
        Path artifact = Files.createTempFile("host", ".jar");
        Loader loader = new Loader(artifact);
        byte[] reference = wrapper(FLOAT, false, false);
        var t = transformer(loader, artifact, reference, FLOAT);
        byte[] output = t.transform(null, loader, FLOAT, null, loader.domain, reference);
        assertNotNull(output, t.failure());
        Class<?> glType = loader.define(GL, glInterface());
        Class<?> impl = loader.define(IMPL, glImpl());
        Class<?> type = loader.define(FLOAT, output);
        Object gl = impl.getDeclaredConstructor().newInstance();
        Object instance = type.getDeclaredConstructor().newInstance();
        Method upload = type.getDeclaredMethod("b", glType, int.class);
        upload.setAccessible(true);

        final AtomicInteger lifecycle = new AtomicInteger();
        final AtomicInteger failures = new AtomicInteger();
        System.getProperties().put(SkippedFrameUploadElisionBridge.PREDICATE_PROPERTY,
            (BiPredicate<Object, Object>) (wrapper, context) -> true);
        System.getProperties().put(SkippedFrameUploadElisionBridge.LIFECYCLE_PROPERTY,
            (Runnable) lifecycle::incrementAndGet);
        System.getProperties().put(SkippedFrameUploadElisionBridge.FAILURE_PROPERTY,
            (Runnable) failures::incrementAndGet);

        upload.invoke(instance, gl, 1);   // reallocate path
        assertEquals(1, impl.getField("binds").getInt(gl), "bind untouched");
        assertEquals(1, impl.getField("gens").getInt(gl), "glGenBuffers untouched");
        assertEquals(1, lifecycle.get(), "lifecycle notified after glGenBuffers");
        assertEquals(0, impl.getField("datas").getInt(gl), "glBufferData suppressed");
        assertEquals(0, impl.getField("subs").getInt(gl), "glBufferSubData suppressed");
        assertEquals(0, failures.get());

        final var cleared = type.getDeclaredField("cleared");
        cleared.setAccessible(true);
        cleared.setBoolean(instance, true);
        upload.invoke(instance, gl, 0);   // dirty-only path
        assertEquals(2, impl.getField("binds").getInt(gl));
        assertEquals(0, impl.getField("datas").getInt(gl));
        assertEquals(0, impl.getField("subs").getInt(gl));
        assertFalse(cleared.getBoolean(instance), "join bookkeeping still runs");
    }

    @Test void absentPredicateAndWrongSlotTypeFallThrough() throws Exception {
        Path artifact = Files.createTempFile("host", ".jar");
        Loader loader = new Loader(artifact);
        byte[] reference = wrapper(FLOAT, false, false);
        var t = transformer(loader, artifact, reference, FLOAT);
        byte[] output = t.transform(null, loader, FLOAT, null, loader.domain, reference);
        assertNotNull(output, t.failure());
        Class<?> glType = loader.define(GL, glInterface());
        Class<?> impl = loader.define(IMPL, glImpl());
        Class<?> type = loader.define(FLOAT, output);
        Object gl = impl.getDeclaredConstructor().newInstance();
        Object instance = type.getDeclaredConstructor().newInstance();
        Method upload = type.getDeclaredMethod("b", glType, int.class);
        upload.setAccessible(true);

        System.getProperties().put(SkippedFrameUploadElisionBridge.PREDICATE_PROPERTY,
            "not-a-predicate");
        upload.invoke(instance, gl, 0);
        assertEquals(1, impl.getField("subs").getInt(gl),
            "mistyped slot falls through to the native upload");

        final AtomicInteger observed = new AtomicInteger();
        System.getProperties().put(SkippedFrameUploadElisionBridge.PREDICATE_PROPERTY,
            (BiPredicate<Object, Object>) (wrapper, context) -> {
                observed.incrementAndGet();
                throw new IllegalStateException("observer bug");
            });
        upload.invoke(instance, gl, 0);
        assertEquals(1, observed.get());
        assertEquals(2, impl.getField("subs").getInt(gl),
            "throwing predicate falls through to the native upload");
    }

    @Test void uploadExceptionNotifiesFailureSlotAndRethrows() throws Exception {
        Path artifact = Files.createTempFile("host", ".jar");
        Loader loader = new Loader(artifact);
        byte[] reference = wrapper(FLOAT, false, false);
        var t = transformer(loader, artifact, reference, FLOAT);
        byte[] output = t.transform(null, loader, FLOAT, null, loader.domain, reference);
        assertNotNull(output, t.failure());
        Class<?> glType = loader.define(GL, glInterface());
        Class<?> impl = loader.define(IMPL, glImpl());
        Class<?> type = loader.define(FLOAT, output);
        Object gl = impl.getDeclaredConstructor().newInstance();
        impl.getField("throwOnSub").setInt(gl, 1);
        Object instance = type.getDeclaredConstructor().newInstance();
        Method upload = type.getDeclaredMethod("b", glType, int.class);
        upload.setAccessible(true);

        final AtomicInteger failures = new AtomicInteger();
        System.getProperties().put(SkippedFrameUploadElisionBridge.FAILURE_PROPERTY,
            (Runnable) failures::incrementAndGet);
        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
            () -> upload.invoke(instance, gl, 0));
        assertInstanceOf(IllegalStateException.class, thrown.getCause(),
            "native upload exception must propagate unchanged");
        assertEquals(1, failures.get(), "failure slot notified before rethrow");
    }

    @Test void bothOwnersAndEveryReviewedVersionAreAdmitted() throws Exception {
        for (String owner : SkippedFrameUploadElisionTarget.OWNERS) {
            Path artifact = Files.createTempFile("host", ".jar");
            Loader loader = new Loader(artifact);
            byte[] reference = wrapper(owner, false, false);
            var t = transformer(loader, artifact, reference, owner);
            assertNotNull(t.transform(null, loader, owner, null, loader.domain, reference),
                owner + " rejected: " + t.failure());
            assertEquals(1, t.matches());
            assertEquals(2, t.guarded());
        }
        assertEquals(2, SkippedFrameUploadElisionTarget.all().size());
        assertTrue(SkippedFrameUploadElisionTarget
                .of(ReviewedHostArtifacts.CUBISM_5_2_03).isEmpty(),
            "5.2.03 lacks the reviewed shader/A.a(Buffer)J size helper");
    }

    @Test void driftedMethodBodyIsRejected() throws Exception {
        Path artifact = Files.createTempFile("host", ".jar");
        Loader loader = new Loader(artifact);
        byte[] reference = wrapper(FLOAT, false, false);
        var t = transformer(loader, artifact, reference, FLOAT);
        assertNull(t.transform(null, loader, FLOAT, null, loader.domain,
            wrapper(FLOAT, true, false)));
        assertNotNull(t.failure());
        assertEquals(0, t.matches());
        assertEquals(0, t.guarded());
    }

    @Test void reducedSiteInventoryFailsClosed() throws Exception {
        Path artifact = Files.createTempFile("host", ".jar");
        Loader loader = new Loader(artifact);
        // Self-consistent reference whose upload method lacks the reallocate
        // block: the shape gate passes but the anchor inventory must refuse.
        byte[] reference = wrapper(FLOAT, false, true);
        var t = transformer(loader, artifact, reference, FLOAT);
        assertNull(t.transform(null, loader, FLOAT, null, loader.domain, reference));
        assertNotNull(t.failure());
        assertTrue(t.failure().contains("anchor"), t.failure());
        assertEquals(0, t.matches());
    }

    @Test void foreignOwnerLoaderArtifactAndDescriptorAreRejected() throws Exception {
        Path artifact = Files.createTempFile("host", ".jar");
        Loader loader = new Loader(artifact);
        byte[] reference = wrapper(FLOAT, false, false);
        var t = transformer(loader, artifact, reference, FLOAT);
        assertNull(t.transform(null, loader, "com/live2d/graphics3d/mesh/a/x", null,
            loader.domain, reference), "foreign owner");
        assertNull(t.transform(null, new Loader(artifact), FLOAT, null,
            loader.domain, reference), "foreign loader");
        assertNull(t.transform(null, loader, FLOAT, null, null, reference),
            "absent protection domain");
        Loader alien = new Loader(Files.createTempFile("other", ".jar"));
        assertNull(t.transform(null, loader, FLOAT, null, alien.domain, reference),
            "class from another artifact");
        assertNull(t.transform(null, loader, FLOAT, null, loader.domain,
            foreignDescriptor()), "foreign descriptor has no reviewed shape");
        assertNotNull(t.failure());
        assertEquals(0, t.matches());
    }

    @Test void unreviewedOwnerCannotConstruct() {
        Path artifact;
        try {
            artifact = Files.createTempFile("host", ".jar");
            Loader loader = new Loader(artifact);
            byte[] reference = wrapper(FLOAT, false, false);
            assertThrows(IllegalArgumentException.class, () ->
                new SkippedFrameUploadElisionTransformer(loader, artifact, reference,
                    "com/live2d/graphics3d/mesh/a/x"));
            assertThrows(IllegalArgumentException.class, () ->
                new SkippedFrameUploadElisionTransformer(loader, artifact,
                    foreignDescriptor(), FLOAT));
        } catch (Exception failure) {
            fail(failure);
        }
    }
}
