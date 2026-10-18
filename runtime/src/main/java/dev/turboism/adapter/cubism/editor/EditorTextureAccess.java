package dev.turboism.adapter.cubism.editor;

import dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringTransactionCoordinator;
import dev.turboism.adapter.cubism.editor.transaction.EditorRefreshRequirement;
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
import dev.turboism.sdk.cubism.model.ModelImageEntry;
import dev.turboism.sdk.cubism.model.ModelImageGroup;
import dev.turboism.sdk.cubism.model.ModelTextures;
import dev.turboism.sdk.cubism.model.RawTexture;
import dev.turboism.sdk.cubism.model.TextureRelationsSnapshot;
import dev.turboism.sdk.cubism.psd.PsdEditFile;
import dev.turboism.sdk.cubism.psd.PsdExportResult;
import dev.turboism.sdk.cubism.psd.PsdFileRevision;
import dev.turboism.sdk.cubism.psd.PsdReplaceResult;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
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

    private static final String TEXTURE_MANAGER = "cubism.editor-model.model-source.texture-manager";
    private static final String RAW_IMAGES = "cubism.editor-model.texture-manager.raw-images";
    private static final String MODEL_IMAGE_GROUPS = "cubism.editor-model.texture-manager.model-image-groups";
    private static final String ALL_MODEL_IMAGES = "cubism.editor-model.texture-manager.all-model-images";
    private static final String TEXTURE_ATLASES = "cubism.editor-model.texture-manager.texture-atlases";
    private static final String HANDLER = "cubism.editor-model.texture-manager.handler";
    private static final String WRAPPER_IMAGE = "cubism.editor-model.layered-image-wrapper.image";
    private static final String LAYERED_IMAGE_GUID = "cubism.editor-model.layered-image.guid";
    private static final String GUID_VALUE = "cubism.editor-model.guid.value";

    private static final String APP_INSTANCE = "cubism.editor-model.app-controller.instance";
    private static final String CURRENT_DOCUMENT = "cubism.editor-model.app-controller.current-document";
    private static final String EDIT_MODE = "cubism.editor-model.modeling-document.edit-mode";
    private static final String MARK_DIRTY = "cubism.editor-model.modeling-document.mark-dirty";
    private static final String BEGIN_EDIT = "cubism.editor-model.edit-mode.begin";
    private static final String END_EDIT = "cubism.editor-model.edit-mode.end";
    private static final String UNDO_ADD = "cubism.editor-model.undo.add";
    private static final String UNDO_ADD_LISTENER = "cubism.editor-model.undo.add-listener";
    private static final String UNDO_REVERT = "cubism.editor-model.texture-undo.undo";
    private static final String UNDO_FORCE_REDO = "cubism.editor-model.texture-undo.force-redo";
    private static final String UNDO_LISTENER_CLASS = "cubism.editor-model.undo-listener.class";
    private static final String UPDATE_INSTANCES = "cubism.editor-model.model-source.update-instances";
    private static final String COMPLETE_PACK = "cubism.editor-model.app-controller.complete-pack";
    private static final String UPDATE_PART_PALETTE = "cubism.editor-model.complete-pack.update-part-palette";
    private static final String REPAINT_CANVAS = "cubism.editor-model.complete-pack.repaint-canvas";

    private static final String GROUP_CREATE = "cubism.editor-model.model-image-group.create";
    private static final String ATLAS_CREATE = "cubism.editor-model.texture-atlas.create";
    private static final String ADD_MODEL_IMAGE_GROUP = "cubism.editor-model.texture-handler.add-model-image-group";
    private static final String REMOVE_MODEL_IMAGE = "cubism.editor-model.texture-handler.remove-model-image";
    private static final String ADD_TEXTURE_ATLAS = "cubism.editor-model.texture-handler.add-texture-atlas";
    private static final String REMOVE_TEXTURE_ATLAS = "cubism.editor-model.texture-handler.remove-texture-atlas";
    private static final String REMOVE_RAW_IMAGE = "cubism.editor-model.texture-handler.remove-raw-image";

    static final Set<String> APPEND_ATLAS_REQUIRED_ALIASES =
            EditorTextureAtlasAppendAccess.APPEND_ATLAS_REQUIRED_ALIASES;

    private final VerifiedMemberResolver resolver;
    private final EditorParameterCombinedAccess.ModelGuard modelGuard;
    private final EditorTextureRelationsAccess relationAccess;
    private final LongSupplier generationSupplier;
    private final EditorTexturePsdCoordinator psdCoordinator;
    private final EditorTextureAtlasAppendAccess atlasAppendAccess;
    private final EditorTextureRegistrationBindingAccess registrationBindingAccess;
    private final EditorAuthoringTransactionCoordinator authoringCoordinator;
    private final Supplier<EditorAuthoringTransactionCoordinator.Binding> bindingSupplier;

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
        this.authoringCoordinator = authoringCoordinator;
        this.bindingSupplier = bindingSupplier;
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

    private boolean writeAuthorized() {
        return resolver.authorizesFeature(
                EditorTextureSelectorContract.ADAPTER_SLICE_ID,
                EditorTextureSelectorContract.WRITE_CAPABILITY_ID,
                EditorTextureSelectorContract.WRITE_REQUIRED_ALIASES);
    }

    private void requireReadAuthorization() {
        if (!readAuthorized()) {
            throw new UnsupportedOperationException(
                    "Texture-library reading is unavailable without an admitted texture contract.");
        }
    }

    private void requireWriteAuthorization() {
        if (!writeAuthorized()) {
            throw new UnsupportedOperationException(
                    "Texture-library writing is unavailable without its verified transaction contract.");
        }
    }

    private boolean legacyRawImageRemoval() {
        return !resolver.authorizesFeature(
                EditorTextureSelectorContract.ADAPTER_SLICE_ID,
                EditorTextureSelectorContract.WRITE_CAPABILITY_ID,
                Set.of(REMOVE_RAW_IMAGE));
    }

    private void requireRawImageRemoval() {
        if (!resolver.authorizesFeature(
                EditorTextureSelectorContract.ADAPTER_SLICE_ID,
                EditorTextureSelectorContract.WRITE_CAPABILITY_ID,
                legacyRawImageRemoval()
                        ? EditorTextureSelectorContract.REMOVE_RAW_IMAGE_5203_ALIASES
                        : EditorTextureSelectorContract.REMOVE_RAW_IMAGE_ALIASES)) {
            throw new UnsupportedOperationException(
                    "Raw image removal is unavailable without its verified non-dialog native Undo route.");
        }
    }

    Map<String, Integer> decodeOracleSnapshot() {
        return registrationBindingAccess.decodeOracleSnapshot();
    }

    private Object textureManager(final Object source) {
        final Object manager = resolver.invoke(TEXTURE_MANAGER, source);
        if (manager == null) {
            throw new IllegalStateException("Editor texture manager is unavailable.");
        }
        return manager;
    }

    private Object handler(final Object source) {
        final Object handler = resolver.invoke(HANDLER, textureManager(source));
        if (handler == null) {
            throw new IllegalStateException("Editor texture handler is unavailable.");
        }
        return handler;
    }

    private List<?> list(final String alias, final Object target, final String label) {
        final Object raw = resolver.invoke(alias, target);
        if (!(raw instanceof List<?> values)) {
            throw new IllegalStateException("Editor " + label + " collection is unavailable.");
        }
        return values;
    }

    private String guidValue(final Object guid, final String label) {
        final Object raw = resolver.invoke(GUID_VALUE, guid);
        if (!(raw instanceof String value) || value.isBlank()) {
            throw new IllegalStateException("Editor " + label + " GUID is invalid.");
        }
        return value;
    }

    private String name(final Object image, final String alias, final String label) {
        final Object raw = resolver.invoke(alias, image);
        if (!(raw instanceof String value) || value.isBlank()) {
            throw new IllegalStateException("Editor " + label + " name is invalid.");
        }
        return value;
    }

    private int dimension(final Object image, final String alias, final String label) {
        final Object raw = resolver.invoke(alias, image);
        if (!(raw instanceof Integer value) || value < 0) {
            throw new IllegalStateException("Editor " + label + " dimension is invalid.");
        }
        return value;
    }

    private void refresh(final Object app) {
        final Object completePack = resolver.invoke(COMPLETE_PACK, app);
        resolver.invoke(UPDATE_PART_PALETTE, completePack, Boolean.TRUE);
        resolver.invoke(REPAINT_CANVAS, completePack, Boolean.TRUE);
    }

    /**
     * Standard Undo envelope: begin → register the construct-and-redo undoable →
     * update instances/repaint → mark dirty → end. On any failure the partial
     * group is closed through the native Undo path so no partial state survives.
     */
    private void envelope(final String label, final Object source, final Operation operation) {
        EditorHostThread.requireHostThread("Cubism texture write");
        final Object app = resolver.invokeStatic(APP_INSTANCE);
        final Object document = resolver.invoke(CURRENT_DOCUMENT, app);
        final var ambientJoin = HostUndoMutationScope.ambient(authoringCoordinator, resolver);
        if (ambientJoin.isPresent()) {
            ambientJoin
                    .orElseThrow()
                    .admit(
                            "cubism.texture.write",
                            "texture:" + label,
                            label,
                            (edit, transactionLabel) -> {
                                // Construct-and-redo: the operation applies against the supplied edit and
                                // returns the undoable; register it on the ambient root exactly as the
                                // standalone envelope does.
                                final Object undoable = operation.apply(edit);
                                if (undoable != edit) registerAppliedUndo(edit, undoable);
                                final Object listener = resolver.createFunctionalProxy(UNDO_LISTENER_CLASS, ignored -> {
                                    resolver.invoke(UPDATE_INSTANCES, source);
                                    refresh(app);
                                    return null;
                                });
                                resolver.invoke(UNDO_ADD_LISTENER, undoable, listener);
                            },
                            () -> {},
                            () -> true,
                            EnumSet.of(
                                    EditorRefreshRequirement.MODEL_INSTANCES,
                                    EditorRefreshRequirement.PART_PALETTE,
                                    EditorRefreshRequirement.CANVAS,
                                    EditorRefreshRequirement.MARK_DIRTY));
            return;
        }
        EditorAmbientTransactionGuard.requireNoAmbientTransaction(authoringCoordinator, label);
        final Object editMode = resolver.invoke(EDIT_MODE, document);
        final Object edit = resolver.invoke(BEGIN_EDIT, editMode, label);
        boolean completed = false;
        try {
            final Object undoable = operation.apply(edit);
            // Prepared 5.2 edits already belong to the root. Other native factories forceRedo
            // before returning; compensate them if the root refuses registration.
            if (undoable != edit) registerAppliedUndo(edit, undoable);
            final Object listener = resolver.createFunctionalProxy(UNDO_LISTENER_CLASS, ignored -> {
                resolver.invoke(UPDATE_INSTANCES, source);
                refresh(app);
                return null;
            });
            resolver.invoke(UNDO_ADD_LISTENER, undoable, listener);
            resolver.invoke(UPDATE_INSTANCES, source);
            refresh(app);
            resolver.invoke(MARK_DIRTY, document);
            completed = true;
        } finally {
            resolver.invoke(END_EDIT, editMode, Boolean.valueOf(!completed), null);
        }
        if (!completed) {
            throw new IllegalStateException("Cubism texture edit was rolled back: " + label);
        }
    }

    private void registerAppliedUndo(final Object edit, final Object undoable) {
        try {
            final Object accepted = resolver.invoke(UNDO_ADD, edit, undoable, Boolean.TRUE);
            if (!Boolean.TRUE.equals(accepted)) {
                throw new IllegalStateException("Cubism rejected the texture Undo entry.");
            }
        } catch (RuntimeException | Error failure) {
            try {
                resolver.invoke(UNDO_REVERT, undoable);
            } catch (RuntimeException | Error compensation) {
                if (compensation != failure) failure.addSuppressed(compensation);
            }
            throw failure;
        }
    }

    private Object findModelImageGuid(final Object source, final ModelImageId id) {
        for (Object image : list(ALL_MODEL_IMAGES, textureManager(source), "model image")) {
            if (!resolver.isInstance("cubism.editor-model.model-image.class", image)) {
                throw new IllegalStateException("Editor model image collection contains an invalid value.");
            }
            final Object guid = resolver.invoke("cubism.editor-model.model-image.guid", image);
            if (guidValue(guid, "model image").equals(id.value())) {
                return guid;
            }
        }
        throw new NoSuchElementException("Cubism model image is absent: " + id.value());
    }

    private Object findTextureAtlas(final Object source, final TextureAtlasId id) {
        for (Object atlas : list(TEXTURE_ATLASES, textureManager(source), "texture atlas")) {
            if (!resolver.isInstance("cubism.editor-model.texture-atlas.class", atlas)) {
                throw new IllegalStateException("Editor texture atlas collection contains an invalid value.");
            }
            final Object guid = resolver.invoke("cubism.editor-model.texture-atlas.guid", atlas);
            if (guidValue(guid, "texture atlas").equals(id.value())) {
                return atlas;
            }
        }
        throw new NoSuchElementException("Cubism texture atlas is absent: " + id.value());
    }

    private Object findRawImageGuid(final Object source, final RawImageId id) {
        for (Object wrapper : list(RAW_IMAGES, textureManager(source), "raw image")) {
            final Object image = resolver.invoke(WRAPPER_IMAGE, wrapper);
            if (!resolver.isInstance("cubism.editor-model.layered-image.class", image)) {
                throw new IllegalStateException("Editor raw image collection contains an invalid value.");
            }
            final Object guid = resolver.invoke("cubism.editor-model.layered-image.guid", image);
            if (guidValue(guid, "raw image").equals(id.value())) {
                return guid;
            }
        }
        throw new NoSuchElementException("Cubism raw image is absent: " + id.value());
    }

    /**
     * Proves an incoming binding on a previously targeted model image. Native structural matching
     * may retain or remove unmatched images; agreement of every old image is not its contract.
     */
    static Optional<RawImageId> observedRawImage(
            final TextureRelationsSnapshot after,
            final List<ModelImageId> affected,
            final RawImageId expectedIncoming) {
        return EditorTexturePsdCoordinator.observedRawImage(after, affected, expectedIncoming);
    }

    /** Explains a failed observation using existing snapshots only; never changes admission or matching. */
    static PsdReplaceHost.Failure replacementObservationFailure(
            final TextureRelationsSnapshot before,
            final TextureRelationsSnapshot after,
            final RawImageId target,
            final RawImageId incoming) {
        return EditorTexturePsdCoordinator.replacementObservationFailure(before, after, target, incoming);
    }

    private List<Object> prepareRawImageRemoval5203(final Object source, final RawImageId id) {
        final Object guid = findRawImageGuid(source, id);
        Object rawImage = null;
        for (Object wrapper : list(RAW_IMAGES, textureManager(source), "raw image")) {
            final Object image = resolver.invoke(WRAPPER_IMAGE, wrapper);
            if (guidValue(resolver.invoke("cubism.editor-model.layered-image.guid", image), "raw image")
                    .equals(id.value())) {
                rawImage = image;
                break;
            }
        }
        if (rawImage == null) throw new NoSuchElementException("Cubism raw image is absent: " + id.value());
        final List<Object> undoables = new ArrayList<>();
        final java.util.Set<Object> observed = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Object image : list(ALL_MODEL_IMAGES, textureManager(source), "model image")) {
            final Object environment = resolver.invoke("cubism.editor-model.model-image.input-filter-env", image);
            final Object inputs =
                    resolver.invoke("cubism.editor-model.model-image-filter-env.layer-input-data", environment);
            if (inputs == null) throw new IllegalStateException("Model image layer-input map is unavailable.");
            if (!observed.add(inputs)) continue;
            final Object layers = resolver.invoke("cubism.editor-model.layer-selector-map.get", inputs, guid);
            if (layers == null) continue;
            if (!(layers instanceof List<?>)) throw new IllegalStateException("Invalid raw-image layer inputs.");
            undoables.add(resolver.construct(
                    "cubism.editor-model.texture-undo.layer-input.create", inputs, guid, null, Boolean.FALSE));
        }
        undoables.add(resolver.construct(
                "cubism.editor-model.texture-undo.raw-image.create", source, rawImage, -1, Boolean.FALSE));
        return List.copyOf(undoables);
    }

    @FunctionalInterface
    private interface Operation {
        Object apply(Object edit);
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
            modelGuard.requireCurrent(identity, model);
            final List<RawTexture> values = new ArrayList<>();
            for (Object wrapper : list(RAW_IMAGES, textureManager(source), "raw image")) {
                final Object image = resolver.invoke(WRAPPER_IMAGE, wrapper);
                if (!resolver.isInstance("cubism.editor-model.layered-image.class", image)) {
                    throw new IllegalStateException("Editor raw image collection contains an invalid value.");
                }
                final String id =
                        guidValue(resolver.invoke("cubism.editor-model.layered-image.guid", image), "raw image");
                values.add(new SimpleRawTexture(
                        new RawImageId(id),
                        name(image, "cubism.editor-model.layered-image.name", "raw image"),
                        dimension(image, "cubism.editor-model.layered-image.width", "raw image"),
                        dimension(image, "cubism.editor-model.layered-image.height", "raw image")));
            }
            return List.copyOf(values);
        }

        @Override
        public List<ModelImageGroup> modelImageGroups() {
            modelGuard.requireCurrent(identity, model);
            final List<ModelImageGroup> values = new ArrayList<>();
            for (Object group : list(MODEL_IMAGE_GROUPS, textureManager(source), "model image group")) {
                if (!resolver.isInstance("cubism.editor-model.model-image-group.class", group)) {
                    throw new IllegalStateException("Editor model image group collection contains an invalid value.");
                }
                final String groupName =
                        name(group, "cubism.editor-model.model-image-group.group-name", "model image group");
                final Object rawMemo = resolver.invoke("cubism.editor-model.model-image-group.memo", group);
                final String memo = rawMemo instanceof String value ? value : "";
                final List<ModelImageEntry> images = new ArrayList<>();
                for (Object image :
                        list("cubism.editor-model.model-image-group.model-images", group, "model image group images")) {
                    if (!resolver.isInstance("cubism.editor-model.model-image.class", image)) {
                        throw new IllegalStateException("Editor model image collection contains an invalid value.");
                    }
                    final String id =
                            guidValue(resolver.invoke("cubism.editor-model.model-image.guid", image), "model image");
                    images.add(new SimpleModelImageEntry(
                            new ModelImageId(id),
                            name(image, "cubism.editor-model.model-image.name", "model image"),
                            dimension(image, "cubism.editor-model.model-image.width", "model image"),
                            dimension(image, "cubism.editor-model.model-image.height", "model image")));
                }
                values.add(new SimpleModelImageGroup(groupName, memo, List.copyOf(images)));
            }
            return List.copyOf(values);
        }

        @Override
        public List<AtlasTexture> textureAtlases() {
            modelGuard.requireCurrent(identity, model);
            final List<AtlasTexture> values = new ArrayList<>();
            for (Object atlas : list(TEXTURE_ATLASES, textureManager(source), "texture atlas")) {
                if (!resolver.isInstance("cubism.editor-model.texture-atlas.class", atlas)) {
                    throw new IllegalStateException("Editor texture atlas collection contains an invalid value.");
                }
                final String id =
                        guidValue(resolver.invoke("cubism.editor-model.texture-atlas.guid", atlas), "texture atlas");
                final String atlasName = name(atlas, "cubism.editor-model.texture-atlas.name", "texture atlas");
                final int width = dimension(atlas, "cubism.editor-model.texture-atlas.width", "texture atlas");
                final int height = dimension(atlas, "cubism.editor-model.texture-atlas.height", "texture atlas");
                final Object rawVersion = resolver.invoke("cubism.editor-model.texture-atlas.atlas-version", atlas);
                final int atlasVersion = rawVersion instanceof Integer value ? value : 0;
                final int modelImageCount = list(
                                "cubism.editor-model.texture-atlas.model-images", atlas, "texture atlas images")
                        .size();
                values.add(new SimpleAtlasTexture(
                        new TextureAtlasId(id), atlasName, width, height, atlasVersion, modelImageCount));
            }
            return List.copyOf(values);
        }

        @Override
        public TextureRelationsSnapshot relations() {
            return relationAccess.relations(identity, source, model);
        }

        @Override
        public dev.turboism.sdk.cubism.model.TextureSourcesSnapshot sources(
                final dev.turboism.sdk.cubism.model.TextureSourceQuery query) {
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
            EditorHostThread.dispatch("Cubism texture write", () -> {
                addModelImageGroupOnEdt(name);
                return null;
            });
        }

        private void addModelImageGroupOnEdt(final String name) {
            requireWriteAuthorization();
            final String value = Objects.requireNonNull(name, "name");
            if (value.strip().isEmpty()) throw new IllegalArgumentException("name must not be blank");
            modelGuard.requireCurrent(identity, model);
            final Object group = resolver.construct(GROUP_CREATE, value);
            final int index = list(MODEL_IMAGE_GROUPS, textureManager(source), "model image group")
                    .size();
            envelope(
                    "Turboism: Add Model Image Group",
                    source,
                    edit -> resolver.invoke(ADD_MODEL_IMAGE_GROUP, handler(source), group, index));
        }

        @Override
        public void removeModelImage(final ModelImageId id) {
            EditorHostThread.dispatch("Cubism texture write", () -> {
                removeModelImageOnEdt(id);
                return null;
            });
        }

        private void removeModelImageOnEdt(final ModelImageId id) {
            requireWriteAuthorization();
            Objects.requireNonNull(id, "id");
            modelGuard.requireCurrent(identity, model);
            final Object imageGuid = findModelImageGuid(source, id);
            envelope(
                    "Turboism: Remove Model Image",
                    source,
                    edit -> resolver.invoke(REMOVE_MODEL_IMAGE, handler(source), imageGuid));
        }

        @Override
        public TextureAtlasId addTextureAtlas(final String name, final int widthPixels, final int heightPixels) {
            return EditorHostThread.dispatch(
                    "Cubism texture write", () -> addTextureAtlasOnEdt(name, widthPixels, heightPixels));
        }

        private TextureAtlasId addTextureAtlasOnEdt(final String name, final int widthPixels, final int heightPixels) {
            requireWriteAuthorization();
            final String value = Objects.requireNonNull(name, "name");
            if (value.strip().isEmpty()) throw new IllegalArgumentException("name must not be blank");
            if (widthPixels <= 0 || heightPixels <= 0) {
                throw new IllegalArgumentException("atlas dimensions must be positive");
            }
            modelGuard.requireCurrent(identity, model);
            final Object atlas = resolver.construct(ATLAS_CREATE, source, value, widthPixels, heightPixels);
            final int index = list(TEXTURE_ATLASES, textureManager(source), "texture atlas")
                    .size();
            envelope(
                    "Turboism: Add Texture Atlas",
                    source,
                    edit -> resolver.invoke(ADD_TEXTURE_ATLAS, handler(source), atlas, index));
            final Object guid = resolver.invoke("cubism.editor-model.texture-atlas.guid", atlas);
            return new TextureAtlasId(guidValue(guid, "texture atlas"));
        }

        @Override
        public void removeTextureAtlas(final TextureAtlasId id) {
            EditorHostThread.dispatch("Cubism texture write", () -> {
                removeTextureAtlasOnEdt(id);
                return null;
            });
        }

        private void removeTextureAtlasOnEdt(final TextureAtlasId id) {
            requireWriteAuthorization();
            Objects.requireNonNull(id, "id");
            modelGuard.requireCurrent(identity, model);
            final Object atlas = findTextureAtlas(source, id);
            envelope(
                    "Turboism: Remove Texture Atlas",
                    source,
                    edit -> resolver.invoke(REMOVE_TEXTURE_ATLAS, handler(source), atlas));
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
            EditorHostThread.dispatch("Cubism texture write", () -> {
                removeRawImageOnEdt(id);
                return null;
            });
        }

        private void removeRawImageOnEdt(final RawImageId id) {
            requireWriteAuthorization();
            requireRawImageRemoval();
            Objects.requireNonNull(id, "id");
            modelGuard.requireCurrent(identity, model);
            if (legacyRawImageRemoval()) {
                // Match the 5.3 handler's false branch: clear this raw image's layer inputs,
                // then remove only its raw-image entry. Never invoke the 5.2 dialog or layer purge.
                final List<Object> prepared = prepareRawImageRemoval5203(source, id);
                envelope("Turboism: Remove Raw Image", source, edit -> {
                    for (Object undoable : prepared) {
                        if (!Boolean.TRUE.equals(resolver.invoke(UNDO_ADD, edit, undoable, Boolean.TRUE))) {
                            throw new IllegalStateException("Cubism rejected the prepared texture Undo entry.");
                        }
                        resolver.invoke(UNDO_FORCE_REDO, undoable);
                    }
                    return edit;
                });
            } else {
                final Object guid = findRawImageGuid(source, id);
                envelope(
                        "Turboism: Remove Raw Image",
                        source,
                        edit -> resolver.invoke(REMOVE_RAW_IMAGE, handler(source), guid, Boolean.FALSE));
            }
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

    private record SimpleRawTexture(RawImageId id, String name, int width, int height) implements RawTexture {}

    private record SimpleModelImageEntry(ModelImageId id, String name, int width, int height)
            implements ModelImageEntry {}

    private record SimpleModelImageGroup(String groupName, String memo, List<ModelImageEntry> modelImages)
            implements ModelImageGroup {}

    private record SimpleAtlasTexture(
            TextureAtlasId id, String name, int width, int height, int atlasVersion, int modelImageCount)
            implements AtlasTexture {}
}
