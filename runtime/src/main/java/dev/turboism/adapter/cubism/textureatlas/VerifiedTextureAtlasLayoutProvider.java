package dev.turboism.adapter.cubism.textureatlas;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutPlan;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * Exact texture-atlas authoring provider bound to the reviewed selector {@link
 * VerifiedTextureAtlasSelectorContract.Profile Profile} supplied by the caller, so the host
 * version is data rather than part of the type name.
 */
public final class VerifiedTextureAtlasLayoutProvider implements TextureAtlasLayoutProvider {

    private final VerifiedTextureAtlasLayoutProviderEngine engine;

    /**
     * @param ambientScopeActive reports whether an authoring transaction ambient scope is open
     *        on the calling thread; while it reports active, {@link #apply} refuses with
     *        {@link ApplyOutcome#REJECTED} before any native edit, staging or image work
     */
    public VerifiedTextureAtlasLayoutProvider(
            final VerifiedMemberResolver resolver,
            final String sessionIdentity,
            final TextureAtlasDataModelCapture capture,
            final VerifiedTextureAtlasSelectorContract.Profile profile,
            final BooleanSupplier ambientScopeActive) {
        Objects.requireNonNull(profile, "profile");
        engine = new VerifiedTextureAtlasLayoutProviderEngine(
                resolver,
                sessionIdentity,
                capture,
                profile.cubismVersion(),
                VerifiedTextureAtlasSelectorContract.ADAPTER_SLICE_ID,
                VerifiedTextureAtlasSelectorContract.CAPABILITY_ID,
                profile.requiredAliases(),
                ambientScopeActive);
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
