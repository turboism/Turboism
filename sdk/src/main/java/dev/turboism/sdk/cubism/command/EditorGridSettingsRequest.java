package dev.turboism.sdk.cubism.command;

import dev.turboism.sdk.cubism.model.Color;

import java.util.Objects;

/** Configures the Editor grid using the observed spacing and color fields. */
public record EditorGridSettingsRequest(int spacingPixels, Color color) implements EditorParameterizedRequest {
    /**
     * Host-verified bounds: the native dialog slider spans [5, min(documentWidth, documentHeight)]
     * and documents are clamped to 30000 pixels, so 30000 is the safe static upper bound. The host
     * grid color is written as opaque RGB (the CColor(int,int,int) constructor); alpha must be 1 so
     * no channel is silently dropped.
     */
    public EditorGridSettingsRequest {
        if (spacingPixels < 1 || spacingPixels > 30_000) {
            throw new IllegalArgumentException("spacingPixels must be between 1 and 30000");
        }
        color = Objects.requireNonNull(color, "color");
        requireUnit(color.red(), "color.red");
        requireUnit(color.green(), "color.green");
        requireUnit(color.blue(), "color.blue");
        if (color.alpha() != 1.0f) {
            throw new IllegalArgumentException("color.alpha must be 1 (the host grid color is opaque RGB)");
        }
    }

    /** @return the parameterized command this request drives: the grid setting dialog */
    public EditorParameterizedCommand command() { return EditorParameterizedCommand.GRID_SETTING; }

    /** @return the host command identifier of {@link #command()}, for logging and dispatch */
    public String commandId() { return command().id(); }

    private static void requireUnit(float value, String name) {
        if (value < 0.0f || value > 1.0f) throw new IllegalArgumentException(name + " must be between 0 and 1");
    }
}
