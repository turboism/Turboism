package dev.turboism.adapter.cubism.optimization.glerror;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.Permissions;
import java.security.ProtectionDomain;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

/**
 * Verifies the test-only glGetError elision on synthetic classes carrying the
 * exact reviewed owner/method/descriptor. The marker method {@code a(GL,String,Z)}
 * is reduced to a single {@code invokeinterface GL.glGetError()I} site plus an
 * {@code ireturn}; a second method {@code b(GL)I} on the same owner carries an
 * identical site that must stay native. Class bytes stand in for the official
 * host method shape; the transformer admits them only through the reviewed
 * artifact path.
 */
public class GlGetErrorElisionTransformerTest {

    private static final String OWNER = "com/live2d/graphics3d/shader/A";
    private static final String GL = "com/jogamp/opengl/GL";

    private static final GlGetErrorElisionTarget T5303 =
            GlGetErrorElisionTarget.of(ReviewedHostArtifacts.CUBISM_5_3_03).orElseThrow();

    private static final class Loader extends ClassLoader {
        private final ProtectionDomain domain;

        Loader(Path artifact) throws Exception {
            super(GlGetErrorElisionTransformerTest.class.getClassLoader());
            domain = new ProtectionDomain(
                    new CodeSource(artifact.toUri().toURL(), (java.security.CodeSigner[]) null), new Permissions());
        }

        Class<?> define(String name, byte[] bytes) {
            return defineClass(name.replace('/', '.'), bytes, 0, bytes.length, domain);
        }
    }

