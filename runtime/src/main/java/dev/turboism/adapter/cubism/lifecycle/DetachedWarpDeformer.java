package dev.turboism.adapter.cubism.lifecycle;

import dev.turboism.sdk.cubism.model.WarpDeformer;
import dev.turboism.sdk.cubism.model.WarpGrid;

/** Immutable, host-detached Warp Deformer projection for event delivery. */
final class DetachedWarpDeformer extends DetachedDeformer implements WarpDeformer {
    private final DetachedValue<WarpGrid> grid;

    private DetachedWarpDeformer(
        final WarpDeformer source,
        final float opacity,
        final DetachedValue<WarpGrid> grid
    ) {
        super(source, opacity);
        this.grid = java.util.Objects.requireNonNull(grid, "grid");
    }

    static DetachedWarpDeformer capture(final WarpDeformer deformer, final float opacity) {
        return new DetachedWarpDeformer(
            deformer, opacity, DetachedValue.capture("grid", deformer::grid)
        );
    }

    static DetachedWarpDeformer capture(
        final WarpDeformer deformer,
        final float opacity,
        final WarpGrid grid
    ) {
        return new DetachedWarpDeformer(
            java.util.Objects.requireNonNull(deformer, "deformer"),
            opacity,
            DetachedValue.capture("grid", () -> java.util.Objects.requireNonNull(grid, "grid"))
        );
    }

    @Override public WarpGrid grid() { return grid.get(); }
    @Override public void replaceGrid(final WarpGrid grid) { throw detachedWarp(); }

    private static UnsupportedOperationException detachedWarp() {
        return new UnsupportedOperationException(
            "Event Warp Deformer snapshots are read-only and host-detached."
        );
    }
}
