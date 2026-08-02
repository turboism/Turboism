package dev.turboism.sdk.runtime;

import java.util.Objects;

/** Immutable global runtime settings edited by Turboism's built-in settings panel. */
public record RuntimeSettings(
    boolean safeMode,
    String logLevel,
    int maxLogStorageMiB,
    boolean skipStartupUpdateCheck,
    boolean skipStartupSplash,
    boolean skipStartupInformation
) {
    public static final int DEFAULT_MAX_LOG_STORAGE_MIB = 100;
    public static final int MIN_MAX_LOG_STORAGE_MIB = 1;
    public static final int MAX_MAX_LOG_STORAGE_MIB = 4_096;

    public RuntimeSettings(
        final boolean safeMode,
        final String logLevel,
        final boolean skipStartupUpdateCheck,
        final boolean skipStartupSplash,
        final boolean skipStartupInformation
    ) {
        this(
            safeMode,
            logLevel,
            DEFAULT_MAX_LOG_STORAGE_MIB,
            skipStartupUpdateCheck,
            skipStartupSplash,
            skipStartupInformation
        );
    }

    public RuntimeSettings {
        logLevel = Objects.requireNonNull(logLevel, "logLevel");
        if (!logLevel.matches("TRACE|DEBUG|INFO|WARN|ERROR|FATAL")) {
            throw new IllegalArgumentException("unsupported logLevel: " + logLevel);
        }
        if (maxLogStorageMiB < MIN_MAX_LOG_STORAGE_MIB
            || maxLogStorageMiB > MAX_MAX_LOG_STORAGE_MIB) {
            throw new IllegalArgumentException("unsupported maxLogStorageMiB: " + maxLogStorageMiB);
        }
    }
}
