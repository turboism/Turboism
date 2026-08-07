package dev.turboism.adapter.cubism.backup;

import dev.turboism.sdk.cubism.ProjectContentSnapshot;
import dev.turboism.sdk.cubism.backup.BackupCompletedEvent;
import dev.turboism.sdk.cubism.backup.BackupSyncTarget;
import dev.turboism.sdk.cubism.backup.EditorAutoBackupService;
import dev.turboism.sdk.cubism.backup.EditorAutoBackupSettings;
import dev.turboism.sdk.cubism.backup.EditorAutoBackupStatus;
import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.plugin.Registration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Runtime {@link EditorAutoBackupService}: orchestrates the verified host
 * auto-backup operations, publishes {@link BackupCompletedEvent}, and invokes
 * registered {@link BackupSyncTarget}s with the new artifacts.
 *
 * <p>Guarantees:</p>
 * <ul>
 *   <li>Settings updates snapshot the observed originals, apply the target
 *       through the verified manager setters, read the values back, and on any
 *       failure roll back to the originals with a verified readback. An
 *       unverified rollback fails closed ({@link IllegalStateException}).</li>
 *   <li>{@code backupNow()} runs the host mutation on the host UI thread and
 *       polls (never fixed sleeps) the backup directory and the document
 *       timestamps until artifacts appear or the timeout expires; a timeout
 *       completes the stage exceptionally (fail closed).</li>
 *   <li>{@code backupAfterSave()} matches the saved snapshot against the pack
 *       file contents, invokes the verified saveDocument primitive (dirty
 *       condition bypassed), polls the exact artifact, and then publishes and
 *       syncs like {@code backupNow()}; per-document saves within the 2-second
 *       debounce window coalesce, and the single host thread serializes every
 *       run so host saveDocument calls never execute concurrently.</li>
 *   <li>Sync-target failures are isolated: a throwing target never corrupts
 *       the backup result or the published event.</li>
 * </ul>
 */
public final class AutoBackupCoordinator implements EditorAutoBackupService, AutoCloseable {

    /** Default backup-completion polling timeout (host produces on a background thread). */
    public static final long DEFAULT_POLL_TIMEOUT_MILLIS = 60_000L;
    static final long POLL_INTERVAL_MILLIS = 500L;

    /** Backup artifact name pattern: {@code <name>_backup<yyyy_MMdd_HHmm>.cmo3}. */
    static final String BACKUP_FILE_MARKER = "_backup";


    /** Per-document save-triggered backup debounce window: saves within this window coalesce. */
    static final long SAVE_DEBOUNCE_WINDOW_MILLIS = 2_000L;

    private final AutoBackupAdapter adapter;
    private final EventBus eventBus;
    private final Clock clock;
    private final long pollTimeoutMillis;
    private final long pollIntervalMillis;
    private final Consumer<String> diagnostics;
    private final CopyOnWriteArrayList<BackupSyncTarget> syncTargets = new CopyOnWriteArrayList<>();
    private final ExecutorService hostThread;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private final ConcurrentHashMap<String, Pending> pendingSaves = new ConcurrentHashMap<>();

    public AutoBackupCoordinator(
        final AutoBackupAdapter adapter,
        final EventBus eventBus,
        final Clock clock,
        final long pollTimeoutMillis
    ) {
        this(adapter, eventBus, clock, pollTimeoutMillis, reason -> { });
    }

