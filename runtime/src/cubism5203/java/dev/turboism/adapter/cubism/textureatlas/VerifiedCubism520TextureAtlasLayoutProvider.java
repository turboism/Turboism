package dev.turboism.adapter.cubism.textureatlas;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutPlan;

import java.util.Objects;
import java.util.Optional;

/** Exact Cubism 5.2.0 texture-atlas authoring provider. */
public final class VerifiedCubism520TextureAtlasLayoutProvider implements TextureAtlasLayoutProvider {

    private final VerifiedTextureAtlasLayoutProviderEngine engine;

    public VerifiedCubism520TextureAtlasLayoutProvider(
        final VerifiedMemberResolver resolver,
        final String sessionIdentity,
        final TextureAtlasDataModelCapture capture
    ) {
        engine = new VerifiedTextureAtlasLayoutProviderEngine(
            resolver,
            sessionIdentity,
            capture,
            "5.2.03",
            VerifiedCubism520TextureAtlasSelectorContract.ADAPTER_SLICE_ID,
            VerifiedCubism520TextureAtlasSelectorContract.CAPABILITY_ID,
            VerifiedCubism520TextureAtlasSelectorContract.REQUIRED_ALIASES
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
