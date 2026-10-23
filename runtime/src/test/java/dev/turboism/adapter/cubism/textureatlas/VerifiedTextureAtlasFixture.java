package dev.turboism.adapter.cubism.textureatlas;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import java.awt.geom.AffineTransform;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

/**
 * Shared test fixture and simulated Cubism internal classes for texture atlas layout tests.
 */
final class VerifiedTextureAtlasFixture {

    static final VerifiedTextureAtlasSelectorContract.Profile PROFILE_5_2_03 =
            VerifiedTextureAtlasSelectorContract.profileFor("5.2.03").orElseThrow();
    static final VerifiedTextureAtlasSelectorContract.Profile PROFILE_5_3_02 =
            VerifiedTextureAtlasSelectorContract.profileFor("5.3.02").orElseThrow();

    private VerifiedTextureAtlasFixture() {}

    static VerifiedTextureAtlasLayoutProvider provider(
            final String version, final String sessionIdentity, final Fixture fixture) {
        final VerifiedTextureAtlasSelectorContract.Profile profile =
                VerifiedTextureAtlasSelectorContract.profileFor(version).orElseThrow();
        return provider(resolver(version, true), sessionIdentity, fixture, profile);
    }

    static VerifiedTextureAtlasLayoutProvider provider(
            final VerifiedMemberResolver resolver,
            final String sessionIdentity,
            final Fixture fixture,
            final VerifiedTextureAtlasSelectorContract.Profile profile) {
        final TextureAtlasDataModelCapture capture = new TextureAtlasDataModelCapture();
        capture.capture(fixture.data);
        return new VerifiedTextureAtlasLayoutProvider(resolver, sessionIdentity, capture, profile);
    }

    static TextureAtlasDataModelCapture captured(final Fixture fixture) {
        final TextureAtlasDataModelCapture capture = new TextureAtlasDataModelCapture();
        capture.capture(fixture.data);
        return capture;
    }

