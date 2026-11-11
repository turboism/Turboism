package dev.turboism.adapter.cubism.textureatlas.image;

import java.util.Objects;

/**
 * One reviewed host target for the atlas tile-bbox scratch shrink: the class whose private
 * per-image alpha workaround is replaced.
 *
 * <p>Cubism carries the same workaround under different class names across versions — it is
 * {@code com/live2d/util/f/g} on 5.3.x and {@code com/live2d/util/e/g} on 5.2.03 — with an
 * instruction-identical private kernel calling the same byte-identical {@code jp.noids.*}
 * helpers. The shared shape gate in {@link AtlasTileBboxPatcher} admits both; this table names
 * the reviewed class per generation. Any class name not listed here fails closed.</p>
 */
public record AtlasTileBboxTarget(String internalName, String className, String hostLabel) {

    /**
     * Cubism 5.3.x target: {@code com/live2d/util/f/g}, shared across 5.3.00–5.3.04;
     * the same name also ships in 5.0.00–5.0.02, which never reach this hook.
     */
    static final AtlasTileBboxTarget CUBISM_53X = new AtlasTileBboxTarget("com/live2d/util/f/g", "5.3.00-5.3.04");

    /** Cubism 5.2.x target: {@code com/live2d/util/e/g}, shared across 5.2.00–5.2.03. */
    static final AtlasTileBboxTarget CUBISM_5203 = new AtlasTileBboxTarget("com/live2d/util/e/g", "5.2.00-5.2.03");

    /** Cubism 5.0.03–5.1.03 target: {@code com/live2d/util/e/g}, shared across 5.0.03–5.1.03. */
    static final AtlasTileBboxTarget CUBISM_5003_5103 = new AtlasTileBboxTarget("com/live2d/util/e/g", "5.0.03-5.1.03");

    /** Cubism 5.0.00–5.0.02 target: {@code com/live2d/util/d/g}, shared across 5.0.00–5.0.02. */
    static final AtlasTileBboxTarget CUBISM_5000_5002 = new AtlasTileBboxTarget("com/live2d/util/d/g", "5.0.00-5.0.02");

    /** Every reviewed target, in table order. */
    static final AtlasTileBboxTarget[] REVIEWED = {CUBISM_53X, CUBISM_5203, CUBISM_5003_5103, CUBISM_5000_5002};

    AtlasTileBboxTarget(final String internalName, final String hostLabel) {
        this(internalName, internalName.replace('/', '.'), hostLabel);
    }

    public AtlasTileBboxTarget {
        Objects.requireNonNull(internalName, "internalName");
        Objects.requireNonNull(className, "className");
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
