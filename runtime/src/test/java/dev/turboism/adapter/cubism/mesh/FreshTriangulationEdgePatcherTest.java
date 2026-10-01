package dev.turboism.adapter.cubism.mesh;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;

/** Defines generated own types only; no official class bytes are executed. */
final class FreshTriangulationEdgePatcherTest implements Opcodes {
    private static final String P = "com/live2d/graphics3d/editableMesh/triangulation/";
    private static byte[] fixture() {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        w.visit(V17, ACC_PUBLIC | ACC_FINAL, P + "h", null, "java/lang/Object", null);
        w.visitField(ACC_PUBLIC | ACC_STATIC, "mask", "I", null, null).visitEnd();
        w.visitField(ACC_PUBLIC | ACC_STATIC, "sink", "Ljava/util/ArrayList;", null, null).visitEnd();
        MethodVisitor c = w.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        c.visitCode(); c.visitVarInsn(ALOAD, 0);
        c.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        c.visitInsn(RETURN); c.visitMaxs(0, 0); c.visitEnd();
        MethodVisitor m = w.visitMethod(ACC_PUBLIC | ACC_FINAL, "c", "()V", null, null);
        m.visitCode(); m.visitTypeInsn(NEW, "java/util/ArrayList"); m.visitInsn(DUP);
        m.visitMethodInsn(INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
        m.visitVarInsn(ASTORE, 7);
        for (int i = 0; i < 3; i++) {
            m.visitTypeInsn(NEW, P + "j"); m.visitInsn(DUP);
            m.visitInsn(ACONST_NULL); m.visitInsn(ACONST_NULL);
            m.visitMethodInsn(INVOKESPECIAL, P + "j", "<init>", "(L" + P + "TriPoint;L" + P + "TriPoint;)V", false);
            m.visitVarInsn(ASTORE, 12 + i);
            Label skip = new Label();
            m.visitFieldInsn(GETSTATIC, P + "h", "mask", "I");
            m.visitIntInsn(BIPUSH, 1 << i); m.visitInsn(IAND); m.visitJumpInsn(IFEQ, skip);
            m.visitVarInsn(ALOAD, 7); m.visitVarInsn(ALOAD, 12 + i);
            m.visitMethodInsn(INVOKEVIRTUAL, "java/util/ArrayList", "contains", "(Ljava/lang/Object;)Z", false);
            m.visitJumpInsn(IFNE, skip);
            m.visitVarInsn(ALOAD, 7); m.visitVarInsn(ALOAD, 12 + i);
            m.visitMethodInsn(INVOKEVIRTUAL, "java/util/ArrayList", "add", "(Ljava/lang/Object;)Z", false);
            m.visitInsn(POP); m.visitLabel(skip);
        }
        m.visitVarInsn(ALOAD, 7); m.visitFieldInsn(PUTSTATIC, P + "h", "sink", "Ljava/util/ArrayList;");
        m.visitInsn(RETURN); m.visitMaxs(0, 0); m.visitEnd(); w.visitEnd();
        return w.toByteArray();
    }

    private static byte[] edge(boolean valueEquality) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(V17, ACC_PUBLIC | ACC_FINAL, P + "j", null, "java/lang/Object", null);
        w.visitField(ACC_PUBLIC | ACC_STATIC, "calls", "I", null, null).visitEnd();
        MethodVisitor m = w.visitMethod(ACC_PUBLIC, "<init>", "(L" + P + "TriPoint;L" + P + "TriPoint;)V", null, null);
        m.visitCode(); m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        m.visitInsn(RETURN); m.visitMaxs(0, 0); m.visitEnd();
        if (valueEquality) {
            m = w.visitMethod(ACC_PUBLIC, "equals", "(Ljava/lang/Object;)Z", null, null);
            m.visitCode(); m.visitFieldInsn(GETSTATIC, P + "j", "calls", "I");
            m.visitInsn(ICONST_1); m.visitInsn(IADD); m.visitFieldInsn(PUTSTATIC, P + "j", "calls", "I");
            m.visitInsn(ICONST_1); m.visitInsn(IRETURN); m.visitMaxs(0, 0); m.visitEnd();
        }
        w.visitEnd(); return w.toByteArray();
    }
    private static byte[] point() {
        ClassWriter w = new ClassWriter(0);
        w.visit(V17, ACC_PUBLIC | ACC_FINAL, P + "TriPoint", null, "java/lang/Object", null);
        w.visitEnd(); return w.toByteArray();
    }
    private static String execute(byte[] host, boolean valueEquality, int mask) throws Exception {
        ClassLoader loader = new ClassLoader(FreshTriangulationEdgePatcherTest.class.getClassLoader()) {
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = switch (name) {
                    case "com.live2d.graphics3d.editableMesh.triangulation.h" -> host;
                    case "com.live2d.graphics3d.editableMesh.triangulation.j" -> edge(valueEquality);
                    case "com.live2d.graphics3d.editableMesh.triangulation.TriPoint" -> point();
                    default -> throw new ClassNotFoundException(name);
                };
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
        Class<?> type = loader.loadClass((P + "h").replace('/', '.'));
        type.getField("mask").setInt(null, mask);
        try { type.getMethod("c").invoke(type.getConstructor().newInstance()); }
        catch (InvocationTargetException e) { throw new AssertionError(e.getCause()); }
        List<?> result = (List<?>) type.getField("sink").get(null);
        Class<?> edge = loader.loadClass((P + "j").replace('/', '.'));
        for (int i = 0; i < result.size(); i++) {
            assertSame(edge, result.get(i).getClass());
            for (int j = 0; j < i; j++) assertNotSame(result.get(i), result.get(j));
        }
        return result.size() + ":" + edge.getField("calls").getInt(null);
    }
    @Test void generatedBytecodePreservesActualLoadedEqualityAndItsSideEffects() throws Exception {
        byte[] original = fixture(); byte[] patched = FreshTriangulationEdgePatcher.patchShape(original);
        for (boolean value : new boolean[] {false, true}) for (int mask = 0; mask < 8; mask++) {
            assertEquals(execute(original, value, mask), execute(patched, value, mask));
        }
        assertEquals("3:0", execute(patched, false, 7));
        assertEquals("1:2", execute(patched, true, 7));
    }
    @Test void rewriteContainsExactlyThreeGuardCallsAndRejectsReapplication() {
        byte[] patched = FreshTriangulationEdgePatcher.patchShape(fixture());
        int[] calls = {0, 0};
        new ClassReader(patched).accept(new ClassVisitor(ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                return new MethodVisitor(ASM9) {
                    @Override public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                        if (name.equals("containsFresh")) {
                            assertEquals(INVOKESTATIC, opcode);
                            assertEquals("(Ljava/util/ArrayList;Ljava/lang/Object;)Z", desc); calls[0]++;
                        }
                        if (name.equals("contains")) calls[1]++;
                    }
                };
            }
        }, 0);
        assertArrayEquals(new int[] {3, 0}, calls);
        assertThrows(IllegalArgumentException.class, () -> FreshTriangulationEdgePatcher.patchShape(patched));
        assertThrows(IllegalArgumentException.class, () -> FreshTriangulationEdgePatcher.patch(fixture()));
    }
    @Test void unknownCallerAndRedefinitionDoNotEnableEitherStage() {
        var transformer = new TriangulationEdgeIndexTransformer();
        assertNull(transformer.transform(null, P + "h", null, null, new byte[] {1, 2, 3}));
        assertEquals(TriangulationEdgeIndexTransformer.Outcome.HASH_MISMATCH, transformer.freshEdgeOutcome());
        assertEquals(TriangulationEdgeIndexTransformer.Outcome.HASH_MISMATCH, transformer.membershipOutcome());
        var redefine = new TriangulationEdgeIndexTransformer();
        assertNull(redefine.transform(null, P + "h", Object.class, null, fixture()));
        assertEquals(TriangulationEdgeIndexTransformer.Outcome.NONE, redefine.freshEdgeOutcome());
    }

}
