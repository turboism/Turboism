package dev.turboism.core.plugin.context;

import dev.turboism.sdk.cubism.CubismEditorApiUnavailableException;
import dev.turboism.sdk.cubism.id.ModelImageId;
import dev.turboism.sdk.cubism.id.RawImageId;
import dev.turboism.sdk.cubism.id.TextureAtlasId;
import dev.turboism.sdk.cubism.model.AtlasTexture;
import dev.turboism.sdk.cubism.model.ModelImageGroup;
import dev.turboism.sdk.cubism.model.ModelTextures;
import dev.turboism.sdk.cubism.model.RawTexture;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Exercises the same SDK proxy boundary used by the MCP texture resource. */
final class TextureMetadataAvailabilityRegressionTest {

    @Test
    void reviewed5303AllowsAllThreeTextureMetadataReads() {
        final RecordingTextures delegate = new RecordingTextures();
        final ModelTextures textures = wrapped("5.3.03", delegate);
        assertEquals(List.of(), textures.rawImages());
        assertEquals(List.of(), textures.modelImageGroups());
        assertEquals(List.of(), textures.textureAtlases());
        assertEquals(3, delegate.reads);
    }

    @Test
    void admittingMetadataDoesNotAdmitAny5303TextureWrites() {
        final RecordingTextures delegate = new RecordingTextures();
        final ModelTextures textures = wrapped("5.3.03", delegate);
        assertThrows(CubismEditorApiUnavailableException.class,
            () -> textures.addModelImageGroup("group"));
        assertThrows(CubismEditorApiUnavailableException.class,
            () -> textures.removeModelImage(new ModelImageId("image")));
        assertThrows(CubismEditorApiUnavailableException.class,
            () -> textures.addTextureAtlas("atlas", 1024, 1024));
        assertThrows(CubismEditorApiUnavailableException.class,
            () -> textures.removeTextureAtlas(new TextureAtlasId("atlas")));
        assertThrows(CubismEditorApiUnavailableException.class,
            () -> textures.removeRawImage(new RawImageId("raw")));
        assertEquals(0, delegate.writes);
    }

    @Test
    void establishedVersionsRetainReadAndWriteAvailability() {
        for (String version : List.of("5.2.03", "5.3.02")) {
            final RecordingTextures delegate = new RecordingTextures();
            final ModelTextures textures = wrapped(version, delegate);
            textures.rawImages();
            textures.modelImageGroups();
            textures.textureAtlases();
            textures.addModelImageGroup("group");
            textures.removeModelImage(new ModelImageId("image"));
            textures.addTextureAtlas("atlas", 1024, 1024);
            textures.removeTextureAtlas(new TextureAtlasId("atlas"));
            if (version.equals("5.3.02")) {
                textures.removeRawImage(new RawImageId("raw"));
                assertEquals(5, delegate.writes);
            } else {
                assertThrows(CubismEditorApiUnavailableException.class,
                    () -> textures.removeRawImage(new RawImageId("raw")));
                assertEquals(4, delegate.writes);
            }
            assertEquals(3, delegate.reads);
        }
    }

    @Test
    void unknownVersionIsRejectedBeforeAnyTextureRead() {
        final RecordingTextures delegate = new RecordingTextures();
        final ModelTextures textures = wrapped("5.3.04", delegate);
        assertThrows(CubismEditorApiUnavailableException.class, textures::rawImages);
        assertThrows(CubismEditorApiUnavailableException.class, textures::modelImageGroups);
        assertThrows(CubismEditorApiUnavailableException.class, textures::textureAtlases);
        assertEquals(0, delegate.reads);
    }

    private static ModelTextures wrapped(final String version, final RecordingTextures delegate) {
        return new CubismEditorApiAvailabilityInterceptor(() -> Optional.of(version))
            .wrapForTesting(delegate, ModelTextures.class);
    }

    private static final class RecordingTextures implements ModelTextures {
        private int reads;
        private int writes;

        @Override public List<RawTexture> rawImages() { reads++; return List.of(); }
        @Override public List<ModelImageGroup> modelImageGroups() { reads++; return List.of(); }
        @Override public List<AtlasTexture> textureAtlases() { reads++; return List.of(); }
        @Override public void addModelImageGroup(final String name) { writes++; }
        @Override public void removeModelImage(final ModelImageId id) { writes++; }
        @Override public TextureAtlasId addTextureAtlas(final String name, final int width, final int height) {
            writes++;
            return new TextureAtlasId("atlas");
        }
        @Override public void removeTextureAtlas(final TextureAtlasId id) { writes++; }
        @Override public void removeRawImage(final RawImageId id) { writes++; }
    }
}
