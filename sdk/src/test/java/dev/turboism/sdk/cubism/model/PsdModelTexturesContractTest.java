package dev.turboism.sdk.cubism.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.cubism.id.RawImageId;
import dev.turboism.sdk.cubism.psd.PsdEditFile;
import dev.turboism.sdk.cubism.psd.PsdExportResult;
import dev.turboism.sdk.cubism.psd.PsdFileOperationResult;
import dev.turboism.sdk.cubism.psd.PsdFileRevision;
import dev.turboism.sdk.cubism.psd.PsdReplaceResult;
import dev.turboism.sdk.plugin.Registration;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class PsdModelTexturesContractTest {

    @Test
    void defaultPsdOperationsAreCompletedTypedUnavailableAndDoNotTouchOpaqueInputs() {
        final RawImageId source = new RawImageId("raw-source");
        final RawImageId target = new RawImageId("raw-target");
        final PsdEditFile file = forbiddenFile();
        final PsdFileRevision revision = new PsdFileRevision() {};
        final ModelTextures textures = emptyTextures();

        final var exportStage = textures.exportRawImagePsd(source);
        final PsdExportResult exported = exportStage.toCompletableFuture().join();
        assertTrue(exportStage.toCompletableFuture().isDone());
        assertEquals(PsdExportResult.Status.UNAVAILABLE, exported.status());
        assertEquals(source, exported.source());
        assertTrue(exported.file().isEmpty());
        assertTrue(exported.initialRevision().isEmpty());

        final var replaceStage = textures.replaceRawImagePsd(target, file, revision);
        final PsdReplaceResult replaced = replaceStage.toCompletableFuture().join();
        assertTrue(replaceStage.toCompletableFuture().isDone());
        assertEquals(PsdReplaceResult.Status.UNAVAILABLE, replaced.status());
        assertEquals(target, replaced.before());
        assertTrue(replaced.after().isEmpty());
        assertTrue(replaced.consumedRevision().isEmpty());
        assertTrue(replaced.relations().isEmpty());
    }

    @Test
    void nullPsdArgumentsAreRejectedBeforeReturningAStage() {
        final ModelTextures textures = emptyTextures();
        final PsdEditFile file = forbiddenFile();
        final PsdFileRevision revision = new PsdFileRevision() {};

        assertThrows(NullPointerException.class, () -> textures.exportRawImagePsd(null));
        assertThrows(NullPointerException.class, () -> textures.replaceRawImagePsd(null, file, revision));
        assertThrows(
                NullPointerException.class,
                () -> textures.replaceRawImagePsd(new RawImageId("raw-target"), null, revision));
        assertThrows(
                NullPointerException.class,
                () -> textures.replaceRawImagePsd(new RawImageId("raw-target"), file, null));
    }

    @Test
    void psdEntryPointsAreOnlyDeclaredForCubism5302() throws Exception {
        final var export = ModelTextures.class.getMethod("exportRawImagePsd", RawImageId.class);
        final var replace = ModelTextures.class.getMethod(
                "replaceRawImagePsd", RawImageId.class, PsdEditFile.class, PsdFileRevision.class);

        assertEquals(CompletionStage.class, export.getReturnType());
        assertEquals(CompletionStage.class, replace.getReturnType());
        assertArrayEquals(
                new String[] {"5.3.02"},
                export.getAnnotation(CubismEditor.class).value());
        assertArrayEquals(
                new String[] {"5.3.02"},
                replace.getAnnotation(CubismEditor.class).value());
    }

    private static PsdEditFile forbiddenFile() {
        return new PsdEditFile() {
            @Override
            public CompletionStage<PsdFileOperationResult> openInDefaultApplication() {
                throw new AssertionError("default entry point must not invoke the file handle");
            }

            @Override
            public Registration observeSaves(final Consumer<PsdFileRevision> listener) {
                throw new AssertionError("default entry point must not invoke the file handle");
            }

            @Override
            public CompletionStage<PsdFileOperationResult> stop() {
                throw new AssertionError("default entry point must not invoke the file handle");
            }
        };
    }

    private static ModelTextures emptyTextures() {
        return new ModelTextures() {
            @Override
            public List<RawTexture> rawImages() {
                return List.of();
            }

            @Override
            public List<ModelImageGroup> modelImageGroups() {
                return List.of();
            }

            @Override
            public List<AtlasTexture> textureAtlases() {
                return List.of();
            }

            @Override
            public void addModelImageGroup(final String name) {}

            @Override
            public void removeModelImage(final dev.turboism.sdk.cubism.id.ModelImageId id) {}

            @Override
            public dev.turboism.sdk.cubism.id.TextureAtlasId addTextureAtlas(
                    final String name, final int widthPixels, final int heightPixels) {
                return new dev.turboism.sdk.cubism.id.TextureAtlasId("atlas");
            }

            @Override
            public void removeTextureAtlas(final dev.turboism.sdk.cubism.id.TextureAtlasId id) {}

            @Override
            public void removeRawImage(final RawImageId id) {}
        };
    }
}