    private static byte[] glInterface() {
        ClassWriter w = new ClassWriter(0);
        w.visit(V17, ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT, GL, null, "java/lang/Object", null);
        w.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "glGetError", "()I", null, null)
                .visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** A {@code GL} implementation that counts calls and always reports error 1280. */
    private static byte[] glImpl() {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        w.visit(V17, ACC_PUBLIC, "FakeGL", null, "java/lang/Object", new String[] {GL});
        w.visitField(ACC_PUBLIC, "errorCalls", "I", null, null).visitEnd();
        MethodVisitor m = w.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        m = w.visitMethod(ACC_PUBLIC, "glGetError", "()I", null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitInsn(DUP);
        m.visitFieldInsn(GETFIELD, "FakeGL", "errorCalls", "I");
        m.visitInsn(ICONST_1);
        m.visitInsn(IADD);
        m.visitFieldInsn(PUTFIELD, "FakeGL", "errorCalls", "I");
        m.visitIntInsn(SIPUSH, 1280);
        m.visitInsn(IRETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /**
     * The reviewed owner with {@code a(GL,String,Z)I} delegating to
     * {@code gl.glGetError()} and an untouched sibling site in {@code b(GL)I}.
     * {@code drift} adds a NOP so the reviewed shape no longer matches.
     * {@code noSite} drops the marker call entirely.
     * {@code nonInterface} calls the method with {@code invokevirtual} instead.
     */
    private static byte[] marker(boolean drift, boolean noSite, boolean nonInterface) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        w.visit(V17, ACC_PUBLIC, OWNER, null, "java/lang/Object", null);
        MethodVisitor m = w.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        m = w.visitMethod(ACC_PUBLIC | ACC_FINAL, "a", "(Lcom/jogamp/opengl/GL;Ljava/lang/String;Z)I", null, null);
        m.visitCode();
        if (drift) m.visitInsn(NOP);
        if (noSite) {
            m.visitInsn(ICONST_0);
        } else {
            m.visitVarInsn(ALOAD, 1);
            m.visitMethodInsn(nonInterface ? INVOKEVIRTUAL : INVOKEINTERFACE, GL, "glGetError", "()I", !nonInterface);
        }
        m.visitInsn(IRETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        m = w.visitMethod(ACC_PUBLIC, "b", "(Lcom/jogamp/opengl/GL;)I", null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 1);
        m.visitMethodInsn(INVOKEINTERFACE, GL, "glGetError", "()I", true);
        m.visitInsn(IRETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** The marker body after elision: {@code aload_1; pop; iconst_0; ireturn}. */
    private static byte[] elidedMarker(boolean drift) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        w.visit(V17, ACC_PUBLIC, OWNER, null, "java/lang/Object", null);
        MethodVisitor m =
                w.visitMethod(ACC_PUBLIC | ACC_FINAL, "a", "(Lcom/jogamp/opengl/GL;Ljava/lang/String;Z)I", null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 1);
        m.visitInsn(POP);
        m.visitInsn(ICONST_0);
        if (drift) m.visitInsn(NOP);
        m.visitInsn(IRETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** Same owner but the marker name carries a foreign descriptor. */
    private static byte[] foreignDescriptor() {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        w.visit(V17, ACC_PUBLIC, OWNER, null, "java/lang/Object", null);
        MethodVisitor m = w.visitMethod(ACC_PUBLIC, "a", "(Lcom/jogamp/opengl/GL;)I", null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 1);
        m.visitMethodInsn(INVOKEINTERFACE, GL, "glGetError", "()I", true);
        m.visitInsn(IRETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    @Test
    void matchingShapeElidesOnlyTheMarkerSite() throws Exception {
        Path artifact = Files.createTempFile("host", ".jar");
        Loader loader = new Loader(artifact);
        byte[] reference = marker(false, false, false);
        var transformer = new GlGetErrorElisionTransformer(loader, artifact, reference, T5303);
        byte[] output = transformer.transform(null, loader, OWNER, null, loader.domain, reference);
        assertNotNull(output, transformer.failure());
        assertEquals(1, transformer.matches());
        assertEquals(1, transformer.elided());
        assertNotNull(transformer.beforeSha256());
        Class<?> glType = loader.define(GL, glInterface());
        Class<?> implType = loader.define("FakeGL", glImpl());
        Class<?> type = loader.define(OWNER, output);
        Object gl = implType.getDeclaredConstructor().newInstance();
        Object instance = type.getDeclaredConstructor().newInstance();
        Method marker = type.getMethod("a", glType, String.class, boolean.class);
        assertEquals(0, marker.invoke(instance, gl, "marker", true));
        assertEquals(
                0, implType.getField("errorCalls").getInt(gl), "marker call must not reach the native error query");
        assertEquals(1280, type.getMethod("b", glType).invoke(instance, gl));
        assertEquals(
                1,
                implType.getField("errorCalls").getInt(gl),
                "glGetError sites outside the reviewed method stay native");
    }

    @Test
    void everyReviewedVersionIsAdmitted() throws Exception {
        for (GlGetErrorElisionTarget target : GlGetErrorElisionTarget.all()) {
            Path artifact = Files.createTempFile("host", ".jar");
            Loader loader = new Loader(artifact);
            byte[] reference = marker(false, false, false);
            var transformer = new GlGetErrorElisionTransformer(loader, artifact, reference, target);
            byte[] output = transformer.transform(null, loader, target.owner(), null, loader.domain, reference);
            assertNotNull(output, target.version() + " rejected: " + transformer.failure());
            assertEquals(1, transformer.matches());
            assertEquals(1, transformer.elided());
        }
        assertEquals(2, GlGetErrorElisionTarget.all().size());
        assertTrue(
                GlGetErrorElisionTarget.of(ReviewedHostArtifacts.CUBISM_5_2_03).isEmpty(),
                "5.2.03 has no such marker method and stays unsupported");
    }

    @Test
    void driftedMethodBodyIsRejected() throws Exception {
        Path artifact = Files.createTempFile("host", ".jar");
        Loader loader = new Loader(artifact);
        byte[] reference = marker(false, false, false);
        var transformer = new GlGetErrorElisionTransformer(loader, artifact, reference, T5303);
        assertNull(transformer.transform(null, loader, OWNER, null, loader.domain, marker(true, false, false)));
        assertNotNull(transformer.failure());
        assertEquals(0, transformer.matches());
        assertEquals(0, transformer.elided());
    }

    @Test
    void foreignOwnerMethodAndDescriptorAreRejected() throws Exception {
        Path artifact = Files.createTempFile("host", ".jar");
        Loader loader = new Loader(artifact);
        byte[] reference = marker(false, false, false);
        var transformer = new GlGetErrorElisionTransformer(loader, artifact, reference, T5303);
        assertNull(
                transformer.transform(null, loader, "com/live2d/graphics3d/shader/B", null, loader.domain, reference),
                "foreign owner");
        assertNull(
                transformer.transform(null, loader, OWNER, null, loader.domain, foreignDescriptor()),
                "foreign descriptor has no reviewed shape");
        assertNotNull(transformer.failure());
        assertEquals(0, transformer.matches());
        assertEquals(0, transformer.elided());
    }

    @Test
    void missingOrNonInterfaceSiteCannotConstructOrTransform() throws Exception {
        Path artifact = Files.createTempFile("host", ".jar");
        Loader loader = new Loader(artifact);
        assertThrows(
                IllegalArgumentException.class,
                () -> new GlGetErrorElisionTransformer(loader, artifact, marker(false, true, false), T5303),
                "reference without the reviewed site is not a target");
        assertThrows(
                IllegalArgumentException.class,
                () -> new GlGetErrorElisionTransformer(loader, artifact, marker(false, false, true), T5303),
                "non-interface call site is not the reviewed invocation");
        byte[] reference = marker(false, false, false);
        var transformer = new GlGetErrorElisionTransformer(loader, artifact, reference, T5303);
        assertNull(
                transformer.transform(null, loader, OWNER, null, loader.domain, marker(false, true, false)),
                "drifted body without the site fails the shape gate");
        assertNotNull(transformer.failure());
        assertEquals(0, transformer.matches());
    }

    @Test
    void installedMarkerTracksLoaderAndOwner() throws Exception {
        Path artifact = Files.createTempFile("host", ".jar");
        Loader loader = new Loader(artifact);
        Loader other = new Loader(artifact);
        String owner = GlGetErrorElisionTarget.OWNER;
        try {
            assertFalse(GlGetErrorElisionTransformer.isInstalled(loader, owner));
            GlGetErrorElisionTransformer.markInstalled(loader, owner);
            assertTrue(GlGetErrorElisionTransformer.isInstalled(loader, owner));
            assertFalse(
                    GlGetErrorElisionTransformer.isInstalled(other, owner),
                    "a different defining loader must not inherit the marker");
            assertFalse(
                    GlGetErrorElisionTransformer.isInstalled(loader, "x/y/Z"),
                    "a foreign owner must not inherit the marker");
        } finally {
            GlGetErrorElisionTransformer.clearInstalled(loader, owner);
        }
        assertFalse(GlGetErrorElisionTransformer.isInstalled(loader, owner));
    }

    @Test
    void composedShapeIsTheProbeOutputShape() throws Exception {
        Path artifact = Files.createTempFile("host", ".jar");
        Loader loader = new Loader(artifact);
        byte[] reference = marker(false, false, false);
        Class<?> type = loader.define(OWNER, reference);
        List<String> composed = GlGetErrorElisionTransformer.composedShape(loader, artifact, reference, T5303, type);
        assertNotNull(composed, "probe must admit the reviewed reference");
        var transformer = new GlGetErrorElisionTransformer(loader, artifact, reference, T5303);
        byte[] rewritten = transformer.transform(null, loader, OWNER, null, loader.domain, reference);
        assertNotNull(rewritten, transformer.failure());
        assertEquals(
                composed,
                dev.turboism.adapter.cubism.optimization.ReviewedMethodShape.read(
                        rewritten, OWNER, "a", "(Lcom/jogamp/opengl/GL;Ljava/lang/String;Z)I"),
                "the advertised composed shape must equal the real rewrite output");
        assertTrue(
                composed.stream().noneMatch(op -> op.contains("glGetError")),
                "the composed shape must not retain the elided call");
    }

    @Test
    void uniformRewrittenBodyIsRejected() throws Exception {
        // The composition contract pins install order: elision runs upstream of
        // the uniform lifecycle observer. If that order ever flips, the elision
        // gate must keep failing closed instead of rewriting foreign bytes.
        Path artifact = Files.createTempFile("host", ".jar");
        Loader loader = new Loader(artifact);
        byte[] reference = marker(false, false, false);
        Class<?> type = loader.define(OWNER, reference);
        var uniform = new dev.turboism.adapter.cubism.optimization.uniform.UniformLocationLifecycleTransformer(
                loader,
                artifact,
                reference,
                dev.turboism.adapter.cubism.optimization.uniform.UniformLocationLifecycleTransformer.Role.ERROR);
        byte[] wrapped = uniform.transform(type.getModule(), loader, OWNER, type, loader.domain, reference);
        assertNotNull(wrapped, uniform.failure());
        var transformer = new GlGetErrorElisionTransformer(loader, artifact, reference, T5303);
        assertNull(
                transformer.transform(type.getModule(), loader, OWNER, type, loader.domain, wrapped),
                "a body already carrying the uniform observer is not the reviewed shape");
        assertNotNull(transformer.failure());
        assertEquals(0, transformer.elided());
    }

    @Test
    void uniformLifecycleAcceptsOnlyTheRegisteredComposedShape() throws Exception {
        Path artifact = Files.createTempFile("host", ".jar");
        Loader loader = new Loader(artifact);
        byte[] reference = marker(false, false, false);
        Class<?> type = loader.define(OWNER, reference);
        List<String> composed = GlGetErrorElisionTransformer.composedShape(loader, artifact, reference, T5303, type);
        assertNotNull(composed);
        var uniform = new dev.turboism.adapter.cubism.optimization.uniform.UniformLocationLifecycleTransformer(
                loader,
                artifact,
                reference,
                dev.turboism.adapter.cubism.optimization.uniform.UniformLocationLifecycleTransformer.Role.ERROR);
        byte[] elided = elidedMarker(false);
        // Without a registered composed shape the elided body is foreign drift.
        assertNull(uniform.transform(type.getModule(), loader, OWNER, type, loader.domain, elided));
        assertNotNull(uniform.failure());
        uniform.acceptComposedShape("a", composed);
        byte[] output = uniform.transform(type.getModule(), loader, OWNER, type, loader.domain, elided);
        assertNotNull(output, uniform.failure());
        List<String> outputShape = dev.turboism.adapter.cubism.optimization.ReviewedMethodShape.read(
                output, OWNER, "a", "(Lcom/jogamp/opengl/GL;Ljava/lang/String;Z)I");
        assertTrue(
                outputShape.stream().noneMatch(op -> op.contains("glGetError")),
                "the composed result must not resurrect the elided call");
        // A drifted elided body is still not either reviewed shape.
        var second = new dev.turboism.adapter.cubism.optimization.uniform.UniformLocationLifecycleTransformer(
                loader,
                artifact,
                reference,
                dev.turboism.adapter.cubism.optimization.uniform.UniformLocationLifecycleTransformer.Role.ERROR);
        second.acceptComposedShape("a", composed);
        assertNull(second.transform(type.getModule(), loader, OWNER, type, loader.domain, elidedMarker(true)));
        assertNotNull(second.failure());
    }

    @Test
    void wrongLoaderNameAndArtifactAreRejected() throws Exception {
        Path artifact = Files.createTempFile("host", ".jar");
        Loader loader = new Loader(artifact);
        byte[] reference = marker(false, false, false);
        var transformer = new GlGetErrorElisionTransformer(loader, artifact, reference, T5303);
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
        assertEquals(0, transformer.elided());
    }
}
