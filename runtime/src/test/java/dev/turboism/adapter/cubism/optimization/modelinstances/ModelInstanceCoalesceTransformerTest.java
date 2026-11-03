package dev.turboism.adapter.cubism.optimization.modelinstances;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.objectweb.asm.Opcodes.*;

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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

class ModelInstanceCoalesceTransformerTest {

    private static final String SOURCE = ModelInstanceCoalesceTarget.SOURCE_OWNER;
    private static final String EDIT = ModelInstanceCoalesceTarget.EDIT_MODE_OWNER;
    private static final String UNDO = ModelInstanceCoalesceTarget.UNDO_MANAGER_OWNER;

    private static final class Loader extends ClassLoader {
        private final ProtectionDomain domain;

        Loader(final Path artifact) throws Exception {
            super(ModelInstanceCoalesceTransformerTest.class.getClassLoader());
            domain = new ProtectionDomain(
                    new CodeSource(artifact.toUri().toURL(), (java.security.CodeSigner[]) null), new Permissions());
        }

        Class<?> define(final String name, final byte[] bytes) {
            return defineClass(name.replace('/', '.'), bytes, 0, bytes.length, domain);
        }
    }

    @AfterEach
    void clearSlots() {
        for (final String key : List.of(
                ModelInstanceCoalesceBridge.UPDATE_CALLBACK_PROPERTY,
                ModelInstanceCoalesceBridge.AFTER_UPDATE_PROPERTY,
                ModelInstanceCoalesceBridge.COMMIT_CALLBACK_PROPERTY,
                ModelInstanceCoalesceBridge.AFTER_COMMIT_PROPERTY,
                ModelInstanceCoalesceBridge.INVALIDATE_CALLBACK_PROPERTY)) {
            System.getProperties().remove(key);
        }
    }

