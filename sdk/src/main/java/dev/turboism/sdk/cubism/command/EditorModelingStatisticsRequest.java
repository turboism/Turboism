package dev.turboism.sdk.cubism.command;

import dev.turboism.sdk.PreviewApi;

/** Configures whether the model statistics palette refreshes automatically. */
@PreviewApi
public record EditorModelingStatisticsRequest(boolean autoUpdate) implements EditorParameterizedRequest {
    @Override
    public EditorParameterizedCommand command() {
        return EditorParameterizedCommand.MODELING_STATISTICS;
    }
}