    public AutoBackupCoordinator(
        final AutoBackupAdapter adapter,
        final EventBus eventBus,
        final Clock clock,
        final long pollTimeoutMillis,
        final Consumer<String> diagnostics
    ) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (pollTimeoutMillis <= 0) {
            throw new IllegalArgumentException("pollTimeoutMillis must be positive");
        }
        this.pollTimeoutMillis = pollTimeoutMillis;
        this.pollIntervalMillis = Math.min(POLL_INTERVAL_MILLIS, pollTimeoutMillis);
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.hostThread = Executors.newSingleThreadExecutor(daemon("turboism-autobackup-host"));
    }

    @Override
    public EditorAutoBackupSettings settings() {
        requireOpen();
        return toSettings(adapter.settings());
    }

    @Override
    public EditorAutoBackupSettings updateSettings(final EditorAutoBackupSettings settings) {
        Objects.requireNonNull(settings, "settings");
        requireOpen();
        final AutoBackupAdapter.Snapshot target = new AutoBackupAdapter.Snapshot(
            settings.enabled(), settings.intervalMinutes(), settings.maxMB(), null
        );
        final AutoBackupAdapter.Snapshot original = adapter.settings();
        if (matches(original, target)) {
            // No-op short-circuit: identical values produce no setter side effects.
            return toSettings(original);
        }
        try {
            return toSettings(adapter.applySettings(target));
        } catch (RuntimeException | Error failure) {
            try {
                rollbackTo(original);
            } catch (RuntimeException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        }
    }

    @Override
    public List<EditorAutoBackupStatus> statuses() {
        requireOpen();
        return adapter.documents().stream().map(AutoBackupCoordinator::toStatus).toList();
    }

    @Override
    public CompletionStage<BackupCompletedEvent> backupNow() {
        requireOpen();
        final CompletableFuture<BackupCompletedEvent> result = new CompletableFuture<>();
        hostThread.execute(() -> {
            try {
                result.complete(runBackupNow());
            } catch (Throwable failure) {
                diagnostics.accept("backupNow:failed " + failure.getClass().getName());
                result.completeExceptionally(sanitize(failure));
            }
        });
        return result;
    }

    @Override
    public CompletionStage<BackupCompletedEvent> backupAfterSave(final ProjectContentSnapshot saved) {
        Objects.requireNonNull(saved, "saved");
        requireOpen();
        final String key = saveKey(saved);
        final long now = clock.millis();
        // Lazy expiry keeps the debounce map bounded to one entry per live document.
        pendingSaves.entrySet().removeIf(
            entry -> now - entry.getValue().scheduledAtMillis >= SAVE_DEBOUNCE_WINDOW_MILLIS
        );
        final CompletableFuture<BackupCompletedEvent> result = new CompletableFuture<>();
        final Pending[] chosen = new Pending[1];
        pendingSaves.compute(key, (ignored, existing) -> {
            if (existing != null
                && now - existing.scheduledAtMillis < SAVE_DEBOUNCE_WINDOW_MILLIS) {
                // Idempotent debounce: a save within the per-document window is
                // coalesced into the in-flight backup and observes its outcome.
                chosen[0] = existing;
                return existing;
            }
            chosen[0] = new Pending(now, result);
            return chosen[0];
        });
        if (chosen[0].stage != result) {
            return chosen[0].stage;
        }
        hostThread.execute(() -> {
            try {
                result.complete(runBackupAfterSave(saved));
            } catch (Throwable failure) {
                diagnostics.accept("backupAfterSave:failed " + failure.getClass().getName());
                result.completeExceptionally(sanitize(failure));
            }
        });
        return result;
    }
    @Override
    public Registration registerSyncTarget(final BackupSyncTarget target) {
        Objects.requireNonNull(target, "target");
        requireOpen();
        syncTargets.add(target);
        return () -> syncTargets.remove(target);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            hostThread.shutdownNow();
        }
    }

    private BackupCompletedEvent runBackupNow() {
        final long startedAt = clock.millis();
        final AutoBackupAdapter.Snapshot before = adapter.settings();
        final List<AutoBackupAdapter.Document> beforeDocuments = adapter.documents();
        adapter.triggerBackupNow();

        final List<File> newFiles = pollForArtifacts(before, beforeDocuments, startedAt);
        final List<EditorAutoBackupStatus> statuses = adapter.documents().stream()
            .map(AutoBackupCoordinator::toStatus)
            .toList();
        final BackupCompletedEvent event = new BackupCompletedEvent(clock.millis(), newFiles, statuses);

        // The event is published first; then sync targets run. A throwing target is
        // isolated and can never corrupt the backup result.
        eventBus.publish(event);
        for (BackupSyncTarget target : syncTargets) {
            try {
                target.sync(newFiles);
            } catch (RuntimeException | Error targetFailure) {
                diagnostics.accept(
                    "backupNow:sync-target-failed " + targetFailure.getClass().getName()
                );
            }
        }
        return event;
    }

    private BackupCompletedEvent runBackupAfterSave(final ProjectContentSnapshot saved) {
        final long startedAt = clock.millis();
        final AutoBackupAdapter.Snapshot before = adapter.settings();
        if (before.backupDir() == null) {
            throw new IllegalStateException("auto-backup backup directory is unavailable");
        }
        final File matchFile = saved.filePath()
            .map(Path::toFile)
            .orElseGet(() -> new File(saved.name()));
        final File artifact = adapter.saveDocumentFor(matchFile, startedAt);
        if (artifact == null) {
            // No pack file content matches the saved snapshot: fail closed with a
            // self-diagnosing message (attempted identity + available pack contents).
            final String packNames = adapter.documents().stream()
                .map(AutoBackupAdapter.Document::name)
                .collect(java.util.stream.Collectors.joining(", "));
            throw new IllegalStateException(
                "auto-backup save-triggered backup: no pack content matches the saved document: "
                    + saved.name()
                    + "; attempted match file: " + matchFile.getPath()
                    + "; pack contents: " + packNames
            );
        }
        final File confirmed = pollForArtifact(artifact, startedAt);
        final List<EditorAutoBackupStatus> statuses = adapter.documents().stream()
            .map(AutoBackupCoordinator::toStatus)
            .toList();
        final BackupCompletedEvent event = new BackupCompletedEvent(
            clock.millis(), List.of(confirmed), statuses
        );
        eventBus.publish(event);
        for (BackupSyncTarget target : syncTargets) {
            try {
                target.sync(List.of(confirmed));
            } catch (RuntimeException | Error targetFailure) {
                diagnostics.accept(
                    "backupAfterSave:sync-target-failed " + targetFailure.getClass().getName()
                );
            }
        }
        return event;
    }

    /** Polls the exact save-triggered artifact (size &gt; 0) until it appears or the timeout expires. */
    private File pollForArtifact(final File artifact, final long startedAt) {
        final long deadline = startedAt + pollTimeoutMillis;
        while (true) {
            if (artifact.isFile() && artifact.length() > 0) {
                return artifact;
            }
            if (clock.millis() >= deadline) {
                throw new IllegalStateException(
                    "auto-backup save-triggered artifact timeout after " + pollTimeoutMillis
                        + " ms: " + artifact.getName()
                );
            }
            try {
                Thread.sleep(pollIntervalMillis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("auto-backup polling interrupted", interrupted);
            }
        }
    }

    private static String saveKey(final ProjectContentSnapshot saved) {
        return saved.kind().name().toLowerCase(java.util.Locale.ROOT) + ":" + saved.name();
    }

    /**
     * Polls the backup directory and the document lastAutoBackupTime values until
     * a fresh {@code <name>_backup*.cmo3} artifact (size &gt; 0) appears, or the
     * timeout expires. Polling only; never a fixed sleep to guess completion.
     */
    private List<File> pollForArtifacts(
        final AutoBackupAdapter.Snapshot before,
        final List<AutoBackupAdapter.Document> beforeDocuments,
        final long startedAt
    ) {
        final Path backupDir = before.backupDir() == null ? null : before.backupDir().toPath();
        final long deadline = startedAt + pollTimeoutMillis;
        while (true) {
            final List<File> fresh = scanForFreshArtifacts(backupDir, startedAt);
            if (!fresh.isEmpty() && lastAutoBackupAdvanced(beforeDocuments)) {
                return fresh;
            }
            if (clock.millis() >= deadline) {
                throw new IllegalStateException(
                    "auto-backup completion timeout after " + pollTimeoutMillis + " ms"
                );
            }
            try {
                Thread.sleep(pollIntervalMillis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("auto-backup polling interrupted", interrupted);
            }
        }
    }

    private List<File> scanForFreshArtifacts(final Path backupDir, final long startedAt) {
        if (backupDir == null || !Files.isDirectory(backupDir)) {
            return List.of();
        }
        final List<File> fresh = new ArrayList<>();
        try (var stream = Files.list(backupDir)) {
            stream.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().contains(BACKUP_FILE_MARKER))
                .filter(path -> path.getFileName().toString().endsWith(".cmo3"))
                .filter(path -> {
                    try {
                        return Files.size(path) > 0 && Files.getLastModifiedTime(path).toMillis() >= startedAt - 1000L;
                    } catch (IOException failure) {
                        return false;
                    }
                })
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .forEach(path -> fresh.add(path.toFile()));
        } catch (IOException unavailable) {
            return List.of();
        }
        return List.copyOf(fresh);
    }

    /** In-flight save-triggered backup pending per document (debounce + coalescing). */
    private record Pending(long scheduledAtMillis, CompletableFuture<BackupCompletedEvent> stage) { }

    private boolean lastAutoBackupAdvanced(final List<AutoBackupAdapter.Document> before) {
        final List<AutoBackupAdapter.Document> now = adapter.documents();
        for (AutoBackupAdapter.Document beforeDocument : before) {
            for (AutoBackupAdapter.Document afterDocument : now) {
                if (Objects.equals(beforeDocument.file(), afterDocument.file())) {
                    if (afterDocument.lastAutoBackupTimeMillis() > beforeDocument.lastAutoBackupTimeMillis()) {
                        return true;
                    }
                    break;
                }
            }
        }
        return false;
    }

    private void rollbackTo(final AutoBackupAdapter.Snapshot original) {
        // Restore the exact observed originals and verify by readback. When the host
        // cannot restore them, applySettings throws and the caller fails closed.
        final AutoBackupAdapter.Snapshot readback = adapter.applySettings(original);
        if (!matches(original, readback)) {
            throw new IllegalStateException("auto-backup rollback unverified: readback mismatch");
        }
    }

    private static boolean matches(
        final AutoBackupAdapter.Snapshot expected,
        final AutoBackupAdapter.Snapshot actual
    ) {
        return expected.enabled() == actual.enabled()
            && expected.intervalMinutes() == actual.intervalMinutes()
            && expected.maxMB() == actual.maxMB();
    }

    private static EditorAutoBackupSettings toSettings(final AutoBackupAdapter.Snapshot snapshot) {
        return new EditorAutoBackupSettings(
            snapshot.enabled(),
            snapshot.intervalMinutes(),
            snapshot.maxMB(),
            snapshot.backupDir() == null ? null : snapshot.backupDir().getPath()
        );
    }

    private static EditorAutoBackupStatus toStatus(final AutoBackupAdapter.Document document) {
        return new EditorAutoBackupStatus(
            document.name(),
            document.file() == null ? null : document.file().getPath(),
            document.lastAutoBackupTimeMillis(),
            document.lastSavedTimeMillis(),
            document.modifiedAfterSaving()
        );
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("auto-backup coordinator is closed");
        }
    }

    private static Throwable sanitize(final Throwable failure) {
        return failure instanceof RuntimeException || failure instanceof Error
            ? failure
            : new IllegalStateException("auto-backup run failed safely", failure);
    }

    private static ThreadFactory daemon(final String name) {
        return runnable -> {
            final Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }
}
