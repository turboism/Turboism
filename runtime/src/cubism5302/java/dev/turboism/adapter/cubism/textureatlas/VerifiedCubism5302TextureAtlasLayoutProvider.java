package dev.turboism.adapter.cubism.textureatlas;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutPlan;

import java.util.Objects;
import java.util.Optional;

/** Exact Cubism 5.3.02 texture-atlas authoring provider. */
public final class VerifiedCubism5302TextureAtlasLayoutProvider implements TextureAtlasLayoutProvider {

    private final VerifiedTextureAtlasLayoutProviderEngine engine;

    public VerifiedCubism5302TextureAtlasLayoutProvider(
        final VerifiedMemberResolver resolver,
        final String sessionIdentity,
        final TextureAtlasDataModelCapture capture
    ) {
        engine = new VerifiedTextureAtlasLayoutProviderEngine(
            resolver,
            sessionIdentity,
            capture,
            "5.3.02",
            VerifiedCubism5302TextureAtlasSelectorContract.ADAPTER_SLICE_ID,
            VerifiedCubism5302TextureAtlasSelectorContract.CAPABILITY_ID,
            VerifiedCubism5302TextureAtlasSelectorContract.REQUIRED_ALIASES
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
