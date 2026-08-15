package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.EditorObjectReadSelectorContract;
import dev.turboism.mapping.verification.EditorPsdSnapshotSelectorContract;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.clipmask.PsdClipMaskDocumentSnapshot;
import dev.turboism.sdk.cubism.clipmask.PsdClipMaskDocumentSnapshot.PsdLayerSnapshot;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused fake-resolver tests for the PSD snapshot extractor. These are
 * synthetic fixture tests only; they are not real-host evidence for any
 * Cubism version. Exact baseline scope: 5.3.02.
 */
final class EditorPsdSnapshotAccessTest {

    private static final String SESSION = "session-a";

    @Test
    void snapshotsPreserveRecursionOrderAndExplicitClippingWithoutBase() {
        final Fixture fixture = new Fixture();
        final LayeredImage image = fixture.image;
        image.children.addAll(List.of(
            fixture.layer("L2", "Clipped", true),
            fixture.layer("L1", "Base", false),
            fixture.group("G1",
                fixture.layer("G1a", "Child A", false),
                fixture.layer("G1b", "Child B", false))
        ));
        final LayeredImage allClipping = new LayeredImage("PSD-2");
        allClipping.children.addAll(List.of(
            fixture.layer("L3", "Clipped A", true),
            fixture.layer("L4", "Clipped B", true)
        ));
        fixture.textureManager.rawImages = new ArrayList<>(List.of(
            new Wrapper(fixture.image), new Wrapper(allClipping)
        ));
        final VerifiedMemberResolver resolver = resolver(
            Set.of(EditorObjectReadSelectorContract.CAPABILITY_ID,
                EditorPsdSnapshotSelectorContract.CAPABILITY_ID)
        );

        final List<PsdClipMaskDocumentSnapshot> snapshots = new EditorPsdSnapshotAccess(
            resolver, (identity, model) -> { }
        ).snapshots(SESSION, fixture.source, fixture.model);

        assertEquals(2, snapshots.size());
        final List<PsdClipMaskDocumentSnapshot.PsdLayerSnapshot> layers = snapshots.get(0).layers();
        assertEquals(3, layers.size());
        assertEquals("L2", layers.get(0).layerId());
        assertTrue(layers.get(0).clipping());
        assertEquals("L1", layers.get(0).clippingBaseLayerId().orElseThrow());
        assertEquals("L1", layers.get(1).layerId());
        assertFalse(layers.get(1).clipping());
        assertEquals("G1", layers.get(2).layerId());
        assertEquals(List.of("G1a", "G1b"), layers.get(2).children().stream()
            .map(PsdClipMaskDocumentSnapshot.PsdLayerSnapshot::layerId).toList());
        final List<PsdClipMaskDocumentSnapshot.PsdLayerSnapshot> allClippingLayers = snapshots.get(1).layers();
        assertEquals(List.of("L3", "L4"), allClippingLayers.stream()
            .map(PsdClipMaskDocumentSnapshot.PsdLayerSnapshot::layerId).toList());
        for (PsdClipMaskDocumentSnapshot.PsdLayerSnapshot layer : allClippingLayers) {
            assertTrue(layer.clipping(), "clipping=true without a resolvable base stays explicit");
            assertTrue(layer.clippingBaseLayerId().isEmpty());
        }
    }

    @Test
    void bindsArtMeshesByGuidValueAcrossDistinctLayerInstances() {
        final Fixture fixture = new Fixture();
        // The binding host object is a different instance than the PSD tree
        // object; both share the verified layer GUID string.
        final LayerEntry bindingInstance = fixture.mesh.layer("L1");
        final LayerEntry treeInstance = fixture.layer("L1", "Bound", false);
        fixture.image.children.add(treeInstance);
        final VerifiedMemberResolver resolver = resolver(
            Set.of(EditorObjectReadSelectorContract.CAPABILITY_ID,
                EditorPsdSnapshotSelectorContract.CAPABILITY_ID)
        );

        final List<PsdClipMaskDocumentSnapshot> snapshots = new EditorPsdSnapshotAccess(
            resolver, (identity, model) -> { }
        ).snapshots(SESSION, fixture.source, fixture.model);

        assertEquals(List.of(new ArtMeshId("ArtMeshFace")),
            snapshots.get(0).layers().get(0).artMeshIds());
        assertTrue(bindingInstance != treeInstance);
    }

    @Test
    void failsClosedWhenPsdSnapshotCapabilityIsMissing() {
        final Fixture fixture = new Fixture();
        final VerifiedMemberResolver resolver = resolver(
            Set.of(EditorObjectReadSelectorContract.CAPABILITY_ID)
        );

        assertThrows(UnsupportedOperationException.class, () -> new EditorPsdSnapshotAccess(
            resolver, (identity, model) -> { }
        ).snapshots(SESSION, fixture.source, fixture.model));
    }

