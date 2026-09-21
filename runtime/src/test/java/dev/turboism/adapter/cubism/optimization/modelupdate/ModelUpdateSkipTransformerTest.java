package dev.turboism.adapter.cubism.optimization.modelupdate;

import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.Permissions;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Verifies the entry guard and after-update injection on a synthetic class carrying the
 * exact reviewed descriptor for each supported version. The class bytes stand in for the
 * official host method shape; the transformer admits them only through the reviewed
 * artifact path.
 */
public class ModelUpdateSkipTransformerTest {

    private static final String CTX = "com/live2d/cubism/view/context/CEViewContext";
    private static final String MODEL = "com/live2d/cubism/doc/model/CModel";
    private static final String UC = "com/live2d/cubism/doc/model/ax";
    private static final String BL = "com/live2d/cubism/view/context/bL";

    private static final ModelUpdateSkipTarget T5303 =
        ModelUpdateSkipTarget.of(ReviewedHostArtifacts.CUBISM_5_3_03).orElseThrow();
    private static final ModelUpdateSkipTarget T5302 =
        ModelUpdateSkipTarget.of(ReviewedHostArtifacts.CUBISM_5_3_02).orElseThrow();
    private static final ModelUpdateSkipTarget T5203 =
        ModelUpdateSkipTarget.of(ReviewedHostArtifacts.CUBISM_5_2_03).orElseThrow();

    private static final class Loader extends ClassLoader {
        private final ProtectionDomain domain;
        Loader(Path artifact) throws Exception {
            super(ModelUpdateSkipTransformerTest.class.getClassLoader());
            domain = new ProtectionDomain(
                new CodeSource(artifact.toUri().toURL(), (java.security.CodeSigner[]) null),
                new Permissions());
        }
        Class<?> define(String name, byte[] bytes) {
            return defineClass(name.replace('/', '.'), bytes, 0, bytes.length, domain);
        }
    }

    private static byte[] empty(String name) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        w.visit(V17, ACC_PUBLIC, name, null, "java/lang/Object", null);
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
     * A class {@code owner} with {@code a(descriptor)} that increments a static counter,
     * optionally returns early on the first boolean argument, then returns.
     * {@code changed} adds a NOP so the reviewed shape no longer matches.
     */
    private static byte[] target(ModelUpdateSkipTarget target, boolean changed,
                                 boolean twoReturns) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        w.visit(V17, ACC_PUBLIC, target.owner(), null, "java/lang/Object", null);
        w.visitField(ACC_PUBLIC | ACC_STATIC, "calls", "I", null, null).visitEnd();
        MethodVisitor m = w.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        m = w.visitMethod(ACC_PUBLIC, ModelUpdateSkipTarget.METHOD, target.methodDescriptor(),
            null, null);
        m.visitCode();
        if (changed) m.visitInsn(NOP);
        m.visitFieldInsn(GETSTATIC, target.owner(), "calls", "I");
        m.visitInsn(ICONST_1);
        m.visitInsn(IADD);
        m.visitFieldInsn(PUTSTATIC, target.owner(), "calls", "I");
        if (twoReturns) {
            Label end = new Label();
            m.visitVarInsn(ILOAD, 3);
            m.visitJumpInsn(IFEQ, end);
            m.visitInsn(RETURN);
            m.visitLabel(end);
        }
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    private static Object slot(String key) {
        return System.getProperties().get(key);
    }

    private static void put(String key, Object value) {
        if (value == null) System.getProperties().remove(key);
        else System.getProperties().put(key, value);
    }

    @Test void earlyReturnSkipsBodyAndNeverSignalsCompletion() throws Exception {
        exercise(T5303, (Predicate<Object[]>) args -> true, 0, 0);
    }

    @Test void falsePredicateRunsBodyAndSignalsCompletionWithModel() throws Exception {
        exercise(T5303, (Predicate<Object[]>) args -> false, 1, 1);
    }

    @Test void absentWrongAndThrowingSlotsFallThroughToNative() throws Exception {
        exercise(T5303, null, 1, 1);
        exercise(T5303, "not a predicate", 1, 1);
        exercise(T5303, (Predicate<Object[]>) args -> {
            throw new AssertionError("predicate failure");
        }, 1, 1);
        exercise(T5303, (Predicate<Object[]>) args -> false, "foreign consumer", 1, 0);
    }

