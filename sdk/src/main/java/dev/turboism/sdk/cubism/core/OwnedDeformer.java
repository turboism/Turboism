package dev.turboism.sdk.cubism.core;


import java.util.List;
import java.util.Objects;

/** Immutable adapter-owned projection of one evaluated Core deformer. */
public record OwnedDeformer(
    String id,
    int parentDeformerIndex,
    List<Integer> parameters
) {

    public OwnedDeformer {
        Objects.requireNonNull(id, "id");
        parameters = List.copyOf(parameters);
        if (id.isBlank() || parentDeformerIndex < -1) {
            throw new IllegalArgumentException("invalid OwnedDeformer definition");
        }
    }
}
