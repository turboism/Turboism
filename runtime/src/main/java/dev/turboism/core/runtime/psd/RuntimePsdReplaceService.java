package dev.turboism.core.runtime.psd;

import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.cubism.id.RawImageId;
import dev.turboism.sdk.cubism.psd.PsdEditFile;
import dev.turboism.sdk.cubism.psd.PsdFileRevision;
import dev.turboism.sdk.cubism.psd.PsdReplaceResult;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.task.PluginCompletionFuture;
import dev.turboism.task.RuntimePluginTaskScheduler;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Per-plugin, runtime-private service that applies one verified staged revision to an explicitly
 * targeted raw image through Cubism's native matcher.
 *
 * <p>Admission is layered: the plugin's file handle and revision token must resolve in this
 * plugin's registry, permissions and scope are re-checked before queueing, inside the worker and
 * from the native admission callback, and the host projection re-reads current state afterwards.
 * A single per-plugin lane serializes every replacement, so two documents of one plugin can never
 * interleave two native mutations. A revision is consumed only when the replacement was observed to
 * apply; an unprovable mutation is reported as a partial failure and pauses automatic importing
 * instead of being retried.</p>
 */
public final class RuntimePsdReplaceService implements AutoCloseable {
    private static final String OPERATION = "model.textures.replaceRawImagePsd";
    private static final int WORKER_COUNT = 1;
    private static final int QUEUE_CAPACITY = 8;
    private static final int MAX_TOKEN_LENGTH = 64;

    private final String pluginId;
    private final PermissionChecker permissionChecker;
    private final BooleanSupplier activeScope;
    private final Consumer<Runnable> continuationDispatcher;
    private final PsdEditRegistry registry;
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final ThreadPoolExecutor executor;

    /**
     * Production composition: the registry is the one owned by this plugin's export service, so a
     * file handle or revision issued there is the only accepted authority here.
     */
    public RuntimePsdReplaceService(
        final String pluginId,
        final PermissionChecker permissionChecker,
        final BooleanSupplier activeScope,
        final RuntimePluginTaskScheduler pluginTasks,
        final RuntimePsdExportService owner
    ) {
        this(
            pluginId,
            permissionChecker,
            activeScope,
            Objects.requireNonNull(pluginTasks, "pluginTasks")::dispatchContinuation,
            Objects.requireNonNull(owner, "owner").registry()
        );
    }

