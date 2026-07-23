package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.EditorParameterGroupLabelColorReadSelectorContract;
import dev.turboism.mapping.verification.EditorParameterGroupLabelColorWriteSelectorContract;
import dev.turboism.mapping.verification.EditorParameterGroupsReadSelectorContract;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.id.ParameterGroupId;
import dev.turboism.sdk.cubism.model.Color;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EditorParameterGroupLabelColorWriteTest {

    @AfterEach
    void clearHost() {
        Host.currentDocument = null;
        Host.completePack = null;
        Host.mainFrame = null;
    }

    @Test
    void customColorsUseOneUndoableEditorTransaction() {
        Fixture fixture = new Fixture("model-a");
        Host.install(fixture);
        var group = new EditorBackedCubismModelAccess(
            resolver(true), "session-a"
        ).active().parameterGroups().find(new ParameterGroupId("GroupFace"));
        var expected = new Color(0.1F, 0.2F, 0.3F, 0.4F);

        group.setLabelColor(expected);

        assertEquals(expected, group.labelColor());
        assertEquals(LabelColorType.CUSTOM, fixture.face.labelColor.type);
        assertEquals(1, fixture.editMode.beginCalls);
        assertEquals(1, fixture.editMode.committedEdits);
        assertEquals(1, fixture.document.dirtyUpdates);
        assertEquals(1, fixture.operation.refreshes);
        assertEquals(1, fixture.completePack.parameterRefreshes);
        assertEquals(1, fixture.completePack.canvasRepaints);

        fixture.editMode.undo();
        assertEquals(new Color(0.25F, 0.5F, 0.75F, 1.0F), group.labelColor());
        fixture.editMode.redo();
        assertEquals(expected, group.labelColor());
        assertEquals(3, fixture.operation.refreshes);
        assertEquals(3, fixture.completePack.parameterRefreshes);
        assertEquals(3, fixture.completePack.canvasRepaints);

        group.setLabelColor(expected);
        assertEquals(1, fixture.editMode.beginCalls, "unchanged color must not create history");
    }

    @Test
    void customColorsFailClosedWithoutSeparateWriteEvidence() {
        Fixture fixture = new Fixture("model-a");
        Host.install(fixture);
        var group = new EditorBackedCubismModelAccess(
            resolver(false), "session-a"
        ).active().parameterGroups().find(new ParameterGroupId("GroupFace"));

        assertThrows(
            UnsupportedOperationException.class,
            () -> group.setLabelColor(new Color(1.0F, 0.0F, 0.0F, 1.0F))
        );
        assertEquals(new Color(0.25F, 0.5F, 0.75F, 1.0F), group.labelColor());
        assertEquals(0, fixture.editMode.beginCalls);
        assertEquals(0, fixture.document.dirtyUpdates);
    }

    private static VerifiedMemberResolver resolver(final boolean writeAuthorized) {
        final String host = internal(Host.class);
        final String document = internal(Document.class);
        final String source = internal(ModelSource.class);
        final String model = internal(Model.class);
        final String id = internal(Id.class);
        final String group = internal(ParameterGroup.class);
        final String labelColor = internal(LabelColor.class);
        final String labelColorType = internal(LabelColorType.class);
        final String color = internal(HostColor.class);
        final String completePack = internal(CompletePack.class);
        final String mainFrame = internal(MainFrame.class);
        final String palette = internal(ParameterPalette.class);
        final String paletteView = internal(ParameterPaletteView.class);
        final String operation = internal(ParameterOperation.class);
        final String editMode = internal(EditMode.class);
        final String undo = internal(Undo.class);
        final String copyable = internal(Copyable.class);
        final java.util.Set<String> capabilities = writeAuthorized
            ? java.util.Set.of(
                "cubism.editor-model.read",
                "cubism.editor-model.write",
                EditorParameterGroupsReadSelectorContract.CAPABILITY_ID,
                EditorParameterGroupLabelColorReadSelectorContract.CAPABILITY_ID,
                EditorParameterGroupLabelColorWriteSelectorContract.CAPABILITY_ID
            )
            : java.util.Set.of(
                "cubism.editor-model.read",
                "cubism.editor-model.write",
                EditorParameterGroupsReadSelectorContract.CAPABILITY_ID,
                EditorParameterGroupLabelColorReadSelectorContract.CAPABILITY_ID
            );
        return TestVerifiedResolvers.create(
            "5.3.02",
            "adapter.editor-model.readwrite",
            capabilities,
            List.of(
                StaticSelector.classSelector("cubism.editor-model.app-controller.class", host),
                StaticSelector.staticMethod("cubism.editor-model.app-controller.instance", host, "instance", desc(Host.class), StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC),
                method("cubism.editor-model.app-controller.current-document", Host.class, "currentDocument", desc(Document.class)),
                method("cubism.editor-model.app-controller.complete-pack", Host.class, "completePack", desc(CompletePack.class)),
                method("cubism.editor-model.app-controller.main-frame", Host.class, "mainFrame", desc(MainFrame.class)),
                StaticSelector.classSelector("cubism.editor-model.modeling-document.class", document),
                method("cubism.editor-model.modeling-document.model-source", Document.class, "modelSource", desc(ModelSource.class)),
                method("cubism.editor-model.modeling-document.edit-mode", Document.class, "editMode", desc(EditMode.class)),
                method("cubism.editor-model.modeling-document.mark-dirty", Document.class, "markDirty", "()V"),
                StaticSelector.classSelector("cubism.editor-model.model-source.class", source),
                method("cubism.editor-model.model-source.guid", ModelSource.class, "guid", desc(Id.class)),
                method("cubism.editor-model.model-source.current-instance", ModelSource.class, "currentInstance", desc(Model.class)),
                method("cubism.editor-model.model-source.root-parameter-group", ModelSource.class, "rootParameterGroup", desc(ParameterGroup.class)),
                StaticSelector.classSelector("cubism.editor-model.model.class", model),
                StaticSelector.classSelector("cubism.editor-model.parameter-group.class", group),
                method("cubism.editor-model.parameter-group.id", ParameterGroup.class, "id", desc(Id.class)),
                method("cubism.editor-model.parameter-group.name", ParameterGroup.class, "name", "()Ljava/lang/String;"),
                method("cubism.editor-model.parameter-group.parent", ParameterGroup.class, "parent", desc(ParameterGroup.class)),
                method("cubism.editor-model.parameter-group.children", ParameterGroup.class, "children", "()Ljava/util/List;"),
                method("cubism.editor-model.parameter-group.label-color", ParameterGroup.class, "labelColor", desc(LabelColor.class)),
                StaticSelector.classSelector("cubism.editor-model.parameter-source.class", internal(ParameterSource.class)),
                method("cubism.editor-model.parameter-source.id", ParameterSource.class, "id", desc(Id.class)),
                StaticSelector.classSelector("cubism.editor-model.id.class", id),
                method("cubism.editor-model.id.value", Id.class, "value", "()Ljava/lang/String;"),
                StaticSelector.classSelector("cubism.editor-model.guid.class", id),
                method("cubism.editor-model.guid.value", Id.class, "value", "()Ljava/lang/String;"),
                StaticSelector.classSelector("cubism.editor-model.label-color.class", labelColor),
                method("cubism.editor-model.label-color.color", LabelColor.class, "color", desc(HostColor.class)),
                method("cubism.editor-model.label-color.set-color", LabelColor.class, "setColor", "(L" + labelColorType + ";L" + color + ";)V"),
                StaticSelector.classSelector("cubism.editor-model.label-color-type.class", labelColorType),
                StaticSelector.field("cubism.editor-model.label-color-type.custom", labelColorType, "CUSTOM", "L" + labelColorType + ";", StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC),
                StaticSelector.classSelector("cubism.editor-model.color.class", color),
                StaticSelector.constructor("cubism.editor-model.color.create", color, "(FFFF)V", StaticSelector.ACCESS_PUBLIC),
                method("cubism.editor-model.color.red", HostColor.class, "red", "()F"),
                method("cubism.editor-model.color.green", HostColor.class, "green", "()F"),
                method("cubism.editor-model.color.blue", HostColor.class, "blue", "()F"),
                method("cubism.editor-model.color.alpha", HostColor.class, "alpha", "()F"),
                StaticSelector.classSelector("cubism.editor-model.complete-pack.class", completePack),
                method("cubism.editor-model.complete-pack.update-parameter", CompletePack.class, "updateParameter", "(Z)V"),
                method("cubism.editor-model.complete-pack.repaint-canvas", CompletePack.class, "repaintCanvas", "(Z)V"),
                StaticSelector.classSelector("cubism.editor-model.main-frame.class", mainFrame),
                method("cubism.editor-model.main-frame.parameter-palette", MainFrame.class, "parameterPalette", desc(ParameterPalette.class)),
                StaticSelector.classSelector("cubism.editor-model.parameter-palette.class", palette),
                method("cubism.editor-model.parameter-palette.view", ParameterPalette.class, "view", desc(ParameterPaletteView.class)),
                StaticSelector.classSelector("cubism.editor-model.parameter-palette-view.class", paletteView),
                method("cubism.editor-model.parameter-palette-view.operation", ParameterPaletteView.class, "operation", desc(ParameterOperation.class)),
                StaticSelector.classSelector("cubism.editor-model.parameter-operation.class", operation),
                method("cubism.editor-model.parameter-operation.refresh", ParameterOperation.class, "refresh", "(Z)V"),
                StaticSelector.classSelector("cubism.editor-model.edit-mode.class", editMode),
                method("cubism.editor-model.edit-mode.begin", EditMode.class, "begin", "(Ljava/lang/String;)L" + undo + ";"),
                method("cubism.editor-model.edit-mode.end", EditMode.class, "end", "(ZLjava/lang/Object;)Z"),
                StaticSelector.classSelector("cubism.editor-model.undo.class", undo),
                method("cubism.editor-model.undo.add", Undo.class, "add", "(Ljava/lang/Object;Z)Z"),
                method("cubism.editor-model.undo.add-listener", Undo.class, "addListener", "(L" + internal(UndoListener.class) + ";)Z"),
                StaticSelector.classSelector("cubism.editor-model.undo-listener.class", internal(UndoListener.class)),
                StaticSelector.constructor("cubism.editor-model.simple-undo.create", internal(SimpleUndo.class), "(Ljava/lang/String;L" + copyable + ";Ljava/lang/Object;)V", StaticSelector.ACCESS_PUBLIC)
            ),
            Host.class.getClassLoader()
        );
    }

    private static StaticSelector method(
        final String alias,
        final Class<?> owner,
        final String name,
        final String descriptor
    ) {
        return StaticSelector.method(
            alias,
            internal(owner),
            name,
            descriptor,
            StaticSelector.ACCESS_PUBLIC
        );
    }

    private static String internal(final Class<?> type) {
        return type.getName().replace('.', '/');
    }

    private static String desc(final Class<?> type) {
        return "()L" + internal(type) + ";";
    }

    public interface Copyable {
        Object snapshot();
        void restore(Object snapshot);
    }

    public interface UndoListener {
        void executed(Object event);
    }

    public record Id(String value) {
        public String value() { return value; }
    }

    public static final class Model {
    }

    public static final class ParameterSource {
        public Id id() { return new Id("ParamA"); }
    }

    public enum LabelColorType {
        CUSTOM,
        BLUE
    }

    public record HostColor(float red, float green, float blue, float alpha) {
        public float red() { return red; }
        public float green() { return green; }
        public float blue() { return blue; }
        public float alpha() { return alpha; }
    }

    public static final class LabelColor implements Copyable {
        LabelColorType type = LabelColorType.BLUE;
        HostColor color = new HostColor(0.25F, 0.5F, 0.75F, 1.0F);
        public HostColor color() { return color; }
        public void setColor(final LabelColorType nextType, final HostColor nextColor) {
            type = nextType;
            color = nextColor;
        }
        @Override public Object snapshot() { return new State(type, color); }
        @Override public void restore(final Object snapshot) {
            State state = (State) snapshot;
            type = state.type();
            color = state.color();
        }
        private record State(LabelColorType type, HostColor color) { }
    }

    public static final class ParameterGroup {
        final Id id;
        final String name;
        final ParameterGroup parent;
        final LabelColor labelColor = new LabelColor();
        final List<Object> children = new ArrayList<>();
        ParameterGroup(final String id, final String name, final ParameterGroup parent) {
            this.id = new Id(id);
            this.name = name;
            this.parent = parent;
        }
        public Id id() { return id; }
        public String name() { return name; }
        public ParameterGroup parent() { return parent; }
        public LabelColor labelColor() { return labelColor; }
        public List<Object> children() { return children; }
    }

    public static final class ModelSource {
        final Id guid;
        final Model model = new Model();
        final ParameterGroup root;
        ModelSource(final String id) {
            guid = new Id(id);
            root = new ParameterGroup("GroupRoot", "Root", null);
            root.children.add(new ParameterGroup("GroupFace", "Face", root));
        }
        public Id guid() { return guid; }
        public Model currentInstance() { return model; }
        public ParameterGroup rootParameterGroup() { return root; }
    }

    public static final class Document {
        final ModelSource source;
        final EditMode editMode;
        int dirtyUpdates;
        Document(final ModelSource source, final EditMode editMode) {
            this.source = source;
            this.editMode = editMode;
        }
        public ModelSource modelSource() { return source; }
        public EditMode editMode() { return editMode; }
        public void markDirty() { dirtyUpdates++; }
    }

    public static final class CompletePack {
        int parameterRefreshes;
        int canvasRepaints;
        public void updateParameter(final boolean immediate) { parameterRefreshes++; }
        public void repaintCanvas(final boolean immediate) { canvasRepaints++; }
    }

    public static final class ParameterOperation {
        int refreshes;
        public void refresh(final boolean immediate) { refreshes++; }
    }

    public static final class ParameterPaletteView {
        final ParameterOperation operation;
        ParameterPaletteView(final ParameterOperation operation) { this.operation = operation; }
        public ParameterOperation operation() { return operation; }
    }

    public static final class ParameterPalette {
        final ParameterPaletteView view;
        ParameterPalette(final ParameterPaletteView view) { this.view = view; }
        public ParameterPaletteView view() { return view; }
    }

    public static final class MainFrame {
        final ParameterPalette palette;
        MainFrame(final ParameterPalette palette) { this.palette = palette; }
        public ParameterPalette parameterPalette() { return palette; }
    }

    public static final class SimpleUndo {
        final Copyable target;
        final Object before;
        Object after;
        public SimpleUndo(final String name, final Copyable target, final Object context) {
            this.target = target;
            this.before = target.snapshot();
        }
        void undo() {
            after = target.snapshot();
            target.restore(before);
        }
        void redo() { target.restore(after); }
    }

    public static final class Undo {
        final List<SimpleUndo> edits = new ArrayList<>();
        final List<UndoListener> listeners = new ArrayList<>();
        public boolean add(final Object raw, final boolean force) {
            edits.add((SimpleUndo) raw);
            return force;
        }
        public boolean addListener(final UndoListener listener) {
            listeners.add(listener);
            return true;
        }
        void undo() {
            for (int index = edits.size() - 1; index >= 0; index--) edits.get(index).undo();
            listeners.forEach(listener -> listener.executed(null));
        }
        void redo() {
            edits.forEach(SimpleUndo::redo);
            listeners.forEach(listener -> listener.executed(null));
        }
    }

    public static final class EditMode {
        int beginCalls;
        int committedEdits;
        Undo active;
        Undo committed;
        public Undo begin(final String action) {
            beginCalls++;
            active = new Undo();
            return active;
        }
        public boolean end(final boolean cancelled, final Object callback) {
            if (!cancelled) {
                committedEdits++;
                committed = active;
            }
            active = null;
            return !cancelled;
        }
        void undo() { committed.undo(); }
        void redo() { committed.redo(); }
    }

    public static final class Host {
        static final Host INSTANCE = new Host();
        static Document currentDocument;
        static CompletePack completePack;
        static MainFrame mainFrame;
        public static Host instance() { return INSTANCE; }
        public Document currentDocument() { return currentDocument; }
        public CompletePack completePack() { return completePack; }
        public MainFrame mainFrame() { return mainFrame; }
        static void install(final Fixture fixture) {
            currentDocument = fixture.document;
            completePack = fixture.completePack;
            mainFrame = fixture.mainFrame;
        }
    }

    static final class Fixture {
        final ModelSource source;
        final ParameterGroup face;
        final EditMode editMode = new EditMode();
        final Document document;
        final CompletePack completePack = new CompletePack();
        final ParameterOperation operation = new ParameterOperation();
        final MainFrame mainFrame = new MainFrame(
            new ParameterPalette(new ParameterPaletteView(operation))
        );
        Fixture(final String id) {
            source = new ModelSource(id);
            face = (ParameterGroup) source.root.children.get(0);
            document = new Document(source, editMode);
        }
    }
}
