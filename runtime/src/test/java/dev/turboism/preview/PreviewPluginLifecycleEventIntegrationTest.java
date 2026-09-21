package dev.turboism.preview;

import dev.turboism.adapter.host.HostSession;
import dev.turboism.core.event.RuntimeEventBroker;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.sdk.runtime.PluginLifecycleEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runtime-owned {@link PluginLifecycleEvent} verdicts observed through the real plugin
 * load/close path: a real observer plugin declares {@code turboism.plugin.lifecycle.observe}
 * and receives the permission-filtered stream, while a trusted raw-broker subscription sees
 * every verdict including the runtime-owned core's. Individual targets are unloaded through
 * the existing {@code unloadOne} seam so the observer stays active for deferred verdicts.
 */
class PreviewPluginLifecycleEventIntegrationTest {

    private static final String OBSERVER_ID = "dev.example.lifecycle-observer";
    private static final String PLUGIN_QUEUE_PROPERTY = "probe.lifecycle.plugin-queue";
    private static final String RELEASE_PROPERTY = "probe.lifecycle.release";
    private static final String SCOPE_COUNT_PROPERTY = "probe.lifecycle.scope-count";
    private static final String SHUTDOWN_COUNT_PROPERTY = "probe.lifecycle.shutdown-count";
    private static final String MARKER_ID = "probe.lifecycle.drain-marker";
    private static final String CORE_ID = "turboism.core";

    @TempDir
    Path temporary;

    private List<String> rawEvents;
    private List<String> pluginEvents;
    private AtomicLong markerSequence;
    private volatile String lastMarkerRow;
    private CountDownLatch release;
    private AtomicInteger scopeCount;
    private AtomicInteger shutdownCount;

    @Test
    void loadVerdictsReportAdmittedGenerationsAndPreAdmissionFailures() throws Exception {
        writePlugin("00-observer.jar", OBSERVER_ID, "probe.observer.ObserverPlugin",
            observerSource(), null, OBSERVER_PERMISSIONS);
        writePlugin("10-success.jar", "dev.example.lifecycle-success",
            "probe.target.SuccessPlugin", successSource(), afterObserver(), "[]");
        writePlugin("20-fail-enable.jar", "dev.example.lifecycle-fail-enable",
            "probe.target.FailEnablePlugin", failEnableSource(), afterObserver(), "[]");
        writePlugin("30-fail-ctor.jar", "dev.example.lifecycle-fail-ctor",
            "probe.target.FailCtorPlugin", failCtorSource(), afterObserver(), "[]");

        final RuntimeScheduler scheduler = scheduler();
        final HostSession host = new HostSession(Optional::empty);
        installProbeState();
        try (PreviewLog log = new PreviewLog(temporary.resolve("logs/turboism.log"))) {
            final LocalPluginRuntime runtime = new LocalPluginRuntime(
                temporary, scheduler, host.adapterAccess(), log
            );
            try {
                final RuntimeEventBroker broker = brokerOf(runtime);
                broker.subscribe(
                    "test.lifecycle-recorder",
                    PluginLifecycleEvent.class,
                    event -> rawEvents.add(row(event))
                );
                runtime.loadAll();

                awaitRows(rawEvents,
                    CORE_ID + ":1:LOAD:SUCCEEDED",
                    OBSERVER_ID + ":1:LOAD:SUCCEEDED",
                    "dev.example.lifecycle-success:1:LOAD:SUCCEEDED",
                    "dev.example.lifecycle-fail-enable:1:LOAD:FAILED",
                    "dev.example.lifecycle-fail-ctor:" + PluginLifecycleEvent.NO_ADMITTED_GENERATION
                        + ":LOAD:FAILED");
                // The plugin-observed stream is permission-filtered and starts only once the
                // observer's own subscription is registered: the core loaded before it existed.
                awaitRows(pluginEvents,
                    OBSERVER_ID + ":1:LOAD:SUCCEEDED",
                    "dev.example.lifecycle-success:1:LOAD:SUCCEEDED",
                    "dev.example.lifecycle-fail-enable:1:LOAD:FAILED",
                    "dev.example.lifecycle-fail-ctor:" + PluginLifecycleEvent.NO_ADMITTED_GENERATION
                        + ":LOAD:FAILED");
                awaitDrain(rawEvents, broker);
                awaitPluginDrain(pluginEvents);

                assertFalse(
                    snapshot(pluginEvents).stream().anyMatch(row -> row.startsWith(CORE_ID)),
                    "the observer subscribed after the core loaded and must not see its verdict"
                );
                assertExactlyOnce(rawEvents, OBSERVER_ID + ":1:LOAD:SUCCEEDED");
                assertExactlyOnce(rawEvents, "dev.example.lifecycle-success:1:LOAD:SUCCEEDED");
            } finally {
                runtime.close();
            }
        } finally {
            clearProbeState();
            host.close();
            awaitSchedulerLeasesReleased(scheduler);
            scheduler.shutdown();
        }
    }

