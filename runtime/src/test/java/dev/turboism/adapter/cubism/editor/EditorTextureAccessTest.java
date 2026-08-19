package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.selector.EditorTextureSelectorContract;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.id.ModelImageId;
import dev.turboism.sdk.cubism.id.RawImageId;
import dev.turboism.sdk.cubism.id.TextureAtlasId;
import dev.turboism.sdk.cubism.model.AtlasTexture;
import dev.turboism.sdk.cubism.model.ModelImageEntry;

import dev.turboism.sdk.cubism.model.ModelTextures;
import dev.turboism.sdk.cubism.model.RawTexture;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Editor texture-library projection: CTextureManager reads and
 * HostTextureManagerHandler undo-enveloped writes (add/remove model image groups,
 * model images, texture atlases, raw images).
 */
class EditorTextureAccessTest {

    @ParameterizedTest
    @ValueSource(strings = {"5.2.0", "5.3.02"})
    void readsTextureLibraryProjection(final String version) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final ModelTextures textures = access(version, true).textures(
            "session-a", fixture.source, fixture.model
        );

        final List<RawTexture> rawImages = textures.rawImages();
        assertEquals(1, rawImages.size());
        assertEquals(new RawImageId("raw-1"), rawImages.get(0).id());
        assertEquals("Raw A", rawImages.get(0).name());
        assertEquals(1024, rawImages.get(0).width());
        assertEquals(512, rawImages.get(0).height());

        final List<dev.turboism.sdk.cubism.model.ModelImageGroup> groups = textures.modelImageGroups();
        assertEquals(1, groups.size());
        assertEquals("Group A", groups.get(0).groupName());
        assertEquals("memo A", groups.get(0).memo());
        final ModelImageEntry image = groups.get(0).modelImages().get(0);
        assertEquals(new ModelImageId("image-1"), image.id());
        assertEquals("Image A", image.name());
        assertEquals(256, image.width());
        assertEquals(128, image.height());

