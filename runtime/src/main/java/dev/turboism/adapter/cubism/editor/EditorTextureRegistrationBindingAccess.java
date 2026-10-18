package dev.turboism.adapter.cubism.editor;

import dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringTransactionCoordinator;
import dev.turboism.adapter.cubism.editor.transaction.EditorRefreshRequirement;
import dev.turboism.adapter.cubism.editor.transaction.EditorUndoContribution;
import dev.turboism.adapter.cubism.editor.transaction.VerifiedEditorAuthoringTransactionHost;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
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
import dev.turboism.sdk.cubism.model.AuthorizedImageContent;
import dev.turboism.sdk.cubism.model.ModelImageBinding;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOptions;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionResult;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Handles authorized image registration, model-image filter graph wiring,
 * ArtMesh texture-input binding, observation, and offline decode oracles.
 */
final class EditorTextureRegistrationBindingAccess {

    private static final String APP_INSTANCE = "cubism.editor-model.app-controller.instance";
    private static final String CURRENT_DOCUMENT = "cubism.editor-model.app-controller.current-document";
    private static final String MODELING_DOCUMENT_CLASS = "cubism.editor-model.modeling-document.class";
    private static final String DOCUMENT_MODEL_SOURCE = "cubism.editor-model.modeling-document.model-source";
    private static final String MODEL_SOURCE_INSTANCE = "cubism.editor-model.model-source.current-instance";

    private static final String TEXTURE_MANAGER = "cubism.editor-model.model-source.texture-manager";
    private static final String RAW_IMAGES = "cubism.editor-model.texture-manager.raw-images";
    private static final String MODEL_IMAGE_GROUPS = "cubism.editor-model.texture-manager.model-image-groups";
    private static final String ALL_MODEL_IMAGES = "cubism.editor-model.texture-manager.all-model-images";
    private static final String TEXTURE_ATLASES = "cubism.editor-model.texture-manager.texture-atlases";
    private static final String LAYERED_IMAGE_GUID = "cubism.editor-model.layered-image.guid";
    private static final String GUID_VALUE = "cubism.editor-model.guid.value";

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

    private static final String UNDO_ADD = "cubism.editor-model.undo.add";
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

    private static final String ATLAS_UNDO_FORCE_REDO = "cubism.texture-atlas.undo.force-redo";

    private static final String IMAGE_LOADED_COUNT = "cubism.editor-model.image-resource.loaded-count";
    private static final String IMAGE_LOADED_BYTES = "cubism.editor-model.image-resource.loaded-bytes";
    private static final String IMAGE_BYTE_DATA_BYTES = "cubism.editor-model.image-resource.byte-data-bytes";

    private static final String REGISTER_PLUGIN_ID = "runtime.editor-model";

    private final VerifiedMemberResolver resolver;
    private final EditorParameterCombinedAccess.ModelGuard modelGuard;
    private final EditorAuthoringTransactionCoordinator authoringCoordinator;
    private final Supplier<EditorAuthoringTransactionCoordinator.Binding> bindingSupplier;

    EditorTextureRegistrationBindingAccess(
            final VerifiedMemberResolver resolver,
            final EditorParameterCombinedAccess.ModelGuard modelGuard,
            final EditorAuthoringTransactionCoordinator authoringCoordinator,
            final Supplier<EditorAuthoringTransactionCoordinator.Binding> bindingSupplier) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.modelGuard = Objects.requireNonNull(modelGuard, "modelGuard");
        this.authoringCoordinator = authoringCoordinator;
        this.bindingSupplier = bindingSupplier;
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

    private void requireTextureInputReadAuthorization() {
        if (!resolver.authorizesFeature(
                EditorTextureSelectorContract.ADAPTER_SLICE_ID,
                EditorTextureSelectorContract.READ_CAPABILITY_ID,
                EditorTextureSelectorContract.TEXTURE_INPUT_OBSERVE_ALIASES)) {
            throw new UnsupportedOperationException(
                    "ArtMesh texture input observation is unavailable without exact verified host evidence.");
        }
    }

