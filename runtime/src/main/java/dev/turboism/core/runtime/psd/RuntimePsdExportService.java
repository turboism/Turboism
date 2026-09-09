package dev.turboism.core.runtime.psd;

import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.cubism.id.RawImageId;
import dev.turboism.sdk.cubism.psd.PsdExportResult;
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
 * Per-plugin, runtime-private first slice for observing a raw-image PSD export.
 *
 * <p>The worker owns the temporary path and is the only code that passes it to the internal
 * {@link PsdExportHost} port. No path, host object, or native result is exposed through the SDK.
 * This slice deliberately publishes {@link PsdExportResult.Status#FAILED} for every readable
 * export because it has not yet established the evidence required to issue a persistent edit
 * handle.</p>
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
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final ExecutorService executor;

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
            PsdTemporaryFile::create
        );
    }

    /** Test-only composition seam; it does not change the production queue or lifecycle policy. */
    RuntimePsdExportService(
        final String pluginId,
        final PermissionChecker permissionChecker,
        final BooleanSupplier activeScope,
        final Consumer<Runnable> continuationDispatcher,
        final TemporaryFileFactory temporaryFileFactory
    ) {
        this.pluginId = requireText(pluginId, "pluginId");
        this.permissionChecker = Objects.requireNonNull(permissionChecker, "permissionChecker");
        this.activeScope = Objects.requireNonNull(activeScope, "activeScope");
        this.continuationDispatcher = Objects.requireNonNull(
            continuationDispatcher,
            "continuationDispatcher"
        );
        this.temporaryFileFactory = Objects.requireNonNull(temporaryFileFactory, "temporaryFileFactory");
        this.executor = new ThreadPoolExecutor(
            WORKER_COUNT,
            WORKER_COUNT,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(QUEUE_CAPACITY),
            new PsdExportThreadFactory(this.pluginId),
            new ThreadPoolExecutor.AbortPolicy()
        );
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

    /**
     * Closes this service without interrupting a native call or waiting for the worker to exit.
     * Queued work remains in the executor, but every queued/running operation fails its next
     * runtime admission check and performs no further native call.
     */
    @Override
    public void close() {
        if (active.compareAndSet(true, false)) {
            executor.shutdown();
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
            return observe(source, Objects.requireNonNull(observation, "native observation"));
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

    private PsdExportResult observe(
        final RawImageId source,
        final PsdExportHost.Observation observation
    ) {
        final String nativeStatus = safeStatus(observation.nativeStatus());
        final PsdExportResult.Status resultStatus = "UNAVAILABLE".equals(nativeStatus)
            ? PsdExportResult.Status.UNAVAILABLE
            : PsdExportResult.Status.FAILED;
        return result(
            resultStatus,
            source,
            nativeStatus,
            safeStatus(observation.integrityStatus()),
            observation.readable(),
            observation.structureMatches()
        );
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

    private static final class InactiveOperation extends RuntimeException {
        private InactiveOperation() {
            super(null, null, false, false);
        }
    }
}
