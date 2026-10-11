package dev.turboism.sdk.cubism.id;

import dev.turboism.sdk.CubismEditor;
import java.util.Objects;

/**
 * Editor-assigned identity of one project open in the host workspace.
 *
 * <p>The wrapped string is the host's own opaque handle, compared verbatim; it is not a file path,
 * and it is meaningful only while the host keeps that project open.
 *
 * @param value the host-issued project identifier, never {@code null}
 */
@CubismEditor
public record ProjectId(String value) {
    public ProjectId {
        value = Objects.requireNonNull(value, "value");
    }
}
