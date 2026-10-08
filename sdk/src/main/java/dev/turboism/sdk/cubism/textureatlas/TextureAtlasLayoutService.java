package dev.turboism.sdk.cubism.textureatlas;

import dev.turboism.sdk.CubismEditor;
import java.util.Optional;

/**
 * Reads and applies texture-atlas layouts. Outside a native packing invocation, targets require
 * a complete fixed-size atlas plan. Inside an invocation, {@code singlePageOptions} identifies
 * a current-page target: only its issued images may be placed, omitted images become native
 * overflow, and rotation/scaling must match the issued constraints. The caller must not plan
 * other pages or reuse a target after its originating invocation ends.
 */
@CubismEditor({"5.2.03", "5.3.02", "5.3.03"})
public interface TextureAtlasLayoutService {

    /**
     * Returns the model's current texture-atlas layout, or empty when the active document has
     * no atlas to project.
     */
    Optional<TextureAtlasLayoutSnapshot> current();

    /**
     * Applies {@code plan} to {@code target} through the native atlas-editing path.
     *
     * @param target the atlas scope the plan applies to
     * @param plan a complete fixed-size layout plan; outside a packing invocation every image
     *             must be placed, inside one only the issued images may be placed
     */
    TextureAtlasLayoutApplyResult apply(TextureAtlasLayoutTarget target, TextureAtlasLayoutPlan plan);

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
    static TextureAtlasLayoutService unavailable() {
        return Unavailable.INSTANCE;
    }

    /**
     * Sentinel returned by {@link #unavailable()}: reads report empty and applies fail with
     * {@link TextureAtlasLayoutFailureCode#CAPABILITY_UNAVAILABLE}.
     */
    enum Unavailable implements TextureAtlasLayoutService {
        INSTANCE;

        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public Optional<TextureAtlasLayoutSnapshot> current() {
            return Optional.empty();
        }

        @Override
        public TextureAtlasLayoutApplyResult apply(
                final TextureAtlasLayoutTarget target, final TextureAtlasLayoutPlan plan) {
            java.util.Objects.requireNonNull(target, "target");
            java.util.Objects.requireNonNull(plan, "plan");
            return TextureAtlasLayoutApplyResult.failed(
                    TextureAtlasLayoutFailureCode.CAPABILITY_UNAVAILABLE,
                    "texture atlas layout service is unavailable");
        }
    }
}
