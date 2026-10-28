package dev.turboism.adapter.cubism.textureatlas.image;

import java.util.Objects;

/**
 * One reviewed host target for the atlas tile-bbox scratch shrink: the class whose private
 * per-image alpha workaround is replaced, pinned by internal name and class SHA-256.
 *
 * <p>Cubism carries the same workaround under different class names across versions — it is
 * {@code com/live2d/util/f/g} on 5.3.x and {@code com/live2d/util/e/g} on 5.2.03 — with an
 * instruction-identical private kernel calling the same byte-identical {@code jp.noids.*}
 * helpers. The shared shape gate in {@link AtlasTileBboxPatcher} therefore admits both; what
 * distinguishes a supported host is this table of reviewed class digests. Any class name not
 * listed here, and any bytes not matching the listed digest, fail closed.</p>
 */
public record AtlasTileBboxTarget(String internalName, String className, String reviewedSha256, String hostLabel) {

    /**
     * Cubism 5.3.x target: {@code com/live2d/util/f/g}, byte-identical across 5.3.00–5.3.04;
     * the same bytes also ship in 5.0.00–5.0.02, which never reach this hook.
     */
    static final AtlasTileBboxTarget CUBISM_53X = new AtlasTileBboxTarget(
            "com/live2d/util/f/g", "ff1d1ce9b4291212d255c6e84c8b57242234c1fa174af09e440c1162a4a1f8d6", "5.3.00-5.3.04");

    /** Cubism 5.2.x target: {@code com/live2d/util/e/g}, byte-identical across 5.2.00–5.2.03. */
    static final AtlasTileBboxTarget CUBISM_5203 = new AtlasTileBboxTarget(
            "com/live2d/util/e/g", "9df616c60ade56657214465b2b18f18df0605faeeac28d99663ce959af11615b", "5.2.00-5.2.03");

    /** Cubism 5.0.03–5.1.03 target: {@code com/live2d/util/e/g}, byte-identical across 5.0.03–5.1.03. */
    static final AtlasTileBboxTarget CUBISM_5003_5103 = new AtlasTileBboxTarget(
            "com/live2d/util/e/g", "c58146065a659f83e9f4e1a550cb061bc2ac1162939f86879eb1162613a1f062", "5.0.03-5.1.03");

    /** Cubism 5.0.00–5.0.02 target: {@code com/live2d/util/d/g}, byte-identical across 5.0.00–5.0.02. */
    static final AtlasTileBboxTarget CUBISM_5000_5002 = new AtlasTileBboxTarget(
            "com/live2d/util/d/g", "452802efce429cf36fa48c96b729500f884be275e1ee67628898fe6770f84187", "5.0.00-5.0.02");

    /** Every reviewed target, in table order. */
    static final AtlasTileBboxTarget[] REVIEWED = {CUBISM_53X, CUBISM_5203, CUBISM_5003_5103, CUBISM_5000_5002};

    AtlasTileBboxTarget(final String internalName, final String reviewedSha256, final String hostLabel) {
        this(internalName, internalName.replace('/', '.'), reviewedSha256, hostLabel);
    }

    public AtlasTileBboxTarget {
        Objects.requireNonNull(internalName, "internalName");
        Objects.requireNonNull(className, "className");
        Objects.requireNonNull(reviewedSha256, "reviewedSha256");
        Objects.requireNonNull(hostLabel, "hostLabel");
    }

    /** The reviewed target for an internal class name, or {@code null} when unsupported. */
    static AtlasTileBboxTarget reviewed(final String internalName) {
        for (final AtlasTileBboxTarget target : REVIEWED) {
            if (target.internalName().equals(internalName)) return target;
        }
        return null;
    }
}