    static VerifiedMemberResolver resolver(final String version, final boolean includeAtlas) {
        final List<StaticSelector> selectors = new ArrayList<>();
        selectors.add(
                StaticSelector.classSelector("cubism.editor-model.modeling-document.class", internal(Document.class)));
        selectors.add(method(
                "cubism.editor-model.modeling-document.model-source",
                Document.class,
                "modelSource",
                desc(ModelSource.class)));
        selectors.add(method("cubism.editor-model.model-source.guid", ModelSource.class, "guid", desc(Id.class)));
        selectors.add(method("cubism.editor-model.guid.value", Id.class, "value", "()Ljava/lang/String;"));
        if (includeAtlas) {
            selectors.add(
                    StaticSelector.classSelector("cubism.texture-atlas.data-model.class", internal(DataModel.class)));
            selectors.add(method(
                    "cubism.texture-atlas.data-model.document", DataModel.class, "document", desc(Document.class)));
            selectors.add(method(
                    "cubism.texture-atlas.data-model.model-source",
                    DataModel.class,
                    "modelSource",
                    desc(ModelSource.class)));
            selectors.add(method(
                    "cubism.texture-atlas.model-source.texture-manager",
                    ModelSource.class,
                    "textureManager",
                    desc(TextureManager.class)));
            selectors.add(method(
                    "cubism.texture-atlas.texture-manager.images",
                    TextureManager.class,
                    "images",
                    "()Ljava/util/List;"));
            selectors.add(method(
                    "cubism.editor-model.texture-manager.handler",
                    TextureManager.class,
                    "handler",
                    desc(TextureManagerHandler.class)));
            selectors.add(method(
                    "cubism.texture-atlas.texture-manager-handler.drawable-uses",
                    TextureManagerHandler.class,
                    "drawableUses",
                    "()Ljava/util/HashMap;"));
            selectors.add(method(
                    "cubism.texture-atlas.texture-manager.atlases",
                    TextureManager.class,
                    "atlases",
                    "()Ljava/util/List;"));
            selectors.add(StaticSelector.field(
                    "cubism.texture-atlas.texture-input-relink.helper-instance",
                    internal(TextureInputRelinkHelper.class),
                    "INSTANCE",
                    type(TextureInputRelinkHelper.class),
                    StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
            selectors.add(method(
                    "cubism.texture-atlas.texture-input-relink.rebuild",
                    TextureInputRelinkHelper.class,
                    "rebuild",
                    "(" + type(ModelSource.class) + ")V"));
            selectors.add(StaticSelector.staticMethod(
                    "cubism.editor-model.model-source.verify",
                    internal(ModelSource.class),
                    "verifyDefault",
                    "(" + type(ModelSource.class) + "ZLjava/lang/Object;ILjava/lang/Object;)V",
                    StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
            selectors.add(method(
                    "cubism.texture-atlas.texture-manager.change-input-to-atlas",
                    TextureManager.class,
                    "changeInputToAtlas",
                    "()V"));
            selectors.add(method(
                    "cubism.texture-atlas.texture-manager.reinit-input", TextureManager.class, "reinitInput", "()V"));
            selectors.add(StaticSelector.classSelector("cubism.texture-atlas.atlas.class", internal(Atlas.class)));
            selectors.add(StaticSelector.constructor(
                    "cubism.texture-atlas.atlas.create",
                    internal(Atlas.class),
                    "(" + type(ModelSource.class) + "Ljava/lang/String;II)V",
                    StaticSelector.ACCESS_PUBLIC));
            selectors.add(method("cubism.texture-atlas.atlas.name", Atlas.class, "name", "()Ljava/lang/String;"));
            selectors.add(method("cubism.texture-atlas.atlas.width", Atlas.class, "width", "()I"));
            selectors.add(method("cubism.texture-atlas.atlas.height", Atlas.class, "height", "()I"));
            selectors.add(method("cubism.texture-atlas.atlas.entries", Atlas.class, "entries", "()Ljava/util/List;"));
            selectors.add(method(
                    "cubism.texture-atlas.atlas.update-texture",
                    Atlas.class,
                    "updateTexture",
                    "(ZZLjava/lang/Object;)V"));
            selectors.add(StaticSelector.classSelector("cubism.texture-atlas.entry.class", internal(Entry.class)));
            selectors.add(StaticSelector.constructor(
                    "cubism.texture-atlas.entry.create",
                    internal(Entry.class),
                    "(" + type(Atlas.class) + type(Id.class) + type(Affine.class) + ")V",
                    StaticSelector.ACCESS_PUBLIC));
            selectors.add(method("cubism.texture-atlas.entry.image", Entry.class, "image", desc(Image.class)));
            selectors.add(method("cubism.texture-atlas.entry.transform", Entry.class, "transform", desc(Affine.class)));
            selectors.add(StaticSelector.classSelector("cubism.texture-atlas.image.class", internal(Image.class)));
            selectors.add(method("cubism.texture-atlas.image.guid", Image.class, "guid", desc(Id.class)));
            selectors.add(method("cubism.texture-atlas.image.width", Image.class, "width", "()I"));
            selectors.add(method("cubism.texture-atlas.image.height", Image.class, "height", "()I"));
            selectors.add(method(
                    "cubism.texture-atlas.image.local-to-canvas",
                    Image.class,
                    "localToCanvas",
                    "()Ljava/awt/geom/AffineTransform;"));
            selectors.add(StaticSelector.classSelector(
                    "cubism.editor-model.art-mesh-source.class", internal(SourceMesh.class)));
            selectors.add(
                    method("cubism.editor-model.art-mesh-source.positions", SourceMesh.class, "positions", "()[F"));
            selectors.add(method("cubism.editor-model.art-mesh-source.indices", SourceMesh.class, "indices", "()[I"));
            selectors.add(StaticSelector.classSelector("cubism.texture-atlas.affine.class", internal(Affine.class)));
            selectors.add(StaticSelector.constructor(
                    "cubism.texture-atlas.affine.create", internal(Affine.class), "()V", StaticSelector.ACCESS_PUBLIC));
            selectors.add(method("cubism.texture-atlas.affine.translate", Affine.class, "translate", "(FF)V"));
            selectors.add(method(
                    "cubism.editor-model.modeling-document.edit-mode",
                    Document.class,
                    "editMode",
                    desc(EditMode.class)));
            selectors.add(method(
                    "cubism.editor-model.edit-mode.begin",
                    EditMode.class,
                    "beginEdit",
                    "(Ljava/lang/String;)" + type(GroupUndo.class)));
            selectors.add(
                    method("cubism.editor-model.edit-mode.end", EditMode.class, "endEdit", "(ZLjava/lang/Object;)Z"));
            selectors.add(StaticSelector.constructor(
                    "cubism.texture-atlas.undo.create",
                    internal(AtlasUndo.class),
                    "(Ljava/lang/String;" + type(ModelSource.class) + "Ljava/util/List;Ljava/util/List;)V",
                    StaticSelector.ACCESS_PUBLIC));
            selectors.add(method("cubism.texture-atlas.undo.force-redo", AtlasUndo.class, "forceRedo", "()V"));
            selectors.add(method(
                    "cubism.texture-atlas.group-undo.add",
                    GroupUndo.class,
                    "plusAssign",
                    "(" + type(AtlasUndo.class) + ")V"));
            selectors.add(StaticSelector.staticMethod(
                    "cubism.editor-model.app-controller.instance",
                    internal(Host.class),
                    "instance",
                    desc(Host.class),
                    StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
            selectors.add(method(
                    "cubism.editor-model.app-controller.current-document",
                    Host.class,
                    "currentDocument",
                    desc(Document.class)));
            selectors.add(method(
                    "cubism.editor-model.app-controller.complete-pack",
                    Host.class,
                    "completePack",
                    desc(CompletePack.class)));
            selectors.add(StaticSelector.classSelector(
                    "cubism.editor-model.undo-listener.class", internal(UndoListener.class)));
            selectors.add(method(
                    "cubism.editor-model.undo.add-listener",
                    AtlasUndo.class,
                    "addListener",
                    "(" + type(UndoListener.class) + ")Z"));
            selectors.add(method(
                    "cubism.editor-model.model-source.update-instances", ModelSource.class, "updateInstances", "()V"));
            selectors.add(method(
                    "cubism.editor-model.complete-pack.repaint-canvas", CompletePack.class, "repaintCanvas", "(Z)V"));
            selectors.add(
                    method("cubism.editor-model.modeling-document.mark-dirty", Document.class, "markDirty", "()V"));
        }
        return TestVerifiedResolvers.create(
                version,
                VerifiedTextureAtlasSelectorContract.ADAPTER_SLICE_ID,
                includeAtlas
                        ? Set.of(VerifiedTextureAtlasSelectorContract.CAPABILITY_ID)
                        : Set.of("cubism.editor-model.read"),
                selectors,
                Host.class.getClassLoader());
    }

    static StaticSelector method(final String alias, final Class<?> owner, final String name, final String descriptor) {
        return StaticSelector.method(alias, internal(owner), name, descriptor, StaticSelector.ACCESS_PUBLIC);
    }

    static String internal(final Class<?> type) {
        return type.getName().replace('.', '/');
    }

    static String type(final Class<?> type) {
        return "L" + internal(type) + ";";
    }

    static String desc(final Class<?> type) {
        return "()" + type(type);
    }

    public static final class Host {
        private static final Host INSTANCE = new Host();
        static Document document;

        public static Host instance() {
            return INSTANCE;
        }

        public Document currentDocument() {
            return document;
        }

        public CompletePack completePack() {
            return document.completePack;
        }
    }

    public static final class Document {
        final ModelSource source;
        DataModel data;
        final EditMode editMode = new EditMode();
        final CompletePack completePack = new CompletePack(this);
        int dirtyCount;
        int repaintCount;

        Document(final ModelSource source, final DataModel data) {
            this.source = source;
            this.data = data;
        }

        public ModelSource modelSource() {
            return source;
        }

        public EditMode editMode() {
            return editMode;
        }

        public void markDirty() {
            if (data != null && data.failDirty) throw new IllegalStateException("dirty mark failed");
            dirtyCount++;
        }
    }

    public static final class CompletePack {
        private final Document document;

        CompletePack(final Document document) {
            this.document = document;
        }

        public void repaintCanvas(final boolean immediate) {
            if (!immediate) throw new IllegalArgumentException("immediate repaint required");
            document.repaintCount++;
        }
    }

    public static final class EditMode {
        final List<String> edits = new ArrayList<>();
        final List<AtlasUndo> undos = new ArrayList<>();
        final List<Boolean> rollbacks = new ArrayList<>();

        public GroupUndo beginEdit(final String name) {
            edits.add(name);
            return new GroupUndo(undos);
        }

        public boolean endEdit(final boolean rollback, final Object callback) {
            rollbacks.add(rollback);
            if (rollback && !undos.isEmpty()) {
                undos.remove(undos.size() - 1).undo();
            }
            if (!rollback && Host.document.data.failEnd) {
                throw new IllegalStateException("end edit failed");
            }
            return true;
        }

        void undo() {
            undos.get(undos.size() - 1).undo();
        }

        void redo() {
            undos.get(undos.size() - 1).redo();
        }
    }

    public static final class GroupUndo {
        private final List<AtlasUndo> undos;

        GroupUndo(final List<AtlasUndo> undos) {
            this.undos = undos;
        }

        public void plusAssign(final AtlasUndo undo) {
            undos.add(undo);
        }
    }

    @FunctionalInterface
    public interface UndoListener {
        void onUndoRedo();
    }

    public static final class AtlasUndo {
        private final TextureManager textureManager;
        private final DataModel data;
        private final List<Atlas> before;
        private final List<Atlas> after;
        private UndoListener listener;

        public AtlasUndo(
                final String name, final ModelSource source, final List<Atlas> before, final List<Atlas> after) {
            assertEquals("Update TextureAtlas", name);
            assertEquals(source.textureManager.atlases, before);
            this.textureManager = source.textureManager;
            this.data = Host.document.data;
            this.before = List.copyOf(before);
            this.after = List.copyOf(after);
        }

        public boolean addListener(final UndoListener value) {
            if (data.rejectListener) return false;
            listener = value;
            return true;
        }

        public void forceRedo() {
            if (data.failRedo) throw new IllegalStateException("redo failed");
            redo();
        }

        void undo() {
            textureManager.atlases.clear();
            textureManager.atlases.addAll(before);
            if (listener != null) listener.onUndoRedo();
        }

        void redo() {
            textureManager.atlases.clear();
            textureManager.atlases.addAll(after);
            data.applyCount++;
            if (listener != null) listener.onUndoRedo();
        }
    }

    public static final class TextureInputRelinkHelper {
        public static final TextureInputRelinkHelper INSTANCE = new TextureInputRelinkHelper();

        public void rebuild(final ModelSource source) {
            source.semanticRelinkCount++;
        }
    }

    public static final class ModelSource {
        final Id guid = new Id("model-a");
        final TextureManager textureManager = new TextureManager();
        int semanticRelinkCount;
        int verifyCount;
        int refreshCount;

        ModelSource() {}

        ModelSource(final Id guid) {
            this.guid.value = guid.value;
        }

        public static void verifyDefault(
                final ModelSource source,
                final boolean deep,
                final Object verifier,
                final int mask,
                final Object marker) {
            if (!deep || verifier != null || mask != 2 || marker != null) {
                throw new IllegalArgumentException("native Atlas verification defaults required");
            }
            source.verifyCount++;
        }

        public Id guid() {
            return guid;
        }

        public TextureManager textureManager() {
            return textureManager;
        }

        public void updateInstances() {
            refreshCount++;
            if (Host.document.data.failRefresh) {
                throw new IllegalStateException("refresh failed");
            }
        }
    }

    public static final class TextureManager {
        final List<Image> images = new ArrayList<>();
        final List<Atlas> atlases = new ArrayList<>();
        final TextureManagerHandler handler = new TextureManagerHandler();
        int atlasInputChangeCount;
        int modelImageInputChangeCount;
        boolean modelImageMode = true;

        public List<Image> images() {
            return images;
        }

        public TextureManagerHandler handler() {
            return handler;
        }

        public List<Atlas> atlases() {
            return atlases;
        }

        public void changeInputToAtlas() {
            modelImageMode = false;
            atlasInputChangeCount++;
        }

        public void reinitInput() {
            if (modelImageMode) {
                modelImageInputChangeCount++;
            } else {
                changeInputToAtlas();
            }
        }
    }

    public static final class TextureManagerHandler {
        final HashMap<Id, Set<Object>> drawableUses = new HashMap<>();

        public HashMap<Id, Set<Object>> drawableUses() {
            return drawableUses;
        }
    }

    public static final class DataModel {
        final Document document;
        final ModelSource source;
        final List<Atlas> atlases = new ArrayList<>();
        int applyCount;
        String failAtlasName;
        boolean failRedo;
        boolean rejectListener;
        boolean failRefresh;
        boolean failDirty;
        boolean failEnd;
        boolean failTextureGeneration;

        DataModel(final Document document, final ModelSource source) {
            this.document = document;
            this.source = source;
        }

        public Document document() {
            return document;
        }

        public ModelSource modelSource() {
            return source;
        }

        public List<Sheet> sheets() {
            return atlases.stream().map(Sheet::new).toList();
        }

        public void apply(final List<Atlas> staged) {
            atlases.clear();
            atlases.addAll(staged);
            applyCount++;
        }
    }

    public record Sheet(Atlas atlas) {}

    public static final class Atlas {
        final String name;
        final int width;
        final int height;
        final List<Entry> entries = new ArrayList<>();
        boolean textureGenerated;

        public void updateTexture(boolean generate, boolean highQuality, Object progress) {
            if (!generate || !highQuality || progress != null)
                throw new IllegalArgumentException("generation required");
            if (Host.document.data.failTextureGeneration) throw new IllegalStateException("texture generation failed");
            textureGenerated = true;
        }

        public Atlas(final ModelSource source, final String name, final int width, final int height) {
            if (Host.document != null && name.equals(Host.document.data.failAtlasName))
                throw new IllegalStateException("staging failed");
            this.name = name;
            this.width = width;
            this.height = height;
        }

        public String name() {
            return name;
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }

        public List<Entry> entries() {
            return entries;
        }
    }

    public static final class Entry {
        final Atlas atlas;
        final Image image;
        final Affine transform;

        public Entry(final Atlas atlas, final Id imageId, final Affine transform) {
            this.atlas = atlas;
            this.image = Host.document.source.textureManager.images.stream()
                    .filter(value -> value.guid == imageId)
                    .findFirst()
                    .orElseThrow();
            this.transform = new Affine(transform);
        }

        public Image image() {
            return image;
        }

        public Affine transform() {
            return new Affine(transform);
        }
    }

    public static final class Affine extends AffineTransform {
        public Affine() {}

        Affine(final AffineTransform value) {
            super(value);
        }

        public void translate(final float x, final float y) {
            super.translate(x, y);
        }
    }

    public static final class Image {
        final Id guid;
        final int width;
        final int height;

        Image(final String id, final int width, final int height) {
            this.guid = new Id(id);
            this.width = width;
            this.height = height;
        }

        public Id guid() {
            return guid;
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }

        final AffineTransform localToCanvas = new AffineTransform();

        public AffineTransform localToCanvas() {
            return new AffineTransform(localToCanvas);
        }
    }

    public static final class SourceMesh {
        final float[] xy;

        SourceMesh(float[] xy) {
            this.xy = xy.clone();
        }

        public float[] positions() {
            return xy.clone();
        }

        public int[] indices() {
            return new int[] {0, 1, 2, 0, 2, 3};
        }
    }

    public static final class Id {
        String value;

        Id(final String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    static final class Fixture {
        final ModelSource source;
        final Document document;
        final DataModel data;

        Fixture() {
            this(true);
        }

        Fixture(final boolean includeUnused) {
            source = new ModelSource();
            document = new Document(source, null);
            data = new DataModel(document, source);
            document.data = data;
            Host.document = document;
            final Image first = new Image("texture-a", 4, 3);
            final Image second = new Image("texture-b", 2, 2);
            source.textureManager.images.add(first);
            source.textureManager.images.add(second);
            source.textureManager.handler.drawableUses.put(first.guid, Set.of(new Object()));
            source.textureManager.handler.drawableUses.put(second.guid, Set.of(new Object()));
            final Atlas atlas = new Atlas(source, "atlas-a", 16, 8);
            atlas.entries.add(new Entry(atlas, first.guid, new Affine(AffineTransform.getTranslateInstance(1.0, 1.0))));
            atlas.entries.add(
                    new Entry(atlas, second.guid, new Affine(AffineTransform.getTranslateInstance(8.0, 1.0))));
            if (includeUnused) {
                final Image unused = new Image("texture-unused", 1, 1);
                source.textureManager.images.add(unused);
                atlas.entries.add(
                        new Entry(atlas, unused.guid, new Affine(AffineTransform.getTranslateInstance(12.0, 1.0))));
            }
            data.atlases.add(atlas);
            source.textureManager.atlases.add(atlas);
        }

        void replaceAtlasWithSameId() {
            final Atlas replacement = new Atlas(source, "atlas-a", 16, 8);
            replacement.entries.add(new Entry(
                    replacement,
                    source.textureManager.images.get(0).guid,
                    new Affine(AffineTransform.getTranslateInstance(2.0, 1.0))));
            replacement.entries.add(new Entry(
                    replacement,
                    source.textureManager.images.get(1).guid,
                    new Affine(AffineTransform.getTranslateInstance(8.0, 1.0))));
            source.textureManager.atlases.clear();
            source.textureManager.atlases.add(replacement);
        }
    }
}
