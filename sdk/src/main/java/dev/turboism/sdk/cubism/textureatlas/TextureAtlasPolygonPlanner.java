package dev.turboism.sdk.cubism.textureatlas;

import java.util.List;

/**
 * Plans a polygon packing for a set of outlined items.
 *
 * <p>Implementations must be deterministic: identical items and constraints must
 * produce an equal plan, including under {@code parallel}. Overflowing items are
 * reported through {@link TextureAtlasPolygonPlan#overflowTextureIds()} and are not
 * treated as failures. Items whose policy excludes them or fixes their position
 * keep their issued transform.</p>
 */
public interface TextureAtlasPolygonPlanner {

    TextureAtlasPolygonPlan plan(
        List<TextureAtlasPolygonItem> items,
        TextureAtlasPolygonConstraints constraints
    );

    /**
     * Plans with the option of internal parallelism. The default delegates to
     * {@link #plan(List, TextureAtlasPolygonConstraints)}; parallel implementations
     * must preserve determinism.
     */
    default TextureAtlasPolygonPlan plan(
        List<TextureAtlasPolygonItem> items,
        TextureAtlasPolygonConstraints constraints,
        boolean parallel
    ) {
        return plan(items, constraints);
    }
}
