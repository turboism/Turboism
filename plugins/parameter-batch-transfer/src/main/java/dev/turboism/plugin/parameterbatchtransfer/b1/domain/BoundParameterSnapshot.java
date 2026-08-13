package dev.turboism.plugin.parameterbatchtransfer.b1.domain;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.ParameterBinding;
import dev.turboism.sdk.cubism.model.ParameterBindingFamily;
import dev.turboism.sdk.cubism.model.ParameterType;

import java.util.Objects;

/**
 * Immutable snapshot of one parameter source for the batch-transfer session.
 *
 * <p>Bound rows carry the owning {@link ParameterBinding} and its binding
 * {@link ParameterBindingFamily}; the model-wide candidate list carries
 * {@code null} bindings and families. The family drives {@code apply} dispatch:
 * BLEND_SHAPE rows transfer through the owner's Morph Targets, KEYFORM_GRID
 * rows through the keyform batch transfer.</p>
 */
@PreviewApi
public record BoundParameterSnapshot(
    ParameterId parameterId,
    String name,
    String label,
    String markers,
    boolean morph,
    boolean combined,
    ParameterBindingFamily family,
    ParameterBinding binding
) {
    public BoundParameterSnapshot {
        parameterId = Objects.requireNonNull(parameterId, "parameterId");
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("label must not be blank");
        }
        markers = markers == null ? "" : markers;
    }

    /** Builds a snapshot from a live parameter; {@code null} parameter yields id-only defaults. */
    public static BoundParameterSnapshot of(final Parameter parameter, final ParameterBinding binding) {
        if (parameter == null) {
            return new BoundParameterSnapshot(
                binding == null ? null : binding.parameterId(),
                null,
                binding == null ? "" : binding.parameterId().value(),
                "",
                false,
                false,
                binding == null ? null : binding.family(),
                binding
            );
        }
        final String name = parameter.name().orElse(null);
        final String label = name != null && !name.isBlank()
            ? name + "(" + parameter.id().value() + ")"
            : parameter.id().value();
        final StringBuilder markers = new StringBuilder();
        if (parameter.type() == ParameterType.BLEND_SHAPE) {
            markers.append('M');
        }
        if (parameter.combined().orElse(false)) {
            markers.append('C');
        }
        return new BoundParameterSnapshot(
            parameter.id(),
            name,
            label,
            markers.toString(),
            parameter.type() == ParameterType.BLEND_SHAPE,
            parameter.combined().orElse(false),
            binding == null ? null : binding.family(),
            binding
        );
    }
}
