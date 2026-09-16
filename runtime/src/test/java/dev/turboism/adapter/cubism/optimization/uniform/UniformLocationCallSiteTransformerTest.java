package dev.turboism.adapter.cubism.optimization.uniform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Executes rewritten bytecode, not a simulation of the injected branch. */
class UniformLocationCallSiteTransformerTest {
    private static final String OWNER = "com/live2d/graphics3d/shader/GShader";
    private static final String CONTEXT = "com/live2d/graphics3d/a";
    private static final String MATERIAL = "com/live2d/graphics3d/material/GMaterial";
    private static final String MATRIX = "com/live2d/graphics3d/type/GMatrix44";
    private static final String GL = "com/jogamp/opengl/GL3";
    private static final String DESC = "(L" + CONTEXT + ";L" + MATERIAL + ";L" + MATRIX + ";)V";
    private static final Path ARTIFACT = Path.of("reviewed-cubism.jar").toAbsolutePath();
    private static int answer, observed, recorded;
    private static boolean failLookup, failRecord;

    @AfterEach void clear() {
        System.getProperties().remove(UniformLocationCallSiteTransformer.LOOKUP_PROPERTY);
        System.getProperties().remove(UniformLocationCallSiteTransformer.RECORD_PROPERTY);
        answer = observed = recorded = 0;
        failLookup = failRecord = false;
    }

    public static int lookup(Object gl, int program, String name) {
        if (failLookup) throw new IllegalStateException("lookup failure");
        return answer;
    }
    public static void record(Object gl, int program, String name, int result) {
        observed++;
        recorded = result;
        if (failRecord) throw new IllegalStateException("observer failure");
    }
    private static MethodHandle lookupHandle() throws Exception {
        return MethodHandles.lookup().findStatic(UniformLocationCallSiteTransformerTest.class, "lookup",
            MethodType.methodType(int.class, Object.class, int.class, String.class));
    }
    private static void installCallbacks() throws Exception {
        System.getProperties().put(UniformLocationCallSiteTransformer.LOOKUP_PROPERTY, lookupHandle());
        System.getProperties().put(UniformLocationCallSiteTransformer.RECORD_PROPERTY,
            MethodHandles.lookup().findStatic(UniformLocationCallSiteTransformerTest.class, "record",
                MethodType.methodType(void.class, Object.class, int.class, String.class, int.class)));
    }

