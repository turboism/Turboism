package dev.turboism.adapter.cubism.editor;

import static dev.turboism.adapter.cubism.editor.EditorTestFixtures.desc;
import static dev.turboism.adapter.cubism.editor.EditorTestFixtures.internal;

import dev.turboism.core.runtime.psd.PsdReplaceHost;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorRawImagePsdReplaceSelectorContract;
import dev.turboism.mapping.verification.selector.EditorRawImagePsdSelectorContract;
import dev.turboism.mapping.verification.selector.EditorTextureRelationsSelectorContract;
import dev.turboism.mapping.verification.selector.EditorTextureSelectorContract;
import dev.turboism.sdk.cubism.id.RawImageId;
import dev.turboism.sdk.cubism.model.ModelTextures;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared mock/fixture engine for texture relations, PSD hierarchies, and replacement diagnostics.
 */
public final class EditorTextureRelationsTestFixtures {

    private EditorTextureRelationsTestFixtures() {}

    public static final class HostId {
        private final String value;

        public HostId(final String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    public static final class ModelSource {
        public final TextureManager textureManager;
        public final List<ArtMeshSource> allArtMeshes;
        final AtomicInteger textureManagerCalls = new AtomicInteger();
        final AtomicReference<Boolean> textureManagerReadOnHostThread = new AtomicReference<>();
        final AtomicReference<Runnable> afterTextureManagerRead = new AtomicReference<>();

        public ModelSource(final TextureManager textureManager, final List<ArtMeshSource> allArtMeshes) {
            this.textureManager = textureManager;
            this.allArtMeshes = allArtMeshes;
        }

        public TextureManager textureManager() {
            textureManagerCalls.incrementAndGet();
            textureManagerReadOnHostThread.set(EditorHostThread.isCurrent());
            final Runnable hook = afterTextureManagerRead.getAndSet(null);
            if (hook != null) hook.run();
            return textureManager;
        }

        public List<ArtMeshSource> allArtMeshes() {
            return allArtMeshes;
        }
    }

    public static final class Model {
        private final String id;
        private final List<ArtMesh> allArtMeshes;

        public Model(final List<ArtMesh> allArtMeshes) {
            this("model-a", allArtMeshes);
        }

        public Model(final String id, final List<ArtMesh> allArtMeshes) {
            this.id = id;
            this.allArtMeshes = allArtMeshes;
        }

        public String id() {
            return id;
        }

        public List<ArtMesh> allArtMeshes() {
            return allArtMeshes;
        }
    }

    public static final class TextureManager {
        public final List<Wrapper> rawImages;
        public final List<HostModelImageGroup> modelImageGroups;
        public final List<ModelImage> allModelImages;
        public final List<TextureAtlas> textureAtlases;
        public final List<HostModelImageGroup> artMeshUsableModelImageGroups;

        public TextureManager(
                final List<Wrapper> rawImages,
                final List<HostModelImageGroup> modelImageGroups,
                final List<ModelImage> allModelImages,
                final List<TextureAtlas> textureAtlases,
                final List<HostModelImageGroup> artMeshUsableModelImageGroups) {
            this.rawImages = rawImages;
            this.modelImageGroups = modelImageGroups;
            this.allModelImages = allModelImages;
            this.textureAtlases = textureAtlases;
            this.artMeshUsableModelImageGroups = artMeshUsableModelImageGroups;
        }

        public List<Wrapper> rawImages() {
            return rawImages;
        }

        public List<HostModelImageGroup> modelImageGroups() {
            return modelImageGroups;
        }

        public List<ModelImage> allModelImages() {
            return allModelImages;
        }

        public List<TextureAtlas> textureAtlases() {
            return textureAtlases;
        }

        public List<HostModelImageGroup> artMeshUsableModelImageGroups() {
            return artMeshUsableModelImageGroups;
        }
    }

    public static final class Wrapper {
        private final LayeredImage image;
        private final String importTime;
        private final String modifiedTime;
        private final boolean replaced;

        public Wrapper(
                final LayeredImage image, final String importTime, final String modifiedTime, final boolean replaced) {
            this.image = image;
            this.importTime = importTime;
            this.modifiedTime = modifiedTime;
            this.replaced = replaced;
        }

        public LayeredImage image() {
            return image;
        }

        public String getImportTime() {
            return importTime;
        }

        public String getModifiedTime() {
            return modifiedTime;
        }

        public boolean isReplaced() {
            return replaced;
        }
    }

    public static class LayerEntry {
        private final HostId guid;
        private final String name;

        public LayerEntry(final HostId guid, final String name) {
            this.guid = guid;
            this.name = name;
        }

        public HostId getGuid() {
            return guid;
        }

        public String getName() {
            return name;
        }
    }

    public static final class LayerGroup extends LayerEntry {
        private final List<LayerEntry> children;

        public LayerGroup(final HostId guid, final String name, final List<LayerEntry> children) {
            super(guid, name);
            this.children = children;
        }

        public List<LayerEntry> getChildren() {
            return children;
        }
    }

    public static final class PsdDocument {}

    public static final class LayeredImage {
        private final HostId guid;
        private final String name;
        private final int width;
        private final int height;
        private final File psdFile;
        private final PsdDocument psdDoc;
        private final List<LayerEntry> children;
        public final AtomicInteger psdFileReads = new AtomicInteger();
        public final AtomicInteger childrenReads = new AtomicInteger();

        public LayeredImage(
                final HostId guid,
                final String name,
                final int width,
                final int height,
                final File psdFile,
                final List<LayerEntry> children) {
            this(guid, name, width, height, psdFile, null, children);
        }

        public LayeredImage(
                final HostId guid,
                final String name,
                final int width,
                final int height,
                final File psdFile,
                final PsdDocument psdDoc,
                final List<LayerEntry> children) {
            this.guid = guid;
            this.name = name;
            this.width = width;
            this.height = height;
            this.psdFile = psdFile;
            this.psdDoc = psdDoc;
            this.children = children;
        }

        public LayeredImage(final String guid) {
            this(new HostId(guid), "LayeredImage", 1024, 512, new File("textures/source.psd"), null, new ArrayList<>());
        }

        public HostId guid() {
            return guid;
        }

        public File psdFile() {
            return psdFile;
        }

        public List<LayerEntry> children() {
            return children;
        }

        public HostId getGuid() {
            return guid;
        }

        public String getName() {
            return name;
        }

        public int getWidth() {
            return width;
        }

        public int getHeight() {
            return height;
        }

        public File getPsdFile() {
            psdFileReads.incrementAndGet();
            return psdFile;
        }

        public PsdDocument getPsdDoc() {
            return psdDoc;
        }

        int psdFileReads() {
            return psdFileReads.get();
        }

        public List<LayerEntry> getChildren() {
            childrenReads.incrementAndGet();
            return children;
        }
    }

    public static final class ModelImage {
        private final HostId guid;
        private final String name;
        private final int width;
        private final int height;
        private final List<HostId> linkedRawImageGuids;
        public final FilterEnv inputFilterEnv;
        public final AtomicInteger environmentReads = new AtomicInteger();

        public ModelImage(
                final HostId guid,
                final String name,
                final int width,
                final int height,
                final List<HostId> linkedRawImageGuids,
                final FilterEnv inputFilterEnv) {
            this.guid = guid;
            this.name = name;
            this.width = width;
            this.height = height;
            this.linkedRawImageGuids = linkedRawImageGuids;
            this.inputFilterEnv = inputFilterEnv;
        }

        public HostId getGuid() {
            return guid;
        }

        public String getName() {
            return name;
        }

        public int getWidth() {
            return width;
        }

        public int getHeight() {
            return height;
        }

        public List<HostId> getLinkedRawImageGuids() {
            return linkedRawImageGuids;
        }

        public FilterEnv getInputFilterEnv() {
            environmentReads.incrementAndGet();
            return inputFilterEnv;
        }
    }

    public static final class FilterEnv {
        private final boolean hasLayerInputData;
        private final SelectorMap layerInputData;
        public final AtomicInteger layerReads = new AtomicInteger();
        private final boolean hasCurrentImageGuid;
        private final HostId currentImageGuid;

        public FilterEnv(
                final boolean hasLayerInputData,
                final SelectorMap layerInputData,
                final boolean hasCurrentImageGuid,
                final HostId currentImageGuid) {
            this.hasLayerInputData = hasLayerInputData;
            this.layerInputData = layerInputData;
            this.hasCurrentImageGuid = hasCurrentImageGuid;
            this.currentImageGuid = currentImageGuid;
        }

        public boolean getHasLayerInputData() {
            layerReads.incrementAndGet();
            return hasLayerInputData;
        }

        public SelectorMap getLayerInputData() {
            layerReads.incrementAndGet();
            return layerInputData;
        }

        public boolean getHasCurrentImageGuid() {
            return hasCurrentImageGuid;
        }

        public HostId getCurrentImageGuid() {
            return currentImageGuid;
        }
    }

    public static final class SelectorMap {
        private final Map<HostId, List<LayerInput>> imageToLayerInput;

        public SelectorMap(final Map<HostId, List<LayerInput>> imageToLayerInput) {
            this.imageToLayerInput = imageToLayerInput;
        }

        public Map<HostId, List<LayerInput>> getImageToLayerInput() {
            return imageToLayerInput;
        }
    }

    public static final class LayerInput {
        private final LayerEntry layer;
        private final Object affine;
        private final Object clipping;

        public LayerInput(final LayerEntry layer, final Object affine, final Object clipping) {
            this.layer = layer;
            this.affine = affine;
            this.clipping = clipping;
        }

        public LayerEntry getLayer() {
            return layer;
        }

        public Object getAffine() {
            return affine;
        }

        public Object getClippingOnTexturePx() {
            return clipping;
        }
    }

    public static final class HostModelImageGroup {
        private final String groupName;
        private final String memo;
        private final List<ModelImage> modelImages;
        private final List<HostId> linkedRawImageGuids;

        public HostModelImageGroup(
                final String groupName,
                final String memo,
                final List<ModelImage> modelImages,
                final List<HostId> linkedRawImageGuids) {
            this.groupName = groupName;
            this.memo = memo;
            this.modelImages = modelImages;
            this.linkedRawImageGuids = linkedRawImageGuids;
        }

        public String getGroupName() {
            return groupName;
        }

        public String getMemo() {
            return memo;
        }

        public List<ModelImage> getModelImages() {
            return modelImages;
        }

        public List<HostId> getLinkedRawImageGuids() {
            return linkedRawImageGuids;
        }
    }

    public static final class TextureAtlas {
        private final HostId guid;

        public TextureAtlas(final HostId guid) {
            this.guid = guid;
        }

        public HostId getGuid() {
            return guid;
        }
    }

    // Native CObjectID and GUID are unrelated types; using one fixture class hid a host failure.
    public record SourceId(String value) {
        public String getIdString() {
            return value;
        }
    }

    public static final class ArtMeshSource {
        private final SourceId id;
        private final Object textureInputExtension;

        public ArtMeshSource(final HostId id, final Object textureInputExtension) {
            this.id = new SourceId(id.value());
            this.textureInputExtension = textureInputExtension;
        }

        public SourceId getId() {
            return id;
        }

        public Object getTextureInputExtension() {
            return textureInputExtension;
        }
    }

    public static final class ArtMesh {
        private final ArtMeshSource source;

        public ArtMesh(final ArtMeshSource source) {
            this.source = source;
        }

        public ArtMeshSource getSource() {
            return source;
        }
    }

    public static final class TextureInputExtension {
        private final List<Object> textureInputs;
        private final Object currentTextureInputData;

        public TextureInputExtension(final List<Object> textureInputs, final Object currentTextureInputData) {
            this.textureInputs = textureInputs;
            this.currentTextureInputData = currentTextureInputData;
        }

        public List<Object> getTextureInputs() {
            return textureInputs;
        }

        public Object getCurrentTextureInputData() {
            return currentTextureInputData;
        }
    }

    public static final class ModelInput {
        private final HostId modelImageGuid;

        public ModelInput(final HostId modelImageGuid) {
            this.modelImageGuid = modelImageGuid;
        }

        public HostId getModelImageGuid() {
            return modelImageGuid;
        }
    }

    public static final class AtlasInput {
        private final HostId textureAtlasGuid;

        public AtlasInput(final HostId textureAtlasGuid) {
            this.textureAtlasGuid = textureAtlasGuid;
        }

        public HostId getTextureAtlasGuid() {
            return textureAtlasGuid;
        }
    }

    public static final class UnknownInput {}

    static NativeFixture nativeFixture() {
        return nativeFixture(true);
    }

    static NativeFixture largeNativeFixture() {
        final EditorTextureRelationsTestFixtures.HostId oldId = new EditorTextureRelationsTestFixtures.HostId("old");
        final EditorTextureRelationsTestFixtures.HostId incomingId =
                new EditorTextureRelationsTestFixtures.HostId("incoming");
        final EditorTextureRelationsTestFixtures.LayeredImage old = new EditorTextureRelationsTestFixtures.LayeredImage(
                oldId,
                "Old",
                100,
                100,
                new File("/old.psd"),
                new EditorTextureRelationsTestFixtures.PsdDocument(),
                List.of());
        final EditorTextureRelationsTestFixtures.LayeredImage incoming =
                new EditorTextureRelationsTestFixtures.LayeredImage(
                        incomingId,
                        "Incoming",
                        100,
                        100,
                        new File("/incoming.psd"),
                        new EditorTextureRelationsTestFixtures.PsdDocument(),
                        List.of());
        final List<EditorTextureRelationsTestFixtures.ModelImage> modelImages = new ArrayList<>();
        final List<EditorTextureRelationsTestFixtures.HostModelImageGroup> groups = new ArrayList<>();
        for (int imageIndex = 0; imageIndex < 33; imageIndex++) {
            final boolean target = imageIndex == 0;
            final List<EditorTextureRelationsTestFixtures.HostId> linked =
                    target ? List.of(oldId) : longIds("linked-" + imageIndex + '-', 32);
            final Map<EditorTextureRelationsTestFixtures.HostId, List<EditorTextureRelationsTestFixtures.LayerInput>>
                    selectorKeys = new LinkedHashMap<>();
            for (int keyIndex = 0; keyIndex < 32; keyIndex++) {
                selectorKeys.put(
                        new EditorTextureRelationsTestFixtures.HostId(
                                longValue("selector-" + imageIndex + '-', keyIndex)),
                        List.of());
            }
            final EditorTextureRelationsTestFixtures.ModelImage image =
                    new EditorTextureRelationsTestFixtures.ModelImage(
                            new EditorTextureRelationsTestFixtures.HostId(
                                    target ? "target-model" : "filler-model-" + imageIndex),
                            target ? "Target" : "Filler",
                            100,
                            100,
                            linked,
                            new EditorTextureRelationsTestFixtures.FilterEnv(
                                    true,
                                    new EditorTextureRelationsTestFixtures.SelectorMap(selectorKeys),
                                    true,
                                    target ? oldId : linked.get(0)));
            modelImages.add(image);
            groups.add(new EditorTextureRelationsTestFixtures.HostModelImageGroup(
                    target ? "Target group" : "Filler group " + imageIndex,
                    "memo",
                    List.of(image),
                    target ? List.of(oldId, incomingId) : linked));
        }
        final EditorTextureRelationsTestFixtures.TextureManager manager =
                new EditorTextureRelationsTestFixtures.TextureManager(
                        List.of(
                                new EditorTextureRelationsTestFixtures.Wrapper(old, "import", "modified", true),
                                new EditorTextureRelationsTestFixtures.Wrapper(incoming, null, null, true)),
                        groups,
                        modelImages,
                        List.of(),
                        groups);
        final EditorTextureRelationsTestFixtures.ModelSource source =
                new EditorTextureRelationsTestFixtures.ModelSource(manager, List.of());
        final EditorTextureRelationsTestFixtures.Model model = new EditorTextureRelationsTestFixtures.Model(List.of());
        return new NativeFixture(source, model, resolver(true));
    }

    static List<EditorTextureRelationsTestFixtures.HostId> longIds(final String prefix, final int count) {
        final List<EditorTextureRelationsTestFixtures.HostId> values = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            values.add(new EditorTextureRelationsTestFixtures.HostId(longValue(prefix, index)));
        }
        return values;
    }

