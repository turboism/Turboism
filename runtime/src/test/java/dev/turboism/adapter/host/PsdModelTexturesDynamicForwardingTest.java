package dev.turboism.adapter.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.turboism.core.runtime.psd.PsdExportHost;
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
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class PsdModelTexturesDynamicForwardingTest {

    private static final RawImageId SOURCE = new RawImageId("raw-source");
    private static final RawImageId TARGET = new RawImageId("raw-target");
    private static final PsdEditFile FILE = forbiddenFile();
    private static final PsdFileRevision REVISION = new PsdFileRevision() {};

    @Test
    void sessionWrapperForwardsSameValuesAndRejectsTheSessionAfterDeactivation() {
        final RecordingTextures delegateTextures = new RecordingTextures();
        final CubismModel delegateModel = new CubismModel() {
            @Override
            public ModelId id() {
                return new ModelId("model-a");
            }

            @Override
            public ModelTextures textures() {
                return delegateTextures;
            }

            @Override
            public dev.turboism.sdk.cubism.model.Parameters parameters() {
                throw new UnsupportedOperationException();
            }

            @Override
            public dev.turboism.sdk.cubism.model.Parts parts() {
                throw new UnsupportedOperationException();
            }

            @Override
            public dev.turboism.sdk.cubism.model.Drawables drawables() {
                throw new UnsupportedOperationException();
            }

            @Override
            public dev.turboism.sdk.cubism.model.Deformers deformers() {
                throw new UnsupportedOperationException();
            }

            @Override
            public dev.turboism.sdk.cubism.model.Glues glues() {
                throw new UnsupportedOperationException();
            }

            @Override
            public void update() {}
        };
        final DynamicCubismModelAccess access = new DynamicCubismModelAccess();
        access.connect(() -> delegateModel);

        final ModelTextures sessionTextures = access.active().textures();
        final var query = new dev.turboism.sdk.cubism.model.TextureSourceQuery(java.util.Set.of(), java.util.Set.of());
        assertSame(delegateTextures.sourceSnapshot, sessionTextures.sources(query));
        assertSame(query, delegateTextures.sourceQuery);
        final var export = sessionTextures.exportRawImagePsd(SOURCE);
        final var replace = sessionTextures.replaceRawImagePsd(TARGET, FILE, REVISION);

        assertSame(delegateTextures.exportStage, export);
        assertSame(delegateTextures.replaceStage, replace);
        assertSame(SOURCE, delegateTextures.exportSource);
        assertSame(TARGET, delegateTextures.replaceTarget);
        assertSame(FILE, delegateTextures.replaceFile);
        assertSame(REVISION, delegateTextures.replaceRevision);
        assertEquals(
                PsdExportResult.Status.UNAVAILABLE,
                export.toCompletableFuture().join().status());
        assertEquals(
                PsdReplaceResult.Status.UNAVAILABLE,
                replace.toCompletableFuture().join().status());
        final int[] admissions = {0};
        final Path destination = Path.of("synthetic.psd");
        final PsdExportHost port = (PsdExportHost) sessionTextures;
        assertEquals(
                "UNAVAILABLE",
                port.exportPsdTo(SOURCE, destination, () -> admissions[0]++).nativeStatus());
        assertSame(destination, delegateTextures.destination);
        assertEquals(1, admissions[0]);

        access.deactivate();
        assertThrows(IllegalStateException.class, () -> sessionTextures.sources(query));
        assertThrows(IllegalStateException.class, delegateTextures.admission::run);
        assertThrows(IllegalStateException.class, () -> port.exportPsdTo(SOURCE, destination, () -> {}));
        assertEquals(1, admissions[0]);
        assertThrows(IllegalStateException.class, () -> sessionTextures.exportRawImagePsd(SOURCE));
        assertThrows(IllegalStateException.class, () -> sessionTextures.replaceRawImagePsd(TARGET, FILE, REVISION));
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

    private static final class RecordingTextures implements ModelTextures, PsdExportHost {
        private RawImageId exportSource;
        private RawImageId replaceTarget;
        private PsdEditFile replaceFile;
        private PsdFileRevision replaceRevision;
        private CompletionStage<PsdExportResult> exportStage;
        private CompletionStage<PsdReplaceResult> replaceStage;
        private Runnable admission;
        private Path destination;
        private dev.turboism.sdk.cubism.model.TextureSourceQuery sourceQuery;
        private final dev.turboism.sdk.cubism.model.TextureSourcesSnapshot sourceSnapshot =
                dev.turboism.sdk.cubism.model.TextureSourcesSnapshot.unavailable();

        @Override
        public dev.turboism.sdk.cubism.model.TextureSourcesSnapshot sources(
                final dev.turboism.sdk.cubism.model.TextureSourceQuery query) {
            sourceQuery = query;
            return sourceSnapshot;
        }

        @Override
        public Observation exportPsdTo(final RawImageId source, final Path destination, final Runnable admission) {
            this.destination = destination;
            this.admission = admission;
            admission.run();
            return Observation.unavailable();
        }

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
        public CompletionStage<PsdExportResult> exportRawImagePsd(final RawImageId source) {
            exportSource = source;
            exportStage = CompletableFuture.completedFuture(new PsdExportResult(
                    PsdExportResult.Status.UNAVAILABLE,
                    "synthetic dynamic forwarding result",
                    source,
                    Optional.empty(),
                    Optional.empty()));
            return exportStage;
        }

        @Override
        public CompletionStage<PsdReplaceResult> replaceRawImagePsd(
                final RawImageId target, final PsdEditFile file, final PsdFileRevision revision) {
            replaceTarget = target;
            replaceFile = file;
            replaceRevision = revision;
            replaceStage = CompletableFuture.completedFuture(new PsdReplaceResult(
                    PsdReplaceResult.Status.UNAVAILABLE,
                    "synthetic dynamic forwarding result",
                    target,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty()));
            return replaceStage;
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
    }
}
