package dev.turboism.adapter.cubism.textureatlas;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;

class VerifiedTextureAtlasLayoutProvider5303ProfileTest {

    private static final VerifiedTextureAtlasSelectorContract.Profile PROFILE_5_3_03 =
        VerifiedTextureAtlasSelectorContract.profileFor("5.3.03").orElseThrow();

    @Test
    void exactProfileStillRequiresDedicatedAuthoringAuthorization() {
        final TextureAtlasDataModelCapture capture = new TextureAtlasDataModelCapture();
        assertFalse(new VerifiedTextureAtlasLayoutProvider(
            resolver("5.3.03", Set.of("cubism.editor-model.read")),
            "session-5303",
            capture,
            PROFILE_5_3_03
        ).current().isPresent());
        assertFalse(new VerifiedTextureAtlasLayoutProvider(
            resolver("5.3.02", Set.of(VerifiedTextureAtlasSelectorContract.CAPABILITY_ID)),
            "session-5302",
            capture,
            PROFILE_5_3_03
        ).current().isPresent());
    }

    private VerifiedMemberResolver resolver(
        final String version,
        final Set<String> capabilities
    ) {
        final String owner = getClass().getName().replace('.', '/');
        final List<StaticSelector> selectors =
            VerifiedTextureAtlasSelectorContract.REQUIRED_ALIASES.stream()
                .map(alias -> StaticSelector.classSelector(alias, owner))
                .toList();
        return TestVerifiedResolvers.create(
            version,
            VerifiedTextureAtlasSelectorContract.ADAPTER_SLICE_ID,
            capabilities,
            selectors,
            getClass().getClassLoader()
        );
    }
}
