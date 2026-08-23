package dev.turboism.sdk.cubism.command;


/** Configures the active model canvas dimensions in pixels. */
public record EditorCanvasSettingsRequest(int widthPixels, int heightPixels) implements EditorParameterizedRequest {
    /** Host-verified bounds: the native canvas dialog rejects values below 16 and clamps above 30000. */
    public EditorCanvasSettingsRequest {
        if (widthPixels < 16 || heightPixels < 16 || widthPixels > 30_000 || heightPixels > 30_000) {
            throw new IllegalArgumentException("canvas dimensions must be between 16 and 30000 pixels");
        }
    }

    /** @return the parameterized command this request drives: the model setting dialog */
    public EditorParameterizedCommand command() { return EditorParameterizedCommand.MODEL_SETTING; }

    /** @return the host command identifier of {@link #command()}, for logging and dispatch */
    public String commandId() { return command().id(); }
}
