package dev.turboism.sdk.cubism.command;


/** Configures the Editor external-application integration endpoint. */
public record EditorExternalAppSettingsRequest(int port, boolean allowRemoteConnections)
    implements EditorParameterizedRequest {

    public EditorExternalAppSettingsRequest {
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
    }

    @Override
    public EditorParameterizedCommand command() {
        return EditorParameterizedCommand.EXTERNAL_APP_SETTING;
    }
}
