package dev.turboism.worktime;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.turboism.adapter.cubism.HostSnapshotSource;
import dev.turboism.adapter.cubism.HostSnapshots;
import dev.turboism.core.event.RuntimeEventBroker;
import dev.turboism.core.io.DurableFile;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.RuntimeTimerHandle;
import dev.turboism.core.runtime.RuntimeTimerSubmission;
import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.hostread.SharedAsyncHostReadLane;
import dev.turboism.runtime.log.RuntimeDiagnostics;
import dev.turboism.sdk.cubism.CubismActivitySnapshot;
import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.ProjectContentSnapshot;
import dev.turboism.sdk.cubism.ProjectSnapshot;
import dev.turboism.sdk.cubism.event.CubismActivityChangedEvent;
import dev.turboism.sdk.cubism.worktime.ProjectWorkTime;
import dev.turboism.sdk.cubism.worktime.ProjectWorkTimeService;
import dev.turboism.sdk.cubism.worktime.ProjectWorkTimeSnapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Runtime-owned foreground-time tracker shared by every plugin context.
 *
 * <p>The tracker samples the process-level foreground state and the active project identity on
 * the runtime scheduler. Time is attributed to the state observed on the previous tick, so a
 * project switch or a foreground loss closes the previous segment at the next sample. Elapsed
 * time is capped at three tick intervals so a suspended machine cannot inflate a single sample.</p>
 *
 * <p>Only a lightweight project identity is read every tick. A full project snapshot is
 * materialized only when that identity changes, which also supplies the payload for
 * {@link CubismActivityChangedEvent} and the display name used by the work-time records.</p>
 */
public final class RuntimeProjectWorkTimeService implements ProjectWorkTimeService, AutoCloseable {

    public static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(1);
    public static final Duration PERSIST_INTERVAL = Duration.ofSeconds(30);

    /** Upper bound of the persisted work-time document; a larger file is never read. */
    static final long MAX_PERSISTENCE_BYTES = 1024L * 1024L;

    /**
     * Suffix of the single bounded recovery image kept when the stored document can no longer be
     * read as the current {@link #FORMAT}. Rewritten rather than appended, so retention is one.
     */
    static final String CORRUPT_SUFFIX = ".corrupt.bak";

    private static final String FORMAT = "turboism.project-work-time/v1";
    private static final String DIAGNOSTIC_COMPONENT = "project-work-time";
    private static final long MAX_ELAPSED_NANOS = DEFAULT_INTERVAL.toNanos() * 3L;

    /**
     * Work-time state is a closed document for this build: an older reader must not act on a root
     * field it does not understand, so an unknown field makes the whole document unreadable.
     */
    private static final Set<String> ALLOWED_ROOT_FIELDS = Set.of("format", "projects");

    private final HostSnapshotSource source;
    private final RuntimeEventBroker eventBroker;
    private final RuntimeScheduler scheduler;
    private final SharedAsyncHostReadLane hostReads;
    private final Path persistenceFile;
    private final Clock clock;
    private final LongSupplier nanoClock;
    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    private final Object lifecycle = new Object();
    private final Duration interval;
    private final Map<String, MutableProjectWorkTime> projects = new HashMap<>();

    private ProjectWorkTimeSnapshot current;
    private CubismActivitySnapshot activity;
    private RuntimeTimerHandle pendingTimer;
    private boolean started;
    private boolean closed;
    private boolean observedOnce;
    private String observedActivityProjectKey;
    private boolean foreground;
    private String activeProjectSessionId;
    private String activeProjectKey;
    private ProjectSnapshot activeProjectSnapshot;
    private long sessionForegroundNanos;
    private long lastTickNanos = -1L;
    private long ticksSincePersist;
    private boolean dirty;

    public RuntimeProjectWorkTimeService(
            final HostSnapshotSource source,
            final RuntimeEventBroker eventBroker,
            final RuntimeScheduler scheduler,
            final Path persistenceFile,
            final Clock clock) {
        this(source, eventBroker, scheduler, null, persistenceFile, clock, DEFAULT_INTERVAL, System::nanoTime);
    }

