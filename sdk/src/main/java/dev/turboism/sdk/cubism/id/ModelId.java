package dev.turboism.sdk.cubism.id;

import dev.turboism.sdk.CubismEditor;
import java.util.Objects;

/**
 * Editor-assigned identity of one Cubism model within a project.
 *
 * <p>The wrapped string is the host's own opaque handle; it is compared verbatim and carries no
 * structure callers may parse. The native Editor backend exposes the model-source GUID,
 * which is serialized in the model file and survives saving and reopening across Editor sessions.
 * Copies that retain that GUID (including Save As) share this identity; it is not a file-path
 * identity. A stored identifier does not keep the model or its live object handles loaded.
 *
 * @param value the host-issued model identifier, never {@code null}
 */
@CubismEditor
public record ModelId(String value) {
    public ModelId {
        value = Objects.requireNonNull(value, "value");
    }
}
