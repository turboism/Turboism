package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ModelImageId;
import dev.turboism.sdk.cubism.id.RawImageId;
import dev.turboism.sdk.cubism.id.RawLayerId;
import dev.turboism.sdk.cubism.id.TextureAtlasId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextureRelationsContractTest {

    @Test
    void defaultRelationsAreTypedUnavailableAndDoNotChangeExistingConstruction() throws Exception {
        final ModelTextures textures = emptyTextures();

        final TextureRelationsSnapshot snapshot = textures.relations();
        assertEquals(TextureRelationsSnapshot.Availability.UNAVAILABLE, snapshot.availability());
        assertFalse(snapshot.isAvailable());
        assertEquals(0, snapshot.generation());
        assertEquals(0, snapshot.revision());
        assertTrue(snapshot.rawImages().isEmpty());
        assertTrue(snapshot.modelImages().isEmpty());
        assertTrue(snapshot.groups().isEmpty());
        assertTrue(snapshot.artMeshInputs().isEmpty());
        assertArrayEquals(
            new String[] {"5.3.02"},
            ModelTextures.class.getMethod("relations").getAnnotation(CubismEditor.class).value()
        );
    }

    @Test
    void relationValuesAreImmutableAndRetainManyToManyCurrentAndUnknownStates() {
        final RawImageId rawId = new RawImageId("raw-a");
        final ModelImageId modelImageId = new ModelImageId("model-a");
        final TextureAtlasId atlasId = new TextureAtlasId("atlas-a");
        final ArtMeshId firstMesh = new ArtMeshId("mesh-a");
        final ArtMeshId secondMesh = new ArtMeshId("mesh-b");
        final RawLayerId firstLayer = new RawLayerId("layer-a");
        final RawLayerId secondLayer = new RawLayerId("layer-b");

        final RawTexture rawTexture = rawTexture(rawId, "Raw A", 2048, 1024);
        final ModelImageEntry modelImage = modelImage(modelImageId, "Model A", 1024, 512);
        final RawLayerBinding firstBinding = new RawLayerBinding(
            rawId,
            firstLayer,
            0,
            RawLayerBinding.DetailAvailability.AVAILABLE,
            RawLayerBinding.DetailAvailability.UNKNOWN
        );
        final RawLayerBinding secondBinding = new RawLayerBinding(
            rawId,
            secondLayer,
            1,
            RawLayerBinding.DetailAvailability.UNAVAILABLE,
            RawLayerBinding.DetailAvailability.AVAILABLE
        );
        final ModelImageRelation modelRelation = new ModelImageRelation(
            modelImageId,
            modelImage,
            List.of(rawId),
            Optional.of(rawId),
            Map.of(rawId, List.of(firstBinding, secondBinding)),
            List.of(firstMesh, secondMesh)
        );
        final RawImageDetails rawDetails = new RawImageDetails(
            rawTexture,
            RawImageDetails.SourceKind.PSD,
            List.of(new RawLayerDetails(
                new RawLayerId("layer-group"),
                rawId,
                RawLayerDetails.EntryKind.GROUP,
                "Group",
                Optional.empty(),
                List.of(
                    new RawLayerDetails(
                        firstLayer,
                        rawId,
                        RawLayerDetails.EntryKind.PIXEL,
                        "Layer A",
                        Optional.empty(),
                        List.of()
                    ),
                    new RawLayerDetails(
                        secondLayer,
                        rawId,
                        RawLayerDetails.EntryKind.PIXEL,
                        "Layer B",
                        Optional.empty(),
                        List.of()
                    )
                )
            )),
            false,
            Optional.of("imported"),
            Optional.empty(),
            Optional.empty()
        );
        final ModelImageGroupRelation groupRelation = new ModelImageGroupRelation(
            modelGroup("Textures", modelImage),
            List.of(modelImageId),
            List.of(rawId),
            Optional.empty()
        );
        final ArtMeshTextureInputs meshInputs = new ArtMeshTextureInputs(
            firstMesh,
            List.of(
                TextureInputBinding.atlas(atlasId),
                TextureInputBinding.modelImage(modelImageId),
                TextureInputBinding.unknown(),
                TextureInputBinding.modelImage(
                    new ModelImageId("missing"),
                    TextureInputBinding.ResolutionState.UNAVAILABLE
                )
            ),
            OptionalInt.of(1)
        );
        final ArtMeshTextureInputs unboundMesh = new ArtMeshTextureInputs(
            secondMesh,
            List.of(),
            OptionalInt.empty()
        );
        final TextureRelationsSnapshot snapshot = new TextureRelationsSnapshot(
            TextureRelationsSnapshot.Availability.AVAILABLE,
            "session-a",
            7,
            1,
            List.of(rawDetails),
            List.of(modelRelation),
            List.of(groupRelation),
            List.of(meshInputs, unboundMesh)
        );

        assertTrue(snapshot.isAvailable());
        assertEquals(Optional.of(rawId), snapshot.modelImage(modelImageId).orElseThrow().currentRawImageId());
        assertEquals(List.of(firstMesh, secondMesh), snapshot.modelImage(modelImageId).orElseThrow().usingArtMeshIds());
        assertEquals(List.of(firstBinding, secondBinding), snapshot.modelImage(modelImageId)
            .orElseThrow().inputsByRawImage().get(rawId));
        assertEquals(TextureInputBinding.Kind.ATLAS, meshInputs.inputs().get(0).kind());
        assertEquals(TextureInputBinding.Kind.UNKNOWN, meshInputs.inputs().get(2).kind());
        assertEquals(TextureInputBinding.ResolutionState.UNAVAILABLE, meshInputs.inputs().get(3).resolutionState());
        assertTrue(unboundMesh.inputs().isEmpty());
        assertTrue(unboundMesh.currentInputIndex().isEmpty());
        assertEquals(Optional.empty(), groupRelation.projectTreeVisible());
        assertFalse(rawId.equals(firstLayer));

        assertThrows(UnsupportedOperationException.class, () -> snapshot.rawImages().add(rawDetails));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.modelImages().get(0).usingArtMeshIds().add(firstMesh));
        assertThrows(
            UnsupportedOperationException.class,
            () -> snapshot.modelImages().get(0).inputsByRawImage().put(rawId, List.of())
        );
        assertThrows(
            UnsupportedOperationException.class,
            () -> snapshot.modelImages().get(0).inputsByRawImage().get(rawId).add(firstBinding)
        );
        assertThrows(UnsupportedOperationException.class, () -> snapshot.groups().add(groupRelation));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.artMeshInputs().get(0).inputs().clear());
        assertThrows(UnsupportedOperationException.class, () -> rawDetails.layers().clear());
        assertThrows(UnsupportedOperationException.class, () -> rawDetails.layers().get(0).children().clear());
    }

    private static ModelTextures emptyTextures() {
        return new ModelTextures() {
            @Override public List<RawTexture> rawImages() { return List.of(); }
            @Override public List<ModelImageGroup> modelImageGroups() { return List.of(); }
            @Override public List<AtlasTexture> textureAtlases() { return List.of(); }
            @Override public void addModelImageGroup(final String name) { }
            @Override public void removeModelImage(final ModelImageId id) { }
            @Override public TextureAtlasId addTextureAtlas(
                final String name,
                final int widthPixels,
                final int heightPixels
            ) {
                return new TextureAtlasId("atlas");
            }
            @Override public void removeTextureAtlas(final TextureAtlasId id) { }
            @Override public void removeRawImage(final RawImageId id) { }
        };
    }

    private static RawTexture rawTexture(
        final RawImageId id,
        final String name,
        final int width,
        final int height
    ) {
        return new RawTexture() {
            @Override public RawImageId id() { return id; }
            @Override public String name() { return name; }
            @Override public int width() { return width; }
            @Override public int height() { return height; }
        };
    }

    private static ModelImageEntry modelImage(
        final ModelImageId id,
        final String name,
        final int width,
        final int height
    ) {
        return new ModelImageEntry() {
            @Override public ModelImageId id() { return id; }
            @Override public String name() { return name; }
            @Override public int width() { return width; }
            @Override public int height() { return height; }
        };
    }

    private static ModelImageGroup modelGroup(final String name, final ModelImageEntry image) {
        return new ModelImageGroup() {
            @Override public String groupName() { return name; }
            @Override public String memo() { return "memo"; }
            @Override public List<ModelImageEntry> modelImages() { return List.of(image); }
        };
    }
}
