package dev.turboism.adapter.cubism.editor;

import dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringTransactionCoordinator;
import dev.turboism.core.runtime.psd.PsdExportHost;
import dev.turboism.core.runtime.psd.PsdReplaceHost;
import dev.turboism.core.runtime.psd.PsdSessionBoundHost;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorTextureSelectorContract;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ModelImageId;
import dev.turboism.sdk.cubism.id.RawImageId;
import dev.turboism.sdk.cubism.id.TextureAtlasId;
import dev.turboism.sdk.cubism.model.ArtMeshTextureInput;
import dev.turboism.sdk.cubism.model.AtlasPageAppendPlan;
import dev.turboism.sdk.cubism.model.AtlasTexture;
import dev.turboism.sdk.cubism.model.AuthorizedImageContent;
import dev.turboism.sdk.cubism.model.ModelImageBinding;
import dev.turboism.sdk.cubism.model.ModelImageGroup;
import dev.turboism.sdk.cubism.model.ModelTextures;
import dev.turboism.sdk.cubism.model.RawTexture;
import dev.turboism.sdk.cubism.model.TextureRelationsSnapshot;
import dev.turboism.sdk.cubism.model.TextureSourceQuery;
import dev.turboism.sdk.cubism.model.TextureSourcesSnapshot;
import dev.turboism.sdk.cubism.psd.PsdEditFile;
import dev.turboism.sdk.cubism.psd.PsdExportResult;
import dev.turboism.sdk.cubism.psd.PsdFileRevision;
import dev.turboism.sdk.cubism.psd.PsdReplaceResult;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Exact, generation-bound Editor projection of the model texture library.
 *
 * <p>Reads enumerate {@code CTextureManager} state (raw layered images, model
 * image groups, texture atlases). Writes are Editor-authoring operations routed
 * through {@code TextureManagerHandler} undo factories inside the standard Undo
 * envelope: {@code beginEdit} → {@code GroupUndo.addEdit} → side effects →
 * {@code endEdit} with rollback through the native Undo path on failure.</p>
 */
final class EditorTextureAccess {

    static final Set<String> APPEND_ATLAS_REQUIRED_ALIASES =
            EditorTextureAtlasAppendAccess.APPEND_ATLAS_REQUIRED_ALIASES;

    private final VerifiedMemberResolver resolver;
    private final EditorParameterCombinedAccess.ModelGuard modelGuard;
    private final LongSupplier generationSupplier;
    private final EditorTextureRelationsAccess relationAccess;
    private final EditorTexturePsdCoordinator psdCoordinator;
    private final EditorTextureAtlasAppendAccess atlasAppendAccess;
    private final EditorTextureRegistrationBindingAccess registrationBindingAccess;
    private final EditorTextureLibraryAccess libraryAccess;

    EditorTextureAccess(
            final VerifiedMemberResolver resolver, final EditorParameterCombinedAccess.ModelGuard modelGuard) {
        this(resolver, modelGuard, () -> 0L, null);
    }

    EditorTextureAccess(
            final VerifiedMemberResolver resolver,
            final EditorParameterCombinedAccess.ModelGuard modelGuard,
            final LongSupplier generationSupplier) {
        this(resolver, modelGuard, generationSupplier, null);
    }

    EditorTextureAccess(
            final VerifiedMemberResolver resolver,
            final EditorParameterCombinedAccess.ModelGuard modelGuard,
            final EditorAuthoringTransactionCoordinator authoringCoordinator) {
        this(resolver, modelGuard, () -> 0L, authoringCoordinator);
    }

    EditorTextureAccess(
            final VerifiedMemberResolver resolver,
            final EditorParameterCombinedAccess.ModelGuard modelGuard,
            final EditorAuthoringTransactionCoordinator authoringCoordinator,
            final Supplier<EditorAuthoringTransactionCoordinator.Binding> bindingSupplier) {
        this(resolver, modelGuard, () -> 0L, authoringCoordinator, bindingSupplier);
    }

    EditorTextureAccess(
            final VerifiedMemberResolver resolver,
            final EditorParameterCombinedAccess.ModelGuard modelGuard,
            final LongSupplier generationSupplier,
            final EditorAuthoringTransactionCoordinator authoringCoordinator) {
        this(resolver, modelGuard, generationSupplier, authoringCoordinator, null);
    }

