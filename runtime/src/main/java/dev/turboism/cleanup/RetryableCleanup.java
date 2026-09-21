package dev.turboism.cleanup;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Runs independent cleanup stages to completion, retaining only failed stages for a later retry. */
public final class RetryableCleanup implements AutoCloseable {
    private final String message;
    private final List<Step> steps = new ArrayList<>();
    private boolean running;

    /**
     * Creates an ordered cleanup sequence. Each stage must support retry after a failed close.
     *
     * @param message diagnostic description used when non-fatal cleanup fails
     * @param actions independent cleanup stages, in execution order
     */
    public RetryableCleanup(final String message, final AutoCloseable... actions) {
        this.message = Objects.requireNonNull(message, "message");
        for (AutoCloseable action : Objects.requireNonNull(actions, "actions")) {
            steps.add(new Step(Objects.requireNonNull(action, "action")));
        }
    }

    /**
     * Attempts every pending stage, including after an Error. Successful stages are never repeated.
     * Reentrant close calls return to the active pass. A fatal error remains the primary failure;
     * other failures are suppressed on it. Ordinary failures are reported with a diagnostic wrapper.
     */
    @Override public synchronized void close() {
        if (running) return;
        running = true;
        Throwable failure = null;
        try {
            for (Step step : steps) {
                if (step.action == null) continue;
                try {
                    step.action.close();
                    step.action = null;
                } catch (Throwable next) {
                    failure = append(failure, next);
                }
            }
        } finally {
            running = false;
        }
        if (failure instanceof Error fatal) throw fatal;
        if (failure != null) throw new IllegalStateException(message, failure);
    }

    /** Returns true only after every stage has completed successfully. */
    public synchronized boolean complete() {
        return steps.stream().allMatch(step -> step.action == null);
    }

    private static Throwable append(final Throwable first, final Throwable next) {
        if (first == null) return next;
        if (first == next) return first;
        if (priority(next) > priority(first)) {
            next.addSuppressed(first);
            return next;
        }
        first.addSuppressed(next);
        return first;
    }

    private static int priority(final Throwable failure) {
        if (failure instanceof VirtualMachineError || failure instanceof ThreadDeath) return 2;
        return failure instanceof Error ? 1 : 0;
    }

    private static final class Step {
        private AutoCloseable action;
        private Step(final AutoCloseable action) { this.action = action; }
    }
}
