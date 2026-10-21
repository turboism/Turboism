package dev.turboism.sdk.cubism.textureatlas;

import dev.turboism.sdk.CubismEditor;
import java.util.List;

/**
 * Framework contract for a texture-atlas layout algorithm.
 *
 * <p>Algorithms registered through {@link TextureAtlasLayoutAlgorithmRegistry} become
 * selectable in the native automatic-layout dialog and receive the user's parallel
 * search flag through {@link #plan(List, TextureAtlasLayoutConstraints, boolean)}.
 * Implementations that do not support parallel search simply inherit the two-argument
 * fallback and ignore the flag.</p>
 */
@FunctionalInterface
@CubismEditor
public interface TextureAtlasLayoutPlanner {

    /**
     * Computes a layout plan for {@code items} under {@code constraints}.
     *
     * @param items the images to place
     * @param constraints the atlas size, padding, and rotation/scaling limits
     * @return a layout plan accepted by {@link TextureAtlasLayoutService#apply}
     * @throws TextureAtlasPackingException when the issued page cannot fit the inputs;
     *     complete-atlas clients can try larger pages or divide folders for recoverable reasons
     */
    TextureAtlasLayoutPlan plan(List<TextureAtlasLayoutItem> items, TextureAtlasLayoutConstraints constraints);

    /**
     * Whether this planner accepts complete-atlas rectangle requests without current-page options.
     * Current-page-only implementations return false so independent authoring tools can disable them.
     */
    default boolean supportsCompleteAtlas() {
        return true;
    }

    /**
     * Parallel variant. The default ignores {@code parallel} and delegates to the
     * serial {@link #plan(List, TextureAtlasLayoutConstraints)}; algorithms that can
     * parallelize their search should override this and preserve result determinism.
     */
    default TextureAtlasLayoutPlan plan(
            final List<TextureAtlasLayoutItem> items,
            final TextureAtlasLayoutConstraints constraints,
            final boolean parallel) {
        return plan(items, constraints);
    }
}
