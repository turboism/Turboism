package dev.turboism.core.runtime;

import java.util.Objects;

/**
 * The answer to a {@link RuntimeScheduler#schedule} call.
 *
 * <p>A handle is always present, so callers never branch on null: a rejected submission carries a
 * no-op handle whose {@code cancel()} returns {@code false}. Rejection happens when the scheduler
 * is closed or the global timer budget is exhausted.
 *
 * @param accepted whether the callback was actually scheduled
 * @param handle handle for the timer; never {@code null}, inert when {@code accepted} is false
 * @throws NullPointerException if {@code handle} is {@code null}
 */
public record RuntimeTimerSubmission(
    boolean accepted,
    RuntimeTimerHandle handle
) {
    public RuntimeTimerSubmission {
        handle = Objects.requireNonNull(handle, "handle");
    }
}
