package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.startup.StartupSuppressionInstaller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurboismAgentBootstrapTest {

    @TempDir
    Path tempDir;

    @Test
    void failedBindingClosesOnlyItsInstalledHandleEvenWhenCleanupThrows() throws Exception {
        final var field = TurboismAgent.class.getDeclaredField("HOOKS");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        final var slot = (AtomicReference<HookRegistry>) field.get(null);
        final HookRegistry registry = new HookRegistry();
        final HookRegistry previous = slot.getAndSet(registry);
        final HookContributor failed = new WarpAltMirrorHookContributor();
        final HookContributor sibling = new NativeEditBeginHookContributor();
        final List<String> closed = new ArrayList<>();
        registry.enroll(failed, () -> {
            closed.add("failed");
            throw new IllegalStateException("cleanup rejected");
        });
        registry.enroll(sibling, () -> closed.add("sibling"));
        try {
            final var bind = TurboismAgent.class.getDeclaredMethod("bindRuntimeHook",
                HookContributor.class, HookEnvironment.class);
            bind.setAccessible(true);
            // No premain installer exists, so the real contributor refuses binding.
            bind.invoke(null, failed, HookEnvironment.builder().build());
            bind.invoke(null, failed, HookEnvironment.builder().build());

            assertEquals(List.of("failed"), closed);
            assertFalse(registry.contains(failed.id()));
            assertTrue(registry.contains(sibling.id()));
        } finally {
            registry.closeAll(ignored -> { }, ignored -> { });
            slot.set(previous);
        }
    }

    @Test
    void earlyHooksAreUnavailableOrPendingBeforePluginInitialization() {
        final HookContributor absent = new MeshMirrorHookContributor();
        final HookContributor premain = new WarpAltMirrorHookContributor();
        final HookContributor host = new FpsHookContributor();
        final HookContributor runtime = new NativeEditBeginHookContributor();
        final List<String> actions = new ArrayList<>();

        TurboismAgent.prepareEarlyHooks(
            List.of(absent, premain, host, runtime), List.of(premain, host),
            hook -> actions.add("unavailable:" + hook.id()),
            hook -> actions.add("pending:" + hook.id()),
            hook -> actions.add("bind:" + hook.id()));

        assertEquals(List.of(
            "unavailable:" + absent.id(), "pending:" + premain.id(), "bind:" + host.id()), actions);
    }

    @Test
    void missingPremainWarpHookIsExplicitlyWithdrawn() {
        final HookContributor warp = new WarpAltMirrorHookContributor();
        final List<String> withdrawn = new ArrayList<>();
        TurboismAgent.prepareEarlyHooks(List.of(warp), List.of(),
            hook -> withdrawn.addAll(hook.runtimeHookIds()),
            hook -> { throw new AssertionError("missing hook cannot become pending"); },
            hook -> { throw new AssertionError("missing hook cannot bind"); });
        assertEquals(List.of("warp-alt-mirror"), withdrawn);
    }

    @Test
    void rejectedOptionsDoNotPoisonTheNextStartAttempt() {
        final AtomicInteger hookRegistrations = new AtomicInteger();

        TurboismAgent.requestStartForTesting(
            StartupSuppressionInstaller.AttachmentMode.PREMAIN,
            "unsafe=true",
            null,
            hook -> hookRegistrations.incrementAndGet()
        );
        TurboismAgent.requestStartForTesting(
            StartupSuppressionInstaller.AttachmentMode.PREMAIN,
            null,
            null,
            hook -> {
                hookRegistrations.incrementAndGet();
                throw new SecurityException("test rejection");
            }
        );

        assertEquals(1, hookRegistrations.get());
    }

    @Test
    void rejectedHookRegistrationCanBeRetriedWithOneHookAttemptPerStart() {
        final AtomicInteger hookRegistrations = new AtomicInteger();
        final TurboismAgent.ShutdownHookRegistrar rejected = hook -> {
            hookRegistrations.incrementAndGet();
            throw new SecurityException("test rejection");
        };

        TurboismAgent.requestStartForTesting(
            StartupSuppressionInstaller.AttachmentMode.PREMAIN,
            null,
            null,
            rejected
        );
        TurboismAgent.requestStartForTesting(
            StartupSuppressionInstaller.AttachmentMode.PREMAIN,
            null,
            null,
            rejected
        );

        assertEquals(2, hookRegistrations.get());
    }

    @Test
    void synchronousRuntimeExceptionIsContainedAndReported() {
        // A null instrumentation reaches the real JvmShims.install and fails
        // inside the synchronous start segment; nothing may reach the caller.
        final String diagnostics = captureErr(() -> TurboismAgent.requestStartForTesting(
            StartupSuppressionInstaller.AttachmentMode.PREMAIN,
            "home=" + tempDir,
            null,
            hook -> { }
        ));

        assertTrue(
            diagnostics.contains("Turboism agent start failed safely"),
            diagnostics
        );
        assertTrue(diagnostics.contains("NullPointerException"), diagnostics);
        assertNoBootstrapThread();
    }

    @Test
    void synchronousErrorIsContainedAndTheNextStartIsNotPoisoned() {
        final AtomicInteger loadedClassQueries = new AtomicInteger();
        final Instrumentation instrumentation = instrumentation((proxy, method, arguments) -> {
            if ("getAllLoadedClasses".equals(method.getName())) {
                loadedClassQueries.incrementAndGet();
                throw new AssertionError("injected premain failure");
            }
            return defaultValue(method.getReturnType());
        });

        final String first = captureErr(() -> TurboismAgent.requestStartForTesting(
            StartupSuppressionInstaller.AttachmentMode.PREMAIN,
            "home=" + tempDir,
            instrumentation,
            hook -> { }
        ));
        final String second = captureErr(() -> TurboismAgent.requestStartForTesting(
            StartupSuppressionInstaller.AttachmentMode.PREMAIN,
            "home=" + tempDir,
            instrumentation,
            hook -> { }
        ));

        assertTrue(first.contains("Turboism agent start failed safely"), first);
        assertTrue(first.contains("AssertionError"), first);
        assertTrue(second.contains("Turboism agent start failed safely"), second);
        assertEquals(2, loadedClassQueries.get());
        assertNoBootstrapThread();
    }

    @Test
    void failedStartRollsBackInstalledTransformersAndStartsNoThread() {
        final List<ClassFileTransformer> added = new ArrayList<>();
        final List<ClassFileTransformer> removed = new ArrayList<>();
        final Instrumentation instrumentation = instrumentation((proxy, method, arguments) ->
            switch (method.getName()) {
                case "isRetransformClassesSupported" -> true;
                case "addTransformer" -> {
                    added.add((ClassFileTransformer) arguments[0]);
                    yield null;
                }
                case "getAllLoadedClasses" -> new Class<?>[0];
                case "isModifiableClass" -> true;
                case "removeTransformer" -> {
                    removed.add((ClassFileTransformer) arguments[0]);
                    yield true;
                }
                default -> defaultValue(method.getReturnType());
            }
        );
        final AtomicInteger threadStarts = new AtomicInteger();

        final String diagnostics = captureErr(() -> TurboismAgent.requestStartForTesting(
            StartupSuppressionInstaller.AttachmentMode.PREMAIN,
            "home=" + tempDir,
            instrumentation,
            hook -> { },
            action -> {
                threadStarts.incrementAndGet();
                throw new IllegalStateException("injected bootstrap thread failure");
            }
        ));

        assertEquals(1, threadStarts.get());
        assertFalse(added.isEmpty(), "expected at least the pipe shim transformer to install");
        assertTrue(removed.containsAll(added), "installed transformers must be removed again");
        assertTrue(diagnostics.contains("Turboism agent start failed safely"), diagnostics);
        assertTrue(diagnostics.contains("IllegalStateException"), diagnostics);
        assertNoBootstrapThread();
    }

    private static String captureErr(final Runnable action) {
        final PrintStream original = System.err;
        final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setErr(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    private static void assertNoBootstrapThread() {
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            assertFalse(
                thread.isAlive() && "turboism-bootstrap".equals(thread.getName()),
                "bootstrap thread must not be running"
            );
        }
    }

    private static Instrumentation instrumentation(
        final java.lang.reflect.InvocationHandler handler
    ) {
        return (Instrumentation) java.lang.reflect.Proxy.newProxyInstance(
            TurboismAgentBootstrapTest.class.getClassLoader(),
            new Class<?>[] {Instrumentation.class},
            handler
        );
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
