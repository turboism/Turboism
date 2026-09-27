package dev.turboism.sdk.cubism.textureatlas;

import java.util.List;
import java.util.Objects;

/**
 * Point-in-time polygon-aware state of one atlas page.
 *
 * <p>Extends the rectangle snapshot contract with item outlines, per-item layout
 * policies and issued transforms so a polygon planner can plan (and a writer can
 * validate) without re-entering the host.</p>
 */
public record TextureAtlasPolygonLayoutSnapshot(
    TextureAtlasLayoutTarget target,
    String documentId,
    String modelId,
    String atlasId,
    TextureAtlasPolygonConstraints constraints,
    List<TextureAtlasPolygonItem> items,
    TextureAtlasPolygonPlan currentPlan
) {

    public TextureAtlasPolygonLayoutSnapshot {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(modelId, "modelId");
        Objects.requireNonNull(atlasId, "atlasId");
        Objects.requireNonNull(constraints, "constraints");
        items = List.copyOf(Objects.requireNonNull(items, "items"));
    }
}