    @Test
    void unloadSucceededAndThrownShutdownReportTheirRealVerdicts() throws Exception {
        writePlugin("00-observer.jar", OBSERVER_ID, "probe.observer.ObserverPlugin",
            observerSource(), null, OBSERVER_PERMISSIONS);
        writePlugin("10-success.jar", "dev.example.lifecycle-success",
            "probe.target.SuccessPlugin", successSource(), afterObserver(), "[]");
        writePlugin("20-shutdown-fail.jar", "dev.example.lifecycle-shutdown-fail",
            "probe.target.ShutdownFailPlugin", shutdownFailSource(), afterObserver(), "[]");

        final RuntimeScheduler scheduler = scheduler();
        final HostSession host = new HostSession(Optional::empty);
        installProbeState();
        try (PreviewLog log = new PreviewLog(temporary.resolve("logs/turboism.log"))) {
            final LocalPluginRuntime runtime = new LocalPluginRuntime(
                temporary, scheduler, host.adapterAccess(), log
            );
            try {
                final RuntimeEventBroker broker = brokerOf(runtime);
                broker.subscribe(
                    "test.lifecycle-recorder",
                    PluginLifecycleEvent.class,
                    event -> rawEvents.add(row(event))
                );
                runtime.loadAll();
                awaitRows(rawEvents, OBSERVER_ID + ":1:LOAD:SUCCEEDED");

                unloadOne(runtime, "dev.example.lifecycle-success");
                unloadOne(runtime, "dev.example.lifecycle-shutdown-fail");
                awaitDrain(rawEvents, broker);
                awaitPluginDrain(pluginEvents);

                assertExactlyOnce(rawEvents, "dev.example.lifecycle-success:1:UNLOAD:SUCCEEDED");
                assertExactlyOnce(
                    rawEvents, "dev.example.lifecycle-shutdown-fail:1:UNLOAD:FAILED"
                );
                assertFalse(
                    snapshot(rawEvents).stream().anyMatch(row ->
                        row.equals("dev.example.lifecycle-shutdown-fail:1:UNLOAD:SUCCEEDED")),
                    "a thrown shutdown body must never report UNLOAD/SUCCEEDED"
                );
                // The observer is still active and sees both verdicts through the filtered path.
                assertExactlyOnce(
                    pluginEvents, "dev.example.lifecycle-success:1:UNLOAD:SUCCEEDED"
                );
                assertExactlyOnce(
                    pluginEvents, "dev.example.lifecycle-shutdown-fail:1:UNLOAD:FAILED"
                );
                assertEquals(
                    0,
                    shutdownOf(runtime).retainedGenerationCount(),
                    "clean disposal ends retention tracking"
                );
            } finally {
                runtime.close();
            }
        } finally {
            clearProbeState();
            host.close();
            awaitSchedulerLeasesReleased(scheduler);
            scheduler.shutdown();
        }
    }

    @Test
    void unprovenScopeDisposalReportsFailedOnceAndStaysRetained() throws Exception {
        writePlugin("00-observer.jar", OBSERVER_ID, "probe.observer.ObserverPlugin",
            observerSource(), null, OBSERVER_PERMISSIONS);
        writePlugin("10-scope-fail.jar", "dev.example.lifecycle-scope-fail",
            "probe.target.ScopeFailPlugin", scopeFailSource(), afterObserver(), "[]");

        final RuntimeScheduler scheduler = scheduler();
        final HostSession host = new HostSession(Optional::empty);
        installProbeState();
        try (PreviewLog log = new PreviewLog(temporary.resolve("logs/turboism.log"))) {
            final LocalPluginRuntime runtime = new LocalPluginRuntime(
                temporary, scheduler, host.adapterAccess(), log
            );
            try {
                final RuntimeEventBroker broker = brokerOf(runtime);
                broker.subscribe(
                    "test.lifecycle-recorder",
                    PluginLifecycleEvent.class,
                    event -> rawEvents.add(row(event))
                );
                runtime.loadAll();
                awaitRows(rawEvents, OBSERVER_ID + ":1:LOAD:SUCCEEDED");

                unloadOne(runtime, "dev.example.lifecycle-scope-fail");
                awaitDrain(rawEvents, broker);
                awaitPluginDrain(pluginEvents);

                assertExactlyOnce(rawEvents, "dev.example.lifecycle-scope-fail:1:UNLOAD:FAILED");
                assertFalse(
                    snapshot(rawEvents).stream().anyMatch(row ->
                        row.equals("dev.example.lifecycle-scope-fail:1:UNLOAD:SUCCEEDED")),
                    "unproven scope disposal must never report UNLOAD/SUCCEEDED"
                );
                assertEquals(
                    1, scopeCount.get(), "the one-shot scope closer ran exactly once"
                );
                // Disposal was never proven: the generation stays retained, but the sticky
                // verdict must not be re-published by retention re-drives.
                assertTrue(
                    shutdownOf(runtime).retainedGenerationCount() >= 1,
                    "a generation with unproven disposal must stay retained"
                );
                awaitDrain(rawEvents, broker);
                awaitPluginDrain(pluginEvents);
                assertExactlyOnce(rawEvents, "dev.example.lifecycle-scope-fail:1:UNLOAD:FAILED");
                assertExactlyOnce(
                    pluginEvents, "dev.example.lifecycle-scope-fail:1:UNLOAD:FAILED"
                );
            } finally {
                runtime.close();
            }
        } finally {
            clearProbeState();
            host.close();
            awaitSchedulerLeasesReleased(scheduler);
            scheduler.shutdown();
        }
    }

