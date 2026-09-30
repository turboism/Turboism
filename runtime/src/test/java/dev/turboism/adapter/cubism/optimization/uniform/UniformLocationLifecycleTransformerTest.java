package dev.turboism.adapter.cubism.optimization.uniform;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class UniformLocationLifecycleTransformerTest {
    private static int begins, ends, errors, errorCalls, mutations, mutationsEnded;
    private static Object errorArg;
    private static long ended;
    private static boolean failCallbacks;
    private static final Path ARTIFACT = Path.of("reviewed.jar").toAbsolutePath();

    public static long begin(Object frame) {
        begins++;
        if (failCallbacks) throw new IllegalStateException();
        return 61;
    }

    public static void end(long token) {
        ends++;
        ended = token;
        if (failCallbacks) throw new IllegalStateException();
    }

    public static void error(Object gl, int value) {
        errors = value;
        errorArg = gl;
        errorCalls++;
        if (failCallbacks) throw new IllegalStateException();
    }

    public static void invalidate() {
        mutations++;
        if (failCallbacks) throw new IllegalStateException();
    }

    public static long mutationBegin() {
        mutations++;
        if (failCallbacks) throw new IllegalStateException();
        return 71;
    }

    public static void mutationEnd(long scope) {
        mutationsEnded++;
        ended = scope;
        if (failCallbacks) throw new IllegalStateException();
    }

    @AfterEach
    void cleanup() {
        for (String key : UniformLocationHookBridge.slots())
            System.getProperties().remove(key);
        begins = ends = errors = errorCalls = mutations = mutationsEnded = 0;
        errorArg = null;
        ended = 0;
        failCallbacks = false;
    }

    private void callbacks() throws Exception {
        var lookup = MethodHandles.lookup();
        System.getProperties()
                .put(
                        UniformLocationHookBridge.BEGIN_PROPERTY,
                        lookup.findStatic(getClass(), "begin", MethodType.methodType(long.class, Object.class)));
        System.getProperties()
                .put(
                        UniformLocationHookBridge.END_PROPERTY,
                        lookup.findStatic(getClass(), "end", MethodType.methodType(void.class, long.class)));
        System.getProperties()
                .put(
                        UniformLocationHookBridge.ERROR_PROPERTY,
                        lookup.findStatic(
                                getClass(), "error", MethodType.methodType(void.class, Object.class, int.class)));
        System.getProperties()
                .put(
                        UniformLocationHookBridge.INVALIDATE_PROPERTY,
                        lookup.findStatic(getClass(), "invalidate", MethodType.methodType(void.class)));
        System.getProperties()
                .put(
                        UniformLocationHookBridge.MUTATION_BEGIN_PROPERTY,
                        lookup.findStatic(getClass(), "mutationBegin", MethodType.methodType(long.class)));
        System.getProperties()
                .put(
                        UniformLocationHookBridge.MUTATION_END_PROPERTY,
                        lookup.findStatic(getClass(), "mutationEnd", MethodType.methodType(void.class, long.class)));
    }

    @Test
    void frameEntryAndAllExitsPreserveNativeFailure() throws Exception {
        callbacks();
        Fixture fixture = new Fixture(UniformLocationLifecycleTransformer.Role.FRAME);
        fixture.runFrame();
        assertEquals(1, begins);
        assertEquals(1, ends);
        assertEquals(61, ended);
        RuntimeException nativeError = new IllegalArgumentException("native frame");
        fixture.type.getField("failure").set(null, nativeError);
        InvocationTargetException error = assertThrows(InvocationTargetException.class, fixture::runFrame);
        assertSame(nativeError, error.getCause());
        assertEquals(2, begins);
        assertEquals(2, ends);
        assertEquals(2, fixture.bodyCalls());
    }

    @Test
    void missingWrongAndThrowingFrameCallbacksDoNotBreakFrame() throws Exception {
        Fixture fixture = new Fixture(UniformLocationLifecycleTransformer.Role.FRAME);
        fixture.runFrame();
        System.getProperties().put(UniformLocationHookBridge.BEGIN_PROPERTY, "wrong");
        fixture.runFrame();
        callbacks();
        failCallbacks = true;
        fixture.runFrame();
        assertEquals(3, fixture.bodyCalls());
    }

    @Test
    void observesNativeErrorWithoutAddingQueriesOrReplacingResult() throws Exception {
        callbacks();
        Fixture fixture = new Fixture(UniformLocationLifecycleTransformer.Role.ERROR);
        assertEquals(1282, fixture.runError());
        assertEquals(1282, errors);
        assertEquals(1, fixture.errorQueries);
        failCallbacks = true;
        assertEquals(1282, fixture.runError());
        assertEquals(2, fixture.errorQueries);
    }
    /**
     * With the upstream glGetError elision installed, the ERROR body arrives
     * composed as {@code aload; pop; iconst_0}: the transformer must emit the
     * same {@code error(gl, 0)} confirmation the elided query stood for, so
     * pending locations keep confirming. The downstream chain —
     * {@code bridge.error} → {@code cache.checkedError(0)} → confirmed →
     * {@code lookup} hits — is covered by the cache/bridge tests.
     */
    @Test
    void elidedErrorBodyStillConfirmsWithZero() throws Exception {
        callbacks();
        Fixture fixture = new Fixture(UniformLocationLifecycleTransformer.Role.ERROR, true);
        assertEquals(0, fixture.runError(), "the elision result is GL_NO_ERROR");
        assertEquals(0, fixture.errorQueries, "the elided body must not reach the native query");
        assertEquals(1, errorCalls, "the replacement callback must fire exactly once");
        assertEquals(0, errors, "the composed observation is the guaranteed no-error");
        assertSame(fixture.gl, errorArg, "the callback must receive the same GL receiver");
    }
    /**
     * An official body must take the native path byte-for-byte even when a
     * composed shape is registered — the elision variant is only admitted when
     * the observed body is the exact composed output.
     */
    @Test
    void officialBodyWithRegisteredComposedShapeIsByteIdentical() throws Exception {
        Loader loader = new Loader();
        ClassWriter writer = empty(UniformLocationLifecycleTransformer.Role.ERROR.owner(), false);
        MethodVisitor method =
                writer.visitMethod(Opcodes.ACC_PUBLIC, "a", "(Lcom/jogamp/opengl/GL;Ljava/lang/String;Z)I", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE, "com/jogamp/opengl/GL", "glGetError", "()I", true);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        byte[] reference = writer.toByteArray();
        ProtectionDomain domain =
                new ProtectionDomain(new CodeSource(ARTIFACT.toUri().toURL(), (Certificate[]) null), null);
        var plain = new UniformLocationLifecycleTransformer(
                loader, ARTIFACT, reference, UniformLocationLifecycleTransformer.Role.ERROR);
        byte[] nativeOutput = plain.transform(
                null, loader, UniformLocationLifecycleTransformer.Role.ERROR.owner(), null, domain, reference);
        assertNotNull(nativeOutput, plain.failure());
        var admitted = new UniformLocationLifecycleTransformer(
                loader, ARTIFACT, reference, UniformLocationLifecycleTransformer.Role.ERROR);
        admitted.acceptComposedShape("a", java.util.List.of("op:87", "op:3"));
        byte[] composedRegistered = admitted.transform(
                null, loader, UniformLocationLifecycleTransformer.Role.ERROR.owner(), null, domain, reference);
        assertNotNull(composedRegistered, admitted.failure());
        assertArrayEquals(
                nativeOutput,
                composedRegistered,
                "a registered composed shape must not change the official-body rewrite");
    }
    /**
     * The composed admission requires an {@code aload} receiver ahead of the
     * elided site; a reference whose query receiver is not a plain local load
     * must reject the elided body rather than emit a callback on a wrong gl.
     */
    @Test
    void composedBodyWithoutLoadableReceiverIsRejected() throws Exception {
        Loader loader = new Loader();
        String owner = UniformLocationLifecycleTransformer.Role.ERROR.owner();
        ClassWriter writer = empty(owner, false);
        MethodVisitor method =
                writer.visitMethod(Opcodes.ACC_PUBLIC, "a", "(Lcom/jogamp/opengl/GL;Ljava/lang/String;Z)I", null, null);
        method.visitCode();
        // getstatic foreign ; invokeinterface glGetError ; ireturn — receiver is
        // not a local load, so no elision-composed body can be instrumented.
        method.visitFieldInsn(Opcodes.GETSTATIC, owner, "failure", "Ljava/lang/RuntimeException;");
        method.visitTypeInsn(Opcodes.CHECKCAST, "com/jogamp/opengl/GL");
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE, "com/jogamp/opengl/GL", "glGetError", "()I", true);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        byte[] reference = writer.toByteArray();
        var transformer = new UniformLocationLifecycleTransformer(
                loader, ARTIFACT, reference, UniformLocationLifecycleTransformer.Role.ERROR);
        transformer.acceptComposedShape("a", java.util.List.of("op:87", "op:3"));
        ProtectionDomain domain =
                new ProtectionDomain(new CodeSource(ARTIFACT.toUri().toURL(), (Certificate[]) null), null);
        // Hand-built elided counterpart with a non-local receiver producer.
        ClassWriter elidedWriter = empty(owner, false);
        MethodVisitor elided = elidedWriter.visitMethod(
                Opcodes.ACC_PUBLIC, "a", "(Lcom/jogamp/opengl/GL;Ljava/lang/String;Z)I", null, null);
        elided.visitCode();
        elided.visitFieldInsn(Opcodes.GETSTATIC, owner, "failure", "Ljava/lang/RuntimeException;");
        elided.visitTypeInsn(Opcodes.CHECKCAST, "com/jogamp/opengl/GL");
        elided.visitInsn(Opcodes.POP);
        elided.visitInsn(Opcodes.ICONST_0);
        elided.visitInsn(Opcodes.IRETURN);
        elided.visitMaxs(0, 0);
        elided.visitEnd();
        elidedWriter.visitEnd();
        // Admit the exact elided shape, then prove the missing receiver is rejected.
        var shape = dev.turboism.adapter.cubism.optimization.ReviewedMethodShape.read(
                elidedWriter.toByteArray(), owner, "a", "(Lcom/jogamp/opengl/GL;Ljava/lang/String;Z)I");
        assertNotNull(shape);
        var gated = new UniformLocationLifecycleTransformer(
                loader, ARTIFACT, reference, UniformLocationLifecycleTransformer.Role.ERROR);
        gated.acceptComposedShape("a", shape);
        assertNull(gated.transform(null, loader, owner, null, domain, elidedWriter.toByteArray()));
        assertNotNull(gated.failure());
    }

    @Test
    void nativeErrorQueryExceptionIsNotRetriedOrSwallowed() throws Exception {
        callbacks();
        Fixture fixture = new Fixture(UniformLocationLifecycleTransformer.Role.ERROR);
        fixture.queryFailure = new IllegalArgumentException("native glGetError");
        InvocationTargetException thrown = assertThrows(InvocationTargetException.class, fixture::runError);
        assertSame(fixture.queryFailure, thrown.getCause());
        assertEquals(1, fixture.errorQueries);
        assertEquals(0, errors);
    }

    @Test
    void invalidatesBeforeEachNativeProgramOperation() throws Exception {
        callbacks();
        Fixture fixture = new Fixture(UniformLocationLifecycleTransformer.Role.MUTATIONS);
        fixture.type.getMethod("glLinkProgram", int.class).invoke(fixture.instance, 5);
        fixture.type.getMethod("glDeleteProgram", int.class).invoke(fixture.instance, 5);
        fixture.type
                .getMethod("glProgramBinary", int.class, int.class, java.nio.Buffer.class, int.class)
                .invoke(fixture.instance, 5, 0, null, 0);
        fixture.type.getMethod("glLinkProgramARB", long.class).invoke(fixture.instance, 5L);
        fixture.type.getMethod("glDeleteObjectARB", long.class).invoke(fixture.instance, 5L);
        assertEquals(5, mutations);
        assertEquals(5, fixture.bodyCalls());
        assertEquals(5, mutationsEnded);
        assertEquals(71, ended);
        failCallbacks = true;
        fixture.type.getMethod("glLinkProgram", int.class).invoke(fixture.instance, 5);
        assertEquals(6, fixture.bodyCalls());
    }

    @Test
    void sharedEsProgramOperationsAlwaysCompleteTheirScopes() throws Exception {
        callbacks();
        Fixture fixture = new Fixture(UniformLocationLifecycleTransformer.Role.MUTATIONS_ES);
        fixture.type.getMethod("glLinkProgram", int.class).invoke(fixture.instance, 5);
        fixture.type.getMethod("glDeleteProgram", int.class).invoke(fixture.instance, 5);
        fixture.type
                .getMethod("glProgramBinary", int.class, int.class, java.nio.Buffer.class, int.class)
                .invoke(fixture.instance, 5, 0, null, 0);
        assertEquals(3, mutations);
        assertEquals(3, mutationsEnded);
        assertEquals(3, fixture.bodyCalls());
        RuntimeException nativeError = new IllegalArgumentException("native ES link");
        fixture.type.getField("failure").set(null, nativeError);
        InvocationTargetException thrown = assertThrows(
                InvocationTargetException.class,
                () -> fixture.type.getMethod("glLinkProgram", int.class).invoke(fixture.instance, 5));
        assertSame(nativeError, thrown.getCause());
        assertEquals(4, mutationsEnded);
    }

    @Test
    void nativeProgramOperationFailureRemainsOriginal() throws Exception {
        callbacks();
        Fixture fixture = new Fixture(UniformLocationLifecycleTransformer.Role.MUTATIONS);
        RuntimeException nativeError = new IllegalArgumentException("native link");
        fixture.type.getField("failure").set(null, nativeError);
        InvocationTargetException thrown = assertThrows(
                InvocationTargetException.class,
                () -> fixture.type.getMethod("glLinkProgram", int.class).invoke(fixture.instance, 5));
        assertSame(nativeError, thrown.getCause());
        assertEquals(1, mutations);
        assertEquals(1, fixture.bodyCalls());
        assertEquals(1, mutationsEnded);
        assertEquals(71, ended);
    }

    private static final class Loader extends ClassLoader {
        Class<?> define(String name, byte[] bytes) {
            return defineClass(name.replace('/', '.'), bytes, 0, bytes.length);
        }
    }

    private static ClassWriter empty(String owner, boolean itf) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC | (itf ? Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT : Opcodes.ACC_SUPER),
                owner,
                null,
                "java/lang/Object",
                null);
        if (!itf) {
            writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "calls", "I", null, null)
                    .visitEnd();
            writer.visitField(
                            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                            "failure",
                            "Ljava/lang/RuntimeException;",
                            null,
                            null)
                    .visitEnd();
            MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            method.visitCode();
            method.visitVarInsn(Opcodes.ALOAD, 0);
            method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            method.visitInsn(Opcodes.RETURN);
            method.visitMaxs(0, 0);
            method.visitEnd();
        }
        return writer;
    }

    private static void body(ClassWriter writer, String owner, String name, String descriptor) {
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, name, descriptor, null, null);
        method.visitCode();
        method.visitFieldInsn(Opcodes.GETSTATIC, owner, "calls", "I");
        method.visitInsn(Opcodes.ICONST_1);
        method.visitInsn(Opcodes.IADD);
        method.visitFieldInsn(Opcodes.PUTSTATIC, owner, "calls", "I");
        method.visitFieldInsn(Opcodes.GETSTATIC, owner, "failure", "Ljava/lang/RuntimeException;");
        Label normal = new Label();
        method.visitInsn(Opcodes.DUP);
        method.visitJumpInsn(Opcodes.IFNULL, normal);
        method.visitInsn(Opcodes.ATHROW);
        method.visitLabel(normal);
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static final class Fixture {
        final Class<?> type;
        final Object instance, gl;
        final Class<?> frameType, rectType, glType;
        int errorQueries;
        RuntimeException queryFailure;

        Fixture(UniformLocationLifecycleTransformer.Role role) throws Exception {
            this(role, false);
        }
        /**
         * {@code elided} feeds the body the upstream glGetError elision actually
         * produces on the same reference, admitted through the generated composed
         * shape — the exact state the retransform chain presents when both hooks
         * are installed.
         */
        Fixture(UniformLocationLifecycleTransformer.Role role, boolean elided) throws Exception {
            Loader loader = new Loader();
            ClassWriter ctx = empty("com/live2d/graphics3d/a", false);
            ctx.visitEnd();
            frameType = loader.define("com/live2d/graphics3d/a", ctx.toByteArray());
            ClassWriter rect = empty("com/live2d/type/CRect", false);
            rect.visitEnd();
            rectType = loader.define("com/live2d/type/CRect", rect.toByteArray());
            ClassWriter glWriter = empty("com/jogamp/opengl/GL", true);
            glWriter.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "glGetError", "()I", null, null)
                    .visitEnd();
            glWriter.visitEnd();
            glType = loader.define("com/jogamp/opengl/GL", glWriter.toByteArray());
            gl = Proxy.newProxyInstance(loader, new Class<?>[] {glType}, (proxy, method, args) -> {
                errorQueries++;
                if (queryFailure != null) throw queryFailure;
                return 1282;
            });
            String owner = role.owner();
            ClassWriter writer = empty(owner, false);
            if (role == UniformLocationLifecycleTransformer.Role.ERROR) {
                MethodVisitor method = writer.visitMethod(
                        Opcodes.ACC_PUBLIC, "a", "(Lcom/jogamp/opengl/GL;Ljava/lang/String;Z)I", null, null);
                method.visitCode();
                method.visitVarInsn(Opcodes.ALOAD, 1);
                method.visitMethodInsn(Opcodes.INVOKEINTERFACE, "com/jogamp/opengl/GL", "glGetError", "()I", true);
                method.visitInsn(Opcodes.IRETURN);
                method.visitMaxs(0, 0);
                method.visitEnd();
            } else for (var target : role.methods().entrySet()) body(writer, owner, target.getKey(), target.getValue());
            writer.visitEnd();
            byte[] reference = writer.toByteArray();
            ProtectionDomain domain =
                    new ProtectionDomain(new CodeSource(ARTIFACT.toUri().toURL(), (Certificate[]) null), null);
            byte[] input = reference;
            UniformLocationLifecycleTransformer transformer =
                    new UniformLocationLifecycleTransformer(loader, ARTIFACT, reference, role);
            if (elided) {
                var target = dev.turboism.adapter.cubism.optimization.glerror.GlGetErrorElisionTarget.of(
                                dev.turboism.mapping.verification.ReviewedHostArtifacts.CUBISM_5_3_03)
                        .orElseThrow();
                var elision = new dev.turboism.adapter.cubism.optimization.glerror.GlGetErrorElisionTransformer(
                        loader, ARTIFACT, reference, target);
                input = elision.transform(null, loader, owner, null, domain, reference);
                assertNotNull(input, elision.failure());
                transformer.acceptComposedShape(
                        "a",
                        dev.turboism.adapter.cubism.optimization.ReviewedMethodShape.read(
                                input, owner, "a", "(Lcom/jogamp/opengl/GL;Ljava/lang/String;Z)I"));
            }
            byte[] rewritten = transformer.transform(null, loader, owner, null, domain, input);
            assertNotNull(rewritten, transformer.failure());
            type = loader.define(owner, rewritten);
            instance = type.getConstructor().newInstance();
        }

        void runFrame() throws Exception {
            type.getMethod("render3d", frameType).invoke(instance, (Object) null);
        }

        int runError() throws Exception {
            return (Integer)
                    type.getMethod("a", glType, String.class, boolean.class).invoke(instance, gl, "test", false);
        }

        int bodyCalls() throws Exception {
            return type.getField("calls").getInt(null);
        }
    }
}