    @Test void afterUpdateFiresOnEveryNativeReturn() throws Exception {
        Path artifact = Files.createTempFile("host", ".jar");
        Loader loader = new Loader(artifact);
        byte[] reference = target(T5303, false, true);
        var transformer = new ModelUpdateSkipTransformer(loader, artifact, reference, T5303);
        byte[] output = transformer.transform(null, loader, T5303.owner(), null,
            loader.domain, reference);
        assertNotNull(output, transformer.failure());
        Class<?> type = loader.define(T5303.owner(), output);
        Class<?> contextType = loader.define(CTX, empty(CTX));
        Class<?> modelType = loader.define(MODEL, empty(MODEL));
        Class<?> updateType = loader.define(UC, empty(UC));
        Class<?> tailType = loader.define(BL, empty(BL));
        Object callback = slot(ModelUpdateSkipBridge.CALLBACK_PROPERTY);
        Object after = slot(ModelUpdateSkipBridge.AFTER_PROPERTY);
        List<Object> completed = new ArrayList<>();
        try {
            put(ModelUpdateSkipBridge.CALLBACK_PROPERTY, (Predicate<Object[]>) args -> false);
            put(ModelUpdateSkipBridge.AFTER_PROPERTY, (Consumer<Object>) completed::add);
            Method method = type.getMethod(ModelUpdateSkipTarget.METHOD,
                contextType, modelType, boolean.class, updateType, boolean.class, tailType,
                boolean.class);
            Object model = modelType.getDeclaredConstructor().newInstance();
            Object instance = type.getDeclaredConstructor().newInstance();
            method.invoke(instance, null, model, true, null, false, null, false);
            method.invoke(instance, null, model, false, null, false, null, false);
            assertEquals(2, type.getField("calls").getInt(null));
            assertEquals(List.of(model, model), completed);
        } finally {
            put(ModelUpdateSkipBridge.CALLBACK_PROPERTY, callback);
            put(ModelUpdateSkipBridge.AFTER_PROPERTY, after);
        }
    }

    @Test void entryArgumentsAreBoxedInDescriptorOrder() throws Exception {
        Path artifact = Files.createTempFile("host", ".jar");
        Loader loader = new Loader(artifact);
        byte[] reference = target(T5303, false, false);
        var transformer = new ModelUpdateSkipTransformer(loader, artifact, reference, T5303);
        byte[] output = transformer.transform(null, loader, T5303.owner(), null,
            loader.domain, reference);
        assertNotNull(output, transformer.failure());
        Class<?> type = loader.define(T5303.owner(), output);
        Class<?> contextType = loader.define(CTX, empty(CTX));
        Class<?> modelType = loader.define(MODEL, empty(MODEL));
        Class<?> updateType = loader.define(UC, empty(UC));
        Class<?> tailType = loader.define(BL, empty(BL));
        Object[][] seen = new Object[1][];
        Object callback = slot(ModelUpdateSkipBridge.CALLBACK_PROPERTY);
        try {
            put(ModelUpdateSkipBridge.CALLBACK_PROPERTY, (Predicate<Object[]>) args -> {
                seen[0] = args;
                return true;
            });
            Method method = type.getMethod(ModelUpdateSkipTarget.METHOD,
                contextType, modelType, boolean.class, updateType, boolean.class, tailType,
                boolean.class);
            Object instance = type.getDeclaredConstructor().newInstance();
            Object model = modelType.getDeclaredConstructor().newInstance();
            Object marker = tailType.getDeclaredConstructor().newInstance();
            method.invoke(instance, null, model, true, null, false, marker, true);
            assertSame(instance, seen[0][0]);
            assertNull(seen[0][1]);
            assertSame(model, seen[0][2]);
            assertEquals(Boolean.TRUE, seen[0][3]);
            assertNull(seen[0][4]);
            assertEquals(Boolean.FALSE, seen[0][5]);
            assertSame(marker, seen[0][6]);
            assertEquals(Boolean.TRUE, seen[0][7]);
        } finally {
            put(ModelUpdateSkipBridge.CALLBACK_PROPERTY, callback);
        }
    }