    /**
     * A lane-FAILED close (a hook that throws before any stage runs) still owes the
     * generation its scope/classloader disposal: the verdict is final, and the
     * retained re-drive finishes the remaining one-shot stages without a second
     * verdict instead of dropping the references.
     */
    @Test
    void failedCloseStageStillReclaimsDisposalThroughRetention() throws Exception {
        writePlugin("00-observer.jar", OBSERVER_ID, "probe.observer.ObserverPlugin",
            observerSource(), null, OBSERVER_PERMISSIONS);
        writePlugin("10-closehook-fail.jar", "dev.example.lifecycle-closehook-fail",
            "probe.target.ScopeCountPlugin", scopeCountSource(), afterObserver(), "[]");

        final RuntimeScheduler scheduler = scheduler();
        final HostSession host = new HostSession(Optional::empty);
        installProbeState();
        try (PreviewLog log = new PreviewLog(temporary.resolve("logs/turboism.log"))) {
            final LocalPluginRuntime runtime = new LocalPluginRuntime(
                temporary, scheduler, host.adapterAccess(), log,
                (pluginId, phase) -> {
                    if ("dev.example.lifecycle-closehook-fail".equals(pluginId)
                        && "close".equals(phase)) {
                        throw new IllegalStateException("close hook failed on purpose");
                    }
                }
            );
            try {
                final RuntimeEventBroker broker = brokerOf(runtime);
                broker.subscribe(
                    "test.lifecycle-recorder",
                    PluginLifecycleEvent.class,
                    event -> rawEvents.add(row(event))
                );
                runtime.loadAll();
                awaitRows(rawEvents,
                    OBSERVER_ID + ":1:LOAD:SUCCEEDED",
                    "dev.example.lifecycle-closehook-fail:1:LOAD:SUCCEEDED");

                unloadOne(runtime, "dev.example.lifecycle-closehook-fail");
                awaitRows(rawEvents, "dev.example.lifecycle-closehook-fail:1:UNLOAD:FAILED");
                awaitDrain(rawEvents, broker);
                awaitPluginDrain(pluginEvents);

                awaitTrue(
                    () -> shutdownOf(runtime).retainedGenerationCount() == 0,
                    "retention must finish the deferred disposal and drain"
                );
                assertEquals(
                    1, scopeCount.get(),
                    "the scope closer ran exactly once, through the retained re-drive"
                );
                assertExactlyOnce(
                    rawEvents, "dev.example.lifecycle-closehook-fail:1:UNLOAD:FAILED"
                );
                assertFalse(
                    snapshot(rawEvents).stream().anyMatch(row ->
                        row.equals("dev.example.lifecycle-closehook-fail:1:UNLOAD:SUCCEEDED")),
                    "a failed close stage must not gain a contradicting success verdict"
                );
                assertExactlyOnce(
                    pluginEvents, "dev.example.lifecycle-closehook-fail:1:UNLOAD:FAILED"
                );
            } finally {
                runtime.close();
            }
        } finally {
            clearProbeState();
            host.close();
            awaitSchedulerLeasesReleased(scheduler);
            scheduler.shutdown();
        }
    }

    @Test
    void timedOutCloseReportsTimeoutThenDeferredSuccess() throws Exception {
        writePlugin("00-observer.jar", OBSERVER_ID, "probe.observer.ObserverPlugin",
            observerSource(), null, OBSERVER_PERMISSIONS);
        writePlugin("10-block.jar", "dev.example.lifecycle-block",
            "probe.target.BlockPlugin", blockSource(), afterObserver(), "[]");

        final RuntimeScheduler scheduler = scheduler();
        final HostSession host = new HostSession(Optional::empty);
        installProbeState();
        try (PreviewLog log = new PreviewLog(temporary.resolve("logs/turboism.log"))) {
            final LocalPluginRuntime runtime = new LocalPluginRuntime(
                temporary, scheduler, host.adapterAccess(), log,
                (pluginId, phase) -> { }, shortClosePolicy()
            );
            try {
                final RuntimeEventBroker broker = brokerOf(runtime);
                broker.subscribe(
                    "test.lifecycle-recorder",
                    PluginLifecycleEvent.class,
                    event -> rawEvents.add(row(event))
                );
                runtime.loadAll();
                awaitRows(rawEvents, OBSERVER_ID + ":1:LOAD:SUCCEEDED");

                unloadOne(runtime, "dev.example.lifecycle-block");
                awaitDrain(rawEvents, broker);
                awaitPluginDrain(pluginEvents);
                assertExactlyOnce(rawEvents, "dev.example.lifecycle-block:1:UNLOAD:TIMED_OUT");
                assertFalse(
                    snapshot(rawEvents).stream().anyMatch(row ->
                        row.equals("dev.example.lifecycle-block:1:UNLOAD:SUCCEEDED")),
                    "a timed-out close must never report success while cleanup is incomplete"
                );

                release.countDown();
                awaitRows(rawEvents, "dev.example.lifecycle-block:1:UNLOAD:SUCCEEDED");
                awaitDrain(rawEvents, broker);
                awaitPluginDrain(pluginEvents);

                assertExactlyOnce(rawEvents, "dev.example.lifecycle-block:1:UNLOAD:TIMED_OUT");
                assertExactlyOnce(rawEvents, "dev.example.lifecycle-block:1:UNLOAD:SUCCEEDED");
                awaitTrue(
                    () -> shutdownOf(runtime).retainedGenerationCount() == 0,
                    "retained generation must drain once cleanup genuinely completes"
                );
            } finally {
                release.countDown();
                runtime.close();
            }
        } finally {
            clearProbeState();
            host.close();
            awaitSchedulerLeasesReleased(scheduler);
            scheduler.shutdown();
        }
    }

