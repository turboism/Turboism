package dev.turboism.preview;

import dev.turboism.sdk.plugin.PluginLogger;

import java.util.Objects;

final class PreviewPluginLogger implements PluginLogger {

    private final PreviewLog log;
    private final String pluginId;

    PreviewPluginLogger(final PreviewLog log, final String pluginId) {
        this.log = Objects.requireNonNull(log, "log");
        this.pluginId = Objects.requireNonNull(pluginId, "pluginId");
    }

    @Override
    public void debug(final String message) {
        log.debug(pluginId, message);
    }

    @Override
    public void info(final String message) {
        log.info(pluginId, message);
    }

    @Override
    public void warn(final String message) {
        log.warn(pluginId, message);
    }

    @Override
    public void error(final String message) {
        log.error(pluginId, message, null);
    }

    @Override
    public void error(final String message, final Throwable throwable) {
        log.error(pluginId, message, throwable);
    }
}
