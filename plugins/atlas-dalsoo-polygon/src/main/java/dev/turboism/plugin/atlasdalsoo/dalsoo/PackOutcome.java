package dev.turboism.plugin.atlasdalsoo.dalsoo;

/** Placement of one packed polygon: translation plus rotation as cos/sin. */
public final class PackOutcome {

    public final int id;
    public final double[] cosSin;
    public final double[] trans;

    PackOutcome(final int id, final double[] cosSin, final double[] trans) {
        this.id = id;
        this.cosSin = cosSin;
        this.trans = trans;
    }

    /** The placed rotation angle in degrees. */
    public double angleDeg() {
        return Math.toDegrees(Math.atan2(cosSin[1], cosSin[0]));
    }
}
