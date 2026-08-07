package dev.turboism.sdk.cubism.id;

import java.util.Objects;

public record TextureAtlasId(String value) {
    public TextureAtlasId {
        value = Objects.requireNonNull(value, "value");
    }
}
