package dev.turboism.core.runtime.work;

import java.util.Objects;

public record PluginWorkResult(
    PluginWorkStatus status,
    String failureCode
) {
    public PluginWorkResult {
        status = Objects.requireNonNull(status, "status");
        failureCode = failureCode == null ? "" : failureCode;
    }

    public static PluginWorkResult succeeded() {
        return new PluginWorkResult(PluginWorkStatus.SUCCEEDED, "");
    }
}
