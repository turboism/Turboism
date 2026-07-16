package dev.turboism.task;

import dev.turboism.core.runtime.CallbackExecutionStatus;
import dev.turboism.core.runtime.CallbackSubmission;
import dev.turboism.core.runtime.PluginTask;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.RuntimeSchedulerLease;
import dev.turboism.cleanup.CleanupEvidenceCollector;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.task.FixedDelayTaskRequest;
import dev.turboism.sdk.task.PluginTaskRequest;
import dev.turboism.sdk.task.PluginTaskScheduler;
import dev.turboism.sdk.task.TaskId;
import dev.turboism.sdk.task.TaskRejectionReason;
import dev.turboism.sdk.task.TaskSubmission;
import dev.turboism.sdk.task.TaskSubmissionStatus;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/** Plugin-scoped task facade over the existing bounded RuntimeScheduler. */
public final class RuntimePluginTaskScheduler implements PluginTaskScheduler, AutoCloseable {

    private static final String NO_CAPABILITY = "none";
    private static final int ACTIVE_TASK_LIMIT = 64;

    private final Object admissionLock = new Object();
    private final String pluginId;
    private final RuntimeScheduler runtimeScheduler;
    private final DisposableScope disposableScope;
    private final RuntimeTaskCompletionDispatcher completionDispatcher;
    private final RuntimeSchedulerLease schedulerLease;
    private final Map<TaskId, AbstractRuntimeTaskHandle> activeTasks = new ConcurrentHashMap<>();
    private final Semaphore activeTaskPermits = new Semaphore(ACTIVE_TASK_LIMIT);
    private final CleanupEvidenceCollector cleanupEvidence;
    private boolean active = true;

    public RuntimePluginTaskScheduler(
        final String pluginId,
        final RuntimeScheduler runtimeScheduler,
        final DisposableScope disposableScope
    ) {
        this(pluginId, runtimeScheduler, disposableScope, new CleanupEvidenceCollector());
    }

    public RuntimePluginTaskScheduler(
        final String pluginId,
        final RuntimeScheduler runtimeScheduler,
        final DisposableScope disposableScope,
        final CleanupEvidenceCollector cleanupEvidence
    ) {
        this.pluginId = requireText(pluginId, "pluginId");
        this.runtimeScheduler = Objects.requireNonNull(runtimeScheduler, "runtimeScheduler");
        this.disposableScope = Objects.requireNonNull(disposableScope, "disposableScope");
        this.cleanupEvidence = Objects.requireNonNull(cleanupEvidence, "cleanupEvidence");
        this.schedulerLease = this.runtimeScheduler.acquirePluginTaskSchedulerLease();
        this.completionDispatcher = new RuntimeTaskCompletionDispatcher(
            this.pluginId,
            this.runtimeScheduler,
            this.cleanupEvidence
        );
        try {
            disposableScope.register(this);
        } catch (RuntimeException exception) {
            schedulerLease.close();
            throw exception;
        }
    }

    @Override
    public TaskSubmission submit(final PluginTaskRequest request) {
        Objects.requireNonNull(request, "request");
        final PendingTaskOwnership ownership = registerPendingOwnership();
        if (ownership == null) {
            return rejected(request.id(), TaskRejectionReason.PLUGIN_INACTIVE);
        }
        synchronized (admissionLock) {
            if (!active || ownership.isClosed()) {
                ownership.disarm();
                return rejected(request.id(), TaskRejectionReason.PLUGIN_INACTIVE);
            }
            if (!activeTaskPermits.tryAcquire()) {
                ownership.disarm();
                return rejected(request.id(), TaskRejectionReason.BACKPRESSURE);
            }
            final ActiveTaskAdmission admission = new ActiveTaskAdmission(
                request.id(),
                activeTasks,
                activeTaskPermits
            );
            final OneShotTaskHandle handle = new OneShotTaskHandle(
                request.id(),
                request.action(),
                admission::release,
                completionDispatcher::dispatchTaskHandleSettlement,
                completionDispatcher::dispatchPluginContinuation
            );
            admission.bind(handle);
            ownership.ownCandidate(handle);
            if (ownership.isClosed()) {
                admission.release();
                return rejected(request.id(), TaskRejectionReason.PLUGIN_INACTIVE);
            }
            if (activeTasks.putIfAbsent(request.id(), handle) != null) {
                admission.release();
                ownership.disarm();
                return rejected(request.id(), TaskRejectionReason.DUPLICATE_ACTIVE_ID);
            }
            if (ownership.isClosed()) {
                admission.release();
                return rejected(request.id(), TaskRejectionReason.PLUGIN_INACTIVE);
            }
            final CallbackSubmission callback = runtimeScheduler.submitLightweight(
                runtimeTask(request),
                handle.token(),
                handle::runAction
            );
            if (!callback.accepted()) {
                admission.release();
                ownership.disarm();
                return rejected(request.id(), mapRejection(callback.rejectionStatus()));
            }
            ownership.bind();
            callback.completion().whenComplete((result, failure) -> {
                if (failure != null || result == null) {
                    handle.observeExecution(new dev.turboism.core.runtime.CallbackExecutionResult(
                        CallbackExecutionStatus.FAILED,
                        "TASK_RUNTIME_FAILURE"
                    ));
                } else {
                    handle.observeExecution(result);
                }
            });
            return accepted(handle);
        }
    }

