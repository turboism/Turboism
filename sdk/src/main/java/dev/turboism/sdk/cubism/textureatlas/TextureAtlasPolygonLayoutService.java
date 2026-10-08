package dev.turboism.sdk.cubism.textureatlas;

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

    /** Returns the freshly read polygon snapshot of the current page, if available. */
    Optional<TextureAtlasPolygonLayoutSnapshot> currentPolygon();

    /**
     * Validates and applies one polygon plan through the host affine/undo boundary.
     *
     * @param target the page target the plan was computed against
     * @param plan the validated plan to write back
     * @return the apply outcome; a rejected plan leaves the page untouched
     */
    TextureAtlasLayoutApplyResult apply(TextureAtlasLayoutTarget target, TextureAtlasPolygonPlan plan);

    /**
     * Reports whether a live runtime backend backs this service.
     *
     * @return {@code false} when the backend backing this instance is
     *         unavailable, including the {@link #unavailable()} sentinel
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * Returns this service's fail-closed {@code Unavailable} sentinel.
     *
     * @return the shared singleton; {@link #isAvailable()} is {@code false} only for it
     */
    static TextureAtlasPolygonLayoutService unavailable() {
        return Unavailable.INSTANCE;
    }

    /**
     * Sentinel returned by {@link #unavailable()}: reads report empty and applies fail with
     * {@link TextureAtlasLayoutFailureCode#CAPABILITY_UNAVAILABLE}.
     */
    enum Unavailable implements TextureAtlasPolygonLayoutService {
        INSTANCE;

        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public Optional<TextureAtlasPolygonLayoutSnapshot> currentPolygon() {
            return Optional.empty();
        }

        @Override
        public TextureAtlasLayoutApplyResult apply(
                final TextureAtlasLayoutTarget target, final TextureAtlasPolygonPlan plan) {
            java.util.Objects.requireNonNull(target, "target");
            java.util.Objects.requireNonNull(plan, "plan");
            return TextureAtlasLayoutApplyResult.failed(
                    TextureAtlasLayoutFailureCode.CAPABILITY_UNAVAILABLE,
                    "texture atlas polygon layout service is unavailable");
        }
    }
}
