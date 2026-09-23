package dev.turboism.plugin.atlasdalsoo.dalsoo;

import java.util.Objects;

/**
 * One input polygon for the packing kernel, with per-item transform locks.
 *
 * <p>{@code inpts} is the item outline (material-local, already scaled by the item
 * scale); {@code outpts} is the margin-buffered outline used for placement tests.
 * The lock flags mirror Cubism 5.4's per-object automatic-layout controls:
 * {@code fixPosition} keeps the issued translation and angle (the item becomes a
 * fixed obstacle), {@code fixRotate} pins the rotation to {@code issuedCosSin},
 * {@code fixScale} marks that the outline already carries the item's own scale.</p>
 */
final class SourcePoly {

    final int id;
    final String textureId;
    final double[][] inpts;
    final double[][] outpts;
    final boolean fixPosition;
    final boolean fixRotate;
    final boolean fixScale;
    final double[] issuedCosSin;
    final double[] issuedPosition; // translation of the issued transform, when placed
    final boolean issuedPlaced;

    private SourcePoly(final Builder builder) {
        this.id = builder.id;
        this.textureId = builder.textureId;
        this.inpts = builder.inpts;
        this.outpts = builder.outpts;
        this.fixPosition = builder.fixPosition;
        this.fixRotate = builder.fixRotate || builder.fixPosition;
        this.fixScale = builder.fixScale;
        this.issuedCosSin = builder.issuedCosSin == null
            ? new double[] {1, 0}
            : builder.issuedCosSin.clone();
        this.issuedPosition = builder.issuedPosition;
        this.issuedPlaced = builder.issuedPlaced;
    }

    static Builder builder(final int id, final String textureId,
        final double[][] inpts, final double[][] outpts) {
        return new Builder(id, textureId, inpts, outpts);
    }

    static final class Builder {
        private final int id;
        private final String textureId;
        private final double[][] inpts;
        private final double[][] outpts;
        private boolean fixPosition;
        private boolean fixRotate;
        private boolean fixScale;
        private double[] issuedCosSin;
        private double[] issuedPosition;
        private boolean issuedPlaced;

        private Builder(final int id, final String textureId,
            final double[][] inpts, final double[][] outpts) {
            this.id = id;
            this.textureId = Objects.requireNonNull(textureId, "textureId");
            this.inpts = Objects.requireNonNull(inpts, "inpts");
            this.outpts = Objects.requireNonNull(outpts, "outpts");
        }

        Builder fixPosition(final boolean value) {
            this.fixPosition = value;
            return this;
        }

        Builder fixRotate(final boolean value) {
            this.fixRotate = value;
            return this;
        }

        Builder fixScale(final boolean value) {
            this.fixScale = value;
            return this;
        }

        Builder issuedCosSin(final double[] value) {
            this.issuedCosSin = value;
            return this;
        }

        Builder issuedPosition(final double[] value) {
            this.issuedPosition = value;
            return this;
        }

        Builder issuedPlaced(final boolean value) {
            this.issuedPlaced = value;
            return this;
        }

        SourcePoly build() {
            return new SourcePoly(this);
        }
    }
}
