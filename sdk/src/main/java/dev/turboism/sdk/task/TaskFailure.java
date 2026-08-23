package dev.turboism.sdk.task;

/**
 * The reportable detail of a task that did not succeed.
 *
 * <p>Carries no throwable: the failure is reduced to text at the boundary so a plugin failure
 * cannot leak host classes or stack state to observers.
 *
 * @param code stable, machine-comparable failure identifier; non-blank, at most 128 characters
 * @param message human-readable explanation; non-blank, at most 1024 characters
 */
public record TaskFailure(String code, String message) {
    /**
     * Validates the record components.
     *
     * @throws NullPointerException if a component is {@code null}
     * @throws IllegalArgumentException if a component is blank or over its length limit
     */
    public TaskFailure {
        code = TaskContracts.requireText(code, "code", 128);
        message = TaskContracts.requireText(message, "message", 1024);
    }
}
