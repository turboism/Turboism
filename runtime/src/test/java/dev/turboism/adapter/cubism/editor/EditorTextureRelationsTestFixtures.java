package dev.turboism.adapter.cubism.editor;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

        public Wrapper(final LayeredImage image, final String importTime, final String modifiedTime, final boolean replaced) {
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

}
