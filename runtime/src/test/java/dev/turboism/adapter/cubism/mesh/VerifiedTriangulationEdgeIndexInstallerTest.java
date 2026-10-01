package dev.turboism.adapter.cubism.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

class VerifiedTriangulationEdgeIndexInstallerTest {

    @Test
    void installsATransformerAndClosesIt() {
        final List<String> calls = new ArrayList<>();
        final List<String> codes = new ArrayList<>();
        final VerifiedTriangulationEdgeIndexInstaller.Installation installation =
                VerifiedTriangulationEdgeIndexInstaller.install(
                        instrumentation(calls, new Class<?>[0], false), codes::add);

        assertEquals(VerifiedTriangulationEdgeIndexInstaller.Status.INSTALLED, installation.status());
        assertEquals(List.of("add:false"), calls);
        assertEquals(List.of("TRIANGULATION_EDGE_INDEX_INSTALLED"), codes);
        assertEquals(TriangulationEdgeIndexTransformer.Outcome.NONE, installation.transformOutcome());

        installation.close();
        assertEquals(List.of("add:false", "remove"), calls);
        assertEquals(VerifiedTriangulationEdgeIndexInstaller.Status.CLOSED, installation.status());
    }

    @Test
    void declinesWhenTheTargetClassIsAlreadyLoaded() throws Exception {
        final Class<?> alreadyLoaded = loadedTarget();
        final List<String> calls = new ArrayList<>();
        final List<String> codes = new ArrayList<>();

        final VerifiedTriangulationEdgeIndexInstaller.Installation installation =
                VerifiedTriangulationEdgeIndexInstaller.install(
                        instrumentation(calls, new Class<?>[] {alreadyLoaded}, false), codes::add);

        assertEquals(
                VerifiedTriangulationEdgeIndexInstaller.Status.TARGET_ALREADY_LOADED,
                installation.status());
        assertEquals(List.of("TRIANGULATION_EDGE_INDEX_TARGET_ALREADY_LOADED"), codes);
        assertEquals(List.of(), calls, "no transformer may be registered for a loaded target");
    }

    @Test
    void declinesWhenLoadedClassEnumerationFails() {
        final List<String> calls = new ArrayList<>();
        final VerifiedTriangulationEdgeIndexInstaller.Installation installation =
                VerifiedTriangulationEdgeIndexInstaller.install(
                        instrumentation(calls, null, true), ignored -> {});
        assertEquals(
                VerifiedTriangulationEdgeIndexInstaller.Status.TARGET_ALREADY_LOADED,
                installation.status(),
                "uncertain enumeration must decline rather than weave blindly");
    }

    @Test
    void declinesWhenMembershipCallerIsAlreadyLoaded() throws Exception {
        final List<String> calls = new ArrayList<>();
        final Class<?> loaded = loadedTarget(TriangulationEdgeIndexTransformer.MEMBERSHIP_INTERNAL_NAME);
        final var installation = VerifiedTriangulationEdgeIndexInstaller.install(
                instrumentation(calls, new Class<?>[] {loaded}, false), ignored -> {});
        assertEquals(VerifiedTriangulationEdgeIndexInstaller.Status.TARGET_ALREADY_LOADED,
                installation.status());
        assertTrue(calls.isEmpty());
    }

    @Test
    void registrationFailureIsFailClosed() {
        final List<String> calls = new ArrayList<>();
        final List<String> codes = new ArrayList<>();
        final VerifiedTriangulationEdgeIndexInstaller.Installation installation =
                VerifiedTriangulationEdgeIndexInstaller.install(
                        instrumentation(calls, new Class<?>[0], true), codes::add);

        assertEquals(VerifiedTriangulationEdgeIndexInstaller.Status.INSTALL_FAILED,
                installation.status());
        assertEquals(List.of("TRIANGULATION_EDGE_INDEX_INSTALL_FAILED"), codes);
    }

    @Test
    void diagnosticSinkExceptionsDoNotEscape() {
        final List<String> calls = new ArrayList<>();
        final VerifiedTriangulationEdgeIndexInstaller.Installation installation =
                VerifiedTriangulationEdgeIndexInstaller.install(
                        instrumentation(calls, new Class<?>[0], false),
                        code -> {
                            throw new IllegalStateException("broken sink");
                        });
        assertEquals(VerifiedTriangulationEdgeIndexInstaller.Status.INSTALLED, installation.status());
        installation.close();
    }

    private Instrumentation instrumentation(
            final List<String> calls, final Class<?>[] loaded, final boolean failAdd) {
        return (Instrumentation) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {Instrumentation.class},
                (proxy, method, arguments) -> {
                    switch (method.getName()) {
                        case "addTransformer" -> {
                            if (failAdd) throw new IllegalStateException("registration refused");
                            calls.add("add:" + arguments[1]);
                            return null;
                        }
                        case "removeTransformer" -> {
                            calls.add("remove");
                            return true;
                        }
                        case "getAllLoadedClasses" -> {
                            if (loaded == null) throw new IllegalStateException("enumerate failed");
                            return loaded;
                        }
                        default -> {
                            return defaultValue(method.getReturnType());
                        }
                    }
                });
    }

    /** Defines a class literally named like the target so the loaded-check sees it. */
    private static Class<?> loadedTarget() throws Exception {
        return loadedTarget(TriangulationEdgeIndexTransformer.TARGET_INTERNAL_NAME);
    }

    private static Class<?> loadedTarget(final String internalName) throws Exception {
        final ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                internalName,
                null, "java/lang/Object", null);
        writer.visitEnd();
        final byte[] bytes = writer.toByteArray();
        return new ClassLoader() {
            Class<?> define() {
                return defineClass(
                        internalName.replace('/', '.'), bytes, 0, bytes.length);
            }
        }.define();
    }

    private static Object defaultValue(final Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return '\0';
        return null;
    }
}
