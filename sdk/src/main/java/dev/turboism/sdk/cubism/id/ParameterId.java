package dev.turboism.sdk.cubism.id;

import java.util.Objects;

/**
 * Editor-assigned identity of one model parameter.
 *
 * <p>The wrapped string is the host's own opaque handle, compared verbatim. It is not the
 * parameter's user-visible display name and is not intended for presentation.
 *
 * @param value the host-issued parameter identifier, never {@code null}
 */
public record ParameterId(String value) {
    public ParameterId {
        value = Objects.requireNonNull(value, "value");
    }
}