    @Test
    void timedOutCloseWithTerminalFailureReportsTimeoutThenFailed() throws Exception {
        writePlugin("00-observer.jar", OBSERVER_ID, "probe.observer.ObserverPlugin",
            observerSource(), null, OBSERVER_PERMISSIONS);
        writePlugin("10-block-fail.jar", "dev.example.lifecycle-block-fail",
            "probe.target.BlockFailPlugin", blockFailSource(), afterObserver(), "[]");

        final RuntimeScheduler scheduler = scheduler();
        final HostSession host = new HostSession(Optional::empty);
        installProbeState();
        try (PreviewLog log = new PreviewLog(temporary.resolve("logs/turboism.log"))) {
            final LocalPluginRuntime runtime = new LocalPluginRuntime(
                temporary, scheduler, host.adapterAccess(), log,
                (pluginId, phase) -> { }, shortClosePolicy()
            );
            try {
                final RuntimeEventBroker broker = brokerOf(runtime);
                broker.subscribe(
                    "test.lifecycle-recorder",
                    PluginLifecycleEvent.class,
                    event -> rawEvents.add(row(event))
                );
                runtime.loadAll();
                awaitRows(rawEvents, OBSERVER_ID + ":1:LOAD:SUCCEEDED");

                unloadOne(runtime, "dev.example.lifecycle-block-fail");
                awaitDrain(rawEvents, broker);
                assertExactlyOnce(
                    rawEvents, "dev.example.lifecycle-block-fail:1:UNLOAD:TIMED_OUT"
                );

                release.countDown();
                awaitRows(rawEvents, "dev.example.lifecycle-block-fail:1:UNLOAD:FAILED");
                awaitDrain(rawEvents, broker);
                awaitPluginDrain(pluginEvents);

                assertExactlyOnce(
                    rawEvents, "dev.example.lifecycle-block-fail:1:UNLOAD:TIMED_OUT"
                );
                assertExactlyOnce(
                    rawEvents, "dev.example.lifecycle-block-fail:1:UNLOAD:FAILED"
                );
                assertEquals(1, shutdownCount.get(), "shutdown ran exactly once");
                // Shutdown failed but scope/classloader disposal completed: terminal and released.
                awaitTrue(
                    () -> shutdownOf(runtime).retainedGenerationCount() == 0,
                    "proven disposal releases the retained generation"
                );
            } finally {
                release.countDown();
                runtime.close();
            }
        } finally {
            clearProbeState();
            host.close();
            awaitSchedulerLeasesReleased(scheduler);
            scheduler.shutdown();
        }
    }

    @Test
    void timedOutCloseWithScopeFailureStaysRetainedAfterFailedVerdict() throws Exception {
        writePlugin("00-observer.jar", OBSERVER_ID, "probe.observer.ObserverPlugin",
            observerSource(), null, OBSERVER_PERMISSIONS);
        writePlugin("10-block-scope-fail.jar", "dev.example.lifecycle-block-scope-fail",
            "probe.target.BlockScopeFailPlugin", blockScopeFailSource(), afterObserver(), "[]");

        final RuntimeScheduler scheduler = scheduler();
        final HostSession host = new HostSession(Optional::empty);
        installProbeState();
        try (PreviewLog log = new PreviewLog(temporary.resolve("logs/turboism.log"))) {
            final LocalPluginRuntime runtime = new LocalPluginRuntime(
                temporary, scheduler, host.adapterAccess(), log,
                (pluginId, phase) -> { }, shortClosePolicy()
            );
            try {
                final RuntimeEventBroker broker = brokerOf(runtime);
                broker.subscribe(
                    "test.lifecycle-recorder",
                    PluginLifecycleEvent.class,
                    event -> rawEvents.add(row(event))
                );
                runtime.loadAll();
                awaitRows(rawEvents, OBSERVER_ID + ":1:LOAD:SUCCEEDED");

                unloadOne(runtime, "dev.example.lifecycle-block-scope-fail");
                awaitDrain(rawEvents, broker);
                assertExactlyOnce(
                    rawEvents, "dev.example.lifecycle-block-scope-fail:1:UNLOAD:TIMED_OUT"
                );

                release.countDown();
                awaitRows(rawEvents, "dev.example.lifecycle-block-scope-fail:1:UNLOAD:FAILED");
                awaitDrain(rawEvents, broker);
                awaitPluginDrain(pluginEvents);

                assertExactlyOnce(
                    rawEvents, "dev.example.lifecycle-block-scope-fail:1:UNLOAD:TIMED_OUT"
                );
                assertExactlyOnce(
                    rawEvents, "dev.example.lifecycle-block-scope-fail:1:UNLOAD:FAILED"
                );
                assertFalse(
                    snapshot(rawEvents).stream().anyMatch(row ->
                        row.equals("dev.example.lifecycle-block-scope-fail:1:UNLOAD:SUCCEEDED")),
                    "a deferred terminal failure must not emit a contradicting success"
                );
                assertEquals(1, scopeCount.get(), "scope closer ran exactly once");
                assertTrue(
                    shutdownOf(runtime).retainedGenerationCount() >= 1,
                    "unproven disposal must keep the generation retained"
                );
            } finally {
                release.countDown();
                runtime.close();
            }
        } finally {
            clearProbeState();
            host.close();
            awaitSchedulerLeasesReleased(scheduler);
            scheduler.shutdown();
        }
    }

