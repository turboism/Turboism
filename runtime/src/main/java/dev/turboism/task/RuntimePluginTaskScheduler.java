package dev.turboism.task;

import dev.turboism.core.runtime.work.PluginWorkResult;
import dev.turboism.core.runtime.work.PluginWorkStatus;
import dev.turboism.core.runtime.work.PluginWorkSubmission;
import dev.turboism.core.runtime.PluginTask;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.RuntimeSchedulerLease;
import dev.turboism.cleanup.CleanupEvidenceCollector;
import dev.turboism.failure.RuntimeFailure;
import dev.turboism.failure.RuntimeFailureDomain;
import dev.turboism.failure.RuntimeFailureSink;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.task.FixedDelayTaskRequest;
import dev.turboism.sdk.task.PluginTaskRequest;
import dev.turboism.sdk.task.PluginTaskScheduler;
import dev.turboism.sdk.task.TaskId;
import dev.turboism.sdk.task.TaskOutcome;
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
    private static final String SUBMIT_OPERATION = "task.submit";
    private static final String SCHEDULE_OPERATION = "task.schedule";
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
    private final RuntimeFailureSink failureSink;
    private boolean active = true;

    public RuntimePluginTaskScheduler(
        final String pluginId,
        final RuntimeScheduler runtimeScheduler,
        final DisposableScope disposableScope
    ) {
        this(
            pluginId,
            runtimeScheduler,
            disposableScope,
            new CleanupEvidenceCollector(),
            RuntimeFailureSink.noop()
        );
    }

    public RuntimePluginTaskScheduler(
        final String pluginId,
        final RuntimeScheduler runtimeScheduler,
        final DisposableScope disposableScope,
        final CleanupEvidenceCollector cleanupEvidence
    ) {
        this(
            pluginId,
            runtimeScheduler,
            disposableScope,
            cleanupEvidence,
            RuntimeFailureSink.noop()
        );
    }

    public RuntimePluginTaskScheduler(
        final String pluginId,
        final RuntimeScheduler runtimeScheduler,
        final DisposableScope disposableScope,
        final CleanupEvidenceCollector cleanupEvidence,
        final RuntimeFailureSink failureSink
    ) {
        this.pluginId = requireText(pluginId, "pluginId");
        this.runtimeScheduler = Objects.requireNonNull(runtimeScheduler, "runtimeScheduler");
        this.disposableScope = Objects.requireNonNull(disposableScope, "disposableScope");
        this.cleanupEvidence = Objects.requireNonNull(cleanupEvidence, "cleanupEvidence");
        this.failureSink = RuntimeFailureSink.require(failureSink);
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
            if (!active || ownership.isCloseRequested()) {
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
                () -> {
                    admission.release();
                    ownership.releaseAfterTerminal();
                },
                outcome -> recordTerminalFailure(SUBMIT_OPERATION, outcome),
                completionDispatcher::dispatchTaskHandleSettlement,
                completionDispatcher::dispatchPluginContinuation
            );
            admission.bind(handle);
            ownership.ownCandidate(handle);
            if (ownership.isCloseRequested()) {
                admission.release();
                ownership.disarm();
                return rejected(request.id(), TaskRejectionReason.PLUGIN_INACTIVE);
            }
            if (activeTasks.putIfAbsent(request.id(), handle) != null) {
                admission.release();
                ownership.disarm();
                return rejected(request.id(), TaskRejectionReason.DUPLICATE_ACTIVE_ID);
            }
            if (ownership.isCloseRequested()) {
                admission.release();
                ownership.disarm();
                return rejected(request.id(), TaskRejectionReason.PLUGIN_INACTIVE);
            }
            final PluginWorkSubmission submission = runtimeScheduler.submitLightweight(
                runtimeTask(request),
                handle.token(),
                () -> ownership.runWhenBound(handle::runAction)
            );
            if (!submission.accepted()) {
                admission.release();
                final boolean closeRequested = ownership.isCloseRequested();
                ownership.disarm();
                return rejected(
                    request.id(),
                    closeRequested
                        ? TaskRejectionReason.PLUGIN_INACTIVE
                        : mapRejection(submission.rejectionStatus())
                );
            }
            ownership.bind();
            submission.completion().whenComplete((result, failure) -> {
                if (failure != null || result == null) {
                    handle.observeExecution(new PluginWorkResult(
                        PluginWorkStatus.FAILED,
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
            return rejectedScheduled(request.id(), TaskRejectionReason.PLUGIN_INACTIVE);
        }
        synchronized (admissionLock) {
            if (!active || ownership.isCloseRequested()) {
                ownership.disarm();
                return rejectedScheduled(request.id(), TaskRejectionReason.PLUGIN_INACTIVE);
            }
            if (!activeTaskPermits.tryAcquire()) {
                ownership.disarm();
                return rejectedScheduled(request.id(), TaskRejectionReason.BACKPRESSURE);
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
                ownership::runWhenBound,
                request.action(),
                () -> {
                    admission.release();
                    ownership.releaseAfterTerminal();
                },
                outcome -> recordTerminalFailure(SCHEDULE_OPERATION, outcome),
                completionDispatcher::dispatchTaskHandleSettlement,
                completionDispatcher::dispatchPluginContinuation
            );
            admission.bind(handle);
            ownership.ownCandidate(handle);
            if (ownership.isCloseRequested()) {
                admission.release();
                ownership.disarm();
                return rejectedScheduled(request.id(), TaskRejectionReason.PLUGIN_INACTIVE);
            }
            if (activeTasks.putIfAbsent(request.id(), handle) != null) {
                admission.release();
                ownership.disarm();
                return rejectedScheduled(request.id(), TaskRejectionReason.DUPLICATE_ACTIVE_ID);
            }
            if (ownership.isCloseRequested()) {
                admission.release();
                ownership.disarm();
                return rejectedScheduled(request.id(), TaskRejectionReason.PLUGIN_INACTIVE);
            }
            if (!handle.start(request.initialDelay())) {
                admission.release();
                final boolean closeRequested = ownership.isCloseRequested();
                ownership.disarm();
                return rejectedScheduled(
                    request.id(),
                    closeRequested
                        ? TaskRejectionReason.PLUGIN_INACTIVE
                        : TaskRejectionReason.BACKPRESSURE
                );
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
        final Registration registration;
        try {
            registration = disposableScope.register(ownership);
        } catch (IllegalStateException exception) {
            ownership.disarm();
            return null;
        }
        ownership.attachRegistration(registration);
        return ownership;
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
        return rejected(id, reason, SUBMIT_OPERATION);
    }

    private TaskSubmission rejectedScheduled(
        final TaskId id,
        final TaskRejectionReason reason
    ) {
        return rejected(id, reason, SCHEDULE_OPERATION);
    }

    private TaskSubmission rejected(
        final TaskId id,
        final TaskRejectionReason reason,
        final String operationId
    ) {
        failureSink.record(RuntimeFailureDomain.TASK, new RuntimeFailure(
            "TASK_REJECTED_" + reason.name(),
            "ERROR",
            "admission",
            pluginId,
            operationId,
            null,
            "Plugin task submission was rejected safely.",
            null,
            1
        ));
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

    private void recordTerminalFailure(
        final String operationId,
        final TaskOutcome outcome
    ) {
        outcome.failure().ifPresent(failure -> failureSink.record(
            RuntimeFailureDomain.TASK,
            new RuntimeFailure(
                failure.code(),
                "ERROR",
                "execution",
                pluginId,
                operationId,
                null,
                failure.message(),
                null,
                1
            )
        ));
    }

    private static TaskRejectionReason mapRejection(final PluginWorkStatus status) {
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
