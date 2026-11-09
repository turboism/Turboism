package dev.turboism.plugin.turbomesh.domain.regions;

/**
 * One polyline together with the region pair it separates.
 *
 * <p>Texture paths carry the two region labels, {@code 0} marking the empty side, so the caller
 * can tag the shared border without re-reading the label raster.</p>
 */
record BoundaryPath(int labelA, int labelB, Polyline line) {

    boolean isOutline() {
        return labelA == 0 || labelB == 0;
    }

    ConstraintRole role() {
        return isOutline() ? ConstraintRole.OUTLINE : ConstraintRole.TEXTURE_LINE;
    }

    BoundaryPath withLine(final Polyline replacement) {
        return new BoundaryPath(labelA, labelB, replacement);
    }
}
