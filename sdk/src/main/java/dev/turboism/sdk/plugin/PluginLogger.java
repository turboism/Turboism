package dev.turboism.sdk.plugin;

/**
 * Plugin-scoped logger provided by the framework through {@link PluginContext#logger()}.
 *
 * <p>The framework automatically attaches the current plugin descriptor id to every record. Plugin
 * code supplies only the message and optional failure; it must not prepend timestamps, levels, or
 * its own id. Records are routed to both Turboism's session log and the Cubism host logger when that
 * host integration is available.</p>
 */
public interface PluginLogger {

    /**
     * Records verbose diagnostic information.
     *
     * @param message message text; line breaks are sanitized by the framework
     */
    void debug(String message);

    /**
     * Records normal plugin operation information.
     *
     * @param message message text; line breaks are sanitized by the framework
     */
    void info(String message);

    /**
     * Records a recoverable or potentially actionable condition.
     *
     * @param message message text; line breaks are sanitized by the framework
     */
    void warn(String message);

    /**
     * Records a plugin operation failure without an attached cause.
     *
     * @param message message text; line breaks are sanitized by the framework
     */
    void error(String message);

    /**
     * Records a plugin operation failure and its cause.
     *
     * @param message message text; line breaks are sanitized by the framework
     * @param throwable failure to attach to the record
     */
    void error(String message, Throwable throwable);
}