    static String longValue(final String prefix, final int index) {
        return prefix + index + '-' + "x".repeat(112);
    }

    static NativeFixture nativeFixture(final boolean emptySelectorMap) {
        final EditorTextureRelationsTestFixtures.HostId oldId = new EditorTextureRelationsTestFixtures.HostId("old");
        final EditorTextureRelationsTestFixtures.HostId incomingId =
                new EditorTextureRelationsTestFixtures.HostId("incoming");
        final EditorTextureRelationsTestFixtures.LayeredImage old = new EditorTextureRelationsTestFixtures.LayeredImage(
                oldId,
                "Old",
                100,
                100,
                new File("/old.psd"),
                new EditorTextureRelationsTestFixtures.PsdDocument(),
                List.of());
        final EditorTextureRelationsTestFixtures.LayeredImage incoming =
                new EditorTextureRelationsTestFixtures.LayeredImage(
                        incomingId,
                        "Incoming",
                        100,
                        100,
                        new File("/incoming.psd"),
                        new EditorTextureRelationsTestFixtures.PsdDocument(),
                        List.of());
        final EditorTextureRelationsTestFixtures.ModelImage modelImage =
                new EditorTextureRelationsTestFixtures.ModelImage(
                        new EditorTextureRelationsTestFixtures.HostId("model-a"),
                        "Image",
                        100,
                        100,
                        List.of(oldId),
                        new EditorTextureRelationsTestFixtures.FilterEnv(
                                true,
                                new EditorTextureRelationsTestFixtures.SelectorMap(
                                        emptySelectorMap ? Map.of() : Map.of(oldId, List.of())),
                                true,
                                oldId));
        final EditorTextureRelationsTestFixtures.HostModelImageGroup group =
                new EditorTextureRelationsTestFixtures.HostModelImageGroup(
                        "Group A", "memo", List.of(modelImage), List.of(oldId, incomingId));
        final EditorTextureRelationsTestFixtures.TextureManager manager =
                new EditorTextureRelationsTestFixtures.TextureManager(
                        List.of(
                                new EditorTextureRelationsTestFixtures.Wrapper(old, "import", "modified", true),
                                new EditorTextureRelationsTestFixtures.Wrapper(incoming, null, null, true)),
                        List.of(group),
                        List.of(modelImage),
                        List.of(),
                        List.of(group));
        final EditorTextureRelationsTestFixtures.ModelSource source =
                new EditorTextureRelationsTestFixtures.ModelSource(manager, List.of());
        final EditorTextureRelationsTestFixtures.Model model = new EditorTextureRelationsTestFixtures.Model(List.of());
        return new NativeFixture(source, model, resolver(true));
    }

