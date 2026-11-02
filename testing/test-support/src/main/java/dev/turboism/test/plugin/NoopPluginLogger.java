package dev.turboism.test.plugin;

import dev.turboism.sdk.plugin.PluginLogger;

/**
 * Stateless {@link PluginLogger} that discards every message.
 *
 * <p>Use this for tests that only need a non-null logger and never assert on log output.
 */
public final class NoopPluginLogger implements PluginLogger {

    public static final NoopPluginLogger INSTANCE = new NoopPluginLogger();

    private NoopPluginLogger() {}

    @Override
    public void debug(final String message) {}

    @Override
    public void info(final String message) {}

    @Override
    public void warn(final String message) {}

    @Override
    public void error(final String message) {}

    @Override
    public void error(final String message, final Throwable throwable) {}
}
