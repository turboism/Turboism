package dev.turboism.sdk.config;

import java.util.Objects;

public final class ConfigRegistrationException extends RuntimeException {

    private final ConfigRegistrationError error;

    public ConfigRegistrationException(final ConfigRegistrationError error) {
        super("typed config schema registration failed: "
            + Objects.requireNonNull(error, "error").name());
        this.error = error;
    }

    public ConfigRegistrationError error() {
        return error;
    }
}
