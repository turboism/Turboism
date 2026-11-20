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
    static final AtlasCacheReuseTarget REVIEWED_TARGET = new AtlasCacheReuseTarget(
            "com/live2d/cubism/doc/model/texture/textureAtlas/CTextureAtlas", "all reviewed builds");

    /** Every reviewed target, in table order. */
    static final AtlasCacheReuseTarget[] REVIEWED = {REVIEWED_TARGET};

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
