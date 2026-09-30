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

    /** Every reviewed target, in table order. */
    static final AtlasCacheReuseTarget[] REVIEWED = {CUBISM_52_53};

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
