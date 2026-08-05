package dev.turboism.sdk.cubism.command;

import dev.turboism.sdk.PreviewApi;

/** Marker for reviewed command-specific request records; no generic options are permitted. */
@PreviewApi
public sealed interface EditorParameterizedRequest permits
    EditorResizeModelRequest,
    EditorGridSettingsRequest,
    EditorCanvasSettingsRequest,
    EditorModelingStatisticsRequest,
    EditorExternalAppSettingsRequest {

    EditorParameterizedCommand command();

    default String commandId() { return command().id(); }
}
