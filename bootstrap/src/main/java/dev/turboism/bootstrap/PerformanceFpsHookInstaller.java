package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.performance.NativePerformanceProbeBridge;
import dev.turboism.adapter.cubism.performance.PerformanceFpsHook;
import dev.turboism.adapter.cubism.performance.PerformanceProbeMetric;
import dev.turboism.adapter.cubism.performance.PerformanceProbeMethodTransformer;
import dev.turboism.adapter.cubism.performance.PerformanceProbeRecorder;
import dev.turboism.adapter.cubism.performance.PerformanceProbeRollbackObserver;
import dev.turboism.adapter.cubism.performance.PerformanceProbeTargets;
import dev.turboism.bootstrap.carrier.PerformanceProbeCallback;
import dev.turboism.bootstrap.carrier.PerformanceProbeCarrier;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import dev.turboism.ui.appearance.SwingFlatLafHostOperations;

import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * FPS counting hook for the preview agent: bytecode-instruments the Cubism
 * renderScene entry (unified with the validation probe — same carrier,
 * transformer, bridge, and recorder, mounted with the plugin lifecycle).
 *
 * <p>Install mounts the transformer and enables RENDER_SCENE counting for the
 * whole host session; classes loaded later are instrumented on load. Close
 * removes the transformer, retransforms every instrumented class back to its
 * original bytes, and verifies the restoration (after == before) per observed
 * owner, failing closed with diagnostics on any mismatch. Unsupported host
 * artifacts are rejected in the constructor.
 */
public final class PerformanceFpsHookInstaller implements PerformanceFpsHook {

    private static final long CUBISM_5203_SIZE = ReviewedHostArtifacts.CUBISM_5_2_03.size();
    private static final String CUBISM_5203_SHA256 = ReviewedHostArtifacts.CUBISM_5_2_03.sha256();

    private static final long CUBISM_5302_SIZE = ReviewedHostArtifacts.CUBISM_5_3_02.size();
    private static final String CUBISM_5302_SHA256 = ReviewedHostArtifacts.CUBISM_5_3_02.sha256();

    /**
     * How long the deferred loaded-target retransform waits for the host
     * FlatLaf look-and-feel before it runs anyway: the startup race window
     * only exists early, so a late pass is safe and a timeout must not drop
     * counts.
     */
    static final long LAF_READY_TIMEOUT_MILLIS = 30_000L;
    private static final long LAF_POLL_MILLIS = 100L;

    private final Instrumentation instrumentation;
    private final ClassLoader hostClassLoader;
    private final List<PerformanceProbeMethodTransformer.Target> targets;
    private final PerformanceProbeMethodTransformer transformer;
    private final PerformanceProbeRollbackObserver rollbackObserver;
    private final PerformanceProbeRecorder recorder = new PerformanceProbeRecorder();
    private final AtomicBoolean installed = new AtomicBoolean();
    private final Object lifecycleLock = new Object();
    private PerformanceProbeCallback callback;
    private boolean carrierOwned;
    private final long lafReadyTimeoutMillis;

    public PerformanceFpsHookInstaller(
        final Instrumentation instrumentation,
        final Path hostArtifact,
        final ClassLoader hostClassLoader
    ) throws Exception {
        this(
            instrumentation,
            hostArtifact,
            hostClassLoader,
            fpsTargetsFor(HostArtifactDigest.from(hostArtifact)),
            LAF_READY_TIMEOUT_MILLIS
        );
    }