    @Test void missingAndWronglyTypedCallbacksKeepNativeQueryAndDraw() throws Exception {
        Fixture fixture = new Fixture(false);
        assertEquals(17, fixture.invoke());
        System.getProperties().put(UniformLocationCallSiteTransformer.LOOKUP_PROPERTY, "not a handle");
        assertEquals(17, fixture.invoke());
        assertEquals(2, fixture.queries.get());
        assertEquals(2, fixture.draws.get());
    }
    @Test void positiveZeroAndNegativeLocationsSkipOnlyQuery() throws Exception {
        installCallbacks();
        Fixture fixture = new Fixture(false);
        for (int value : new int[]{42, 0, -1}) {
            answer = value;
            assertEquals(value, fixture.invoke());
        }
        assertEquals(0, fixture.queries.get());
        assertEquals(0, observed);
        assertEquals(3, fixture.draws.get());
    }
    @Test void missAndCallbackFailureCallNativeExactlyOnce() throws Exception {
        installCallbacks();
        Fixture fixture = new Fixture(false);
        answer = Integer.MIN_VALUE;
        assertEquals(17, fixture.invoke());
        assertEquals(17, recorded);
        failLookup = true;
        failRecord = true;
        assertEquals(17, fixture.invoke());
        assertEquals(2, fixture.queries.get());
        assertEquals(2, observed);
    }
    @Test void wrongMethodHandleSignaturesFallBackWithoutChangingNativeResult() throws Exception {
        Fixture fixture = new Fixture(false);
        System.getProperties().put(UniformLocationCallSiteTransformer.LOOKUP_PROPERTY,
            MethodHandles.constant(int.class, 99));
        System.getProperties().put(UniformLocationCallSiteTransformer.RECORD_PROPERTY,
            MethodHandles.constant(int.class, 99));
        assertEquals(17, fixture.invoke());
        assertEquals(1, fixture.queries.get());
    }
    @Test void nativeFailureIsNeverSwallowedOrRetried() throws Exception {
        installCallbacks(); answer = Integer.MIN_VALUE;
        Fixture fixture = new Fixture(false);
        fixture.failure = new IllegalArgumentException("native failure");
        InvocationTargetException thrown = assertThrows(InvocationTargetException.class, fixture::invoke);
        assertSame(fixture.failure, thrown.getCause());
        assertEquals(1, fixture.queries.get());
        assertEquals(0, observed);
        assertEquals(0, fixture.draws.get());
    }
    @Test void nullReceiverNeverUsesCache() throws Exception {
        installCallbacks(); answer = 8;
        Fixture fixture = new Fixture(false);
        fixture.contextType.getField("gl").set(fixture.context, null);
        InvocationTargetException thrown = assertThrows(InvocationTargetException.class, fixture::invoke);
        assertInstanceOf(NullPointerException.class, thrown.getCause());
    }
    @Test void retainsNativeCatchHandlerPriority() throws Exception {
        installCallbacks(); answer = Integer.MIN_VALUE; failLookup = true;
        Fixture fixture = new Fixture(true);
        fixture.failure = new IllegalArgumentException("caught by native handler");
        assertEquals(-7, fixture.invoke());
        assertEquals(1, fixture.queries.get());
    }
    @Test void rejectsChangedSourceLoaderAndBody() throws Exception {
        Loader loader = new Loader();
        byte[] reference = shader(false);
        UniformLocationCallSiteTransformer transformer = new UniformLocationCallSiteTransformer(loader, ARTIFACT, reference);
        ProtectionDomain domain = domain(ARTIFACT);
        assertNull(transformer.transform(null, new Loader(), OWNER, null, domain, reference));
        assertNull(transformer.transform(null, loader, OWNER, null, domain(Path.of("different.jar")), reference));
        assertNull(transformer.transform(null, loader, OWNER, null, domain, shader(true)));
        assertNotNull(transformer.failure());
    }
    @Test void changesOnlyReviewedMethodAndDoesNotAllocateArgumentsArrays() throws Exception {
        Fixture fixture = new Fixture(false);
        int[] arrays = {0}, nativeQueries = {0}, draws = {0};
        new ClassReader(fixture.transformed).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitTypeInsn(int op, String type) { if (op == Opcodes.ANEWARRAY) arrays[0]++; }
                    @Override public void visitMethodInsn(int op, String owner, String method, String desc, boolean itf) {
                        if (method.equals("glGetUniformLocation")) nativeQueries[0]++;
                        if (method.equals("glDrawArrays")) draws[0]++;
                    }
                };
            }
        }, 0);
        assertEquals(0, arrays[0]);
        assertEquals(1, nativeQueries[0]);
        assertEquals(1, draws[0]);
        assertEquals(2, fixture.shaderType.getDeclaredFields().length);
        assertEquals(1, fixture.shaderType.getDeclaredMethods().length);
    }

    private static ProtectionDomain domain(Path path) throws Exception {
        return new ProtectionDomain(new CodeSource(path.toAbsolutePath().toUri().toURL(), (Certificate[]) null), null);
    }
    private static ClassWriter type(String name, boolean itf) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | (itf ? Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT : Opcodes.ACC_SUPER), name, null, "java/lang/Object", null);
        if (!itf) {
            MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            init.visitCode(); init.visitVarInsn(Opcodes.ALOAD, 0);
            init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            init.visitInsn(Opcodes.RETURN); init.visitMaxs(0, 0); init.visitEnd();
        }
        return writer;
    }
    private static byte[] simple(String name) {
        ClassWriter writer = type(name, false); writer.visitEnd(); return writer.toByteArray();
    }
    private static byte[] shader(boolean catches) {
        ClassWriter writer = type(OWNER, false);
        writer.visitField(Opcodes.ACC_PUBLIC, "result", "I", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC, "sentinel", "I", null, null).visitEnd();
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "preDraw_exe", DESC, null, null);
        Label start = new Label(), end = new Label(), handler = new Label();
        if (catches) method.visitTryCatchBlock(start, end, handler, "java/lang/IllegalArgumentException");
        method.visitCode(); method.visitLabel(start);
        method.visitIntInsn(Opcodes.BIPUSH, 63); method.visitVarInsn(Opcodes.ISTORE, 7);
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitFieldInsn(Opcodes.GETFIELD, CONTEXT, "gl", "L" + GL + ";");
        method.visitIntInsn(Opcodes.BIPUSH, 7); method.visitLdcInsn("color");
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE, GL, "glGetUniformLocation", "(ILjava/lang/String;)I", true);
        method.visitVarInsn(Opcodes.ISTORE, 4);
        method.visitVarInsn(Opcodes.ALOAD, 0); method.visitVarInsn(Opcodes.ILOAD, 4);
        method.visitFieldInsn(Opcodes.PUTFIELD, OWNER, "result", "I");
        method.visitVarInsn(Opcodes.ALOAD, 0); method.visitVarInsn(Opcodes.ILOAD, 7);
        method.visitFieldInsn(Opcodes.PUTFIELD, OWNER, "sentinel", "I");
        method.visitVarInsn(Opcodes.ALOAD, 1); method.visitFieldInsn(Opcodes.GETFIELD, CONTEXT, "gl", "L" + GL + ";");
        method.visitInsn(Opcodes.ICONST_4); method.visitInsn(Opcodes.ICONST_0); method.visitInsn(Opcodes.ICONST_3);
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE, GL, "glDrawArrays", "(III)V", true);
        method.visitLabel(end); method.visitInsn(Opcodes.RETURN);
        if (catches) {
            method.visitLabel(handler); method.visitInsn(Opcodes.POP);
            method.visitVarInsn(Opcodes.ALOAD, 0); method.visitIntInsn(Opcodes.BIPUSH, -7);
            method.visitFieldInsn(Opcodes.PUTFIELD, OWNER, "result", "I"); method.visitInsn(Opcodes.RETURN);
        }
        method.visitMaxs(0, 0); method.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }
    private static final class Loader extends ClassLoader {
        Loader() { super(UniformLocationCallSiteTransformerTest.class.getClassLoader()); }
        Class<?> define(String name, byte[] bytes) { return defineClass(name.replace('/', '.'), bytes, 0, bytes.length); }
    }
    private static final class Fixture {
        final AtomicInteger queries = new AtomicInteger(), draws = new AtomicInteger();
        final Class<?> shaderType, contextType;
        final Object shader, context;
        final java.lang.reflect.Method method;
        final byte[] transformed;
        RuntimeException failure;
        Fixture(boolean catches) throws Exception {
            Loader loader = new Loader();
            ClassWriter api = type(GL, true);
            api.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "glGetUniformLocation", "(ILjava/lang/String;)I", null, null).visitEnd();
            api.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "glDrawArrays", "(III)V", null, null).visitEnd();
            api.visitEnd(); Class<?> glType = loader.define(GL, api.toByteArray());
            ClassWriter ctx = type(CONTEXT, false);
            ctx.visitField(Opcodes.ACC_PUBLIC, "gl", "L" + GL + ";", null, null).visitEnd(); ctx.visitEnd();
            contextType = loader.define(CONTEXT, ctx.toByteArray());
            Class<?> material = loader.define(MATERIAL, simple(MATERIAL)), matrix = loader.define(MATRIX, simple(MATRIX));
            context = contextType.getConstructor().newInstance();
            contextType.getField("gl").set(context, Proxy.newProxyInstance(loader, new Class<?>[]{glType}, (proxy, method, args) -> {
                if (method.getName().equals("glGetUniformLocation")) { queries.incrementAndGet(); if (failure != null) throw failure; return 17; }
                if (method.getName().equals("glDrawArrays")) { draws.incrementAndGet(); return null; }
                throw new AssertionError(method);
            }));
            byte[] reference = shader(catches);
            UniformLocationCallSiteTransformer transformer = new UniformLocationCallSiteTransformer(loader, ARTIFACT, reference);
            transformed = transformer.transform(null, loader, OWNER, null, domain(ARTIFACT), reference);
            assertNotNull(transformed, transformer.failure());
            shaderType = loader.define(OWNER, transformed); shader = shaderType.getConstructor().newInstance();
            method = shaderType.getDeclaredMethod("preDraw_exe", contextType, material, matrix); method.setAccessible(true);
        }
        int invoke() throws Exception {
            method.invoke(shader, context, null, null);
            if (shaderType.getField("result").getInt(shader) != -7) assertEquals(63, shaderType.getField("sentinel").getInt(shader));
            return shaderType.getField("result").getInt(shader);
        }
    }
}
