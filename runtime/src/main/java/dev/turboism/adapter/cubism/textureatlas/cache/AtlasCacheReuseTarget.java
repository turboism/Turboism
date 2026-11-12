package dev.turboism.adapter.cubism.textureatlas.cache;

import java.util.Objects;

/**
 * One reviewed host target for the atlas cache-reuse guard: the class whose
 * {@code updateTexture(boolean, boolean, a.a)} is wrapped with the content-signature
 * check.
 *
 * <p>{@code CTextureAtlas} carries the reviewed method shape on every reviewed Cubism
 * build, so the target is scoped by internal name and the whole-artifact identity; the
 * rewrite gate then verifies the exact body it substitutes.</p>
 */
public record AtlasCacheReuseTarget(String internalName, String className, String hostLabel) {

    /** Reviewed target: the atlas class shared by every reviewed build. */
    static final AtlasCacheReuseTarget CUBISM_52_53 = new AtlasCacheReuseTarget(
            "com/live2d/cubism/doc/model/texture/textureAtlas/CTextureAtlas", "5.2.00-5.3.04");

    /** Reviewed target: the 5.1.x atlas class. */
    static final AtlasCacheReuseTarget CUBISM_51 = new AtlasCacheReuseTarget(
            "com/live2d/cubism/doc/model/texture/textureAtlas/CTextureAtlas", "5.1.00-5.1.03");

    /** Reviewed target: the 5.0.x atlas class. */
    static final AtlasCacheReuseTarget CUBISM_50 = new AtlasCacheReuseTarget(
            "com/live2d/cubism/doc/model/texture/textureAtlas/CTextureAtlas", "5.0.00-5.0.06");

    /** Every reviewed target, in table order. */
    static final AtlasCacheReuseTarget[] REVIEWED = {CUBISM_52_53, CUBISM_51, CUBISM_50};

    AtlasCacheReuseTarget(final String internalName, final String hostLabel) {
        this(internalName, internalName.replace('/', '.'), hostLabel);
    }

    public AtlasCacheReuseTarget {
        Objects.requireNonNull(internalName, "internalName");
        Objects.requireNonNull(className, "className");
        Objects.requireNonNull(hostLabel, "hostLabel");
    }

    /** The reviewed target for an internal class name, or {@code null} when unsupported. */
    static AtlasCacheReuseTarget reviewed(final String internalName) {
        for (final AtlasCacheReuseTarget target : REVIEWED) {
            if (target.internalName().equals(internalName)) return target;
        }
        return null;
    }
}
