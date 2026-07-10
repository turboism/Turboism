package dev.turboism.sdk.cubism.write;

import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.ParameterId;

/**
 * DTO for a parameter value write operation.
 * All fields are SDK-owned types — no host references.
 */
public record WriteParameterCommand(
    String commandId,
    ModelId modelId,
    ParameterId parameterId,
    float value
) implements CubismWriteCommand {

    public WriteParameterCommand {
        if (commandId == null || commandId.isBlank()) {
            throw new IllegalArgumentException("commandId must not be null or blank");
        }
        if (modelId == null) {
            throw new IllegalArgumentException("modelId must not be null");
        }
        if (parameterId == null) {
            throw new IllegalArgumentException("parameterId must not be null");
        }
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException("value must be finite");
        }
    }
}
