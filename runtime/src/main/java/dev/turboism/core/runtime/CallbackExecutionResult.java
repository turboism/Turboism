package dev.turboism.core.runtime;

import java.util.Objects;

public record CallbackExecutionResult(
    CallbackExecutionStatus status,
    String failureCode
) {
    public CallbackExecutionResult {
        status = Objects.requireNonNull(status, "status");
        failureCode = failureCode == null ? "" : failureCode;
    }

    public static CallbackExecutionResult succeeded() {
        return new CallbackExecutionResult(CallbackExecutionStatus.SUCCEEDED, "");
    }
}
