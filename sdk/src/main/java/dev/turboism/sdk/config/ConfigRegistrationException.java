package dev.turboism.sdk.config;

import java.util.Objects;

/**
 * Unchecked failure of {@link PluginConfigRegistry#registerSchema}, carrying the refusal reason.
 *
 * <p>The detail message is derived from the error name; callers should branch on {@link #error()}
 * rather than parse it.
 */
public final class ConfigRegistrationException extends RuntimeException {

    private final ConfigRegistrationError error;

    public ConfigRegistrationException(final ConfigRegistrationError error) {
        super("typed config schema registration failed: "
            + Objects.requireNonNull(error, "error").name());
        this.error = error;
    }

    /**
     * @return why registration was refused, never {@code null}
     */
    public ConfigRegistrationError error() {
        return error;
    }
}
