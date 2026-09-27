package dev.turboism.core.runtime.psd;

import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.cubism.id.RawImageId;
import dev.turboism.sdk.cubism.psd.PsdExportResult;
import dev.turboism.sdk.cubism.psd.PsdFileRevision;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.task.PluginCompletionFuture;
import dev.turboism.task.RuntimePluginTaskScheduler;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Per-plugin, runtime-private service for exporting a raw image into a controlled PSD handle.
 *
 * <p>The worker owns the temporary path and is the only code that passes it to the internal
 * {@link PsdExportHost} port. No path, host object, or native result is exposed through the SDK.
 * A readable native export from a session-bound port ({@link PsdSessionBoundHost}) issues a
 * {@link RuntimePsdEditFile} handle with an initial baseline revision; an unreadable export, or a
 * port that cannot prove its session identity, stays failed and issues nothing.</p>
 */
public final class RuntimePsdExportService implements AutoCloseable {
    private static final String OPERATION = "model.textures.exportRawImagePsd";
    private static final int WORKER_COUNT = 1;
    private static final int QUEUE_CAPACITY = 8;
    private static final Pattern SAFE_STATUS = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    private final String pluginId;
    private final PermissionChecker permissionChecker;
    private final BooleanSupplier activeScope;
    private final Consumer<Runnable> continuationDispatcher;
    private final TemporaryFileFactory temporaryFileFactory;
    private final PsdEditRegistry registry = new PsdEditRegistry();
    private final PsdDefaultApplicationLauncher launcher;
    private final PsdSaveWatcher.Scheduler saveScheduler;
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final ExecutorService executor;
    private final ScheduledExecutorService watchLane;
    private final List<RuntimePsdEditFile> handles = new ArrayList<>();

    /** Creates the service with the owning plugin task completion lane. */
    public RuntimePsdExportService(
        final String pluginId,
        final PermissionChecker permissionChecker,
        final BooleanSupplier activeScope,
        final RuntimePluginTaskScheduler pluginTasks
    ) {
        this(
            pluginId,
            permissionChecker,
            activeScope,
            Objects.requireNonNull(pluginTasks, "pluginTasks")::dispatchContinuation,
            PsdTemporaryFile::create,
            PsdDefaultApplicationLauncher.system()
        );
    }

    /** Test-only composition seam; it does not change the production queue or lifecycle policy. */
    RuntimePsdExportService(
        final String pluginId,
        final PermissionChecker permissionChecker,
        final BooleanSupplier activeScope,
        final Consumer<Runnable> continuationDispatcher,
        final TemporaryFileFactory temporaryFileFactory,
        final PsdDefaultApplicationLauncher launcher
    ) {
        this.pluginId = requireText(pluginId, "pluginId");
        this.permissionChecker = Objects.requireNonNull(permissionChecker, "permissionChecker");
        this.activeScope = Objects.requireNonNull(activeScope, "activeScope");
        this.continuationDispatcher = Objects.requireNonNull(
            continuationDispatcher,
            "continuationDispatcher"
        );
        this.temporaryFileFactory = Objects.requireNonNull(temporaryFileFactory, "temporaryFileFactory");
        this.launcher = Objects.requireNonNull(launcher, "launcher");
        this.executor = new ThreadPoolExecutor(
            WORKER_COUNT,
            WORKER_COUNT,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(QUEUE_CAPACITY),
            new PsdExportThreadFactory(this.pluginId),
            new ThreadPoolExecutor.AbortPolicy()
        );
        // One bounded lane per plugin serializes every handle's save observation, so two documents
        // of one plugin can never interleave inside a watcher pass.
        this.watchLane = Executors.newSingleThreadScheduledExecutor(
            new PsdWatchThreadFactory(this.pluginId));
        this.saveScheduler = (task, delayMillis) -> {
            final java.util.concurrent.ScheduledFuture<?> future =
                watchLane.schedule(task, delayMillis, TimeUnit.MILLISECONDS);
            return () -> future.cancel(false);
        };
    }

