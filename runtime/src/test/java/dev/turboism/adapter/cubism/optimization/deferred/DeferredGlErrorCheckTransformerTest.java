package dev.turboism.adapter.cubism.optimization.deferred;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import dev.turboism.adapter.cubism.optimization.uniform.UniformLocationHookBridge;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Emission-level tests for the deferred GL error-check transform: the shader
 * site becomes a guarded {@code deferQuery} consult and every frame-method
 * {@code RETURN} consults the armed report. Bridge semantics are covered by
 * {@code UniformLocationHookBridgeTest}.
 */
class DeferredGlErrorCheckTransformerTest {
    private static final Path ARTIFACT = Path.of("reviewed.jar").toAbsolutePath();
    private static int deferredQueries, reportConsults;
    private static Object deferredGl;
    private static String deferredContext;
    private static boolean deferredThrowing, failCallbacks, deferFallback;
    private static Object report;

    public static int deferQuery(Object gl, String context, boolean throwing) {
        deferredQueries++; deferredGl = gl; deferredContext = context;
        deferredThrowing = throwing;
        if (failCallbacks) throw new IllegalStateException();
        return deferFallback ? UniformLocationHookBridge.DEFERRED_FALLBACK : 0;
    }
    public static Object consumeReport() {
        reportConsults++;
        if (failCallbacks) throw new IllegalStateException();
        return report;
    }
    @AfterEach void cleanup() {
        System.getProperties().remove(UniformLocationHookBridge.DEFER_QUERY_PROPERTY);
        System.getProperties().remove(UniformLocationHookBridge.DEFER_REPORT_PROPERTY);
        deferredQueries = reportConsults = 0; deferredGl = null; deferredContext = null;
        deferredThrowing = failCallbacks = deferFallback = false; report = null;
    }
    private void deferredCallbacks() throws Exception {
        var lookup = MethodHandles.lookup();
        System.getProperties().put(UniformLocationHookBridge.DEFER_QUERY_PROPERTY,
            lookup.findStatic(getClass(), "deferQuery",
                MethodType.methodType(int.class, Object.class, String.class, boolean.class)));
        System.getProperties().put(UniformLocationHookBridge.DEFER_REPORT_PROPERTY,
            lookup.findStatic(getClass(), "consumeReport", MethodType.methodType(Object.class)));
    }

    /** Without the bridge slot the checkpoint must run the real glGetError. */
    @Test void siteWithoutCallbackRunsTheNativeQuery() throws Exception {
        Fixture fixture = new Fixture();
        assertEquals(1282, fixture.runError());
        assertEquals(1, fixture.errorQueries);
        assertEquals(0, deferredQueries);
    }
    /**
     * With the slot present the checkpoint consults it and returns its value —
     * the deferred emission, not the native query, decides.
     */
    @Test void siteWithCallbackDefers() throws Exception {
        deferredCallbacks();
        Fixture fixture = new Fixture();
        assertEquals(0, fixture.runError());
        assertEquals(1, deferredQueries);
        assertEquals(0, fixture.errorQueries, "the native query must not run");
        assertSame(fixture.gl, deferredGl);
        assertEquals("draw", deferredContext);
        assertTrue(deferredThrowing);
    }
    /** A failing callback falls back to the real query — host semantics hold. */
    @Test void siteCallbackFailureFallsBackToNativeQuery() throws Exception {
        deferredCallbacks();
        failCallbacks = true;
        Fixture fixture = new Fixture();
        assertEquals(1282, fixture.runError());
        assertEquals(1, fixture.errorQueries);
    }
    /**
     * A callback returning {@code DEFERRED_FALLBACK} runs the real query
     * inline exactly once — the native result, not the sentinel, is what the
     * site returns.
     */
    @Test void deferFallbackRunsRealQueryInlineExactlyOnce() throws Exception {
        deferredCallbacks();
        deferFallback = true;
        Fixture fixture = new Fixture();
        assertEquals(1282, fixture.runError(),
            "the inline real query's result reaches the caller");
        assertEquals(1, deferredQueries, "the consult ran");
        assertEquals(1, fixture.errorQueries,
            "the real glGetError ran exactly once, inline");
    }
    @Test void realQueryFailureIsNeverRetriedOrSwallowed() throws Exception {
        for (int mode = 0; mode < 3; mode++) {
            for (Throwable failure : List.of(new IllegalStateException("native"),
                    new LinkageError("driver"))) {
                cleanup();
                if (mode != 0) deferredCallbacks();
                failCallbacks = mode == 1;
                deferFallback = mode == 2;
                Fixture fixture = new Fixture();
                fixture.nativeFailure = failure;
                InvocationTargetException thrown =
                    assertThrows(InvocationTargetException.class, fixture::runError);
                assertSame(failure, thrown.getCause(), "native throwable identity");
                assertEquals(1, fixture.errorQueries, "real query must execute exactly once");
            }
        }
    }

