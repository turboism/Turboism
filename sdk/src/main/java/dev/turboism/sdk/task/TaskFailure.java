package dev.turboism.sdk.task;

public record TaskFailure(String code, String message) {
    public TaskFailure {
        code = TaskContracts.requireText(code, "code", 128);
        message = TaskContracts.requireText(message, "message", 1024);
    }
}
