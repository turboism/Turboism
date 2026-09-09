package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorTextureRelationsSelectorContract;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ModelImageId;
import dev.turboism.sdk.cubism.id.RawImageId;
import dev.turboism.sdk.cubism.id.RawLayerId;
import dev.turboism.sdk.cubism.id.TextureAtlasId;
import dev.turboism.sdk.cubism.model.ArtMeshTextureInputs;
import dev.turboism.sdk.cubism.model.ModelImageEntry;
import dev.turboism.sdk.cubism.model.ModelImageGroup;
import dev.turboism.sdk.cubism.model.ModelImageGroupRelation;
import dev.turboism.sdk.cubism.model.ModelImageRelation;
import dev.turboism.sdk.cubism.model.RawImageDetails;
import dev.turboism.sdk.cubism.model.RawLayerBinding;
import dev.turboism.sdk.cubism.model.RawLayerDetails;
import dev.turboism.sdk.cubism.model.RawTexture;
import dev.turboism.sdk.cubism.model.TextureInputBinding;
import dev.turboism.sdk.cubism.model.TextureRelationsSnapshot;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Read-only projection of the verified 5.3.02 raw/model-image/ArtMesh relation graph.
 *
 * <p>One call is synchronously marshalled to {@link EditorHostThread}; the before/after model
 * guard runs inside that same host-thread boundary. The observation revision is not a native
 * model revision and cannot detect an in-place mutation that the host does not serialize.</p>
 *
 * <p>The projection deliberately keeps host objects inside this package. SDK values contain only
 * copied scalars, SDK identifiers, and immutable collections. A missing relation capability is a
 * typed unavailable result; it is never represented as an available empty graph.</p>
 */