    static VerifiedMemberResolver resolver(final boolean authorized) {
        final Map<String, StaticSelector> selectors = new LinkedHashMap<>();
        for (final String alias : EditorTextureRelationsSelectorContract.REQUIRED_ALIASES) {
            selectors.put(alias, StaticSelector.classSelector(alias, internal(Object.class)));
        }
        putMethod(
                selectors,
                "cubism.editor-model.model-source.texture-manager",
                EditorTextureRelationsTestFixtures.ModelSource.class,
                "textureManager",
                desc(EditorTextureRelationsTestFixtures.TextureManager.class));
        putMethod(
                selectors,
                "cubism.editor-model.texture-manager.raw-images",
                EditorTextureRelationsTestFixtures.TextureManager.class,
                "rawImages",
                "()Ljava/util/List;");
        putMethod(
                selectors,
                "cubism.editor-model.texture-manager.all-model-images",
                EditorTextureRelationsTestFixtures.TextureManager.class,
                "allModelImages",
                "()Ljava/util/List;");
        putMethod(
                selectors,
                "cubism.editor-model.texture-manager.model-image-groups",
                EditorTextureRelationsTestFixtures.TextureManager.class,
                "modelImageGroups",
                "()Ljava/util/List;");
        putMethod(
                selectors,
                "cubism.editor-model.layered-image-wrapper.image",
                EditorTextureRelationsTestFixtures.Wrapper.class,
                "image",
                desc(EditorTextureRelationsTestFixtures.LayeredImage.class));
        putMethod(
                selectors,
                "cubism.editor-model.layered-image-wrapper.replaced",
                EditorTextureRelationsTestFixtures.Wrapper.class,
                "isReplaced",
                "()Z");
        putClass(
                selectors,
                "cubism.editor-model.layered-image.class",
                EditorTextureRelationsTestFixtures.LayeredImage.class);
        putMethod(
                selectors,
                "cubism.editor-model.layered-image.guid",
                EditorTextureRelationsTestFixtures.LayeredImage.class,
                "getGuid",
                desc(EditorTextureRelationsTestFixtures.HostId.class));
        putClass(
                selectors,
                "cubism.editor-model.model-image.class",
                EditorTextureRelationsTestFixtures.ModelImage.class);
        putMethod(
                selectors,
                "cubism.editor-model.model-image.guid",
                EditorTextureRelationsTestFixtures.ModelImage.class,
                "getGuid",
                desc(EditorTextureRelationsTestFixtures.HostId.class));
        putMethod(
                selectors,
                "cubism.editor-model.model-image.linked-raw-image-guids",
                EditorTextureRelationsTestFixtures.ModelImage.class,
                "getLinkedRawImageGuids",
                "()Ljava/util/List;");
        putMethod(
                selectors,
                "cubism.editor-model.model-image.input-filter-env",
                EditorTextureRelationsTestFixtures.ModelImage.class,
                "getInputFilterEnv",
                desc(EditorTextureRelationsTestFixtures.FilterEnv.class));
        putClass(
                selectors,
                "cubism.editor-model.model-image-filter-env.class",
                EditorTextureRelationsTestFixtures.FilterEnv.class);
        putMethod(
                selectors,
                "cubism.editor-model.model-image-filter-env.has-layer-input-data",
                EditorTextureRelationsTestFixtures.FilterEnv.class,
                "getHasLayerInputData",
                "()Z");
        putMethod(
                selectors,
                "cubism.editor-model.model-image-filter-env.layer-input-data",
                EditorTextureRelationsTestFixtures.FilterEnv.class,
                "getLayerInputData",
                desc(EditorTextureRelationsTestFixtures.SelectorMap.class));
        putMethod(
                selectors,
                "cubism.editor-model.model-image-filter-env.has-current-image-guid",
                EditorTextureRelationsTestFixtures.FilterEnv.class,
                "getHasCurrentImageGuid",
                "()Z");
        putMethod(
                selectors,
                "cubism.editor-model.model-image-filter-env.current-image-guid",
                EditorTextureRelationsTestFixtures.FilterEnv.class,
                "getCurrentImageGuid",
                desc(EditorTextureRelationsTestFixtures.HostId.class));
        putClass(
                selectors,
                "cubism.editor-model.layer-selector-map.class",
                EditorTextureRelationsTestFixtures.SelectorMap.class);
        putMethod(
                selectors,
                "cubism.editor-model.layer-selector-map.image-to-layer-input",
                EditorTextureRelationsTestFixtures.SelectorMap.class,
                "getImageToLayerInput",
                "()Ljava/util/Map;");
        putClass(
                selectors,
                "cubism.editor-model.model-image-group.class",
                EditorTextureRelationsTestFixtures.HostModelImageGroup.class);
        putMethod(
                selectors,
                "cubism.editor-model.model-image-group.group-name",
                EditorTextureRelationsTestFixtures.HostModelImageGroup.class,
                "getGroupName",
                "()Ljava/lang/String;");
        putMethod(
                selectors,
                "cubism.editor-model.model-image-group.model-images",
                EditorTextureRelationsTestFixtures.HostModelImageGroup.class,
                "getModelImages",
                "()Ljava/util/List;");
        putMethod(
                selectors,
                "cubism.editor-model.model-image-group.linked-raw-image-guids",
                EditorTextureRelationsTestFixtures.HostModelImageGroup.class,
                "getLinkedRawImageGuids",
                "()Ljava/util/List;");
        putClass(selectors, "cubism.editor-model.guid.class", EditorTextureRelationsTestFixtures.HostId.class);
        putMethod(
                selectors,
                "cubism.editor-model.guid.value",
                EditorTextureRelationsTestFixtures.HostId.class,
                "value",
                "()Ljava/lang/String;");
        final Set<String> capabilities = authorized
                ? Set.of(EditorTextureRelationsSelectorContract.CAPABILITY_ID)
                : Set.of("unrelated-capability");
        return TestVerifiedResolvers.create(
                "5.3.02",
                EditorTextureRelationsSelectorContract.ADAPTER_SLICE_ID,
                capabilities,
                new ArrayList<>(selectors.values()),
                EditorTextureRelationsTestFixtures.class.getClassLoader());
    }

