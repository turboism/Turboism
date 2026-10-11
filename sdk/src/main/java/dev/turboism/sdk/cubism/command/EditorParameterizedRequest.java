package dev.turboism.sdk.cubism.command;

import dev.turboism.sdk.CubismEditor;

/** Marker for reviewed command-specific request records; no generic options are permitted. */
@CubismEditor
public sealed interface EditorParameterizedRequest
        permits EditorResizeModelRequest,
                EditorGridSettingsRequest,
                EditorCanvasSettingsRequest,
                EditorModelingStatisticsRequest,
                EditorExternalAppSettingsRequest {

    /** Returns the parameterized command this request targets. */
    EditorParameterizedCommand command();

    /** Returns the host command identifier of {@link #command()}. */
    default String commandId() {
        return command().id();
    }
}
