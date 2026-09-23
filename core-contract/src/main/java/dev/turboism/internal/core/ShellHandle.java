package dev.turboism.internal.core;

import dev.turboism.sdk.plugin.PluginContext;

/**
 * The runtime-admitted framework shell. Not a plugin: it has no jar, no plugin classloader and
 * no plugin lifecycle state machine, and it never produces plugin lifecycle events. The runtime
 * assembles the same {@link PluginContext} service bundle external plugins receive and calls
 * {@link #start} once inside the bounded lifecycle admission; {@link #close} releases the shell's
 * surfaces before its disposable scope and event owner are torn down.
 */
public interface ShellHandle extends AutoCloseable {

    /**
     * Wires the shell's services and contributes every built-in surface.
     *
     * <p>Invoked by the runtime on the bounded lifecycle lane inside the admission deadline.
     *
     * @param context the plugin-facing context the runtime assembled for the shell's identity
     * @throws Exception when the shell cannot start; the runtime retains and cleans up the
     *     admitted generation, then fails the runtime startup
     */
    void start(PluginContext context) throws Exception;

    /** Releases the shell's contributed surfaces; safe to call once from runtime teardown. */
    @Override
    void close();
}