    @Test
    void loadTimeoutNeverReportsSuccess() throws Exception {
        writePlugin("00-observer.jar", OBSERVER_ID, "probe.observer.ObserverPlugin",
            observerSource(), null, OBSERVER_PERMISSIONS);
        writePlugin("10-slow-init.jar", "dev.example.lifecycle-slow-init",
            "probe.target.SlowInitPlugin", slowInitSource(), afterObserver(), "[]");

        final RuntimeScheduler scheduler = scheduler();
        final HostSession host = new HostSession(Optional::empty);
        installProbeState();
        try (PreviewLog log = new PreviewLog(temporary.resolve("logs/turboism.log"))) {
            final LocalPluginRuntime runtime = new LocalPluginRuntime(
                temporary, scheduler, host.adapterAccess(), log,
                (pluginId, phase) -> { }, shortLoadPolicy()
            );
            try {
                final RuntimeEventBroker broker = brokerOf(runtime);
                broker.subscribe(
                    "test.lifecycle-recorder",
                    PluginLifecycleEvent.class,
                    event -> rawEvents.add(row(event))
                );
                runtime.loadAll();
                awaitRows(rawEvents, "dev.example.lifecycle-slow-init:1:LOAD:TIMED_OUT");

                release.countDown();
                awaitDrain(rawEvents, broker);
                awaitPluginDrain(pluginEvents);

                assertExactlyOnce(
                    rawEvents, "dev.example.lifecycle-slow-init:1:LOAD:TIMED_OUT"
                );
                assertFalse(
                    snapshot(rawEvents).stream().anyMatch(row ->
                        row.startsWith("dev.example.lifecycle-slow-init:")
                            && row.endsWith(":LOAD:SUCCEEDED")),
                    "a fenced load must never report success after its timeout verdict"
                );
            } finally {
                release.countDown();
                // The fenced worker still owns the plugin scope (and its task scheduler
                // lease) until the retention watcher reclaims it; wait for the drain so
                // the scheduler can shut down deterministically.
                awaitTrue(
                    () -> shutdownOf(runtime).retainedGenerationCount() == 0,
                    "the fenced generation must drain once the worker exits"
                );
                runtime.close();
            }
        } finally {
            clearProbeState();
            host.close();
            awaitSchedulerLeasesReleased(scheduler);
            scheduler.shutdown();
        }
    }

    private static final String OBSERVER_PERMISSIONS =
        "[{\"id\":\"turboism.event.subscribe\",\"scope\":\"application\","
            + "\"reason\":\"Observe lifecycle verdicts.\"},"
            + "{\"id\":\"turboism.plugin.lifecycle.observe\",\"scope\":\"application\","
            + "\"reason\":\"Observe lifecycle verdicts.\"}]";

    private static String afterObserver() {
        return """
            {"id":"%s","version":"[0.1.0,0.2.0)","type":"required","ordering":"after"}
            """.formatted(OBSERVER_ID);
    }

    private void installProbeState() {
        rawEvents = new CopyOnWriteArrayList<>();
        pluginEvents = new CopyOnWriteArrayList<>();
        markerSequence = new AtomicLong();
        lastMarkerRow = null;
        release = new CountDownLatch(1);
        scopeCount = new AtomicInteger();
        shutdownCount = new AtomicInteger();
        System.getProperties().put(PLUGIN_QUEUE_PROPERTY, pluginEvents);
        System.getProperties().put(RELEASE_PROPERTY, release);
        System.getProperties().put(SCOPE_COUNT_PROPERTY, scopeCount);
        System.getProperties().put(SHUTDOWN_COUNT_PROPERTY, shutdownCount);
    }