    @Test
    void failsClosedWhenARequiredCollectionIsNotAnIterable() {
        final Fixture fixture = new Fixture();
        fixture.textureManager.rawImages = "not-a-collection";
        final VerifiedMemberResolver resolver = resolver(
            Set.of(EditorObjectReadSelectorContract.CAPABILITY_ID,
                EditorPsdSnapshotSelectorContract.CAPABILITY_ID)
        );

        assertThrows(IllegalStateException.class, () -> new EditorPsdSnapshotAccess(
            resolver, (identity, model) -> { }
        ).snapshots(SESSION, fixture.source, fixture.model));
    }

    private static VerifiedMemberResolver resolver(final Set<String> capabilities) {
        return TestVerifiedResolvers.create(
            "5.3.02",
            EditorObjectReadSelectorContract.ADAPTER_SLICE_ID,
            capabilities,
            selectors(),
            EditorPsdSnapshotAccessTest.class.getClassLoader()
        );
    }

    private static List<StaticSelector> selectors() {
        final List<StaticSelector> selectors = new ArrayList<>();
        // Real selectors for the invoked object-read path.
        selectors.add(method("cubism.editor-model.model-source.current-instance", ModelSource.class, "currentInstance", desc(Model.class)));
        selectors.add(method("cubism.editor-model.model-source.all-art-meshes", ModelSource.class, "allArtMeshes", "()Ljava/util/List;"));
        selectors.add(method("cubism.editor-model.model.all-art-meshes", Model.class, "allArtMeshes", "()Ljava/util/List;"));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.art-mesh-source.class", internal(ArtMeshSource.class)));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.art-mesh.class", internal(ArtMesh.class)));
        selectors.add(method("cubism.editor-model.parameter-controllable-source.id", ArtMeshSource.class, "id", desc(Id.class)));
        selectors.add(method("cubism.editor-model.art-mesh.source", ArtMesh.class, "source", desc(ArtMeshSource.class)));
        selectors.add(method("cubism.editor-model.guid.value", Id.class, "value", "()Ljava/lang/String;"));
        selectors.add(method("cubism.editor-model.id.value", Id.class, "value", "()Ljava/lang/String;"));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.model.class", internal(Model.class)));
        // Real selectors for the PSD snapshot chain.
        selectors.add(method("cubism.editor-model.model-source.texture-manager", ModelSource.class, "textureManager", desc(TextureManager.class)));
        selectors.add(method("cubism.editor-model.texture-manager.raw-images", TextureManager.class, "rawImages", "()Ljava/lang/Object;"));
        selectors.add(method("cubism.editor-model.layered-image-wrapper.image", Wrapper.class, "image", desc(LayeredImage.class)));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.layered-image.class", internal(LayeredImage.class)));
        selectors.add(method("cubism.editor-model.layered-image.guid", LayeredImage.class, "guid", desc(Id.class)));
        selectors.add(method("cubism.editor-model.layered-image.psd-file", LayeredImage.class, "psdFile", desc(File.class)));
        selectors.add(method("cubism.editor-model.layered-image.children", LayeredImage.class, "children", "()Ljava/util/List;"));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.layer-entry.class", internal(LayerEntry.class)));
        selectors.add(method("cubism.editor-model.layer-entry.guid", LayerEntry.class, "guid", desc(Id.class)));
        selectors.add(method("cubism.editor-model.layer-entry.name", LayerEntry.class, "name", "()Ljava/lang/String;"));
        selectors.add(method("cubism.editor-model.layer-entry.visible", LayerEntry.class, "visible", "()Z"));
        selectors.add(method("cubism.editor-model.layer-entry.clipping", LayerEntry.class, "clipping", "()Z"));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.layer-group.class", internal(LayerGroup.class)));
        selectors.add(method("cubism.editor-model.layer-group.children", LayerGroup.class, "children", "()Ljava/util/List;"));
        selectors.add(method("cubism.editor-model.art-mesh-source.texture-input-extension", ArtMeshSource.class, "textureInputExtension", desc(Extension.class)));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.texture-input-extension.class", internal(Extension.class)));
        selectors.add(method("cubism.editor-model.texture-input-extension.model-image-input", Extension.class, "modelImageInput", desc(Input.class)));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.texture-input-model-image.class", internal(Input.class)));
        selectors.add(method("cubism.editor-model.texture-input-model-image.model-image", Input.class, "modelImage", desc(ModelImage.class)));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.model-image.class", internal(ModelImage.class)));
        selectors.add(method("cubism.editor-model.model-image.current-layer-input-data", ModelImage.class, "currentLayerInputData", "()Ljava/util/List;"));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.layer-input-data.class", internal(InputData.class)));
        selectors.add(method("cubism.editor-model.layer-input-data.layer", InputData.class, "layer", desc(LayerEntry.class)));
        // Placeholders for the remaining object-read contract aliases; they are
        // demanded by the object-read gate but never invoked on this path.
        for (String alias : EditorObjectReadSelectorContract.REQUIRED_ALIASES) {
            if (selectors.stream().noneMatch(s -> s.alias().equals(alias))) {
                selectors.add(StaticSelector.classSelector(alias, "java/lang/Object"));
            }
        }
        return List.copyOf(selectors);
    }

    private static StaticSelector method(
        final String alias, final Class<?> owner, final String name, final String descriptor
    ) {
        return StaticSelector.method(alias, internal(owner), name, descriptor, StaticSelector.ACCESS_PUBLIC);
    }

    private static String internal(final Class<?> type) {
        return type.getName().replace('.', '/');
    }

    private static String desc(final Class<?> type) {
        return "()L" + internal(type) + ";";
    }

    private static final class Fixture {
        final ModelSource source = new ModelSource();
        final Model model = new Model();
        final TextureManager textureManager = new TextureManager();
        final LayeredImage image = new LayeredImage("PSD-1");
        final ArtMeshSource mesh = new ArtMeshSource("ArtMeshFace");

        Fixture() {
            source.textureManager = textureManager;
            textureManager.rawImages = new ArrayList<>(List.of(new Wrapper(image)));
            source.meshes.add(mesh);
            model.meshes.add(new ArtMesh(mesh));
        }

        LayerEntry layer(final String guid, final String name, final boolean clipping) {
            return new LayerEntry(new Id(guid), name, true, clipping);
        }

        LayerGroup group(final String guid, final LayerEntry... children) {
            return new LayerGroup(new Id(guid), guid, children);
        }
    }

    public static final class ModelSource {
        final List<ArtMeshSource> meshes = new ArrayList<>();
        TextureManager textureManager;
        public Model currentInstance() { return null; }
        public List<ArtMeshSource> allArtMeshes() { return meshes; }
        public TextureManager textureManager() { return textureManager; }
    }

    public static final class Model {
        final List<ArtMesh> meshes = new ArrayList<>();
        public List<ArtMesh> allArtMeshes() { return meshes; }
    }

    public static final class ArtMesh {
        final ArtMeshSource source;
        ArtMesh(final ArtMeshSource source) { this.source = source; }
        public ArtMeshSource source() { return source; }
    }

    public static final class ArtMeshSource {
        final Id id;
        final List<InputData> inputData = new ArrayList<>();
        Extension extension;
        ArtMeshSource(final String id) { this.id = new Id(id); }
        public Id id() { return id; }
        public Extension textureInputExtension() { return extension; }
        LayerEntry layer(final String guid) {
            if (extension == null) {
                extension = new Extension();
            }
            final LayerEntry entry = new LayerEntry(new Id(guid), guid, true, false);
            extension.input.modelImage.inputData.add(new InputData(entry));
            return entry;
        }
    }

    public static final class Extension {
        final Input input = new Input();
        public Input modelImageInput() { return input; }
    }

    public static final class Input {
        final ModelImage modelImage = new ModelImage();
        public ModelImage modelImage() { return modelImage; }
    }

    public static final class ModelImage {
        final List<InputData> inputData = new ArrayList<>();
        public List<InputData> currentLayerInputData() { return inputData; }
    }

    public static final class InputData {
        final LayerEntry layer;
        InputData(final LayerEntry layer) { this.layer = layer; }
        public LayerEntry layer() { return layer; }
    }

    public static final class TextureManager {
        Object rawImages = new ArrayList<>();
        public Object rawImages() { return rawImages; }
    }

    public static final class Wrapper {
        final LayeredImage image;
        Wrapper(final LayeredImage image) { this.image = image; }
        public LayeredImage image() { return image; }
    }

    public static final class LayeredImage {
        final Id guid;
        final File psdFile = new File("textures/source.psd");
        final List<LayerEntry> children = new ArrayList<>();
        LayeredImage(final String guid) { this.guid = new Id(guid); }
        public Id guid() { return guid; }
        public File psdFile() { return psdFile; }
        public List<LayerEntry> children() { return children; }
    }

    public static class LayerEntry {
        final Id guid;
        final String name;
        final boolean visible;
        final boolean clipping;
        LayerEntry(final Id guid, final String name, final boolean visible, final boolean clipping) {
            this.guid = guid;
            this.name = name;
            this.visible = visible;
            this.clipping = clipping;
        }
        public Id guid() { return guid; }
        public String name() { return name; }
        public boolean visible() { return visible; }
        public boolean clipping() { return clipping; }
    }

    public static final class LayerGroup extends LayerEntry {
        final List<LayerEntry> children = new ArrayList<>();
        LayerGroup(final Id guid, final String name, final LayerEntry... children) {
            super(guid, name, true, false);
            this.children.addAll(List.of(children));
        }
        public List<LayerEntry> children() { return children; }
    }

    public static final class Id {
        final String value;
        Id(final String value) { this.value = value; }
        public String value() { return value; }
    }
}