    EditorTextureAccess(
            final VerifiedMemberResolver resolver,
            final EditorParameterCombinedAccess.ModelGuard modelGuard,
            final LongSupplier generationSupplier,
            final EditorAuthoringTransactionCoordinator authoringCoordinator,
            final Supplier<EditorAuthoringTransactionCoordinator.Binding> bindingSupplier) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.modelGuard = Objects.requireNonNull(modelGuard, "modelGuard");
        this.generationSupplier = Objects.requireNonNull(generationSupplier, "generationSupplier");
        this.relationAccess = new EditorTextureRelationsAccess(resolver, modelGuard, this.generationSupplier);
        this.psdCoordinator =
                new EditorTexturePsdCoordinator(resolver, modelGuard, this.generationSupplier, this.relationAccess);
        this.registrationBindingAccess =
                new EditorTextureRegistrationBindingAccess(resolver, modelGuard, authoringCoordinator, bindingSupplier);
        this.atlasAppendAccess = new EditorTextureAtlasAppendAccess(
                resolver, modelGuard, authoringCoordinator, this.registrationBindingAccess::observeTextureInput);
        this.libraryAccess = new EditorTextureLibraryAccess(resolver, modelGuard, authoringCoordinator);
    }

    ModelTextures textures(final String identity, final Object source, final Object model) {
        requireReadAuthorization();
        modelGuard.requireCurrent(identity, model);
        return new EditorTextures(identity, source, model);
    }

    private boolean readAuthorized() {
        return resolver.authorizesFeature(
                EditorTextureSelectorContract.ADAPTER_SLICE_ID,
                EditorTextureSelectorContract.READ_CAPABILITY_ID,
                EditorTextureSelectorContract.READ_REQUIRED_ALIASES);
    }

    private void requireReadAuthorization() {
        if (!readAuthorized()) {
            throw new UnsupportedOperationException(
                    "Texture-library reading is unavailable without an admitted texture contract.");
        }
    }

    boolean supportsAppendTextureAtlasPages(final String identity, final Object model) {
        return atlasAppendAccess.supportsAppendTextureAtlasPages(identity, model);
    }

    Map<String, Integer> decodeOracleSnapshot() {
        return registrationBindingAccess.decodeOracleSnapshot();
    }

    static Optional<RawImageId> observedRawImage(
            final TextureRelationsSnapshot after,
            final List<ModelImageId> affected,
            final RawImageId expectedIncoming) {
        return EditorTexturePsdCoordinator.observedRawImage(after, affected, expectedIncoming);
    }

    static PsdReplaceHost.Failure replacementObservationFailure(
            final TextureRelationsSnapshot before,
            final TextureRelationsSnapshot after,
            final RawImageId target,
            final RawImageId incoming) {
        return EditorTexturePsdCoordinator.replacementObservationFailure(before, after, target, incoming);
    }

    private final class EditorTextures implements ModelTextures, PsdExportHost, PsdSessionBoundHost, PsdReplaceHost {
        private final String identity;
        private final Object source;
        private final Object model;

        private EditorTextures(final String identity, final Object source, final Object model) {
            this.identity = identity;
            this.source = source;
            this.model = model;
        }

        @Override
        public List<RawTexture> rawImages() {
            return libraryAccess.rawImages(identity, source, model);
        }

        @Override
        public List<ModelImageGroup> modelImageGroups() {
            return libraryAccess.modelImageGroups(identity, source, model);
        }

        @Override
        public List<AtlasTexture> textureAtlases() {
            return libraryAccess.textureAtlases(identity, source, model);
        }

        @Override
        public TextureRelationsSnapshot relations() {
            return relationAccess.relations(identity, source, model);
        }

        @Override
        public TextureSourcesSnapshot sources(final TextureSourceQuery query) {
            return relationAccess.sources(identity, source, model, query);
        }

        @Override
        public Observation exportPsdTo(final RawImageId sourceId, final Path destination, final Runnable admission) {
            return psdCoordinator.exportPsdTo(identity, source, model, sourceId, destination, admission);
        }

        @Override
        public String sessionIdentity() {
            modelGuard.requireCurrent(identity, model);
            return identity;
        }

        @Override
        public long generation() {
            modelGuard.requireCurrent(identity, model);
            return generationSupplier.getAsLong();
        }

        @Override
        public Replacement replaceWithStagedPsd(
                final RawImageId target, final Path stage, final String sourceFileName, final Runnable admission) {
            return psdCoordinator.replaceWithStagedPsd(
                    identity, source, model, target, stage, sourceFileName, admission);
        }

        @Override
        public CompletionStage<PsdExportResult> exportRawImagePsd(final RawImageId source) {
            Objects.requireNonNull(source, "source");
            modelGuard.requireCurrent(identity, model);
            return ModelTextures.super.exportRawImagePsd(source);
        }

        @Override
        public CompletionStage<PsdReplaceResult> replaceRawImagePsd(
                final RawImageId target, final PsdEditFile file, final PsdFileRevision revision) {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(revision, "revision");
            modelGuard.requireCurrent(identity, model);
            return ModelTextures.super.replaceRawImagePsd(target, file, revision);
        }

        @Override
        public void addModelImageGroup(final String name) {
            libraryAccess.addModelImageGroup(identity, source, model, name);
        }

        @Override
        public void removeModelImage(final ModelImageId id) {
            libraryAccess.removeModelImage(identity, source, model, id);
        }

        @Override
        public TextureAtlasId addTextureAtlas(final String name, final int widthPixels, final int heightPixels) {
            return libraryAccess.addTextureAtlas(identity, source, model, name, widthPixels, heightPixels);
        }

        @Override
        public void removeTextureAtlas(final TextureAtlasId id) {
            libraryAccess.removeTextureAtlas(identity, source, model, id);
        }

        @Override
        public boolean supportsAppendTextureAtlasPages() {
            return atlasAppendAccess.supportsAppendTextureAtlasPages(identity, model);
        }

        @Override
        public List<TextureAtlasId> appendTextureAtlasPages(final AtlasPageAppendPlan plan) {
            return atlasAppendAccess.appendTextureAtlasPages(identity, source, model, plan);
        }

        @Override
        public void removeRawImage(final RawImageId id) {
            libraryAccess.removeRawImage(identity, source, model, id);
        }

        @Override
        public ModelImageBinding registerImageAndBind(final AuthorizedImageContent image, final ArtMeshId target) {
            return registrationBindingAccess.registerImageAndBind(identity, source, model, image, target);
        }

        @Override
        public Optional<ArtMeshTextureInput> textureInput(final ArtMeshId target) {
            return registrationBindingAccess.textureInput(identity, source, model, target);
        }

        @Override
        public ModelImageBinding bindModelImage(final ModelImageId image, final ArtMeshId target) {
            return registrationBindingAccess.bindModelImage(identity, source, model, image, target);
        }
    }
}
