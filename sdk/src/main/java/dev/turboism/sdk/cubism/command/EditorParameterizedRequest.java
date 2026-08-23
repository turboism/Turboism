package dev.turboism.sdk.cubism.command;


/** Marker for reviewed command-specific request records; no generic options are permitted. */
public sealed interface EditorParameterizedRequest permits
    EditorResizeModelRequest,
    EditorGridSettingsRequest,
    EditorCanvasSettingsRequest,
    EditorModelingStatisticsRequest,
    EditorExternalAppSettingsRequest {

    EditorParameterizedCommand command();

    default String commandId() { return command().id(); }
}