    private void clearProbeState() {
        System.getProperties().remove(PLUGIN_QUEUE_PROPERTY);
        System.getProperties().remove(RELEASE_PROPERTY);
        System.getProperties().remove(SCOPE_COUNT_PROPERTY);
        System.getProperties().remove(SHUTDOWN_COUNT_PROPERTY);
    }

    private static String row(final PluginLifecycleEvent event) {
        return event.pluginId() + ":" + event.generation()
            + ":" + event.phase() + ":" + event.outcome();
    }

    private static List<String> snapshot(final List<String> rows) {
        return List.copyOf(rows);
    }

    private static void assertExactlyOnce(
        final List<String> rows,
        final String expected
    ) {
        final long count = snapshot(rows).stream().filter(expected::equals).count();
        assertEquals(1, count, "expected exactly once: " + expected + " in " + rows);
    }

    private void awaitRows(
        final List<String> rows,
        final String... expected
    ) throws Exception {
        final List<String> wanted = List.of(expected);
        awaitTrue(() -> rows.containsAll(wanted), "missing rows " + wanted + " in " + rows);
    }

    /**
     * A marker event lands behind everything already enqueued for the same owner, so its
     * arrival proves the raw recorder's mailbox drained. Each call publishes a fresh marker
     * id so earlier drains cannot satisfy a later wait.
     */
    private void awaitDrain(
        final List<String> rows,
        final RuntimeEventBroker broker
    ) throws Exception {
        final String markerId = MARKER_ID + "." + markerSequence.incrementAndGet();
        broker.publishRuntime(new PluginLifecycleEvent(
            markerId,
            PluginLifecycleEvent.NO_ADMITTED_GENERATION,
            PluginLifecycleEvent.Phase.LOAD,
            PluginLifecycleEvent.Outcome.SUCCEEDED
        ));
        final String markerRow = markerId + ":" + PluginLifecycleEvent.NO_ADMITTED_GENERATION
            + ":" + PluginLifecycleEvent.Phase.LOAD + ":"
            + PluginLifecycleEvent.Outcome.SUCCEEDED;
        lastMarkerRow = markerRow;
        awaitTrue(() -> rows.contains(markerRow), "drain marker missing; rows hold " + rows);
    }

    /** The plugin observer records through the same marker; drain it the same way. */
    private void awaitPluginDrain(final List<String> rows) throws Exception {
        final String markerRow = lastMarkerRow;
        assertTrue(markerRow != null, "awaitPluginDrain requires a preceding awaitDrain");
        awaitTrue(() -> rows.contains(markerRow), "plugin drain marker missing; rows " + rows);
    }

    /**
     * Plugin task scheduler leases are released when a plugin scope closes; a retained or
     * in-flight cleanup may still hold one briefly after {@code runtime.close()} returns.
     */
    private static void awaitSchedulerLeasesReleased(
        final RuntimeScheduler scheduler
    ) throws Exception {
        final Field leases = RuntimeScheduler.class.getDeclaredField("pluginTaskSchedulerLeases");
        leases.setAccessible(true);
        try {
            awaitTrue(
                () -> leases.getInt(scheduler) == 0,
                "plugin task scheduler leases never released"
            );
        } catch (AssertionError ignored) {
            // scheduler.shutdown() below reports the same condition with the real error.
        }
    }

