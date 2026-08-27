package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.performance.PerformanceProbeMetric;
import dev.turboism.adapter.cubism.performance.PerformanceProbeMethodTransformer;
import dev.turboism.adapter.cubism.performance.PerformanceProbeTargets;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import javax.swing.UIDefaults;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * FPS counting digest admission, exact-version target routing, and the S7
 * install timing contract. Digest routing is the reviewed-constants seam the
 * constructor delegates to (the same {@code forArtifact} idiom as
 * {@code MeshMirrorHostProfile} and {@code EditorModelVerificationManifest});
 * a file-based accept test cannot fabricate a SHA-256 preimage, so the
 * constructor's reject path is exercised with a real fake artifact file.
 *
 * <p>Install-timing tests use the package-private wiring seam (exact targets
 * + bounded L&F-readiness timeout) and a recording {@link Instrumentation}
 * proxy, driving the real Swing {@code UIManager} so the S1 EDT-dispatch
 * pattern is exercised for real.
 */
final class PerformanceFpsHookInstallerTest {

    private static final long CUBISM_5203_SIZE = ReviewedHostArtifacts.CUBISM_5_2_03.size();
    private static final String CUBISM_5203_SHA256 =
        ReviewedHostArtifacts.CUBISM_5_2_03.sha256();
    private static final long CUBISM_5302_SIZE = ReviewedHostArtifacts.CUBISM_5_3_02.size();
    private static final String CUBISM_5302_SHA256 =
        ReviewedHostArtifacts.CUBISM_5_3_02.sha256();
    private static final long CUBISM_5303_SIZE = ReviewedHostArtifacts.CUBISM_5_3_03.size();
    private static final String CUBISM_5303_SHA256 =
        ReviewedHostArtifacts.CUBISM_5_3_03.sha256();

    @TempDir
    Path temporary;

