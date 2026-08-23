package dev.turboism.adapter.cubism.lifecycle;

import dev.turboism.sdk.cubism.model.WarpDeformer;
import dev.turboism.sdk.cubism.model.WarpGrid;

/** Immutable, host-detached Warp Deformer projection for event delivery. */
final class DetachedWarpDeformer extends DetachedDeformer implements WarpDeformer {
    private final WarpGrid grid;

    private DetachedWarpDeformer(
        final WarpDeformer source,
        final float opacity,
        final WarpGrid grid
    ) {
        super(source, opacity);
        this.grid = java.util.Objects.requireNonNull(grid, "grid");
    }

    static DetachedWarpDeformer capture(
        final WarpDeformer deformer,
        final float opacity,
        final WarpGrid grid
    ) {
        return new DetachedWarpDeformer(
            java.util.Objects.requireNonNull(deformer, "deformer"),
            opacity,
            grid
        );
    }

    @Override public WarpGrid grid() { return grid; }
    @Override public void replaceGrid(final WarpGrid grid) { throw detachedWarp(); }

    private static UnsupportedOperationException detachedWarp() {
        return new UnsupportedOperationException(
            "Event Warp Deformer snapshots are read-only and host-detached."
        );
    }
}
