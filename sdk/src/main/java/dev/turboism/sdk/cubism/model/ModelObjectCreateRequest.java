package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;

import java.util.Objects;
import java.util.Optional;

/** Typed creation requests for model objects. */
@PreviewApi
public sealed interface ModelObjectCreateRequest permits
    ModelObjectCreateRequest.Part,
    ModelObjectCreateRequest.ArtMesh,
    ModelObjectCreateRequest.WarpDeformer,
    ModelObjectCreateRequest.RotationDeformer {

    ModelObjectKind kind();

    String name();

    Optional<ModelObjectReference> parent();

    @PreviewApi
    record Part(String name, Optional<ModelObjectReference> parent)
        implements ModelObjectCreateRequest {
        public Part {
            name = ModelObjectDescriptor.normalizeName(name);
            parent = checkedParent(parent, true);
        }

        @Override public ModelObjectKind kind() {
            return ModelObjectKind.PART;
        }
    }

    @PreviewApi
    record ArtMesh(
        String name,
        Optional<ModelObjectReference> parent,
        ArtMeshGeometry geometry
    ) implements ModelObjectCreateRequest {
        public ArtMesh {
            name = ModelObjectDescriptor.normalizeName(name);
            parent = checkedParent(parent, false);
            geometry = Objects.requireNonNull(geometry, "geometry");
        }

        @Override public ModelObjectKind kind() {
            return ModelObjectKind.ART_MESH;
        }
    }

    @PreviewApi
    record WarpDeformer(
        String name,
        Optional<ModelObjectReference> parent,
        WarpGrid grid
    ) implements ModelObjectCreateRequest {
        public WarpDeformer {
            name = ModelObjectDescriptor.normalizeName(name);
            parent = checkedParent(parent, false);
            grid = Objects.requireNonNull(grid, "grid");
        }

        @Override public ModelObjectKind kind() {
            return ModelObjectKind.WARP_DEFORMER;
        }
    }

    @PreviewApi
    record RotationDeformer(
        String name,
        Optional<ModelObjectReference> parent,
        RotationDeformerForm form
    ) implements ModelObjectCreateRequest {
        public RotationDeformer {
            name = ModelObjectDescriptor.normalizeName(name);
            parent = checkedParent(parent, false);
            form = Objects.requireNonNull(form, "form");
        }

        @Override public ModelObjectKind kind() {
            return ModelObjectKind.ROTATION_DEFORMER;
        }
    }

    private static Optional<ModelObjectReference> checkedParent(
        final Optional<ModelObjectReference> value,
        final boolean partOnly
    ) {
        final Optional<ModelObjectReference> parent = Objects.requireNonNull(value, "parent");
        if (parent.isEmpty()) {
            return parent;
        }
        final ModelObjectKind kind = parent.orElseThrow().kind();
        if (partOnly && kind != ModelObjectKind.PART) {
            throw new IllegalArgumentException("a Part parent must also be a Part");
        }
        if (!partOnly && kind == ModelObjectKind.ART_MESH) {
            throw new IllegalArgumentException("an ArtMesh cannot be used as a parent");
        }
        return parent;
    }
}