    /**
     * Wiring seam shared with the reviewed-artifact constructor: binds exact
     * targets and a bounded L&F-readiness timeout. The artifact parameter is
     * only used for protection-domain filtering and may be {@code null} in
     * tests.
     */
    PerformanceFpsHookInstaller(
        final Instrumentation instrumentation,
        final Path hostArtifact,
        final ClassLoader hostClassLoader,
        final List<PerformanceProbeMethodTransformer.Target> targets,
        final long lafReadyTimeoutMillis
    ) {
        this.instrumentation = Objects.requireNonNull(instrumentation, "instrumentation");
        this.hostClassLoader = Objects.requireNonNull(hostClassLoader, "hostClassLoader");
        this.targets = List.copyOf(targets);
        this.transformer = new PerformanceProbeMethodTransformer(hostClassLoader, hostArtifact, targets);
        this.rollbackObserver = new PerformanceProbeRollbackObserver(hostClassLoader, hostArtifact, targets);
        this.lafReadyTimeoutMillis = lafReadyTimeoutMillis;
    }

    /**
     * FPS counting targets for one reviewed host artifact. Each exact version
     * routes through its own target list; the 5.2.03 entry is verified against
     * the exact 5.2.03 bytecode and is never inferred from 5.3.02. Unreviewed
     * artifacts fail closed.
     */
    static List<PerformanceProbeMethodTransformer.Target> fpsTargetsFor(
        final HostArtifactDigest digest
    ) {
        if (digest.size() == CUBISM_5203_SIZE && CUBISM_5203_SHA256.equals(digest.sha256())) {
            return PerformanceProbeTargets.cubism5203();
        }
        if (digest.size() == CUBISM_5302_SIZE && CUBISM_5302_SHA256.equals(digest.sha256())) {
            return PerformanceProbeTargets.cubism5302().stream()
                .filter(target -> target.metric() == PerformanceProbeMetric.RENDER_SCENE)
                .toList();
        }
        throw new IllegalArgumentException(
            "unsupported Cubism artifact for FPS counting"
                + " (expected Cubism 5.2.03 or 5.3.02; got size=" + digest.size()
                + " sha256=" + digest.sha256() + ")"
        );
    }

    @Override
    public void install() {
        synchronized (lifecycleLock) {
            if (!installed.compareAndSet(false, true)) return;
            if (!instrumentation.isRetransformClassesSupported()) {
                installed.set(false);
                throw new IllegalStateException("Class retransformation is unavailable.");
            }
            callback = new PerformanceProbeCallback() {
                @Override public long enter(final int metricId) {
                    return NativePerformanceProbeBridge.enter(recorder, metricId);
                }
                @Override public void exit(final int metricId, final long startedNanos) {
                    NativePerformanceProbeBridge.exit(recorder, metricId, startedNanos);
                }
            };
            PerformanceProbeCarrier.install(callback);
            carrierOwned = true;
            PerformanceProbeCarrier.enable(PerformanceProbeMetric.RENDER_SCENE.mask());
            instrumentation.addTransformer(transformer, true);
            deferLoadedTargetRetransform();
        }
    }

