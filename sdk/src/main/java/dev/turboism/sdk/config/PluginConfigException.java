package dev.turboism.sdk.config;

/**
 * Checked failure of an untyped string config operation, such as a rejected write.
 *
 * <p>The typed API reports failures as {@link ConfigWriteResult}/{@link ConfigReadResult} values
 * instead of throwing this.
 */
public class PluginConfigException extends Exception {

    public PluginConfigException(String message) {
        super(message);
    }

    public PluginConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
