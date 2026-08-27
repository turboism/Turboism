package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.performance.NativePerformanceProbeBridge;
import dev.turboism.adapter.cubism.performance.PerformanceProbeMethodTransformer;
import dev.turboism.adapter.cubism.performance.PerformanceProbeRecorder;
import dev.turboism.adapter.cubism.performance.PerformanceProbeReportWriter;
import dev.turboism.adapter.cubism.performance.PerformanceProbeRollbackObserver;
import dev.turboism.adapter.cubism.performance.PerformanceProbeRollbackWriter;
import dev.turboism.adapter.cubism.performance.PerformanceProbeTargets;
import dev.turboism.adapter.cubism.performance.PerformanceProbeMetric;
import dev.turboism.bootstrap.carrier.PerformanceProbeCallback;
import dev.turboism.bootstrap.carrier.PerformanceProbeCarrier;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarFile;

/** Owns an exact-version Cubism 5.3 validation-only timing transformer. */
final class VerifiedPerformanceProbeInstaller implements AutoCloseable {

    private static final Duration ADMISSION_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration ADMISSION_POLL_INTERVAL = Duration.ofMillis(500);
    private static final Duration TRIGGER_POLL_INTERVAL = Duration.ofMillis(500);

    private final Instrumentation instrumentation;
    private final ClassLoader hostClassLoader;
    private final String cubismVersion;
    private final String artifactSha256;
    private final List<PerformanceProbeMethodTransformer.Target> targets;
    private final PerformanceProbeMethodTransformer transformer;
    private final PerformanceProbeRollbackObserver rollbackObserver;
    private final PerformanceProbeRecorder recorder = new PerformanceProbeRecorder();
    private final PerformanceProbeCallback callback = new PerformanceProbeCallback() {
        @Override public long enter(final int metricId) {
            return NativePerformanceProbeBridge.enter(recorder, metricId);
        }
        @Override public void exit(final int metricId, final long startedNanos) {
            NativePerformanceProbeBridge.exit(recorder, metricId, startedNanos);
        }
    };
    private final AtomicBoolean installed = new AtomicBoolean();
    private final AtomicBoolean admitted = new AtomicBoolean();
    private final List<Class<?>> transformed = new ArrayList<>();
    private final ScheduledExecutorService reporter = Executors.newSingleThreadScheduledExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "turboism-performance-probe");
        thread.setDaemon(true);
        return thread;
    });
    private volatile String runId;
    private volatile Path rollbackOutput;
    private volatile String variant;
    private volatile String scenario;
    private volatile String agentSha256;
    private volatile String fixtureSha256;

    VerifiedPerformanceProbeInstaller(
        final Instrumentation instrumentation,
        final Path hostArtifact,
        final ClassLoader hostClassLoader,
        final Path carrierJar
    ) throws Exception {
        this.instrumentation = Objects.requireNonNull(instrumentation, "instrumentation");
        this.hostClassLoader = Objects.requireNonNull(hostClassLoader, "hostClassLoader");
        final HostArtifactDigest digest = HostArtifactDigest.from(hostArtifact);
        final ProbeProfile profile = profileForArtifact(digest);
        this.cubismVersion = profile.cubismVersion();
        this.artifactSha256 = digest.sha256();
        this.targets = profile.targets();
        appendCarrier(Objects.requireNonNull(carrierJar, "carrierJar"));
        final Class<?> visibleCarrier = Class.forName(
            PerformanceProbeCarrier.class.getName(), false, hostClassLoader
        );
        if (visibleCarrier != PerformanceProbeCarrier.class
            || visibleCarrier.getClassLoader() != ClassLoader.getSystemClassLoader()) {
            throw new IllegalStateException("performance probe carrier identity mismatch");
        }
        this.transformer = new PerformanceProbeMethodTransformer(hostClassLoader, hostArtifact, targets);
        this.rollbackObserver = new PerformanceProbeRollbackObserver(hostClassLoader, hostArtifact, targets);
    }

    static ProbeProfile profileForArtifact(final HostArtifactDigest artifact) {
        Objects.requireNonNull(artifact, "artifact");
        if (ReviewedHostArtifacts.CUBISM_5_3_02.equals(artifact)) {
            return new ProbeProfile(
                ReviewedHostArtifacts.CUBISM_5_3_02_VERSION,
                PerformanceProbeTargets.cubism5302()
            );
        }
        if (ReviewedHostArtifacts.CUBISM_5_3_03.equals(artifact)) {
            return new ProbeProfile(
                ReviewedHostArtifacts.CUBISM_5_3_03_VERSION,
                PerformanceProbeTargets.cubism5303()
            );
        }
        throw new IllegalArgumentException("unsupported Cubism artifact for performance probe");
    }

    void install(
        final boolean capture,
        final String scenario,
        final String agentSha256,
        final String fixtureSha256,
        final Duration delay,
        final Duration duration,
        final Path output,
        final String runId,
        final Path rollbackOutput
    ) throws Exception {
        if (!installed.compareAndSet(false, true)) return;
        if (!instrumentation.isRetransformClassesSupported()) {
            installed.set(false);
            throw new IllegalStateException("Class retransformation is unavailable.");
        }
        this.runId = runId;
        this.rollbackOutput = rollbackOutput == null ? null : rollbackOutput.toAbsolutePath().normalize();
        this.variant = capture ? "on" : "off";
        this.scenario = scenario;
        this.agentSha256 = agentSha256;
        this.fixtureSha256 = fixtureSha256;
        PerformanceProbeCarrier.install(callback);
        instrumentation.addTransformer(transformer, true);
        try {
            final List<String> targetNames = targets.stream()
                .map(target -> target.ownerInternalName().replace('/', '.'))
                .distinct().toList();
            for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
                if (targetNames.contains(loaded.getName())
                    && loaded.getClassLoader() == hostClassLoader
                    && instrumentation.isModifiableClass(loaded)) {
                    transformed.add(loaded);
                    instrumentation.retransformClasses(loaded);
                }
            }
            awaitAdmission();
            admitted.set(true);
            if (capture) scheduleCapture(
                scenario, agentSha256, fixtureSha256, delay, duration, output
            );
            startTriggerWatch();
        } catch (Throwable failure) {
            close();
            throw failure;
        }
    }

    /**
     * Starts the daemon trigger watch. On the exact Cubism host the JVM exits
     * through a native path and Java shutdown hooks do not run, so the
     * rollback manifest must be published on a deterministic trigger while the
     * JVM is still alive: when {@code rollbackOutput}.trigger appears, this
     * thread calls {@link #close()} (restore bytecode + publish manifest) and
     * stops. The watch also stops on {@code close()} from any other path via
     * the {@code installed} flag. Never blocks {@link #install()}.
     */
    private void startTriggerWatch() {
        final Path rollback = rollbackOutput;
        if (rollback == null) return;
        final Path trigger = rollback.resolveSibling(rollback.getFileName() + ".trigger");
        final Thread watch = new Thread(() -> {
            while (installed.get()) {
                if (Files.isRegularFile(trigger)) {
                    System.err.println("Turboism performance probe trigger detected: " + trigger);
                    try {
                        close();
                    } catch (Throwable failure) {
                        System.err.println("Turboism performance probe trigger close failed safely: "
                            + failure.getClass().getName() + ": " + failure.getMessage()
                            + (failure.getCause() == null ? ""
                                : " cause=" + failure.getCause().getClass().getName()
                                    + ": " + failure.getCause().getMessage()));
                    }
                    return;
                }
                try {
                    Thread.sleep(TRIGGER_POLL_INTERVAL.toMillis());
                } catch (InterruptedException interrupted) {
                    return;
                }
            }
        }, "turboism-probe-trigger");
        watch.setDaemon(true);
        watch.start();
    }

    /**
     * Polls admission with a bounded deadline. Render-target classes load with
     * the modeling document, after the agent attaches, and are instrumented on
     * load by this canRetransform transformer; admission succeeds once all
     * owners are transformed and every selector matched exactly once. On
     * deadline with incomplete admission, fail closed exactly as the former
     * immediate check did.
     */
    private void awaitAdmission() throws InterruptedException {
        final Set<String> owners = targets.stream()
            .map(PerformanceProbeMethodTransformer.Target::ownerInternalName)
            .collect(Collectors.toUnmodifiableSet());
        final long deadline = System.nanoTime() + ADMISSION_TIMEOUT.toNanos();
        while (true) {
            final Set<String> transformedOwners = transformer.beforeSha256().keySet();
            final boolean everySelectorSingle = transformer.matchCounts()
                .values().stream().allMatch(count -> count == 1);
            if (transformedOwners.containsAll(owners) && everySelectorSingle) return;
            if (System.nanoTime() >= deadline) {
                final Set<String> missing = new TreeSet<>(owners);
                missing.removeAll(transformedOwners);
                final List<String> badSelectors = transformer.matchCounts().entrySet().stream()
                    .filter(entry -> entry.getValue() != 1)
                    .map(entry -> entry.getKey().ownerInternalName() + "."
                        + entry.getKey().methodName() + "=" + entry.getValue())
                    .toList();
                throw new IllegalStateException(
                    "performance probe target admission was incomplete after "
                        + ADMISSION_TIMEOUT.toSeconds() + "s; missing owners=" + missing
                        + ", selectors=" + badSelectors);
            }
            Thread.sleep(ADMISSION_POLL_INTERVAL.toMillis());
        }
    }

    /** Currently loaded classes this transformer actually instrumented, pinned to the host loader. */
    private List<Class<?>> currentlyLoadedTargets() {
        final Set<String> instrumentedOwners = transformer.beforeSha256().keySet();
        final List<Class<?>> loaded = new ArrayList<>();
        for (Class<?> candidate : instrumentation.getAllLoadedClasses()) {
            if (instrumentedOwners.contains(candidate.getName().replace('.', '/'))
                && candidate.getClassLoader() == hostClassLoader
                && instrumentation.isModifiableClass(candidate)) {
                loaded.add(candidate);
            }
        }
        return loaded;
    }

    private void appendCarrier(final Path carrierJar) throws IOException {
        instrumentation.appendToSystemClassLoaderSearch(new JarFile(carrierJar.toFile()));
    }

    private void scheduleCapture(
        final String scenario,
        final String agentSha256,
        final String fixtureSha256,
        final Duration delay,
        final Duration duration,
        final Path output
    ) {
        reporter.schedule(() -> {
            final long started = System.currentTimeMillis();
            if (!recorder.startCapture()) {
                System.err.println("Turboism performance probe capture rejected safely");
                return;
            }
            PerformanceProbeCarrier.enable(metricMask(scenario));
            reporter.schedule(() -> {
                PerformanceProbeCarrier.disable();
                recorder.stopCapture();
                if (!recorder.awaitQuiescence(5_000L)) recorder.fail();
                try {
                    new PerformanceProbeReportWriter().write(
                        output,
                        cubismVersion,
                        artifactSha256,
                        agentSha256,
                        fixtureSha256,
                        scenario,
                        started,
                        System.currentTimeMillis(),
                        recorder.snapshot()
                    );
                } catch (Throwable failure) {
                    System.err.println("Turboism performance probe report failed safely");
                }
            }, duration.toSeconds(), TimeUnit.SECONDS);
        }, delay.toSeconds(), TimeUnit.SECONDS);
    }

    private static long metricMask(final String scenario) {
        long mask = PerformanceProbeMetric.RENDER_SCENE.mask()
            | PerformanceProbeMetric.MODELING_PRE_RENDER_UPDATE.mask()
            | PerformanceProbeMetric.RENDER_SYSTEM.mask()
            | PerformanceProbeMetric.SCENE_TRAVERSAL.mask()
            | PerformanceProbeMetric.RENDERER_DISPATCH.mask();
        if (scenario.equals("edit")) {
            mask |= PerformanceProbeMetric.UPDATE_MODEL_INSTANCES.mask()
                | PerformanceProbeMetric.REINIT_MODEL_INSTANCE_EXE.mask();
        }
        return mask;
    }

    @Override
    public void close() {
        if (!installed.get()) {
            System.err.println("Turboism performance probe close skipped: not installed");
            return;
        }
        if (!installed.compareAndSet(true, false)) return;
        System.err.println(
            "Turboism performance probe close started; admitted=" + admitted.get()
                + ", runId=" + runId + ", rollbackOutput=" + rollbackOutput);
        recorder.stopCapture();
        PerformanceProbeCarrier.disable();
        reporter.shutdownNow();
        instrumentation.removeTransformer(transformer);
        Throwable cleanupFailure = null;
        try {
            // Register the non-mutating observer only now, last in the current
            // transformer chain, so it observes the final restored bytes during
            // cleanup retransformation after the mutating transformer is gone.
            instrumentation.addTransformer(rollbackObserver, true);
            rollbackObserver.beginRestoration();
            final Set<Class<?>> restoreTargets = new LinkedHashSet<>(transformed);
            restoreTargets.addAll(currentlyLoadedTargets());
            System.err.println("Turboism performance probe close: restoreTargets="
                + restoreTargets.stream().map(Class::getName).sorted().toList());
            for (Class<?> target : restoreTargets) {
                try {
                    if (instrumentation.isModifiableClass(target)) instrumentation.retransformClasses(target);
                } catch (Throwable failure) {
                    cleanupFailure = failure;
                }
            }
            transformed.clear();
        } finally {
            instrumentation.removeTransformer(rollbackObserver);
            PerformanceProbeCarrier.clear(callback);
        }
        if (cleanupFailure != null) {
            throw new IllegalStateException("performance probe bytecode restoration failed", cleanupFailure);
        }
        publishRollbackManifest();
    }

    /**
     * Publishes the strict rollback-evidence manifest from the actual bytes
     * observed during install and restoration. Throws on any partial,
     * mismatched, or failed evidence so the failure stays visible through the
     * probe cleanup diagnostic; nothing is written on failure.
     */
    private void publishRollbackManifest() {
        if (!admitted.get() || runId == null || rollbackOutput == null) return;
        try {
            final Map<String, PerformanceProbeRollbackWriter.OwnerEvidence> owners = new LinkedHashMap<>();
            final Map<String, String> before = transformer.beforeSha256();
            final Map<String, String> instrumented = transformer.instrumentedSha256();
            final Map<String, String> after = rollbackObserver.observedSha256();
            for (PerformanceProbeMethodTransformer.Target target : targets) {
                final String owner = target.ownerInternalName();
                final String dotted = owner.replace('/', '.');
                owners.put(dotted, new PerformanceProbeRollbackWriter.OwnerEvidence(
                    before.get(owner),
                    instrumented.get(owner),
                    after.get(owner)
                ));
            }
            final Map<String, Integer> restorationMatches = new LinkedHashMap<>();
            rollbackObserver.observationCounts().forEach((owner, count) ->
                restorationMatches.put(owner.replace('/', '.'), count));
            new PerformanceProbeRollbackWriter().write(
                rollbackOutput,
                cubismVersion,
                artifactSha256,
                runId,
                variant,
                scenario,
                agentSha256,
                fixtureSha256,
                targets,
                owners,
                transformer.matchCounts(),
                restorationMatches
            );
        } catch (Throwable failure) {
            throw new IllegalStateException("performance probe rollback manifest failed", failure);
        }
    }

    record ProbeProfile(
        String cubismVersion,
        List<PerformanceProbeMethodTransformer.Target> targets
    ) {
        ProbeProfile {
            Objects.requireNonNull(cubismVersion, "cubismVersion");
            targets = List.copyOf(Objects.requireNonNull(targets, "targets"));
            if (targets.isEmpty()) {
                throw new IllegalArgumentException("performance probe targets must not be empty");
            }
        }
    }
}
