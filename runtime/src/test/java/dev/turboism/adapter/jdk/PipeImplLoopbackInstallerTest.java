package dev.turboism.adapter.jdk;

import org.junit.jupiter.api.Test;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipeImplLoopbackInstallerTest {

    @Test
    void installsUnconditionallyAndCloseIsIdempotent() {
        final List<String> calls = new ArrayList<>();
        final List<String> diagnostics = new ArrayList<>();

        final PipeImplLoopbackInstaller.Installation installation =
            PipeImplLoopbackInstaller.install(
                instrumentation(calls, false, false),
                diagnostics::add
            );

        assertEquals(PipeImplLoopbackInstaller.Status.INSTALLED, installation.status());
        assertEquals(List.of("add:false"), calls);
        assertEquals(List.of("PIPE_IMPL_SHIM_INSTALLED"), diagnostics);
        assertEquals("PENDING", installation.transformOutcome());

        installation.close();
        assertTrue(calls.contains("remove"));
        final int removes = count(calls, "remove");
        installation.close();
        assertEquals(removes, count(calls, "remove"), "close must be idempotent");
    }

    @Test
    void reportsAnAlreadyLoadedTargetWithoutInstallingAnything() {
        final List<String> calls = new ArrayList<>();
        final List<String> diagnostics = new ArrayList<>();

        final PipeImplLoopbackInstaller.Installation installation =
            PipeImplLoopbackInstaller.install(
                instrumentation(calls, true, false),
                diagnostics::add
            );

        assertEquals(
            PipeImplLoopbackInstaller.Status.TARGET_ALREADY_LOADED,
            installation.status()
        );
        assertEquals(List.of(), calls);
        assertEquals(List.of("PIPE_IMPL_SHIM_TARGET_ALREADY_LOADED"), diagnostics);
        assertEquals("NOT_INSTALLED", installation.transformOutcome());
    }

    @Test
    void reportsAnInstallFailureAndRemovesTheTransformer() {
        final List<String> calls = new ArrayList<>();
        final List<String> diagnostics = new ArrayList<>();

        final PipeImplLoopbackInstaller.Installation installation =
            PipeImplLoopbackInstaller.install(
                instrumentation(calls, false, true),
                diagnostics::add
            );

        assertEquals(PipeImplLoopbackInstaller.Status.INSTALL_FAILED, installation.status());
        assertEquals(List.of("remove"), calls);
        assertEquals(List.of("PIPE_IMPL_SHIM_INSTALL_FAILED"), diagnostics);
        assertEquals("NOT_INSTALLED", installation.transformOutcome());
    }

    @Test
    void transformsTheJdkClassThroughTheInstalledTransformerAndSelfRemoves() throws Exception {
        final List<String> calls = new ArrayList<>();
        final List<String> diagnostics = new ArrayList<>();
        final List<ClassFileTransformer> installed = new ArrayList<>();

        final PipeImplLoopbackInstaller.Installation installation =
            PipeImplLoopbackInstaller.install(
                capturingInstrumentation(calls, installed, false, false),
                diagnostics::add
            );

        assertEquals(PipeImplLoopbackInstaller.Status.INSTALLED, installation.status());
        assertEquals(1, installed.size());

        final byte[] fixture = PipeImplSyntheticFixture.valid();
        final byte[] transformed = installed.get(0).transform(
            null,
            null,
            "sun/nio/ch/PipeImpl",
            null,
            null,
            fixture
        );

        assertNotNull(transformed);
        assertEquals("TRANSFORMED", installation.transformOutcome());
        assertTrue(count(calls, "remove") >= 1, "the transformer must self-remove after the target attempt");

        final int afterTransform = count(calls, "remove");
        installation.close();
        final int afterClose = count(calls, "remove");
        installation.close();
        assertEquals(
            afterClose,
            count(calls, "remove"),
            "close after self-removal stays idempotent"
        );
        assertEquals(afterTransform + 1, afterClose);
    }

    private static int count(final List<String> calls, final String value) {
        return (int) calls.stream().filter(value::equals).count();
    }

    private Instrumentation instrumentation(
        final List<String> calls,
        final boolean alreadyLoaded,
        final boolean failAdd
    ) {
        return capturingInstrumentation(calls, new ArrayList<>(), alreadyLoaded, failAdd);
    }

    private Instrumentation capturingInstrumentation(
        final List<String> calls,
        final List<ClassFileTransformer> installed,
        final boolean alreadyLoaded,
        final boolean failAdd
    ) {
        return (Instrumentation) Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[]{Instrumentation.class},
            (proxy, method, arguments) -> {
                if (method.getName().equals("addTransformer")) {
                    if (failAdd) {
                        throw new RuntimeException("addTransformer refused");
                    }
                    calls.add("add:" + arguments[1]);
                    installed.add((ClassFileTransformer) arguments[0]);
                    return null;
                }
                if (method.getName().equals("removeTransformer")) {
                    calls.add("remove");
                    return true;
                }
                if (method.getName().equals("getAllLoadedClasses")) {
                    if (alreadyLoaded) {
                        try {
                            return new Class<?>[]{Class.forName("sun.nio.ch.PipeImpl")};
                        } catch (ClassNotFoundException unavailable) {
                            return new Class<?>[0];
                        }
                    }
                    return new Class<?>[0];
                }
                if (method.getReturnType() == boolean.class) {
                    return false;
                }
                if (method.getReturnType() == int.class) {
                    return 0;
                }
                if (method.getReturnType() == long.class) {
                    return 0L;
                }
                return null;
            }
        );
    }

}
