package dev.turboism.adapter.host;

import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.RawImageId;
import dev.turboism.sdk.cubism.model.AtlasTexture;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.ModelImageGroup;
import dev.turboism.sdk.cubism.model.ModelTextures;
import dev.turboism.sdk.cubism.model.RawTexture;
import dev.turboism.sdk.cubism.psd.PsdEditFile;
import dev.turboism.sdk.cubism.psd.PsdExportResult;
import dev.turboism.sdk.cubism.psd.PsdFileOperationResult;
import dev.turboism.sdk.cubism.psd.PsdFileRevision;
import dev.turboism.sdk.cubism.psd.PsdReplaceResult;
import dev.turboism.sdk.plugin.Registration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PsdModelTexturesDynamicForwardingTest {

    private static final RawImageId SOURCE = new RawImageId("raw-source");
    private static final RawImageId TARGET = new RawImageId("raw-target");
    private static final PsdEditFile FILE = forbiddenFile();
    private static final PsdFileRevision REVISION = new PsdFileRevision() { };

    @Test
    void sessionWrapperForwardsSameValuesAndRejectsTheSessionAfterDeactivation() {
        final RecordingTextures delegateTextures = new RecordingTextures();
        final CubismModel delegateModel = new CubismModel() {
            @Override public ModelId id() { return new ModelId("model-a"); }
            @Override public ModelTextures textures() { return delegateTextures; }
            @Override public dev.turboism.sdk.cubism.model.Parameters parameters() {
                throw new UnsupportedOperationException();
            }
            @Override public dev.turboism.sdk.cubism.model.Parts parts() {
                throw new UnsupportedOperationException();
            }
            @Override public dev.turboism.sdk.cubism.model.Drawables drawables() {
                throw new UnsupportedOperationException();
            }
            @Override public dev.turboism.sdk.cubism.model.Deformers deformers() {
                throw new UnsupportedOperationException();
            }
            @Override public dev.turboism.sdk.cubism.model.Glues glues() {
                throw new UnsupportedOperationException();
            }
            @Override public void update() { }
        };
        final DynamicCubismModelAccess access = new DynamicCubismModelAccess();
        access.connect(() -> delegateModel);

        final ModelTextures sessionTextures = access.active().textures();
        final var export = sessionTextures.exportRawImagePsd(SOURCE);
        final var replace = sessionTextures.replaceRawImagePsd(TARGET, FILE, REVISION);

        assertSame(delegateTextures.exportStage, export);
        assertSame(delegateTextures.replaceStage, replace);
        assertSame(SOURCE, delegateTextures.exportSource);
        assertSame(TARGET, delegateTextures.replaceTarget);
        assertSame(FILE, delegateTextures.replaceFile);
        assertSame(REVISION, delegateTextures.replaceRevision);
        assertEquals(PsdExportResult.Status.UNAVAILABLE, export.toCompletableFuture().join().status());
        assertEquals(PsdReplaceResult.Status.UNAVAILABLE, replace.toCompletableFuture().join().status());

        access.deactivate();
        assertThrows(
            IllegalStateException.class,
            () -> sessionTextures.exportRawImagePsd(SOURCE)
        );
        assertThrows(
            IllegalStateException.class,
            () -> sessionTextures.replaceRawImagePsd(TARGET, FILE, REVISION)
        );
    }

    private static PsdEditFile forbiddenFile() {
        return new PsdEditFile() {
            @Override
            public CompletionStage<PsdFileOperationResult> openInDefaultApplication() {
                throw new AssertionError("dynamic forwarding must not invoke the file handle");
            }

            @Override
            public Registration observeSaves(final Consumer<PsdFileRevision> listener) {
                throw new AssertionError("dynamic forwarding must not invoke the file handle");
            }

            @Override
            public CompletionStage<PsdFileOperationResult> stop() {
                throw new AssertionError("dynamic forwarding must not invoke the file handle");
            }
        };
    }

    private static final class RecordingTextures implements ModelTextures {
        private RawImageId exportSource;
        private RawImageId replaceTarget;
        private PsdEditFile replaceFile;
        private PsdFileRevision replaceRevision;
        private CompletionStage<PsdExportResult> exportStage;
        private CompletionStage<PsdReplaceResult> replaceStage;

        @Override public List<RawTexture> rawImages() { return List.of(); }
        @Override public List<ModelImageGroup> modelImageGroups() { return List.of(); }
        @Override public List<AtlasTexture> textureAtlases() { return List.of(); }

        @Override
        public CompletionStage<PsdExportResult> exportRawImagePsd(final RawImageId source) {
            exportSource = source;
            exportStage = CompletableFuture.completedFuture(
                new PsdExportResult(
                    PsdExportResult.Status.UNAVAILABLE,
                    "synthetic dynamic forwarding result",
                    source,
                    Optional.empty(),
                    Optional.empty()
                )
            );
            return exportStage;
        }

        @Override
        public CompletionStage<PsdReplaceResult> replaceRawImagePsd(
            final RawImageId target,
            final PsdEditFile file,
            final PsdFileRevision revision
        ) {
            replaceTarget = target;
            replaceFile = file;
            replaceRevision = revision;
            replaceStage = CompletableFuture.completedFuture(
                new PsdReplaceResult(
                    PsdReplaceResult.Status.UNAVAILABLE,
                    "synthetic dynamic forwarding result",
                    target,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty()
                )
            );
            return replaceStage;
        }

        @Override public void addModelImageGroup(final String name) { }
        @Override public void removeModelImage(final dev.turboism.sdk.cubism.id.ModelImageId id) { }
        @Override public dev.turboism.sdk.cubism.id.TextureAtlasId addTextureAtlas(
            final String name,
            final int widthPixels,
            final int heightPixels
        ) {
            return new dev.turboism.sdk.cubism.id.TextureAtlasId("atlas");
        }
        @Override public void removeTextureAtlas(
            final dev.turboism.sdk.cubism.id.TextureAtlasId id
        ) { }
        @Override public void removeRawImage(final RawImageId id) { }
    }
}