    ModelImageBinding registerImageAndBind(
            final String identity,
            final Object source,
            final Object model,
            final AuthorizedImageContent image,
            final ArtMeshId target) {
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
                            .orElseThrow(() -> new IllegalStateException("Editor authoring binding is unavailable."));
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
                return handle(
                        identity, binding, registration.rawImageGuidValue, registration.modelImageGuidValue, target);
            });
        });
    }

    ModelImageBinding bindModelImage(
            final String identity,
            final Object source,
            final Object model,
            final ModelImageId image,
            final ArtMeshId target) {
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
                            .orElseThrow(() -> new IllegalStateException("Editor authoring binding is unavailable."));
            final Object meshSource = resolveArtMeshSource(source, target);
            final Object modelImage = findModelImage(source, image);
            final Object existing = requireBindableTarget(meshSource);
            if (existing != null) {
                final Object boundImage = boundModelImage(existing);
                if (boundImage == modelImage) {
                    return handle(identity, binding, linkedRawImageId(modelImage), image.value(), target);
                }
                throw new IllegalStateException("Cubism art mesh is already bound to a different model image.");
            }
            return participateOrExecute(active, binding, "Turboism: Bind Model Image", () -> {
                final BindingMembers bound = buildBinding(meshSource, modelImage, target);
                active.mutate(
                        binding,
                        bindingContribution(
                                binding, CubismOperation.BIND_MODEL_IMAGE.id(), meshSource, modelImage, bound));
                return handle(identity, binding, linkedRawImageId(modelImage), image.value(), target);
            });
        });
    }

    Optional<ArtMeshTextureInput> textureInput(
            final String identity, final Object source, final Object model, final ArtMeshId target) {
        requireTextureInputReadAuthorization();
        Objects.requireNonNull(target, "target");
        modelGuard.requireCurrent(identity, model);
        return EditorHostThread.dispatch(
                "ArtMesh texture input observation", () -> observeTextureInput(identity, source, model, target));
    }

    /**
     * Read-only observation of the native texture input bound to one ArtMesh.
     * Runs entirely on the EDT; performs no edit, Undo, dirty, or event calls.
     * {@code Optional.empty()} means the mesh genuinely carries no extension or
     * no current input; every other absence of evidence fails closed.
     */
    Optional<ArtMeshTextureInput> observeTextureInput(
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

    private static final String GROUP_CREATE = "cubism.editor-model.model-image-group.create";

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

    private void claimCurrentImage(final Object modelImage, final Object layeredGuid) {
        final Object env = resolver.invoke(MODEL_IMAGE_FILTER_ENV, modelImage);
        resolver.invoke(ENV_SET_CURRENT_IMAGE_GUID, env, layeredGuid);
        if (!Boolean.TRUE.equals(resolver.invoke(ENV_HAS_CURRENT_IMAGE_GUID, env))
                || !guidValue(layeredGuid, "raw image")
                        .equals(guidValue(resolver.invoke(ENV_CURRENT_IMAGE_GUID, env), "raw image"))) {
            throw new IllegalStateException("Cubism model image did not retain its source image identity.");
        }
    }

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

    private String linkedRawImageId(final Object modelImage) {
        final Object linked = resolver.invoke(MODEL_IMAGE_LINKED_GUIDS, modelImage);
        if (!(linked instanceof List<?> guids) || guids.isEmpty()) {
            throw new IllegalStateException("Cubism model image is not linked to a raw image.");
        }
        return guidValue(guids.get(0), "raw image");
    }

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

    private Object boundModelImage(final Object extension) {
        final Object current = resolver.invoke(EXTENSION_CURRENT_INPUT, extension);
        if (current == null || !resolver.isInstance(INPUT_CLASS, current)) {
            return null;
        }
        return resolver.invoke(INPUT_MODEL_IMAGE, current);
    }

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

    private ModelImageBinding participateOrExecute(
            final EditorAuthoringTransactionCoordinator active,
            final EditorAuthoringTransactionCoordinator.Binding binding,
            final String label,
            final Supplier<ModelImageBinding> work) {
        if (active.hasActiveTransaction()) return work.get();
        return requireCommitted(active.execute(binding, AuthoringTransactionOptions.of(label), work::get), label);
    }

    private ModelImageBinding handle(
            final String identity,
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

    private Object textureManager(final Object source) {
        final Object manager = resolver.invoke(TEXTURE_MANAGER, source);
        if (manager == null) {
            throw new IllegalStateException("Editor texture manager is unavailable.");
        }
        return manager;
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

    private int dimension(final Object image, final String alias, final String label) {
        final Object raw = resolver.invoke(alias, image);
        if (!(raw instanceof Integer value) || value < 0) {
            throw new IllegalStateException("Editor " + label + " dimension is invalid.");
        }
        return value;
    }

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
}
