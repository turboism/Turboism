package dev.turboism.sdk.cubism.command;


/** Marker for reviewed command-specific request records; no generic options are permitted. */
public sealed interface EditorParameterizedRequest permits
    EditorResizeModelRequest,
    EditorGridSettingsRequest,
    EditorCanvasSettingsRequest,
    EditorModelingStatisticsRequest,
    EditorExternalAppSettingsRequest {

    /** Returns the parameterized command this request targets. */
    EditorParameterizedCommand command();

    /** Returns the host command identifier of {@link #command()}. */
    default String commandId() { return command().id(); }
}
