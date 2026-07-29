package dev.turboism.adapter.cubism.textureatlas;

import dev.turboism.mapping.verification.VerifiedMemberResolver;

/** Exact Cubism 5.2.0 texture-atlas authoring provider. */
public final class VerifiedCubism520TextureAtlasLayoutProvider
    extends VerifiedCubism5302TextureAtlasLayoutProvider {

    public VerifiedCubism520TextureAtlasLayoutProvider(
        final VerifiedMemberResolver resolver,
        final String sessionIdentity,
        final TextureAtlasDataModelCapture capture
    ) {
        super(resolver, sessionIdentity, capture, "5.2.0");
    }
}
