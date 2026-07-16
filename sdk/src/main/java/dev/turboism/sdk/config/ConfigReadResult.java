package dev.turboism.sdk.config;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record ConfigReadResult<T>(
    ConfigValue<T> value,
    Optional<ConfigError> error
) {
    public ConfigReadResult {
        value = Objects.requireNonNull(value, "value");
        error = Objects.requireNonNull(error, "error");
        final Set<ConfigErrorCode> legal = switch (value.source()) {
            case STORED, DEFAULT_MISSING -> Set.of();
            case DEFAULT_INVALID -> Set.of(ConfigErrorCode.INVALID_VALUE);
            case DEFAULT_FUTURE_VERSION -> Set.of(ConfigErrorCode.FUTURE_SCHEMA_VERSION);
            case DEFAULT_MIGRATION_FAILED -> Set.of(
                ConfigErrorCode.MIGRATION_GAP,
                ConfigErrorCode.MIGRATION_FAILED
            );
            case DEFAULT_UNAVAILABLE -> Set.of(
                ConfigErrorCode.SCHEMA_NOT_REGISTERED,
                ConfigErrorCode.PERMISSION_DENIED,
                ConfigErrorCode.PERSISTENCE_FAILED,
                ConfigErrorCode.RUNTIME_UNAVAILABLE
            );
        };
        if (error.isPresent() != !legal.isEmpty()
            || error.map(ConfigError::code).filter(legal::contains).isEmpty() != error.isEmpty()) {
            throw new IllegalArgumentException("config read source/error matrix is invalid");
        }
    }
}
