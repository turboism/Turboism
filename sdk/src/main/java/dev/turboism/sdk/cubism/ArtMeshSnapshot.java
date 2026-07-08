package dev.turboism.sdk.cubism;

import java.util.Objects;
import java.util.Optional;

public record ArtMeshSnapshot(
    String id,
    String name,
    Optional<String> textureId,
    boolean visible,
    boolean renderable
) implements ModelObjectSnapshot {
    public ArtMeshSnapshot {
        id = Objects.requireNonNull(id, "id");
        name = Objects.requireNonNull(name, "name");
        textureId = Objects.requireNonNull(textureId, "textureId");
    }
}
