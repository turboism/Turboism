package dev.turboism.sdk.cubism.edit;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.model.PartId;
import dev.turboism.sdk.cubism.id.ModelObjectId;
import dev.turboism.sdk.cubism.model.Point2;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Read payload of an {@link EditObjectKind#ART_MESH} object, matching the official
 * {@code ArtMesh} data block of {@code GetObject}, including mesh geometry.
 */
@CubismEditor({"5.2.03", "5.3.02", "5.3.03"})
public record EditArtMeshData(
        String name,
        Optional<PartId> parentId,
        Optional<DeformerId> parentDeformerId,
        List<ModelObjectId> clippingIds,
        boolean reverseMask,
        int drawOrder,
        double opacity,
        Optional<String> multiplyColor,
        Optional<String> screenColor,
        EditColorBlend colorBlend,
        EditAlphaBlend alphaBlend,
        boolean culling,
        EditLabelColor labelColor,
        List<Point2> vertices,
        List<Point2> uvs,
        List<EditTriangle> triangles)
        implements EditObjectData {

    public EditArtMeshData {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(parentId, "parentId");
        Objects.requireNonNull(parentDeformerId, "parentDeformerId");
        clippingIds = List.copyOf(Objects.requireNonNull(clippingIds, "clippingIds"));
        if (!Double.isFinite(opacity)) {
            throw new IllegalArgumentException("opacity must be finite");
        }
        Objects.requireNonNull(multiplyColor, "multiplyColor");
        Objects.requireNonNull(screenColor, "screenColor");
        Objects.requireNonNull(colorBlend, "colorBlend");
        Objects.requireNonNull(alphaBlend, "alphaBlend");
        Objects.requireNonNull(labelColor, "labelColor");
        vertices = List.copyOf(Objects.requireNonNull(vertices, "vertices"));
        uvs = List.copyOf(Objects.requireNonNull(uvs, "uvs"));
        triangles = List.copyOf(Objects.requireNonNull(triangles, "triangles"));
    }

    @Override
    public EditObjectKind kind() {
        return EditObjectKind.ART_MESH;
    }
}
