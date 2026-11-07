package dev.turboism.worktime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.turboism.adapter.cubism.HostSnapshotSource;
import dev.turboism.core.event.RuntimeEventBroker;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.sdk.cubism.event.CubismActivityChangedEvent;
import dev.turboism.sdk.cubism.worktime.ProjectWorkTime;
import dev.turboism.sdk.cubism.worktime.ProjectWorkTimeSnapshot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
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
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final String STATE_FILE_NAME = "project-work-time.json";
    private static final String STATE_FORMAT = "turboism.project-work-time/v1";

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

    @Test
    void corruptStateIsPreservedBeforeTheUpdateReplacesIt(@TempDir final Path home) throws Exception {
        final byte[] corrupt = "{\n  \"format\" : \"turboism.project-work-time/v1\",\n  \"projects\" :\n"
                .getBytes(StandardCharsets.UTF_8);
        Files.write(stateFile(home), corrupt);

        recordOneForegroundSecond(home);

        assertArrayEquals(
                corrupt,
                Files.readAllBytes(corruptImage(home)),
                "the bytes the corrupt read discarded must survive the overwrite that replaces them");
        assertEquals(STATE_FORMAT, persistedRoot(home).path("format").asText());
        assertEquals(
                Duration.ofSeconds(1).toNanos(),
                persistedProject(home, "alpha.cmo3").path("totalNanos").asLong(),
                "the new document must carry the current session instead of the discarded bytes");
    }

    @Test
    void aFutureStateIsKeptBeforeTheDowngradeOverwritesIt(@TempDir final Path home) throws Exception {
        final byte[] future = """
                {
                  "format": "turboism.project-work-time/v2",
                  "projects": {
                    "alpha.cmo3|": {
                      "name": "alpha.cmo3",
                      "totalNanos": 99000000000,
                      "lastActiveAt": "2026-01-01T00:00:00Z"
                    }
                  }
                }
                """.getBytes(StandardCharsets.UTF_8);
        Files.write(stateFile(home), future);

        recordOneForegroundSecond(home);

        assertArrayEquals(
                future,
                Files.readAllBytes(corruptImage(home)),
                "a document from a newer build must be recoverable after the downgrade overwrites it");
        assertEquals(STATE_FORMAT, persistedRoot(home).path("format").asText());
        assertEquals(
                Duration.ofSeconds(1).toNanos(),
                persistedProject(home, "alpha.cmo3").path("totalNanos").asLong(),
                "a future schema must be refused, never migrated into the new document");
    }

    @Test
    void aStateWithAnUnknownRootFieldIsPreservedBeforeTheUpdateReplacesIt(@TempDir final Path home) throws Exception {
        final byte[] unknownField = """
                {
                  "format": "turboism.project-work-time/v1",
                  "projects": {},
                  "workspaceId": "unreleased"
                }
                """.getBytes(StandardCharsets.UTF_8);
        Files.write(stateFile(home), unknownField);

        recordOneForegroundSecond(home);

        assertArrayEquals(
                unknownField,
                Files.readAllBytes(corruptImage(home)),
                "an older reader must keep a root field it cannot interpret");
        assertEquals(
                Duration.ofSeconds(1).toNanos(),
                persistedProject(home, "alpha.cmo3").path("totalNanos").asLong());
    }

    @Test
    void duplicateKeysAndTrailingTokensAreTreatedAsUnreadable(@TempDir final Path home) throws Exception {
        final Path duplicateHome = home.resolve("duplicate");
        final Path trailingHome = home.resolve("trailing");
        Files.createDirectories(duplicateHome);
        Files.createDirectories(trailingHome);
        final byte[] duplicateKeys = """
                {
                  "format": "turboism.project-work-time/v1",
                  "format": "turboism.project-work-time/v1",
                  "projects": {}
                }
                """.getBytes(StandardCharsets.UTF_8);
        final byte[] trailingTokens = """
                {"format":"turboism.project-work-time/v1","projects":{}} {"stale":true}
                """.getBytes(StandardCharsets.UTF_8);
        Files.write(stateFile(duplicateHome), duplicateKeys);
        Files.write(stateFile(trailingHome), trailingTokens);

        recordOneForegroundSecond(duplicateHome);
        recordOneForegroundSecond(trailingHome);

        assertArrayEquals(
                duplicateKeys,
                Files.readAllBytes(corruptImage(duplicateHome)),
                "duplicate keys are ambiguous bytes and must be preserved, never resolved last-wins");
        assertArrayEquals(
                trailingTokens,
                Files.readAllBytes(corruptImage(trailingHome)),
                "bytes after the first JSON value must be preserved, never silently ignored");
    }

    @Test
    void aReadableStateIsOverwrittenWithoutARecoveryImage(@TempDir final Path home) throws Exception {
        Files.writeString(stateFile(home), """
                {
                  "format": "turboism.project-work-time/v1",
                  "projects": {
                    "alpha.cmo3|": {
                      "name": "alpha.cmo3",
                      "totalNanos": 5000000000,
                      "lastActiveAt": "2026-01-01T00:00:00Z"
                    }
                  }
                }
                """);

        recordOneForegroundSecond(home);

        assertFalse(
                Files.exists(corruptImage(home)),
                "a readable document must be overwritten in place without accumulating backups");
        assertEquals(
                Duration.ofSeconds(6).toNanos(),
                persistedProject(home, "alpha.cmo3").path("totalNanos").asLong(),
                "the loaded total must be updated by the current session");
    }

    @Test
    void aSymlinkedStateIsIgnoredAtLoadWithoutFailing(@TempDir final Path home) throws Exception {
        final byte[] linked = """
                {
                  "format": "turboism.project-work-time/v1",
                  "projects": {
                    "ghost.cmo3|": {
                      "name": "ghost.cmo3",
                      "totalNanos": 42000000000,
                      "lastActiveAt": "2026-01-01T00:00:00Z"
                    }
                  }
                }
                """.getBytes(StandardCharsets.UTF_8);
        final Path outside = home.resolve("outside").resolve(STATE_FILE_NAME);
        Files.createDirectories(outside.getParent());
        Files.write(outside, linked);
        Files.createSymbolicLink(stateFile(home), outside);

        scheduler();
        service = newService(new MutableSource(), home, new AtomicLong(), Duration.ofSeconds(1));
        service.tick();

        assertTrue(
                service.snapshot().projects().isEmpty(), "a symlinked state file must never be followed during load");
        assertArrayEquals(linked, Files.readAllBytes(outside), "the link target must stay untouched");
    }

    @Test
    void anOversizedStateIsIgnoredAtLoadWithoutBeingRead(@TempDir final Path home) throws Exception {
        final byte[] document = """
                {
                  "format": "turboism.project-work-time/v1",
                  "projects": {
                    "ghost.cmo3|": {
                      "name": "ghost.cmo3",
                      "totalNanos": 42000000000,
                      "lastActiveAt": "2026-01-01T00:00:00Z"
                    }
                  }
                }
                """.getBytes(StandardCharsets.UTF_8);
        final byte[] oversized = new byte[(int) RuntimeProjectWorkTimeService.MAX_PERSISTENCE_BYTES + 1];
        Arrays.fill(oversized, (byte) ' ');
        System.arraycopy(document, 0, oversized, 0, document.length);
        Files.write(stateFile(home), oversized);

        scheduler();
        service = newService(new MutableSource(), home, new AtomicLong(), Duration.ofSeconds(1));
        service.tick();

        assertTrue(
                service.snapshot().projects().isEmpty(),
                "a document above the read limit must be ignored instead of loaded");
        assertArrayEquals(
                oversized, Files.readAllBytes(stateFile(home)), "a refused load must not rewrite the document");
    }

    /**
     * Drives one foreground second for {@code alpha.cmo3} through a service that persists on close,
     * so a test can assert what the save did to a document that was already on disk.
     */
    private void recordOneForegroundSecond(final Path home) {
        scheduler();
        final MutableSource source = new MutableSource();
        final AtomicLong nanos = new AtomicLong();
        source.foreground = true;
        source.project = project("session-a", "alpha.cmo3");
        service = newService(source, home, nanos, Duration.ofSeconds(1));
        service.tick();
        nanos.addAndGet(Duration.ofSeconds(1).toNanos());
        service.tick();
        service.close();
        service = null;
    }

    private static Path stateFile(final Path home) {
        return home.resolve(STATE_FILE_NAME);
    }

    private static Path corruptImage(final Path home) {
        return stateFile(home).resolveSibling(STATE_FILE_NAME + RuntimeProjectWorkTimeService.CORRUPT_SUFFIX);
    }

    private static JsonNode persistedRoot(final Path home) throws IOException {
        return JSON.readTree(Files.readAllBytes(stateFile(home)));
    }

    private static JsonNode persistedProject(final Path home, final String name) throws IOException {
        final var entries = persistedRoot(home).path("projects").fields();
        while (entries.hasNext()) {
            final var entry = entries.next();
            if (name.equals(entry.getValue().path("name").asText())) {
                return entry.getValue();
            }
        }
        throw new AssertionError("no persisted project named " + name);
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
                source, broker, scheduler, null, home.resolve(STATE_FILE_NAME), FIXED_CLOCK, interval, nanos::get);
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
