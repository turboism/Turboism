package dev.turboism.adapter.cubism.textureatlas;

import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutAlgorithm;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RuntimeTextureAtlasLayoutAlgorithmRegistryTest {

    @Test
    void closeRemovesOnlyTheExactRegistrationGeneration() {
        final RuntimeTextureAtlasLayoutAlgorithmRegistry registry =
            new RuntimeTextureAtlasLayoutAlgorithmRegistry();
        final TextureAtlasLayoutAlgorithm first = new TextureAtlasLayoutAlgorithm(
            "layout",
            "First",
            false,
            null
        );
        final TextureAtlasLayoutAlgorithm second = new TextureAtlasLayoutAlgorithm(
            "layout",
            "Second",
            false,
            null
        );

        final var firstRegistration = registry.register(first);
        final var secondRegistration = registry.register(second);
        firstRegistration.close();

        assertEquals("Second", registry.find("layout").orElseThrow().displayName());
        secondRegistration.close();
        assertTrue(registry.find("layout").isEmpty());
    }
}