    /** A wrong-shaped slot is ignored the same way: real query, no consult. */
    @Test void siteWrongSlotShapeRunsNativeQuery() throws Exception {
        System.getProperties().put(UniformLocationHookBridge.DEFER_QUERY_PROPERTY, "wrong");
        Fixture fixture = new Fixture();
        assertEquals(1282, fixture.runError());
        assertEquals(1, fixture.errorQueries);
    }
    /** Frame RETURN consults the armed report; null means a normal return. */
    @Test void frameReturnConsultsArmedReport() throws Exception {
        deferredCallbacks();
        Fixture fixture = new Fixture();
        fixture.runFrame();
        assertEquals(1, reportConsults);
        assertEquals(1, fixture.frameCalls());
    }
    /** A non-null armed report is rethrown through the frame exit. */
    @Test void frameReturnRethrowsArmedReport() throws Exception {
        deferredCallbacks();
        Fixture fixture = new Fixture();
        RuntimeException armed = new IllegalStateException("deferred");
        report = armed;
        InvocationTargetException thrown =
            assertThrows(InvocationTargetException.class, fixture::runFrame);
        assertSame(armed, thrown.getCause());
        assertEquals(1, reportConsults);
    }
    /** The composed (uniform-transformed) body is admitted only when registered. */
    @Test void composedErrorBodyRequiresRegisteredShape() throws Exception {
        Fixture fixture = new Fixture();
        ProtectionDomain domain = fixture.domain;
        byte[] composed = fixture.composedError();
        assertNotNull(composed);
        var transformer = fixture.transformer();
        assertNull(transformer.transform(null, fixture.loader,
            DeferredGlErrorCheckTarget.ERROR_OWNER, null, domain, composed));
        assertNotNull(transformer.failure());
        List<String> shape = ReviewedMethodShape.read(composed,
            DeferredGlErrorCheckTarget.ERROR_OWNER,
            DeferredGlErrorCheckTarget.ERROR_METHOD,
            DeferredGlErrorCheckTarget.ERROR_DESCRIPTOR);
        var admitted = fixture.transformer();
        admitted.acceptComposedShape(DeferredGlErrorCheckTarget.ERROR_OWNER, shape);
        assertNotNull(admitted.transform(null, fixture.loader,
            DeferredGlErrorCheckTarget.ERROR_OWNER, null, domain, composed),
            admitted.failure());
    }
    /** A method body without the reviewed call site fails closed. */
    @Test void missingErrorSiteIsRejected() throws Exception {
        Fixture fixture = new Fixture();
        ClassWriter writer = empty(DeferredGlErrorCheckTarget.ERROR_OWNER);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "a",
            DeferredGlErrorCheckTarget.ERROR_DESCRIPTOR, null, null);
        method.visitCode();
        method.visitInsn(Opcodes.ICONST_0);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(0, 0); method.visitEnd();
        writer.visitEnd();
        byte[] drifted = writer.toByteArray();
        var transformer = fixture.transformer();
        assertNull(transformer.transform(null, fixture.loader,
            DeferredGlErrorCheckTarget.ERROR_OWNER, null, fixture.domain, drifted));
        assertNotNull(transformer.failure());
    }

    private static final class Loader extends ClassLoader {
        Class<?> define(String name, byte[] bytes) {
            return defineClass(name.replace('/', '.'), bytes, 0, bytes.length);
        }
    }
    private static ClassWriter empty(String owner) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, owner, null,
            "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "calls", "I", null, null).visitEnd();
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode(); init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN); init.visitMaxs(0, 0); init.visitEnd();
        return writer;
    }

    private final class Fixture {
        final Loader loader = new Loader();
        final ProtectionDomain domain;
        final Class<?> errorType, frameType;
        final Object errorInstance, frameInstance, gl;
        final byte[] errorReference, frameReference;
        int errorQueries;
        Throwable nativeFailure;
        Fixture() throws Exception {
            // The GL owner must be an interface for the invokeinterface to link.
            ClassWriter glInterface = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
            glInterface.visit(Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                "com/jogamp/opengl/GL", null, "java/lang/Object", null);
            glInterface.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "glGetError", "()I",
                null, null).visitEnd();
            glInterface.visitEnd();
            Class<?> glType = loader.define("com/jogamp/opengl/GL", glInterface.toByteArray());
            gl = Proxy.newProxyInstance(loader, new Class<?>[] {glType},
                (proxy, invoked, args) -> {
                    errorQueries++;
                    if (nativeFailure != null) throw nativeFailure;
                    return 1282;
                });

            ClassWriter argWriter = empty("com/live2d/graphics3d/a");
            argWriter.visitEnd();
            loader.define("com/live2d/graphics3d/a", argWriter.toByteArray());

            // shader/A.a(GL,String,Z)I: the reviewed site sequence
            // aload_1 ; invokeinterface glGetError ; ireturn — plus enough
            // locals so context (2) and throwing (3) are real parameters.
            ClassWriter errorWriter = empty(DeferredGlErrorCheckTarget.ERROR_OWNER);
            MethodVisitor error = errorWriter.visitMethod(Opcodes.ACC_PUBLIC, "a",
                DeferredGlErrorCheckTarget.ERROR_DESCRIPTOR, null, null);
            error.visitCode();
            error.visitVarInsn(Opcodes.ALOAD, 1);
            error.visitMethodInsn(Opcodes.INVOKEINTERFACE, "com/jogamp/opengl/GL",
                "glGetError", "()I", true);
            error.visitInsn(Opcodes.IRETURN);
            error.visitMaxs(0, 0); error.visitEnd();
            errorWriter.visitEnd();
            errorReference = errorWriter.toByteArray();

            // SGFramework/g.render3d(a)V: a minimal body with one RETURN.
            ClassWriter frameWriter = empty(DeferredGlErrorCheckTarget.FRAME_OWNER);
            MethodVisitor frame = frameWriter.visitMethod(Opcodes.ACC_PUBLIC, "render3d",
                DeferredGlErrorCheckTarget.FRAME_DESCRIPTOR, null, null);
            frame.visitCode();
            frame.visitFieldInsn(Opcodes.GETSTATIC, DeferredGlErrorCheckTarget.FRAME_OWNER,
                "calls", "I");
            frame.visitInsn(Opcodes.ICONST_1);
            frame.visitInsn(Opcodes.IADD);
            frame.visitFieldInsn(Opcodes.PUTSTATIC, DeferredGlErrorCheckTarget.FRAME_OWNER,
                "calls", "I");
            frame.visitInsn(Opcodes.RETURN);
            frame.visitMaxs(0, 0); frame.visitEnd();
            frameWriter.visitEnd();
            frameReference = frameWriter.toByteArray();

            domain = new ProtectionDomain(
                new CodeSource(ARTIFACT.toUri().toURL(), (Certificate[]) null), null);
            var transformer = transformer();
            byte[] errorOut = transformer.transform(null, loader,
                DeferredGlErrorCheckTarget.ERROR_OWNER, null, domain, errorReference);
            assertNotNull(errorOut, transformer.failure());
            byte[] frameOut = transformer.transform(null, loader,
                DeferredGlErrorCheckTarget.FRAME_OWNER, null, domain, frameReference);
            assertNotNull(frameOut, transformer.failure());
            errorType = loader.define(DeferredGlErrorCheckTarget.ERROR_OWNER, errorOut);
            frameType = loader.define(DeferredGlErrorCheckTarget.FRAME_OWNER, frameOut);
            errorInstance = errorType.getDeclaredConstructor().newInstance();
            frameInstance = frameType.getDeclaredConstructor().newInstance();
        }
        DeferredGlErrorCheckTransformer transformer() {
            return new DeferredGlErrorCheckTransformer(loader, ARTIFACT,
                errorReference, frameReference, DeferredGlErrorCheckTarget.all().get(2));
        }
        int runError() throws Exception {
            return (int) errorType.getMethod("a", gl.getClass().getInterfaces()[0],
                String.class, boolean.class).invoke(errorInstance, gl, "draw", true);
        }
        void runFrame() throws Exception {
            frameType.getMethod("render3d",
                Class.forName("com.live2d.graphics3d.a", false, loader))
                .invoke(frameInstance, new Object[] {null});
        }
        int frameCalls() throws Exception {
            return frameType.getField("calls").getInt(null);
        }
        /** The body the upstream uniform ERROR transform produces on the reference. */
        byte[] composedError() {
            var upstream = new dev.turboism.adapter.cubism.optimization.uniform
                .UniformLocationLifecycleTransformer(loader, ARTIFACT, errorReference,
                    dev.turboism.adapter.cubism.optimization.uniform
                        .UniformLocationLifecycleTransformer.Role.ERROR,
                    dev.turboism.mapping.verification.ReviewedHostArtifacts.CUBISM_5_3_03);
            return upstream.transform(null, loader,
                DeferredGlErrorCheckTarget.ERROR_OWNER, null, domain, errorReference);
        }
    }
}
