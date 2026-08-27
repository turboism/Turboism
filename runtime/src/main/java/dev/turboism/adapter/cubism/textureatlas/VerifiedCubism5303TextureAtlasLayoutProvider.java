package dev.turboism.adapter.cubism.textureatlas;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutPlan;

import java.util.Objects;
import java.util.Optional;

/** Exact Cubism 5.3.03 texture-atlas authoring provider. */
public final class VerifiedCubism5303TextureAtlasLayoutProvider implements TextureAtlasLayoutProvider {

    private final VerifiedTextureAtlasLayoutProviderEngine engine;

    public VerifiedCubism5303TextureAtlasLayoutProvider(
        final VerifiedMemberResolver resolver,
        final String sessionIdentity,
        final TextureAtlasDataModelCapture capture
    ) {
        engine = new VerifiedTextureAtlasLayoutProviderEngine(
            resolver,
            sessionIdentity,
            capture,
            "5.3.03",
            VerifiedCubism5303TextureAtlasSelectorContract.ADAPTER_SLICE_ID,
            VerifiedCubism5303TextureAtlasSelectorContract.CAPABILITY_ID,
            VerifiedCubism5303TextureAtlasSelectorContract.REQUIRED_ALIASES
        );
    }

    @Override
    public Optional<TextureAtlasAuthoringState> current() {
        return engine.current();
    }

    @Override
    public ApplyOutcome apply(final TextureAtlasAuthoringState expected, final TextureAtlasLayoutPlan plan) {
        return engine.apply(Objects.requireNonNull(expected, "expected"), Objects.requireNonNull(plan, "plan"));
    }
}
