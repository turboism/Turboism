package dev.turboism.sdk.script;

import dev.turboism.sdk.PreviewApi;

import java.util.Objects;
import java.util.Optional;

/** Terminal result of one script execution. */
@PreviewApi
public record ScriptRunResult(
    ScriptExecutionId executionId,
    ScriptRunStatus status,
    String output,
    Optional<ScriptFailure> failure
) {

    public ScriptRunResult {
        executionId = Objects.requireNonNull(executionId, "executionId");
        status = Objects.requireNonNull(status, "status");
        output = Objects.requireNonNull(output, "output");
        failure = Objects.requireNonNull(failure, "failure");
        final boolean failed = status != ScriptRunStatus.SUCCEEDED;
        if (failed != failure.isPresent()) {
            throw new IllegalArgumentException("Non-success script results must carry one failure");
        }
    }

    public static ScriptRunResult success(final ScriptExecutionId id, final String output) {
        return new ScriptRunResult(id, ScriptRunStatus.SUCCEEDED, output, Optional.empty());
    }

    public static ScriptRunResult failure(
        final ScriptExecutionId id,
        final ScriptRunStatus status,
        final String code,
        final String message,
        final String output
    ) {
        if (status == ScriptRunStatus.SUCCEEDED) {
            throw new IllegalArgumentException("Failure result cannot use SUCCEEDED status");
        }
        return new ScriptRunResult(
            id, status, output, Optional.of(new ScriptFailure(code, message))
        );
    }
}
