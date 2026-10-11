package dev.turboism.sdk.cubism.model;

import java.util.List;
import java.util.Objects;
import dev.turboism.sdk.CubismEditor;

/** One existing non-default ordinary form's draw order in a compare-and-set batch. */
@CubismEditor({"5.2.03", "5.3.02", "5.3.03"})
public record ArtMeshKeyformDrawOrder(List<ArtMeshAuthoringSnapshot.Coordinate> coordinates,
        String formGuid, int drawOrder) {
    public ArtMeshKeyformDrawOrder {
        coordinates = List.copyOf(coordinates);
        formGuid = Objects.requireNonNull(formGuid, "formGuid");
        if (formGuid.isBlank()) throw new IllegalArgumentException("blank form GUID");
        if (drawOrder < 0 || drawOrder > 1000) throw new IllegalArgumentException("draw order outside 0..1000");
    }
}
