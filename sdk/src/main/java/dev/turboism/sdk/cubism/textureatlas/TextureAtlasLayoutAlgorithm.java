package dev.turboism.sdk.cubism.textureatlas;


import java.util.Objects;

/**
 * One registered texture-atlas layout algorithm.
 *
 * <p>A {@code null} planner designates the pass-through algorithm (Cubism's native
 * packing): the automatic-layout entry is delegated to the host and no Turboism
 * plan is produced. A non-null planner receives the automatic-layout invocation and
 * the user's parallel-search flag.</p>
 *
 * <p>{@code supportsPolygonOptions} declares that the algorithm consumes the
 * extended packing controls (rotation granularity, lock preset, scale mode and
 * auto-scale tuning, kernel choice); the dialog enables those controls only for
 * algorithms declaring it and they are ignored for every other selection.</p>
 */
public record TextureAtlasLayoutAlgorithm(
    String id,
    String displayName,
    boolean supportsParallel,
    boolean supportsPolygonOptions,
    TextureAtlasLayoutPlanner planner
) {
    /**
     * Compatibility constructor keeping the pre-polygon-options signature:
     * the algorithm declares no extended packing-option support.
     */
    public TextureAtlasLayoutAlgorithm(
        final String id,
        final String displayName,
        final boolean supportsParallel,
        final TextureAtlasLayoutPlanner planner
    ) {
        this(id, displayName, supportsParallel, false, planner);
    }

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
