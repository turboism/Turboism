package dev.turboism.adapter.cubism.textureatlas;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutPlan;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPlacement;
import org.junit.jupiter.api.Test;

import java.awt.geom.AffineTransform;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifiedCubism5302TextureAtlasLayoutProviderTest {

    @Test
    void projectsCurrentAtlasAndAppliesACompletePlanThroughNativeAtlasTransaction() {
        final Fixture fixture = new Fixture();
        final VerifiedCubism5302TextureAtlasLayoutProvider provider = provider(
            resolver("5.3.02", true), "session-a", fixture
        );

        final TextureAtlasAuthoringState current = provider.current().orElseThrow();
        assertEquals("session-a", current.documentId());
        assertEquals("model-a", current.modelId());
        assertEquals("atlas-a", current.atlasId());
        assertEquals(2, current.items().size());
        assertEquals(8, current.currentPlan().placements().get(1).x());

        final TextureAtlasLayoutPlan plan = new TextureAtlasLayoutPlan(16, 8, 2, List.of(
            new TextureAtlasPlacement("texture-a", 0, 2, 1, 4, 3, false),
            new TextureAtlasPlacement("texture-b", 1, 5, 2, 2, 2, false)
        ));
        assertEquals(TextureAtlasLayoutProvider.ApplyOutcome.APPLIED, provider.apply(current, plan));

        assertEquals(List.of("Turboism Atlas 1", "Turboism Atlas 2"), fixture.source.textureManager.atlases.stream().map(Atlas::name).toList());
        assertEquals(List.of("texture-a"), fixture.source.textureManager.atlases.get(0).entries.stream().map(entry -> entry.image.guid.value).toList());
        assertEquals(List.of("texture-b"), fixture.source.textureManager.atlases.get(1).entries.stream().map(entry -> entry.image.guid.value).toList());
        assertEquals(AffineTransform.getTranslateInstance(2.0, 1.0), fixture.source.textureManager.atlases.get(0).entries.get(0).transform);
        assertEquals(AffineTransform.getTranslateInstance(5.0, 2.0), fixture.source.textureManager.atlases.get(1).entries.get(0).transform);
        assertEquals(List.of("atlas-a"), fixture.data.atlases.stream().map(Atlas::name).toList());
        assertEquals(1, fixture.data.applyCount);
        assertEquals(List.of("Update TextureAtlas"), fixture.document.editMode.edits);
        assertEquals(1, fixture.document.editMode.undos.size());

        final TextureAtlasAuthoringState updated = provider.current().orElseThrow();
        assertEquals(current.revision() + 1, updated.revision());
        assertEquals(plan, updated.currentPlan());
    }

    @Test
    void rejectsStalePlanningStateAndUnsupportedIdentityBeforeMutation() {
        final Fixture fixture = new Fixture();
        final VerifiedCubism5302TextureAtlasLayoutProvider provider = provider(
            resolver("5.3.02", true), "session-a", fixture
        );
        final TextureAtlasAuthoringState stale = provider.current().orElseThrow();
        fixture.replaceAtlasWithSameId();

        assertEquals(
            TextureAtlasLayoutProvider.ApplyOutcome.REJECTED,
            provider.apply(stale, stale.currentPlan())
        );
        assertEquals(0, fixture.data.applyCount);
        assertFalse(provider(resolver("5.2.0", true), "session-a", fixture).current().isPresent());
        assertFalse(new VerifiedCubism520TextureAtlasLayoutProvider(
            resolver("5.3.02", true), "session-a", captured(fixture)
        ).current().isPresent());
        assertFalse(provider(resolver("5.3.02", false), "session-a", fixture).current().isPresent());
    }

    @Test
    void stagedConstructionFailureLeavesEditorStateUntouched() {
        final Fixture fixture = new Fixture();
        fixture.data.failAtlasName = "Turboism Atlas 2";
        final VerifiedCubism5302TextureAtlasLayoutProvider provider = provider(
            resolver("5.3.02", true), "session-a", fixture
        );
        final TextureAtlasAuthoringState current = provider.current().orElseThrow();
        final TextureAtlasLayoutPlan plan = new TextureAtlasLayoutPlan(16, 8, 2, List.of(
            new TextureAtlasPlacement("texture-a", 0, 2, 1, 4, 3, false),
            new TextureAtlasPlacement("texture-b", 1, 5, 2, 2, 2, false)
        ));

        assertThrows(RuntimeException.class, () -> provider.apply(current, plan));
        assertEquals(List.of("atlas-a"), fixture.source.textureManager.atlases.stream().map(Atlas::name).toList());
        assertEquals(0, fixture.data.applyCount);
        assertTrue(fixture.document.editMode.edits.isEmpty());
        assertTrue(fixture.document.editMode.undos.isEmpty());
    }

    private static VerifiedCubism5302TextureAtlasLayoutProvider provider(
        final VerifiedMemberResolver resolver,
        final String sessionIdentity,
        final Fixture fixture
    ) {
        final TextureAtlasDataModelCapture capture = new TextureAtlasDataModelCapture();
        capture.capture(fixture.data);
        return new VerifiedCubism5302TextureAtlasLayoutProvider(resolver, sessionIdentity, capture);
    }

    private static TextureAtlasDataModelCapture captured(final Fixture fixture) {
        final TextureAtlasDataModelCapture capture = new TextureAtlasDataModelCapture();
        capture.capture(fixture.data);
        return capture;
    }

    private static VerifiedMemberResolver resolver(final String version, final boolean includeAtlas) {
        final List<StaticSelector> selectors = new ArrayList<>();
        selectors.add(StaticSelector.classSelector("cubism.editor-model.modeling-document.class", internal(Document.class)));
        selectors.add(method("cubism.editor-model.model-source.guid", ModelSource.class, "guid", desc(Id.class)));
        selectors.add(method("cubism.editor-model.guid.value", Id.class, "value", "()Ljava/lang/String;"));
        if (includeAtlas) {
            selectors.add(StaticSelector.classSelector("cubism.texture-atlas.data-model.class", internal(DataModel.class)));
            selectors.add(method("cubism.texture-atlas.data-model.document", DataModel.class, "document", desc(Document.class)));
            selectors.add(method("cubism.texture-atlas.data-model.model-source", DataModel.class, "modelSource", desc(ModelSource.class)));
            selectors.add(method("cubism.texture-atlas.model-source.texture-manager", ModelSource.class, "textureManager", desc(TextureManager.class)));
            selectors.add(method("cubism.texture-atlas.texture-manager.images", TextureManager.class, "images", "()Ljava/util/List;"));
            selectors.add(method("cubism.texture-atlas.texture-manager.atlases", TextureManager.class, "atlases", "()Ljava/util/List;"));
            selectors.add(StaticSelector.classSelector("cubism.texture-atlas.atlas.class", internal(Atlas.class)));
            selectors.add(StaticSelector.constructor(
                "cubism.texture-atlas.atlas.create",
                internal(Atlas.class),
                "(" + type(ModelSource.class) + "Ljava/lang/String;II)V",
                StaticSelector.ACCESS_PUBLIC
            ));
            selectors.add(method("cubism.texture-atlas.atlas.name", Atlas.class, "name", "()Ljava/lang/String;"));
            selectors.add(method("cubism.texture-atlas.atlas.width", Atlas.class, "width", "()I"));
            selectors.add(method("cubism.texture-atlas.atlas.height", Atlas.class, "height", "()I"));
            selectors.add(method("cubism.texture-atlas.atlas.entries", Atlas.class, "entries", "()Ljava/util/List;"));
            selectors.add(StaticSelector.classSelector("cubism.texture-atlas.entry.class", internal(Entry.class)));
            selectors.add(StaticSelector.constructor("cubism.texture-atlas.entry.create", internal(Entry.class), "(" + type(Atlas.class) + type(Id.class) + type(Affine.class) + ")V", StaticSelector.ACCESS_PUBLIC));
            selectors.add(method("cubism.texture-atlas.entry.image", Entry.class, "image", desc(Image.class)));
            selectors.add(method("cubism.texture-atlas.entry.transform", Entry.class, "transform", desc(Affine.class)));
            selectors.add(StaticSelector.classSelector("cubism.texture-atlas.image.class", internal(Image.class)));
            selectors.add(method("cubism.texture-atlas.image.guid", Image.class, "guid", desc(Id.class)));
            selectors.add(method("cubism.texture-atlas.image.width", Image.class, "width", "()I"));
            selectors.add(method("cubism.texture-atlas.image.height", Image.class, "height", "()I"));
            selectors.add(StaticSelector.classSelector("cubism.texture-atlas.affine.class", internal(Affine.class)));
            selectors.add(StaticSelector.constructor("cubism.texture-atlas.affine.create", internal(Affine.class), "()V", StaticSelector.ACCESS_PUBLIC));
            selectors.add(method("cubism.texture-atlas.affine.translate", Affine.class, "translate", "(FF)V"));
            selectors.add(method("cubism.editor-model.modeling-document.edit-mode", Document.class, "editMode", desc(EditMode.class)));
            selectors.add(method("cubism.editor-model.edit-mode.begin", EditMode.class, "beginEdit", "(Ljava/lang/String;)" + type(GroupUndo.class)));
            selectors.add(StaticSelector.constructor(
                "cubism.texture-atlas.undo.create",
                internal(AtlasUndo.class),
                "(Ljava/lang/String;" + type(ModelSource.class) + "Ljava/util/List;Ljava/util/List;)V",
                StaticSelector.ACCESS_PUBLIC
            ));
            selectors.add(method("cubism.texture-atlas.undo.force-redo", AtlasUndo.class, "forceRedo", "()V"));
            selectors.add(method("cubism.texture-atlas.group-undo.add", GroupUndo.class, "plusAssign", "(" + type(AtlasUndo.class) + ")V"));
        }
        return TestVerifiedResolvers.create(
            version,
            VerifiedCubism5302TextureAtlasSelectorContract.ADAPTER_SLICE_ID,
            includeAtlas ? Set.of(VerifiedCubism5302TextureAtlasSelectorContract.CAPABILITY_ID) : Set.of("cubism.editor-model.read"),
            selectors,
            Host.class.getClassLoader()
        );
    }

    private static StaticSelector method(final String alias, final Class<?> owner, final String name, final String descriptor) {
        return StaticSelector.method(alias, internal(owner), name, descriptor, StaticSelector.ACCESS_PUBLIC);
    }

    private static String internal(final Class<?> type) { return type.getName().replace('.', '/'); }
    private static String type(final Class<?> type) { return "L" + internal(type) + ";"; }
    private static String desc(final Class<?> type) { return "()" + type(type); }

    public static final class Host {
        private static final Host INSTANCE = new Host();
        static Document document;
        public static Host instance() { return INSTANCE; }
        public Document currentDocument() { return document; }
    }

    public static final class Document {
        final ModelSource source;
        DataModel data;
        final EditMode editMode = new EditMode();
        int dirtyCount;
        int refreshCount;
        Document(final ModelSource source, final DataModel data) { this.source = source; this.data = data; }
        public EditMode editMode() { return editMode; }
    }

    public static final class EditMode {
        final List<String> edits = new ArrayList<>();
        final List<AtlasUndo> undos = new ArrayList<>();
        public GroupUndo beginEdit(final String name) {
            edits.add(name);
            return new GroupUndo(undos);
        }
    }

    public static final class GroupUndo {
        private final List<AtlasUndo> undos;
        GroupUndo(final List<AtlasUndo> undos) { this.undos = undos; }
        public void plusAssign(final AtlasUndo undo) { undos.add(undo); }
    }

    public static final class AtlasUndo {
        private final TextureManager textureManager;
        private final DataModel data;
        private final List<Atlas> after;
        public AtlasUndo(
            final String name,
            final ModelSource source,
            final List<Atlas> before,
            final List<Atlas> after
        ) {
            assertEquals("Update TextureAtlas", name);
            assertEquals(source.textureManager.atlases, before);
            this.textureManager = source.textureManager;
            this.data = Host.document.data;
            this.after = List.copyOf(after);
        }
        public void forceRedo() {
            textureManager.atlases.clear();
            textureManager.atlases.addAll(after);
            data.applyCount++;
        }
    }

    public static final class ModelSource {
        final Id guid = new Id("model-a");
        final TextureManager textureManager = new TextureManager();
        public Id guid() { return guid; }
        public TextureManager textureManager() { return textureManager; }
    }

    public static final class TextureManager {
        final List<Image> images = new ArrayList<>();
        final List<Atlas> atlases = new ArrayList<>();
        public List<Image> images() { return images; }
        public List<Atlas> atlases() { return atlases; }
    }

    public static final class DataModel {
        final Document document;
        final ModelSource source;
        final List<Atlas> atlases = new ArrayList<>();
        int applyCount;
        String failAtlasName;
        DataModel(final Document document, final ModelSource source) {
            this.document = document;
            this.source = source;
        }
        public Document document() { return document; }
        public ModelSource modelSource() { return source; }
        public List<Sheet> sheets() { return atlases.stream().map(Sheet::new).toList(); }
        public void apply(final List<Atlas> staged) { atlases.clear(); atlases.addAll(staged); applyCount++; }
    }

    public record Sheet(Atlas atlas) { }

    public static final class Atlas {
        final String name;
        final int width;
        final int height;
        final List<Entry> entries = new ArrayList<>();
        public Atlas(final ModelSource source, final String name, final int width, final int height) {
            if (Host.document != null && name.equals(Host.document.data.failAtlasName)) throw new IllegalStateException("staging failed");
            this.name = name; this.width = width; this.height = height;
        }
        public String name() { return name; }
        public int width() { return width; }
        public int height() { return height; }
        public List<Entry> entries() { return entries; }
    }

    public static final class Entry {
        final Atlas atlas;
        final Image image;
        final Affine transform;
        public Entry(final Atlas atlas, final Id imageId, final Affine transform) {
            this.atlas = atlas;
            this.image = Host.document.source.textureManager.images.stream()
                .filter(value -> value.guid == imageId).findFirst().orElseThrow();
            this.transform = new Affine(transform);
            atlas.entries.add(this);
        }
        public Image image() { return image; }
        public Affine transform() { return new Affine(transform); }
    }

    public static final class Affine extends AffineTransform {
        public Affine() { }
        Affine(final AffineTransform value) { super(value); }
        public void translate(final float x, final float y) { super.translate(x, y); }
    }

    public static final class Image {
        final Id guid;
        final int width;
        final int height;
        Image(final String id, final int width, final int height) { this.guid = new Id(id); this.width = width; this.height = height; }
        public Id guid() { return guid; }
        public int width() { return width; }
        public int height() { return height; }
    }

    public static final class Id {
        final String value;
        Id(final String value) { this.value = value; }
        public String value() { return value; }
    }

    private static final class Fixture {
        final ModelSource source = new ModelSource();
        final Document document;
        final DataModel data;
        Fixture() {
            document = new Document(source, null);
            data = new DataModel(document, source);
            document.data = data;
            Host.document = document;
            final Image first = new Image("texture-a", 4, 3);
            final Image second = new Image("texture-b", 2, 2);
            source.textureManager.images.add(first);
            source.textureManager.images.add(second);
            final Atlas atlas = new Atlas(source, "atlas-a", 16, 8);
            new Entry(atlas, first.guid, new Affine(AffineTransform.getTranslateInstance(1.0, 1.0)));
            new Entry(atlas, second.guid, new Affine(AffineTransform.getTranslateInstance(8.0, 1.0)));
            data.atlases.add(atlas);
            source.textureManager.atlases.add(atlas);
        }
        void replaceAtlasWithSameId() {
            final Atlas replacement = new Atlas(source, "atlas-a", 16, 8);
            new Entry(replacement, source.textureManager.images.get(0).guid, new Affine(AffineTransform.getTranslateInstance(2.0, 1.0)));
            new Entry(replacement, source.textureManager.images.get(1).guid, new Affine(AffineTransform.getTranslateInstance(8.0, 1.0)));
            source.textureManager.atlases.clear();
            source.textureManager.atlases.add(replacement);
        }
    }
}
