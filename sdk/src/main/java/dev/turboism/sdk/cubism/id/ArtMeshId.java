package dev.turboism.sdk.cubism.id;

import java.util.Objects;

public record ArtMeshId(String value) {
    public ArtMeshId {
        value = Objects.requireNonNull(value, "value");
    }
}
