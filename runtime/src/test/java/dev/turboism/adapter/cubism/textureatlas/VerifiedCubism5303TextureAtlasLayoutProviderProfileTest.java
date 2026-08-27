package dev.turboism.adapter.cubism.textureatlas;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;

class VerifiedCubism5303TextureAtlasLayoutProviderProfileTest {

    @Test
    void exactProfileStillRequiresDedicatedAuthoringAuthorization() {
        final TextureAtlasDataModelCapture capture = new TextureAtlasDataModelCapture();
        assertFalse(new VerifiedCubism5303TextureAtlasLayoutProvider(
            resolver("5.3.03", Set.of("cubism.editor-model.read")),
            "session-5303",
            capture
        ).current().isPresent());
        assertFalse(new VerifiedCubism5303TextureAtlasLayoutProvider(
            resolver("5.3.02", Set.of(VerifiedCubism5303TextureAtlasSelectorContract.CAPABILITY_ID)),
            "session-5302",
            capture
        ).current().isPresent());
    }

    private VerifiedMemberResolver resolver(
        final String version,
        final Set<String> capabilities
    ) {
        final String owner = getClass().getName().replace('.', '/');
        final List<StaticSelector> selectors =
            VerifiedCubism5303TextureAtlasSelectorContract.REQUIRED_ALIASES.stream()
                .map(alias -> StaticSelector.classSelector(alias, owner))
                .toList();
        return TestVerifiedResolvers.create(
            version,
            VerifiedCubism5303TextureAtlasSelectorContract.ADAPTER_SLICE_ID,
            capabilities,
            selectors,
            getClass().getClassLoader()
        );
    }
}
