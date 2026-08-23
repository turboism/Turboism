package dev.turboism.sdk.cubism.id;

import java.util.Objects;

/**
 * Editor-assigned identity of one texture atlas belonging to a model.
 *
 * <p>The wrapped string is the host's own opaque handle, compared verbatim. It survives layout
 * edits to the atlas, but not the atlas being deleted and recreated by the host.
 *
 * @param value the host-issued texture atlas identifier, never {@code null}
 */
public record TextureAtlasId(String value) {
    public TextureAtlasId {
        value = Objects.requireNonNull(value, "value");
    }
}
