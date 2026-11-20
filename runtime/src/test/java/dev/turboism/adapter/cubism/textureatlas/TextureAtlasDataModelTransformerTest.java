package dev.turboism.adapter.cubism.textureatlas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.EventQueue;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class TextureAtlasDataModelTransformerTest {

    @Test
    void capturesOnlyAfterSuccessfulExactInitializationAndClearsOnClose() throws Exception {
        final TextureAtlasDataModelCapture capture = new TextureAtlasDataModelCapture();
        final TextureAtlasDataModelTransformer transformer = new TextureAtlasDataModelTransformer(
                "fixture/ModelImageList",
                "initGui",
                "()V",
                null,
                "getTaeDataModel",
                "()Ljava/lang/Object;",
                capture.key());
        final byte[] transformed =
                transformer.transform(null, null, "fixture/ModelImageList", null, null, fixtureClass());
        final FixtureLoader loader = new FixtureLoader(null);
        final Class<?> type = loader.define("fixture.ModelImageList", transformed);
        final Object instance = type.getConstructor().newInstance();
        final Object dataModel = type.getField("dataModel").get(instance);
        try {
            type.getMethod("initGui").invoke(instance);
            assertSame(dataModel, capture.current().orElseThrow());

            final Object replacement = new Object();
            type.getField("dataModel").set(instance, replacement);
            type.getField("fail").setBoolean(instance, true);
            org.junit.jupiter.api.Assertions.assertThrows(
                    InvocationTargetException.class,
                    () -> type.getMethod("initGui").invoke(instance));
            assertSame(dataModel, capture.current().orElseThrow());

            capture.close();
            assertTrue(capture.current().isEmpty());
        } finally {
            capture.close();
        }
    }

    @Test
    void atlasLoadingClearsItsScopeWhenNativeInitializationFails() throws Exception {
        final var initializer = MethodHandles.lookup()
                .findStatic(
                        TextureAtlasDataModelTransformerTest.class,
                        "failWhileLoading",
                        MethodType.methodType(void.class, Object.class));
        final var failure = new IllegalStateException("Native initialization failed");
        EventQueue.invokeAndWait(() -> {
            assertSame(
                    failure,
                    assertThrows(IllegalStateException.class, () -> TextureAtlasLoading.run(failure, initializer)));
            assertFalse(TextureAtlasLoading.isInitializing());
        });
    }

    private static void failWhileLoading(final Object failure) {
        assertTrue(TextureAtlasLoading.isInitializing());
        TextureAtlasLoading.layerReady();
        throw (IllegalStateException) failure;
    }

    @Test
    void nativeSolverKeepsItsCallerThreadOutsideAtlasLoading() throws Throwable {
        final var solver = new AtomicReference<Thread>();
        final var nativeSolve = MethodHandles.lookup()
                .findStatic(
                        TextureAtlasDataModelTransformerTest.class,
                        "recordSolveThread",
                        MethodType.methodType(void.class, AtomicReference.class, Object.class));
        assertFalse(TextureAtlasParallelMeshes.capture(solver, null, nativeSolve));
        assertSame(solver, TextureAtlasParallelMeshes.solve(solver, null, null, nativeSolve));
        assertSame(Thread.currentThread(), solver.get());
    }

    private static void recordSolveThread(final AtomicReference<Thread> solver, final Object progress) {
        solver.set(Thread.currentThread());
    }

    @Test
    void loadingTransformerDetectsDrawableGroupsStructurallyAndFailsClosedOnAmbiguity() {
        final String model = "com/live2d/cubism/doc/modeling/ui/atlasEditor/impl/TAE_DataModel";
        final byte[] valid = taeDataModelBytes(1, false);
        final byte[] patched = new TextureAtlasLoadingTransformer(true).transform(null, model, null, null, valid);
        assertNotNull(patched);
        assertTrue(
                methodCalls(patched).stream().anyMatch(call -> call.contains("TextureAtlasParallelMeshes.preload(")));

        assertNotNull(new TextureAtlasLoadingTransformer(false)
                .transform(null, model, null, null, taeDataModelBytes(0, false)));
        assertNull(new TextureAtlasLoadingTransformer(true)
                .transform(null, model, null, null, taeDataModelBytes(0, false)));
        assertNull(new TextureAtlasLoadingTransformer(true)
                .transform(null, model, null, null, taeDataModelBytes(2, false)));
        assertNull(new TextureAtlasLoadingTransformer(true)
                .transform(null, model, null, null, taeDataModelBytes(1, true)));
    }

    @Test
    void meshSolverTransformerDetectsProgressTypeStructurallyAndFailsClosedOnAmbiguity() {
        final String progress = "com/live2d/util/j/a";
        final byte[] valid = meshOpsBytes(progress, false);
        assertEquals(progress, TextureAtlasMeshSolverTransformer.progressType(valid));
        final byte[] patched = TextureAtlasMeshSolverTransformer.transform(valid);
        assertNotNull(patched);
        assertTrue(
                methodCalls(patched).stream().anyMatch(call -> call.contains("TextureAtlasParallelMeshes.capture(")));

        assertNull(TextureAtlasMeshSolverTransformer.progressType(meshOpsBytes(progress, true)));
        assertNull(TextureAtlasMeshSolverTransformer.transform(meshOpsBytes(progress, true)));
        assertNull(TextureAtlasMeshSolverTransformer.progressType(meshOpsBytes("com/example/Progress", false)));
    }

    private static List<String> methodCalls(final byte[] bytes) {
        final List<String> calls = new ArrayList<>();
        new ClassReader(bytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    final int access,
                                    final String name,
                                    final String descriptor,
                                    final String signature,
                                    final String[] exceptions) {
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitMethodInsn(
                                            final int opcode,
                                            final String owner,
                                            final String method,
                                            final String desc,
                                            final boolean itf) {
                                        calls.add(owner + "." + method + desc);
                                    }
                                };
                            }
                        },
                        0);
        return calls;
    }

    private static byte[] taeDataModelBytes(final int groupCalls, final boolean separatePrivateMethod) {
        final String model = "com/live2d/cubism/doc/modeling/ui/atlasEditor/impl/TAE_DataModel";
        final String ctorDesc =
                "(Lcom/live2d/cubism/doc/modeling/CModelingDocument;Lcom/live2d/cubism/doc/model/CModelSource;Lcom/live2d/cubism/doc/modeling/ui/atlasEditor/f;Z)V";
        final String groupsOwner = "com/live2d/cubism/doc/modeling/ui/atlasEditor/impl/W";
        final String groupsDesc = "(Lcom/live2d/cubism/doc/model/CModelSource;)Ljava/util/HashMap;";
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, model, null, "java/lang/Object", null);

        final MethodVisitor ctor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", ctorDesc, null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, model, "z", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(1, 5);
        ctor.visitEnd();

        final MethodVisitor init = writer.visitMethod(Opcodes.ACC_PRIVATE, "z", "()V", null, null);
        init.visitCode();
        if (!separatePrivateMethod) {
            for (int i = 0; i < groupCalls; i++) {
                init.visitInsn(Opcodes.ACONST_NULL);
                init.visitInsn(Opcodes.ACONST_NULL);
                init.visitMethodInsn(Opcodes.INVOKEVIRTUAL, groupsOwner, "a", groupsDesc, false);
                init.visitInsn(Opcodes.POP);
            }
        }
        init.visitInsn(Opcodes.ACONST_NULL);
        init.visitMethodInsn(
                Opcodes.INVOKEINTERFACE,
                "com/live2d/cubism/doc/modeling/ui/atlasEditor/impl/aa",
                "setupEditLayer",
                "()V",
                true);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(2, 1);
        init.visitEnd();

        if (separatePrivateMethod) {
            final MethodVisitor other = writer.visitMethod(Opcodes.ACC_PRIVATE, "v", "()V", null, null);
            other.visitCode();
            for (int i = 0; i < groupCalls; i++) {
                other.visitInsn(Opcodes.ACONST_NULL);
                other.visitInsn(Opcodes.ACONST_NULL);
                other.visitMethodInsn(Opcodes.INVOKEVIRTUAL, groupsOwner, "a", groupsDesc, false);
                other.visitInsn(Opcodes.POP);
            }
            other.visitInsn(Opcodes.RETURN);
            other.visitMaxs(2, 1);
            other.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] meshOpsBytes(final String progress, final boolean duplicateOverload) {
        final String owner = TextureAtlasMeshSolverTransformer.MESH_OPS;
        final String mesh = TextureAtlasMeshSolverTransformer.MESH;
        final String solver = TextureAtlasMeshSolverTransformer.TRIANGULATION + "a";
        final String applyDesc = "(L" + mesh + ";Ljava/util/List;ZL" + progress + ";)V";
        final String solveDesc = "(L" + progress + ";)V";
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, owner, null, "java/lang/Object", null);

        final MethodVisitor apply =
                writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "a", applyDesc, null, null);
        apply.visitCode();
        apply.visitInsn(Opcodes.ACONST_NULL);
        apply.visitVarInsn(Opcodes.ASTORE, 5);
        apply.visitVarInsn(Opcodes.ALOAD, 5);
        apply.visitVarInsn(Opcodes.ALOAD, 4);
        apply.visitMethodInsn(Opcodes.INVOKEVIRTUAL, solver, "a", solveDesc, false);
        apply.visitInsn(Opcodes.RETURN);
        apply.visitMaxs(2, 6);
        apply.visitEnd();

        if (duplicateOverload) {
            final MethodVisitor dup = writer.visitMethod(
                    Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                    "a",
                    "(L" + mesh + ";Ljava/util/List;ZLcom/live2d/util/i/a;)V",
                    null,
                    null);
            dup.visitCode();
            dup.visitInsn(Opcodes.RETURN);
            dup.visitMaxs(0, 5);
            dup.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] fixtureClass() {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "fixture/ModelImageList", null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC, "dataModel", "Ljava/lang/Object;", null, null)
                .visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC, "fail", "Z", null, null).visitEnd();
        final MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitTypeInsn(Opcodes.NEW, "java/lang/Object");
        constructor.visitInsn(Opcodes.DUP);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitFieldInsn(Opcodes.PUTFIELD, "fixture/ModelImageList", "dataModel", "Ljava/lang/Object;");
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        final MethodVisitor getter =
                writer.visitMethod(Opcodes.ACC_PUBLIC, "getTaeDataModel", "()Ljava/lang/Object;", null, null);
        getter.visitCode();
        getter.visitVarInsn(Opcodes.ALOAD, 0);
        getter.visitFieldInsn(Opcodes.GETFIELD, "fixture/ModelImageList", "dataModel", "Ljava/lang/Object;");
        getter.visitInsn(Opcodes.ARETURN);
        getter.visitMaxs(0, 0);
        getter.visitEnd();

        final MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "initGui", "()V", null, null);
        init.visitCode();
        final org.objectweb.asm.Label success = new org.objectweb.asm.Label();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitFieldInsn(Opcodes.GETFIELD, "fixture/ModelImageList", "fail", "Z");
        init.visitJumpInsn(Opcodes.IFEQ, success);
        init.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException");
        init.visitInsn(Opcodes.DUP);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "()V", false);
        init.visitInsn(Opcodes.ATHROW);
        init.visitLabel(success);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static final class FixtureLoader extends ClassLoader {
        private FixtureLoader(final ClassLoader parent) {
            super(parent);
        }

        private Class<?> define(final String name, final byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
