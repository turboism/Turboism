package dev.turboism.adapter.cubism;

import dev.turboism.permissions.CubismPermissionGate;
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
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.permission.PluginPermission;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.Registration;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PsdModelTexturesForwardingTest {

    private static final Clock FIXED_CLOCK =
        Clock.fixed(Instant.parse("2026-07-07T00:00:00Z"), ZoneOffset.UTC);
    private static final RawImageId SOURCE = new RawImageId("raw-source");
    private static final RawImageId TARGET = new RawImageId("raw-target");
    private static final PsdEditFile FILE = forbiddenFile();
    private static final PsdFileRevision REVISION = new PsdFileRevision() { };

    @Test
    void permissionCheckedWrapperForwardsTheSamePsdValues() {
        final RecordingTextures delegate = new RecordingTextures();
        final ModelTextures textures = facade(
            delegate,
            Set.of(
                PermissionIds.TURBOISM_CUBISM_MODEL_READ,
                PermissionIds.TURBOISM_CUBISM_MODEL_WRITE,
                PermissionIds.TURBOISM_FILE_READ,
                PermissionIds.TURBOISM_FILE_WRITE
            )
        ).model().active().textures();

        final var export = textures.exportRawImagePsd(SOURCE);
        final var replace = textures.replaceRawImagePsd(TARGET, FILE, REVISION);

        assertSame(delegate.exportStage, export);
        assertSame(delegate.replaceStage, replace);
        assertSame(SOURCE, delegate.exportSource);
        assertSame(TARGET, delegate.replaceTarget);
        assertSame(FILE, delegate.replaceFile);
        assertSame(REVISION, delegate.replaceRevision);
        assertEquals(1, delegate.exportCalls);
        assertEquals(1, delegate.replaceCalls);
    }

    @Test
    void exportRequiresModelReadAndFileWriteWithoutInvokingTheDelegateWhenDenied() {
        final RecordingTextures delegate = new RecordingTextures();
        final ModelTextures textures = facade(
            delegate,
            Set.of(PermissionIds.TURBOISM_CUBISM_MODEL_READ)
        ).model().active().textures();

        assertThrows(
            CubismPermissionException.class,
            () -> textures.exportRawImagePsd(SOURCE)
        );
        assertEquals(0, delegate.exportCalls);
    }

    @Test
    void replaceRequiresModelWriteAndBothFilePermissionsWithoutInvokingTheDelegateWhenDenied() {
        final RecordingTextures missingModelWrite = new RecordingTextures();
        final ModelTextures modelWriteDenied = facade(
            missingModelWrite,
            Set.of(
                PermissionIds.TURBOISM_CUBISM_MODEL_READ,
                PermissionIds.TURBOISM_FILE_READ,
                PermissionIds.TURBOISM_FILE_WRITE
            )
        ).model().active().textures();
        assertThrows(
            CubismPermissionException.class,
            () -> modelWriteDenied.replaceRawImagePsd(TARGET, FILE, REVISION)
        );
        assertEquals(0, missingModelWrite.replaceCalls);

        final RecordingTextures missingFileRead = new RecordingTextures();
        final ModelTextures fileReadDenied = facade(
            missingFileRead,
            Set.of(
                PermissionIds.TURBOISM_CUBISM_MODEL_READ,
                PermissionIds.TURBOISM_CUBISM_MODEL_WRITE,
                PermissionIds.TURBOISM_FILE_WRITE
            )
        ).model().active().textures();
        assertThrows(
            CubismPermissionException.class,
            () -> fileReadDenied.replaceRawImagePsd(TARGET, FILE, REVISION)
        );
        assertEquals(0, missingFileRead.replaceCalls);

        final RecordingTextures missingFileWrite = new RecordingTextures();
        final ModelTextures fileWriteDenied = facade(
            missingFileWrite,
            Set.of(
                PermissionIds.TURBOISM_CUBISM_MODEL_READ,
                PermissionIds.TURBOISM_CUBISM_MODEL_WRITE,
                PermissionIds.TURBOISM_FILE_READ
            )
        ).model().active().textures();
        assertThrows(
            CubismPermissionException.class,
            () -> fileWriteDenied.replaceRawImagePsd(TARGET, FILE, REVISION)
        );
        assertEquals(0, missingFileWrite.replaceCalls);
    }

    private static CubismFacadeImpl facade(
        final RecordingTextures textures,
        final Set<String> permissions
    ) {
        final CubismModel model = new CubismModel() {
            @Override public ModelId id() { return new ModelId("model-a"); }
            @Override public ModelTextures textures() { return textures; }
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
        return new CubismFacadeImpl(
            new HostSnapshotSource() {
                @Override public Optional<HostProject> activeProject() { return Optional.empty(); }
                @Override public Optional<HostDocument> activeDocument() { return Optional.empty(); }
                @Override public Optional<HostModel> activeModel() { return Optional.empty(); }
                @Override public HostSelection selection() {
                    return new HostSelection(List.of(), Optional.empty(), Optional.empty(), Optional.empty());
                }
                @Override public boolean isHostPresent() { return false; }
                @Override public long invalidationToken() { return 0L; }
            },
            new CubismPermissionGate(
                "plugin.demo",
                permissions.stream().map(PsdModelTexturesForwardingTest::permission).toList(),
                ignored -> { },
                FIXED_CLOCK
            ),
            (CubismModelAccess) () -> model
        );
    }

    private static PluginPermission permission(final String id) {
        return new PluginPermission() {
            @Override public String id() { return id; }
            @Override public String scope() { return "test"; }
            @Override public String reason() { return "PSD forwarding test"; }
        };
    }

    private static PsdEditFile forbiddenFile() {
        return new PsdEditFile() {
            @Override
            public CompletionStage<PsdFileOperationResult> openInDefaultApplication() {
                throw new AssertionError("forwarding must not invoke the file handle");
            }

            @Override
            public Registration observeSaves(final Consumer<PsdFileRevision> listener) {
                throw new AssertionError("forwarding must not invoke the file handle");
            }

            @Override
            public CompletionStage<PsdFileOperationResult> stop() {
                throw new AssertionError("forwarding must not invoke the file handle");
            }
        };
    }

    private static final class RecordingTextures implements ModelTextures {
        private RawImageId exportSource;
        private RawImageId replaceTarget;
        private PsdEditFile replaceFile;
        private PsdFileRevision replaceRevision;
        private int exportCalls;
        private int replaceCalls;
        private CompletionStage<PsdExportResult> exportStage;
        private CompletionStage<PsdReplaceResult> replaceStage;

        @Override public List<RawTexture> rawImages() { return List.of(); }
        @Override public List<ModelImageGroup> modelImageGroups() { return List.of(); }
        @Override public List<AtlasTexture> textureAtlases() { return List.of(); }

        @Override
        public CompletionStage<PsdExportResult> exportRawImagePsd(final RawImageId source) {
            exportSource = source;
            exportCalls++;
            exportStage = CompletableFuture.completedFuture(
                new PsdExportResult(
                    PsdExportResult.Status.UNAVAILABLE,
                    "synthetic forwarding result",
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
            replaceCalls++;
            replaceStage = CompletableFuture.completedFuture(
                new PsdReplaceResult(
                    PsdReplaceResult.Status.UNAVAILABLE,
                    "synthetic forwarding result",
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