    /**
     * Schedules one observation-only export for the current model-bound host port.
     *
     * <p>Admission is non-blocking. Permission and scope checks are repeated before queueing,
     * inside the worker, from the native admission callback, and on the completion lane before
     * publication.</p>
     */
    public CompletionStage<PsdExportResult> exportRawImagePsd(
        final PsdExportHost host,
        final RawImageId source
    ) {
        final PsdExportHost exportHost = Objects.requireNonNull(host, "host");
        final RawImageId rawImage = Objects.requireNonNull(source, "source");
        final PluginCompletionFuture<PsdExportResult> completion = new PluginCompletionFuture<>(
            continuationDispatcher,
            this::acceptsContinuation
        );
        final AtomicBoolean settled = new AtomicBoolean(false);

        try {
            requireOperational();
            checkPermissions();
        } catch (CubismPermissionException denied) {
            settle(
                completion,
                settled,
                rejected(rawImage, "PERMISSION_DENIED", "UNAVAILABLE")
            );
            return completion.stage();
        } catch (InactiveOperation inactiveOperation) {
            settle(
                completion,
                settled,
                unavailable(rawImage, "SERVICE_UNAVAILABLE", "UNAVAILABLE")
            );
            return completion.stage();
        } catch (RuntimeException failure) {
            settle(
                completion,
                settled,
                failed(rawImage, "ADMISSION_FAILURE", "UNAVAILABLE", false, false)
            );
            return completion.stage();
        }

        try {
            executor.execute(() -> run(exportHost, rawImage, completion, settled));
        } catch (RejectedExecutionException rejected) {
            settle(
                completion,
                settled,
                rejected(rawImage, "QUEUE_REJECTED", "UNAVAILABLE")
            );
        }
        return completion.stage();
    }

    /** The one registry that issues this plugin's PSD file handles and revisions. */
    PsdEditRegistry registry() {
        return registry;
    }

    /**
     * Closes this service without interrupting a native call or waiting for the worker to exit.
     * Queued work remains in the executor, but every queued/running operation fails its next
     * runtime admission check and performs no further native call.
     */
    @Override
    public void close() {
        if (active.compareAndSet(true, false)) {
            final List<RuntimePsdEditFile> issued;
            synchronized (handles) {
                issued = new ArrayList<>(handles);
                handles.clear();
            }
            // Revoking a handle stops its watcher and future admission without deleting any file.
            for (final RuntimePsdEditFile handle : issued) {
                handle.revokeInternal();
            }
            registry.close();
            executor.shutdown();
            watchLane.shutdown();
        }
    }

    private void run(
        final PsdExportHost host,
        final RawImageId source,
        final PluginCompletionFuture<PsdExportResult> completion,
        final AtomicBoolean settled
    ) {
        settle(completion, settled, execute(host, source));
    }

    private PsdExportResult execute(final PsdExportHost host, final RawImageId source) {
        try {
            requireOperational();
            checkPermissions();
        } catch (CubismPermissionException denied) {
            return rejected(source, "PERMISSION_DENIED", "UNAVAILABLE");
        } catch (InactiveOperation inactiveOperation) {
            return unavailable(source, "SERVICE_UNAVAILABLE", "UNAVAILABLE");
        } catch (RuntimeException failure) {
            return failed(source, "WORKER_ADMISSION_FAILURE", "UNAVAILABLE", false, false);
        }

        try {
            final PsdTemporaryFile temporary = Objects.requireNonNull(
                temporaryFileFactory.create(),
                "temporaryFileFactory result"
            );
            final Path destination = temporary.validatedPath();
            final PsdExportHost.Observation observation = host.exportPsdTo(
                source,
                destination,
                this::admitNative
            );
            requireOperational();
            checkPermissions();
            if (observation != null && observation.readable()) {
                temporary.useSourceName(observation.sourceName());
            }
            return issue(
                source,
                host,
                temporary,
                Objects.requireNonNull(observation, "native observation")
            );
        } catch (CubismPermissionException denied) {
            return rejected(source, "PERMISSION_DENIED", "UNAVAILABLE");
        } catch (InactiveOperation inactiveOperation) {
            return unavailable(source, "SERVICE_UNAVAILABLE", "UNAVAILABLE");
        } catch (IllegalStateException staleTarget) {
            return staleTarget(source);
        } catch (IOException temporaryFailure) {
            return failed(source, "TEMPORARY_FILE_FAILURE", "UNAVAILABLE", false, false);
        } catch (RuntimeException nativeFailure) {
            return failed(source, "NATIVE_FAILURE", "UNAVAILABLE", false, false);
        }
    }

