package dev.turboism.sdk.config;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record ConfigWriteResult(
    boolean written,
    long revision,
    Optional<ConfigError> error
) {
    private static final Set<ConfigErrorCode> WRITE_ERRORS = Set.of(
        ConfigErrorCode.SCHEMA_NOT_REGISTERED,
        ConfigErrorCode.INVALID_VALUE,
        ConfigErrorCode.REVISION_CONFLICT,
        ConfigErrorCode.PERMISSION_DENIED,
        ConfigErrorCode.PERSISTENCE_FAILED,
        ConfigErrorCode.RUNTIME_UNAVAILABLE
    );

    public ConfigWriteResult {
        error = Objects.requireNonNull(error, "error");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        if (written) {
            if (revision == 0 || error.isPresent()) {
                throw new IllegalArgumentException("successful config write requires a positive revision and no error");
            }
        } else if (error.isEmpty() || !WRITE_ERRORS.contains(error.orElseThrow().code())) {
            throw new IllegalArgumentException("failed config write requires a legal write error");
        }
    }
}