    @Override
    public TaskSubmission scheduleWithFixedDelay(final FixedDelayTaskRequest request) {
        Objects.requireNonNull(request, "request");
        final PendingTaskOwnership ownership = registerPendingOwnership();
        if (ownership == null) {
            return rejected(request.id(), TaskRejectionReason.PLUGIN_INACTIVE);
        }
        synchronized (admissionLock) {
            if (!active || ownership.isClosed()) {
                ownership.disarm();
                return rejected(request.id(), TaskRejectionReason.PLUGIN_INACTIVE);
            }
            if (!activeTaskPermits.tryAcquire()) {
                ownership.disarm();
                return rejected(request.id(), TaskRejectionReason.BACKPRESSURE);
            }
            final ActiveTaskAdmission admission = new ActiveTaskAdmission(
                request.id(),
                activeTasks,
                activeTaskPermits
            );
            final FixedDelayTaskHandle handle = new FixedDelayTaskHandle(
                request.id(),
                runtimeScheduler,
                runtimeTask(request),
                request.delay(),
                request.action(),
                admission::release,
                completionDispatcher::dispatchTaskHandleSettlement,
                completionDispatcher::dispatchPluginContinuation
            );
            admission.bind(handle);
            ownership.ownCandidate(handle);
            if (ownership.isClosed()) {
                admission.release();
                return rejected(request.id(), TaskRejectionReason.PLUGIN_INACTIVE);
            }
            if (activeTasks.putIfAbsent(request.id(), handle) != null) {
                admission.release();
                ownership.disarm();
                return rejected(request.id(), TaskRejectionReason.DUPLICATE_ACTIVE_ID);
            }
            if (ownership.isClosed()) {
                admission.release();
                return rejected(request.id(), TaskRejectionReason.PLUGIN_INACTIVE);
            }
            if (!handle.start(request.initialDelay())) {
                admission.release();
                ownership.disarm();
                return rejected(request.id(), TaskRejectionReason.BACKPRESSURE);
            }
            ownership.bind();
            return accepted(handle);
        }
    }

    @Override
    public void close() {
        final ArrayList<AbstractRuntimeTaskHandle> toCancel;
        synchronized (admissionLock) {
            if (active) {
                active = false;
                toCancel = new ArrayList<>(activeTasks.values());
            } else {
                toCancel = new ArrayList<>();
            }
        }
        completionDispatcher.beginCleanup();
        toCancel.forEach(handle -> {
            if (handle.cancel()) {
                cleanupEvidence.taskHandleCanceled();
            }
        });
        completionDispatcher.closeAndAwaitQuiescence();
        schedulerLease.close();
    }

    public void dispatchContinuation(final Runnable continuation) {
        completionDispatcher.dispatchPluginContinuation(
            Objects.requireNonNull(continuation, "continuation")
        );
    }

    public void awaitContinuationQuiescence() {
        completionDispatcher.awaitQuiescence();
    }

    public void awaitContinuationQuiescence(final Duration timeout) {
        completionDispatcher.awaitQuiescence(Objects.requireNonNull(timeout, "timeout"));
    }

    int activeTaskCount() {
        return activeTasks.size();
    }

    int pendingCompletionCount() {
        return completionDispatcher.pendingCount();
    }

    int availableActiveTaskPermits() {
        return activeTaskPermits.availablePermits();
    }

    private PendingTaskOwnership registerPendingOwnership() {
        final PendingTaskOwnership ownership = new PendingTaskOwnership(
            completionDispatcher::beginCleanup,
            cleanupEvidence::taskHandleCanceled
        );
        try {
            ownership.attachRegistration(disposableScope.register(ownership));
            return ownership;
        } catch (IllegalStateException exception) {
            ownership.disarm();
            return null;
        }
    }

    private PluginTask runtimeTask(final PluginTaskRequest request) {
        return new PluginTask(
            runtimeTaskType(request.kind(), request.priority()),
            pluginId,
            "plugin task " + request.id().value(),
            NO_CAPABILITY
        );
    }

    private PluginTask runtimeTask(final FixedDelayTaskRequest request) {
        return new PluginTask(
            runtimeTaskType(request.kind(), request.priority()),
            pluginId,
            "plugin task " + request.id().value(),
            NO_CAPABILITY
        );
    }

    private static String runtimeTaskType(
        final dev.turboism.sdk.task.PluginTaskKind kind,
        final dev.turboism.sdk.task.PluginTaskPriority priority
    ) {
        final String category = switch (kind) {
            case COMPUTE -> "compute";
            case LOW_FREQUENCY_REFRESH -> "refresh";
        };
        final String level = switch (priority) {
            case NORMAL -> "normal";
            case LOW -> "low";
        };
        return "plugin." + category + "." + level;
    }

    private static TaskSubmission accepted(final AbstractRuntimeTaskHandle handle) {
        return new TaskSubmission(
            TaskSubmissionStatus.ACCEPTED,
            handle,
            Optional.empty()
        );
    }

    private TaskSubmission rejected(
        final TaskId id,
        final TaskRejectionReason reason
    ) {
        return new TaskSubmission(
            TaskSubmissionStatus.REJECTED,
            new RejectedTaskHandle(
                id,
                reason,
                completionDispatcher::dispatchPluginContinuation
            ),
            Optional.of(reason)
        );
    }

    private static TaskRejectionReason mapRejection(final CallbackExecutionStatus status) {
        return switch (status) {
            case REJECTED_BACKPRESSURE -> TaskRejectionReason.BACKPRESSURE;
            case REJECTED_CIRCUIT_OPEN -> TaskRejectionReason.CIRCUIT_OPEN;
            case POLICY_REJECTED -> TaskRejectionReason.POLICY_REJECTED;
            case RUNTIME_UNAVAILABLE -> TaskRejectionReason.RUNTIME_UNAVAILABLE;
            case FAILED, TIMED_OUT, SUCCEEDED -> TaskRejectionReason.RUNTIME_UNAVAILABLE;
        };
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