final class EditorTextureRelationsAccess {
    private static final String TEXTURE_MANAGER = "cubism.editor-model.model-source.texture-manager";
    private static final String RAW_IMAGES = "cubism.editor-model.texture-manager.raw-images";
    private static final String MODEL_IMAGE_GROUPS = "cubism.editor-model.texture-manager.model-image-groups";
    private static final String ALL_MODEL_IMAGES = "cubism.editor-model.texture-manager.all-model-images";
    private static final String TEXTURE_ATLASES = "cubism.editor-model.texture-manager.texture-atlases";
    private static final String ART_MESH_USABLE_GROUPS =
        "cubism.editor-model.texture-manager.art-mesh-usable-model-image-groups";
    private static final String WRAPPER_IMAGE = "cubism.editor-model.layered-image-wrapper.image";
    private static final String WRAPPER_IMPORT_TIME = "cubism.editor-model.layered-image-wrapper.import-time";
    private static final String WRAPPER_MODIFIED_TIME = "cubism.editor-model.layered-image-wrapper.modified-time";
    private static final String WRAPPER_REPLACED = "cubism.editor-model.layered-image-wrapper.replaced";
    private static final String LAYERED_IMAGE_CLASS = "cubism.editor-model.layered-image.class";
    private static final String LAYERED_IMAGE_GUID = "cubism.editor-model.layered-image.guid";
    private static final String LAYERED_IMAGE_NAME = "cubism.editor-model.layered-image.name";
    private static final String LAYERED_IMAGE_WIDTH = "cubism.editor-model.layered-image.width";
    private static final String LAYERED_IMAGE_HEIGHT = "cubism.editor-model.layered-image.height";
    private static final String LAYERED_IMAGE_PSD_DOC = "cubism.editor-model.layered-image.psd-doc";
    private static final String LAYERED_IMAGE_CHILDREN = "cubism.editor-model.layered-image.children";
    private static final String LAYER_ENTRY_CLASS = "cubism.editor-model.layer-entry.class";
    private static final String LAYER_ENTRY_GUID = "cubism.editor-model.layer-entry.guid";
    private static final String LAYER_ENTRY_NAME = "cubism.editor-model.layer-entry.name";
    private static final String LAYER_GROUP_CLASS = "cubism.editor-model.layer-group.class";
    private static final String LAYER_GROUP_CHILDREN = "cubism.editor-model.layer-group.children";
    private static final String MODEL_IMAGE_CLASS = "cubism.editor-model.model-image.class";
    private static final String MODEL_IMAGE_GUID = "cubism.editor-model.model-image.guid";
    private static final String MODEL_IMAGE_NAME = "cubism.editor-model.model-image.name";
    private static final String MODEL_IMAGE_WIDTH = "cubism.editor-model.model-image.width";
    private static final String MODEL_IMAGE_HEIGHT = "cubism.editor-model.model-image.height";
    private static final String MODEL_IMAGE_LINKED_RAW =
        "cubism.editor-model.model-image.linked-raw-image-guids";
    private static final String MODEL_IMAGE_INPUT_FILTER_ENV =
        "cubism.editor-model.model-image.input-filter-env";
    private static final String FILTER_ENV_CLASS = "cubism.editor-model.model-image-filter-env.class";
    private static final String FILTER_ENV_HAS_LAYER_INPUT =
        "cubism.editor-model.model-image-filter-env.has-layer-input-data";
    private static final String FILTER_ENV_LAYER_INPUT =
        "cubism.editor-model.model-image-filter-env.layer-input-data";
    private static final String FILTER_ENV_HAS_CURRENT =
        "cubism.editor-model.model-image-filter-env.has-current-image-guid";
    private static final String FILTER_ENV_CURRENT =
        "cubism.editor-model.model-image-filter-env.current-image-guid";
    private static final String SELECTOR_MAP_CLASS = "cubism.editor-model.layer-selector-map.class";
    private static final String SELECTOR_MAP_IMAGE_INPUTS =
        "cubism.editor-model.layer-selector-map.image-to-layer-input";
    private static final String LAYER_INPUT_CLASS = "cubism.editor-model.layer-input-data.class";
    private static final String LAYER_INPUT_LAYER = "cubism.editor-model.layer-input-data.layer";
    private static final String LAYER_INPUT_AFFINE = "cubism.editor-model.layer-input-data.affine";
    private static final String LAYER_INPUT_CLIPPING =
        "cubism.editor-model.layer-input-data.clipping-on-texture-px";
    private static final String GROUP_CLASS = "cubism.editor-model.model-image-group.class";
    private static final String GROUP_NAME = "cubism.editor-model.model-image-group.group-name";
    private static final String GROUP_MEMO = "cubism.editor-model.model-image-group.memo";
    private static final String GROUP_IMAGES = "cubism.editor-model.model-image-group.model-images";
    private static final String GROUP_LINKED_RAW =
        "cubism.editor-model.model-image-group.linked-raw-image-guids";
    private static final String ATLAS_CLASS = "cubism.editor-model.texture-atlas.class";
    private static final String ATLAS_GUID = "cubism.editor-model.texture-atlas.guid";
    private static final String EXTENSION_CLASS = "cubism.editor-model.texture-input-extension.class";
    private static final String EXTENSION_INPUTS =
        "cubism.editor-model.texture-input-extension.texture-inputs";
    private static final String EXTENSION_CURRENT =
        "cubism.editor-model.texture-input-extension.current-texture-input-data";
    private static final String MODEL_INPUT_CLASS = "cubism.editor-model.texture-input-model-image.class";
    private static final String MODEL_INPUT_GUID =
        "cubism.editor-model.texture-input-model-image.model-image-guid";
    private static final String ATLAS_INPUT_CLASS =
        "cubism.editor-model.texture-input-texture-atlas-region.class";
    private static final String ATLAS_INPUT_GUID =
        "cubism.editor-model.texture-input-texture-atlas-region.texture-atlas-guid";
    private static final String ALL_ART_MESH_SOURCES = "cubism.editor-model.model-source.all-art-meshes";
    private static final String ALL_ART_MESHES = "cubism.editor-model.model.all-art-meshes";
    private static final String ART_MESH_SOURCE_CLASS = "cubism.editor-model.art-mesh-source.class";
    private static final String ART_MESH_CLASS = "cubism.editor-model.art-mesh.class";
    private static final String ART_MESH_SOURCE = "cubism.editor-model.art-mesh.source";
    private static final String OBJECT_ID = "cubism.editor-model.parameter-controllable-source.id";
    private static final String OBJECT_ID_VALUE = "cubism.editor-model.id.value";
    private static final String GUID_VALUE = "cubism.editor-model.guid.value";