    static void putClass(final Map<String, StaticSelector> selectors, final String alias, final Class<?> type) {
        selectors.put(alias, StaticSelector.classSelector(alias, internal(type)));
    }

    static void putMethod(
            final Map<String, StaticSelector> selectors,
            final String alias,
            final Class<?> owner,
            final String name,
            final String descriptor) {
        selectors.put(
                alias, StaticSelector.method(alias, internal(owner), name, descriptor, StaticSelector.ACCESS_PUBLIC));
    }

    record NativeFixture(
            EditorTextureRelationsTestFixtures.ModelSource source,
            EditorTextureRelationsTestFixtures.Model model,
            VerifiedMemberResolver resolver) {}

    static CallSiteFixture callSiteFixture(final Path tempDir) throws Exception {
        // Callers arm a failure after construction so fixture seeding cannot consume it.
        CallSiteLayeredImage.failIncomingGuidReadAt = 0;
        CallSiteLayeredImage.incomingGuidReads = 0;
        final List<CallSiteWrapper> wrappers = new ArrayList<>();
        final CallSiteLayeredImage old = new CallSiteLayeredImage("old", "Old");
        final CallSiteLayeredImage incoming = new CallSiteLayeredImage("incoming", "Incoming");
        final MutableModelImage modelImage = new MutableModelImage(old);
        final CallSiteGroup group =
                new CallSiteGroup("Group A", "memo", List.of(modelImage), List.of(old.getGuid(), incoming.getGuid()));
        wrappers.add(new CallSiteWrapper(old, "import", "modified", true));
        final CallSiteTextureManager manager =
                new CallSiteTextureManager(wrappers, List.of(group), List.of(modelImage), List.of(), List.of(group));
        final CallSiteSource source = new CallSiteSource(manager);
        final CallSiteModel model = new CallSiteModel();
        final CallSiteFixture fixture = new CallSiteFixture(
                source,
                model,
                new CallSiteDocument(),
                wrappers,
                modelImage,
                callSiteResolver(),
                tempDir.resolve("staged.psd"));
        Files.writeString(fixture.stage, "synthetic-stage");
        CallSiteNativeProcess.active = fixture;
        CallSiteAppController.currentDocument = fixture.document;
        // Fixture construction uses the same reviewed GUID accessor to seed linked IDs; start
        // the counter after setup so failures below target production/diagnostic call-site reads.
        CallSiteLayeredImage.incomingGuidReads = 0;
        return fixture;
    }

