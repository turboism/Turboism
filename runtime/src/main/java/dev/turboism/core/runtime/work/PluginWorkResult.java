package dev.turboism.core.runtime.work;

import java.util.Objects;

/**
 * The terminal outcome of one unit of plugin work.
 *
 * <p>{@code failureCode} is normalised to the empty string rather than {@code null}, so callers may
 * compare it without a null check; it is empty for a success.
 *
 * @param status how the work ended
 * @param failureCode short stable code naming the failure, empty when there was none
 */
public record PluginWorkResult(
    PluginWorkStatus status,
    String failureCode
) {
    public PluginWorkResult {
        status = Objects.requireNonNull(status, "status");
        failureCode = failureCode == null ? "" : failureCode;
    }

    /**
     * @return the canonical success result: {@link PluginWorkStatus#SUCCEEDED} with no failure code
     */
    public static PluginWorkResult succeeded() {
        return new PluginWorkResult(PluginWorkStatus.SUCCEEDED, "");
    }
}
