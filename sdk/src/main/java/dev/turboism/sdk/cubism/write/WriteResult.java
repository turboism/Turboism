package dev.turboism.sdk.cubism.write;


/** Result of a single write command. */
public record WriteResult(
    String commandId,
    boolean success,
    String errorMessage
) {

    /**
     * @param commandId id of the command that succeeded
     * @return a successful result with no error message
     */
    public static WriteResult success(String commandId) {
        return new WriteResult(commandId, true, null);
    }

    /**
     * @param commandId id of the command that failed
     * @param errorMessage why it failed, for reporting back to the plugin
     * @return an unsuccessful result carrying that message
     */
    public static WriteResult failure(String commandId, String errorMessage) {
        return new WriteResult(commandId, false, errorMessage);
    }
}
