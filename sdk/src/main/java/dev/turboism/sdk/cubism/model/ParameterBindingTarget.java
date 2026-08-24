package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;

import java.util.Objects;

/** Typed identity of an Editor object that owns a parameter binding. */
public record ParameterBindingTarget(ParameterBindingTargetType type, String id) {
    public ParameterBindingTarget {
        type = Objects.requireNonNull(type, "type");
        id = Objects.requireNonNull(id, "id").trim();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("id must not be blank");
        }
    }

    /**
     * @param id ArtMesh that owns the binding
     * @return a target naming that ArtMesh
     * @throws NullPointerException if {@code id} is {@code null}
     */
    public static ParameterBindingTarget artMesh(final ArtMeshId id) {
        return new ParameterBindingTarget(
            ParameterBindingTargetType.ART_MESH,
            Objects.requireNonNull(id, "id").value()
        );
    }

    /**
     * @param id deformer that owns the binding; the caller is responsible for
     *     it actually being a warp deformer, which is not checked here
     * @return a target naming that warp deformer
     * @throws NullPointerException if {@code id} is {@code null}
     */
    public static ParameterBindingTarget warpDeformer(final DeformerId id) {
        return new ParameterBindingTarget(
            ParameterBindingTargetType.WARP_DEFORMER,
            Objects.requireNonNull(id, "id").value()
        );
    }

    /**
     * @param id deformer that owns the binding; the caller is responsible for
     *     it actually being a rotation deformer, which is not checked here
     * @return a target naming that rotation deformer
     * @throws NullPointerException if {@code id} is {@code null}
     */
    public static ParameterBindingTarget rotationDeformer(final DeformerId id) {
        return new ParameterBindingTarget(
            ParameterBindingTargetType.ROTATION_DEFORMER,
            Objects.requireNonNull(id, "id").value()
        );
    }
}
