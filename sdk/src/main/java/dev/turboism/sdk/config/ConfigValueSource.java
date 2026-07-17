package dev.turboism.sdk.config;

public enum ConfigValueSource {
    STORED,
    DEFAULT_MISSING,
    DEFAULT_INVALID,
    DEFAULT_FUTURE_VERSION,
    DEFAULT_MIGRATION_FAILED,
    DEFAULT_UNAVAILABLE
}