    /** Test-only composition seam. */
    RuntimePsdReplaceService(
        final String pluginId,
        final PermissionChecker permissionChecker,
        final BooleanSupplier activeScope,
        final Consumer<Runnable> continuationDispatcher,
        final PsdEditRegistry registry
    ) {
        this.pluginId = requireText(pluginId, "pluginId");
        this.permissionChecker = Objects.requireNonNull(permissionChecker, "permissionChecker");
        this.activeScope = Objects.requireNonNull(activeScope, "activeScope");
        this.continuationDispatcher = Objects.requireNonNull(
            continuationDispatcher, "continuationDispatcher");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.executor = new ThreadPoolExecutor(
            WORKER_COUNT,
            WORKER_COUNT,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(QUEUE_CAPACITY),
            runnable -> {
                final Thread thread = new Thread(
                    runnable, "turboism.psd-replace." + this.pluginId.replaceAll("[^A-Za-z0-9_.-]", "_"));
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy()
        );
    }

    /**
     * Schedules one explicit-target replacement for a revision issued by this plugin's registry.
     *
     * @return a plugin-facing stage that completes once the outcome is known
     */
    public CompletionStage<PsdReplaceResult> replaceRawImagePsd(
        final PsdReplaceHost host,
        final RawImageId target,
        final PsdEditFile file,
        final PsdFileRevision revision
    ) {
        final PsdReplaceHost replaceHost = Objects.requireNonNull(host, "host");
        final RawImageId requested = Objects.requireNonNull(target, "target");
        final PsdEditFile handle = Objects.requireNonNull(file, "file");
        final PsdFileRevision token = Objects.requireNonNull(revision, "revision");
        final PluginCompletionFuture<PsdReplaceResult> completion = new PluginCompletionFuture<>(
            continuationDispatcher,
            this::acceptsContinuation
        );
        final AtomicBoolean settled = new AtomicBoolean(false);

        final PsdStableSnapshot.Snapshot stage;
        try {
            requireOperational();
            checkPermissions();
            // Resolution happens before queueing so a forged, foreign or retired token is rejected
            // without occupying the native lane.
            stage = registry.requireRevision(bindingOf(handle), handle, token);
        } catch (CubismPermissionException denied) {
            settle(completion, settled, rejected(requested, "PERMISSION_DENIED"));
            return completion.stage();
        } catch (InactiveOperation inactive) {
            settle(completion, settled, unavailable(requested, "SERVICE_UNAVAILABLE"));
            return completion.stage();
        } catch (SecurityException foreign) {
            settle(completion, settled, rejected(requested, "HANDLE_OR_REVISION_REJECTED"));
            return completion.stage();
        } catch (RuntimeException failure) {
            settle(completion, settled, failed(requested, "REQUEST_REJECTED"));
            return completion.stage();
        }

        try {
            executor.execute(() -> settle(
                completion, settled, execute(replaceHost, requested, handle, token, stage)));
        } catch (RejectedExecutionException queueRejected) {
            settle(completion, settled, rejected(requested, "QUEUE_REJECTED"));
        }
        return completion.stage();
    }

    /**
     * Closes this service without interrupting a native call. Queued work fails its next runtime
     * admission check and performs no further native mutation.
     */
    @Override
    public void close() {
        if (active.compareAndSet(true, false)) {
            executor.shutdown();
        }
    }

    private PsdReplaceResult execute(
        final PsdReplaceHost host,
        final RawImageId target,
        final PsdEditFile handle,
        final PsdFileRevision token,
        final PsdStableSnapshot.Snapshot stage
    ) {
        final PsdEditRegistry.Binding binding;
        try {
            requireOperational();
            checkPermissions();
            binding = bindingOf(handle);
            // Re-resolve after queueing: the revision may have been retired or superseded meanwhile.
            registry.requireRevision(binding, handle, token);
        } catch (CubismPermissionException denied) {
            return rejected(target, "PERMISSION_DENIED");
        } catch (InactiveOperation inactive) {
            return unavailable(target, "SERVICE_UNAVAILABLE");
        } catch (SecurityException foreign) {
            return rejected(target, "REVISION_REJECTED");
        } catch (RuntimeException failure) {
            return failed(target, "WORKER_ADMISSION_FAILURE");
        }

        final PsdReplaceHost.Replacement replacement;
        try {
            replacement = Objects.requireNonNull(
                host.replaceWithStagedPsd(
                    target,
                    stage.path(),
                    registry.requireFile(binding, handle).fileName(),
                    () -> {
                        requireOperational();
                        checkPermissions();
                        registry.requireRevision(binding, handle, token);
                    }
                ),
                "native replacement"
            );
        } catch (CubismPermissionException denied) {
            return rejected(target, "PERMISSION_DENIED");
        } catch (InactiveOperation inactive) {
            return unavailable(target, "SERVICE_UNAVAILABLE");
        } catch (SecurityException foreign) {
            return rejected(target, "TARGET_REJECTED");
        } catch (RuntimeException nativeFailure) {
            // The projection could not report whether the native mutation happened.
            return partialFailure(target, "NATIVE_OUTCOME_UNKNOWN");
        }

        try {
            requireOperational();
            checkPermissions();
        } catch (CubismPermissionException denied) {
            return rejected(target, "PERMISSION_DENIED");
        } catch (InactiveOperation inactive) {
            return unavailable(target, "SERVICE_UNAVAILABLE");
        } catch (RuntimeException failure) {
            return failed(target, "COMPLETION_ADMISSION_FAILURE");
        }

        return classify(target, handle, token, replacement);
    }

    private PsdReplaceResult classify(
        final RawImageId target,
        final PsdEditFile handle,
        final PsdFileRevision token,
        final PsdReplaceHost.Replacement replacement
    ) {
        final String status = safeToken(replacement.nativeStatus());
        if ("UNAVAILABLE".equals(status)) {
            return unavailable(target, status);
        }
        if (replacement.editingRejected()) {
            return rejected(target, "HOST_EDIT_IN_PROGRESS");
        }
        if (!replacement.nativeReturned()) {
            if (replacement.mutationUnknown()) {
                return partialFailure(target, "NATIVE_MUTATION_UNKNOWN");
            }
            final String detail = replacement.failure().map(failure ->
                ";phase=" + safeToken(failure.phase()) + ";category=" + safeToken(failure.category())).orElse("");
            return new PsdReplaceResult(PsdReplaceResult.Status.FAILED,
                "PSD_NATIVE_REPLACE;status=" + status + detail, target, Optional.empty(), Optional.empty(),
                Optional.empty());
        }
        if (!replacement.sessionCurrent()) {
            // The session changed across the native call: the outcome cannot be attributed.
            return partialFailure(target, "SESSION_CHANGED_ACROSS_NATIVE");
        }
        if (!replacement.relationsAvailable()) {
            // A native return alone is not proof of application, and the mutation is unobservable.
            return partialFailure(target, "POST_REPLACEMENT_STATE_UNOBSERVED");
        }
        if (replacement.afterRawImageId().isEmpty()) {
            return partialFailure(target, "POST_REPLACEMENT_TARGET_ABSENT");
        }
        try {
            registry.retireRevision(bindingOf(handle), handle, token);
        } catch (SecurityException raceLost) {
            return failed(target, "REVISION_RETIRED_BEFORE_COMPLETION");
        }
        return new PsdReplaceResult(
            PsdReplaceResult.Status.APPLIED,
            "PSD_NATIVE_REPLACE;status=" + status + ";observed=applied",
            target,
            replacement.afterRawImageId(),
            Optional.of(token),
            Optional.empty()
        );
    }

    private PsdEditRegistry.Binding bindingOf(final PsdEditFile handle) {
        if (!(handle instanceof RuntimePsdEditFile owned)) {
            throw new SecurityException("PSD edit file is not an instance issued by this runtime");
        }
        return owned.binding();
    }

    private void settle(
        final PluginCompletionFuture<PsdReplaceResult> completion,
        final AtomicBoolean settled,
        final PsdReplaceResult result
    ) {
        if (!settled.compareAndSet(false, true)) return;
        final Runnable publication = () -> completion.settle(result);
        try {
            continuationDispatcher.accept(publication);
        } catch (RuntimeException dispatcherFailure) {
            completion.settle(result);
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
        permissionChecker.check(PermissionIds.TURBOISM_CUBISM_MODEL_WRITE, OPERATION);
        permissionChecker.check(PermissionIds.TURBOISM_FILE_READ, OPERATION);
        permissionChecker.check(PermissionIds.TURBOISM_FILE_WRITE, OPERATION);
    }

    private static PsdReplaceResult unavailable(final RawImageId target, final String status) {
        return new PsdReplaceResult(
            PsdReplaceResult.Status.UNAVAILABLE,
            "PSD_NATIVE_REPLACE;status=" + safeToken(status),
            target,
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
        );
    }

    private static PsdReplaceResult rejected(final RawImageId target, final String status) {
        return new PsdReplaceResult(
            PsdReplaceResult.Status.REJECTED,
            "PSD_NATIVE_REPLACE;status=" + safeToken(status),
            target,
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
        );
    }

    private static PsdReplaceResult failed(final RawImageId target, final String status) {
        return new PsdReplaceResult(
            PsdReplaceResult.Status.FAILED,
            "PSD_NATIVE_REPLACE;status=" + safeToken(status),
            target,
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
        );
    }

    private static PsdReplaceResult partialFailure(final RawImageId target, final String status) {
        return new PsdReplaceResult(
            PsdReplaceResult.Status.PARTIAL_FAILURE,
            "PSD_NATIVE_REPLACE;status=" + safeToken(status) + ";pause=automatic-import",
            target,
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
        );
    }

    /** Diagnostics must never echo native text, a path or an exception message. */
    private static String safeToken(final String value) {
        final String candidate = value == null ? "" : value;
        return candidate.matches("[A-Z][A-Z0-9_]{0," + (MAX_TOKEN_LENGTH - 1) + "}")
            ? candidate
            : "UNKNOWN";
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    private static final class InactiveOperation extends RuntimeException {
        private InactiveOperation() {
            super(null, null, false, false);
        }
    }
}
