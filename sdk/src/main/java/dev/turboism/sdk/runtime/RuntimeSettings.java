package dev.turboism.sdk.runtime;

import dev.turboism.sdk.PreviewApi;

import java.util.Objects;

/** Immutable global runtime settings edited by Turboism's built-in settings panel. */
@PreviewApi
public record RuntimeSettings(
    boolean safeMode,
    String logLevel,
    int maxLogStorageMiB,
    boolean skipStartupUpdateCheck,
    boolean skipStartupSplash,
    boolean skipStartupInformation,
    boolean separateExportSaveDirectory,
    String locale
) {
    public static final int DEFAULT_MAX_LOG_STORAGE_MIB = 100;
    public static final int MIN_MAX_LOG_STORAGE_MIB = 1;
    public static final int MAX_MAX_LOG_STORAGE_MIB = 4_096;
    public static final String DEFAULT_LOCALE = "system";
    public static final java.util.List<String> LOCALE_OPTIONS = java.util.List.of("system", "en", "ja", "ko", "zh-Hans", "zh-Hant");

    public RuntimeSettings(
        final boolean safeMode,
        final String logLevel,
        final int maxLogStorageMiB,
        final boolean skipStartupUpdateCheck,
        final boolean skipStartupSplash,
        final boolean skipStartupInformation,
        final boolean separateExportSaveDirectory
    ) {
        this(
            safeMode, logLevel, maxLogStorageMiB, skipStartupUpdateCheck,
            skipStartupSplash, skipStartupInformation, separateExportSaveDirectory, DEFAULT_LOCALE
        );
    }

    public RuntimeSettings(
        final boolean safeMode,
        final String logLevel,
        final boolean skipStartupUpdateCheck,
        final boolean skipStartupSplash,
        final boolean skipStartupInformation
    ) {
        this(
            safeMode, logLevel, DEFAULT_MAX_LOG_STORAGE_MIB, skipStartupUpdateCheck,
            skipStartupSplash, skipStartupInformation, false, DEFAULT_LOCALE
        );
    }

    public RuntimeSettings {
        logLevel = Objects.requireNonNull(logLevel, "logLevel");
        locale = Objects.requireNonNull(locale, "locale");
        if (!logLevel.matches("TRACE|DEBUG|INFO|WARN|ERROR|FATAL")) {
            throw new IllegalArgumentException("unsupported logLevel: " + logLevel);
        }
        if (!LOCALE_OPTIONS.contains(locale)) {
            throw new IllegalArgumentException("unsupported locale: " + locale);
        }
        if (maxLogStorageMiB < MIN_MAX_LOG_STORAGE_MIB
            || maxLogStorageMiB > MAX_MAX_LOG_STORAGE_MIB) {
            throw new IllegalArgumentException("unsupported maxLogStorageMiB: " + maxLogStorageMiB);
        }
    }
}
