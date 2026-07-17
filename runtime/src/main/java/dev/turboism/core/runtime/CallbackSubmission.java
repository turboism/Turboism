package dev.turboism.core.runtime;

import java.util.Objects;
import java.util.concurrent.CompletionStage;

public record CallbackSubmission(
    boolean accepted,
    CallbackExecutionStatus rejectionStatus,
    CompletionStage<CallbackExecutionResult> completion
) {
    public CallbackSubmission {
        rejectionStatus = Objects.requireNonNull(rejectionStatus, "rejectionStatus");
        completion = Objects.requireNonNull(completion, "completion");
        if (accepted && rejectionStatus != CallbackExecutionStatus.SUCCEEDED) {
            throw new IllegalArgumentException("accepted callback must use SUCCEEDED as its neutral admission marker");
        }
        if (!accepted && rejectionStatus == CallbackExecutionStatus.SUCCEEDED) {
            throw new IllegalArgumentException("rejected callback must expose a rejection status");
        }
    }
}
