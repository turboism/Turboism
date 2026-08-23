package dev.turboism.core.runtime.work;

import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * The answer to a submission attempt: whether the work was admitted, and how to observe its end.
 *
 * <p>The compact constructor enforces the two legal shapes. An accepted submission must carry
 * {@link PluginWorkStatus#SUCCEEDED} as a neutral marker (it says nothing about the eventual
 * outcome, which arrives through {@code completion}); a rejected one must carry a real rejection
 * status and its {@code completion} is already finished. Anything else throws
 * {@link IllegalArgumentException}.
 *
 * @param accepted whether the work was admitted to run
 * @param rejectionStatus the refusal reason, or {@code SUCCEEDED} as a placeholder when accepted
 * @param completion completes with the terminal result; never fails exceptionally
 */
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
