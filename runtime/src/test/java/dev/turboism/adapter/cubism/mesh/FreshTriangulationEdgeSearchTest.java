package dev.turboism.adapter.cubism.mesh;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
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
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        if (!identity) {
            m = w.visitMethod(ACC_PUBLIC, "equals", "(Ljava/lang/Object;)Z", null, null);
            m.visitCode();
            if (throwing) {
                m.visitTypeInsn(NEW, "java/lang/IllegalStateException");
                m.visitInsn(DUP);
                m.visitMethodInsn(INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "()V", false);
                m.visitInsn(ATHROW);
            } else {
                m.visitInsn(ICONST_1);
                m.visitInsn(IRETURN);
            }
            m.visitMaxs(0, 0);
            m.visitEnd();
        }
        w.visitEnd();
        byte[] bytes = w.toByteArray();
        Class<?> type = new ClassLoader(FreshTriangulationEdgeSearchTest.class.getClassLoader()) {
            Class<?> define() {
                return defineClass(null, bytes, 0, bytes.length);
            }
        }.define();
        return type.getConstructor().newInstance();
    }

    @Test
    void freshIdentityEdgeDoesNotMatchAnotherStoredIdentity() throws Exception {
        Object first = edge(true, true, false);
        Object fresh = first.getClass().getConstructor().newInstance();
        ArrayList<Object> list = new ArrayList<>();
        list.add(first);
        assertFalse(FreshTriangulationEdgeSearch.containsFresh(list, fresh));
        assertEquals(1, list.size());
        assertSame(first, list.get(0));
    }

    @Test
    void sameBinaryNameInAnotherLoaderDoesNotInheritAdmission() throws Exception {
        Object identity = edge(true, true, false);
        ArrayList<Object> list = new ArrayList<>();
        list.add(new Object());
        assertFalse(FreshTriangulationEdgeSearch.containsFresh(list, identity));
        Object value = edge(false, true, false);
        assertEquals(identity.getClass().getName(), value.getClass().getName());
        assertNotSame(identity.getClass(), value.getClass());
        assertTrue(FreshTriangulationEdgeSearch.containsFresh(list, value));
        assertFalse(FreshTriangulationEdgeSearch.containsFresh(list, identity));
    }

    @Test
    void originalEqualsExceptionPropagatesFromFallback() throws Exception {
        ArrayList<Object> list = new ArrayList<>();
        list.add(new Object());
        Object value = edge(false, true, true);
        assertThrows(IllegalStateException.class, () -> FreshTriangulationEdgeSearch.containsFresh(list, value));
    }

    @Test
    void nonFinalAndUnrelatedTypesUseOriginalSearch() throws Exception {
        Object nonFinal = edge(true, false, false);
        ArrayList<Object> list = new ArrayList<>();
        list.add(nonFinal);
        list.add("value");
        assertTrue(FreshTriangulationEdgeSearch.containsFresh(list, nonFinal));
        assertTrue(FreshTriangulationEdgeSearch.containsFresh(list, new String("value")));
    }

    private static final class CustomList extends ArrayList<Object> {
        private static final long serialVersionUID = 1L;
        int calls;

        @Override
        public boolean contains(Object value) {
            calls++;
            return true;
        }
    }

    @Test
    void listOverridesAndNullBehaviorRemainNative() throws Exception {
        CustomList custom = new CustomList();
        assertTrue(FreshTriangulationEdgeSearch.containsFresh(custom, edge(true, true, false)));
        assertEquals(1, custom.calls);
        ArrayList<Object> list = new ArrayList<>();
        list.add(null);
        assertTrue(FreshTriangulationEdgeSearch.containsFresh(list, null));
        assertThrows(NullPointerException.class, () -> FreshTriangulationEdgeSearch.containsFresh(null, new Object()));
    }

    private static final String DIAGNOSTIC_PROPERTY = "turboism.validation.triangulationEdgeGuard";
    private static final String DIAGNOSTIC_TOKEN = "FRESH_EDGE_GUARD_METADATA_V1";

    private static String captureDiagnostics(String token, Runnable action) {
        final String previous = System.getProperty(DIAGNOSTIC_PROPERTY);
        final PrintStream previousError = System.err;
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream output = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            if (token == null) System.clearProperty(DIAGNOSTIC_PROPERTY);
            else System.setProperty(DIAGNOSTIC_PROPERTY, token);
            System.setErr(output);
            action.run();
        } finally {
            System.setErr(previousError);
            if (previous == null) System.clearProperty(DIAGNOSTIC_PROPERTY);
            else System.setProperty(DIAGNOSTIC_PROPERTY, previous);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    void validationReceiptReportsOneColdAdmissionAndKeepsFreshResults() throws Exception {
        final Object first = edge(true, true, false);
        final Object second = first.getClass().getConstructor().newInstance();
        final ArrayList<Object> list = new ArrayList<>();
        list.add(new Object());
        final String output = captureDiagnostics(DIAGNOSTIC_TOKEN, () -> {
            assertFalse(FreshTriangulationEdgeSearch.containsFresh(list, first));
            assertFalse(FreshTriangulationEdgeSearch.containsFresh(list, second));
        });
        assertEquals(1, output.lines().count(), "sequential queries reuse the cached cold decision");
        assertTrue(output.contains(
                "TRIANGULATION_FRESH_EDGE_GUARD type=" + first.getClass().getName()));
        assertTrue(output.contains("admitted=true"));
        assertTrue(output.contains("reason=IDENTITY_EQUALITY"));
        assertTrue(output.contains("equalsOwner=java.lang.Object"));
        assertTrue(output.contains("coldComputation=true"));
        assertEquals(1, list.size());
    }

    @Test
    void validationReceiptDistinguishesSameNamedLoadedTypesAndNativeFallback() throws Exception {
        final Object identity = edge(true, true, false);
        final Object value = edge(false, true, false);
        final ArrayList<Object> list = new ArrayList<>();
        list.add(new Object());
        final String output = captureDiagnostics(DIAGNOSTIC_TOKEN, () -> {
            assertFalse(FreshTriangulationEdgeSearch.containsFresh(list, identity));
            assertTrue(FreshTriangulationEdgeSearch.containsFresh(list, value));
            assertFalse(FreshTriangulationEdgeSearch.containsFresh(list, identity));
        });
        assertEquals(2, output.lines().count());
        assertTrue(output.contains("admitted=true reason=IDENTITY_EQUALITY"));
        assertTrue(output.contains("admitted=false reason=EQUALS_OVERRIDE"));
        assertTrue(output.contains("equalsOwner=" + value.getClass().getName()));
    }

    @Test
    void noTokenOrWrongTokenRemainsSilent() throws Exception {
        for (final String token : new String[] {null, "true", "FRESH_EDGE_GUARD_METADATA"}) {
            final Object identity = edge(true, true, false);
            assertEquals(
                    "",
                    captureDiagnostics(
                            token,
                            () -> assertFalse(
                                    FreshTriangulationEdgeSearch.containsFresh(new ArrayList<>(), identity))));
        }
    }

    @Test
    void receiptRequiresTheActualExactListAndNonNullEdgeGuard() throws Exception {
        final Object identity = edge(true, true, false);
        final CustomList custom = new CustomList();
        final ArrayList<Object> exact = new ArrayList<>();
        exact.add(null);
        assertEquals("", captureDiagnostics(DIAGNOSTIC_TOKEN, () -> {
            assertTrue(FreshTriangulationEdgeSearch.containsFresh(custom, identity));
            assertTrue(FreshTriangulationEdgeSearch.containsFresh(exact, null));
        }));
        assertEquals(
                1,
                captureDiagnostics(
                                DIAGNOSTIC_TOKEN,
                                () -> assertFalse(FreshTriangulationEdgeSearch.containsFresh(exact, identity)))
                        .lines()
                        .count());
    }

    @Test
    void diagnosticFailureCannotChangeAdmissionOrNativeException() throws Exception {
        final Object identity = edge(true, true, false);
        final Object throwing = edge(false, true, true);
        final ArrayList<Object> list = new ArrayList<>();
        list.add(new Object());
        final String previous = System.getProperty(DIAGNOSTIC_PROPERTY);
        final PrintStream previousError = System.err;
        try (PrintStream failing = new PrintStream(new ByteArrayOutputStream()) {
            @Override
            public void println(String line) {
                throw new IllegalStateException("diagnostic sink failed");
            }
        }) {
            System.setProperty(DIAGNOSTIC_PROPERTY, DIAGNOSTIC_TOKEN);
            System.setErr(failing);
            assertFalse(FreshTriangulationEdgeSearch.containsFresh(list, identity));
            assertNull(
                    assertThrows(
                                    IllegalStateException.class,
                                    () -> FreshTriangulationEdgeSearch.containsFresh(list, throwing))
                            .getMessage(),
                    "the exception must come from native equals, not the failing diagnostic sink");
        } finally {
            System.setErr(previousError);
            if (previous == null) System.clearProperty(DIAGNOSTIC_PROPERTY);
            else System.setProperty(DIAGNOSTIC_PROPERTY, previous);
        }
    }

    @Test
    void fatalDiagnosticFailurePropagatesWithoutPoisoningTheTypeDecision() throws Exception {
        final Object identity = edge(true, true, false);
        final InternalError fatal = new InternalError("fatal diagnostic sink");
        final String previous = System.getProperty(DIAGNOSTIC_PROPERTY);
        final PrintStream previousError = System.err;
        try (PrintStream failing = new PrintStream(new ByteArrayOutputStream()) {
            @Override
            public void println(String line) {
                throw fatal;
            }
        }) {
            System.setProperty(DIAGNOSTIC_PROPERTY, DIAGNOSTIC_TOKEN);
            System.setErr(failing);
            assertSame(
                    fatal,
                    assertThrows(
                            InternalError.class,
                            () -> FreshTriangulationEdgeSearch.containsFresh(new ArrayList<>(), identity)));
        } finally {
            System.setErr(previousError);
            if (previous == null) System.clearProperty(DIAGNOSTIC_PROPERTY);
            else System.setProperty(DIAGNOSTIC_PROPERTY, previous);
        }
        assertEquals(
                "",
                captureDiagnostics(
                        null,
                        () -> assertFalse(FreshTriangulationEdgeSearch.containsFresh(new ArrayList<>(), identity))));
    }
}
