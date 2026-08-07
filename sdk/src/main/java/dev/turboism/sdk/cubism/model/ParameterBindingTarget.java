package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;

import java.util.Objects;

/** Typed identity of an Editor object that owns a parameter binding. */
@PreviewApi
public record ParameterBindingTarget(ParameterBindingTargetType type, String id) {
    public ParameterBindingTarget {
        type = Objects.requireNonNull(type, "type");
        id = Objects.requireNonNull(id, "id").trim();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("id must not be blank");
        }
    }

    public static ParameterBindingTarget artMesh(final ArtMeshId id) {
        return new ParameterBindingTarget(
            ParameterBindingTargetType.ART_MESH,
            Objects.requireNonNull(id, "id").value()
        );
    }

    public static ParameterBindingTarget warpDeformer(final DeformerId id) {
        return new ParameterBindingTarget(
            ParameterBindingTargetType.WARP_DEFORMER,
            Objects.requireNonNull(id, "id").value()
        );
    }

    public static ParameterBindingTarget rotationDeformer(final DeformerId id) {
        return new ParameterBindingTarget(
            ParameterBindingTargetType.ROTATION_DEFORMER,
            Objects.requireNonNull(id, "id").value()
        );
    }
}
