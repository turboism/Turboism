package dev.turboism.worktime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.adapter.cubism.HostSnapshotSource;
import dev.turboism.core.event.RuntimeEventBroker;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.sdk.cubism.event.CubismActivityChangedEvent;
import dev.turboism.sdk.cubism.worktime.ProjectWorkTime;
import dev.turboism.sdk.cubism.worktime.ProjectWorkTimeSnapshot;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeProjectWorkTimeServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-09-12T00:00:00Z"), ZoneOffset.UTC);

    private RuntimeScheduler scheduler;
    private RuntimeProjectWorkTimeService service;

    @AfterEach
    void teardown() {
        if (service != null) {
            service.close();
        }
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    @Test
    void foregroundTimeFollowsProjectTabsAndPausesWhenBackgrounded(@TempDir final Path home) {
        scheduler();
        final MutableSource source = new MutableSource();
        final AtomicLong nanos = new AtomicLong();
        source.foreground = true;
        source.project = project("session-a", "alpha.cmo3");
        service = newService(source, home, nanos, Duration.ofSeconds(1));

        service.tick(); // baseline
        nanos.addAndGet(Duration.ofSeconds(1).toNanos());
        service.tick(); // alpha gets the first elapsed second

        ProjectWorkTimeSnapshot snapshot = service.snapshot();
        assertEquals(Duration.ofSeconds(1), snapshot.sessionForegroundTime());
        assertEquals(
                Duration.ofSeconds(1), snapshot.activeProject().orElseThrow().sessionTime());
        assertEquals(
                Duration.ofSeconds(1), snapshot.activeProject().orElseThrow().totalTime());

        source.project = project("session-b", "beta.cmo3");
        nanos.addAndGet(Duration.ofSeconds(1).toNanos());
        service.tick(); // closes alpha, starts beta

        snapshot = service.snapshot();
        assertEquals(Duration.ofSeconds(2), project(snapshot, "alpha.cmo3").totalTime());
        assertEquals(Duration.ZERO, project(snapshot, "beta.cmo3").sessionTime());

        nanos.addAndGet(Duration.ofSeconds(1).toNanos());
        service.tick();
        snapshot = service.snapshot();
        assertEquals(Duration.ofSeconds(1), project(snapshot, "beta.cmo3").sessionTime());
        assertEquals(Duration.ofSeconds(3), snapshot.sessionForegroundTime());

        source.foreground = false;
        nanos.addAndGet(Duration.ofSeconds(1).toNanos());
        service.tick(); // completes the foreground segment, then observes background
        snapshot = service.snapshot();
        assertFalse(snapshot.editorForeground());
        assertEquals(Duration.ofSeconds(2), project(snapshot, "beta.cmo3").totalTime());
        assertEquals(Duration.ofSeconds(4), snapshot.sessionForegroundTime());

        nanos.addAndGet(Duration.ofSeconds(5).toNanos());
        service.tick(); // background time is never counted
        assertEquals(Duration.ofSeconds(4), service.snapshot().sessionForegroundTime());
    }

    @Test
    void totalsSurviveServiceReconstruction(@TempDir final Path home) {
        scheduler();
        final MutableSource source = new MutableSource();
        final AtomicLong nanos = new AtomicLong();
        source.foreground = true;
        source.project = project("session-a", "alpha.cmo3");
        service = newService(source, home, nanos, Duration.ofMillis(10));

        service.tick();
        nanos.addAndGet(Duration.ofSeconds(2).toNanos());
        service.tick();
        service.close();
        service = null;

        final MutableSource restarted = new MutableSource();
        restarted.foreground = false;
        restarted.project = Optional.empty();
        final RuntimeProjectWorkTimeService reloaded =
                newService(restarted, home, new AtomicLong(), Duration.ofMillis(10));
        try {
            reloaded.tick();
            final ProjectWorkTime alpha = project(reloaded.snapshot(), "alpha.cmo3");
            assertEquals(Duration.ofSeconds(2), alpha.totalTime());
            assertEquals(Duration.ZERO, alpha.sessionTime());
        } finally {
            reloaded.close();
        }
    }

    @Test
    void foregroundAndProjectTransitionsPublishActivityEvents(@TempDir final Path home) throws Exception {
        scheduler();
        final MutableSource source = new MutableSource();
        final AtomicLong nanos = new AtomicLong();
        source.foreground = true;
        source.project = project("session-a", "alpha.cmo3");
        service = newService(source, home, nanos, Duration.ofSeconds(1));

        final List<CubismActivityChangedEvent> events = new ArrayList<>();
        final CountDownLatch delivered = new CountDownLatch(1);
        final RuntimeEventBroker.Owner owner = serviceBroker().admit("plugin.work-time");
        serviceBroker().subscribe(owner.key(), CubismActivityChangedEvent.class, event -> {
            events.add(event);
            delivered.countDown();
        });
        owner.activate();

        service.tick(); // silent baseline
        source.project = project("session-b", "beta.cmo3");
        nanos.addAndGet(Duration.ofSeconds(1).toNanos());
        service.tick();

        assertTrue(delivered.await(5, TimeUnit.SECONDS));
        assertEquals(
                "alpha.cmo3",
                events.get(0).previous().activeProject().orElseThrow().name());
        assertEquals(
                "beta.cmo3",
                events.get(0).current().activeProject().orElseThrow().name());
    }

    private void scheduler() {
        scheduler = new RuntimeScheduler(
                new DefaultWorkBudgetPolicy(),
                new PluginWorkExecutorRegistry(1, 2, ignored -> {}, FIXED_CLOCK),
                ignored -> {});
    }

    private RuntimeEventBroker serviceBroker() {
        // The service was constructed with a broker; the test keeps the same instance.
        return broker;
    }

    private RuntimeEventBroker broker;

    private RuntimeProjectWorkTimeService newService(
            final MutableSource source, final Path home, final AtomicLong nanos, final Duration interval) {
        broker = new RuntimeEventBroker(scheduler);
        return new RuntimeProjectWorkTimeService(
                source,
                broker,
                scheduler,
                null,
                home.resolve("project-work-time.json"),
                FIXED_CLOCK,
                interval,
                nanos::get);
    }

    private static ProjectWorkTime project(final ProjectWorkTimeSnapshot snapshot, final String name) {
        return snapshot.projects().stream()
                .filter(project -> project.projectName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static Optional<HostSnapshotSource.HostProject> project(final String id, final String name) {
        return Optional.of(new HostSnapshotSource.HostProject(id, name, Optional.empty(), List.of(), List.of()));
    }

    private static final class MutableSource implements HostSnapshotSource {
        private volatile boolean foreground;
        private volatile Optional<HostSnapshotSource.HostProject> project = Optional.empty();
        private final AtomicBoolean read = new AtomicBoolean();

        @Override
        public Optional<String> activeProjectId() {
            return project.map(HostSnapshotSource.HostProject::projectId);
        }

        @Override
        public Optional<HostSnapshotSource.HostProject> activeProject() {
            return project;
        }

        @Override
        public Optional<HostDocument> activeDocument() {
            return Optional.empty();
        }

        @Override
        public Optional<HostModel> activeModel() {
            return Optional.empty();
        }

        @Override
        public HostSelection selection() {
            return HostSelection.empty();
        }

        @Override
        public boolean isForeground() {
            return foreground;
        }

        @Override
        public boolean isHostPresent() {
            return project.isPresent();
        }

        @Override
        public long invalidationToken() {
            read.set(true);
            return 0L;
        }
    }
}
