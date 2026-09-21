package dev.turboism.validation.fps;

import dev.turboism.sdk.performance.PerformanceProbeService;
import dev.turboism.sdk.performance.PerformanceSnapshot;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.tests.plugin.McpValidationHostClose;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Test-only two-plugin acceptance on the real public performance service. Never exits the JVM. */
public final class FpsLifecycleAcceptance {
    private FpsLifecycleAcceptance() { }

    static void run(final PluginContext context, final Path stateDir,
                    final String hostVersion, final String modelId) {
        final Map<String, String> result = new LinkedHashMap<>();
        final String runId = System.getProperty("turboism.validation.runId", "");
        final Path observer = stateDir.getParent().resolve("dev.turboism.validation.fps.observer");
        result.put("schemaVersion", "1");
        result.put("runId", runId);
        result.put("hostVersion", hostVersion);
        result.put("modelId", modelId);
        result.put("fixtureName", System.getProperty("turboism.validation.fixtureName", ""));
        result.put("authoring", "NOT_APPLICABLE-read-only-performance-service");
        result.put("undoRedoPersistence", "NOT_APPLICABLE-no-model-writes");
        boolean passed = false;
        try {
            check(result, "activeModelPresent", !modelId.isBlank() && !"missing".equals(modelId));
            final PerformanceProbeService stats = context.performanceStats();
            await(() -> number(observer, "count") >= 3, "observer-started");
            check(result, "observerStarted", true);
            final AtomicLong fastCount = new AtomicLong();
            final AtomicLong slowCount = new AtomicLong();
            final AtomicLong maxFrames = new AtomicLong();
            final Registration fast = stats.sample(Duration.ofMillis(50), sample -> {
                fastCount.incrementAndGet();
                maxFrames.accumulateAndGet(sample.renderedFrames(), Math::max);
            });
            final Registration slow = stats.sample(Duration.ofHours(1), sample -> slowCount.incrementAndGet());
            try {
                await(() -> fastCount.get() >= 12 && maxFrames.get() > 0, "native-frames-and-fast-delivery");
                check(result, "nativeFrames", maxFrames.get() > 0);
                check(result, "independentCadence", slowCount.get() == 0);
                result.put("fastSamples", Long.toString(fastCount.get()));
                result.put("slowSamples", Long.toString(slowCount.get()));
                result.put("renderSceneCalls", Long.toString(maxFrames.get()));
            } finally {
                slow.close();
                fast.close();
            }

            final AtomicLong sameCount = new AtomicLong();
            final Consumer<PerformanceSnapshot> same = sample -> sameCount.incrementAndGet();
            final Registration old = stats.sample(Duration.ofMillis(60), same);
            old.close();
            final Registration replacement = stats.sample(Duration.ofMillis(60), same);
            try {
                old.close();
                final long before = sameCount.get();
                await(() -> sameCount.get() >= before + 4, "replacement-survives-old-close");
                check(result, "oldHandleIsolation", true);
            } finally {
                replacement.close();
            }

            final AtomicLong scopedCount = new AtomicLong();
            stats.sample(Duration.ofMillis(60), sample -> scopedCount.incrementAndGet());
            await(() -> scopedCount.get() >= 3, "scope-owned-subscription");
            context.disposableScope().close();
            // A callback already admitted by the old scope may finish before this checkpoint.
            Thread.sleep(250);
            final long settled = scopedCount.get();
            final long observerBefore = number(observer, "count");
            await(() -> number(observer, "count") >= observerBefore + 4, "other-plugin-survives-scope-close");
            check(result, "scopeCancellation", scopedCount.get() == settled);
            check(result, "retainedSnapshotRejected", rejects(stats::snapshot));
            check(result, "retainedSampleRejected", rejects(() -> {
                final Registration leaked = stats.sample(Duration.ofHours(1), sample -> { });
                leaked.close();
            }));
            check(result, "otherPluginStillSampling", number(observer, "count") >= observerBefore + 4);
            result.put("observerSamplesAfterActorClose", Long.toString(number(observer, "count")));
            Files.writeString(observer.resolve("stop.request"), runId, StandardCharsets.UTF_8);
            await(() -> "STOPPED".equals(readObserver(observer).getProperty("state")), "observer-scope-close");
            final Properties stopped = readObserver(observer);
            check(result, "observerSnapshotRejected", "true".equals(stopped.getProperty("snapshotRejected")));
            check(result, "observerSampleRejected", "true".equals(stopped.getProperty("sampleRejected")));
            check(result, "observerScopeCancellation", "true".equals(stopped.getProperty("callbacksStopped")));
            passed = true;
        } catch (Throwable failure) {
            result.put("failure", failure.getClass().getName() + ":" + safe(failure.getMessage()));
            context.logger().warn("FPS_LIFECYCLE_ASSERTION_FAILED " + result.get("failure"));
            try { context.disposableScope().close(); }
            catch (Throwable cleanup) { result.put("cleanupFailure", cleanup.getClass().getName()); }
        }
        result.put("status", passed ? "PASS" : "FAIL");
        result.put("closeEvidence", "normal-native-close-requested-supervisor-verdict-required");
        try {
            publish(stateDir.resolve("result.txt"), result);
            context.logger().info("FPS_LIFECYCLE_RESULT status=" + result.get("status"));
            final String close = McpValidationHostClose.request(true, true, true, runId,
                System.getProperty("turboism.validation.hostVersion"));
            context.logger().info("FPS_LIFECYCLE_NORMAL_CLOSE " + close);
            if (close.startsWith("SKIPPED:")) {
                result.put("status", "FAIL");
                result.put("failure", "normal-close-not-admitted:" + safe(close));
                publish(stateDir.resolve("result.txt"), result);
            }
        } catch (Throwable closeFailure) {
            result.put("status", "FAIL");
            result.put("closeFailure", closeFailure.getClass().getName() + ":" + safe(closeFailure.getMessage()));
            try { publish(stateDir.resolve("result.txt"), result); }
            catch (IOException writeFailure) { context.logger().warn("FPS_LIFECYCLE_RESULT_WRITE_FAILED"); }
            context.logger().warn("FPS_LIFECYCLE_NORMAL_CLOSE_FAILED " + result.get("closeFailure"));
            // No arbitrary window, process exit, or force-close fallback. The queue owns containment.
        }
    }

