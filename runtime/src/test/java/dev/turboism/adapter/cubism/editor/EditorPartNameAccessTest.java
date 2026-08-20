package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.selector.EditorPartBasicSettingsSelectorContract;
import dev.turboism.mapping.verification.selector.EditorPartNameSelectorContract;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.model.Color;
import dev.turboism.sdk.cubism.model.PartId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EditorPartNameAccessTest {

    @ParameterizedTest
    @ValueSource(strings = {"5.2.03", "5.3.02"})
    void readsLocalNameAndFallsBackToIdText(final String cubismVersion) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var access = new EditorBackedCubismModelAccess(resolver(true, cubismVersion), "session-a");
        final var part = access.active().parts().find(new PartId("PartClip"));

        assertEquals("Clipping Part", part.name());
        fixture.partSource.localName = "";
        assertEquals("PartClip", part.name());
        assertThrows(UnsupportedOperationException.class, part::parentId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.2.03", "5.3.02"})
    void writesAuthoringNameWithUndoDirtyRefreshAndNoChangeElision(final String cubismVersion) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var access = new EditorBackedCubismModelAccess(resolver(true, cubismVersion), "session-a");
        final var part = access.active().parts().find(new PartId("PartClip"));

        part.setName("Renamed Part");

        assertEquals("Renamed Part", fixture.partSource.localName);
        assertEquals(1, fixture.editMode.edits.size());
        assertEquals(1, fixture.source.updateCount);
        assertEquals(1, fixture.pack.partRefreshCount);
        assertEquals(1, fixture.pack.repaintCount);
        assertEquals(true, fixture.document.dirty);

        part.setName("Renamed Part");
        assertEquals(1, fixture.editMode.edits.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.2.03", "5.3.02"})
    void readsAndWritesBasicSettingsAndModelMetadata(final String cubismVersion) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var access = new EditorBackedCubismModelAccess(resolver(true, cubismVersion), "session-a");
        final var model = access.active();
        final var part = model.parts().find(new PartId("PartClip"));

        assertEquals("Fixture Model", model.name());
        assertEquals(Optional.of("Clipping Part"), part.shortName());
        assertEquals(true, part.visible());
        assertEquals(false, part.locked());
        assertEquals(true, part.visibleInHierarchy());
        assertEquals(false, part.lockedInHierarchy());
        assertEquals(Optional.of(new Color(0.1F, 0.2F, 0.3F, 0.4F)), part.editColor());
        assertEquals(false, part.sketch());
        assertEquals(0, part.defaultOrder());

        part.setShortName(Optional.empty());
        part.setVisible(false);
        part.setLocked(true);
        part.setEditColor(Optional.of(new Color(0.5F, 0.6F, 0.7F, 0.8F)));
        part.setSketch(true);
        part.setDefaultOrder(23);

        assertEquals(Optional.empty(), part.shortName());
        assertEquals("PartClip", part.name());
        assertEquals(false, part.visible());
        assertEquals(true, part.locked());
        assertEquals(false, part.visibleInHierarchy());
        assertEquals(true, part.lockedInHierarchy());
        assertEquals(Optional.of(new Color(0.5F, 0.6F, 0.7F, 0.8F)), part.editColor());
        assertEquals(true, part.sketch());
        assertEquals(23, part.defaultOrder());
        assertEquals(6, fixture.editMode.edits.size());
        assertEquals(6, fixture.source.hierarchyUpdateCount);
        assertEquals(6, fixture.source.updateCount);

        part.setEditColor(Optional.empty());
        assertEquals(Optional.empty(), part.editColor());
        model.update();
        assertEquals(8, fixture.source.updateCount);
    }

    @Test
    void rejectsBlankNameAndStaleSameIdWrite() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var access = new EditorBackedCubismModelAccess(resolver(true), "session-a");
        final var part = access.active().parts().find(new PartId("PartClip"));

        assertThrows(IllegalArgumentException.class, () -> part.setName("  "));
        fixture.replacePartWithSameId();
        assertThrows(IllegalStateException.class, () -> part.setName("Renamed Part"));
        assertEquals(0, fixture.editMode.edits.size());
    }

    @Test
    void sameIdReplacementMakesNameReferenceStale() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var access = new EditorBackedCubismModelAccess(resolver(true), "session-a");
        final var part = access.active().parts().find(new PartId("PartClip"));

        fixture.replacePartWithSameId();

        assertThrows(IllegalStateException.class, part::name);
    }

    @Test
    void resolverWithoutNameCapabilityFailsClosedBeforeNameSelectorUse() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var access = new EditorBackedCubismModelAccess(resolver(false), "session-a");

        assertThrows(UnsupportedOperationException.class, () -> access.active().parts());
    }

    private static VerifiedMemberResolver resolver(final boolean includeNameCapability) {
        return resolver(includeNameCapability, "5.3.02");
    }

    private static VerifiedMemberResolver resolver(
        final boolean includeNameCapability,
        final String cubismVersion
    ) {
        final List<StaticSelector> selectors = new ArrayList<>();
        selectors.add(StaticSelector.classSelector("cubism.editor-model.app-controller.class", internal(Host.class)));
        selectors.add(StaticSelector.staticMethod(
            "cubism.editor-model.app-controller.instance", internal(Host.class), "instance",
            desc(Host.class), StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC
        ));
        selectors.add(method("cubism.editor-model.app-controller.current-document", Host.class, "currentDocument", desc(Document.class)));
        selectors.add(method("cubism.editor-model.app-controller.complete-pack", Host.class, "completePack", desc(CompletePack.class)));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.modeling-document.class", internal(Document.class)));
        selectors.add(method("cubism.editor-model.modeling-document.model-source", Document.class, "modelSource", desc(ModelSource.class)));
        selectors.add(method("cubism.editor-model.model-source.guid", ModelSource.class, "guid", desc(Id.class)));
        selectors.add(method("cubism.editor-model.model-source.name", ModelSource.class, "name", "()Ljava/lang/String;"));
        selectors.add(method("cubism.editor-model.model-source.current-instance", ModelSource.class, "currentInstance", desc(Model.class)));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.model.class", internal(Model.class)));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.guid.class", internal(Id.class)));
        selectors.add(method("cubism.editor-model.guid.value", Id.class, "value", "()Ljava/lang/String;"));
        selectors.add(method("cubism.editor-model.model-source.parts", ModelSource.class, "parts", "()Ljava/util/List;"));
        selectors.add(method("cubism.editor-model.model.parts", Model.class, "parts", "()Ljava/util/List;"));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.part.class", internal(HostPart.class)));
        selectors.add(method("cubism.editor-model.part.source", HostPart.class, "source", desc(PartSource.class)));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.part-source.class", internal(PartSource.class)));
        selectors.add(method("cubism.editor-model.part-source.id", PartSource.class, "id", desc(Id.class)));
        selectors.add(method("cubism.editor-model.part-id.value", Id.class, "value", "()Ljava/lang/String;"));
        if (includeNameCapability) {
            selectors.add(method("cubism.editor-model.modeling-document.edit-mode", Document.class, "editMode", desc(EditMode.class)));
            selectors.add(method("cubism.editor-model.modeling-document.mark-dirty", Document.class, "markDirty", "()V"));
            selectors.add(method("cubism.editor-model.edit-mode.begin", EditMode.class, "begin", "(Ljava/lang/String;)" + type(GroupUndo.class)));
            selectors.add(method("cubism.editor-model.edit-mode.end", EditMode.class, "end", "(ZLjava/lang/Object;)V"));
            selectors.add(method("cubism.editor-model.undo.add", GroupUndo.class, "add", "(" + type(Undo.class) + "Z)Z"));
            selectors.add(method("cubism.editor-model.undo.add-listener", Undo.class, "addListener", "(" + type(Listener.class) + ")Z"));
            selectors.add(StaticSelector.classSelector("cubism.editor-model.undo-listener.class", internal(Listener.class)));
            selectors.add(method("cubism.editor-model.model-source.update-instances", ModelSource.class, "updateInstances", "()V"));
            selectors.add(method("cubism.editor-model.model-source.update-visible-lock-hierarchy", ModelSource.class, "updateVisibleAndLockHierarchy", "()V"));
            selectors.add(method("cubism.editor-model.part-source.local-name", PartSource.class, "localName", "()Ljava/lang/String;"));
            selectors.add(method("cubism.editor-model.part-source.set-local-name", PartSource.class, "setLocalName", "(Ljava/lang/String;)V"));
            selectors.add(method("cubism.editor-model.part-source.default-order", PartSource.class, "defaultOrder", "()I"));
            selectors.add(method("cubism.editor-model.part-source.set-default-order", PartSource.class, "setDefaultOrder", "(I)V"));
            selectors.add(method("cubism.editor-model.part-source.sketch", PartSource.class, "sketch", "()Z"));
            selectors.add(method("cubism.editor-model.part-source.set-sketch", PartSource.class, "setSketch", "(Z)V"));
            selectors.add(method("cubism.editor-model.part-source.edit-color", PartSource.class, "editColor", desc(HostColor.class)));
            selectors.add(method("cubism.editor-model.part-source.set-edit-color", PartSource.class, "setEditColor", "(" + type(HostColor.class) + ")V"));
            selectors.add(method("cubism.editor-model.part-source.create-undo-for-basic-settings", PartSource.class, "basicUndo", "(Ljava/lang/String;)" + type(Undo.class)));
            selectors.add(method("cubism.editor-model.parameter-controllable-source.visible", PartSource.class, "visible", "()Z"));
            selectors.add(method("cubism.editor-model.parameter-controllable-source.set-visible", PartSource.class, "setVisible", "(Z)V"));
            selectors.add(method("cubism.editor-model.parameter-controllable-source.locked", PartSource.class, "locked", "()Z"));
            selectors.add(method("cubism.editor-model.parameter-controllable-source.set-locked", PartSource.class, "setLocked", "(Z)V"));
            selectors.add(method("cubism.editor-model.parameter-controllable-source.visible-in-hierarchy", PartSource.class, "visibleInHierarchy", "()Z"));
            selectors.add(method("cubism.editor-model.parameter-controllable-source.locked-in-hierarchy", PartSource.class, "lockedInHierarchy", "()Z"));
            selectors.add(StaticSelector.classSelector("cubism.editor-model.color.class", internal(HostColor.class)));
            selectors.add(StaticSelector.constructor("cubism.editor-model.color.create", internal(HostColor.class), "(FFFF)V", StaticSelector.ACCESS_PUBLIC));
            selectors.add(method("cubism.editor-model.color.red", HostColor.class, "red", "()F"));
            selectors.add(method("cubism.editor-model.color.green", HostColor.class, "green", "()F"));
            selectors.add(method("cubism.editor-model.color.blue", HostColor.class, "blue", "()F"));
            selectors.add(method("cubism.editor-model.color.alpha", HostColor.class, "alpha", "()F"));
            selectors.add(method("cubism.editor-model.part-source.handler", PartSource.class, "handler", desc(PartHandler.class)));
            selectors.add(StaticSelector.classSelector("cubism.editor-model.part-handler.class", internal(PartHandler.class)));
            selectors.add(method("cubism.editor-model.part-handler.create-undo-for-all-edit", PartHandler.class, "undo", "(Ljava/lang/String;)" + type(Undo.class)));
            selectors.add(method("cubism.editor-model.complete-pack.update-part-palette", CompletePack.class, "updateParts", "(Z)V"));
            selectors.add(method("cubism.editor-model.complete-pack.repaint-canvas", CompletePack.class, "repaint", "(Z)V"));
        }
        return TestVerifiedResolvers.create(
            cubismVersion,
            "adapter.editor-model.readwrite",
            includeNameCapability
                ? java.util.Set.of(
                    EditorPartNameSelectorContract.CAPABILITY_ID,
                    EditorPartNameSelectorContract.WRITE_CAPABILITY_ID,
                    EditorPartBasicSettingsSelectorContract.READ_CAPABILITY_ID,
                    EditorPartBasicSettingsSelectorContract.WRITE_CAPABILITY_ID
                )
                : java.util.Set.of("cubism.editor-model.read"),
            selectors,
            Host.class.getClassLoader()
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

    public static final class Host {
        private static final Host INSTANCE = new Host();
        static Document document;
        public static Host instance() { return INSTANCE; }
        public Document currentDocument() { return document; }
        public CompletePack completePack() { return document.pack; }
    }

    public static final class Document {
        final ModelSource source;
        final EditMode editMode = new EditMode();
        final CompletePack pack = new CompletePack();
        boolean dirty;
        Document(final ModelSource source) { this.source = source; }
        public ModelSource modelSource() { return source; }
        public EditMode editMode() { return editMode; }
        public void markDirty() { dirty = true; }
    }

    public static final class ModelSource {
        final Id guid = new Id("model-a");
        final List<PartSource> sources = new ArrayList<>();
        Model model;
        int updateCount;
        int hierarchyUpdateCount;
        public String name() { return "Fixture Model"; }
        public Id guid() { return guid; }
        public Model currentInstance() { return model; }
        public List<PartSource> parts() { return sources; }
        public void updateVisibleAndLockHierarchy() {
            hierarchyUpdateCount++;
            sources.forEach(source -> {
                source.visibleInHierarchy = source.visible;
                source.lockedInHierarchy = source.locked;
            });
        }
        public void updateInstances() { updateCount++; }
    }

    public static final class Model {
        final List<HostPart> parts = new ArrayList<>();
        public List<HostPart> parts() { return parts; }
    }

    public static final class HostPart {
        final PartSource source;
        HostPart(final PartSource source) { this.source = source; }
        public PartSource source() { return source; }
    }

    public static final class PartSource {
        final Id id;
        final PartHandler handler = new PartHandler();
        String localName;
        boolean visible = true;
        boolean locked;
        boolean visibleInHierarchy = true;
        boolean lockedInHierarchy;
        boolean sketch;
        int defaultOrder;
        HostColor editColor = new HostColor(0.1F, 0.2F, 0.3F, 0.4F);
        PartSource(final String id, final String localName) {
            this.id = new Id(id);
            this.localName = localName;
        }
        public Id id() { return id; }
        public String localName() { return localName; }
        public void setLocalName(final String value) { localName = value; }
        public boolean visible() { return visible; }
        public void setVisible(final boolean value) { visible = value; }
        public boolean locked() { return locked; }
        public void setLocked(final boolean value) { locked = value; }
        public boolean visibleInHierarchy() { return visibleInHierarchy; }
        public boolean lockedInHierarchy() { return lockedInHierarchy; }
        public boolean sketch() { return sketch; }
        public void setSketch(final boolean value) { sketch = value; }
        public int defaultOrder() { return defaultOrder; }
        public void setDefaultOrder(final int value) { defaultOrder = value; }
        public HostColor editColor() { return editColor; }
        public void setEditColor(final HostColor value) { editColor = value; }
        public Undo basicUndo(final String name) { return new Undo(); }
        public PartHandler handler() { return handler; }
    }

    public record HostColor(float red, float green, float blue, float alpha) { }

    public static final class PartHandler {
        public Undo undo(final String name) { return new Undo(); }
    }

    public static final class EditMode {
        final List<Undo> edits = new ArrayList<>();
        public GroupUndo begin(final String name) { return new GroupUndo(edits); }
        public void end(final boolean abort, final Object ignored) { }
    }

    public static final class GroupUndo {
        final List<Undo> edits;
        GroupUndo(final List<Undo> edits) { this.edits = edits; }
        public boolean add(final Undo undo, final boolean significant) { edits.add(undo); return true; }
    }

    public static final class Undo {
        public boolean addListener(final Listener listener) { return true; }
    }

    @FunctionalInterface public interface Listener { void changed(Object ignored); }

    public static final class CompletePack {
        int partRefreshCount;
        int repaintCount;
        public void updateParts(final boolean immediate) { partRefreshCount++; }
        public void repaint(final boolean immediate) { repaintCount++; }
    }

    public static final class Id {
        final String value;
        Id(final String value) { this.value = value; }
        public String value() { return value; }
    }

    private static final class Fixture {
        final ModelSource source = new ModelSource();
        final PartSource partSource = new PartSource("PartClip", "Clipping Part");
        final Document document;
        final EditMode editMode;
        final CompletePack pack;
        Fixture() {
            source.sources.add(partSource);
            source.model = new Model();
            source.model.parts.add(new HostPart(partSource));
            document = new Document(source);
            editMode = document.editMode;
            pack = document.pack;
        }
        void replacePartWithSameId() {
            final PartSource replacement = new PartSource("PartClip", "Replacement");
            source.sources.clear();
            source.sources.add(replacement);
            source.model.parts.clear();
            source.model.parts.add(new HostPart(replacement));
        }
    }
}
