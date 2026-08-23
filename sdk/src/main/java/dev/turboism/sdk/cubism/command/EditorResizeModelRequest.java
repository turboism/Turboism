package dev.turboism.sdk.cubism.command;


/** Resizes the active model document by a verified percentage scale. */
public record EditorResizeModelRequest(int percent) implements EditorParameterizedRequest {
    /** Host-verified bounds: the native percentage input dialog accepts 1..5000. */
    public EditorResizeModelRequest {
        if (percent < 1 || percent > 5000) {
            throw new IllegalArgumentException("percent must be between 1 and 5000");
        }
    }

    /** @return the parameterized command this request drives: resize model document */
    public EditorParameterizedCommand command() {
        return EditorParameterizedCommand.RESIZE_MODEL_DOCUMENT;
    }

    /** @return the host command identifier of {@link #command()}, for logging and dispatch */
    public String commandId() {
        return command().id();
    }
}
