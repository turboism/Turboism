package dev.turboism.plugin.turbomesh.domain.regions;

/**
 * One polyline together with the region pair it separates.
 *
 * <p>Texture paths carry the two region labels, {@code 0} marking the empty side, so the caller
 * can tag the shared border without re-reading the label raster. Direction guides carry
 * {@link #GUIDE_LABEL} on both sides.</p>
 */
record BoundaryPath(int labelA, int labelB, Polyline line) {

    /** Label pair marker for shape direction guides. */
    static final int GUIDE_LABEL = -1;

    static BoundaryPath guide(final Polyline line) {
        return new BoundaryPath(GUIDE_LABEL, GUIDE_LABEL, line);
    }

    boolean isGuide() {
        return labelA == GUIDE_LABEL;
    }

    boolean isOutline() {
        return !isGuide() && (labelA == 0 || labelB == 0);
    }

    ConstraintRole role() {
        if (isGuide()) {
            return ConstraintRole.DIRECTION_LINE;
        }
        return isOutline() ? ConstraintRole.OUTLINE : ConstraintRole.TEXTURE_LINE;
    }

    BoundaryPath withLine(final Polyline replacement) {
        return new BoundaryPath(labelA, labelB, replacement);
    }
}