    /**
     * Defers the loaded-target retransform until the host FlatLaf
     * look-and-feel is installed: the S3 synchronous pass ran during early
     * startup (getAllLoadedClasses scan + retransform) and disturbed Cubism
     * 5.2.03 EDT window construction (279 "no ComponentUI class" errors).
     * The transformer stays registered immediately, so classes loaded after
     * {@code install()} are instrumented on load and counts are never lost;
     * the deferred pass only covers classes that were already loaded before
     * registration. It runs exactly once on a daemon thread (EDT-dispatched
     * readiness reads, sleeps on the daemon thread) and still runs after the
     * timeout — the startup race window is gone by then, and a late pass is
     * preferable to a lost count.
     */
    private void deferLoadedTargetRetransform() {
        final Thread thread = new Thread(() -> {
            waitForHostLafReady(lafReadyTimeoutMillis);
            synchronized (lifecycleLock) {
                if (!installed.get()) return; // closed before the deferred pass ran
                try {
                    retransformLoadedTargets();
                } catch (Throwable failure) {
                    // Fail-open diagnostic: on-load instrumentation keeps
                    // counting; only pre-install loaded classes are missed.
                    System.err.println(
                        "Turboism FPS counting hook deferred retransform failed safely: " + failure
                    );
                }
            }
        }, "turboism-fps-laf-ready");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Polls host FlatLaf readiness (EDT-dispatched reads, S1 pattern) with
     * sleeps on the calling daemon thread; returns when ready or the timeout
     * elapses. Package-private for focused tests.
     */
    boolean waitForHostLafReady(final long timeoutMillis) {
        final long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (SwingFlatLafHostOperations.isHostLafReady()) {
                    return true;
                }
            } catch (RuntimeException ignored) {
                // UIManager may not be ready while the host boots.
            }
            try {
                Thread.sleep(LAF_POLL_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * Retransforms already-loaded target classes that were not instrumented
     * on load; classes loaded later are handled on load. Owners the
     * transformer already instrumented (loaded after registration) are
     * skipped so the deferred pass can never double-instrument and double
     * count.
     */
    private void retransformLoadedTargets() {
        try {
            for (Class<?> loaded : loadedTargetClasses()) {
                final String owner = loaded.getName().replace('.', '/');
                if (transformer.instrumentedSha256().containsKey(owner)) {
                    continue; // already instrumented on load; retransform would double-count
                }
                instrumentation.retransformClasses(loaded);
            }
        } catch (java.lang.instrument.UnmodifiableClassException failure) {
            throw new IllegalStateException(
                "performance FPS hook target retransformation failed", failure
            );
        }
    }

    private List<Class<?>> loadedTargetClasses() {
        final List<String> targetNames = targets.stream()
            .map(target -> target.ownerInternalName().replace('/', '.'))
            .distinct().toList();
        final List<Class<?>> loaded = new ArrayList<>();
        for (Class<?> candidate : instrumentation.getAllLoadedClasses()) {
            if (targetNames.contains(candidate.getName())
                && candidate.getClassLoader() == hostClassLoader
                && instrumentation.isModifiableClass(candidate)) {
                loaded.add(candidate);
            }
        }
        return loaded;
    }

    @Override
    public boolean isInstalled() {
        return installed.get();
    }

    @Override
    public long renderSceneCalls() {
        return recorder.renderSceneCalls();
    }

    @Override
    public void close() {
        final PerformanceProbeCallback mounted;
        synchronized (lifecycleLock) {
            if (!installed.compareAndSet(true, false)) return;
            mounted = callback;
        }
        if (carrierOwned) {
            PerformanceProbeCarrier.disable();
        }
        instrumentation.removeTransformer(transformer);
        Throwable cleanupFailure = null;
        try {
            // Non-mutating observer registered last in the transformer chain so it
            // observes the final restored bytes during cleanup retransformation.
            instrumentation.addTransformer(rollbackObserver, true);
            rollbackObserver.beginRestoration();
            for (Class<?> target : loadedTargetClasses()) {
                try {
                    instrumentation.retransformClasses(target);
                } catch (Throwable failure) {
                    cleanupFailure = failure;
                }
            }
        } finally {
            instrumentation.removeTransformer(rollbackObserver);
            if (carrierOwned) {
                PerformanceProbeCarrier.clear(mounted);
            }
        }
        if (cleanupFailure != null) {
            throw new IllegalStateException("performance FPS hook bytecode restoration failed", cleanupFailure);
        }
        verifyRestoration();
    }

    /**
     * Rollback evidence: every owner this transformer instrumented must have
     * been observed exactly once during restoration with the original bytes.
     */
    private void verifyRestoration() {
        final var before = transformer.beforeSha256();
        final var after = rollbackObserver.observedSha256();
        final var observations = rollbackObserver.observationCounts();
        final List<String> mismatches = new ArrayList<>();
        for (String owner : before.keySet()) {
            final String restored = after.get(owner);
            final int count = observations.getOrDefault(owner, 0);
            if (restored == null || count != 1 || !restored.equals(before.get(owner))) {
                mismatches.add(owner + " (observations=" + count + ")");
            }
        }
        if (!mismatches.isEmpty()) {
            throw new IllegalStateException(
                "performance FPS hook bytecode restoration verification failed: " + mismatches
            );
        }
    }
}