    private static void check(final Map<String, String> result, final String name, final boolean condition) {
        result.put("assertion." + name + ".expected", "true");
        result.put("assertion." + name + ".actual", Boolean.toString(condition));
        result.put("assertion." + name + ".status", condition ? "PASS" : "FAIL");
        if (!condition) throw new IllegalStateException(name);
    }

    private static boolean rejects(final Runnable action) {
        try { action.run(); return false; }
        catch (IllegalStateException expected) { return true; }
    }

    private static void await(final BooleanSupplier condition, final String assertion) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline >= 0) throw new IllegalStateException("timeout:" + assertion);
            Thread.sleep(50);
        }
    }

    private static long number(final Path observer, final String key) {
        try { return Long.parseLong(readObserver(observer).getProperty(key, "-1")); }
        catch (NumberFormatException invalid) { return -1; }
    }

    private static Properties readObserver(final Path directory) {
        final Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(directory.resolve("observations.properties"), StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException unavailable) { /* A not-yet-published observer record is not success. */ }
        return properties;
    }

    private static String safe(final String value) {
        return value == null ? "none" : value.replace('\n', ' ').replace('\r', ' ');
    }

    private static void publish(final Path target, final Map<String, String> values) throws IOException {
        final StringBuilder text = new StringBuilder();
        values.forEach((key, value) -> text.append(key).append('=').append(safe(value)).append('\n'));
        final Path pending = target.resolveSibling(target.getFileName() + ".pending");
        Files.writeString(pending, text, StandardCharsets.UTF_8);
        try { Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException unsupported) { Files.move(pending, target, StandardCopyOption.REPLACE_EXISTING); }
    }
}
