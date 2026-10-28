package dev.turboism.adapter.cubism.textureatlas.cache;

import java.util.Objects;

/**
 * One reviewed host target for the atlas cache-reuse guard: the class whose
 * {@code updateTexture(boolean, boolean, a.a)} is wrapped with the content-signature
 * check, pinned by internal name and class SHA-256.
 *
 * <p>{@code CTextureAtlas} is byte-identical across every reviewed Cubism 5.2.x and 5.3.x
 * build (5.2.00–5.3.04), so a single digest admits all of them. Any bytes not matching the
 * listed digest fail closed.</p>
 */
public record AtlasCacheReuseTarget(String internalName, String className, String reviewedSha256, String hostLabel) {

    /** Reviewed target: byte-identical on every 5.2.x and 5.3.x build. */
    static final AtlasCacheReuseTarget CUBISM_52_53 = new AtlasCacheReuseTarget(
            "com/live2d/cubism/doc/model/texture/textureAtlas/CTextureAtlas",
            "cec7892e7581fc88f5a76a8fe89fce53fcfb6c2f66717ba9fc202fc6ceaf3ef3",
            "5.2.00-5.3.04");

    /** Reviewed target: byte-identical on every 5.1.x build (5.1.00–5.1.03). */
    static final AtlasCacheReuseTarget CUBISM_51 = new AtlasCacheReuseTarget(
            "com/live2d/cubism/doc/model/texture/textureAtlas/CTextureAtlas",
            "2a2989618eb4b2c3fcfb2dae3639416d20dfcc6ed62922a15f45d4c165096933",
            "5.1.00-5.1.03");

    /** Reviewed target: byte-identical on every 5.0.x build (5.0.00–5.0.06). */
    static final AtlasCacheReuseTarget CUBISM_50 = new AtlasCacheReuseTarget(
            "com/live2d/cubism/doc/model/texture/textureAtlas/CTextureAtlas",
            "d1d1e86eced7228573111477ca123876725ea5c8b9bb2c3eec81e663a7e6b88e",
            "5.0.00-5.0.06");

    /** Every reviewed target, in table order. */
    static final AtlasCacheReuseTarget[] REVIEWED = {CUBISM_52_53, CUBISM_51, CUBISM_50};

    AtlasCacheReuseTarget(final String internalName, final String reviewedSha256, final String hostLabel) {
        this(internalName, internalName.replace('/', '.'), reviewedSha256, hostLabel);
    }

    public AtlasCacheReuseTarget {
        Objects.requireNonNull(internalName, "internalName");
        Objects.requireNonNull(className, "className");
        Objects.requireNonNull(reviewedSha256, "reviewedSha256");
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