    static PsdReplaceHost.Replacement replaceAtCallSite(final CallSiteFixture fixture) {
        final ModelTextures textures = new EditorTextureAccess(fixture.resolver, (identity, model) -> {})
                .textures("session-a", fixture.source, fixture.model);
        final PsdReplaceHost.Replacement result = ((PsdReplaceHost) textures)
                .replaceWithStagedPsd(new RawImageId("old"), fixture.stage, "external-edit.psd", () -> {});
        return result;
    }

    static VerifiedMemberResolver callSiteResolver() {
        final Map<String, StaticSelector> selectors = new LinkedHashMap<>();
        addAliases(selectors, EditorTextureRelationsSelectorContract.REQUIRED_ALIASES);
        addAliases(selectors, EditorTextureSelectorContract.READ_REQUIRED_ALIASES);
        addAliases(selectors, EditorRawImagePsdSelectorContract.REQUIRED_ALIASES);
        addAliases(selectors, EditorRawImagePsdReplaceSelectorContract.REQUIRED_ALIASES);

        putMethod(
                selectors,
                "cubism.editor-model.model-source.texture-manager",
                CallSiteSource.class,
                "textureManager",
                desc(CallSiteTextureManager.class));
        putMethod(
                selectors,
                "cubism.editor-model.texture-manager.raw-images",
                CallSiteTextureManager.class,
                "rawImages",
                "()Ljava/util/List;");
        putMethod(
                selectors,
                "cubism.editor-model.texture-manager.model-image-groups",
                CallSiteTextureManager.class,
                "modelImageGroups",
                "()Ljava/util/List;");
        putMethod(
                selectors,
                "cubism.editor-model.texture-manager.all-model-images",
                CallSiteTextureManager.class,
                "allModelImages",
                "()Ljava/util/List;");
        putMethod(
                selectors,
                "cubism.editor-model.texture-manager.texture-atlases",
                CallSiteTextureManager.class,
                "textureAtlases",
                "()Ljava/util/List;");
        putMethod(
                selectors,
                "cubism.editor-model.texture-manager.art-mesh-usable-model-image-groups",
                CallSiteTextureManager.class,
                "artMeshUsableModelImageGroups",
                "()Ljava/util/List;");
        putMethod(
                selectors,
                "cubism.editor-model.layered-image-wrapper.image",
                CallSiteWrapper.class,
                "image",
                desc(CallSiteLayeredImage.class));
        putMethod(
                selectors,
                "cubism.editor-model.layered-image-wrapper.import-time",
                CallSiteWrapper.class,
                "getImportTime",
                "()Ljava/lang/String;");
        putMethod(
                selectors,
                "cubism.editor-model.layered-image-wrapper.modified-time",
                CallSiteWrapper.class,
                "getModifiedTime",
                "()Ljava/lang/String;");
        putMethod(
                selectors,
                "cubism.editor-model.layered-image-wrapper.replaced",
                CallSiteWrapper.class,
                "isReplaced",
                "()Z");
        putClass(selectors, "cubism.editor-model.layered-image.class", CallSiteLayeredImage.class);
        putMethod(
                selectors,
                "cubism.editor-model.layered-image.guid",
                CallSiteLayeredImage.class,
                "getGuid",
                desc(HostId.class));
        putMethod(
                selectors,
                "cubism.editor-model.layered-image.name",
                CallSiteLayeredImage.class,
                "getName",
                "()Ljava/lang/String;");
        putMethod(selectors, "cubism.editor-model.layered-image.width", CallSiteLayeredImage.class, "getWidth", "()I");
        putMethod(
                selectors, "cubism.editor-model.layered-image.height", CallSiteLayeredImage.class, "getHeight", "()I");
        putMethod(
                selectors,
                "cubism.editor-model.layered-image.psd-doc",
                CallSiteLayeredImage.class,
                "getPsdDoc",
                desc(CallSitePsdDocument.class));
        putMethod(
                selectors,
                "cubism.editor-model.layered-image.children",
                CallSiteLayeredImage.class,
                "getChildren",
                "()Ljava/util/List;");
        putClass(selectors, "cubism.editor-model.psd-progress.class", CallSiteProgress.class);
        putStaticMethod(
                selectors,
                "cubism.editor-model.psd-progress.default",
                CallSiteProgressFactory.class,
                "e",
                desc(CallSiteProgress.class));
        putMethod(
                selectors,
                "cubism.editor-model.layered-image.save-psd",
                CallSiteLayeredImage.class,
                "save",
                "(Ljava/io/File;L" + internal(CallSiteProgress.class) + ";)V");
        putClass(selectors, "cubism.editor-model.model-image.class", CallSiteModelImage.class);
        putMethod(
                selectors,
                "cubism.editor-model.model-image.guid",
                CallSiteModelImage.class,
                "getGuid",
                desc(HostId.class));
        putMethod(
                selectors,
                "cubism.editor-model.model-image.name",
                CallSiteModelImage.class,
                "getName",
                "()Ljava/lang/String;");
        putMethod(selectors, "cubism.editor-model.model-image.width", CallSiteModelImage.class, "getWidth", "()I");
        putMethod(selectors, "cubism.editor-model.model-image.height", CallSiteModelImage.class, "getHeight", "()I");
        putMethod(
                selectors,
                "cubism.editor-model.model-image.linked-raw-image-guids",
                CallSiteModelImage.class,
                "getLinkedRawImageGuids",
                "()Ljava/util/List;");
        putMethod(
                selectors,
                "cubism.editor-model.model-image.input-filter-env",
                CallSiteModelImage.class,
                "getInputFilterEnv",
                desc(CallSiteFilterEnv.class));
        putClass(selectors, "cubism.editor-model.model-image-filter-env.class", CallSiteFilterEnv.class);
        putMethod(
                selectors,
                "cubism.editor-model.model-image-filter-env.has-layer-input-data",
                CallSiteFilterEnv.class,
                "getHasLayerInputData",
                "()Z");
        putMethod(
                selectors,
                "cubism.editor-model.model-image-filter-env.layer-input-data",
                CallSiteFilterEnv.class,
                "getLayerInputData",
                desc(CallSiteSelectorMap.class));
        putMethod(
                selectors,
                "cubism.editor-model.model-image-filter-env.has-current-image-guid",
                CallSiteFilterEnv.class,
                "getHasCurrentImageGuid",
                "()Z");
        putMethod(
                selectors,
                "cubism.editor-model.model-image-filter-env.current-image-guid",
                CallSiteFilterEnv.class,
                "getCurrentImageGuid",
                desc(HostId.class));
        putClass(selectors, "cubism.editor-model.layer-selector-map.class", CallSiteSelectorMap.class);
        putMethod(
                selectors,
                "cubism.editor-model.layer-selector-map.image-to-layer-input",
                CallSiteSelectorMap.class,
                "getImageToLayerInput",
                "()Ljava/util/Map;");
        putClass(selectors, "cubism.editor-model.model-image-group.class", CallSiteGroup.class);
        putMethod(
                selectors,
                "cubism.editor-model.model-image-group.group-name",
                CallSiteGroup.class,
                "getGroupName",
                "()Ljava/lang/String;");
        putMethod(
                selectors,
                "cubism.editor-model.model-image-group.memo",
                CallSiteGroup.class,
                "getMemo",
                "()Ljava/lang/String;");
        putMethod(
                selectors,
                "cubism.editor-model.model-image-group.model-images",
                CallSiteGroup.class,
                "getModelImages",
                "()Ljava/util/List;");
        putMethod(
                selectors,
                "cubism.editor-model.model-image-group.linked-raw-image-guids",
                CallSiteGroup.class,
                "getLinkedRawImageGuids",
                "()Ljava/util/List;");
        putMethod(
                selectors,
                "cubism.editor-model.model-source.all-art-meshes",
                CallSiteSource.class,
                "allArtMeshes",
                "()Ljava/util/List;");
        putMethod(
                selectors,
                "cubism.editor-model.model.all-art-meshes",
                CallSiteModel.class,
                "allArtMeshes",
                "()Ljava/util/List;");
        putMethod(selectors, "cubism.editor-model.guid.value", HostId.class, "value", "()Ljava/lang/String;");

        putClass(selectors, "cubism.editor-model.app-controller.class", CallSiteAppController.class);
        putStaticMethod(
                selectors,
                "cubism.editor-model.app-controller.instance",
                CallSiteAppController.class,
                "instance",
                desc(CallSiteAppController.class));
        putMethod(
                selectors,
                "cubism.editor-model.app-controller.current-document",
                CallSiteAppController.class,
                "currentDocument",
                desc(CallSiteDocument.class));
        putClass(selectors, "cubism.editor-model.modeling-document.class", CallSiteDocument.class);
        putMethod(
                selectors,
                "cubism.editor-command.canvas.edit-mode",
                CallSiteDocument.class,
                "editMode",
                desc(CallSiteEditMode.class));
        putMethod(selectors, "cubism.editor-command.canvas.is-editing", CallSiteEditMode.class, "isEditing", "()Z");
        putClass(selectors, "cubism.editor-model.psd-import-process.class", CallSiteNativeProcess.class);
        selectors.put(
                "cubism.editor-model.psd-import-process.instance",
                StaticSelector.field(
                        "cubism.editor-model.psd-import-process.instance",
                        internal(CallSiteNativeProcess.class),
                        "INSTANCE",
                        desc(CallSiteNativeProcess.class).substring(2),
                        StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
        putMethod(
                selectors,
                "cubism.editor-model.psd-import-process.replace",
                CallSiteNativeProcess.class,
                "replace",
                "(" + desc(CallSiteAppController.class).substring(2)
                        + desc(CallSiteLayeredImage.class).substring(2)
                        + "Ljava/io/File;"
                        + desc(CallSiteDocument.class).substring(2)
                        + "Ljava/util/List;)V");

        putMethod(
                selectors,
                "cubism.editor-model.psd-document.layers-owned",
                CallSiteParsed.class,
                "h",
                "()[Ljava/lang/Object;");
        putMethod(
                selectors,
                "cubism.editor-model.layered-image.dispose-owned",
                CallSiteLayeredImage.class,
                "dispose",
                "()V");
        putClass(selectors, "cubism.editor-model.psd-document.class", CallSiteParsed.class);
        putClass(selectors, "cubism.editor-model.psd-document-companion.class", CallSiteCompanion.class);
        selectors.put(
                "cubism.editor-model.psd-document.companion",
                StaticSelector.field(
                        "cubism.editor-model.psd-document.companion",
                        internal(CallSitePsdDocument.class),
                        "companion",
                        desc(CallSiteCompanion.class).substring(2),
                        StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
        putMethod(
                selectors,
                "cubism.editor-model.psd-document.parse-file",
                CallSiteCompanion.class,
                "parseFile",
                "(Ljava/io/File;ZZ)" + desc(CallSiteParsed.class).substring(2));
        selectors.put(
                "cubism.editor-model.layered-image.from-psd",
                StaticSelector.constructor(
                        "cubism.editor-model.layered-image.from-psd",
                        internal(CallSiteLayeredImage.class),
                        "(" + desc(CallSiteParsed.class).substring(2) + "Ljava/io/File;Ljava/lang/String;)V",
                        0));

        final Set<String> capabilities = Set.of(
                EditorTextureSelectorContract.READ_CAPABILITY_ID,
                EditorRawImagePsdReplaceSelectorContract.CAPABILITY_ID,
                EditorRawImagePsdSelectorContract.CAPABILITY_ID);
        return TestVerifiedResolvers.create(
                "5.3.02",
                EditorTextureSelectorContract.ADAPTER_SLICE_ID,
                capabilities,
                new ArrayList<>(selectors.values()),
                EditorTextureRelationsTestFixtures.class.getClassLoader());
    }

    static void addAliases(final Map<String, StaticSelector> selectors, final Set<String> aliases) {
        for (final String alias : aliases) {
            selectors.putIfAbsent(alias, StaticSelector.classSelector(alias, internal(Object.class)));
        }
    }

    static void putStaticMethod(
            final Map<String, StaticSelector> selectors,
            final String alias,
            final Class<?> owner,
            final String name,
            final String descriptor) {
        selectors.put(
                alias,
                StaticSelector.staticMethod(
                        alias,
                        internal(owner),
                        name,
                        descriptor,
                        StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
    }

    static final class CallSiteFixture {
        final CallSiteSource source;
        final CallSiteModel model;
        final CallSiteDocument document;
        final List<CallSiteWrapper> wrappers;
        final MutableModelImage modelImage;
        final VerifiedMemberResolver resolver;
        final Path stage;
        final CallSiteNativeProcess nativeProcess = CallSiteNativeProcess.INSTANCE;

        CallSiteFixture(
                final CallSiteSource source,
                final CallSiteModel model,
                final CallSiteDocument document,
                final List<CallSiteWrapper> wrappers,
                final MutableModelImage modelImage,
                final VerifiedMemberResolver resolver,
                final Path stage) {
            this.source = source;
            this.model = model;
            this.document = document;
            this.wrappers = wrappers;
            this.modelImage = modelImage;
            this.resolver = resolver;
            this.stage = stage;
        }
    }

    static final class CallSiteAppController {
        static final CallSiteAppController INSTANCE = new CallSiteAppController();
        static CallSiteDocument currentDocument;

        public static CallSiteAppController instance() {
            return INSTANCE;
        }

        public CallSiteDocument currentDocument() {
            return currentDocument;
        }
    }

    static final class CallSiteDocument {
        final CallSiteEditMode editMode = new CallSiteEditMode();

        public CallSiteEditMode editMode() {
            return editMode;
        }
    }

    static final class CallSiteEditMode {
        public boolean isEditing() {
            return false;
        }
    }

    public static final class CallSiteParsed {
        public Object[] h() {
            return new Object[0];
        }

        final File source;

        CallSiteParsed(final File source) {
            this.source = source;
        }
    }

    public static final class CallSiteCompanion {
        public CallSiteParsed parseFile(final File source, final boolean first, final boolean second) {
            if (!source.isFile() || source.length() == 0) return null;
            return new CallSiteParsed(source);
        }
    }

    public static final class CallSitePsdDocument {
        public static final CallSiteCompanion companion = new CallSiteCompanion();
    }

    static final class CallSiteLayeredImage {
        static int failIncomingGuidReadAt;
        static int incomingGuidReads;
        static int saveCalls;
        final HostId guid;
        final String name;
        final File psdFile;
        final CallSitePsdDocument psdDocument = new CallSitePsdDocument();

        CallSiteLayeredImage(final String id, final String name) {
            this.guid = new HostId(id);
            this.name = name;
            this.psdFile = new File("/" + id + ".psd");
        }

        CallSiteLayeredImage(final CallSiteParsed parsed, final File source, final String name) {
            if (parsed == null || !parsed.source.equals(source)) {
                throw new IllegalStateException("wrong parsed stage");
            }
            this.guid = new HostId("incoming");
            this.name = name;
            this.psdFile = source;
        }

        public HostId getGuid() {
            if ("incoming".equals(guid.value) && ++incomingGuidReads == failIncomingGuidReadAt) {
                throw new IllegalStateException("incoming diagnostic identity failure");
            }
            return guid;
        }

        public String getName() {
            return name;
        }

        public int getWidth() {
            return 100;
        }

        public int getHeight() {
            return 100;
        }

        public File getPsdFile() {
            return psdFile;
        }

        public CallSitePsdDocument getPsdDoc() {
            return psdDocument;
        }

        public List<?> getChildren() {
            return List.of();
        }

        public void dispose() {}

        public void save(final File target, final CallSiteProgress progress) {
            if (progress == null) throw new IllegalStateException("missing export progress");
            saveCalls++;
            try {
                Files.writeString(target.toPath(), "synthetic-export");
            } catch (Exception failure) {
                throw new IllegalStateException("synthetic export failed", failure);
            }
        }
    }

    public static final class CallSiteProgress {}

    public static final class CallSiteProgressFactory {
        CallSiteProgressFactory() {}

        public static CallSiteProgress e() {
            return new CallSiteProgress();
        }
    }

    static final class CallSiteWrapper {
        final CallSiteLayeredImage image;
        final String importTime;
        final String modifiedTime;
        final boolean replaced;

        CallSiteWrapper(
                final CallSiteLayeredImage image,
                final String importTime,
                final String modifiedTime,
                final boolean replaced) {
            this.image = image;
            this.importTime = importTime;
            this.modifiedTime = modifiedTime;
            this.replaced = replaced;
        }

        public CallSiteLayeredImage image() {
            return image;
        }

        public String getImportTime() {
            return importTime;
        }

        public String getModifiedTime() {
            return modifiedTime;
        }

        public boolean isReplaced() {
            return replaced;
        }
    }

    static final class CallSiteFilterEnv {
        static int hasReads;
        static int throwOnHasRead;
        static int throwOnHasReadLinkageError;

        final boolean hasLayerInputData;
        final CallSiteSelectorMap layerInputData;
        final boolean hasCurrentImageGuid;
        final HostId currentImageGuid;

        CallSiteFilterEnv(
                final boolean hasLayerInputData,
                final CallSiteSelectorMap layerInputData,
                final boolean hasCurrentImageGuid,
                final HostId currentImageGuid) {
            this.hasLayerInputData = hasLayerInputData;
            this.layerInputData = layerInputData;
            this.hasCurrentImageGuid = hasCurrentImageGuid;
            this.currentImageGuid = currentImageGuid;
        }

        public boolean getHasLayerInputData() {
            hasReads++;
            if (throwOnHasRead == hasReads) {
                throw new IllegalStateException("diagnostic filter observation failure");
            }
            if (throwOnHasReadLinkageError == hasReads) {
                throwOnHasReadLinkageError = 0;
                throw new NoClassDefFoundError("diagnostic filter linkage failure");
            }
            return hasLayerInputData;
        }

        public CallSiteSelectorMap getLayerInputData() {
            return layerInputData;
        }

        public boolean getHasCurrentImageGuid() {
            return hasCurrentImageGuid;
        }

        public HostId getCurrentImageGuid() {
            return currentImageGuid;
        }
    }

    static final class CallSiteSelectorMap {
        final Map<HostId, List<?>> imageToLayerInput;

        CallSiteSelectorMap(final Map<HostId, List<?>> imageToLayerInput) {
            this.imageToLayerInput = imageToLayerInput;
        }

        public Map<HostId, List<?>> getImageToLayerInput() {
            return imageToLayerInput;
        }
    }

    static class CallSiteModelImage {
        final HostId guid;
        List<HostId> linkedRawImageGuids;
        CallSiteFilterEnv inputFilterEnv;

        CallSiteModelImage(final CallSiteLayeredImage old) {
            guid = new HostId("model-a");
            linkedRawImageGuids = List.of(old.getGuid());
            inputFilterEnv = new CallSiteFilterEnv(true, new CallSiteSelectorMap(Map.of()), true, old.getGuid());
        }

        public HostId getGuid() {
            return guid;
        }

        public String getName() {
            return "Image";
        }

        public int getWidth() {
            return 100;
        }

        public int getHeight() {
            return 100;
        }

        public List<HostId> getLinkedRawImageGuids() {
            return linkedRawImageGuids;
        }

        public CallSiteFilterEnv getInputFilterEnv() {
            return inputFilterEnv;
        }

        void replace(final HostId incoming) {
            linkedRawImageGuids = List.of(incoming);
            inputFilterEnv = new CallSiteFilterEnv(true, new CallSiteSelectorMap(Map.of()), true, incoming);
        }
    }

    static final class MutableModelImage extends CallSiteModelImage {
        MutableModelImage(final CallSiteLayeredImage old) {
            super(old);
        }
    }

    static final class CallSiteGroup {
        final String name;
        final String memo;
        final List<CallSiteModelImage> modelImages;
        final List<HostId> linkedRawImageGuids;

        CallSiteGroup(
                final String name,
                final String memo,
                final List<CallSiteModelImage> modelImages,
                final List<HostId> linkedRawImageGuids) {
            this.name = name;
            this.memo = memo;
            this.modelImages = modelImages;
            this.linkedRawImageGuids = linkedRawImageGuids;
        }

        public String getGroupName() {
            return name;
        }

        public String getMemo() {
            return memo;
        }

        public List<CallSiteModelImage> getModelImages() {
            return modelImages;
        }

        public List<HostId> getLinkedRawImageGuids() {
            return linkedRawImageGuids;
        }
    }

    static final class CallSiteTextureManager {
        final List<CallSiteWrapper> rawImages;
        final List<CallSiteGroup> modelImageGroups;
        final List<CallSiteModelImage> allModelImages;
        final List<?> textureAtlases;
        final List<CallSiteGroup> artMeshUsableModelImageGroups;

        CallSiteTextureManager(
                final List<CallSiteWrapper> rawImages,
                final List<CallSiteGroup> modelImageGroups,
                final List<CallSiteModelImage> allModelImages,
                final List<?> textureAtlases,
                final List<CallSiteGroup> artMeshUsableModelImageGroups) {
            this.rawImages = rawImages;
            this.modelImageGroups = modelImageGroups;
            this.allModelImages = allModelImages;
            this.textureAtlases = textureAtlases;
            this.artMeshUsableModelImageGroups = artMeshUsableModelImageGroups;
        }

        public List<CallSiteWrapper> rawImages() {
            return rawImages;
        }

        public List<CallSiteGroup> modelImageGroups() {
            return modelImageGroups;
        }

        public List<CallSiteModelImage> allModelImages() {
            return allModelImages;
        }

        public List<?> textureAtlases() {
            return textureAtlases;
        }

        public List<CallSiteGroup> artMeshUsableModelImageGroups() {
            return artMeshUsableModelImageGroups;
        }
    }

    static final class CallSiteSource {
        final CallSiteTextureManager textureManager;

        CallSiteSource(final CallSiteTextureManager textureManager) {
            this.textureManager = textureManager;
        }

        public CallSiteTextureManager textureManager() {
            return textureManager;
        }

        public List<?> allArtMeshes() {
            return List.of();
        }
    }

    static final class CallSiteModel {
        public List<?> allArtMeshes() {
            return List.of();
        }
    }

    public static final class CallSiteNativeProcess {
        enum NativeMutation {
            CORRECT_INCOMING,
            OLD_UNCHANGED,
            REGISTER_INCOMING_ONLY,
            WRONG_RAW,
            INCOMING_NOT_REGISTERED
        }

        public static final CallSiteNativeProcess INSTANCE = new CallSiteNativeProcess();
        static CallSiteFixture active;
        static int calls;
        static boolean fail;
        static NativeMutation mutation = NativeMutation.CORRECT_INCOMING;

        public void replace(
                final CallSiteAppController app,
                final CallSiteLayeredImage incoming,
                final File stage,
                final CallSiteDocument document,
                final List<?> targets) {
            calls++;
            if (fail) throw new IllegalStateException("native call root cause");
            if (active == null) throw new IllegalStateException("missing call-site fixture");
            if (mutation != NativeMutation.OLD_UNCHANGED && mutation != NativeMutation.INCOMING_NOT_REGISTERED) {
                active.wrappers.add(new CallSiteWrapper(incoming, "import-incoming", "modified-incoming", true));
            }
            switch (mutation) {
                case CORRECT_INCOMING, REGISTER_INCOMING_ONLY -> {
                    if (mutation == NativeMutation.CORRECT_INCOMING) {
                        active.modelImage.replace(incoming.getGuid());
                    }
                }
                case OLD_UNCHANGED -> {}
                case INCOMING_NOT_REGISTERED -> active.modelImage.replace(incoming.getGuid());
                case WRONG_RAW -> {
                    final CallSiteLayeredImage third = new CallSiteLayeredImage("third", "Third");
                    active.wrappers.add(new CallSiteWrapper(third, "import-third", "modified-third", true));
                    active.modelImage.replace(third.getGuid());
                }
            }
        }
    }
}
