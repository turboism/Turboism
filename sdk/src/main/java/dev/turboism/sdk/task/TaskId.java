package dev.turboism.sdk.task;

public record TaskId(String value) {
    public TaskId {
        value = TaskContracts.requireText(
            value,
            "value",
            TaskContracts.MAX_TASK_ID_LENGTH
        );
    }
}
