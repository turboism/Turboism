package dev.turboism.sdk.cubism.textureatlas;

import dev.turboism.sdk.PreviewApi;

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
@PreviewApi
@FunctionalInterface
public interface TextureAtlasLayoutPlanner {

    TextureAtlasLayoutPlan plan(
        List<TextureAtlasLayoutItem> items,
        TextureAtlasLayoutConstraints constraints
    );

    /**
     * Parallel variant. The default ignores {@code parallel} and delegates to the
     * serial {@link #plan(List, TextureAtlasLayoutConstraints)}; algorithms that can
     * parallelize their search should override this and preserve result determinism.
     */
    @PreviewApi
    default TextureAtlasLayoutPlan plan(
        final List<TextureAtlasLayoutItem> items,
        final TextureAtlasLayoutConstraints constraints,
        final boolean parallel
    ) {
        return plan(items, constraints);
    }
}
