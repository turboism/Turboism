package dev.turboism.sdk.cubism.command;


/** Configures whether the model statistics palette refreshes automatically. */
public record EditorModelingStatisticsRequest(boolean autoUpdate) implements EditorParameterizedRequest {
    @Override
    public EditorParameterizedCommand command() {
        return EditorParameterizedCommand.MODELING_STATISTICS;
    }
}