    private static void awaitTrue(
        final ConditionWithException condition,
        final String description
    ) throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(description);
            }
            Thread.sleep(10);
        }
    }

    private interface ConditionWithException {
        boolean getAsBoolean() throws Exception;
    }

    /**
     * Removes the target from live visibility and closes it through the existing
     * dependency-rollback seam while the observer stays active.
     */
    private void unloadOne(
        final LocalPluginRuntime runtime,
        final String pluginId
    ) throws Exception {
        final LocalPluginRuntime.LoadedPlugin target = loadedOf(runtime).stream()
            .filter(plugin -> plugin.runtime().id().equals(pluginId))
            .findFirst()
            .orElseThrow(() -> new AssertionError("plugin not loaded: " + pluginId));
        loadedOf(runtime).remove(target);
        shutdownOf(runtime).unloadOne(target);
    }

    @SuppressWarnings("unchecked")
    private static List<LocalPluginRuntime.LoadedPlugin> loadedOf(
        final LocalPluginRuntime runtime
    ) throws Exception {
        final Field field = LocalPluginRuntime.class.getDeclaredField("loaded");
        field.setAccessible(true);
        return (List<LocalPluginRuntime.LoadedPlugin>) field.get(runtime);
    }

    private static PreviewPluginShutdown shutdownOf(
        final LocalPluginRuntime runtime
    ) throws Exception {
        final Field field = LocalPluginRuntime.class.getDeclaredField("shutdown");
        field.setAccessible(true);
        return (PreviewPluginShutdown) field.get(runtime);
    }

    private static RuntimeEventBroker brokerOf(
        final LocalPluginRuntime runtime
    ) throws Exception {
        final Field field = LocalPluginRuntime.class.getDeclaredField("contextFactory");
        field.setAccessible(true);
        return ((PreviewPluginContextFactory) field.get(runtime)).eventBroker();
    }

    private static PluginLifecyclePolicy shortClosePolicy() {
        return new PluginLifecyclePolicy(
            2, 16,
            Duration.ofSeconds(10),
            Duration.ofSeconds(2),
            Duration.ofMillis(200),
            Duration.ofMillis(50),
            Duration.ofMillis(30)
        );
    }

    private static PluginLifecyclePolicy shortLoadPolicy() {
        return new PluginLifecyclePolicy(
            2, 16,
            Duration.ofMillis(1500),
            Duration.ofSeconds(2),
            Duration.ofSeconds(5),
            Duration.ofMillis(50),
            Duration.ofMillis(30)
        );
    }

    private static String observerSource() {
        return """
            package probe.observer;

            import dev.turboism.sdk.event.SubscribeEvent;
            import dev.turboism.sdk.event.TurboismEvent;
            import dev.turboism.sdk.plugin.TurboismPlugin;
            import dev.turboism.sdk.runtime.PluginLifecycleEvent;
            import java.util.List;

            public final class ObserverPlugin implements TurboismPlugin {
                @SubscribeEvent
                @SuppressWarnings("unchecked")
                public void onLifecycle(TurboismEvent event) {
                    if (!(event instanceof PluginLifecycleEvent lifecycle)) {
                        return;
                    }
                    final Object queue = System.getProperties().get("%s");
                    if (queue instanceof List) {
                        ((List<String>) queue).add(
                            lifecycle.pluginId() + ":" + lifecycle.generation()
                                + ":" + lifecycle.phase() + ":" + lifecycle.outcome()
                        );
                    }
                }
            }
            """.formatted(PLUGIN_QUEUE_PROPERTY);
    }

    private static String successSource() {
        return """
            package probe.target;

            import dev.turboism.sdk.plugin.TurboismPlugin;

            public final class SuccessPlugin implements TurboismPlugin {
            }
            """;
    }

    private static String failEnableSource() {
        return """
            package probe.target;

            import dev.turboism.sdk.plugin.TurboismPlugin;

            public final class FailEnablePlugin implements TurboismPlugin {
                @Override public void enable() {
                    throw new IllegalStateException("enable failed on purpose");
                }
            }
            """;
    }

    private static String failCtorSource() {
        return """
            package probe.target;

            import dev.turboism.sdk.plugin.TurboismPlugin;

            public final class FailCtorPlugin implements TurboismPlugin {
                public FailCtorPlugin() {
                    throw new IllegalStateException("ctor failed on purpose");
                }
            }
            """;
    }

    private static String shutdownFailSource() {
        return """
            package probe.target;

            import dev.turboism.sdk.plugin.TurboismPlugin;

            public final class ShutdownFailPlugin implements TurboismPlugin {
                @Override public void shutdown() {
                    ProbeSupport.count("%s");
                    throw new IllegalStateException("shutdown failed on purpose");
                }
            }
            """.formatted(SHUTDOWN_COUNT_PROPERTY);
    }

    private static String scopeCountSource() {
        return """
            package probe.target;

            import dev.turboism.sdk.plugin.PluginContext;
            import dev.turboism.sdk.plugin.TurboismPlugin;

            public final class ScopeCountPlugin implements TurboismPlugin {
                @Override public void init(PluginContext context) {
                    context.disposableScope().register(() -> ProbeSupport.count("%s"));
                }
            }
            """.formatted(SCOPE_COUNT_PROPERTY);
    }

    private static String scopeFailSource() {
        return """
            package probe.target;

            import dev.turboism.sdk.plugin.PluginContext;
            import dev.turboism.sdk.plugin.TurboismPlugin;

            public final class ScopeFailPlugin implements TurboismPlugin {
                @Override public void init(PluginContext context) {
                    context.disposableScope().register(() -> {
                        ProbeSupport.count("%s");
                        throw new IllegalStateException("scope closer failed on purpose");
                    });
                }
            }
            """.formatted(SCOPE_COUNT_PROPERTY);
    }

    private static String blockSource() {
        return """
            package probe.target;

            import dev.turboism.sdk.plugin.TurboismPlugin;

            public final class BlockPlugin implements TurboismPlugin {
                @Override public void shutdown() {
                    ProbeSupport.count("%s");
                    ProbeSupport.await("%s");
                }
            }
            """.formatted(SHUTDOWN_COUNT_PROPERTY, RELEASE_PROPERTY);
    }

    private static String blockFailSource() {
        return """
            package probe.target;

            import dev.turboism.sdk.plugin.TurboismPlugin;

            public final class BlockFailPlugin implements TurboismPlugin {
                @Override public void shutdown() {
                    ProbeSupport.count("%s");
                    ProbeSupport.await("%s");
                    throw new IllegalStateException("shutdown failed on purpose");
                }
            }
            """.formatted(SHUTDOWN_COUNT_PROPERTY, RELEASE_PROPERTY);
    }

    private static String blockScopeFailSource() {
        return """
            package probe.target;

            import dev.turboism.sdk.plugin.PluginContext;
            import dev.turboism.sdk.plugin.TurboismPlugin;

            public final class BlockScopeFailPlugin implements TurboismPlugin {
                @Override public void init(PluginContext context) {
                    context.disposableScope().register(() -> {
                        ProbeSupport.count("%s");
                        throw new IllegalStateException("scope closer failed on purpose");
                    });
                }
                @Override public void shutdown() {
                    ProbeSupport.count("%s");
                    ProbeSupport.await("%s");
                }
            }
            """.formatted(
                SCOPE_COUNT_PROPERTY, SHUTDOWN_COUNT_PROPERTY, RELEASE_PROPERTY
            );
    }

    private static String slowInitSource() {
        return """
            package probe.target;

            import dev.turboism.sdk.plugin.PluginContext;
            import dev.turboism.sdk.plugin.TurboismPlugin;

            public final class SlowInitPlugin implements TurboismPlugin {
                @Override public void init(PluginContext context) {
                    ProbeSupport.await("%s");
                }
            }
            """.formatted(RELEASE_PROPERTY);
    }

    private static final String PROBE_SUPPORT = """
        package probe.target;

        import java.util.concurrent.CountDownLatch;
        import java.util.concurrent.atomic.AtomicInteger;

        final class ProbeSupport {
            static void count(String property) {
                final Object counter = System.getProperties().get(property);
                if (counter instanceof AtomicInteger value) {
                    value.incrementAndGet();
                }
            }
            static void await(String property) {
                final Object latch = System.getProperties().get(property);
                if (!(latch instanceof CountDownLatch release)) {
                    return;
                }
                // A plugin that ignores the lane's advisory interrupt: keep waiting without
                // restoring the flag, so the latch is still observed once released.
                while (true) {
                    try {
                        release.await();
                        return;
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        }
        """;

    private void writePlugin(
        final String jarName,
        final String id,
        final String className,
        final String entrypointSource,
        final String dependency,
        final String permissions
    ) throws Exception {
        final String baseName = id.substring(id.lastIndexOf('-') + 1);
        final Path sourceRoot = temporary.resolve("source-" + baseName);
        final Path classes = temporary.resolve("classes-" + baseName);
        final Path source = sourceRoot.resolve(className.replace('.', '/') + ".java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, entrypointSource, StandardCharsets.UTF_8);
        final List<String> sources = new ArrayList<>(List.of(source.toString()));
        if (entrypointSource.contains("ProbeSupport")) {
            final Path support = sourceRoot.resolve("probe/target/ProbeSupport.java");
            Files.createDirectories(support.getParent());
            Files.writeString(support, PROBE_SUPPORT, StandardCharsets.UTF_8);
            sources.add(support.toString());
        }
        Files.createDirectories(classes);
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        final List<String> arguments = new ArrayList<>(List.of(
            "-classpath", System.getProperty("java.class.path"),
            "-d", classes.toString()
        ));
        arguments.addAll(sources);
        final int result = compiler.run(
            null, null, null,
            arguments.toArray(new String[0])
        );
        if (result != 0) {
            throw new IllegalStateException("fixture compilation failed for " + id);
        }

        final Path jar = temporary.resolve("plugins").resolve(jarName);
        Files.createDirectories(jar.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            try (var paths = Files.walk(classes)) {
                for (Path path : paths.filter(Files::isRegularFile)
                    .sorted(Comparator.naturalOrder()).toList()) {
                    add(output, classes.relativize(path).toString().replace('\\', '/'),
                        Files.readAllBytes(path));
                }
            }
            add(output, "META-INF/turboism/plugin.json",
                descriptor(id, className, dependency, permissions).getBytes(StandardCharsets.UTF_8));
            add(output, "META-INF/turboism/i18n/messages.properties", new byte[0]);
        }
    }

    private static String descriptor(
        final String id,
        final String entrypoint,
        final String dependency,
        final String permissions
    ) {
        final String dependencies = dependency == null ? "[]" : "[" + dependency + "]";
        return """
            {"format":"turboism.plugin.meta","schemaVersion":2,
            "id":"%s","name":"%s","version":"0.1.0",
            "description":"test","entrypoints":["%s"],
            "turboismApi":"[0.1.0,0.2.0)","authors":[{"name":"Tests"}],
            "license":"Test","website":"https://turboism.dev",
            "resources":[],"i18n":{"baseName":"META-INF/turboism/i18n/messages","locales":[]},
            "dependencies":%s,"permissions":%s,"capabilities":[],
            "environment":{"requiresCubism":false,"ui":"none"}}
            """.formatted(id, id, entrypoint, dependencies, permissions);
    }

    private static RuntimeScheduler scheduler() {
        return new RuntimeScheduler(
            new DefaultWorkBudgetPolicy(),
            new PluginWorkExecutorRegistry(1, 16, ignored -> { }, Clock.systemUTC()),
            SidecarDispatcher.noop(),
            ignored -> { }
        );
    }

    private static void add(
        final JarOutputStream output,
        final String name,
        final byte[] content
    ) throws Exception {
        output.putNextEntry(new JarEntry(name));
        output.write(content);
        output.closeEntry();
    }
}