    private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
        final Thread thread = new Thread(r, "fps-hook-test-worker");
        thread.setDaemon(true);
        return thread;
    });

    @BeforeEach
    void resetToMetalLookAndFeel() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            installLookAndFeel(new javax.swing.plaf.metal.MetalLookAndFeel());
        });
    }

    @AfterEach
    void resetUIManager() throws Exception {
        UIManager.getDefaults().remove("PanelUI");
        SwingUtilities.invokeAndWait(() -> {
            try {
                UIManager.setLookAndFeel(new javax.swing.plaf.metal.MetalLookAndFeel());
            } catch (UnsupportedLookAndFeelException exception) {
                throw new IllegalStateException(exception);
            }
        });
        workers.shutdownNow();
    }

    @Test
    void acceptsReviewed5203DigestAndSelectsExactRenderSceneTarget() {
        final List<PerformanceProbeMethodTransformer.Target> targets =
            PerformanceFpsHookInstaller.fpsTargetsFor(
                new HostArtifactDigest(CUBISM_5203_SIZE, CUBISM_5203_SHA256)
            );

        assertEquals(1, targets.size());
        final PerformanceProbeMethodTransformer.Target target = targets.get(0);
        assertEquals("com/live2d/cubism/view/context/CEViewContext", target.ownerInternalName());
        assertEquals("renderScene_exe", target.methodName());
        assertEquals(
            "(Lcom/live2d/graphics3d/a;Lcom/live2d/type/CRect;Lcom/live2d/type/CRect;)V",
            target.descriptor()
        );
        assertEquals(PerformanceProbeMetric.RENDER_SCENE, target.metric());
    }

    @Test
    void acceptsReviewed5302DigestRegression() {
        final List<PerformanceProbeMethodTransformer.Target> expected = PerformanceProbeTargets
            .cubism5302().stream()
            .filter(target -> target.metric() == PerformanceProbeMetric.RENDER_SCENE)
            .toList();
        final List<PerformanceProbeMethodTransformer.Target> targets =
            PerformanceFpsHookInstaller.fpsTargetsFor(
                new HostArtifactDigest(CUBISM_5302_SIZE, CUBISM_5302_SHA256)
            );

        assertEquals(expected, targets);
        assertEquals(1, targets.size());
        assertEquals("com/live2d/cubism/view/context/CEViewContext", targets.get(0).ownerInternalName());
        assertEquals("renderScene_exe", targets.get(0).methodName());
        assertEquals(
            "(Lcom/live2d/graphics3d/a;Lcom/live2d/type/CRect;Lcom/live2d/type/CRect;)V",
            targets.get(0).descriptor()
        );
    }

    @Test
    void acceptsReviewed5303DigestThroughItsExactProfile() {
        final List<PerformanceProbeMethodTransformer.Target> expected = PerformanceProbeTargets
            .cubism5303().stream()
            .filter(target -> target.metric() == PerformanceProbeMetric.RENDER_SCENE)
            .toList();
        final List<PerformanceProbeMethodTransformer.Target> targets =
            PerformanceFpsHookInstaller.fpsTargetsFor(
                new HostArtifactDigest(CUBISM_5303_SIZE, CUBISM_5303_SHA256)
            );

        assertEquals(expected, targets);
        assertEquals(1, targets.size());
        assertEquals(PerformanceProbeMetric.RENDER_SCENE, targets.get(0).metric());
    }

    @Test
    void rejectsUnknownDigest() {
        assertThrows(
            IllegalArgumentException.class,
            () -> PerformanceFpsHookInstaller.fpsTargetsFor(
                new HostArtifactDigest(1_234L, "a".repeat(64))
            )
        );
    }

    @Test
    void constructorRejectsFakeArtifactFile() throws Exception {
        final Path fakeArtifact = temporary.resolve("fake-artifact.jar");
        Files.write(fakeArtifact, new byte[] {1, 2, 3, 4, 5});

        final Instrumentation instrumentation = (Instrumentation) Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {Instrumentation.class},
            (proxy, method, arguments) -> defaultValue(method.getReturnType())
        );

        final IllegalArgumentException failure = assertThrows(
            IllegalArgumentException.class,
            () -> new PerformanceFpsHookInstaller(
                instrumentation, fakeArtifact, getClass().getClassLoader())
        );
        final HostArtifactDigest fakeDigest = HostArtifactDigest.from(fakeArtifact);
        assertEquals(
            "unsupported Cubism artifact for FPS counting"
                + " (expected Cubism 5.2.03, 5.3.02, or 5.3.03; got size="
                + fakeDigest.size() + " sha256=" + fakeDigest.sha256() + ")",
            failure.getMessage()
        );
    }

    @Test
    void installRegistersTransformerBeforeReturningAndDefersRetransform() throws Exception {
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        final Instrumentation instrumentation = recordingInstrumentation(calls, TargetRenderScene.class);

        final PerformanceFpsHookInstaller installer = new InstallerBuilder(instrumentation)
            .lafReadyTimeoutMillis(2_000L)
            .build();
        try {
            installer.install();
            // The transformer must be registered synchronously; install() must
            // not run the loaded-target retransform during early startup.
            assertTrue(calls.contains("add:true"), "addTransformer must be registered before install() returns");
            assertFalse(hasRetransform(calls), "install() must not retransform synchronously");
            Thread.sleep(300L);
            assertFalse(hasRetransform(calls),
                "retransform must stay deferred while the host L&F is not ready");
        } finally {
            installer.close();
        }
    }

    @Test
    void retransformRunsExactlyOnceAfterHostLafIsReady() throws Exception {
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        final Instrumentation instrumentation = recordingInstrumentation(calls, TargetRenderScene.class);

        final PerformanceFpsHookInstaller installer = new InstallerBuilder(instrumentation)
            .lafReadyTimeoutMillis(5_000L)
            .build();
        try {
            installer.install();
            Thread.sleep(300L);
            assertFalse(hasRetransform(calls), "no retransform while the host L&F is not ready");

            installCubismLikeLaf();
            awaitRetransform(calls, 5_000L);
            assertEquals(1L, retransformCount(calls), "deferred retransform must run exactly once");
            Thread.sleep(300L);
            assertEquals(1L, retransformCount(calls), "deferred retransform must not repeat");
        } finally {
            installer.close();
        }
    }

    @Test
    void retransformRunsExactlyOnceAfterReadinessTimeout() throws Exception {
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        final Instrumentation instrumentation = recordingInstrumentation(calls, TargetRenderScene.class);

        final PerformanceFpsHookInstaller installer = new InstallerBuilder(instrumentation)
            .lafReadyTimeoutMillis(200L)
            .build();
        try {
            installer.install();
            // Metal L&F never becomes FlatLaf-ready: the timeout fallback must
            // still run one retransform pass (late beats lost counts).
            awaitRetransform(calls, 5_000L);
            assertEquals(1L, retransformCount(calls), "timeout fallback must run exactly once");
        } finally {
            installer.close();
        }
    }

    @Test
    void readinessPollingDispatchesUIManagerReadsToTheEdt() throws Exception {
        installCubismLikeLaf();
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        final PerformanceFpsHookInstaller installer = new InstallerBuilder(
            recordingInstrumentation(calls, TargetRenderScene.class))
            .lafReadyTimeoutMillis(5_000L)
            .build();
        try (EdtHold hold = new EdtHold()) {
            final Future<Boolean> poll = workers.submit(() -> installer.waitForHostLafReady(5_000L));
            // While the EDT is held, an EDT-dispatched poll cannot observe the
            // installed look-and-feel; a direct UIManager read would return at once.
            Thread.sleep(400L);
            assertFalse(poll.isDone(), "readiness polling must run UIManager reads on the EDT");
            hold.release();
            assertTrue(poll.get(5, TimeUnit.SECONDS), "poll must find the installed look-and-feel");
        }
    }

    @Test
    void pollingSleepStaysOnTheDaemonWorkerSoTheEdtStaysResponsive() throws Exception {
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        final PerformanceFpsHookInstaller installer = new InstallerBuilder(
            recordingInstrumentation(calls, TargetRenderScene.class))
            .lafReadyTimeoutMillis(5_000L)
            .build();
        final AtomicReference<Thread> edtThread = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> edtThread.set(Thread.currentThread()));
        final AtomicReference<Thread> pollThread = new AtomicReference<>();
        final CountDownLatch pollStarted = new CountDownLatch(1);
        final Future<Boolean> poll = workers.submit(() -> {
            pollThread.set(Thread.currentThread());
            pollStarted.countDown();
            return installer.waitForHostLafReady(5_000L);
        });
        assertTrue(pollStarted.await(2, TimeUnit.SECONDS), "poll worker must start");
        assertNotSame(edtThread.get(), pollThread.get(), "poll must run on a non-EDT worker");
        assertTrue(awaitPollingSleep(pollThread.get(), 2_000L),
            "polling sleep must be observed on the poll worker's own stack");
        final CountDownLatch edtProbe = new CountDownLatch(1);
        SwingUtilities.invokeLater(edtProbe::countDown);
        assertTrue(edtProbe.await(2, TimeUnit.SECONDS),
            "EDT must remain responsive while the poll is active");
        assertFalse(poll.isDone(), "poll must remain active while the probe fires");
        poll.cancel(true);
    }

    @Test
    void closeBeforeDeferredPassSkipsTheRetransform() throws Exception {
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        final Instrumentation instrumentation = recordingInstrumentation(calls, TargetRenderScene.class);

        final PerformanceFpsHookInstaller installer = new InstallerBuilder(instrumentation)
            .lafReadyTimeoutMillis(5_000L)
            .build();
        try {
            installer.install();
            installer.close();
            // Late L&F readiness must not trigger a retransform on a closed hook.
            installCubismLikeLaf();
            Thread.sleep(500L);
            assertFalse(hasRetransform(calls), "no retransform may run after close()");
            assertFalse(installer.isInstalled());
        } finally {
            installer.close();
        }
    }

    @Test
    void closeRestorationStillRetransformsInstrumentedOwners() throws Exception {
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        final Instrumentation instrumentation = recordingInstrumentation(calls, TargetRenderScene.class);

        final PerformanceFpsHookInstaller installer = new InstallerBuilder(instrumentation)
            .lafReadyTimeoutMillis(5_000L)
            .build();
        installer.install();
        installCubismLikeLaf();
        awaitRetransform(calls, 5_000L);
        assertEquals(1L, retransformCount(calls), "deferred pass must run once before close()");

        installer.close();
        assertFalse(installer.isInstalled());
        assertTrue(calls.stream().anyMatch(call -> call.startsWith("remove:")),
            "close() must remove the transformer and observer");
        assertTrue(calls.stream().anyMatch(call -> call.startsWith("retransform:")
                && !call.startsWith("retransform:" + FPS_DAEMON_THREAD)),
            "close() must retransform instrumented owners back to original bytes on the caller thread");
    }

    private PerformanceFpsHookInstaller newInstaller(
        final Instrumentation instrumentation,
        final long lafReadyTimeoutMillis
    ) {
        return new InstallerBuilder(instrumentation).lafReadyTimeoutMillis(lafReadyTimeoutMillis).build();
    }

    /**
     * Recording instrumentation: {@code getAllLoadedClasses} returns the test
     * render target so the deferred pass (and close restoration) can be
     * observed through {@code retransformClasses} calls.
     */
    private static Instrumentation recordingInstrumentation(
        final List<String> calls,
        final Class<?> targetClass
    ) {
        return (Instrumentation) Proxy.newProxyInstance(
            PerformanceFpsHookInstallerTest.class.getClassLoader(),
            new Class<?>[] {Instrumentation.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "addTransformer" -> {
                    calls.add("add:" + arguments[1]);
                    yield null;
                }
                case "removeTransformer" -> {
                    calls.add("remove:" + arguments[0].getClass().getSimpleName());
                    yield true;
                }
                case "isRetransformClassesSupported" -> true;
                case "getAllLoadedClasses" -> new Class<?>[] {targetClass};
                case "isModifiableClass" -> true;
                case "retransformClasses" -> {
                    // Deferred pass runs on the turboism-fps-laf-ready daemon
                    // thread; close() restoration runs on the caller thread, so
                    // the two are distinguishable in the recorded trace.
                    calls.add("retransform:" + Thread.currentThread().getName());
                    yield null;
                }
                default -> defaultValue(method.getReturnType());
            }
        );
    }

    /** Binds the exact test target and a bounded L&F-readiness timeout. */
    private final class InstallerBuilder {
        private final Instrumentation instrumentation;
        private long lafReadyTimeoutMillis = 30_000L;

        private InstallerBuilder(final Instrumentation instrumentation) {
            this.instrumentation = instrumentation;
        }

        private InstallerBuilder lafReadyTimeoutMillis(final long timeoutMillis) {
            this.lafReadyTimeoutMillis = timeoutMillis;
            return this;
        }

        private PerformanceFpsHookInstaller build() {
            final String owner = TargetRenderScene.class.getName().replace('.', '/');
            return new PerformanceFpsHookInstaller(
                instrumentation,
                null,
                TargetRenderScene.class.getClassLoader(),
                List.of(new PerformanceProbeMethodTransformer.Target(
                    owner, "render", "(I)V", PerformanceProbeMetric.RENDER_SCENE
                )),
                lafReadyTimeoutMillis
            );
        }
    }

    private static final String FPS_DAEMON_THREAD = "turboism-fps-laf-ready";

    private static boolean hasRetransform(final List<String> calls) {
        return retransformCount(calls) > 0;
    }

    private static long retransformCount(final List<String> calls) {
        return calls.stream()
            .filter(call -> call.startsWith("retransform:" + FPS_DAEMON_THREAD))
            .count();
    }

    private static void awaitRetransform(final List<String> calls, final long timeoutMillis)
        throws InterruptedException {
        final long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (hasRetransform(calls)) return;
            Thread.sleep(20L);
        }
        fail("deferred retransform did not run within " + timeoutMillis + "ms; calls=" + calls);
    }

    /**
     * Bounded state sampling: waits until the worker is observed sleeping in
     * the readiness poll (TIMED_WAITING with {@code Thread.sleep} on the
     * {@code waitForHostLafReady} stack). No latency threshold is asserted.
     */
    private static boolean awaitPollingSleep(final Thread worker, final long timeoutMillis)
        throws InterruptedException {
        final long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (worker.getState() == Thread.State.TIMED_WAITING
                && isPollingSleep(worker.getStackTrace())) {
                return true;
            }
            Thread.sleep(5L);
        }
        return false;
    }

    private static boolean isPollingSleep(final StackTraceElement[] stack) {
        boolean sleeping = false;
        boolean polling = false;
        for (StackTraceElement element : stack) {
            sleeping |= "java.lang.Thread".equals(element.getClassName())
                && "sleep".equals(element.getMethodName());
            polling |= PerformanceFpsHookInstaller.class.getName().equals(element.getClassName())
                && "waitForHostLafReady".equals(element.getMethodName());
        }
        return sleeping && polling;
    }

    private static void installCubismLikeLaf() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            installLookAndFeel(new TestCubismLightTheme());
            UIManager.put("PanelUI", "javax.swing.plaf.metal.MetalPanelUI");
        });
    }

    private static void installLookAndFeel(final javax.swing.LookAndFeel lookAndFeel) {
        try {
            UIManager.setLookAndFeel(lookAndFeel);
        } catch (UnsupportedLookAndFeelException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** Occupies the EDT until {@link #release()}, so EDT-dispatched work can be observed. */
    private static final class EdtHold implements AutoCloseable {
        private final CountDownLatch held = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        private EdtHold() throws Exception {
            SwingUtilities.invokeLater(() -> {
                held.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(held.await(2, TimeUnit.SECONDS), "EDT hold did not start");
        }

        void release() {
            release.countDown();
        }

        @Override
        public void close() {
            release.countDown();
        }
    }

    /**
     * Look-and-feel whose class name trips the FlatLaf detector, so the poll
     * can succeed without a real FlatLaf on the test classpath.
     */
    private static final class TestCubismLightTheme extends javax.swing.LookAndFeel {
        @Override
        public String getName() {
            return "TestCubismLightTheme";
        }

        @Override
        public String getID() {
            return "TestCubismLightTheme";
        }

        @Override
        public String getDescription() {
            return "Stub look and feel for FPS hook install timing tests";
        }

        @Override
        public boolean isNativeLookAndFeel() {
            return false;
        }

        @Override
        public boolean isSupportedLookAndFeel() {
            return true;
        }

        @Override
        public UIDefaults getDefaults() {
            return new UIDefaults();
        }
    }

    /** Class used as the retransform target owner; loaded by the test classloader. */
    static final class TargetRenderScene {
        void render(final int frame) { }
    }

    private static Object defaultValue(final Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0f;
        if (type == double.class) return 0.0d;
        return null;
    }
}
