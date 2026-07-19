package dev.turboism.sdk.cubism.write;

import dev.turboism.sdk.PreviewApi;

/** Result of a single write command. */
@PreviewApi
public record WriteResult(
    String commandId,
    boolean success,
    String errorMessage
) {

    public static WriteResult success(String commandId) {
        return new WriteResult(commandId, true, null);
    }

    public static WriteResult failure(String commandId, String errorMessage) {
        return new WriteResult(commandId, false, errorMessage);
    }
}
