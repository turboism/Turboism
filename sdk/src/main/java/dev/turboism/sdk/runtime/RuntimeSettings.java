package dev.turboism.sdk.runtime;

import java.util.Objects;

/** Immutable global runtime settings edited by Turboism's built-in settings panel. */
public record RuntimeSettings(
    boolean safeMode,
    String logLevel,
    boolean skipStartupUpdateCheck,
    boolean skipStartupSplash,
    boolean skipStartupInformation
) {
    public RuntimeSettings {
        logLevel = Objects.requireNonNull(logLevel, "logLevel");
        if (!logLevel.matches("DEBUG|INFO|WARN|ERROR")) {
            throw new IllegalArgumentException("unsupported logLevel: " + logLevel);
        }
    }
}
