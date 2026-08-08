package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.id.ParameterId;

import java.util.List;
import java.util.Objects;

/** Immutable model-level plan for transferring selected object bindings between parameters. */
@PreviewApi
public record ParameterBindingTransferPlan(
    ParameterId sourceParameterId,
    ParameterId targetParameterId,
    List<ParameterBindingTarget> targets,
    boolean invertAfterTransfer
) {
    public ParameterBindingTransferPlan {
        sourceParameterId = Objects.requireNonNull(sourceParameterId, "sourceParameterId");
        targetParameterId = Objects.requireNonNull(targetParameterId, "targetParameterId");
        targets = List.copyOf(Objects.requireNonNull(targets, "targets"));
        if (sourceParameterId.equals(targetParameterId)) {
            throw new IllegalArgumentException("source and target parameters must differ");
        }
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("targets must not be empty");
        }
        if (targets.stream().distinct().count() != targets.size()) {
            throw new IllegalArgumentException("targets must not contain duplicates");
        }
    }
}
