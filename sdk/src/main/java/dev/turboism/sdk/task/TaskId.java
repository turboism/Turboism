package dev.turboism.sdk.task;

/**
 * Identity of a task within its plugin's scheduler; two active tasks may not share one.
 *
 * @param value non-blank identifier, at most 128 characters
 * @throws NullPointerException if {@code value} is {@code null}
 * @throws IllegalArgumentException if {@code value} is blank or too long
 */
public record TaskId(String value) {
    public TaskId {
        value = TaskContracts.requireText(
            value,
            "value",
            TaskContracts.MAX_TASK_ID_LENGTH
        );
    }
}
