package dev.turboism.plugin.atlasmaxrectsbssf.layout;

/** Compatibility type for existing MaxRects clients; the contract is shared through the SDK. */
public final class TextureAtlasPackingException
        extends dev.turboism.sdk.cubism.textureatlas.TextureAtlasPackingException {
    TextureAtlasPackingException(final String textureId, final Reason reason) {
        super(textureId, reason);
    }
}
