package dev.turboism.core.runtime.work;

import java.util.Objects;
import java.util.concurrent.CompletionStage;

public record PluginWorkSubmission(
    boolean accepted,
    PluginWorkStatus rejectionStatus,
    CompletionStage<PluginWorkResult> completion
) {
    public PluginWorkSubmission {
        rejectionStatus = Objects.requireNonNull(rejectionStatus, "rejectionStatus");
        completion = Objects.requireNonNull(completion, "completion");
        if (accepted && rejectionStatus != PluginWorkStatus.SUCCEEDED) {
            throw new IllegalArgumentException("accepted work must use SUCCEEDED as its neutral admission marker");
        }
        if (!accepted && rejectionStatus == PluginWorkStatus.SUCCEEDED) {
            throw new IllegalArgumentException("rejected work must expose a rejection status");
        }
    }
}
