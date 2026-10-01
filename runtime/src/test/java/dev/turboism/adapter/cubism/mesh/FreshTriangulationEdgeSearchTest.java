package dev.turboism.adapter.cubism.mesh;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

final class FreshTriangulationEdgeSearchTest implements Opcodes {
    private static final String EDGE = "com/live2d/graphics3d/editableMesh/triangulation/j";

    /** Own generated types with the reviewed binary name, defined by distinct loaders. */
    private static Object edge(boolean identity, boolean finalType, boolean throwing) throws Exception {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(V17, ACC_PUBLIC | (finalType ? ACC_FINAL : 0), EDGE, null, "java/lang/Object", null);
        MethodVisitor m = w.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        m.visitCode(); m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        m.visitInsn(RETURN); m.visitMaxs(0, 0); m.visitEnd();
        if (!identity) {
            m = w.visitMethod(ACC_PUBLIC, "equals", "(Ljava/lang/Object;)Z", null, null);
            m.visitCode();
            if (throwing) {
                m.visitTypeInsn(NEW, "java/lang/IllegalStateException"); m.visitInsn(DUP);
                m.visitMethodInsn(INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "()V", false);
                m.visitInsn(ATHROW);
            } else { m.visitInsn(ICONST_1); m.visitInsn(IRETURN); }
            m.visitMaxs(0, 0); m.visitEnd();
        }
        w.visitEnd(); byte[] bytes = w.toByteArray();
        Class<?> type = new ClassLoader(FreshTriangulationEdgeSearchTest.class.getClassLoader()) {
            Class<?> define() { return defineClass(null, bytes, 0, bytes.length); }
        }.define();
        return type.getConstructor().newInstance();
    }

    @Test void freshIdentityEdgeDoesNotMatchAnotherStoredIdentity() throws Exception {
        Object first = edge(true, true, false);
        Object fresh = first.getClass().getConstructor().newInstance();
        ArrayList<Object> list = new ArrayList<>(); list.add(first);
        assertFalse(FreshTriangulationEdgeSearch.containsFresh(list, fresh));
        assertEquals(1, list.size());
        assertSame(first, list.get(0));
    }

    @Test void sameBinaryNameInAnotherLoaderDoesNotInheritAdmission() throws Exception {
        Object identity = edge(true, true, false);
        ArrayList<Object> list = new ArrayList<>(); list.add(new Object());
        assertFalse(FreshTriangulationEdgeSearch.containsFresh(list, identity));
        Object value = edge(false, true, false);
        assertEquals(identity.getClass().getName(), value.getClass().getName());
        assertNotSame(identity.getClass(), value.getClass());
        assertTrue(FreshTriangulationEdgeSearch.containsFresh(list, value));
        assertFalse(FreshTriangulationEdgeSearch.containsFresh(list, identity));
    }

    @Test void originalEqualsExceptionPropagatesFromFallback() throws Exception {
        ArrayList<Object> list = new ArrayList<>(); list.add(new Object());
        Object value = edge(false, true, true);
        assertThrows(IllegalStateException.class,
                () -> FreshTriangulationEdgeSearch.containsFresh(list, value));
    }

    @Test void nonFinalAndUnrelatedTypesUseOriginalSearch() throws Exception {
        Object nonFinal = edge(true, false, false);
        ArrayList<Object> list = new ArrayList<>(); list.add(nonFinal); list.add("value");
        assertTrue(FreshTriangulationEdgeSearch.containsFresh(list, nonFinal));
        assertTrue(FreshTriangulationEdgeSearch.containsFresh(list, new String("value")));
    }

    private static final class CustomList extends ArrayList<Object> {
        private static final long serialVersionUID = 1L;
        int calls;
        @Override public boolean contains(Object value) { calls++; return true; }
    }

    @Test void listOverridesAndNullBehaviorRemainNative() throws Exception {
        CustomList custom = new CustomList();
        assertTrue(FreshTriangulationEdgeSearch.containsFresh(custom, edge(true, true, false)));
        assertEquals(1, custom.calls);
        ArrayList<Object> list = new ArrayList<>(); list.add(null);
        assertTrue(FreshTriangulationEdgeSearch.containsFresh(list, null));
        assertThrows(NullPointerException.class,
                () -> FreshTriangulationEdgeSearch.containsFresh(null, new Object()));
    }
}
