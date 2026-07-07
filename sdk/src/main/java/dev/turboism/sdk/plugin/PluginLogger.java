package dev.turboism.sdk.plugin;

/**
 * Logger exposed to plugins. Implementations are provided by the framework.
 */
public interface PluginLogger {

    void debug(String message);

    void info(String message);

    void warn(String message);

    void error(String message);

    void error(String message, Throwable throwable);
}
