package dev.turboism.sdk.cubism.command;

import dev.turboism.sdk.CubismEditor;

/** Configures whether the model statistics palette refreshes automatically. */
@CubismEditor
public record EditorModelingStatisticsRequest(boolean autoUpdate) implements EditorParameterizedRequest {
    @Override
    public EditorParameterizedCommand command() {
        return EditorParameterizedCommand.MODELING_STATISTICS;
    }
}