    /** Production composition: host reads are dispatched onto the shared serialized read lane. */
    public RuntimeProjectWorkTimeService(
            final HostSnapshotSource source,
            final RuntimeEventBroker eventBroker,
            final RuntimeScheduler scheduler,
            final SharedAsyncHostReadLane hostReads,
            final Path persistenceFile,
            final Clock clock) {
        this(source, eventBroker, scheduler, hostReads, persistenceFile, clock, DEFAULT_INTERVAL, System::nanoTime);
    }

    RuntimeProjectWorkTimeService(
            final HostSnapshotSource source,
            final RuntimeEventBroker eventBroker,
            final RuntimeScheduler scheduler,
            final Path persistenceFile,
            final Clock clock,
            final Duration interval) {
        this(source, eventBroker, scheduler, null, persistenceFile, clock, interval, System::nanoTime);
    }

    RuntimeProjectWorkTimeService(
            final HostSnapshotSource source,
            final RuntimeEventBroker eventBroker,
            final RuntimeScheduler scheduler,
            final SharedAsyncHostReadLane hostReads,
            final Path persistenceFile,
            final Clock clock,
            final Duration interval,
            final LongSupplier nanoClock) {
        this.source = Objects.requireNonNull(source, "source");
        this.eventBroker = Objects.requireNonNull(eventBroker, "eventBroker");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.hostReads = hostReads;
        this.persistenceFile = Objects.requireNonNull(persistenceFile, "persistenceFile");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.interval = Objects.requireNonNull(interval, "interval");
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval must be positive");
        }
        final Instant now = clock.instant();
        this.activity = CubismActivitySnapshot.inactive(now);
        this.current = ProjectWorkTimeSnapshot.empty(now);
        loadPersistedProjects();
    }

    @Override
    public ProjectWorkTimeSnapshot snapshot() {
        synchronized (lifecycle) {
            return current;
        }
    }

    @Override
    public boolean isAvailable() {
        synchronized (lifecycle) {
            return !closed;
        }
    }

    /** Starts the sampling loop. Idempotent. */
    public void start() {
        synchronized (lifecycle) {
            if (closed || started) {
                return;
            }
            started = true;
        }
        scheduleNext(interval);
    }

    private void scheduleNext(final Duration delay) {
        final RuntimeTimerSubmission submission = scheduler.schedule(delay, this::tick);
        synchronized (lifecycle) {
            if (!closed && submission.accepted()) {
                pendingTimer = submission.handle();
            }
        }
    }

    void tick() {
        synchronized (lifecycle) {
            if (closed) {
                return;
            }
            pendingTimer = null;
        }
        if (hostReads == null) {
            // Direct test composition: callers drive one observation at a time.
            observeOnce();
            return;
        }
        if (!hostReads.offerSessionObservation(this::observeSafely)) {
            armNext();
        }
    }

    private void observeSafely() {
        try {
            observeOnce();
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            RuntimeDiagnostics.debug(
                    DIAGNOSTIC_COMPONENT,
                    "Activity observation failed safely: " + failure.getClass().getName());
        } finally {
            armNext();
        }
    }

    private void observeOnce() {
        synchronized (lifecycle) {
            if (closed) {
                return;
            }
        }
        final long nowNanos = nanoClock.getAsLong();
        final boolean nextForeground;
        final Optional<String> nextProjectId;
        try {
            nextForeground = source.isForeground();
            nextProjectId = source.activeProjectId();
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            RuntimeDiagnostics.debug(
                    DIAGNOSTIC_COMPONENT,
                    "Activity probe failed safely: " + failure.getClass().getName());
            return;
        }

        ProjectSnapshot fullProject = null;
        final boolean readFullProject;
        synchronized (lifecycle) {
            if (closed) {
                return;
            }
            final String nextId = nextProjectId.orElse(null);
            readFullProject = nextId != null
                    && (!Objects.equals(nextId, activeProjectSessionId) || activeProjectSnapshot == null);
        }
        if (readFullProject) {
            try {
                final HostSnapshotSource.HostProject hostProject =
                        source.activeProject().orElse(null);
                fullProject = hostProject == null ? null : HostSnapshots.project(hostProject);
            } catch (Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                RuntimeDiagnostics.debug(
                        DIAGNOSTIC_COMPONENT,
                        "Project identity read failed safely: "
                                + failure.getClass().getName());
            }
        }

        boolean persistDue;
        synchronized (lifecycle) {
            if (closed) {
                return;
            }
            final long elapsed = lastTickNanos < 0L ? 0L : Math.min(nowNanos - lastTickNanos, MAX_ELAPSED_NANOS);
            lastTickNanos = nowNanos;
            addElapsed(elapsed);

            final String nextId = nextProjectId.orElse(null);
            if (nextId == null) {
                clearActiveProject();
            } else if (readFullProject && fullProject != null) {
                activateProject(fullProject, nextId);
            } else if (!Objects.equals(nextId, activeProjectSessionId)) {
                activateUnreadableProject(nextId);
            }

            foreground = nextForeground;
            final Instant observedAt = clock.instant();
            final CubismActivitySnapshot nextActivity =
                    new CubismActivitySnapshot(nextForeground, Optional.ofNullable(activeProjectSnapshot), observedAt);
            final boolean activityChanged = observedOnce
                    && (nextActivity.editorForeground() != activity.editorForeground()
                            || !Objects.equals(activeProjectKey, observedActivityProjectKey));
            final CubismActivitySnapshot previousActivity = activity;
            activity = nextActivity;
            observedActivityProjectKey = activeProjectKey;
            observedOnce = true;
            current = buildSnapshot(observedAt);
            if (activityChanged && eventBroker.hasObserversFor(CubismActivityChangedEvent.class)) {
                eventBroker.publishRuntime(new CubismActivityChangedEvent(previousActivity, nextActivity));
            }
            ticksSincePersist++;
            persistDue = dirty && ticksSincePersist * interval.toNanos() >= PERSIST_INTERVAL.toNanos();
        }
        if (persistDue) {
            persist();
            synchronized (lifecycle) {
                ticksSincePersist = 0;
            }
        }
    }

    private void armNext() {
        synchronized (lifecycle) {
            if (closed || pendingTimer != null) {
                return;
            }
        }
        scheduleNext(interval);
    }

    private void addElapsed(final long elapsedNanos) {
        // Only a project in the foreground is measured. Counting idle editor time made the session
        // timer keep running on an empty workspace, which reads as a wrong total.
        if (elapsedNanos <= 0L || !foreground || activeProjectKey == null) {
            return;
        }
        sessionForegroundNanos = saturatedAdd(sessionForegroundNanos, elapsedNanos);
        if (activeProjectKey != null) {
            final MutableProjectWorkTime project = projects.get(activeProjectKey);
            if (project != null) {
                project.sessionNanos = saturatedAdd(project.sessionNanos, elapsedNanos);
                project.totalNanos = saturatedAdd(project.totalNanos, elapsedNanos);
                project.lastActiveAt = clock.instant();
                dirty = true;
            }
        }
    }

    private void clearActiveProject() {
        if (activeProjectKey != null) {
            // The session timer measures the open project, so closing it returns the reading to
            // zero instead of leaving the previous project's foreground time on screen.
            sessionForegroundNanos = 0L;
        }
        activeProjectSessionId = null;
        activeProjectKey = null;
        activeProjectSnapshot = null;
    }

    private void activateProject(final ProjectSnapshot project, final String sessionId) {
        final String key = projectKey(project);
        activateProject(key, project.name(), sessionId, project);
    }

    private void activateUnreadableProject(final String sessionId) {
        final String key = "native:" + sessionId;
        activateProject(key, "Untitled", sessionId, null);
    }

    private void activateProject(
            final String key, final String name, final String sessionId, final ProjectSnapshot snapshot) {
        activeProjectSessionId = sessionId;
        activeProjectKey = key;
        activeProjectSnapshot = snapshot;
        projects.computeIfAbsent(key, ignored -> {
            dirty = true;
            return new MutableProjectWorkTime(key, name);
        });
        final MutableProjectWorkTime project = projects.get(key);
        if (project != null && name != null && !name.isBlank() && !name.equals(project.name)) {
            project.name = name;
            dirty = true;
        }
    }

    private ProjectWorkTimeSnapshot buildSnapshot(final Instant observedAt) {
        final List<ProjectWorkTime> values = new ArrayList<>(projects.size());
        for (MutableProjectWorkTime project : projects.values()) {
            if (project.totalNanos <= 0L && project.sessionNanos <= 0L && !project.key.equals(activeProjectKey)) {
                continue;
            }
            values.add(new ProjectWorkTime(
                    project.key,
                    project.name,
                    Duration.ofNanos(project.sessionNanos),
                    Duration.ofNanos(project.totalNanos),
                    project.lastActiveAt));
        }
        values.sort(Comparator.comparing(ProjectWorkTime::totalTime)
                .reversed()
                .thenComparing(ProjectWorkTime::projectName)
                .thenComparing(ProjectWorkTime::projectKey));
        final Optional<ProjectWorkTime> active = values.stream()
                .filter(project -> project.projectKey().equals(activeProjectKey))
                .findFirst();
        return new ProjectWorkTimeSnapshot(
                foreground, Duration.ofNanos(sessionForegroundNanos), active, values, observedAt);
    }

    private static String projectKey(final ProjectSnapshot project) {
        final String directory = project.projectDirectory().map(Path::toString).orElse("");
        final List<String> contentNames = project.contents().stream()
                .map(ProjectContentSnapshot::name)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .sorted()
                .toList();
        final List<String> documentNames = project.documents().stream()
                .map(DocumentSnapshot::name)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .sorted()
                .toList();
        final List<String> stableNames = contentNames.isEmpty() ? documentNames : contentNames;
        final String stableBase =
                stableNames.isEmpty() ? project.name() : String.join(Character.toString(0x1F), stableNames);
        return stableBase + "|" + directory;
    }

    private static long saturatedAdd(final long left, final long right) {
        final long result = left + right;
        return result < 0L ? Long.MAX_VALUE : result;
    }

    /**
     * Parses the persisted document and accepts it only when it is the closed shape this build
     * writes: a JSON object, no root field outside {@link #ALLOWED_ROOT_FIELDS}, the current
     * {@link #FORMAT} and an object {@code projects} field.
     *
     * @param bytes the exact document bytes
     * @return the parsed root, or {@code null} when the bytes are valid JSON but not the current
     *     schema (corrupt shape, unknown root field or a forward-written format)
     * @throws IOException when the bytes are not JSON at all
     */
    private JsonNode parseValidDocument(final byte[] bytes) throws IOException {
        final JsonNode root = mapper.readTree(bytes);
        if (root == null || !root.isObject()) {
            return null;
        }
        final var fieldNames = root.fieldNames();
        while (fieldNames.hasNext()) {
            if (!ALLOWED_ROOT_FIELDS.contains(fieldNames.next())) {
                return null;
            }
        }
        if (!FORMAT.equals(root.path("format").asText(null))
                || !root.path("projects").isObject()) {
            return null;
        }
        return root;
    }

    private void loadPersistedProjects() {
        if (!Files.exists(persistenceFile, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            if (!Files.isRegularFile(persistenceFile, LinkOption.NOFOLLOW_LINKS)) {
                RuntimeDiagnostics.warn(DIAGNOSTIC_COMPONENT, "Ignoring work-time state that is not a regular file");
                return;
            }
            if (Files.size(persistenceFile) > MAX_PERSISTENCE_BYTES) {
                RuntimeDiagnostics.warn(
                        DIAGNOSTIC_COMPONENT,
                        "Ignoring work-time state above the " + MAX_PERSISTENCE_BYTES + " byte read limit");
                return;
            }
            final JsonNode root = parseValidDocument(Files.readAllBytes(persistenceFile));
            if (root == null) {
                RuntimeDiagnostics.warn(DIAGNOSTIC_COMPONENT, "Ignoring work-time state with an unknown format");
                return;
            }
            final JsonNode stored = root.path("projects");
            stored.fields().forEachRemaining(entry -> {
                final JsonNode value = entry.getValue();
                final long total = value.path("totalNanos").asLong(0L);
                final String name = value.path("name").asText("").trim();
                if (entry.getKey().isBlank() || total <= 0L || name.isEmpty()) {
                    return;
                }
                final Instant lastActive =
                        parseInstant(value.path("lastActiveAt").asText(""));
                projects.put(entry.getKey(), new MutableProjectWorkTime(entry.getKey(), name, 0L, total, lastActive));
            });
        } catch (IOException | RuntimeException failure) {
            RuntimeDiagnostics.warn(
                    DIAGNOSTIC_COMPONENT,
                    "Work-time state could not be read safely: "
                            + failure.getClass().getName());
        }
    }

    private static Instant parseInstant(final String value) {
        if (value.isBlank()) {
            return Instant.EPOCH;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException invalid) {
            return Instant.EPOCH;
        }
    }

    private void persist() {
        final Map<String, MutableProjectWorkTime> copy = new HashMap<>();
        synchronized (lifecycle) {
            for (Map.Entry<String, MutableProjectWorkTime> entry : projects.entrySet()) {
                copy.put(entry.getKey(), entry.getValue().copy());
            }
        }
        final ObjectNode root = mapper.createObjectNode();
        root.put("format", FORMAT);
        final ObjectNode stored = root.putObject("projects");
        for (Map.Entry<String, MutableProjectWorkTime> entry : copy.entrySet()) {
            final MutableProjectWorkTime project = entry.getValue();
            if (project.totalNanos <= 0L) {
                continue;
            }
            final ObjectNode value = stored.putObject(entry.getKey());
            value.put("name", project.name);
            value.put("totalNanos", project.totalNanos);
            value.put("lastActiveAt", project.lastActiveAt.toString());
        }
        try {
            final Path parent = persistenceFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            preserveUnreadableState();
            DurableFile.at(persistenceFile).replace(mapper.writeValueAsBytes(root));
            synchronized (lifecycle) {
                dirty = false;
            }
        } catch (IOException failure) {
            RuntimeDiagnostics.warn(
                    DIAGNOSTIC_COMPONENT,
                    "Work-time state could not be persisted safely: "
                            + failure.getClass().getName());
        }
    }

    /**
     * Keeps one image of a work-time document this build can no longer read, before a save replaces
     * it.
     *
     * <p>{@link #loadPersistedProjects()} ignores a corrupt, unknown-field or forward-written
     * document and leaves it in place, so the next save would otherwise erase the only copy of what
     * the previous (possibly newer) build had recorded. A document this build reads normally is
     * overwritten with no extra copy, so a healthy installation never accumulates backups. Retention
     * is exactly one image and the copy is best effort: it never blocks or fails the save.</p>
     */
    private void preserveUnreadableState() {
        final byte[] existing;
        try {
            if (!Files.isRegularFile(persistenceFile, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(persistenceFile) > MAX_PERSISTENCE_BYTES) {
                return;
            }
            existing = Files.readAllBytes(persistenceFile);
        } catch (IOException | RuntimeException unavailable) {
            RuntimeDiagnostics.debug(
                    DIAGNOSTIC_COMPONENT,
                    "Work-time state pre-image could not be read: "
                            + unavailable.getClass().getName());
            return;
        }
        try {
            if (parseValidDocument(existing) != null) {
                return;
            }
        } catch (IOException | RuntimeException unreadable) {
            // Fall through: the bytes this build cannot read are exactly what must be kept.
        }
        try {
            DurableFile.at(persistenceFile.resolveSibling(
                            persistenceFile.getFileName().toString() + CORRUPT_SUFFIX))
                    .replace(existing);
        } catch (IOException | RuntimeException unwritable) {
            RuntimeDiagnostics.debug(
                    DIAGNOSTIC_COMPONENT,
                    "Work-time state pre-image could not be preserved: "
                            + unwritable.getClass().getName());
        }
    }

    @Override
    public void close() {
        final RuntimeTimerHandle timer;
        synchronized (lifecycle) {
            if (closed) {
                return;
            }
            closed = true;
            timer = pendingTimer;
            pendingTimer = null;
        }
        if (timer != null) {
            timer.cancel();
        }
        persist();
    }

    private static final class MutableProjectWorkTime {
        private final String key;
        private String name;
        private long sessionNanos;
        private long totalNanos;
        private Instant lastActiveAt;

        private MutableProjectWorkTime(final String key, final String name) {
            this(key, name, 0L, 0L, Instant.EPOCH);
        }

        private MutableProjectWorkTime(
                final String key,
                final String name,
                final long sessionNanos,
                final long totalNanos,
                final Instant lastActiveAt) {
            this.key = key;
            this.name = name;
            this.sessionNanos = Math.max(0L, sessionNanos);
            this.totalNanos = Math.max(Math.max(0L, totalNanos), this.sessionNanos);
            this.lastActiveAt = lastActiveAt == null ? Instant.EPOCH : lastActiveAt;
        }

        private MutableProjectWorkTime copy() {
            return new MutableProjectWorkTime(key, name, sessionNanos, totalNanos, lastActiveAt);
        }
    }
}
