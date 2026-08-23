package dev.turboism.sdk.config;

import java.util.Objects;

/**
 * Unchecked failure raised when a schema or migration chain fails validation before registration.
 *
 * <p>This signals a programming error in the declaring plugin, not a runtime or host condition;
 * callers should branch on {@link #error()} rather than parse the detail message.
 */
public final class ConfigSchemaValidationException extends RuntimeException {

    private final ConfigSchemaValidationError error;

    public ConfigSchemaValidationException(final ConfigSchemaValidationError error) {
        super("typed config schema validation failed: "
            + Objects.requireNonNull(error, "error").name());
        this.error = error;
    }

    /**
     * @return the specific defect found in the schema or migration chain, never {@code null}
     */
    public ConfigSchemaValidationError error() {
        return error;
    }
}
