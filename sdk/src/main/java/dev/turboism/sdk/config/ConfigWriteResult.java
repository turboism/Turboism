package dev.turboism.sdk.config;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Outcome of a typed config write.
 *
 * <p>The compact constructor enforces the two legal shapes: a successful write carries a positive
 * revision and no error, and a failed write carries an error whose code is one of the write-side
 * codes (schema not registered, invalid value, revision conflict, permission denied, persistence
 * failed, runtime unavailable). Anything else throws {@link IllegalArgumentException}.
 *
 * @param written whether the value was persisted
 * @param revision the new store revision on success; not meaningful when {@code written} is false
 * @param error empty on success, otherwise the reason the write was rejected
 */
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