    private void admitNative() {
        requireOperational();
        checkPermissions();
    }

    private PsdExportResult issue(
        final RawImageId source,
        final PsdExportHost host,
        final PsdTemporaryFile allocation,
        final PsdExportHost.Observation observation
    ) {
        final String nativeStatus = safeStatus(observation.nativeStatus());
        final String integrityStatus = safeStatus(observation.integrityStatus());
        if ("UNAVAILABLE".equals(nativeStatus)) {
            return unavailable(source, nativeStatus, integrityStatus);
        }
        if (!observation.readable()) {
            final String detail = observation.failure().map(failure ->
                ";phase=" + safeStatus(failure.phase())
                    + ";category=" + safeStatus(failure.category())
                    + ";saveReturned=" + failure.saveReturned()).orElse("");
            return new PsdExportResult(PsdExportResult.Status.FAILED,
                diagnostic(nativeStatus, integrityStatus, false, observation.structureMatches()) + detail,
                source, Optional.empty(), Optional.empty());
        }
        if (!(host instanceof PsdSessionBoundHost bound)) {
            // Fail closed: without a runtime-issued session identity a handle could be replayed
            // across documents that share a model id.
            return failed(
                source, "EXPORTED_UNBOUND", integrityStatus, true, observation.structureMatches());
        }
        RuntimePsdEditFile file = null;
        try {
            final PsdEditRegistry.Binding binding = new PsdEditRegistry.Binding(
                bound.sessionIdentity(), bound.generation());
            final PsdStableSnapshot.Snapshot baselineSnapshot = PsdStableSnapshot.capture(allocation);
            file = new RuntimePsdEditFile(
                pluginId,
                binding,
                allocation,
                registry,
                permissionChecker,
                activeScope,
                continuationDispatcher,
                executor,
                launcher,
                saveScheduler,
                System::nanoTime,
                baselineSnapshot.sha256()
            );
            registry.register(binding, file, allocation);
            final PsdFileRevision baseline = registry.issueRevision(binding, file, baselineSnapshot);
            synchronized (handles) {
                handles.add(file);
            }
            file.beginWatching(baseline);
            return new PsdExportResult(
                PsdExportResult.Status.EXPORTED,
                diagnostic(nativeStatus, integrityStatus, true, observation.structureMatches()),
                source,
                Optional.of(file),
                Optional.of(baseline)
            );
        } catch (IOException | RuntimeException handleFailure) {
            if (file != null) registry.revoke(file);
            return failed(
                source, "EXPORTED_HANDLE_FAILED", integrityStatus, true, observation.structureMatches());
        }
    }

    private void settle(
        final PluginCompletionFuture<PsdExportResult> completion,
        final AtomicBoolean settled,
        final PsdExportResult result
    ) {
        if (!settled.compareAndSet(false, true)) {
            return;
        }
        final Runnable publication = () -> completion.settle(revalidateBeforeCompletion(result));
        try {
            continuationDispatcher.accept(publication);
        } catch (RuntimeException dispatcherFailure) {
            // A scheduler may be closing concurrently. Do not leave the plugin stage pending;
            // the fallback still applies the same final scope/permission boundary.
            completion.settle(revalidateBeforeCompletion(result));
        }
    }

    private PsdExportResult revalidateBeforeCompletion(final PsdExportResult result) {
        try {
            requireOperational();
            checkPermissions();
            return result;
        } catch (CubismPermissionException denied) {
            return rejected(result.source(), "PERMISSION_DENIED", "UNAVAILABLE");
        } catch (InactiveOperation inactiveOperation) {
            return unavailable(result.source(), "SERVICE_UNAVAILABLE", "UNAVAILABLE");
        } catch (RuntimeException failure) {
            return failed(result.source(), "COMPLETION_ADMISSION_FAILURE", "UNAVAILABLE", false, false);
        }
    }

