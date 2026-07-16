package dev.turboism.task;

import dev.turboism.sdk.task.TaskId;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

final class ActiveTaskAdmission {

    private final TaskId id;
    private final Map<TaskId, AbstractRuntimeTaskHandle> activeTasks;
    private final Semaphore permits;
    private final AtomicBoolean permitHeld = new AtomicBoolean(true);
    private AbstractRuntimeTaskHandle candidate;

    ActiveTaskAdmission(
        final TaskId id,
        final Map<TaskId, AbstractRuntimeTaskHandle> activeTasks,
        final Semaphore permits
    ) {
        this.id = Objects.requireNonNull(id, "id");
        this.activeTasks = Objects.requireNonNull(activeTasks, "activeTasks");
        this.permits = Objects.requireNonNull(permits, "permits");
    }

    void bind(final AbstractRuntimeTaskHandle handle) {
        if (candidate != null) {
            throw new IllegalStateException("Active task admission is already bound");
        }
        candidate = Objects.requireNonNull(handle, "handle");
    }

    void release() {
        final AbstractRuntimeTaskHandle bound = candidate;
        if (bound != null) {
            activeTasks.remove(id, bound);
        }
        if (permitHeld.compareAndSet(true, false)) {
            permits.release();
        }
    }
}