    private final VerifiedMemberResolver resolver;
    private final EditorParameterCombinedAccess.ModelGuard modelGuard;
    private final LongSupplier generationSupplier;
    /** Local observation sequence; it is not a native Cubism revision. */
    private final AtomicLong revision = new AtomicLong();

    EditorTextureRelationsAccess(
        final VerifiedMemberResolver resolver,
        final EditorParameterCombinedAccess.ModelGuard modelGuard,
        final LongSupplier generationSupplier
    ) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.modelGuard = Objects.requireNonNull(modelGuard, "modelGuard");
        this.generationSupplier = Objects.requireNonNull(generationSupplier, "generationSupplier");
    }

    TextureRelationsSnapshot relations(
        final String identity,
        final Object source,
        final Object model
    ) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(model, "model");
        if (!isAvailable()) {
            return TextureRelationsSnapshot.unavailable();
        }
        return EditorHostThread.dispatch(
            "Cubism texture relations",
            () -> relationsOnHostThread(identity, source, model)
        );
    }

    /** All native relation selectors run in one synchronous host-thread read boundary. */
    private TextureRelationsSnapshot relationsOnHostThread(
        final String identity,
        final Object source,
        final Object model
    ) {
        modelGuard.requireCurrent(identity, model);

        final Object textureManager = requireObject(
            resolver.invoke(TEXTURE_MANAGER, source),
            "Editor texture manager"
        );
        final RawRead rawRead = readRawImages(textureManager);
        final ModelImageRead modelImageRead = readModelImages(textureManager);
        final Set<String> atlasIds = readAtlasIds(textureManager);
        final List<ModelImageGroupRelation> groups = readGroups(textureManager, modelImageRead);
        final List<ArtMeshRead> meshes = readArtMeshes(source, model);
        final Map<ModelImageId, LinkedHashSet<ArtMeshId>> users = new LinkedHashMap<>();
        final List<ArtMeshTextureInputs> artMeshInputs = new ArrayList<>(meshes.size());
        for (final ArtMeshRead mesh : meshes) {
            final ArtMeshTextureInputs inputs = readArtMeshInputs(mesh, modelImageRead, atlasIds, users);
            artMeshInputs.add(inputs);
        }

        final List<ModelImageRelation> modelImages = new ArrayList<>(modelImageRead.values.size());
        for (final ModelImageData image : modelImageRead.values) {
            final LinkedHashSet<ArtMeshId> using = users.getOrDefault(image.id, new LinkedHashSet<>());
            modelImages.add(readModelImageRelation(image, using));
        }

        // The same guard closes the boundary: a replacement with the same binding/model ID
        // must not publish a projection assembled from an older native object graph.
        modelGuard.requireCurrent(identity, model);
        final long generation = generationSupplier.getAsLong();
        final long observedRevision = revision.incrementAndGet();
        return new TextureRelationsSnapshot(
            TextureRelationsSnapshot.Availability.AVAILABLE,
            identity,
            generation,
            observedRevision,
            rawRead.values,
            modelImages,
            groups,
            artMeshInputs
        );
    }

    private boolean isAvailable() {
        return resolver.isExactCubismVersion(EditorTextureRelationsSelectorContract.SUPPORTED_CUBISM_VERSION)
            && resolver.authorizesFeature(
                EditorTextureRelationsSelectorContract.ADAPTER_SLICE_ID,
                EditorTextureRelationsSelectorContract.CAPABILITY_ID,
                EditorTextureRelationsSelectorContract.REQUIRED_ALIASES
            );
    }

    private RawRead readRawImages(final Object textureManager) {
        final List<RawImageDetails> values = new ArrayList<>();
        final Map<RawImageId, RawImageDetails> byId = new LinkedHashMap<>();
        for (final Object wrapper : list(resolver.invoke(RAW_IMAGES, textureManager), "raw image")) {
            final Object image = requireObject(resolver.invoke(WRAPPER_IMAGE, wrapper), "raw layered image");
            requireInstance(LAYERED_IMAGE_CLASS, image, "raw layered image");
            final RawImageId id = new RawImageId(guidValue(
                resolver.invoke(LAYERED_IMAGE_GUID, image),
                "raw image"
            ));
            final RawTexture rawTexture = immutableRawTexture(id, image);
            final List<RawLayerDetails> layers = readLayers(
                resolver.invoke(LAYERED_IMAGE_CHILDREN, image),
                id,
                new HashSet<>()
            );
            // CLayeredImage.psdFile is shared by the ordinary CImageResource constructor and the
            // CPsdDocument constructor. Only a non-null, typed getPsdDoc result is positive PSD
            // evidence; a reopened document with a missing psdDoc remains UNKNOWN.
            final Object psdDocument = resolver.invoke(LAYERED_IMAGE_PSD_DOC, image);
            final RawImageDetails.SourceKind sourceKind = psdDocument != null
                ? RawImageDetails.SourceKind.PSD
                : RawImageDetails.SourceKind.UNKNOWN;
            final RawImageDetails details = new RawImageDetails(
                rawTexture,
                sourceKind,
                layers,
                booleanValue(resolver.invoke(WRAPPER_REPLACED, wrapper), "raw image replaced"),
                optionalString(resolver.invoke(WRAPPER_IMPORT_TIME, wrapper), "raw image import time"),
                optionalString(resolver.invoke(WRAPPER_MODIFIED_TIME, wrapper), "raw image modified time"),
                Optional.empty()
            );
            if (byId.put(id, details) != null) {
                throw unavailable("Editor raw image identifiers are not unique.");
            }
            values.add(details);
        }
        return new RawRead(List.copyOf(values), Map.copyOf(byId));
    }

    private RawTexture immutableRawTexture(final RawImageId id, final Object image) {
        final String name = stringValue(
            resolver.invoke(LAYERED_IMAGE_NAME, image),
            "raw image name"
        );
        final int width = dimension(resolver.invoke(LAYERED_IMAGE_WIDTH, image), "raw image width");
        final int height = dimension(resolver.invoke(LAYERED_IMAGE_HEIGHT, image), "raw image height");
        return new RawTexture() {
            @Override public RawImageId id() { return id; }
            @Override public String name() { return name; }
            @Override public int width() { return width; }
            @Override public int height() { return height; }
        };
    }

    private List<RawLayerDetails> readLayers(
        final Object rawEntries,
        final RawImageId ownerRawImageId,
        final Set<String> seenIds
    ) {
        final List<RawLayerDetails> values = new ArrayList<>();
        for (final Object entry : list(rawEntries, "raw layer")) {
            requireInstance(LAYER_ENTRY_CLASS, entry, "raw layer entry");
            final String idValue = guidValue(resolver.invoke(LAYER_ENTRY_GUID, entry), "raw layer");
            if (!seenIds.add(idValue)) {
                throw unavailable("Editor raw layer identifiers are not unique within a raw image.");
            }
            final RawLayerDetails.EntryKind kind;
            final List<RawLayerDetails> children;
            if (resolver.isInstance(LAYER_GROUP_CLASS, entry)) {
                kind = RawLayerDetails.EntryKind.GROUP;
                children = readLayers(
                    resolver.invoke(LAYER_GROUP_CHILDREN, entry),
                    ownerRawImageId,
                    seenIds
                );
            } else {
                kind = RawLayerDetails.EntryKind.PIXEL;
                children = List.of();
            }
            values.add(new RawLayerDetails(
                new RawLayerId(idValue),
                ownerRawImageId,
                kind,
                stringValue(resolver.invoke(LAYER_ENTRY_NAME, entry), "raw layer name"),
                Optional.empty(),
                children
            ));
        }
        return List.copyOf(values);
    }

    private ModelImageRead readModelImages(final Object textureManager) {
        final List<ModelImageData> values = new ArrayList<>();
        final Map<ModelImageId, ModelImageData> byId = new LinkedHashMap<>();
        final IdentityHashMap<Object, ModelImageData> byHost = new IdentityHashMap<>();
        for (final Object image : list(resolver.invoke(ALL_MODEL_IMAGES, textureManager), "model image")) {
            requireInstance(MODEL_IMAGE_CLASS, image, "model image");
            final ModelImageId id = new ModelImageId(
                guidValue(resolver.invoke(MODEL_IMAGE_GUID, image), "model image")
            );
            final ModelImageEntry entry = immutableModelImageEntry(id, image);
            final ModelImageData data = new ModelImageData(image, id, entry);
            if (byId.put(id, data) != null || byHost.put(image, data) != null) {
                throw unavailable("Editor model image identifiers are not unique.");
            }
            values.add(data);
        }
        return new ModelImageRead(List.copyOf(values), Map.copyOf(byId), byHost);
    }

    private ModelImageEntry immutableModelImageEntry(final ModelImageId id, final Object image) {
        final String name = stringValue(
            resolver.invoke(MODEL_IMAGE_NAME, image),
            "model image name"
        );
        final int width = dimension(resolver.invoke(MODEL_IMAGE_WIDTH, image), "model image width");
        final int height = dimension(resolver.invoke(MODEL_IMAGE_HEIGHT, image), "model image height");
        return new ModelImageEntry() {
            @Override public ModelImageId id() { return id; }
            @Override public String name() { return name; }
            @Override public int width() { return width; }
            @Override public int height() { return height; }
        };
    }

    private Set<String> readAtlasIds(final Object textureManager) {
        final Set<String> ids = new LinkedHashSet<>();
        for (final Object atlas : list(resolver.invoke(TEXTURE_ATLASES, textureManager), "texture atlas")) {
            requireInstance(ATLAS_CLASS, atlas, "texture atlas");
            final String id = guidValue(resolver.invoke(ATLAS_GUID, atlas), "texture atlas");
            if (!ids.add(id)) {
                throw unavailable("Editor texture atlas identifiers are not unique.");
            }
        }
        return Set.copyOf(ids);
    }

    private List<ModelImageGroupRelation> readGroups(
        final Object textureManager,
        final ModelImageRead modelImages
    ) {
        final Set<Object> usableGroups = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (final Object group : list(
            resolver.invoke(ART_MESH_USABLE_GROUPS, textureManager),
            "art-mesh-usable model image group"
        )) {
            requireInstance(GROUP_CLASS, group, "art-mesh-usable model image group");
            usableGroups.add(group);
        }

        final List<ModelImageGroupRelation> values = new ArrayList<>();
        for (final Object group : list(
            resolver.invoke(MODEL_IMAGE_GROUPS, textureManager),
            "model image group"
        )) {
            requireInstance(GROUP_CLASS, group, "model image group");
            final String groupName = stringValue(resolver.invoke(GROUP_NAME, group), "model image group name");
            final Object rawMemo = resolver.invoke(GROUP_MEMO, group);
            final String memo = rawMemo == null ? "" : stringValue(rawMemo, "model image group memo");
            final List<ModelImageEntry> entries = new ArrayList<>();
            final List<ModelImageId> modelImageIds = new ArrayList<>();
            for (final Object image : list(
                resolver.invoke(GROUP_IMAGES, group),
                "model image group images"
            )) {
                final ModelImageData data = modelImageData(image, modelImages);
                entries.add(data.entry);
                modelImageIds.add(data.id);
            }
            final ModelImageGroup groupValue = new ModelImageGroup() {
                @Override public String groupName() { return groupName; }
                @Override public String memo() { return memo; }
                @Override public List<ModelImageEntry> modelImages() { return List.copyOf(entries); }
            };
            values.add(new ModelImageGroupRelation(
                groupValue,
                modelImageIds,
                rawImageIds(resolver.invoke(GROUP_LINKED_RAW, group), "model image group linked raw images"),
                Optional.of(usableGroups.contains(group))
            ));
        }
        return List.copyOf(values);
    }

    private List<ArtMeshRead> readArtMeshes(final Object source, final Object model) {
        final List<?> sources = list(
            resolver.invoke(ALL_ART_MESH_SOURCES, source),
            "ArtMesh source"
        );
        final List<?> instances = list(
            resolver.invoke(ALL_ART_MESHES, model),
            "ArtMesh instance"
        );
        final IdentityHashMap<Object, Object> instanceBySource = new IdentityHashMap<>();
        for (final Object instance : instances) {
            requireInstance(ART_MESH_CLASS, instance, "ArtMesh instance");
            final Object objectSource = resolver.invoke(ART_MESH_SOURCE, instance);
            requireInstance(ART_MESH_SOURCE_CLASS, objectSource, "ArtMesh source");
            if (!containsIdentity(sources, objectSource) || instanceBySource.put(objectSource, instance) != null) {
                throw unavailable("Editor ArtMesh source/instance binding is invalid.");
            }
        }
        final List<ArtMeshRead> values = new ArrayList<>(sources.size());
        final Set<String> ids = new HashSet<>();
        for (final Object objectSource : sources) {
            requireInstance(ART_MESH_SOURCE_CLASS, objectSource, "ArtMesh source");
            final Object instance = instanceBySource.remove(objectSource);
            if (instance == null) {
                throw unavailable("Editor ArtMesh source has no active instance.");
            }
            final String id = stringValue(
                resolver.invoke(OBJECT_ID_VALUE, resolver.invoke(OBJECT_ID, objectSource)), "ArtMesh ID");
            if (!ids.add(id)) {
                throw unavailable("Editor ArtMesh identifiers are not unique.");
            }
            values.add(new ArtMeshRead(objectSource, new ArtMeshId(id)));
        }
        if (!instanceBySource.isEmpty()) {
            throw unavailable("Editor ArtMesh instance has no source.");
        }
        return List.copyOf(values);
    }

    private ArtMeshTextureInputs readArtMeshInputs(
        final ArtMeshRead mesh,
        final ModelImageRead modelImages,
        final Set<String> atlasIds,
        final Map<ModelImageId, LinkedHashSet<ArtMeshId>> users
    ) {
        final Object extension = resolver.invoke(
            "cubism.editor-model.art-mesh-source.texture-input-extension",
            mesh.source
        );
        if (extension == null) {
            return new ArtMeshTextureInputs(mesh.id, List.of(), OptionalInt.empty());
        }
        requireInstance(EXTENSION_CLASS, extension, "ArtMesh texture-input extension");
        final List<?> inputs = list(resolver.invoke(EXTENSION_INPUTS, extension), "ArtMesh texture input");
        final List<TextureInputBinding> values = new ArrayList<>(inputs.size());
        final IdentityHashMap<Object, Integer> indices = new IdentityHashMap<>();
        for (int index = 0; index < inputs.size(); index++) {
            final Object input = inputs.get(index);
            indices.put(input, index);
            final TextureInputBinding binding = textureInput(input, modelImages, atlasIds);
            values.add(binding);
            binding.modelImageId().ifPresent(id -> {
                if (modelImages.byId.containsKey(id)) {
                    users.computeIfAbsent(id, ignored -> new LinkedHashSet<>()).add(mesh.id);
                }
            });
        }
        final Object current = resolver.invoke(EXTENSION_CURRENT, extension);
        final OptionalInt currentIndex;
        if (current == null) {
            currentIndex = OptionalInt.empty();
        } else {
            final Integer index = indices.get(current);
            if (index == null) {
                throw unavailable("ArtMesh current texture input is not in its input list.");
            }
            currentIndex = OptionalInt.of(index);
        }
        return new ArtMeshTextureInputs(mesh.id, values, currentIndex);
    }

    private TextureInputBinding textureInput(
        final Object input,
        final ModelImageRead modelImages,
        final Set<String> atlasIds
    ) {
        if (resolver.isInstance(MODEL_INPUT_CLASS, input)) {
            final Object guid = resolver.invoke(MODEL_INPUT_GUID, input);
            if (guid == null) {
                return TextureInputBinding.modelImage(null, TextureInputBinding.ResolutionState.UNAVAILABLE);
            }
            final ModelImageId id = new ModelImageId(guidValue(guid, "model image texture input"));
            return TextureInputBinding.modelImage(
                id,
                modelImages.byId.containsKey(id)
                    ? TextureInputBinding.ResolutionState.RESOLVED
                    : TextureInputBinding.ResolutionState.UNAVAILABLE
            );
        }
        if (resolver.isInstance(ATLAS_INPUT_CLASS, input)) {
            final Object guid = resolver.invoke(ATLAS_INPUT_GUID, input);
            if (guid == null) {
                return TextureInputBinding.atlas(null, TextureInputBinding.ResolutionState.UNAVAILABLE);
            }
            final TextureAtlasId id = new TextureAtlasId(guidValue(guid, "texture atlas texture input"));
            return TextureInputBinding.atlas(
                id,
                atlasIds.contains(id.value())
                    ? TextureInputBinding.ResolutionState.RESOLVED
                    : TextureInputBinding.ResolutionState.UNAVAILABLE
            );
        }
        return TextureInputBinding.unknown();
    }

    private ModelImageRelation readModelImageRelation(
        final ModelImageData image,
        final Set<ArtMeshId> usingArtMeshIds
    ) {
        final List<RawImageId> linkedRawImageIds = rawImageIds(
            resolver.invoke(MODEL_IMAGE_LINKED_RAW, image.host),
            "model image linked raw images"
        );
        final Object filterEnv = resolver.invoke(MODEL_IMAGE_INPUT_FILTER_ENV, image.host);
        final Optional<RawImageId> currentRawImageId;
        final Map<RawImageId, List<RawLayerBinding>> inputsByRawImage;
        if (filterEnv == null) {
            currentRawImageId = Optional.empty();
            inputsByRawImage = Map.of();
        } else {
            requireInstance(FILTER_ENV_CLASS, filterEnv, "model image filter environment");
            currentRawImageId = readCurrentRawImage(filterEnv);
            inputsByRawImage = readLayerInputs(filterEnv);
        }
        return new ModelImageRelation(
            image.id,
            image.entry,
            linkedRawImageIds,
            currentRawImageId,
            inputsByRawImage,
            List.copyOf(usingArtMeshIds)
        );
    }

    private Optional<RawImageId> readCurrentRawImage(final Object filterEnv) {
        final boolean hasCurrent = booleanValue(
            resolver.invoke(FILTER_ENV_HAS_CURRENT, filterEnv),
            "model image current raw-image flag"
        );
        if (!hasCurrent) {
            return Optional.empty();
        }
        final Object guid = resolver.invoke(FILTER_ENV_CURRENT, filterEnv);
        return guid == null
            ? Optional.empty()
            : Optional.of(new RawImageId(guidValue(guid, "model image current raw image")));
    }

    private Map<RawImageId, List<RawLayerBinding>> readLayerInputs(final Object filterEnv) {
        final boolean hasLayerInputData = booleanValue(
            resolver.invoke(FILTER_ENV_HAS_LAYER_INPUT, filterEnv),
            "model image layer-input flag"
        );
        if (!hasLayerInputData) {
            return Map.of();
        }
        final Object selectorMap = resolver.invoke(FILTER_ENV_LAYER_INPUT, filterEnv);
        requireInstance(SELECTOR_MAP_CLASS, selectorMap, "model image layer selector map");
        final Object rawMap = resolver.invoke(SELECTOR_MAP_IMAGE_INPUTS, selectorMap);
        if (!(rawMap instanceof Map<?, ?> map)) {
            throw unavailable("Model image layer selector map is not a map.");
        }
        final Map<RawImageId, List<RawLayerBinding>> values = new LinkedHashMap<>();
        for (final Map.Entry<?, ?> entry : map.entrySet()) {
            final RawImageId rawImageId = new RawImageId(guidValue(entry.getKey(), "layer selector raw image"));
            final List<RawLayerBinding> bindings = new ArrayList<>();
            final List<?> layerInputs = list(entry.getValue(), "model image layer inputs");
            for (int inputOrder = 0; inputOrder < layerInputs.size(); inputOrder++) {
                final Object layerInput = layerInputs.get(inputOrder);
                requireInstance(LAYER_INPUT_CLASS, layerInput, "model image layer input");
                final Object layer = resolver.invoke(LAYER_INPUT_LAYER, layerInput);
                requireInstance(LAYER_ENTRY_CLASS, layer, "model image layer entry");
                final RawLayerId rawLayerId = new RawLayerId(
                    guidValue(resolver.invoke(LAYER_ENTRY_GUID, layer), "model image raw layer")
                );
                bindings.add(new RawLayerBinding(
                    rawImageId,
                    rawLayerId,
                    inputOrder,
                    detailAvailability(resolver.invoke(LAYER_INPUT_AFFINE, layerInput)),
                    detailAvailability(resolver.invoke(LAYER_INPUT_CLIPPING, layerInput))
                ));
            }
            if (values.put(rawImageId, List.copyOf(bindings)) != null) {
                throw unavailable("Model image layer selector map repeats a raw image identifier.");
            }
        }
        return Map.copyOf(values);
    }

    private RawLayerBinding.DetailAvailability detailAvailability(final Object value) {
        return value == null
            ? RawLayerBinding.DetailAvailability.UNAVAILABLE
            : RawLayerBinding.DetailAvailability.AVAILABLE;
    }

    private ModelImageData modelImageData(final Object image, final ModelImageRead modelImages) {
        final ModelImageData byHost = modelImages.byHost.get(image);
        if (byHost != null) {
            return byHost;
        }
        requireInstance(MODEL_IMAGE_CLASS, image, "model image group member");
        final ModelImageId id = new ModelImageId(
            guidValue(resolver.invoke(MODEL_IMAGE_GUID, image), "model image group member")
        );
        final ModelImageData byId = modelImages.byId.get(id);
        if (byId == null) {
            throw unavailable("Model image group references an unenumerated model image.");
        }
        return byId;
    }

    private List<RawImageId> rawImageIds(final Object value, final String label) {
        final List<RawImageId> ids = new ArrayList<>();
        for (final Object guid : list(value, label)) {
            ids.add(new RawImageId(guidValue(guid, label)));
        }
        return List.copyOf(ids);
    }

    private void requireInstance(final String alias, final Object value, final String label) {
        if (!resolver.isInstance(alias, value)) {
            throw unavailable("" + label + " has an invalid verified host type.");
        }
    }

    private static Object requireObject(final Object value, final String label) {
        if (value == null) {
            throw unavailable(label + " is unavailable.");
        }
        return value;
    }

    private String guidValue(final Object guid, final String label) {
        final Object value = requireObject(guid, label + " GUID");
        final Object raw = resolver.invoke(GUID_VALUE, value);
        if (!(raw instanceof String text) || text.isBlank()) {
            throw unavailable(label + " GUID is invalid.");
        }
        return text;
    }

    private static String stringValue(final Object value, final String label) {
        if (!(value instanceof String text)) {
            throw unavailable(label + " is invalid.");
        }
        return text;
    }

    private static Optional<String> optionalString(final Object value, final String label) {
        return value == null ? Optional.empty() : Optional.of(stringValue(value, label));
    }

    private static int dimension(final Object value, final String label) {
        if (!(value instanceof Integer number) || number < 0) {
            throw unavailable(label + " is invalid.");
        }
        return number;
    }

    private static boolean booleanValue(final Object value, final String label) {
        if (value instanceof Boolean result) {
            return result;
        }
        throw unavailable(label + " is invalid.");
    }

    private static List<?> list(final Object value, final String label) {
        if (value == null || !(value instanceof Iterable<?> iterable)) {
            throw unavailable(label + " collection is unavailable.");
        }
        final List<Object> result = new ArrayList<>();
        iterable.forEach(result::add);
        return result;
    }

    private static boolean containsIdentity(final List<?> values, final Object expected) {
        for (final Object value : values) {
            if (value == expected) return true;
        }
        return false;
    }

    private static IllegalStateException unavailable(final String message) {
        return new IllegalStateException(message);
    }

    private record RawRead(
        List<RawImageDetails> values,
        Map<RawImageId, RawImageDetails> byId
    ) { }

    private record ModelImageRead(
        List<ModelImageData> values,
        Map<ModelImageId, ModelImageData> byId,
        IdentityHashMap<Object, ModelImageData> byHost
    ) { }

    private record ModelImageData(Object host, ModelImageId id, ModelImageEntry entry) { }

    private record ArtMeshRead(Object source, ArtMeshId id) { }
}
