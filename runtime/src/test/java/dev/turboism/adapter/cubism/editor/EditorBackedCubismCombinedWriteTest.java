package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.id.ParameterId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorBackedCubismCombinedWriteTest {

    @AfterEach
    void clearHost() {
        Host.currentDocument = null;
        Host.mainFrame = null;
    }

    @Test
    void explicitPairingAndUnpairingUseOneUndoableStructuralTransaction() {
        Fixture fixture = new Fixture();
        Host.install(fixture);
        var access = new EditorBackedCubismModelAccess(resolver(true), "session-a");
        var first = access.active().parameters().find(new ParameterId("ParamA"));
        var second = access.active().parameters().find(new ParameterId("ParamB"));

        assertEquals(Optional.empty(), first.combinedWith());
        assertEquals(Optional.empty(), second.combinedWith());

        first.combineWith(second.id());

        assertEquals(Optional.of(second.id()), first.combinedWith());
        assertEquals(Optional.of(first.id()), second.combinedWith());
        assertTrue(first.combined().orElseThrow());
        assertFalse(second.combined().orElseThrow());
        assertEquals(List.of("ParamA", "ParamB", "ParamC"), fixture.group.ids());
        assertEquals(1, fixture.editMode.beginCalls);
        assertEquals(1, fixture.editMode.committedEdits);
        assertEquals(3, fixture.editMode.committed.edits.size());
        assertEquals(1, fixture.operation.refreshes);

        fixture.editMode.undo();
        assertEquals(Optional.empty(), first.combinedWith());
        assertEquals(Optional.empty(), second.combinedWith());

        fixture.editMode.redo();
        assertEquals(Optional.of(second.id()), first.combinedWith());
        assertEquals(Optional.of(first.id()), second.combinedWith());
        assertEquals(3, fixture.operation.refreshes);

        second.uncombine();
        assertEquals(Optional.empty(), first.combinedWith());
        assertEquals(Optional.empty(), second.combinedWith());
        assertEquals(2, fixture.editMode.committedEdits);
    }

    @Test
    void pairingReordersWithinOneParentButRejectsInvalidOrOccupiedPairsBeforeEditing() {
        Fixture fixture = new Fixture();
        Host.install(fixture);
        var access = new EditorBackedCubismModelAccess(resolver(true), "session-a");
        var first = access.active().parameters().find(new ParameterId("ParamA"));
        var second = access.active().parameters().find(new ParameterId("ParamB"));
        var third = access.active().parameters().find(new ParameterId("ParamC"));

        first.combineWith(third.id());
        assertEquals(List.of("ParamA", "ParamC", "ParamB"), fixture.group.ids());
        assertEquals(Optional.of(third.id()), first.combinedWith());
        assertEquals(Optional.of(first.id()), third.combinedWith());

        assertThrows(IllegalArgumentException.class, () -> first.combineWith(first.id()));
        assertThrows(IllegalStateException.class, () -> second.combineWith(third.id()));
        assertEquals(1, fixture.editMode.beginCalls);

        Fixture otherGroupFixture = new Fixture();
        otherGroupFixture.moveThirdToOtherGroup();
        Host.install(otherGroupFixture);
        var otherAccess = new EditorBackedCubismModelAccess(resolver(true), "session-b");
        var otherFirst = otherAccess.active().parameters().find(new ParameterId("ParamA"));
        assertThrows(
            IllegalStateException.class,
            () -> otherFirst.combineWith(new ParameterId("ParamC"))
        );
        assertEquals(0, otherGroupFixture.editMode.beginCalls);
    }

    @Test
    void samePairRequestIsIdempotentAndStructuralCorruptionFailsClosed() {
        Fixture fixture = new Fixture();
        Host.install(fixture);
        var access = new EditorBackedCubismModelAccess(resolver(true), "session-a");
        var first = access.active().parameters().find(new ParameterId("ParamA"));
        var second = access.active().parameters().find(new ParameterId("ParamB"));

        first.combineWith(second.id());
        first.combineWith(second.id());
        assertEquals(1, fixture.editMode.beginCalls);

        fixture.a.combined = false;
        fixture.b.combined = true;
        fixture.group.children.remove(fixture.c);
        assertThrows(IllegalStateException.class, second::combinedWith);
        assertEquals(1, fixture.editMode.beginCalls);
    }

    @Test
    void failsClosedWithoutSeparateCombinedWriteEvidence() {
        Fixture fixture = new Fixture();
        Host.install(fixture);
        var access = new EditorBackedCubismModelAccess(resolver(false), "session-a");
        var first = access.active().parameters().find(new ParameterId("ParamA"));

        assertThrows(
            UnsupportedOperationException.class,
            () -> first.combineWith(new ParameterId("ParamB"))
        );
        assertEquals(0, fixture.editMode.beginCalls);
        assertEquals(List.of("ParamA", "ParamB", "ParamC"), fixture.group.ids());
        assertEquals(0, fixture.operation.refreshes);
    }

    private static VerifiedMemberResolver resolver(final boolean combinedWriteAuthorized) {
        String host = internal(Host.class);
        String document = internal(Document.class);
        String modelSource = internal(ModelSource.class);
        String model = internal(Model.class);
        String parameterSet = internal(ParameterSet.class);
        String parameter = internal(RuntimeParameter.class);
        String source = internal(ParameterSource.class);
        String id = internal(Id.class);
        String group = internal(ParameterGroup.class);
        String mainFrame = internal(MainFrame.class);
        String palette = internal(ParameterPalette.class);
        String paletteView = internal(ParameterPaletteView.class);
        String operation = internal(ParameterOperation.class);
        String editMode = internal(EditMode.class);
        String undo = internal(Undo.class);
        String copyable = internal(Copyable.class);
        return TestVerifiedResolvers.create(
            "5.3.02",
            "adapter.editor-model.readwrite",
            combinedWriteAuthorized
                ? java.util.Set.of(
                    "cubism.editor-model.read",
                    "cubism.editor-model.write",
                    "cubism.editor-model.parameter-combined.write"
                )
                : java.util.Set.of("cubism.editor-model.read", "cubism.editor-model.write"),
            List.of(
                StaticSelector.classSelector("cubism.editor-model.app-controller.class", host),
                StaticSelector.staticMethod("cubism.editor-model.app-controller.instance", host, "instance", desc(Host.class), StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC),
                method("cubism.editor-model.app-controller.current-document", Host.class, "currentDocument", desc(Document.class)),
                method("cubism.editor-model.app-controller.main-frame", Host.class, "mainFrame", desc(MainFrame.class)),
                StaticSelector.classSelector("cubism.editor-model.modeling-document.class", document),
                method("cubism.editor-model.modeling-document.model-source", Document.class, "modelSource", desc(ModelSource.class)),
                method("cubism.editor-model.model-source.guid", ModelSource.class, "guid", desc(Id.class)),
                method("cubism.editor-model.model-source.current-instance", ModelSource.class, "currentInstance", desc(Model.class)),
                StaticSelector.classSelector("cubism.editor-model.model.class", model),
                method("cubism.editor-model.model.parameter-set", Model.class, "parameterSet", desc(ParameterSet.class)),
                StaticSelector.classSelector("cubism.editor-model.parameter-set.class", parameterSet),
                method("cubism.editor-model.parameter-set.parameters", ParameterSet.class, "parameters", "()Ljava/util/List;"),
                StaticSelector.classSelector("cubism.editor-model.parameter.class", parameter),
                method("cubism.editor-model.parameter.id", RuntimeParameter.class, "id", desc(Id.class)),
                method("cubism.editor-model.parameter.value", RuntimeParameter.class, "value", "()F"),
                method("cubism.editor-model.parameter.source", RuntimeParameter.class, "source", desc(ParameterSource.class)),
                StaticSelector.classSelector("cubism.editor-model.parameter-source.class", source),
                method("cubism.editor-model.parameter-source.minimum", ParameterSource.class, "minimum", "()F"),
                method("cubism.editor-model.parameter-source.maximum", ParameterSource.class, "maximum", "()F"),
                method("cubism.editor-model.parameter-source.default", ParameterSource.class, "defaultValue", "()F"),
                method("cubism.editor-model.parameter-source.name", ParameterSource.class, "name", "()Ljava/lang/String;"),
                method("cubism.editor-model.parameter-source.repeat", ParameterSource.class, "repeat", "()Z"),
                method("cubism.editor-model.parameter-source.morph-target", ParameterSource.class, "morphTarget", "()Z"),
                method("cubism.editor-model.parameter-source.combined", ParameterSource.class, "combined", "()Z"),
                method("cubism.editor-model.parameter-source.set-combined", ParameterSource.class, "setCombined", "(Z)V"),
                method("cubism.editor-model.parameter-source.guid", ParameterSource.class, "guid", desc(Id.class)),
                method("cubism.editor-model.parameter-source.id", ParameterSource.class, "id", desc(Id.class)),
                method("cubism.editor-model.parameter-source.parent-group", ParameterSource.class, "parentGroup", desc(ParameterGroup.class)),
                StaticSelector.classSelector("cubism.editor-model.parameter-group.class", group),
                method("cubism.editor-model.parameter-group.children", ParameterGroup.class, "children", "()Ljava/util/List;"),
                method("cubism.editor-model.parameter-group.remove", ParameterGroup.class, "remove", "(L" + id + ";)V"),
                method("cubism.editor-model.parameter-group.add", ParameterGroup.class, "add", "(L" + source + ";I)V"),
                StaticSelector.classSelector("cubism.editor-model.id.class", id),
                method("cubism.editor-model.id.value", Id.class, "value", "()Ljava/lang/String;"),
                StaticSelector.classSelector("cubism.editor-model.guid.class", id),
                method("cubism.editor-model.guid.value", Id.class, "value", "()Ljava/lang/String;"),
                StaticSelector.classSelector("cubism.editor-model.main-frame.class", mainFrame),
                method("cubism.editor-model.main-frame.parameter-palette", MainFrame.class, "parameterPalette", desc(ParameterPalette.class)),
                StaticSelector.classSelector("cubism.editor-model.parameter-palette.class", palette),
                method("cubism.editor-model.parameter-palette.view", ParameterPalette.class, "view", desc(ParameterPaletteView.class)),
                StaticSelector.classSelector("cubism.editor-model.parameter-palette-view.class", paletteView),
                method("cubism.editor-model.parameter-palette-view.operation", ParameterPaletteView.class, "operation", desc(ParameterOperation.class)),
                StaticSelector.classSelector("cubism.editor-model.parameter-operation.class", operation),
                method("cubism.editor-model.parameter-operation.refresh", ParameterOperation.class, "refresh", "(Z)V"),
                method("cubism.editor-model.modeling-document.edit-mode", Document.class, "editMode", desc(EditMode.class)),
                method("cubism.editor-model.modeling-document.mark-dirty", Document.class, "markDirty", "()V"),
                StaticSelector.classSelector("cubism.editor-model.edit-mode.class", editMode),
                method("cubism.editor-model.edit-mode.begin", EditMode.class, "begin", "(Ljava/lang/String;)L" + undo + ";"),
                method("cubism.editor-model.edit-mode.end", EditMode.class, "end", "(ZLjava/lang/Object;)Z"),
                StaticSelector.classSelector("cubism.editor-model.undo.class", undo),
                method("cubism.editor-model.undo.add", Undo.class, "add", "(Ljava/lang/Object;Z)Z"),
                StaticSelector.constructor("cubism.editor-model.simple-undo.create", internal(SimpleUndo.class), "(Ljava/lang/String;L" + copyable + ";Ljava/lang/Object;)V", StaticSelector.ACCESS_PUBLIC),
                method("cubism.editor-model.undo.add-listener", Undo.class, "addListener", "(L" + internal(UndoListener.class) + ";)Z"),
                StaticSelector.classSelector("cubism.editor-model.undo-listener.class", internal(UndoListener.class))
            ),
            Host.class.getClassLoader()
        );
    }

    private static StaticSelector method(String alias, Class<?> owner, String name, String descriptor) {
        return StaticSelector.method(alias, internal(owner), name, descriptor, StaticSelector.ACCESS_PUBLIC);
    }

    private static String internal(Class<?> type) { return type.getName().replace('.', '/'); }
    private static String desc(Class<?> type) { return "()L" + internal(type) + ";"; }

    public interface Copyable {
        Object snapshot();
        void restore(Object snapshot);
    }

    public static final class Id {
        final String value;
        Id(String value) { this.value = value; }
        public String value() { return value; }
        @Override public boolean equals(Object other) { return other instanceof Id id && value.equals(id.value); }
        @Override public int hashCode() { return value.hashCode(); }
    }

    public static final class ParameterSource implements Copyable {
        final Id id;
        final Id guid;
        final String name;
        ParameterGroup parent;
        boolean combined;
        ParameterSource(String id) {
            this.id = new Id(id);
            this.guid = new Id("guid-" + id);
            this.name = id;
        }
        public float minimum() { return -1; }
        public float maximum() { return 1; }
        public float defaultValue() { return 0; }
        public String name() { return name; }
        public boolean repeat() { return false; }
        public boolean morphTarget() { return false; }
        public boolean combined() { return combined; }
        public void setCombined(boolean value) { combined = value; }
        public Id id() { return id; }
        public Id guid() { return guid; }
        public ParameterGroup parentGroup() { return parent; }
        @Override public Object snapshot() { return Boolean.valueOf(combined); }
        @Override public void restore(Object snapshot) { combined = (Boolean) snapshot; }
    }

    public static final class ParameterGroup implements Copyable {
        final List<ParameterSource> children = new ArrayList<>();
        public List<ParameterSource> children() { return List.copyOf(children); }
        public void remove(Id guid) { children.removeIf(source -> source.guid.equals(guid)); }
        public void add(ParameterSource source, int index) {
            if (source.parent != null) source.parent.children.remove(source);
            int resolved = index < 0 || index > children.size() ? children.size() : index;
            children.add(resolved, source);
            source.parent = this;
        }
        List<String> ids() { return children.stream().map(source -> source.id.value).toList(); }
        @Override public Object snapshot() { return List.copyOf(children); }
        @Override public void restore(Object snapshot) {
            children.clear();
            @SuppressWarnings("unchecked")
            final List<ParameterSource> restored = (List<ParameterSource>) snapshot;
            children.addAll(restored);
            children.forEach(source -> source.parent = this);
        }
    }

    public static final class RuntimeParameter {
        final ParameterSource source;
        RuntimeParameter(ParameterSource source) { this.source = source; }
        public Id id() { return source.id; }
        public float value() { return 0; }
        public ParameterSource source() { return source; }
    }

    public static final class ParameterSet {
        final Fixture fixture;
        ParameterSet(Fixture fixture) { this.fixture = fixture; }
        public List<RuntimeParameter> parameters() {
            return List.of(fixture.a, fixture.b, fixture.c).stream().map(RuntimeParameter::new).toList();
        }
    }

    public static final class Model {
        final ParameterSet set;
        Model(ParameterSet set) { this.set = set; }
        public ParameterSet parameterSet() { return set; }
    }

    public static final class ModelSource {
        final Id guid = new Id("model-a");
        final Model model;
        ModelSource(Model model) { this.model = model; }
        public Id guid() { return guid; }
        public Model currentInstance() { return model; }
    }

    public interface UndoListener { void executed(Object event); }

    public static final class SimpleUndo {
        final Copyable target;
        final Object before;
        Object after;
        public SimpleUndo(String name, Copyable target, Object context) {
            this.target = target;
            this.before = target.snapshot();
        }
        void undo() { after = target.snapshot(); target.restore(before); }
        void redo() { target.restore(after); }
    }

    public static final class Undo {
        final List<SimpleUndo> edits = new ArrayList<>();
        final List<UndoListener> listeners = new ArrayList<>();
        public boolean add(Object edit, boolean force) { edits.add((SimpleUndo) edit); return true; }
        public boolean addListener(UndoListener listener) { listeners.add(listener); return true; }
        void undo() { for (int i = edits.size() - 1; i >= 0; i--) edits.get(i).undo(); listeners.forEach(it -> it.executed(null)); }
        void redo() { edits.forEach(SimpleUndo::redo); listeners.forEach(it -> it.executed(null)); }
    }

    public static final class EditMode {
        int beginCalls;
        int endCalls;
        int committedEdits;
        Undo current;
        Undo committed;
        public Undo begin(String name) { beginCalls++; return current = new Undo(); }
        public boolean end(boolean cancel, Object callback) {
            endCalls++;
            if (!cancel) { committed = current; committedEdits++; }
            return !cancel;
        }
        void undo() { committed.undo(); }
        void redo() { committed.redo(); }
    }

    public static final class Document {
        final ModelSource source;
        final EditMode editMode;
        int dirty;
        Document(ModelSource source, EditMode editMode) { this.source = source; this.editMode = editMode; }
        public ModelSource modelSource() { return source; }
        public EditMode editMode() { return editMode; }
        public void markDirty() { dirty++; }
    }

    public static final class ParameterOperation {
        int refreshes;
        public void refresh(boolean structural) { refreshes++; }
    }
    public static final class ParameterPaletteView {
        final ParameterOperation operation;
        ParameterPaletteView(ParameterOperation operation) { this.operation = operation; }
        public ParameterOperation operation() { return operation; }
    }
    public static final class ParameterPalette {
        final ParameterPaletteView view;
        ParameterPalette(ParameterPaletteView view) { this.view = view; }
        public ParameterPaletteView view() { return view; }
    }
    public static final class MainFrame {
        final ParameterPalette palette;
        MainFrame(ParameterPalette palette) { this.palette = palette; }
        public ParameterPalette parameterPalette() { return palette; }
    }
    public static final class Host {
        public static final Host INSTANCE = new Host();
        static Document currentDocument;
        static MainFrame mainFrame;
        public static Host instance() { return INSTANCE; }
        public Document currentDocument() { return currentDocument; }
        public MainFrame mainFrame() { return mainFrame; }
        static void install(Fixture fixture) { currentDocument = fixture.document; mainFrame = fixture.mainFrame; }
    }

    public static final class Fixture {
        final ParameterSource a = new ParameterSource("ParamA");
        final ParameterSource b = new ParameterSource("ParamB");
        final ParameterSource c = new ParameterSource("ParamC");
        final ParameterGroup group = new ParameterGroup();
        final ParameterGroup otherGroup = new ParameterGroup();
        final EditMode editMode = new EditMode();
        final ParameterOperation operation = new ParameterOperation();
        final ParameterSet set = new ParameterSet(this);
        final ModelSource source = new ModelSource(new Model(set));
        final Document document = new Document(source, editMode);
        final MainFrame mainFrame = new MainFrame(new ParameterPalette(new ParameterPaletteView(operation)));
        Fixture() { group.add(a, -1); group.add(b, -1); group.add(c, -1); }
        void moveThirdToOtherGroup() { otherGroup.add(c, -1); }
    }
}
