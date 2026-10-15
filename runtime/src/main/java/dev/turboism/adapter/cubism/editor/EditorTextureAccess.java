package dev.turboism.adapter.cubism.editor;

import dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringTransactionCoordinator;
import dev.turboism.adapter.cubism.editor.transaction.EditorRefreshRequirement;
import dev.turboism.adapter.cubism.editor.transaction.EditorUndoContribution;
import dev.turboism.adapter.cubism.editor.transaction.VerifiedEditorAuthoringTransactionHost;
import dev.turboism.adapter.cubism.textureatlas.VerifiedTextureAtlasSelectorContract;
import dev.turboism.core.runtime.psd.PsdExportHost;
import dev.turboism.core.runtime.psd.PsdReplaceHost;
import dev.turboism.core.runtime.psd.PsdSessionBoundHost;
import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorEditSessionSelectorContract;
import dev.turboism.mapping.verification.selector.EditorTextureSelectorContract;
import dev.turboism.sdk.cubism.event.CubismOperation;
import dev.turboism.sdk.cubism.history.HistoryAction;
import dev.turboism.sdk.cubism.history.HistoryChange;
import dev.turboism.sdk.cubism.history.HistoryEditContext;
import dev.turboism.sdk.cubism.history.HistoryEntryDetail;
import dev.turboism.sdk.cubism.history.HistoryOrigin;
import dev.turboism.sdk.cubism.history.HistoryTarget;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ModelId;
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
import dev.turboism.sdk.cubism.model.ModelImagePlacement;
import dev.turboism.sdk.cubism.model.ModelImageRelation;
import dev.turboism.sdk.cubism.model.ModelTextures;
import dev.turboism.sdk.cubism.model.RawTexture;
import dev.turboism.sdk.cubism.model.TextureRelationsSnapshot;
import dev.turboism.sdk.cubism.psd.PsdEditFile;
import dev.turboism.sdk.cubism.psd.PsdExportResult;
import dev.turboism.sdk.cubism.psd.PsdFileRevision;
import dev.turboism.sdk.cubism.psd.PsdReplaceResult;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOptions;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionResult;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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

    private static final String ATLAS_ENTRIES = "cubism.texture-atlas.atlas.entries";
    private static final String ENTRY_CLASS = "cubism.texture-atlas.entry.class";
    private static final String ENTRY_CREATE = "cubism.texture-atlas.entry.create";
    private static final String ENTRY_IMAGE = "cubism.texture-atlas.entry.image";
    private static final String IMAGE_GUID = "cubism.texture-atlas.image.guid";
    private static final String AFFINE_CREATE = "cubism.texture-atlas.affine.create";
    private static final String AFFINE_TRANSLATE = "cubism.texture-atlas.affine.translate";
    private static final String ATLAS_UNDO_CREATE = "cubism.texture-atlas.undo.create";
    private static final String ATLAS_UNDO_FORCE_REDO = "cubism.texture-atlas.undo.force-redo";
    private static final String RELINK_HELPER = "cubism.texture-atlas.texture-input-relink.helper-instance";
    private static final String RELINK_REBUILD = "cubism.texture-atlas.texture-input-relink.rebuild";
    private static final String MODEL_SOURCE_VERIFY = "cubism.editor-model.model-source.verify";
    private static final String CHANGE_INPUT_TO_ATLAS = "cubism.texture-atlas.texture-manager.change-input-to-atlas";
    private static final String REINIT_INPUT = "cubism.texture-atlas.texture-manager.reinit-input";

    static final Set<String> APPEND_ATLAS_REQUIRED_ALIASES = Set.of(
            ATLAS_CREATE,
            ATLAS_ENTRIES,
            ENTRY_CLASS,
            ENTRY_CREATE,
            ENTRY_IMAGE,
            IMAGE_GUID,
            AFFINE_CREATE,
            AFFINE_TRANSLATE,
            ATLAS_UNDO_CREATE,
            ATLAS_UNDO_FORCE_REDO,
            RELINK_HELPER,
            RELINK_REBUILD,
            MODEL_SOURCE_VERIFY,
            CHANGE_INPUT_TO_ATLAS,
            REINIT_INPUT);

    private static final String MODELING_DOCUMENT_CLASS = "cubism.editor-model.modeling-document.class";
    private static final String DOCUMENT_MODEL_SOURCE = "cubism.editor-model.modeling-document.model-source";
    private static final String MODEL_SOURCE_INSTANCE = "cubism.editor-model.model-source.current-instance";
    private static final String ALL_ART_MESHES = "cubism.editor-model.model-source.all-art-meshes";
    private static final String ART_MESH_SOURCE_CLASS = "cubism.editor-model.art-mesh-source.class";
    private static final String SOURCE_ID = "cubism.editor-model.parameter-controllable-source.id";
    private static final String ID_VALUE = "cubism.editor-model.id.value";
    private static final String LOCKED_IN_HIERARCHY =
            "cubism.editor-model.parameter-controllable-source.locked-in-hierarchy";
    private static final String EXTENSIONS = "cubism.editor-model.parameter-controllable-source.extensions";
    private static final String ALL_PARENT_DEFORMERS =
            "cubism.editor-model.parameter-controllable-source.all-parent-deformers";
    private static final String MESH_TEXTURE_INPUT = "cubism.editor-model.art-mesh-source.texture-input-extension";
    private static final String MESH_TEXTURE = "cubism.editor-model.art-mesh-source.texture";
    private static final String MESH_SET_TEXTURE = "cubism.editor-model.art-mesh-source.set-texture";
    private static final String MESH_TEXTURE_STATE = "cubism.editor-model.art-mesh-source.texture-state";
    private static final String MESH_SET_TEXTURE_STATE = "cubism.editor-model.art-mesh-source.set-texture-state";
    private static final String TEXTURE_STATE_MODEL_IMAGE = "cubism.editor-model.texture-state.model-image";

    private static final String IMAGE_RESOURCE_CREATE = "cubism.editor-model.image-resource.create";
    private static final String IMAGE_COLOR_TYPE = "cubism.editor-model.image-color-type.int-argb";
    private static final String IMAGE_RESOURCE_IMAGE = "cubism.editor-model.image-resource.image";
    private static final String IMAGE_RESOURCE_WIDTH = "cubism.editor-model.image-resource.width";
    private static final String IMAGE_RESOURCE_HEIGHT = "cubism.editor-model.image-resource.height";
    private static final String LAYERED_IMAGE_CREATE = "cubism.editor-model.layered-image.create";
    private static final String WRAPPER_CREATE = "cubism.editor-model.layered-image-wrapper.create";
    private static final String MODEL_IMAGE_GUID_CREATE = "cubism.editor-model.model-image-guid.create";
    private static final String MODEL_IMAGE_CREATE = "cubism.editor-model.model-image.create";
    private static final String MODEL_IMAGE_GUID = "cubism.editor-model.model-image.guid";
    private static final String MODEL_IMAGE_FILTER = "cubism.editor-model.model-image.input-filter";
    private static final String MODEL_IMAGE_FILTER_ENV = "cubism.editor-model.model-image.input-filter-env";
    private static final String MODEL_IMAGE_LINKED_GUIDS = "cubism.editor-model.model-image.linked-raw-image-guids";
    private static final String GROUP_LINK = "cubism.editor-model.model-image-group.add-linked-layered-image";
    private static final String GROUP_IMAGES = "cubism.editor-model.model-image-group.model-images";
    private static final String ENV_COMPANION = "cubism.editor-model.model-image-filter-env.companion";
    private static final String ENV_OUTPUT_IMAGE = "cubism.editor-model.model-image-filter-env.output-image";
    private static final String ENV_OUTPUT_TRANSFORM = "cubism.editor-model.model-image-filter-env.output-transform";
    private static final String ENV_HAS_CURRENT_IMAGE_GUID =
            "cubism.editor-model.model-image-filter-env.has-current-image-guid";
    private static final String ENV_CURRENT_IMAGE_GUID =
            "cubism.editor-model.model-image-filter-env.current-image-guid";
    private static final String ENV_SET_CURRENT_IMAGE_GUID =
            "cubism.editor-model.model-image-filter-env.set-current-image-guid";
    private static final String FILTER_VALUE_CREATE = "cubism.editor-model.filter-value.create";
    private static final String FILTER_VALUE_ID = "cubism.editor-model.filter-value.id";
    private static final String EMPTY_FILTER_CREATE = "cubism.editor-model.empty-filter.create";
    private static final String FILTER_SET_ADD = "cubism.editor-model.filter-set.add-filter";
    private static final String FILTER_INSTANCE_SET_INPUT = "cubism.editor-model.filter-instance.set-input";
    private static final String CONST_CONNECTOR_CREATE = "cubism.editor-model.const-value-connector.create";
    private static final String CAFFINE_CREATE = "cubism.editor-model.caffine.create";
    private static final String FILTER_SET_CONNECT_OUTPUT = "cubism.editor-model.filter-set.connect-env-output";
    private static final String FILTER_SET_VERIFY = "cubism.editor-model.filter-set.verify";
    private static final String VERIFY_COLLECTOR_CREATE = "cubism.editor-model.verify-collector.create";

    private static final String UNDO_RAW_CREATE = "cubism.editor-model.undo-add-remove-raw-image-wrapper.create";
    private static final String UNDO_GROUP_CREATE = "cubism.editor-model.undo-add-remove-model-image-group.create";
    private static final String UNDO_IMAGE_CREATE = "cubism.editor-model.undo-add-remove-model-image.create";
    private static final String UNDO_EXTENSION_CREATE = "cubism.editor-model.undo-add-remove-extension.create";
    private static final String UNDO_EXECUTE_UNDO = "cubism.editor-model.undo.execute-undo";
    private static final String UU_UNDO_INSTANCE = "cubism.editor-model.uu-undo.instance";
    private static final String UU_UNDO_CREATE = "cubism.editor-model.uu-undo.local-simple-create";

    private static final String INPUT_CLASS = "cubism.editor-model.texture-input-model-image.class";
    private static final String ATLAS_REGION_INPUT_CLASS =
            "cubism.editor-model.texture-input-texture-atlas-region.class";
    private static final String ATLAS_REGION_GUID = "cubism.editor-model.texture-atlas-guid";
    private static final String EXTENSION_CREATE = "cubism.editor-model.texture-input-extension.create";
    private static final String INPUT_CREATE = "cubism.editor-model.texture-input-model-image.create";
    private static final String EXTENSION_ADD_INPUT = "cubism.editor-model.texture-input-extension.add-input";
    private static final String EXTENSION_SET_CURRENT = "cubism.editor-model.texture-input-extension.set-current-input";
    private static final String EXTENSION_CURRENT_INPUT = "cubism.editor-model.texture-input-extension.current-input";
    private static final String EXTENSION_INPUTS = "cubism.editor-model.texture-input-extension.inputs";
    private static final String INPUT_MODEL_IMAGE = "cubism.editor-model.texture-input-model-image.model-image";
    private static final String INPUT_CACHED_IMAGE = "cubism.editor-model.texture-input-model-image.cached-image";
    private static final String CACHED_PARAM_COMPANION = "cubism.editor-model.cached-image-param.companion";
    private static final String CACHED_PARAM_DEFAULT = "cubism.editor-model.cached-image-param.default";
    private static final String CACHED_IMAGE_TEXTURE = "cubism.editor-model.cached-image.texture";

    private static final String IMAGE_LOADED_COUNT = "cubism.editor-model.image-resource.loaded-count";
    private static final String IMAGE_LOADED_BYTES = "cubism.editor-model.image-resource.loaded-bytes";
    private static final String IMAGE_BYTE_DATA_BYTES = "cubism.editor-model.image-resource.byte-data-bytes";

    private static final String REGISTER_PLUGIN_ID = "runtime.editor-model";

    private final VerifiedMemberResolver resolver;
    private final EditorParameterCombinedAccess.ModelGuard modelGuard;
    private final EditorTextureRelationsAccess relationAccess;
    private final LongSupplier generationSupplier;
    private final EditorRawImagePsdAccess psdAccess;
    private final EditorRawImagePsdSourceBinding psdSourceBinding;
    private final EditorRawImagePsdReplaceAccess psdReplaceAccess;
    private static final int MAX_EXPORT_DIAGNOSTIC_SESSIONS = 8;
    private final Object exportDiagnosticGateLock = new Object();
    private final LinkedHashMap<ExportSessionKey, ExportDiagnosticGate> exportDiagnosticGates =
            new LinkedHashMap<>(MAX_EXPORT_DIAGNOSTIC_SESSIONS, 0.75f, true);
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
        this.psdAccess = new EditorRawImagePsdAccess(resolver, modelGuard::requireCurrent);
        this.psdSourceBinding =
                new EditorRawImagePsdSourceBinding(resolver, new EditorRawImagePsdIntegrityAccess(resolver));
        this.psdReplaceAccess = new EditorRawImagePsdReplaceAccess(resolver, modelGuard::requireCurrent);
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

    private void requireRegisterBindAuthorization() {
        if (!resolver.authorizesFeature(
                EditorTextureSelectorContract.ADAPTER_SLICE_ID,
                EditorTextureSelectorContract.REGISTER_BIND_CAPABILITY_ID,
                EditorTextureSelectorContract.REGISTER_BIND_REQUIRED_ALIASES)) {
            throw new UnsupportedOperationException(
                    "Model image registration and binding is unavailable without exact verified host evidence.");
        }
    }

    boolean supportsAppendTextureAtlasPages(final String identity, final Object model) {
        try {
            modelGuard.requireCurrent(identity, model);
        } catch (RuntimeException notCurrent) {
            return false;
        }
        if (!writeAuthorized()
                || !resolver.authorizesFeature(
                        VerifiedTextureAtlasSelectorContract.ADAPTER_SLICE_ID,
                        VerifiedTextureAtlasSelectorContract.CAPABILITY_ID,
                        APPEND_ATLAS_REQUIRED_ALIASES)) {
            return false;
        }
        if ("false".equalsIgnoreCase(System.getProperty(HostUndoMutationScope.ENABLED_PROPERTY))) {
            return false;
        }
        return resolver.authorizesFeature(
                EditorEditSessionSelectorContract.ADAPTER_SLICE_ID,
                EditorEditSessionSelectorContract.EDIT_BEGIN_CAPABILITY_ID,
                Set.of("cubism.editor-model.undo.group-undo"));
    }

    @SuppressWarnings("unchecked")
    private static void append(final Object value, final Object entry) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalStateException("Verified texture atlas list is unavailable.");
        }
        ((List<Object>) list).add(entry);
    }

    /**
     * Resolves the active native document and verifies it still owns the bound
     * source/model pair, mirroring the model-access binding without mutating it.
     */
    private Object resolveBoundDocument(final Object source, final Object model) {
        final Object app = resolver.invokeStatic(APP_INSTANCE);
        final Object document = app == null ? null : resolver.invoke(CURRENT_DOCUMENT, app);
        if (!resolver.isInstance(MODELING_DOCUMENT_CLASS, document)) {
            throw new IllegalStateException("The active Cubism document is not a modeling document.");
        }
        final Object activeSource = resolver.invoke(DOCUMENT_MODEL_SOURCE, document);
        final Object activeModel = activeSource == null ? null : resolver.invoke(MODEL_SOURCE_INSTANCE, activeSource);
        if (activeSource != source || activeModel != model) {
            throw new IllegalStateException("Cubism model reference is stale for the active Editor model generation.");
        }
        return document;
    }

    private VerifiedEditorAuthoringTransactionHost selfHost(
            final String identity, final Object source, final Object model, final long generation) {
        return new VerifiedEditorAuthoringTransactionHost(
                resolver,
                () -> new VerifiedEditorAuthoringTransactionHost.NativeBinding(
                        identity, generation, resolveBoundDocument(source, model), source, model),
                () -> generation);
    }

    private long generationOf(final String identity) {
        final int separator = identity.lastIndexOf(':');
        if (separator < 0 || separator == identity.length() - 1) {
            throw new IllegalStateException("Cubism model identity carries no generation.");
        }
        try {
            return Long.parseLong(identity.substring(separator + 1));
        } catch (NumberFormatException malformed) {
            throw new IllegalStateException("Cubism model identity carries no generation.", malformed);
        }
    }

    private String modelIdOf(final String identity) {
        final int separator = identity.lastIndexOf(':');
        if (separator < 0) return identity;
        final String head = identity.substring(0, separator);
        final int second = head.lastIndexOf(':');
        return second < 0 ? head : head.substring(second + 1);
    }

    /** Resolves one ArtMesh id to its unique native ArtMesh source, fail-closed. */
    private Object resolveArtMeshSource(final Object source, final ArtMeshId target) {
        Object match = null;
        for (final Object meshSource : list(ALL_ART_MESHES, source, "art mesh")) {
            if (!resolver.isInstance(ART_MESH_SOURCE_CLASS, meshSource)) {
                throw new IllegalStateException("Editor art mesh collection contains an invalid value.");
            }
            final Object id = resolver.invoke(SOURCE_ID, meshSource);
            final Object text = resolver.invoke(ID_VALUE, id);
            if (target.value().equals(text)) {
                if (match != null) {
                    throw new IllegalStateException(
                            "Cubism art mesh id resolves to multiple sources: " + target.value());
                }
                match = meshSource;
            }
        }
        if (match == null) {
            throw new NoSuchElementException("Cubism art mesh is absent: " + target.value());
        }
        return match;
    }

    /**
     * Rejects targets that are locked, deformable-parented, or already carry a
     * foreign extension. Returns the existing texture-input extension, or
     * {@code null} when the ArtMesh is unbound.
     */
    private Object requireBindableTarget(final Object meshSource) {
        if (Boolean.TRUE.equals(resolver.invoke(LOCKED_IN_HIERARCHY, meshSource))) {
            throw new IllegalStateException("Cubism art mesh is locked in its hierarchy.");
        }
        final Object parents = resolver.invoke(ALL_PARENT_DEFORMERS, meshSource);
        if (parents instanceof Iterable<?> deformers && deformers.iterator().hasNext()) {
            throw new IllegalStateException("Cubism art mesh sits under a deformer.");
        }
        final Object extensions = resolver.invoke(EXTENSIONS, meshSource);
        if (!(extensions instanceof List<?> extensionList)) {
            throw new IllegalStateException("Editor extension collection is unavailable.");
        }
        final Object textureInput = resolver.invoke(MESH_TEXTURE_INPUT, meshSource);
        boolean seenEditable = false;
        boolean seenTexture = false;
        for (final Object extension : extensionList) {
            if (extension != null && extension == textureInput && !seenTexture) {
                seenTexture = true;
            } else if (!seenEditable
                    && extension != null
                    && resolver.isInstance("cubism.editor-model.editable-mesh-extension.class", extension)
                    && resolver.invoke("cubism.editor-model.editable-mesh-extension.owner", extension) == meshSource
                    && Boolean.FALSE.equals(
                            resolver.invoke("cubism.editor-model.editable-mesh-extension.locked", extension))) {
                seenEditable = true;
            } else {
                throw new IllegalStateException("Cubism art mesh carries a foreign or locked extension.");
            }
        }
        if (textureInput != null && !seenTexture) {
            throw new IllegalStateException("Cubism texture-input extension is detached.");
        }
        return textureInput;
    }

    private void admit(final Object edit, final Object undoable) {
        final Object accepted = resolver.invoke(UNDO_ADD, edit, undoable, Boolean.TRUE);
        if (!(accepted instanceof Boolean admitted) || !admitted) {
            throw new IllegalStateException("Cubism rejected the model image Undo entry.");
        }
    }

    /**
     * The registration contribution: raw layered image, model image group, and
     * model image each carried by a self-held public {@code Undo_AddOrRemove}
     * that is purely admitted ({@code addEdit(u, true)}) and applied later via
     * {@code forceRedo} in the mutation phase. Compensation removes applied
     * members in reverse order only when they are still present.
     */
    private EditorUndoContribution registrationContribution(
            final EditorAuthoringTransactionCoordinator.Binding binding,
            final String operationId,
            final Object source,
            final RegistrationMembers members) {
        final Object textureManager = textureManager(source);
        final Object[] undoables = new Object[3];
        return new EditorUndoContribution(
                operationId,
                binding.modelIdentity() + ":model-image:" + members.modelImageGuidValue,
                "Turboism: Register Model Image",
                (edit, transactionLabel) -> {
                    undoables[0] = resolver.construct(UNDO_RAW_CREATE, source, members.wrapper, -1, true);
                    admit(edit, undoables[0]);
                    undoables[1] = resolver.construct(UNDO_GROUP_CREATE, source, members.group, -1, true);
                    admit(edit, undoables[1]);
                    undoables[2] = resolver.construct(UNDO_IMAGE_CREATE, members.group, members.modelImage, -1, true);
                    admit(edit, undoables[2]);
                },
                () -> {
                    resolver.invoke(ATLAS_UNDO_FORCE_REDO, undoables[0]);
                    resolver.invoke(ATLAS_UNDO_FORCE_REDO, undoables[1]);
                    resolver.invoke(ATLAS_UNDO_FORCE_REDO, undoables[2]);
                },
                () -> members.registered(textureManager),
                () -> members.unregister(textureManager, undoables),
                () -> members.unregistered(textureManager),
                EnumSet.of(
                        EditorRefreshRequirement.MODEL_INSTANCES,
                        EditorRefreshRequirement.MARK_DIRTY,
                        EditorRefreshRequirement.CANVAS),
                members.registrationDetail(binding.pluginId(), operationId));
    }

    /**
     * The binding contribution: one texture-input extension member plus two
     * deferred field undoables registered through {@code UUUndo.a(..., false,
     * ...)} (execute=false → pure registration). The extension is wired to its
     * unpublished input before admission; publication happens only through the
     * extension undoable's {@code forceRedo}; the two ArtMesh fields are written
     * manually in the mutation phase without {@code updateArtMeshSourceUvs}.
     */
    private EditorUndoContribution bindingContribution(
            final EditorAuthoringTransactionCoordinator.Binding binding,
            final String operationId,
            final Object meshSource,
            final Object modelImage,
            final BindingMembers members) {
        final Object[] undoables = new Object[3];
        final Object uuUndo = resolver.readStaticField(UU_UNDO_INSTANCE);
        return new EditorUndoContribution(
                operationId,
                binding.modelIdentity() + ":art-mesh:" + members.meshId,
                "Turboism: Bind Model Image",
                (edit, transactionLabel) -> {
                    undoables[0] = resolver.construct(UNDO_EXTENSION_CREATE, meshSource, members.extension, -1, true);
                    admit(edit, undoables[0]);
                    undoables[1] = resolver.invoke(
                            UU_UNDO_CREATE,
                            uuUndo,
                            transactionLabel,
                            Boolean.FALSE,
                            resolver.createFunctionalArgumentProxy(UU_UNDO_CREATE, 2, ignored -> {
                                resolver.invoke(MESH_SET_TEXTURE, meshSource, members.newTexture());
                                return null;
                            }),
                            resolver.createFunctionalArgumentProxy(UU_UNDO_CREATE, 3, ignored -> {
                                resolver.invoke(MESH_SET_TEXTURE, meshSource, members.oldTexture);
                                return null;
                            }),
                            null);
                    admit(edit, undoables[1]);
                    undoables[2] = resolver.invoke(
                            UU_UNDO_CREATE,
                            uuUndo,
                            transactionLabel,
                            Boolean.FALSE,
                            resolver.createFunctionalArgumentProxy(UU_UNDO_CREATE, 2, ignored -> {
                                resolver.invoke(MESH_SET_TEXTURE_STATE, meshSource, members.modelImageState);
                                return null;
                            }),
                            resolver.createFunctionalArgumentProxy(UU_UNDO_CREATE, 3, ignored -> {
                                resolver.invoke(MESH_SET_TEXTURE_STATE, meshSource, members.oldState);
                                return null;
                            }),
                            null);
                    admit(edit, undoables[2]);
                },
                () -> {
                    resolver.invoke(ATLAS_UNDO_FORCE_REDO, undoables[0]);
                    members.publish(meshSource);
                },
                () -> members.bound(meshSource),
                () -> members.unbind(meshSource, undoables[0]),
                () -> members.unbound(meshSource),
                EnumSet.of(
                        EditorRefreshRequirement.MODEL_INSTANCES,
                        EditorRefreshRequirement.MARK_DIRTY,
                        EditorRefreshRequirement.CANVAS),
                members.bindingDetail(binding.pluginId(), operationId));
    }

    /** Constructs the unpublished {@code CImageResource} and decodes it exactly once. */
    private Object decodeResource(final AuthorizedImageContent image) {
        final Object colorType = resolver.readStaticField(IMAGE_COLOR_TYPE);
        final Object resource = resolver.construct(IMAGE_RESOURCE_CREATE, image.copyBytes(), colorType, Boolean.TRUE);
        final Object decoded = resolver.invoke(IMAGE_RESOURCE_IMAGE, resource);
        if (decoded == null) {
            throw new IllegalStateException("Cubism image decode produced no image.");
        }
        final int width = dimension(resource, IMAGE_RESOURCE_WIDTH, "registered image");
        final int height = dimension(resource, IMAGE_RESOURCE_HEIGHT, "registered image");
        if (width != image.width() || height != image.height()) {
            throw new IllegalStateException(
                    "Decoded image dimensions must match declared " + image.width() + "x" + image.height() + ".");
        }
        return resource;
    }

    /** Builds the unpublished layered image, wrapper, group, and wired model image. */
    private RegistrationMembers buildRegistration(final Object source, final AuthorizedImageContent image) {
        final Object resource = decodeResource(image);
        final String name = image.sourceDescription();
        final Object layered = resolver.construct(LAYERED_IMAGE_CREATE, resource, null, name, name);
        final Object layeredGuid = resolver.invoke(LAYERED_IMAGE_GUID, layered);
        final Object wrapper = resolver.construct(WRAPPER_CREATE, layered, -1L, -1L, Boolean.FALSE);
        final Object group = resolver.construct(GROUP_CREATE, name);
        resolver.invoke(GROUP_LINK, group, layeredGuid);
        final Object modelImageGuid = resolver.construct(MODEL_IMAGE_GUID_CREATE);
        final Object modelImage = resolver.construct(MODEL_IMAGE_CREATE, source, name, modelImageGuid);
        wireModelImageFilters(modelImage, resource);
        claimCurrentImage(modelImage, layeredGuid);
        final Object linked = resolver.invoke(MODEL_IMAGE_LINKED_GUIDS, modelImage);
        if (!(linked instanceof List<?>)) {
            throw new IllegalStateException("Editor linked-raw-image collection is unavailable.");
        }
        @SuppressWarnings("unchecked")
        final List<Object> linkedGuids = (List<Object>) linked;
        linkedGuids.add(layeredGuid);
        return new RegistrationMembers(
                resource,
                layered,
                layeredGuid,
                wrapper,
                group,
                modelImage,
                guidValue(layeredGuid, "raw image"),
                guidValue(modelImageGuid, "model image"));
    }

    /**
     * Replays the native {@code CModelImage$a.a} filter wiring on the unpublished
     * model image: one "Image" EmptyFilter fed by a {@code ConstValueConnector}
     * over the image resource and one "Transform" EmptyFilter fed by a constant
     * {@code CAffine}, each connected to its environment output, then verified.
     */
    private void wireModelImageFilters(final Object modelImage, final Object resource) {
        final Object companion = resolver.readStaticField(ENV_COMPANION);
        final Object imageOutput = resolver.invoke(ENV_OUTPUT_IMAGE, companion);
        final Object transformOutput = resolver.invoke(ENV_OUTPUT_TRANSFORM, companion);
        final Object filter = resolver.invoke(MODEL_IMAGE_FILTER, modelImage);
        final Object imageValue =
                resolver.construct(FILTER_VALUE_CREATE, "Image", resolver.invoke(FILTER_VALUE_ID, imageOutput), null);
        final Object imageInstance =
                resolver.invoke(FILTER_SET_ADD, filter, resolver.construct(EMPTY_FILTER_CREATE, imageValue), null);
        resolver.invoke(
                FILTER_INSTANCE_SET_INPUT,
                imageInstance,
                imageValue,
                resolver.construct(CONST_CONNECTOR_CREATE, resource));
        resolver.invoke(FILTER_SET_CONNECT_OUTPUT, filter, imageInstance, imageValue, imageOutput);
        final Object transformValue = resolver.construct(
                FILTER_VALUE_CREATE, "Transform", resolver.invoke(FILTER_VALUE_ID, transformOutput), null);
        final Object transformInstance =
                resolver.invoke(FILTER_SET_ADD, filter, resolver.construct(EMPTY_FILTER_CREATE, transformValue), null);
        resolver.invoke(
                FILTER_INSTANCE_SET_INPUT,
                transformInstance,
                transformValue,
                resolver.construct(CONST_CONNECTOR_CREATE, resolver.construct(CAFFINE_CREATE)));
        resolver.invoke(FILTER_SET_CONNECT_OUTPUT, filter, transformInstance, transformValue, transformOutput);
        resolver.invoke(FILTER_SET_VERIFY, filter, Boolean.TRUE, resolver.construct(VERIFY_COLLECTOR_CREATE));
    }

    /**
     * Publishes the raw-image ownership marker the native save-time check
     * requires: {@code TextureManagerHandler.b()} counts a raw image as used
     * only when some model image's {@code inputFilterEnv} carries
     * {@code currentImageGuid == rawGuid} ({@code c(CLayeredImageGuid)},
     * verified 5.3.02 bytecode); {@code linkedRawImageGuids} is never consulted.
     * Native layer-linked factories write the same env value
     * ({@code CEPsdImport_Import.a} / {@code io.b.d$c.a}), and the env value set
     * is {@code @Serialize}-persisted, so the marker survives save and reopen.
     * Runs while the model image is still unpublished; the marker lives on the
     * retained instance, so Undo membership removal and Redo re-add keep it.
     */
    private void claimCurrentImage(final Object modelImage, final Object layeredGuid) {
        final Object env = resolver.invoke(MODEL_IMAGE_FILTER_ENV, modelImage);
        resolver.invoke(ENV_SET_CURRENT_IMAGE_GUID, env, layeredGuid);
        if (!Boolean.TRUE.equals(resolver.invoke(ENV_HAS_CURRENT_IMAGE_GUID, env))
                || !guidValue(layeredGuid, "raw image")
                        .equals(guidValue(resolver.invoke(ENV_CURRENT_IMAGE_GUID, env), "raw image"))) {
            throw new IllegalStateException("Cubism model image did not retain its source image identity.");
        }
    }

    /** Resolves a registered model image by GUID, fail-closed. */
    private Object findModelImage(final Object source, final ModelImageId id) {
        for (final Object image : list(ALL_MODEL_IMAGES, textureManager(source), "model image")) {
            if (!resolver.isInstance("cubism.editor-model.model-image.class", image)) {
                throw new IllegalStateException("Editor model image collection contains an invalid value.");
            }
            final Object guid = resolver.invoke(MODEL_IMAGE_GUID, image);
            if (guidValue(guid, "model image").equals(id.value())) {
                return image;
            }
        }
        throw new NoSuchElementException("Cubism model image is absent: " + id.value());
    }

    /** Reads the first raw layered image GUID linked to a model image. */
    private String linkedRawImageId(final Object modelImage) {
        final Object linked = resolver.invoke(MODEL_IMAGE_LINKED_GUIDS, modelImage);
        if (!(linked instanceof List<?> guids) || guids.isEmpty()) {
            throw new IllegalStateException("Cubism model image is not linked to a raw image.");
        }
        return guidValue(guids.get(0), "raw image");
    }

    private void requireTextureInputReadAuthorization() {
        if (!resolver.authorizesFeature(
                EditorTextureSelectorContract.ADAPTER_SLICE_ID,
                EditorTextureSelectorContract.READ_CAPABILITY_ID,
                EditorTextureSelectorContract.TEXTURE_INPUT_OBSERVE_ALIASES)) {
            throw new UnsupportedOperationException(
                    "ArtMesh texture input observation is unavailable without exact verified host evidence.");
        }
    }

    /**
     * Read-only observation of the native texture input bound to one ArtMesh.
     * Runs entirely on the EDT; performs no edit, Undo, dirty, or event calls.
     * {@code Optional.empty()} means the mesh genuinely carries no extension or
     * no current input; every other absence of evidence fails closed.
     */
    private Optional<ArtMeshTextureInput> observeTextureInput(
            final String identity, final Object source, final Object model, final ArtMeshId target) {
        // Runs inside the dispatched EDT closure: the generation/current-model guard
        // is re-verified here so a document switch between dispatch and the read
        // cannot serve observations of a superseded binding.
        modelGuard.requireCurrent(identity, model);
        resolveBoundDocument(source, model);
        final Object meshSource = resolveArtMeshSource(source, target);
        final Object extension = resolver.invoke(MESH_TEXTURE_INPUT, meshSource);
        if (extension == null) {
            return Optional.empty();
        }
        final Object current = resolver.invoke(EXTENSION_CURRENT_INPUT, extension);
        if (current == null) {
            return Optional.empty();
        }
        final long generation = generationOf(identity);
        final ModelId modelId = new ModelId(modelIdOf(identity));
        if (resolver.isInstance(INPUT_CLASS, current)) {
            final Object modelImage = resolver.invoke(INPUT_MODEL_IMAGE, current);
            if (modelImage == null) {
                throw new IllegalStateException("Cubism model-image texture input resolves to no model image.");
            }
            if (!resolver.isInstance("cubism.editor-model.model-image.class", modelImage)) {
                throw new IllegalStateException("Cubism model-image texture input carries an invalid model image.");
            }
            final String imageGuid = guidValue(resolver.invoke(MODEL_IMAGE_GUID, modelImage), "model image");
            requireUniqueModelImageMembership(source, imageGuid, modelImage);
            return Optional.of(new ArtMeshTextureInput(
                    target,
                    modelId,
                    generation,
                    ArtMeshTextureInput.Kind.MODEL_IMAGE,
                    Optional.of(new ModelImageId(imageGuid)),
                    Optional.empty()));
        }
        if (resolver.isInstance(ATLAS_REGION_INPUT_CLASS, current)) {
            final String atlasGuid = guidValue(resolver.invoke(ATLAS_REGION_GUID, current), "texture atlas region");
            requireUniqueAtlasMembership(source, atlasGuid);
            return Optional.of(new ArtMeshTextureInput(
                    target,
                    modelId,
                    generation,
                    ArtMeshTextureInput.Kind.TEXTURE_ATLAS_REGION,
                    Optional.empty(),
                    Optional.of(new TextureAtlasId(atlasGuid))));
        }
        throw new IllegalStateException("Cubism art mesh carries an unknown texture input type: "
                + current.getClass().getName());
    }

    /**
     * Verifies the bound image resolves to exactly one entry of the active
     * model's own image table — not dangling, duplicated, or foreign.
     */
    private void requireUniqueModelImageMembership(
            final Object source, final String imageGuid, final Object boundImage) {
        Object unique = null;
        int matches = 0;
        for (final Object image : list(ALL_MODEL_IMAGES, textureManager(source), "model image")) {
            if (!resolver.isInstance("cubism.editor-model.model-image.class", image)) {
                throw new IllegalStateException("Editor model image collection contains an invalid value.");
            }
            if (guidValue(resolver.invoke(MODEL_IMAGE_GUID, image), "model image")
                    .equals(imageGuid)) {
                unique = image;
                matches++;
            }
        }
        if (matches != 1 || unique != boundImage) {
            throw new IllegalStateException(
                    "Cubism texture input references a model image outside the active model's manager.");
        }
    }

    /**
     * Verifies the bound atlas GUID resolves to exactly one atlas owned by the
     * active model's texture manager — not dangling or duplicated.
     */
    private void requireUniqueAtlasMembership(final Object source, final String atlasGuid) {
        int matches = 0;
        for (final Object atlas : list(TEXTURE_ATLASES, textureManager(source), "texture atlas")) {
            if (!resolver.isInstance("cubism.editor-model.texture-atlas.class", atlas)) {
                throw new IllegalStateException("Editor texture atlas collection contains an invalid value.");
            }
            final Object guid = resolver.invoke("cubism.editor-model.texture-atlas.guid", atlas);
            if (guidValue(guid, "texture atlas").equals(atlasGuid)) {
                matches++;
            }
        }
        if (matches != 1) {
            throw new IllegalStateException(
                    "Cubism texture input references a texture atlas outside the active model's manager.");
        }
    }

    /**
     * The model image currently bound through one texture-input extension, or
     * {@code null} when the extension carries no model-image input.
     */
    private Object boundModelImage(final Object extension) {
        final Object current = resolver.invoke(EXTENSION_CURRENT_INPUT, extension);
        if (current == null || !resolver.isInstance(INPUT_CLASS, current)) {
            return null;
        }
        return resolver.invoke(INPUT_MODEL_IMAGE, current);
    }

    /** Unpublished registration members plus their owner-table membership protocol. */
    private final class RegistrationMembers {
        private final Object resource;
        private final Object layered;
        private final Object layeredGuid;
        private final Object wrapper;
        private final Object group;
        private final Object modelImage;
        private final String rawImageGuidValue;
        private final String modelImageGuidValue;

        private RegistrationMembers(
                final Object resource,
                final Object layered,
                final Object layeredGuid,
                final Object wrapper,
                final Object group,
                final Object modelImage,
                final String rawImageGuidValue,
                final String modelImageGuidValue) {
            this.resource = resource;
            this.layered = layered;
            this.layeredGuid = layeredGuid;
            this.wrapper = wrapper;
            this.group = group;
            this.modelImage = modelImage;
            this.rawImageGuidValue = rawImageGuidValue;
            this.modelImageGuidValue = modelImageGuidValue;
        }

        private boolean registered(final Object textureManager) {
            return list(RAW_IMAGES, textureManager, "raw image").contains(wrapper)
                    && list(MODEL_IMAGE_GROUPS, textureManager, "model image group")
                            .contains(group)
                    && list(GROUP_IMAGES, group, "model image").contains(modelImage)
                    && resolver.invoke(MODEL_IMAGE_FILTER_ENV, modelImage) != null;
        }

        private void unregister(final Object textureManager, final Object[] undoables) {
            if (list(GROUP_IMAGES, group, "model image").contains(modelImage)) {
                resolver.invoke(UNDO_EXECUTE_UNDO, undoables[2]);
            }
            if (list(MODEL_IMAGE_GROUPS, textureManager, "model image group").contains(group)) {
                resolver.invoke(UNDO_EXECUTE_UNDO, undoables[1]);
            }
            if (list(RAW_IMAGES, textureManager, "raw image").contains(wrapper)) {
                resolver.invoke(UNDO_EXECUTE_UNDO, undoables[0]);
            }
        }

        private boolean unregistered(final Object textureManager) {
            return !list(RAW_IMAGES, textureManager, "raw image").contains(wrapper)
                    && !list(MODEL_IMAGE_GROUPS, textureManager, "model image group")
                            .contains(group)
                    && !list(GROUP_IMAGES, group, "model image").contains(modelImage);
        }

        private HistoryEntryDetail registrationDetail(final String pluginId, final String operationId) {
            return new HistoryEntryDetail(
                    "Register model image " + modelImageGuidValue,
                    HistoryAction.DetailLevel.FULL,
                    HistoryOrigin.turboism(pluginId, operationId),
                    List.of(
                            new HistoryTarget("RAW_IMAGE", Optional.of(rawImageGuidValue), Optional.empty()),
                            new HistoryTarget("MODEL_IMAGE", Optional.of(modelImageGuidValue), Optional.empty())),
                    List.of(
                            new HistoryChange(
                                    HistoryChange.Operation.ADD,
                                    Optional.of(0),
                                    Optional.of("rawImage"),
                                    Optional.empty(),
                                    Optional.of(rawImageGuidValue),
                                    new HistoryEditContext(
                                            HistoryEditContext.Kind.OBJECT, Optional.empty(), List.of())),
                            new HistoryChange(
                                    HistoryChange.Operation.ADD,
                                    Optional.of(1),
                                    Optional.of("modelImage"),
                                    Optional.empty(),
                                    Optional.of(modelImageGuidValue),
                                    new HistoryEditContext(
                                            HistoryEditContext.Kind.OBJECT, Optional.empty(), List.of()))),
                    Optional.empty(),
                    Optional.empty());
        }
    }

    /** Binding members plus the texture-input extension ownership protocol. */
    private final class BindingMembers {
        private final String meshId;
        private final Object extension;
        private final Object input;
        private final Object modelImage;
        private final Object oldTexture;
        private final Object oldState;
        private final Object modelImageState;
        private final Object[] texture = new Object[1];

        private BindingMembers(
                final String meshId,
                final Object extension,
                final Object input,
                final Object modelImage,
                final Object oldTexture,
                final Object oldState,
                final Object modelImageState) {
            this.meshId = meshId;
            this.extension = extension;
            this.input = input;
            this.modelImage = modelImage;
            this.oldTexture = oldTexture;
            this.oldState = oldState;
            this.modelImageState = modelImageState;
        }

        private Object newTexture() {
            return texture[0];
        }

        /**
         * The fork-(b) controlled deviation: resolve the bound texture once and
         * write the two ArtMesh fields manually; UVs and the mesh-updated flag
         * are deliberately untouched.
         */
        private void publish(final Object meshSource) {
            final Object param =
                    resolver.invoke(CACHED_PARAM_DEFAULT, resolver.readStaticField(CACHED_PARAM_COMPANION));
            final Object cached = resolver.invoke(INPUT_CACHED_IMAGE, input, param);
            texture[0] = Objects.requireNonNull(resolver.invoke(CACHED_IMAGE_TEXTURE, cached), "bound texture");
            resolver.invoke(MESH_SET_TEXTURE, meshSource, texture[0]);
            resolver.invoke(MESH_SET_TEXTURE_STATE, meshSource, modelImageState);
        }

        private boolean bound(final Object meshSource) {
            final Object inputs = resolver.invoke(EXTENSION_INPUTS, extension);
            if (!(inputs instanceof List<?> inputList)
                    || !inputList.contains(input)
                    || resolver.invoke(MESH_TEXTURE_INPUT, meshSource) != extension
                    || resolver.invoke(EXTENSION_CURRENT_INPUT, extension) != input
                    || resolver.invoke(INPUT_MODEL_IMAGE, input) != modelImage) {
                return false;
            }
            return resolver.invoke(MESH_TEXTURE, meshSource) == texture[0]
                    && resolver.invoke(MESH_TEXTURE_STATE, meshSource) == modelImageState;
        }

        private void unbind(final Object meshSource, final Object extensionUndoable) {
            if (resolver.invoke(MESH_TEXTURE, meshSource) != oldTexture) {
                resolver.invoke(MESH_SET_TEXTURE, meshSource, oldTexture);
            }
            if (resolver.invoke(MESH_TEXTURE_STATE, meshSource) != oldState) {
                resolver.invoke(MESH_SET_TEXTURE_STATE, meshSource, oldState);
            }
            final Object extensions = resolver.invoke(EXTENSIONS, meshSource);
            if (extensions instanceof List<?> list && list.contains(extension)) {
                resolver.invoke(UNDO_EXECUTE_UNDO, extensionUndoable);
            }
        }

        private boolean unbound(final Object meshSource) {
            final Object extensions = resolver.invoke(EXTENSIONS, meshSource);
            return extensions instanceof List<?> list
                    && !list.contains(extension)
                    && resolver.invoke(MESH_TEXTURE, meshSource) == oldTexture
                    && resolver.invoke(MESH_TEXTURE_STATE, meshSource) == oldState;
        }

        private HistoryEntryDetail bindingDetail(final String pluginId, final String operationId) {
            return new HistoryEntryDetail(
                    "Bind model image to " + meshId,
                    HistoryAction.DetailLevel.FULL,
                    HistoryOrigin.turboism(pluginId, operationId),
                    List.of(new HistoryTarget("ART_MESH", Optional.of(meshId), Optional.empty())),
                    List.of(new HistoryChange(
                            HistoryChange.Operation.SET,
                            Optional.of(0),
                            Optional.of("textureInput"),
                            Optional.of("none"),
                            Optional.of("model-image"),
                            new HistoryEditContext(HistoryEditContext.Kind.OBJECT, Optional.empty(), List.of()))),
                    Optional.empty(),
                    Optional.empty());
        }
    }

    /**
     * Offline decode oracle: the three private {@code CImageResource} decode
     * counters are pinned by the verification record but are only resolvable
     * where the host exposes them (the offline fixture publishes same-signature
     * public statics). Never invoked on the real host, where the fields remain
     * private and fail closed.
     */
    Map<String, Integer> decodeOracleSnapshot() {
        return Map.of(
                "loadedCount", (Integer) resolver.readStaticField(IMAGE_LOADED_COUNT),
                "loadedBytes", (Integer) resolver.readStaticField(IMAGE_LOADED_BYTES),
                "byteDataBytes", (Integer) resolver.readStaticField(IMAGE_BYTE_DATA_BYTES));
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
     * Model images whose current raw image is the replaced target in the pre-replacement read.
     * This is the bounded observation subset for the requested target; native matching may
     * affect other model images, which this method deliberately does not infer.
     */
    private static List<ModelImageId> modelImagesUsing(
            final TextureRelationsSnapshot snapshot, final RawImageId target) {
        final List<ModelImageId> affected = new ArrayList<>();
        for (final ModelImageRelation relation : snapshot.modelImages()) {
            if (relation.currentRawImageId().filter(target::equals).isPresent()) {
                affected.add(relation.id());
            }
        }
        return List.copyOf(affected);
    }

    /**
     * Proves an incoming binding on a previously targeted model image. Native structural matching
     * may retain or remove unmatched images; agreement of every old image is not its contract.
     */
    static Optional<RawImageId> observedRawImage(
            final TextureRelationsSnapshot after,
            final List<ModelImageId> affected,
            final RawImageId expectedIncoming) {
        if (after == null || !after.isAvailable() || affected.isEmpty() || expectedIncoming == null) {
            return Optional.empty();
        }
        if (after.rawImages().stream()
                        .filter(raw -> expectedIncoming.equals(raw.id()))
                        .count()
                != 1L) {
            return Optional.empty();
        }
        boolean observed = false;
        for (final ModelImageId id : affected) {
            final Optional<ModelImageRelation> relation = after.modelImage(id);
            if (relation.isEmpty()
                    || relation.orElseThrow()
                            .currentRawImageId()
                            .filter(expectedIncoming::equals)
                            .isEmpty()) continue;
            final ModelImageRelation image = relation.orElseThrow();
            if (!image.linkedRawImageIds().contains(expectedIncoming)
                    || image.inputsByRawImage()
                            .getOrDefault(expectedIncoming, List.of())
                            .isEmpty()) {
                // A dangling current pointer or an empty selector is not application evidence.
                return Optional.empty();
            }
            observed = true;
        }
        return observed ? Optional.of(expectedIncoming) : Optional.empty();
    }

    /** Explains a failed observation using existing snapshots only; never changes admission or matching. */
    static PsdReplaceHost.Failure replacementObservationFailure(
            final TextureRelationsSnapshot before,
            final TextureRelationsSnapshot after,
            final RawImageId target,
            final RawImageId incoming) {
        final long incomingCount = after.rawImages().stream()
                .filter(raw -> incoming.equals(raw.id()))
                .count();
        final List<ModelImageId> affected = modelImagesUsing(before, target);
        String category = "INCOMING_NOT_CURRENT";
        if (incomingCount == 0L) {
            category = "INCOMING_RAW_ABSENT";
        } else if (incomingCount != 1L) {
            category = "INCOMING_RAW_NOT_UNIQUE";
        } else if (affected.isEmpty()) {
            category = "TARGET_MODEL_IMAGES_ABSENT";
        } else {
            boolean hasTargetLayerInputs = false;
            for (final ModelImageId id : affected) {
                final ModelImageRelation original = before.modelImage(id).orElseThrow();
                hasTargetLayerInputs |= !original.inputsByRawImage()
                        .getOrDefault(target, List.of())
                        .isEmpty();
                final Optional<ModelImageRelation> current = after.modelImage(id);
                if (current.isEmpty()
                        || current.orElseThrow()
                                .currentRawImageId()
                                .filter(incoming::equals)
                                .isEmpty()) continue;
                final ModelImageRelation image = current.orElseThrow();
                if (!image.linkedRawImageIds().contains(incoming)) {
                    return new PsdReplaceHost.Failure("POST_BINDING_OBSERVATION", "INCOMING_LINK_MISSING");
                }
                if (image.inputsByRawImage().getOrDefault(incoming, List.of()).isEmpty()) {
                    return new PsdReplaceHost.Failure("POST_BINDING_OBSERVATION", "INCOMING_LAYER_INPUTS_EMPTY");
                }
            }
            if (!hasTargetLayerInputs) category = "TARGET_LAYER_INPUTS_EMPTY_BEFORE";
        }
        return new PsdReplaceHost.Failure("POST_BINDING_OBSERVATION", category);
    }

    private static String message(final Throwable failure) {
        return failure.getMessage() == null ? failure.getClass().getName() : failure.getMessage();
    }

    /**
     * Shares the one-shot export budget across every texture facade for one exact model session.
     * The bounded LRU is keyed by object identity for source/model, plus the runtime binding and
     * generation; no facade from another session can consume or observe this gate.
     */
    private ExportDiagnosticGate exportDiagnosticGate(final String identity, final Object source, final Object model) {
        final ExportSessionKey key = new ExportSessionKey(identity, generationSupplier.getAsLong(), source, model);
        synchronized (exportDiagnosticGateLock) {
            final ExportDiagnosticGate existing = exportDiagnosticGates.get(key);
            if (existing != null) return existing;
            if (exportDiagnosticGates.size() >= MAX_EXPORT_DIAGNOSTIC_SESSIONS) {
                final var oldest = exportDiagnosticGates.entrySet().iterator();
                if (oldest.hasNext()) {
                    oldest.next();
                    oldest.remove();
                }
            }
            final ExportDiagnosticGate created = new ExportDiagnosticGate(key);
            exportDiagnosticGates.put(key, created);
            return created;
        }
    }

    private static final class ExportDiagnosticGate {
        private final ExportSessionKey key;
        private final AtomicBoolean consumed = new AtomicBoolean();

        private ExportDiagnosticGate(final ExportSessionKey key) {
            this.key = key;
        }

        private boolean consume() {
            return consumed.compareAndSet(false, true);
        }
    }

    private static final class ExportSessionKey {
        private final String identity;
        private final long generation;
        private final Object source;
        private final Object model;

        private ExportSessionKey(
                final String identity, final long generation, final Object source, final Object model) {
            this.identity = identity;
            this.generation = generation;
            this.source = source;
            this.model = model;
        }

        @Override
        public boolean equals(final Object other) {
            if (this == other) return true;
            if (!(other instanceof ExportSessionKey key)) return false;
            return generation == key.generation
                    && Objects.equals(identity, key.identity)
                    && source == key.source
                    && model == key.model;
        }

        @Override
        public int hashCode() {
            int result = Objects.hashCode(identity);
            result = 31 * result + Long.hashCode(generation);
            result = 31 * result + System.identityHashCode(source);
            result = 31 * result + System.identityHashCode(model);
            return result;
        }
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
            Objects.requireNonNull(admission, "admission");
            final AtomicBoolean exportGuardSeen = new AtomicBoolean();
            final AtomicBoolean postGuardStarted = new AtomicBoolean();
            final AtomicReference<EditorTextureReplacementDiagnostic.ExportSession> diagnosticSession =
                    new AtomicReference<>();
            final EditorRawImagePsdAccess access = new EditorRawImagePsdAccess(resolver, (id, currentModel) -> {
                admission.run();
                final boolean firstGuard = exportGuardSeen.compareAndSet(false, true);
                if (!firstGuard) postGuardStarted.set(true);
                modelGuard.requireCurrent(id, currentModel);
                if (!EditorTextureReplacementDiagnostic.enabled()) return;
                if (firstGuard) {
                    try {
                        final ExportDiagnosticGate gate = exportDiagnosticGate(identity, source, model);
                        final EditorRawImagePsdSourceBinding.BindingResult binding =
                                psdSourceBinding.bindOnHostThread(source, sourceId);
                        if (binding.status() != EditorRawImagePsdSourceBinding.BindingStatus.MATCHED
                                || !gate.consume()) {
                            return;
                        }
                        final Object document = diagnosticDocument();
                        EditorTextureReplacementDiagnostic.beginExport(
                                        resolver, identity, source, document, model, sourceId)
                                .ifPresent(diagnosticSession::set);
                    } catch (RuntimeException | LinkageError ignored) {
                        // Diagnostic setup must not change admission or ordinary export behavior.
                    }
                    return;
                }
                final EditorTextureReplacementDiagnostic.ExportSession session = diagnosticSession.get();
                if (session != null) session.finish(true, null);
            });
            try {
                final EditorRawImagePsdAccess.ExportResult result =
                        access.exportPsd(identity, source, model, sourceId, destination);
                final EditorTextureReplacementDiagnostic.ExportSession session = diagnosticSession.getAndSet(null);
                if (session != null) {
                    // A save that did not reach the access' second current-guard has no valid
                    // post native read; the finish method records UNAVAILABLE without reading host.
                    session.finish(false, "export-post-guard-not-observed");
                }
                return result.observation();
            } catch (RuntimeException | LinkageError exportFailure) {
                final EditorTextureReplacementDiagnostic.ExportSession session = diagnosticSession.getAndSet(null);
                if (session != null) {
                    final String cause = postGuardStarted.get()
                            ? "post-current-guard-failed:" + message(exportFailure)
                            : "export-call-threw:" + message(exportFailure);
                    session.finish(false, cause);
                }
                throw exportFailure;
            }
        }

        private Object diagnosticDocument() {
            try {
                final Object app = resolver.invokeStatic(APP_INSTANCE);
                return app == null ? null : resolver.invoke(CURRENT_DOCUMENT, app);
            } catch (RuntimeException | LinkageError ignored) {
                return null;
            }
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
            Objects.requireNonNull(admission, "admission");
            return EditorHostThread.dispatch(
                    "Cubism PSD raw-image replace",
                    () -> replaceOnHostThread(target, stage, sourceFileName, admission));
        }

        private Replacement replaceOnHostThread(
                final RawImageId target, final Path stage, final String sourceFileName, final Runnable admission) {
            admission.run();
            modelGuard.requireCurrent(identity, model);

            final TextureRelationsSnapshot before = relationAccess.relations(identity, source, model);
            final EditorRawImagePsdSourceBinding.BindingResult binding =
                    psdSourceBinding.bindIdentityOnHostThread(source, target);
            if (binding.status() != EditorRawImagePsdSourceBinding.BindingStatus.MATCHED) {
                return new Replacement(
                        "TARGET_NOT_FOUND",
                        true,
                        false,
                        false,
                        false,
                        false,
                        Optional.empty(),
                        "The requested raw image is not exactly resolvable in the current model source.");
            }

            final Object appController;
            final Object document;
            try {
                appController = resolver.invokeStatic("cubism.editor-model.app-controller.instance");
                document = appController == null
                        ? null
                        : resolver.invoke("cubism.editor-model.app-controller.current-document", appController);
            } catch (RuntimeException resolutionFailure) {
                return new Replacement(
                        "DOCUMENT_UNAVAILABLE",
                        true,
                        false,
                        false,
                        false,
                        false,
                        Optional.empty(),
                        "The current Cubism document could not be resolved for replacement.");
            }

            final Object incoming;
            try {
                incoming = psdAccess.parseStageOnHostThread(stage, sourceFileName);
            } catch (IOException | RuntimeException parseFailure) {
                return EditorRawImagePsdAccess.unreadableStage(parseFailure);
            }

            final RawImageId incomingRaw;
            try {
                // This is production application evidence, not opt-in diagnostic capture. Keep
                // the exact parsed identity fixed across the one native call and fresh reread.
                incomingRaw =
                        new RawImageId(guidValue(resolver.invoke(LAYERED_IMAGE_GUID, incoming), "incoming raw image"));
            } catch (RuntimeException | LinkageError identityFailure) {
                return new Replacement(
                        "UNAVAILABLE",
                        true,
                        false,
                        false,
                        false,
                        false,
                        Optional.empty(),
                        "The parsed incoming raw image GUID could not be verified before native replace.");
            }
            if (target.equals(incomingRaw)) {
                return new Replacement(
                        "UNAVAILABLE",
                        true,
                        false,
                        false,
                        false,
                        false,
                        Optional.empty(),
                        "The parsed incoming raw image is not distinct from the requested target.");
            }

            final EditorTextureReplacementDiagnostic.Session diagnostic =
                    replacementDiagnostic(before, document, target, incoming);
            final EditorRawImagePsdReplaceAccess.ReplaceResult nativeResult;
            try {
                nativeResult = psdReplaceAccess.replacePsd(
                        identity,
                        model,
                        appController,
                        document,
                        List.of(binding.candidate().nativeSource()),
                        incoming,
                        stage);
            } catch (RuntimeException | LinkageError nativeCallFailure) {
                finishDiagnostic(diagnostic, null, null, "replace-call-threw:" + message(nativeCallFailure));
                throw nativeCallFailure;
            }

            if (nativeResult.status() == EditorRawImagePsdReplaceAccess.ReplaceStatus.EDITING_REJECTED) {
                finishDiagnostic(diagnostic, null, nativeResult, "native-not-invoked:editing-rejected");
                return new Replacement(
                        "HOST_EDIT_IN_PROGRESS",
                        nativeResult.postCurrentGuardPassed(),
                        false,
                        false,
                        true,
                        false,
                        Optional.empty(),
                        "Cubism is currently applying another edit; no replacement was attempted.");
            }
            if (nativeResult.status() == EditorRawImagePsdReplaceAccess.ReplaceStatus.UNAVAILABLE) {
                finishDiagnostic(diagnostic, null, nativeResult, "native-not-invoked:unavailable");
                return new Replacement(
                        "UNAVAILABLE",
                        nativeResult.postCurrentGuardPassed(),
                        false,
                        false,
                        false,
                        false,
                        Optional.empty(),
                        "The native replacement seam is unavailable for this host build.");
            }
            if (nativeResult.status() == EditorRawImagePsdReplaceAccess.ReplaceStatus.INVALID_INPUT) {
                finishDiagnostic(diagnostic, null, nativeResult, "native-not-invoked:invalid-input");
                return new Replacement(
                        "INVALID_INPUT",
                        nativeResult.postCurrentGuardPassed(),
                        false,
                        false,
                        false,
                        false,
                        Optional.empty(),
                        "The native replacement inputs were rejected before any mutation.");
            }
            if (nativeResult.status() == EditorRawImagePsdReplaceAccess.ReplaceStatus.PARTIAL_FAILURE) {
                finishDiagnosticAfterAttempt(diagnostic, nativeResult);
                return new Replacement(
                        "NATIVE_OUTCOME_UNKNOWN",
                        nativeResult.postCurrentGuardPassed(),
                        false,
                        true,
                        false,
                        false,
                        Optional.empty(),
                        "The native replacement did not report a usable outcome; mutation state is unknown.");
            }

            // The native call returned. Application is claimed only from a fresh observation.
            final TextureRelationsSnapshot after;
            try {
                after = relationAccess.relations(identity, source, model);
            } catch (RuntimeException rereadFailure) {
                finishDiagnostic(diagnostic, null, nativeResult, "post-relation-read-failed:" + message(rereadFailure));
                return new Replacement(
                        "NATIVE_RETURNED_UNOBSERVED",
                        nativeResult.postCurrentGuardPassed(),
                        true,
                        false,
                        false,
                        false,
                        Optional.empty(),
                        "The native replacement returned but current state could not be re-read.");
            }
            finishDiagnostic(diagnostic, after, nativeResult, null);
            if (!after.isAvailable()) {
                return new Replacement(
                        "NATIVE_RETURNED_UNOBSERVED",
                        nativeResult.postCurrentGuardPassed(),
                        true,
                        false,
                        false,
                        false,
                        Optional.empty(),
                        "The native replacement returned but the relation projection is unavailable.");
            }
            final Optional<RawImageId> observed =
                    observedRawImage(after, modelImagesUsing(before, target), incomingRaw);
            return new Replacement(
                    "NATIVE_RETURNED",
                    nativeResult.postCurrentGuardPassed(),
                    true,
                    false,
                    false,
                    true,
                    observed,
                    "The native replacement returned and current state was re-read; application evidence "
                            + "requires an observed incoming binding on a previously targeted model image.",
                    observed.isPresent()
                            ? Optional.empty()
                            : Optional.of(replacementObservationFailure(before, after, target, incomingRaw)));
        }

        private EditorTextureReplacementDiagnostic.Session replacementDiagnostic(
                final TextureRelationsSnapshot before,
                final Object document,
                final RawImageId target,
                final Object incoming) {
            try {
                if (!EditorTextureReplacementDiagnostic.enabled()) return null;
                final EditorTextureReplacementDiagnostic.RawIdentity incomingRaw =
                        EditorTextureReplacementDiagnostic.resolveIncomingRaw(resolver, incoming);
                return EditorTextureReplacementDiagnostic.begin(
                                resolver, identity, source, document, model, target, incomingRaw, before)
                        .orElse(null);
            } catch (RuntimeException | LinkageError diagnosticFailure) {
                // Diagnostic setup must not move, duplicate, classify, or mask the native call.
                return null;
            }
        }

        private void finishDiagnosticAfterAttempt(
                final EditorTextureReplacementDiagnostic.Session diagnostic,
                final EditorRawImagePsdReplaceAccess.ReplaceResult nativeResult) {
            if (diagnostic == null) return;
            if (!nativeResult.postCurrentGuardPassed()) {
                finishDiagnostic(diagnostic, null, nativeResult, "post-current-guard-failed");
                return;
            }
            try {
                final TextureRelationsSnapshot after = relationAccess.relations(identity, source, model);
                finishDiagnostic(diagnostic, after, nativeResult, null);
            } catch (RuntimeException | LinkageError rereadFailure) {
                finishDiagnostic(diagnostic, null, nativeResult, "post-relation-read-failed:" + message(rereadFailure));
            }
        }

        private void finishDiagnostic(
                final EditorTextureReplacementDiagnostic.Session diagnostic,
                final TextureRelationsSnapshot after,
                final EditorRawImagePsdReplaceAccess.ReplaceResult nativeResult,
                final String cause) {
            if (diagnostic != null) diagnostic.finish(after, nativeResult, cause);
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
            return EditorTextureAccess.this.supportsAppendTextureAtlasPages(identity, model);
        }

        @Override
        public List<TextureAtlasId> appendTextureAtlasPages(final AtlasPageAppendPlan plan) {
            return EditorHostThread.dispatch("Cubism texture write", () -> appendTextureAtlasPagesOnEdt(plan));
        }

        private List<TextureAtlasId> appendTextureAtlasPagesOnEdt(final AtlasPageAppendPlan plan) {
            Objects.requireNonNull(plan, "plan");
            if (!supportsAppendTextureAtlasPages()) {
                throw new UnsupportedOperationException(
                        "Append texture atlas pages is unavailable without verified layout write capability.");
            }
            modelGuard.requireCurrent(identity, model);

            final Object manager = textureManager(source);
            final List<?> oldAtlasesRaw = list(TEXTURE_ATLASES, manager, "texture atlas");
            final List<Object> oldAtlases = new ArrayList<>(oldAtlasesRaw);
            final int oldPageCount = oldAtlases.size();
            final int totalPageCount = oldPageCount + plan.pageCount();
            if (totalPageCount > AtlasPageAppendPlan.MAX_PAGE_COUNT) {
                throw new IllegalArgumentException("Total atlas page count (" + totalPageCount
                        + ") exceeds maximum allowed (" + AtlasPageAppendPlan.MAX_PAGE_COUNT + ")");
            }

            long totalPixels = 0L;
            for (final Object oldAtlas : oldAtlases) {
                final int w = dimension(oldAtlas, "cubism.editor-model.texture-atlas.width", "texture atlas");
                final int h = dimension(oldAtlas, "cubism.editor-model.texture-atlas.height", "texture atlas");
                totalPixels += (long) w * (long) h;
            }
            totalPixels += (long) plan.pageWidth() * (long) plan.pageHeight() * (long) plan.pageCount();
            if (totalPixels > AtlasPageAppendPlan.MAX_TOTAL_PIXELS) {
                throw new IllegalArgumentException("Total atlas pixel budget exceeded: " + totalPixels + " > "
                        + AtlasPageAppendPlan.MAX_TOTAL_PIXELS);
            }

            final List<String> oldGuids = new ArrayList<>(oldPageCount);
            for (final Object oldAtlas : oldAtlases) {
                final Object guidObj = resolver.invoke("cubism.editor-model.texture-atlas.guid", oldAtlas);
                oldGuids.add(guidValue(guidObj, "texture atlas"));
            }

            final java.util.HashSet<String> existingPlacedImageGuids = new java.util.HashSet<>();
            for (final Object oldAtlas : oldAtlases) {
                final Object entriesObj = resolver.invoke(ATLAS_ENTRIES, oldAtlas);
                if (entriesObj instanceof List<?> entries) {
                    for (final Object entry : entries) {
                        if (resolver.isInstance(ENTRY_CLASS, entry)) {
                            final Object image = resolver.invoke(ENTRY_IMAGE, entry);
                            if (image != null) {
                                final Object guid = resolver.invoke(IMAGE_GUID, image);
                                final String val = guidValue(guid, "model image");
                                existingPlacedImageGuids.add(val);
                            }
                        }
                    }
                }
            }

            final Map<ModelImageId, Object> modelImages = new LinkedHashMap<>();
            final Map<ModelImageId, Object> modelImageGuids = new LinkedHashMap<>();
            for (final ModelImagePlacement placement : plan.placements()) {
                final ModelImageId id = placement.modelImageId();
                if (existingPlacedImageGuids.contains(id.value())) {
                    throw new IllegalArgumentException(
                            "ModelImage is already placed on an existing atlas page: " + id.value());
                }
                if (!modelImages.containsKey(id)) {
                    final Object modelImage = findModelImage(source, id);
                    final Object guid = findModelImageGuid(source, id);
                    final int imgW = dimension(modelImage, "cubism.editor-model.model-image.width", "model image");
                    final int imgH = dimension(modelImage, "cubism.editor-model.model-image.height", "model image");
                    if (placement.width() != imgW || placement.height() != imgH) {
                        throw new IllegalArgumentException(
                                "Placement dimensions (" + placement.width() + "x" + placement.height()
                                        + ") must match ModelImage dimensions (" + imgW + "x" + imgH
                                        + ") for image " + id.value());
                    }
                    modelImages.put(id, modelImage);
                    modelImageGuids.put(id, guid);
                }
            }

            // Capture before-state of all art mesh texture inputs to verify applied and restored bindings
            final Map<ArtMeshId, Optional<ArtMeshTextureInput>> beforeMeshInputs = new LinkedHashMap<>();
            for (final Object meshSource : list(ALL_ART_MESHES, source, "art mesh")) {
                if (resolver.isInstance(ART_MESH_SOURCE_CLASS, meshSource)) {
                    final Object idObj = resolver.invoke(SOURCE_ID, meshSource);
                    final Object text = resolver.invoke(ID_VALUE, idObj);
                    if (text instanceof String meshId && !meshId.isBlank()) {
                        final ArtMeshId targetId = new ArtMeshId(meshId);
                        beforeMeshInputs.put(targetId, observeTextureInput(identity, source, model, targetId));
                    }
                }
            }

            final List<Object> newAtlases = new ArrayList<>(oldPageCount + plan.pageCount());
            newAtlases.addAll(oldAtlases);

            for (int i = 0; i < plan.pageCount(); i++) {
                final int pageIndex = oldPageCount + i;
                final String pageName = (plan.pageNames() != null
                                && i < plan.pageNames().size()
                                && !plan.pageNames().get(i).isBlank())
                        ? plan.pageNames().get(i).strip()
                        : "Atlas Page " + (pageIndex + 1);
                final Object newAtlas =
                        resolver.construct(ATLAS_CREATE, source, pageName, plan.pageWidth(), plan.pageHeight());
                newAtlases.add(newAtlas);
            }

            for (final ModelImagePlacement placement : plan.placements()) {
                final Object targetAtlas = newAtlases.get(oldPageCount + placement.pageIndex());
                final Object guid = modelImageGuids.get(placement.modelImageId());
                final Object affine = resolver.construct(AFFINE_CREATE);
                resolver.invoke(AFFINE_TRANSLATE, affine, (float) placement.x(), (float) placement.y());
                final Object entry = resolver.construct(ENTRY_CREATE, targetAtlas, guid, affine);
                append(resolver.invoke(ATLAS_ENTRIES, targetAtlas), entry);
            }

            final List<TextureAtlasId> createdPageIds = new ArrayList<>(plan.pageCount());
            for (int i = 0; i < plan.pageCount(); i++) {
                final Object newAtlas = newAtlases.get(oldPageCount + i);
                final Object guid = resolver.invoke("cubism.editor-model.texture-atlas.guid", newAtlas);
                createdPageIds.add(new TextureAtlasId(guidValue(guid, "texture atlas")));
            }

            final Object undo = resolver.construct(
                    ATLAS_UNDO_CREATE, "Turboism: Append Texture Atlas Pages", source, oldAtlases, newAtlases);

            final Object app = resolver.invokeStatic(APP_INSTANCE);
            final Object document = resolver.invoke(CURRENT_DOCUMENT, app);
            final Object completePack = app == null ? null : resolver.invoke(COMPLETE_PACK, app);

            final var ambientJoin = HostUndoMutationScope.ambient(authoringCoordinator, resolver);
            if (ambientJoin.isPresent()) {
                ambientJoin
                        .orElseThrow()
                        .admit(
                                "cubism.texture.write",
                                "texture:Turboism: Append Texture Atlas Pages",
                                "Turboism: Append Texture Atlas Pages",
                                (edit, transactionLabel) -> {
                                    applyUndoAndRegisterListener(
                                            edit, undo, source, manager, completePack, oldPageCount);
                                },
                                () -> {
                                    relinkTextureInputs(source, manager, true);
                                    refreshAtlas(source, completePack);
                                },
                                () -> verifyAtlasApplied(
                                        manager,
                                        oldAtlases,
                                        oldGuids,
                                        newAtlases,
                                        plan.pageCount(),
                                        source,
                                        beforeMeshInputs,
                                        plan.placements(),
                                        createdPageIds),
                                () -> {
                                    rollbackAppend(undo, source, manager, completePack, oldPageCount);
                                },
                                () -> verifyAtlasRestored(manager, oldAtlases, oldGuids, source, beforeMeshInputs),
                                EnumSet.of(
                                        EditorRefreshRequirement.MODEL_INSTANCES,
                                        EditorRefreshRequirement.PART_PALETTE,
                                        EditorRefreshRequirement.CANVAS,
                                        EditorRefreshRequirement.MARK_DIRTY));
                return createdPageIds;
            }

            EditorAmbientTransactionGuard.requireNoAmbientTransaction(
                    authoringCoordinator, "Turboism: Append Texture Atlas Pages");
            final Object editMode = resolver.invoke(EDIT_MODE, document);
            final Object edit = resolver.invoke(BEGIN_EDIT, editMode, "Turboism: Append Texture Atlas Pages");
            boolean completed = false;
            try {
                resolver.invoke(ATLAS_UNDO_FORCE_REDO, undo);
                registerAppliedUndo(edit, undo);

                final Object listener = createRelinkListener(source, manager, completePack, oldPageCount);
                final Object accepted = resolver.invoke(UNDO_ADD_LISTENER, undo, listener);
                if (!Boolean.TRUE.equals(accepted)) {
                    throw new IllegalStateException("Cubism rejected the texture-atlas Undo/Redo refresh listener.");
                }

                relinkTextureInputs(source, manager, true);
                refreshAtlas(source, completePack);
                resolver.invoke(MARK_DIRTY, document);
                if (!verifyAtlasApplied(
                        manager,
                        oldAtlases,
                        oldGuids,
                        newAtlases,
                        plan.pageCount(),
                        source,
                        beforeMeshInputs,
                        plan.placements(),
                        createdPageIds)) {
                    throw new IllegalStateException("Texture atlas append postcondition failed.");
                }
                completed = true;
            } catch (Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                try {
                    final List<?> current = list(TEXTURE_ATLASES, manager, "texture atlas");
                    final boolean atlasesAlreadyReverted = samePageCountAndInstances(current, oldAtlases);
                    if (!atlasesAlreadyReverted) {
                        resolver.invoke(UNDO_REVERT, undo);
                    }
                    relinkTextureInputs(source, manager, oldPageCount > 0);
                    refreshAtlas(source, completePack);
                    if (!verifyAtlasRestored(manager, oldAtlases, oldGuids, source, beforeMeshInputs)) {
                        throw new IllegalStateException("Texture atlas append rollback postcondition failed.");
                    }
                } catch (Throwable rollbackError) {
                    FatalErrors.rethrowIfFatal(rollbackError);
                    failure.addSuppressed(rollbackError);
                }
                throw failure;
            } finally {
                resolver.invoke(END_EDIT, editMode, Boolean.valueOf(!completed), null);
            }
            return createdPageIds;
        }

        private static boolean samePageCountAndInstances(final List<?> current, final List<?> expected) {
            if (current == null || expected == null || current.size() != expected.size()) {
                return false;
            }
            for (int i = 0; i < expected.size(); i++) {
                if (current.get(i) != expected.get(i)) return false;
            }
            return true;
        }

        private Object createRelinkListener(
                final Object source, final Object manager, final Object completePack, final int oldPageCount) {
            return resolver.createFunctionalProxy(UNDO_LISTENER_CLASS, ignored -> {
                final List<?> current = list(TEXTURE_ATLASES, manager, "texture atlas");
                final boolean isRedo = current.size() > oldPageCount;
                relinkTextureInputs(source, manager, isRedo);
                refreshAtlas(source, completePack);
                return null;
            });
        }

        private void applyUndoAndRegisterListener(
                final Object edit,
                final Object undo,
                final Object source,
                final Object manager,
                final Object completePack,
                final int oldPageCount) {
            resolver.invoke(ATLAS_UNDO_FORCE_REDO, undo);
            registerAppliedUndo(edit, undo);
            final Object listener = createRelinkListener(source, manager, completePack, oldPageCount);
            final Object listenerAccepted = resolver.invoke(UNDO_ADD_LISTENER, undo, listener);
            if (!(listenerAccepted instanceof Boolean accepted) || !accepted) {
                throw new IllegalStateException("Cubism rejected the texture-atlas Undo/Redo refresh listener.");
            }
        }

        private void rollbackAppend(
                final Object undo,
                final Object source,
                final Object manager,
                final Object completePack,
                final int oldPageCount) {
            resolver.invoke(UNDO_REVERT, undo);
            relinkTextureInputs(source, manager, oldPageCount > 0);
            refreshAtlas(source, completePack);
        }

        private boolean verifyAtlasApplied(
                final Object manager,
                final List<Object> oldAtlases,
                final List<String> oldGuids,
                final List<Object> newAtlases,
                final int newPageCount,
                final Object source,
                final Map<ArtMeshId, Optional<ArtMeshTextureInput>> beforeMeshInputs,
                final List<ModelImagePlacement> placements,
                final List<TextureAtlasId> createdPageIds) {
            final List<?> current = list(TEXTURE_ATLASES, manager, "texture atlas");
            if (current.size() != oldAtlases.size() + newPageCount) {
                return false;
            }
            for (int i = 0; i < oldAtlases.size(); i++) {
                if (current.get(i) != oldAtlases.get(i)) return false;
                final Object guid = resolver.invoke("cubism.editor-model.texture-atlas.guid", current.get(i));
                if (!oldGuids.get(i).equals(guidValue(guid, "texture atlas"))) return false;
            }
            for (int i = 0; i < newPageCount; i++) {
                if (current.get(oldAtlases.size() + i) != newAtlases.get(oldAtlases.size() + i)) {
                    return false;
                }
            }
            final Map<ModelImageId, TextureAtlasId> expectedAtlasByImage = new LinkedHashMap<>();
            for (final ModelImagePlacement p : placements) {
                expectedAtlasByImage.put(p.modelImageId(), createdPageIds.get(p.pageIndex()));
            }
            for (final Map.Entry<ArtMeshId, Optional<ArtMeshTextureInput>> entry : beforeMeshInputs.entrySet()) {
                final ArtMeshId meshId = entry.getKey();
                final Optional<ArtMeshTextureInput> before = entry.getValue();
                final Optional<ArtMeshTextureInput> now;
                try {
                    now = observeTextureInput(identity, source, model, meshId);
                } catch (NoSuchElementException deleted) {
                    return false;
                }
                if (before.isPresent() && before.get().kind() == ArtMeshTextureInput.Kind.MODEL_IMAGE) {
                    final ModelImageId imgId = before.get().modelImageId().orElse(null);
                    if (imgId != null && expectedAtlasByImage.containsKey(imgId)) {
                        final TextureAtlasId expectedAtlasId = expectedAtlasByImage.get(imgId);
                        if (now.isEmpty()
                                || now.get().kind() != ArtMeshTextureInput.Kind.TEXTURE_ATLAS_REGION
                                || !expectedAtlasId.equals(
                                        now.get().textureAtlasId().orElse(null))) {
                            return false;
                        }
                        continue;
                    }
                }
                if (!sameSemanticInput(before, now)) {
                    return false;
                }
            }
            return true;
        }

        private boolean verifyAtlasRestored(
                final Object manager,
                final List<Object> oldAtlases,
                final List<String> oldGuids,
                final Object source,
                final Map<ArtMeshId, Optional<ArtMeshTextureInput>> beforeMeshInputs) {
            final List<?> current = list(TEXTURE_ATLASES, manager, "texture atlas");
            if (current.size() != oldAtlases.size()) {
                return false;
            }
            for (int i = 0; i < oldAtlases.size(); i++) {
                if (current.get(i) != oldAtlases.get(i)) return false;
                final Object guid = resolver.invoke("cubism.editor-model.texture-atlas.guid", current.get(i));
                if (!oldGuids.get(i).equals(guidValue(guid, "texture atlas"))) return false;
            }
            for (final Map.Entry<ArtMeshId, Optional<ArtMeshTextureInput>> entry : beforeMeshInputs.entrySet()) {
                final ArtMeshId meshId = entry.getKey();
                final Optional<ArtMeshTextureInput> expected = entry.getValue();
                final Optional<ArtMeshTextureInput> now;
                try {
                    now = observeTextureInput(identity, source, model, meshId);
                } catch (NoSuchElementException deleted) {
                    // Mesh was deleted during root transaction rollback; this is expected and valid
                    continue;
                }
                if (!sameSemanticInput(expected, now)) {
                    return false;
                }
            }
            return true;
        }

        private static boolean sameSemanticInput(
                final Optional<ArtMeshTextureInput> a, final Optional<ArtMeshTextureInput> b) {
            if (a.isEmpty() && b.isEmpty()) return true;
            if (a.isEmpty() || b.isEmpty()) return false;
            final ArtMeshTextureInput inputA = a.get();
            final ArtMeshTextureInput inputB = b.get();
            return inputA.kind() == inputB.kind()
                    && Objects.equals(inputA.modelImageId(), inputB.modelImageId())
                    && Objects.equals(inputA.textureAtlasId(), inputB.textureAtlasId());
        }

        private void relinkTextureInputs(
                final Object modelSource, final Object textureManager, final boolean selectAtlas) {
            final Object helper = resolver.readStaticField(RELINK_HELPER);
            resolver.invoke(RELINK_REBUILD, helper, modelSource);
            resolver.invokeStatic(MODEL_SOURCE_VERIFY, modelSource, Boolean.TRUE, null, 2, null);
            resolver.invoke(!selectAtlas ? REINIT_INPUT : CHANGE_INPUT_TO_ATLAS, textureManager);
        }

        private void refreshAtlas(final Object modelSource, final Object completePack) {
            resolver.invoke(UPDATE_INSTANCES, modelSource);
            if (completePack != null) {
                resolver.invoke(REPAINT_CANVAS, completePack, Boolean.TRUE);
            }
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
            requireRegisterBindAuthorization();
            Objects.requireNonNull(image, "image");
            Objects.requireNonNull(target, "target");
            modelGuard.requireCurrent(identity, model);
            return EditorHostThread.dispatch("Model image registration and binding", () -> {
                image.claim();
                final long generation = generationOf(identity);
                final VerifiedEditorAuthoringTransactionHost host = selfHost(identity, source, model, generation);
                final EditorAuthoringTransactionCoordinator active = authoringCoordinator != null
                        ? authoringCoordinator
                        : new EditorAuthoringTransactionCoordinator(host);
                final EditorAuthoringTransactionCoordinator.Binding binding = bindingSupplier != null
                        ? bindingSupplier.get()
                        : host.binding(REGISTER_PLUGIN_ID)
                                .orElseThrow(
                                        () -> new IllegalStateException("Editor authoring binding is unavailable."));
                final Object meshSource = resolveArtMeshSource(source, target);
                final Object existing = requireBindableTarget(meshSource);
                if (existing != null) {
                    throw new IllegalStateException("Cubism art mesh already carries a texture-input extension.");
                }
                return participateOrExecute(active, binding, "Turboism: Register and Bind Model Image", () -> {
                    final RegistrationMembers registration = buildRegistration(source, image);
                    active.mutate(
                            binding,
                            registrationContribution(
                                    binding, CubismOperation.REGISTER_MODEL_IMAGE.id(), source, registration));
                    final BindingMembers bound = buildBinding(meshSource, registration.modelImage, target);
                    active.mutate(
                            binding,
                            bindingContribution(
                                    binding,
                                    CubismOperation.BIND_MODEL_IMAGE.id(),
                                    meshSource,
                                    registration.modelImage,
                                    bound));
                    return handle(binding, registration.rawImageGuidValue, registration.modelImageGuidValue, target);
                });
            });
        }

        @Override
        public Optional<ArtMeshTextureInput> textureInput(final ArtMeshId target) {
            requireTextureInputReadAuthorization();
            Objects.requireNonNull(target, "target");
            modelGuard.requireCurrent(identity, model);
            return EditorHostThread.dispatch(
                    "ArtMesh texture input observation", () -> observeTextureInput(identity, source, model, target));
        }

        @Override
        public ModelImageBinding bindModelImage(final ModelImageId image, final ArtMeshId target) {
            requireRegisterBindAuthorization();
            Objects.requireNonNull(image, "image");
            Objects.requireNonNull(target, "target");
            modelGuard.requireCurrent(identity, model);
            return EditorHostThread.dispatch("Model image binding", () -> {
                final long generation = generationOf(identity);
                final VerifiedEditorAuthoringTransactionHost host = selfHost(identity, source, model, generation);
                final EditorAuthoringTransactionCoordinator active = authoringCoordinator != null
                        ? authoringCoordinator
                        : new EditorAuthoringTransactionCoordinator(host);
                final EditorAuthoringTransactionCoordinator.Binding binding = bindingSupplier != null
                        ? bindingSupplier.get()
                        : host.binding(REGISTER_PLUGIN_ID)
                                .orElseThrow(
                                        () -> new IllegalStateException("Editor authoring binding is unavailable."));
                final Object meshSource = resolveArtMeshSource(source, target);
                final Object modelImage = findModelImage(source, image);
                final Object existing = requireBindableTarget(meshSource);
                if (existing != null) {
                    final Object boundImage = boundModelImage(existing);
                    if (boundImage == modelImage) {
                        return handle(binding, linkedRawImageId(modelImage), image.value(), target);
                    }
                    throw new IllegalStateException("Cubism art mesh is already bound to a different model image.");
                }
                return participateOrExecute(active, binding, "Turboism: Bind Model Image", () -> {
                    final BindingMembers bound = buildBinding(meshSource, modelImage, target);
                    active.mutate(
                            binding,
                            bindingContribution(
                                    binding, CubismOperation.BIND_MODEL_IMAGE.id(), meshSource, modelImage, bound));
                    return handle(binding, linkedRawImageId(modelImage), image.value(), target);
                });
            });
        }

        private ModelImageBinding participateOrExecute(
                final EditorAuthoringTransactionCoordinator active,
                final EditorAuthoringTransactionCoordinator.Binding binding,
                final String label,
                final Supplier<ModelImageBinding> work) {
            // mutate validates the binding on every contribution. Exceptions propagate to
            // the owning root; only that root can commit or restore the complete import.
            if (active.hasActiveTransaction()) return work.get();
            return requireCommitted(active.execute(binding, AuthoringTransactionOptions.of(label), work::get), label);
        }

        /** Builds the unpublished extension/input pair and captures the two bound fields. */
        private BindingMembers buildBinding(final Object meshSource, final Object modelImage, final ArtMeshId target) {
            final Object extension = resolver.construct(EXTENSION_CREATE, meshSource);
            final Object input = resolver.construct(INPUT_CREATE, extension, modelImage);
            resolver.invoke(EXTENSION_ADD_INPUT, extension, input);
            resolver.invoke(EXTENSION_SET_CURRENT, extension, input);
            return new BindingMembers(
                    target.value(),
                    extension,
                    input,
                    modelImage,
                    resolver.invoke(MESH_TEXTURE, meshSource),
                    resolver.invoke(MESH_TEXTURE_STATE, meshSource),
                    resolver.readStaticField(TEXTURE_STATE_MODEL_IMAGE));
        }

        private ModelImageBinding handle(
                final EditorAuthoringTransactionCoordinator.Binding binding,
                final String rawImageGuidValue,
                final String modelImageGuidValue,
                final ArtMeshId target) {
            return new ModelImageBinding(
                    binding.pluginId(),
                    new ModelId(modelIdOf(identity)),
                    binding.modelGeneration(),
                    new RawImageId(rawImageGuidValue),
                    new ModelImageId(modelImageGuidValue),
                    Optional.of(target));
        }

        private ModelImageBinding requireCommitted(
                final AuthoringTransactionResult<ModelImageBinding> result, final String operation) {
            if (!result.successful()) {
                throw new IllegalStateException("Cubism " + operation + " transaction failed: " + result.outcome()
                        + result.diagnosticId().map(id -> " [" + id + "]").orElse(""));
            }
            return result.value().orElseThrow();
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
