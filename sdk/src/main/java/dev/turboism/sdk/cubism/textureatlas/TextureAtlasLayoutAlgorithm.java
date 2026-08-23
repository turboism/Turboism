package dev.turboism.sdk.cubism.textureatlas;


import java.util.Objects;

/**
 * One registered texture-atlas layout algorithm.
 *
 * <p>A {@code null} planner designates the pass-through algorithm (Cubism's native
 * packing): the automatic-layout entry is delegated to the host and no Turboism
 * plan is produced. A non-null planner receives the automatic-layout invocation and
 * the user's parallel-search flag.</p>
 */
public record TextureAtlasLayoutAlgorithm(
    String id,
    String displayName,
    boolean supportsParallel,
    TextureAtlasLayoutPlanner planner
) {
    public TextureAtlasLayoutAlgorithm {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        if (id.isBlank()) throw new IllegalArgumentException("id must not be blank");
        if (displayName.isBlank()) throw new IllegalArgumentException("displayName must not be blank");
    }

    /** True for the pass-through (Cubism native) algorithm. */
    public boolean isNative() {
        return planner == null;
    }
}
