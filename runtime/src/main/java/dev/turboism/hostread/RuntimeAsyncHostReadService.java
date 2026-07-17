package dev.turboism.hostread;

import dev.turboism.sdk.hostread.AsyncHostReadError;
import dev.turboism.sdk.hostread.AsyncHostReadErrorCode;
import dev.turboism.sdk.hostread.AsyncHostReadHandle;
import dev.turboism.sdk.hostread.AsyncHostReadIntent;
import dev.turboism.sdk.hostread.AsyncHostReadRequest;
import dev.turboism.sdk.hostread.AsyncHostReadService;
import dev.turboism.sdk.hostread.AsyncHostReadSubmission;
import dev.turboism.sdk.hostread.AsyncHostReadSubmissionStatus;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.task.RuntimePluginTaskScheduler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Plugin-scoped admission, permission, coalescing, handle, and lifecycle owner. */
public final class RuntimeAsyncHostReadService implements AsyncHostReadService, AutoCloseable {

    public static final String PROJECT_READ_PERMISSION = "turboism.cubism.project.read";
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);
    private static final String QUIESCENCE_FAILURE =
        "Async host-read operations did not quiesce before plugin scope close.";

    private final String pluginId;
    private final Set<String> permissions;
    private final ProjectWorkspaceHostReadSource source;
    private final SharedAsyncHostReadLane lane;
    private final RuntimePluginTaskScheduler tasks;
    private final Object lifecycleLock = new Object();
    private final Duration closeTimeout;
    private final Map<AsyncHostReadIntent, RuntimeAsyncHostReadHandle> active =
        new EnumMap<>(AsyncHostReadIntent.class);
    private boolean open = true;
    private int physicalOperations;

    public RuntimeAsyncHostReadService(
        final String pluginId,
        final Set<String> permissions,
        final ProjectWorkspaceHostReadSource source,
        final SharedAsyncHostReadLane lane,
        final RuntimePluginTaskScheduler tasks,
        final DisposableScope scope
    ) {
        this(pluginId, permissions, source, lane, tasks, scope, CLOSE_TIMEOUT);
    }

    RuntimeAsyncHostReadService(
        final String pluginId,
        final Set<String> permissions,
        final ProjectWorkspaceHostReadSource source,
        final SharedAsyncHostReadLane lane,
        final RuntimePluginTaskScheduler tasks,
        final DisposableScope scope,
        final Duration closeTimeout
    ) {
        this.pluginId = requireText(pluginId, "pluginId");
        this.permissions = Set.copyOf(Objects.requireNonNull(permissions, "permissions"));
        this.source = Objects.requireNonNull(source, "source");
        this.lane = Objects.requireNonNull(lane, "lane");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.closeTimeout = requirePositive(closeTimeout, "closeTimeout");
        Objects.requireNonNull(scope, "scope").register(this);
    }

    @Override
    public AsyncHostReadSubmission submit(final AsyncHostReadRequest request) {
        final AsyncHostReadRequest validated = Objects.requireNonNull(request, "request");
        synchronized (lifecycleLock) {
            if (!open || lane.isClosed()) {
                return rejected(AsyncHostReadErrorCode.RUNTIME_UNAVAILABLE);
            }
            if (!permissions.contains(PROJECT_READ_PERMISSION)) {
                return rejected(AsyncHostReadErrorCode.PERMISSION_DENIED);
            }
            final RuntimeAsyncHostReadHandle existing = active.get(validated.intent());
            if (existing != null) {
                return new AsyncHostReadSubmission(
                    AsyncHostReadSubmissionStatus.COALESCED,
                    Optional.of(existing),
                    Optional.empty()
                );
            }
            final RuntimeAsyncHostReadHandle handle = new RuntimeAsyncHostReadHandle(
                this,
                validated.intent()
            );
            active.put(validated.intent(), handle);
            final SharedAsyncHostReadLane.Admission admission = lane.admit(
                handle,
                validated.timeout(),
                () -> execute(handle)
            );
            if (admission != SharedAsyncHostReadLane.Admission.ACCEPTED) {
                active.remove(validated.intent(), handle);
                return rejected(admission == SharedAsyncHostReadLane.Admission.BACKPRESSURE
                    ? AsyncHostReadErrorCode.BACKPRESSURE
                    : AsyncHostReadErrorCode.RUNTIME_UNAVAILABLE);
            }
            return new AsyncHostReadSubmission(
                AsyncHostReadSubmissionStatus.ACCEPTED,
                Optional.of(handle),
                Optional.empty()
            );
        }
    }

    public boolean cancel(final AsyncHostReadHandle handle) {
        Objects.requireNonNull(handle, "handle");
        if (!(handle instanceof RuntimeAsyncHostReadHandle runtimeHandle)) {
            throw new IllegalArgumentException(
                "Handle was not issued by the runtime async host-read service."
            );
        }
        return cancelOwned(runtimeHandle);
    }

    @Override
    public void close() {
        final ArrayList<RuntimeAsyncHostReadHandle> toCancel;
        synchronized (lifecycleLock) {
            if (!open) {
                return;
            }
            open = false;
            toCancel = new ArrayList<>(active.values());
        }
        toCancel.forEach(RuntimeAsyncHostReadHandle::cancelInternal);
        final long deadline = System.nanoTime() + closeTimeout.toNanos();
        awaitPhysicalQuiescence(deadline);
        tasks.awaitContinuationQuiescence(remaining(deadline));
    }

    boolean acceptsContinuations() {
        synchronized (lifecycleLock) {
            return open;
        }
    }

    void dispatchContinuation(final Runnable continuation) {
        tasks.dispatchContinuation(Objects.requireNonNull(continuation, "continuation"));
    }

    boolean cancelOwned(final RuntimeAsyncHostReadHandle handle) {
        if (handle == null || !handle.ownedBy(this)) {
            throw new IllegalArgumentException(
                "Handle belongs to another async host-read service."
            );
        }
        return handle.cancelInternal();
    }

    void operationFinished(final RuntimeAsyncHostReadHandle handle) {
        synchronized (lifecycleLock) {
            active.remove(handle.intent(), handle);
        }
    }

    void physicalAdmitted() {
        synchronized (lifecycleLock) {
            physicalOperations++;
        }
    }

    void physicalExited() {
        synchronized (lifecycleLock) {
            if (physicalOperations <= 0) {
                throw new IllegalStateException("Async host-read physical operation accounting underflow.");
            }
            physicalOperations--;
            lifecycleLock.notifyAll();
        }
    }

    AsyncHostReadError error(final AsyncHostReadErrorCode code) {
        final String message = switch (code) {
            case CAPABILITY_UNAVAILABLE -> "Host read capability is unavailable.";
            case PERMISSION_DENIED -> "Permission denied for async host read.";
            case HOST_VERSION_UNSUPPORTED -> "Host version is unsupported.";
            case MAPPING_NOT_VERIFIED -> "Required host mapping is not verified.";
            case VALIDATION_FAILURE -> "Host read result failed validation.";
            case TIMEOUT -> "Host read timed out.";
            case CANCELED -> "Host read was canceled.";
            case BACKPRESSURE -> "Async host read queue is full.";
            case RUNTIME_UNAVAILABLE -> "Async host read runtime is unavailable.";
            case RUNTIME_FAILURE -> "Async host read failed safely.";
        };
        return new AsyncHostReadError(code, message);
    }

    private void execute(final RuntimeAsyncHostReadHandle handle) {
        try {
            final ProjectWorkspaceHostReadResult result = Objects.requireNonNull(
                source.read(),
                "project/workspace host read result"
            );
            if (result.value().isPresent()) {
                handle.succeed(result.value().orElseThrow());
            } else {
                handle.fail(error(result.errorCode().orElseThrow()));
            }
        } catch (RuntimeException exception) {
            handle.fail(error(AsyncHostReadErrorCode.RUNTIME_FAILURE));
        }
    }

    private void awaitPhysicalQuiescence(final long deadline) {
        synchronized (lifecycleLock) {
            while (physicalOperations > 0) {
                final long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    throw new IllegalStateException(QUIESCENCE_FAILURE);
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(lifecycleLock, remaining);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(QUIESCENCE_FAILURE, exception);
                }
            }
        }
    }

    private static Duration remaining(final long deadline) {
        final long nanos = deadline - System.nanoTime();
        if (nanos <= 0L) {
            throw new IllegalStateException(QUIESCENCE_FAILURE);
        }
        return Duration.ofNanos(nanos);
    }

    private static Duration requirePositive(final Duration value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private AsyncHostReadSubmission rejected(final AsyncHostReadErrorCode code) {
        return new AsyncHostReadSubmission(
            AsyncHostReadSubmissionStatus.REJECTED,
            Optional.empty(),
            Optional.of(error(code))
        );
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
