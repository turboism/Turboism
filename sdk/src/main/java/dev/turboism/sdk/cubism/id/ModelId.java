package dev.turboism.sdk.cubism.id;

import java.util.Objects;

/**
 * Editor-assigned identity of one Cubism model within a project.
 *
 * <p>The wrapped string is the host's own opaque handle; it is compared verbatim and carries no
 * structure callers may parse. It is meaningful only for as long as the host keeps that model
 * loaded.
 *
 * @param value the host-issued model identifier, never {@code null}
 */
public record ModelId(String value) {
    public ModelId {
        value = Objects.requireNonNull(value, "value");
    }
}