    @Test void everyDescriptorVariantIsAdmitted() throws Exception {
        for (ModelUpdateSkipTarget target : List.of(T5203, T5302, T5303)) {
            Path artifact = Files.createTempFile("host", ".jar");
            Loader loader = new Loader(artifact);
            byte[] reference = target(target, false, false);
            var transformer = new ModelUpdateSkipTransformer(loader, artifact, reference, target);
            byte[] output = transformer.transform(null, loader, target.owner(), null,
                loader.domain, reference);
            assertNotNull(output, target.version() + " rejected: " + transformer.failure());
            assertEquals(1, transformer.matches());
            assertNotNull(transformer.beforeSha256());
        }
    }

    @Test void changedBodyIsRejectedWithFailure() throws Exception {
        Path artifact = Files.createTempFile("host", ".jar");
        Loader loader = new Loader(artifact);
        byte[] reference = target(T5303, false, false);
        var transformer = new ModelUpdateSkipTransformer(loader, artifact, reference, T5303);
        assertNull(transformer.transform(null, loader, T5303.owner(), null, loader.domain,
            target(T5303, true, false)));
        assertNotNull(transformer.failure());
        assertEquals(0, transformer.matches());
    }

    @Test void wrongLoaderNameAndArtifactAreRejected() throws Exception {
        Path artifact = Files.createTempFile("host", ".jar");
        Loader loader = new Loader(artifact);
        byte[] reference = target(T5303, false, false);
        var transformer = new ModelUpdateSkipTransformer(loader, artifact, reference, T5303);
        assertNull(transformer.transform(null, new Loader(artifact), T5303.owner(), null,
            loader.domain, reference));
        assertNull(transformer.transform(null, loader, "com/live2d/cubism/view/au", null,
            loader.domain, reference));
        assertNull(transformer.transform(null, loader, T5303.owner(), null, null, reference));
        Loader alien = new Loader(Files.createTempFile("other", ".jar"));
        assertNull(transformer.transform(null, loader, T5303.owner(), null, alien.domain,
            reference));
        assertNotNull(transformer.failure());
    }

    private static void exercise(ModelUpdateSkipTarget target, Object callback,
                                 int expectedCalls, int expectedCompleted) throws Exception {
        exercise(target, callback, null, expectedCalls, expectedCompleted);
    }

    private static void exercise(ModelUpdateSkipTarget target, Object callback, Object afterSlot,
                                 int expectedCalls, int expectedCompleted) throws Exception {
        Path artifact = Files.createTempFile("host", ".jar");
        Loader loader = new Loader(artifact);
        byte[] reference = target(target, false, false);
        var transformer = new ModelUpdateSkipTransformer(loader, artifact, reference, target);
        byte[] output = transformer.transform(null, loader, target.owner(), null,
            loader.domain, reference);
        assertNotNull(output, transformer.failure());
        assertEquals(1, transformer.matches());
        Class<?> type = loader.define(target.owner(), output);
        Object savedCallback = slot(ModelUpdateSkipBridge.CALLBACK_PROPERTY);
        Object savedAfter = slot(ModelUpdateSkipBridge.AFTER_PROPERTY);
        List<Object> completed = new ArrayList<>();
        try {
            put(ModelUpdateSkipBridge.CALLBACK_PROPERTY, callback);
            put(ModelUpdateSkipBridge.AFTER_PROPERTY,
                afterSlot == null ? (Consumer<Object>) completed::add : afterSlot);
            Class<?>[] parameters = argumentTypes(loader, target);
            Method method = type.getMethod(ModelUpdateSkipTarget.METHOD, parameters);
            Object[] args = new Object[parameters.length];
            for (int i = 0; i < args.length; i++) {
                if (parameters[i] == boolean.class) args[i] = false;
            }
            method.invoke(type.getDeclaredConstructor().newInstance(), args);
            assertEquals(expectedCalls, type.getField("calls").getInt(null));
            assertEquals(expectedCompleted, completed.size());
        } finally {
            put(ModelUpdateSkipBridge.CALLBACK_PROPERTY, savedCallback);
            put(ModelUpdateSkipBridge.AFTER_PROPERTY, savedAfter);
        }
    }

    private static Class<?>[] argumentTypes(Loader loader, ModelUpdateSkipTarget target) {
        Type[] types = Type.getArgumentTypes(target.methodDescriptor());
        Class<?>[] result = new Class<?>[types.length];
        for (int i = 0; i < types.length; i++) {
            result[i] = types[i].equals(Type.BOOLEAN_TYPE) ? boolean.class
                : loader.define(types[i].getInternalName(), empty(types[i].getInternalName()));
        }
        return result;
    }
}
