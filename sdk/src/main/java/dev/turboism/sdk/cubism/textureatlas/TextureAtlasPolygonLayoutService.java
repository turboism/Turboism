package dev.turboism.sdk.cubism.textureatlas;

import java.util.Objects;
import java.util.Optional;

/**
 * Polygon-aware counterpart of {@link TextureAtlasLayoutService}.
 *
 * <p>Reads the current page's outlines, policies and issued transforms, and applies
 * a validated {@link TextureAtlasPolygonPlan} - including arbitrary angles and a
 * uniform scale - through the host's affine/undo boundary. Validation happens
 * against the freshly read state: placements must cover exactly the participating
 * items, respect the declared rotation mode and margins, and transformed outlines
 * must not overlap. Any failure leaves the page untouched.</p>
 */
public interface TextureAtlasPolygonLayoutService {

    Optional<TextureAtlasPolygonLayoutSnapshot> currentPolygon();

    TextureAtlasLayoutApplyResult apply(
        TextureAtlasLayoutTarget target,
        TextureAtlasPolygonPlan plan
    );
}
