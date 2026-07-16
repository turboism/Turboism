package dev.turboism.sdk.config;

import java.util.Objects;

public final class ConfigSchemaValidationException extends RuntimeException {

    private final ConfigSchemaValidationError error;

    public ConfigSchemaValidationException(final ConfigSchemaValidationError error) {
        super("typed config schema validation failed: "
            + Objects.requireNonNull(error, "error").name());
        this.error = error;
    }

    public ConfigSchemaValidationError error() {
        return error;
    }
}