    private static byte[] owner(final String name, final String method, final boolean changed) {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC, name, null, "java/lang/Object", null);
        writer.visitField(ACC_PUBLIC | ACC_STATIC, "calls", "I", null, null).visitEnd();
        final MethodVisitor init = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        final MethodVisitor body = writer.visitMethod(ACC_PUBLIC, method, "()V", null, null);
        body.visitCode();
        if (changed) body.visitInsn(NOP);
        body.visitFieldInsn(GETSTATIC, name, "calls", "I");
        body.visitInsn(ICONST_1);
        body.visitInsn(IADD);
        body.visitFieldInsn(PUTSTATIC, name, "calls", "I");
        body.visitInsn(RETURN);
        body.visitMaxs(0, 0);
        body.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] undoManager(final boolean changed) {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC, UNDO, null, "java/lang/Object", null);
        writer.visitField(ACC_PUBLIC | ACC_STATIC, "calls", "I", null, null).visitEnd();
        final MethodVisitor init = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        for (final String method : List.of("undo", "redo")) {
            final MethodVisitor body = writer.visitMethod(ACC_PUBLIC, method, "()V", null, null);
            body.visitCode();
            if (changed && "undo".equals(method)) body.visitInsn(NOP);
            body.visitFieldInsn(GETSTATIC, UNDO, "calls", "I");
            body.visitInsn(ICONST_1);
            body.visitInsn(IADD);
            body.visitFieldInsn(PUTSTATIC, UNDO, "calls", "I");
            body.visitInsn(RETURN);
            body.visitMaxs(0, 0);
            body.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static Path artifact() throws Exception {
        return Files.createTempFile("coalesce-host", ".jar");
    }

    @Test
    void predicateSkipAvoidsTheNativeBodyAndFalseRunsIt() throws Exception {
        final Path artifact = artifact();
        final Loader loader = new Loader(artifact);
        final byte[] reference = owner(SOURCE, ModelInstanceCoalesceTarget.UPDATE_METHOD, false);
        final ModelInstanceCoalesceTransformer transformer = new ModelInstanceCoalesceTransformer(
                loader, artifact, reference, owner(EDIT, "updateLastModifiedTime", false), undoManager(false));
        final byte[] output = transformer.transform(null, loader, SOURCE, null, loader.domain, reference);
        assertNotNull(output, transformer.failure());
        final Class<?> type = loader.define(SOURCE, output);
        final List<Object> completed = new ArrayList<>();
        try {
            System.getProperties()
                    .put(ModelInstanceCoalesceBridge.UPDATE_CALLBACK_PROPERTY, (Predicate<Object>) ignored -> true);
            final Method update = type.getMethod(ModelInstanceCoalesceTarget.UPDATE_METHOD);
            update.invoke(type.getDeclaredConstructor().newInstance());
            assertEquals(0, type.getField("calls").getInt(null));

            System.getProperties()
                    .put(ModelInstanceCoalesceBridge.UPDATE_CALLBACK_PROPERTY, (Predicate<Object>) ignored -> false);
            System.getProperties()
                    .put(ModelInstanceCoalesceBridge.AFTER_UPDATE_PROPERTY, (Consumer<Object>) completed::add);
            update.invoke(type.getDeclaredConstructor().newInstance());
            assertEquals(1, type.getField("calls").getInt(null));
            assertEquals(1, completed.size());
        } finally {
            clearSlots();
        }
    }

    @Test
    void commitCapturesEntryAndPublishesOnNormalReturn() throws Exception {
        final Path artifact = artifact();
        final Loader loader = new Loader(artifact);
        final byte[] reference = owner(EDIT, ModelInstanceCoalesceTarget.COMMIT_METHOD, false);
        final ModelInstanceCoalesceTransformer transformer = new ModelInstanceCoalesceTransformer(
                loader, artifact, owner(SOURCE, "updateModelInstances", false), reference, undoManager(false));
        final byte[] output = transformer.transform(null, loader, EDIT, null, loader.domain, reference);
        assertNotNull(output, transformer.failure());
        final Class<?> type = loader.define(EDIT, output);
        final List<Object> entered = new ArrayList<>();
        final List<Object> completed = new ArrayList<>();
        try {
            System.getProperties()
                    .put(ModelInstanceCoalesceBridge.COMMIT_CALLBACK_PROPERTY, (Consumer<Object>) entered::add);
            System.getProperties()
                    .put(ModelInstanceCoalesceBridge.AFTER_COMMIT_PROPERTY, (Consumer<Object>) completed::add);
            final Object instance = type.getDeclaredConstructor().newInstance();
            type.getMethod(ModelInstanceCoalesceTarget.COMMIT_METHOD).invoke(instance);
            assertEquals(List.of(instance), entered);
            assertEquals(List.of(instance), completed);
        } finally {
            clearSlots();
        }
    }

    @Test
    void undoAndRedoBothInvalidateAtEntry() throws Exception {
        final Path artifact = artifact();
        final Loader loader = new Loader(artifact);
        final byte[] reference = undoManager(false);
        final ModelInstanceCoalesceTransformer transformer = new ModelInstanceCoalesceTransformer(
                loader,
                artifact,
                owner(SOURCE, "updateModelInstances", false),
                owner(EDIT, "updateLastModifiedTime", false),
                reference);
        final byte[] output = transformer.transform(null, loader, UNDO, null, loader.domain, reference);
        assertNotNull(output, transformer.failure());
        final Class<?> type = loader.define(UNDO, output);
        final List<Object> invalidations = new ArrayList<>();
        try {
            System.getProperties().put(ModelInstanceCoalesceBridge.INVALIDATE_CALLBACK_PROPERTY, (Consumer<Object>)
                    invalidations::add);
            final Object instance = type.getDeclaredConstructor().newInstance();
            type.getMethod("undo").invoke(instance);
            type.getMethod("redo").invoke(instance);
            assertEquals(List.of(instance, instance), invalidations);
            assertEquals(2, type.getField("calls").getInt(null));
        } finally {
            clearSlots();
        }
    }

    @Test
    void shapeMismatchAndForeignDomainFailClosed() throws Exception {
        final Path artifact = artifact();
        final Loader loader = new Loader(artifact);
        final byte[] reference = owner(SOURCE, ModelInstanceCoalesceTarget.UPDATE_METHOD, false);
        final ModelInstanceCoalesceTransformer transformer = new ModelInstanceCoalesceTransformer(
                loader, artifact, reference, owner(EDIT, "updateLastModifiedTime", false), undoManager(false));

        final byte[] changed = owner(SOURCE, ModelInstanceCoalesceTarget.UPDATE_METHOD, true);
        assertNull(transformer.transform(null, loader, SOURCE, null, loader.domain, changed));
        assertTrueFailure(transformer.failure());

        final Path other = artifact();
        final Loader foreign = new Loader(other);
        assertNull(transformer.transform(null, foreign, SOURCE, null, foreign.domain, reference));
        assertTrueFailure(transformer.failure());
        assertFalse(transformer.beforeSha256().containsKey(SOURCE));
    }

    private static void assertTrueFailure(final String failure) {
        assertNotNull(failure);
        assertFalse(failure.isBlank());
    }
}