    private boolean acceptsContinuation() {
        return active.get() && scopeIsActive();
    }

    private void requireOperational() {
        if (!active.get() || !scopeIsActive()) {
            throw new InactiveOperation();
        }
    }

    private boolean scopeIsActive() {
        try {
            return activeScope.getAsBoolean();
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private void checkPermissions() {
        permissionChecker.check(PermissionIds.TURBOISM_CUBISM_MODEL_READ, OPERATION);
        permissionChecker.check(PermissionIds.TURBOISM_FILE_WRITE, OPERATION);
        permissionChecker.check(PermissionIds.TURBOISM_FILE_READ, OPERATION);
    }

    private static PsdExportResult unavailable(
        final RawImageId source,
        final String nativeStatus,
        final String integrityStatus
    ) {
        return result(
            PsdExportResult.Status.UNAVAILABLE,
            source,
            nativeStatus,
            integrityStatus,
            false,
            false
        );
    }

    private static PsdExportResult rejected(
        final RawImageId source,
        final String nativeStatus,
        final String integrityStatus
    ) {
        return result(
            PsdExportResult.Status.REJECTED,
            source,
            nativeStatus,
            integrityStatus,
            false,
            false
        );
    }

    private static PsdExportResult staleTarget(final RawImageId source) {
        return result(
            PsdExportResult.Status.STALE_TARGET,
            source,
            "STALE_TARGET",
            "UNAVAILABLE",
            false,
            false
        );
    }

    private static PsdExportResult failed(
        final RawImageId source,
        final String nativeStatus,
        final String integrityStatus,
        final boolean readable,
        final boolean structureMatches
    ) {
        return result(
            PsdExportResult.Status.FAILED,
            source,
            nativeStatus,
            integrityStatus,
            readable,
            structureMatches
        );
    }

    private static PsdExportResult result(
        final PsdExportResult.Status status,
        final RawImageId source,
        final String nativeStatus,
        final String integrityStatus,
        final boolean readable,
        final boolean structureMatches
    ) {
        return new PsdExportResult(
            status,
            diagnostic(nativeStatus, integrityStatus, readable, structureMatches),
            source,
            Optional.empty(),
            Optional.empty()
        );
    }

    private static String diagnostic(
        final String nativeStatus,
        final String integrityStatus,
        final boolean readable,
        final boolean structureMatches
    ) {
        return "PSD_NATIVE_EXPORT;status=" + safeStatus(nativeStatus)
            + ";integrity=" + safeStatus(integrityStatus)
            + ";readable=" + readable
            + ";structure=" + structureMatches;
    }

    private static String safeStatus(final String value) {
        final String candidate = value == null ? "" : value;
        return SAFE_STATUS.matcher(candidate).matches() ? candidate : "UNKNOWN";
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    @FunctionalInterface
    interface TemporaryFileFactory {
        PsdTemporaryFile create() throws IOException;
    }

    private static final class PsdExportThreadFactory implements ThreadFactory {
        private final String threadName;
        private final AtomicInteger sequence = new AtomicInteger();

        private PsdExportThreadFactory(final String pluginId) {
            this.threadName = "turboism.psd-export." + pluginId.replaceAll("[^A-Za-z0-9_.-]", "_");
        }

        @Override
        public Thread newThread(final Runnable task) {
            final Thread thread = new Thread(task, threadName + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }

    /** Daemon lane for save observation; one per plugin so documents cannot interleave. */
    private static final class PsdWatchThreadFactory implements ThreadFactory {
        private final String threadName;

        private PsdWatchThreadFactory(final String pluginId) {
            this.threadName = "turboism.psd-watch." + pluginId.replaceAll("[^A-Za-z0-9_.-]", "_");
        }

        @Override
        public Thread newThread(final Runnable task) {
            final Thread thread = new Thread(task, threadName);
            thread.setDaemon(true);
            return thread;
        }
    }

    private static final class InactiveOperation extends RuntimeException {
        private InactiveOperation() {
            super(null, null, false, false);
        }
    }
}