        final List<AtlasTexture> atlases = textures.textureAtlases();
        assertEquals(1, atlases.size());
        assertEquals(new TextureAtlasId("atlas-1"), atlases.get(0).id());
        assertEquals("Atlas A", atlases.get(0).name());
        assertEquals(2048, atlases.get(0).width());
        assertEquals(1024, atlases.get(0).height());
        assertEquals(3, atlases.get(0).atlasVersion());
        assertEquals(1, atlases.get(0).modelImageCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.2.0", "5.3.02"})
    void addsModelImageGroupInsideUndoEnvelope(final String version) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final ModelTextures textures = access(version, true).textures(
            "session-a", fixture.source, fixture.model
        );

        textures.addModelImageGroup("New Group");
        assertEquals(2, fixture.manager.modelImageGroups.size());
        assertEquals("New Group", fixture.manager.modelImageGroups.get(1).groupName());
        assertEquals(List.of("Turboism: Add Model Image Group"), fixture.editMode.labels);
        assertEquals(1, fixture.editMode.edits.size());
        assertTrue(fixture.document.dirty);
        assertTrue(fixture.source.instancesUpdated);
        assertTrue(fixture.pack.repainted);

        fixture.editMode.undo();
        assertEquals(1, fixture.manager.modelImageGroups.size());

        fixture.editMode.redo();
        assertEquals(2, fixture.manager.modelImageGroups.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.2.0", "5.3.02"})
    void addsTextureAtlasInsideUndoEnvelope(final String version) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final ModelTextures textures = access(version, true).textures(
            "session-a", fixture.source, fixture.model
        );

        final TextureAtlasId id = textures.addTextureAtlas("Atlas B", 512, 256);
        assertEquals("atlas-new", id.value());
        assertEquals(2, fixture.manager.textureAtlases.size());
        assertEquals("Atlas B", fixture.manager.textureAtlases.get(1).name());
        assertEquals(512, fixture.manager.textureAtlases.get(1).width());
        assertEquals(256, fixture.manager.textureAtlases.get(1).height());

        fixture.editMode.undo();
        assertEquals(1, fixture.manager.textureAtlases.size());

        fixture.editMode.redo();
        assertEquals(2, fixture.manager.textureAtlases.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.2.0", "5.3.02"})
    void removesModelImageInsideUndoEnvelope(final String version) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final ModelTextures textures = access(version, true).textures(
            "session-a", fixture.source, fixture.model
        );

        textures.removeModelImage(new ModelImageId("image-1"));
        assertTrue(fixture.manager.allModelImages.isEmpty());

        fixture.editMode.undo();
        assertEquals(1, fixture.manager.allModelImages.size());

        fixture.editMode.redo();
        assertTrue(fixture.manager.allModelImages.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.2.0", "5.3.02"})
    void removesTextureAtlasInsideUndoEnvelope(final String version) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final ModelTextures textures = access(version, true).textures(
            "session-a", fixture.source, fixture.model
        );

        textures.removeTextureAtlas(new TextureAtlasId("atlas-1"));
        assertTrue(fixture.manager.textureAtlases.isEmpty());

        fixture.editMode.undo();
        assertEquals(1, fixture.manager.textureAtlases.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.2.0", "5.3.02"})
    void absentTextureIdsFailClosed(final String version) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final ModelTextures textures = access(version, true).textures(
            "session-a", fixture.source, fixture.model
        );

        assertThrows(NoSuchElementException.class,
            () -> textures.removeModelImage(new ModelImageId("missing")));
        assertThrows(NoSuchElementException.class,
            () -> textures.removeTextureAtlas(new TextureAtlasId("missing")));
        if (version.equals("5.3.02")) {
            assertThrows(NoSuchElementException.class,
                () -> textures.removeRawImage(new RawImageId("missing")));
        } else {
            assertThrows(UnsupportedOperationException.class,
                () -> textures.removeRawImage(new RawImageId("missing")));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.2.0", "5.3.02"})
    void textureReadsFailClosedWithoutCapability(final String version) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final EditorTextureAccess access = access(version, false);
        assertThrows(UnsupportedOperationException.class,
            () -> access.textures("session-a", fixture.source, fixture.model));
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.2.0", "5.3.02"})
    void textureWritesFailClosedWithoutWriteCapability(final String version) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final ModelTextures textures = readOnly(version).textures(
            "session-a", fixture.source, fixture.model
        );
        assertThrows(UnsupportedOperationException.class,
            () -> textures.addModelImageGroup("Blocked"));
        assertThrows(UnsupportedOperationException.class,
            () -> textures.addTextureAtlas("Blocked", 64, 64));
        assertThrows(UnsupportedOperationException.class,
            () -> textures.removeModelImage(new ModelImageId("image-1")));
        assertThrows(UnsupportedOperationException.class,
            () -> textures.removeTextureAtlas(new TextureAtlasId("atlas-1")));
        assertThrows(UnsupportedOperationException.class,
            () -> textures.removeRawImage(new RawImageId("raw-1")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.2.0", "5.3.02"})
    void rejectedUndoEntryRollsBackAndReportsFailure(final String version) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        fixture.editMode.rejectUndo = true;
        final ModelTextures textures = access(version, true).textures(
            "session-a", fixture.source, fixture.model
        );

        assertThrows(IllegalStateException.class, () -> textures.addModelImageGroup("Rejected"));
        assertEquals(1, fixture.manager.modelImageGroups.size(), "no partial state survives");
        assertTrue(fixture.editMode.finished, "edit must be closed");
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.2.0"})
    void removeRawImageFailsClosedOn52(final String version) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final ModelTextures textures = access(version, true).textures(
            "session-a", fixture.source, fixture.model
        );
        assertThrows(UnsupportedOperationException.class,
            () -> textures.removeRawImage(new RawImageId("raw-1")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.3.02"})
    void removeRawImageUsesUndoEnvelopeOn5302(final String version) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final ModelTextures textures = access(version, true).textures(
            "session-a", fixture.source, fixture.model
        );

        textures.removeRawImage(new RawImageId("raw-1"));
        assertTrue(fixture.manager.rawImages.isEmpty());
        assertTrue(fixture.manager.rawImageRemoved);

        fixture.editMode.undo();
        assertEquals(1, fixture.manager.rawImages.size());
        assertTrue(fixture.manager.rawImageRestored);
    }

    private static EditorTextureAccess access(final String version, final boolean includeCapability) {
        return new EditorTextureAccess(resolver(version, includeCapability), (identity, model) -> { });
    }

    private static EditorTextureAccess readOnly(final String version) {
        return new EditorTextureAccess(
            resolver(version, true, java.util.Set.of(
                EditorTextureSelectorContract.READ_CAPABILITY_ID
            )),
            (identity, model) -> { }
        );
    }

    private static VerifiedMemberResolver resolver(
        final String version,
        final boolean includeCapability
    ) {
        final java.util.Set<String> capabilities = includeCapability
            ? java.util.Set.of(
                EditorTextureSelectorContract.READ_CAPABILITY_ID,
                EditorTextureSelectorContract.WRITE_CAPABILITY_ID)
            : java.util.Set.of("cubism.editor-model.read");
        return resolver(version, includeCapability, capabilities);
    }

    private static VerifiedMemberResolver resolver(
        final String version,
        final boolean includeCapability,
        final java.util.Set<String> capabilities
    ) {
        final List<StaticSelector> values = new ArrayList<>();
        values.add(StaticSelector.classSelector("cubism.editor-model.app-controller.class", internal(Host.class)));
        values.add(StaticSelector.staticMethod("cubism.editor-model.app-controller.instance", internal(Host.class), "instance",
            "()L" + internal(Host.class) + ";", StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
        values.add(method("cubism.editor-model.app-controller.current-document", Host.class, "currentDocument", desc(Document.class)));
        values.add(method("cubism.editor-model.app-controller.complete-pack", Host.class, "completePack", desc(CompletePack.class)));
        values.add(StaticSelector.classSelector("cubism.editor-model.modeling-document.class", internal(Document.class)));
        values.add(method("cubism.editor-model.modeling-document.edit-mode", Document.class, "editMode", desc(EditMode.class)));
        values.add(method("cubism.editor-model.modeling-document.mark-dirty", Document.class, "markDirty", "()V"));
        values.add(method("cubism.editor-model.edit-mode.begin", EditMode.class, "begin", "(Ljava/lang/String;)" + type(GroupUndo.class)));
        values.add(method("cubism.editor-model.edit-mode.end", EditMode.class, "end", "(ZLjava/lang/Object;)V"));
        values.add(method("cubism.editor-model.undo.add", GroupUndo.class, "add", "(" + type(Undo.class) + "Z)Z"));
        values.add(method("cubism.editor-model.undo.add-listener", Undo.class, "addListener", "(" + type(Listener.class) + ")Z"));
        values.add(StaticSelector.classSelector("cubism.editor-model.undo-listener.class", internal(Listener.class)));
        values.add(method("cubism.editor-model.model-source.update-instances", ModelSource.class, "updateInstances", "()V"));
        values.add(method("cubism.editor-model.complete-pack.update-part-palette", CompletePack.class, "updateParts", "(Z)V"));
        values.add(method("cubism.editor-model.complete-pack.repaint-canvas", CompletePack.class, "repaint", "(Z)V"));
        values.add(method("cubism.editor-model.model-source.texture-manager", ModelSource.class, "textureManager", desc(TextureManager.class)));
        values.add(method("cubism.editor-model.texture-manager.raw-images", TextureManager.class, "rawImages", "()Ljava/util/List;"));
        values.add(method("cubism.editor-model.texture-manager.model-image-groups", TextureManager.class, "modelImageGroups", "()Ljava/util/List;"));
        values.add(method("cubism.editor-model.texture-manager.all-model-images", TextureManager.class, "allModelImages", "()Ljava/util/List;"));
        values.add(method("cubism.editor-model.texture-manager.texture-atlases", TextureManager.class, "textureAtlases", "()Ljava/util/List;"));
        values.add(method("cubism.editor-model.texture-manager.handler", TextureManager.class, "handler", desc(HostTextureManagerHandler.class)));
        values.add(method("cubism.editor-model.layered-image-wrapper.image", HostLayeredImageWrapper.class, "image", desc(HostLayeredImage.class)));
        values.add(StaticSelector.classSelector("cubism.editor-model.layered-image.class", internal(HostLayeredImage.class)));
        values.add(method("cubism.editor-model.layered-image.guid", HostLayeredImage.class, "guid", desc(Id.class)));
        values.add(method("cubism.editor-model.layered-image.name", HostLayeredImage.class, "name", "()Ljava/lang/String;"));
        values.add(method("cubism.editor-model.layered-image.width", HostLayeredImage.class, "width", "()I"));
        values.add(method("cubism.editor-model.layered-image.height", HostLayeredImage.class, "height", "()I"));
        values.add(StaticSelector.classSelector("cubism.editor-model.model-image-group.class", internal(HostModelImageGroup.class)));
        values.add(method("cubism.editor-model.model-image-group.group-name", HostModelImageGroup.class, "groupName", "()Ljava/lang/String;"));
        values.add(method("cubism.editor-model.model-image-group.memo", HostModelImageGroup.class, "memo", "()Ljava/lang/String;"));
        values.add(method("cubism.editor-model.model-image-group.model-images", HostModelImageGroup.class, "modelImages", "()Ljava/util/List;"));
        values.add(StaticSelector.classSelector("cubism.editor-model.model-image.class", internal(HostModelImage.class)));
        values.add(method("cubism.editor-model.model-image.guid", HostModelImage.class, "guid", desc(Id.class)));
        values.add(method("cubism.editor-model.model-image.name", HostModelImage.class, "name", "()Ljava/lang/String;"));
        values.add(method("cubism.editor-model.model-image.width", HostModelImage.class, "width", "()I"));
        values.add(method("cubism.editor-model.model-image.height", HostModelImage.class, "height", "()I"));
        values.add(StaticSelector.classSelector("cubism.editor-model.texture-atlas.class", internal(HostTextureAtlas.class)));
        values.add(method("cubism.editor-model.texture-atlas.guid", HostTextureAtlas.class, "guid", desc(Id.class)));
        values.add(method("cubism.editor-model.texture-atlas.name", HostTextureAtlas.class, "name", "()Ljava/lang/String;"));
        values.add(method("cubism.editor-model.texture-atlas.width", HostTextureAtlas.class, "width", "()I"));
        values.add(method("cubism.editor-model.texture-atlas.height", HostTextureAtlas.class, "height", "()I"));
        values.add(method("cubism.editor-model.texture-atlas.atlas-version", HostTextureAtlas.class, "atlasVersion", "()I"));
        values.add(method("cubism.editor-model.texture-atlas.model-images", HostTextureAtlas.class, "modelImages", "()Ljava/util/List;"));
        values.add(method("cubism.editor-model.guid.value", Id.class, "value", "()Ljava/lang/String;"));
        values.add(StaticSelector.constructor("cubism.editor-model.model-image-group.create", internal(HostModelImageGroup.class),
            "(Ljava/lang/String;)V", StaticSelector.ACCESS_PUBLIC));
        values.add(StaticSelector.constructor("cubism.editor-model.texture-atlas.create", internal(HostTextureAtlas.class),
            "(L" + internal(ModelSource.class) + ";Ljava/lang/String;II)V", StaticSelector.ACCESS_PUBLIC));
        values.add(method("cubism.editor-model.texture-handler.add-model-image-group", HostTextureManagerHandler.class, "addGroup",
            "(" + type(HostModelImageGroup.class) + "I)" + type(Undo.class)));
        values.add(method("cubism.editor-model.texture-handler.remove-model-image", HostTextureManagerHandler.class, "removeImage",
            "(" + type(Id.class) + ")" + type(Undo.class)));
        values.add(method("cubism.editor-model.texture-handler.add-texture-atlas", HostTextureManagerHandler.class, "addAtlas",
            "(" + type(HostTextureAtlas.class) + "I)" + type(Undo.class)));
        values.add(method("cubism.editor-model.texture-handler.remove-texture-atlas", HostTextureManagerHandler.class, "removeAtlas",
            "(" + type(HostTextureAtlas.class) + ")" + type(Undo.class)));
        if (version.equals("5.3.02")) {
            values.add(method("cubism.editor-model.texture-handler.remove-raw-image", HostTextureManagerHandler.class, "removeRawImage",
                "(" + type(Id.class) + "Z)" + type(Undo.class)));
        }
        return TestVerifiedResolvers.create(
            version, "adapter.editor-model.readwrite", capabilities, values, Host.class.getClassLoader()
        );
    }

    private static StaticSelector method(
        final String alias, final Class<?> owner, final String name, final String descriptor
    ) {
        return StaticSelector.method(alias, internal(owner), name, descriptor, StaticSelector.ACCESS_PUBLIC);
    }

    private static String internal(final Class<?> type) { return type.getName().replace('.', '/'); }
    private static String type(final Class<?> type) { return "L" + internal(type) + ";"; }
    private static String desc(final Class<?> type) { return "()" + type(type); }

    private static final class Fixture {
        final Document document = new Document();
        final ModelSource source = document.source;
        final Model model = source.model;
        final TextureManager manager = source.manager;
        final EditMode editMode = document.editMode;
        final CompletePack pack = document.pack;
    }

    public static final class Host {
        private static final Host INSTANCE = new Host();
        static Document document;
        public static Host instance() { return INSTANCE; }
        public Document currentDocument() { return document; }
        public CompletePack completePack() { return document.pack; }
    }

    public static final class Document {
        final ModelSource source = new ModelSource();
        final EditMode editMode = new EditMode();
        final CompletePack pack = new CompletePack();
        boolean dirty;
        public ModelSource modelSource() { return source; }
        public EditMode editMode() { return editMode; }
        public void markDirty() { dirty = true; }
    }

    public static final class CompletePack {
        boolean repainted;
        public void updateParts(final boolean value) { }
        public void repaint(final boolean value) { repainted = true; }
    }

    public static final class ModelSource {
        final TextureManager manager = new TextureManager();
        final Model model = new Model();
        boolean instancesUpdated;
        public TextureManager textureManager() { return manager; }
        public Model currentInstance() { return model; }
        public void updateInstances() { instancesUpdated = true; }
    }

    public static final class Model { }

    public static final class TextureManager {
        final List<HostLayeredImageWrapper> rawImages = new ArrayList<>();
        final List<HostModelImageGroup> modelImageGroups = new ArrayList<>();
        final List<HostModelImage> allModelImages = new ArrayList<>();
        final List<HostTextureAtlas> textureAtlases = new ArrayList<>();
        final HostTextureManagerHandler handler = new HostTextureManagerHandler(this);
        boolean rawImageRemoved;
        boolean rawImageRestored;

        TextureManager() {
            final HostModelImage image = new HostModelImage("image-1", "Image A", 256, 128);
            allModelImages.add(image);
            modelImageGroups.add(new HostModelImageGroup("Group A", "memo A", new ArrayList<>(List.of(image))));
            rawImages.add(new HostLayeredImageWrapper(new HostLayeredImage("raw-1", "Raw A", 1024, 512)));
            textureAtlases.add(new HostTextureAtlas("atlas-1", "Atlas A", 2048, 1024, 3, new ArrayList<>(List.of(image))));
        }

        public List<HostLayeredImageWrapper> rawImages() { return rawImages; }
        public List<HostModelImageGroup> modelImageGroups() { return modelImageGroups; }
        public List<HostModelImage> allModelImages() { return allModelImages; }
        public List<HostTextureAtlas> textureAtlases() { return textureAtlases; }
        public HostTextureManagerHandler handler() { return handler; }
    }

    public static final class HostLayeredImageWrapper {
        final HostLayeredImage image;
        HostLayeredImageWrapper(final HostLayeredImage image) { this.image = image; }
        public HostLayeredImage image() { return image; }
    }

    public static final class HostLayeredImage {
        final Id guid;
        final String name;
        final int width;
        final int height;
        HostLayeredImage(final String guid, final String name, final int width, final int height) {
            this.guid = new Id(guid);
            this.name = name;
            this.width = width;
            this.height = height;
        }
        public Id guid() { return guid; }
        public String name() { return name; }
        public int width() { return width; }
        public int height() { return height; }
    }

    public static final class HostModelImageGroup {
        final String groupName;
        final String memo;
        final List<HostModelImage> modelImages;
        public HostModelImageGroup(final String groupName) {
            this.groupName = groupName;
            this.memo = "";
            this.modelImages = new ArrayList<>();
        }
        public HostModelImageGroup(final String groupName, final String memo, final List<HostModelImage> modelImages) {
            this.groupName = groupName;
            this.memo = memo;
            this.modelImages = modelImages;
        }
        public String groupName() { return groupName; }
        public String memo() { return memo; }
        public List<HostModelImage> modelImages() { return modelImages; }
    }

    public static final class HostModelImage {
        final Id guid;
        final String name;
        final int width;
        final int height;
        HostModelImage(final String guid, final String name, final int width, final int height) {
            this.guid = new Id(guid);
            this.name = name;
            this.width = width;
            this.height = height;
        }
        public Id guid() { return guid; }
        public String name() { return name; }
        public int width() { return width; }
        public int height() { return height; }
    }

    public static final class HostTextureAtlas {
        final Id guid;
        final String name;
        final int width;
        final int height;
        final int atlasVersion;
        final List<?> modelImages;
        public HostTextureAtlas(final ModelSource source, final String name, final int width, final int height) {
            this.guid = new Id("atlas-new");
            this.name = name;
            this.width = width;
            this.height = height;
            this.atlasVersion = 0;
            this.modelImages = List.of();
        }
        HostTextureAtlas(final String guid, final String name, final int width, final int height,
                     final int atlasVersion, final List<?> modelImages) {
            this.guid = new Id(guid);
            this.name = name;
            this.width = width;
            this.height = height;
            this.atlasVersion = atlasVersion;
            this.modelImages = modelImages;
        }
        public Id guid() { return guid; }
        public String name() { return name; }
        public int width() { return width; }
        public int height() { return height; }
        public int atlasVersion() { return atlasVersion; }
        public List<?> modelImages() { return modelImages; }
    }

    public static final class Id {
        final String value;
        public Id(final String value) { this.value = value; }
        public String value() { return value; }
    }

    public static final class EditMode {
        final List<String> labels = new ArrayList<>();
        final List<GroupUndo> edits = new ArrayList<>();
        boolean rejectUndo;
        boolean finished;
        GroupUndo current;
        public GroupUndo begin(final String label) {
            labels.add(label);
            current = new GroupUndo(rejectUndo);
            return current;
        }
        public void end(final boolean rollback, final Object callback) {
            if (rollback && current != null) {
                current.discard();
            } else if (current != null) {
                edits.add(current);
            }
            current = null;
            finished = true;
        }
        public void undo() {
            for (int i = edits.size() - 1; i >= 0; i--) edits.get(i).undo();
        }
        public void redo() {
            for (GroupUndo edit : edits) edit.redo();
        }
    }

    public static final class GroupUndo {
        final List<Undo> entries = new ArrayList<>();
        final boolean reject;
        boolean discarded;
        GroupUndo() { this(false); }
        GroupUndo(final boolean reject) { this.reject = reject; }
        public boolean add(final Undo undo, final boolean redo) {
            if (discarded || reject) return false;
            if (redo) undo.redo();
            entries.add(undo);
            return true;
        }
        void discard() { discarded = true; }
        void undo() { for (int i = entries.size() - 1; i >= 0; i--) entries.get(i).undo(); }
        void redo() { for (Undo entry : entries) entry.redo(); }
    }

    /** Construct-and-redo undoable: redo() applies, undo() reverts the host mutation. */
    public static final class Undo {
        final Runnable apply;
        final Runnable revert;
        Undo(final Runnable apply, final Runnable revert) {
            this.apply = apply;
            this.revert = revert;
        }
        public boolean addListener(final Listener listener) { return true; }
        void redo() { apply.run(); }
        void undo() { revert.run(); }
    }

    @FunctionalInterface
    public interface Listener {
        void onEvent();
    }

    public static final class HostTextureManagerHandler {
        final TextureManager manager;
        HostTextureManagerHandler(final TextureManager manager) { this.manager = manager; }

        public Undo addGroup(final HostModelImageGroup group, final int index) {
            return new Undo(
                () -> manager.modelImageGroups.add(index, group),
                () -> manager.modelImageGroups.remove(group)
            );
        }

        public Undo removeImage(final Id guid) {
            final HostModelImage target = manager.allModelImages.stream()
                .filter(image -> image.guid.value.equals(guid.value)).findFirst().orElseThrow();
            return new Undo(
                () -> {
                    manager.allModelImages.remove(target);
                    manager.modelImageGroups.forEach(group -> group.modelImages.remove(target));
                },
                () -> {
                    manager.allModelImages.add(target);
                    manager.modelImageGroups.forEach(group -> {
                        if (!group.modelImages.contains(target)) group.modelImages.add(target);
                    });
                }
            );
        }

        public Undo addAtlas(final HostTextureAtlas atlas, final int index) {
            return new Undo(
                () -> manager.textureAtlases.add(index, atlas),
                () -> manager.textureAtlases.remove(atlas)
            );
        }

        public Undo removeAtlas(final HostTextureAtlas atlas) {
            return new Undo(
                () -> manager.textureAtlases.remove(atlas),
                () -> manager.textureAtlases.add(atlas)
            );
        }

        public Undo removeRawImage(final Id guid, final boolean flag) {
            final HostLayeredImageWrapper wrapper = manager.rawImages.stream()
                .filter(value -> value.image.guid.value.equals(guid.value)).findFirst().orElseThrow();
            return new Undo(
                () -> {
                    manager.rawImages.remove(wrapper);
                    manager.rawImageRemoved = true;
                },
                () -> {
                    manager.rawImages.add(wrapper);
                    manager.rawImageRestored = true;
                }
            );
        }
    }
}
