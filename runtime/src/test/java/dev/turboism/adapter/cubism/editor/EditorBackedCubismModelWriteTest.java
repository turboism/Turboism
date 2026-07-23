package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.id.ParameterId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EditorBackedCubismModelWriteTest {

    @AfterEach
    void clearHost() {
        Host.currentDocument = null;
    }

    @Test
    void parameterSetterUsesTheVerifiedEditorOperationAndRefreshesPaletteAndCanvas() {
        Fixture fixture = new Fixture("model-a", 12.0F);
        Host.install(fixture);
        EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(
            resolver(), "session-a"
        );
        var parameter = access.active().parameters().find(new ParameterId("ParamAngleX"));

        parameter.setValue(90.0F);

        assertEquals(30.0F, parameter.getValue(), "the Editor-native operation owns clamping");
        assertEquals(1, fixture.operation.calls);
        assertEquals(30.0F, fixture.operation.lastValue);
        assertEquals(1, fixture.editMode.beginCalls);
        assertEquals(1, fixture.editMode.endCalls);
        assertEquals(1, fixture.editMode.committedEdits);
        assertEquals(false, fixture.editMode.activeEdit);
        assertEquals(1, fixture.document.dirtyUpdates);
        assertEquals(1, fixture.completePack.parameterRefreshes);
        assertEquals(1, fixture.completePack.canvasRepaints);
        assertEquals(0, fixture.parameterSet.directCopyCalls,
            "SimpleUndo must snapshot the live set; Runtime must not pre-copy it");

        fixture.editMode.undo();
        assertEquals(12.0F, parameter.getValue(), "Undo must restore the live Editor parameter set");
        fixture.editMode.redo();
        assertEquals(30.0F, parameter.getValue(), "Redo must restore the live Editor parameter set");

        parameter.setValue(30.0F);
        assertEquals(1, fixture.editMode.beginCalls, "unchanged values must not create history");
        assertEquals(1, fixture.document.dirtyUpdates);
    }

    @Test
    void invalidWritesHaveNoHostSideEffects() {
        Fixture fixture = new Fixture("model-a", 12.0F);
        Host.install(fixture);
        EditorBackedCubismModelAccess allowed = new EditorBackedCubismModelAccess(
            resolver(), "session-a"
        );
        var parameter = allowed.active().parameters().find(new ParameterId("ParamAngleX"));
        assertThrows(IllegalArgumentException.class, () -> parameter.setValue(Float.NaN));
        assertEquals(0, fixture.operation.calls);
        assertEquals(12.0F, parameter.getValue());
    }

    @Test
    void nativeFailureCancelsTheEditorTransactionWithoutDirtyingTheDocument() {
        Fixture fixture = new Fixture("model-a", 12.0F);
        fixture.operation.fail = true;
        Host.install(fixture);
        EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(
            resolver(), "session-a"
        );
        var parameter = access.active().parameters().find(new ParameterId("ParamAngleX"));

        assertThrows(RuntimeException.class, () -> parameter.setValue(4.0F));

        assertEquals(1, fixture.editMode.beginCalls);
        assertEquals(1, fixture.editMode.endCalls);
        assertEquals(1, fixture.editMode.cancelledEdits);
        assertEquals(0, fixture.editMode.committedEdits);
        assertEquals(false, fixture.editMode.activeEdit);
        assertEquals(0, fixture.document.dirtyUpdates);
        assertEquals(0, fixture.completePack.parameterRefreshes);
        assertEquals(0, fixture.completePack.canvasRepaints);
        assertEquals(12.0F, parameter.getValue());
    }

    @Test
    void undoPreparationFailureStillClosesTheActiveEditorTransaction() {
        Fixture fixture = new Fixture("model-a", 12.0F);
        fixture.parameterSet.failUndoPreparation = true;
        Host.install(fixture);
        EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(
            resolver(), "session-a"
        );
        var parameter = access.active().parameters().find(new ParameterId("ParamAngleX"));

        assertThrows(RuntimeException.class, () -> parameter.setValue(4.0F));

        assertEquals(1, fixture.editMode.beginCalls);
        assertEquals(1, fixture.editMode.endCalls);
        assertEquals(1, fixture.editMode.cancelledEdits);
        assertEquals(0, fixture.editMode.committedEdits);
        assertEquals(false, fixture.editMode.activeEdit);
        assertEquals(0, fixture.operation.calls);
        assertEquals(0, fixture.document.dirtyUpdates);
        assertEquals(12.0F, parameter.getValue());
    }

    @Test
    void staleParameterCannotWriteTheReplacementModel() {
        Fixture first = new Fixture("model-a", 12.0F);
        Host.install(first);
        EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(
            resolver(), "session-a"
        );
        var stale = access.active().parameters().find(new ParameterId("ParamAngleX"));

        Fixture replacement = new Fixture("model-b", -5.0F);
        Host.install(replacement);

        assertThrows(IllegalStateException.class, () -> stale.setValue(7.0F));
        assertEquals(0, first.operation.calls);
        assertEquals(0, replacement.operation.calls);
        assertEquals(-5.0F, replacement.parameter.value());
    }

    private static VerifiedMemberResolver resolver() {
        String host = internal(Host.class);
        String document = internal(Document.class);
        String source = internal(ModelSource.class);
        String model = internal(Model.class);
        String set = internal(ParameterSet.class);
        String copyContext = internal(CopyContext.class);
        String parameter = internal(Parameter.class);
        String metadata = internal(ParameterSource.class);
        String id = internal(Id.class);
        String completePack = internal(CompletePack.class);
        String mainFrame = internal(MainFrame.class);
        String editMode = internal(EditMode.class);
        String undo = internal(Undo.class);
        String simpleUndo = internal(SimpleUndo.class);
        String palette = internal(ParameterPalette.class);
        String paletteView = internal(ParameterPaletteView.class);
        String operation = internal(ParameterOperation.class);
        return TestVerifiedResolvers.create(
            "5.3.02",
            "adapter.editor-model.readwrite",
            java.util.Set.of("cubism.editor-model.read", "cubism.editor-model.write"),
            List.of(
                StaticSelector.classSelector("cubism.editor-model.app-controller.class", host),
                StaticSelector.staticMethod("cubism.editor-model.app-controller.instance", host, "instance", desc(Host.class), StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC),
                method("cubism.editor-model.app-controller.current-document", Host.class, "currentDocument", desc(Document.class)),
                method("cubism.editor-model.app-controller.complete-pack", Host.class, "completePack", desc(CompletePack.class)),
                StaticSelector.classSelector("cubism.editor-model.modeling-document.class", document),
                method("cubism.editor-model.modeling-document.model-source", Document.class, "modelSource", desc(ModelSource.class)),
                method("cubism.editor-model.modeling-document.edit-mode", Document.class, "editMode", desc(EditMode.class)),
                method("cubism.editor-model.modeling-document.mark-dirty", Document.class, "markDirty", "()V"),
                method("cubism.editor-model.modeling-document.last-active-view", Document.class, "lastActiveView", "()Ljava/lang/Object;"),
                StaticSelector.classSelector("cubism.editor-model.modeling-view.class", internal(Object.class)),
                method("cubism.editor-model.modeling-view.model", Object.class, "toString", "()Ljava/lang/String;"),
                StaticSelector.classSelector("cubism.editor-model.model-source.class", source),
                method("cubism.editor-model.model-source.guid", ModelSource.class, "guid", desc(Id.class)),
                method("cubism.editor-model.model-source.current-instance", ModelSource.class, "currentInstance", desc(Model.class)),
                method("cubism.editor-model.model-source.all-parameters", ModelSource.class, "allParameters", "()Ljava/util/List;"),
                StaticSelector.classSelector("cubism.editor-model.model.class", model),
                method("cubism.editor-model.model.parameter-set", Model.class, "parameterSet", desc(ParameterSet.class)),
                StaticSelector.classSelector("cubism.editor-model.parameter-set.class", set),
                method("cubism.editor-model.parameter-set.parameters", ParameterSet.class, "parameters", "()Ljava/util/List;"),
                method("cubism.editor-model.parameter-set.copy", ParameterSet.class, "copy", "(L" + copyContext + ";)L" + set + ";"),
                StaticSelector.classSelector("cubism.editor-model.parameter.class", parameter),
                method("cubism.editor-model.parameter.id", Parameter.class, "id", desc(Id.class)),
                method("cubism.editor-model.parameter.value", Parameter.class, "value", "()F"),
                method("cubism.editor-model.parameter.source", Parameter.class, "source", desc(ParameterSource.class)),
                StaticSelector.classSelector("cubism.editor-model.parameter-source.class", metadata),
                method("cubism.editor-model.parameter-source.minimum", ParameterSource.class, "minimum", "()F"),
                method("cubism.editor-model.parameter-source.maximum", ParameterSource.class, "maximum", "()F"),
                method("cubism.editor-model.parameter-source.default", ParameterSource.class, "defaultValue", "()F"),
                method("cubism.editor-model.parameter-source.name", ParameterSource.class, "name", "()Ljava/lang/String;"),
                method("cubism.editor-model.parameter-source.repeat", ParameterSource.class, "repeat", "()Z"),
                method("cubism.editor-model.parameter-source.morph-target", ParameterSource.class, "morphTarget", "()Z"),
                method("cubism.editor-model.parameter-source.combined", ParameterSource.class, "combined", "()Z"),
                StaticSelector.classSelector("cubism.editor-model.id.class", id),
                method("cubism.editor-model.id.value", Id.class, "value", "()Ljava/lang/String;"),
                StaticSelector.classSelector("cubism.editor-model.guid.class", id),
                method("cubism.editor-model.guid.value", Id.class, "value", "()Ljava/lang/String;"),
                StaticSelector.classSelector("cubism.editor-model.complete-pack.class", completePack),
                StaticSelector.classSelector("cubism.editor-model.edit-mode.class", editMode),
                method("cubism.editor-model.edit-mode.begin", EditMode.class, "begin", "(Ljava/lang/String;)L" + undo + ";"),
                method("cubism.editor-model.edit-mode.end", EditMode.class, "end", "(ZLjava/lang/Object;)Z"),
                StaticSelector.classSelector("cubism.editor-model.undo.class", undo),
                method("cubism.editor-model.undo.add", Undo.class, "add", "(L" + simpleUndo + ";Z)Z"),
                StaticSelector.classSelector("cubism.editor-model.simple-undo.class", simpleUndo),
                StaticSelector.constructor(
                    "cubism.editor-model.simple-undo.create",
                    internal(SimpleUndo.class),
                    "(Ljava/lang/String;L" + set + ";Ljava/lang/Object;)V",
                    StaticSelector.ACCESS_PUBLIC
                ),
                StaticSelector.classSelector("cubism.editor-model.main-frame.class", mainFrame),
                method("cubism.editor-model.app-controller.main-frame", Host.class, "mainFrame", desc(MainFrame.class)),
                method("cubism.editor-model.main-frame.parameter-palette", MainFrame.class, "parameterPalette", desc(ParameterPalette.class)),
                StaticSelector.classSelector("cubism.editor-model.parameter-palette.class", palette),
                method("cubism.editor-model.parameter-palette.view", ParameterPalette.class, "view", desc(ParameterPaletteView.class)),
                StaticSelector.classSelector("cubism.editor-model.parameter-palette-view.class", paletteView),
                method("cubism.editor-model.parameter-palette-view.operation", ParameterPaletteView.class, "operation", desc(ParameterOperation.class)),
                method("cubism.editor-model.complete-pack.update-parameter", CompletePack.class, "updateParameter", "(Z)V"),
                method("cubism.editor-model.complete-pack.repaint-canvas", CompletePack.class, "repaintCanvas", "(Z)V"),
                StaticSelector.classSelector("cubism.editor-model.parameter-operation.class", operation),
                method("cubism.editor-model.parameter-operation.set-value", ParameterOperation.class, "setValue", "(L" + metadata + ";F)V")
            ),
            Host.class.getClassLoader()
        );
    }

    private static StaticSelector method(String alias, Class<?> owner, String name, String descriptor) {
        return StaticSelector.method(alias, internal(owner), name, descriptor, StaticSelector.ACCESS_PUBLIC);
    }

    private static String internal(Class<?> type) {
        return type.getName().replace('.', '/');
    }

    private static String desc(Class<?> type) {
        return "()L" + internal(type) + ";";
    }

    public static final class Host {
        static final Host INSTANCE = new Host();
        static Object currentDocument;
        static CompletePack completePack;
        static MainFrame mainFrame;
        public static Host instance() { return INSTANCE; }
        public Document currentDocument() { return (Document) currentDocument; }
        public CompletePack completePack() { return completePack; }
        public MainFrame mainFrame() { return mainFrame; }
        static void install(Fixture fixture) {
            currentDocument = fixture.document;
            completePack = fixture.completePack;
            mainFrame = fixture.mainFrame;
        }
    }

    public static final class Document {
        final ModelSource source;
        final EditMode editMode;
        int dirtyUpdates;
        Document(ModelSource source, EditMode editMode) {
            this.source = source;
            this.editMode = editMode;
        }
        public ModelSource modelSource() { return source; }
        public EditMode editMode() { return editMode; }
        public void markDirty() { dirtyUpdates++; }
        public Object lastActiveView() { return null; }
    }

    public static final class ModelSource {
        final Id guid;
        Model currentInstance;
        ModelSource(String id, Model model) { guid = new Id(id); currentInstance = model; }
        public Id guid() { return guid; }
        public Model currentInstance() { return currentInstance; }
        public List<ParameterSource> allParameters() {
            return currentInstance.parameterSet.parameters.stream().map(Parameter::source).toList();
        }
    }

    public static final class Model {
        final ParameterSet parameterSet;
        Model(ParameterSet parameterSet) { this.parameterSet = parameterSet; }
        public ParameterSet parameterSet() { return parameterSet; }
    }

    public static final class ParameterSet {
        final List<Parameter> parameters;
        int updateVersion;
        int directCopyCalls;
        boolean failUndoPreparation;
        ParameterSet(List<Parameter> parameters) { this.parameters = parameters; }
        public List<Parameter> parameters() { return parameters; }
        public ParameterSet copy(CopyContext context) {
            if (context == null) throw new IllegalArgumentException("copy context is required");
            directCopyCalls++;
            return copyForUndo();
        }
        ParameterSet copyForUndo() {
            if (failUndoPreparation) throw new IllegalStateException("snapshot failed");
            return new ParameterSet(parameters.stream()
                .map(parameter -> new Parameter(parameter.id().value(), parameter.value))
                .toList());
        }
        void restore(ParameterSet snapshot) {
            for (int index = 0; index < parameters.size(); index++) {
                parameters.get(index).value = snapshot.parameters.get(index).value;
            }
            updateVersion++;
        }
    }

    public static final class CopyContext {
    }

    public static final class Parameter {
        final Id id;
        final ParameterSource source;
        float value;
        Parameter(String id, float value) {
            this.id = new Id(id);
            this.source = new ParameterSource();
            this.value = value;
        }
        public Id id() { return id; }
        public float value() { return value; }
        public ParameterSource source() { return source; }
    }

    public static final class ParameterSource {
        public float minimum() { return -30.0F; }
        public float maximum() { return 30.0F; }
        public float defaultValue() { return 0.0F; }
        public String name() { return "Angle X"; }
        public boolean repeat() { return false; }
        public boolean morphTarget() { return false; }
        public boolean combined() { return false; }
    }

    public static final class CompletePack {
        int parameterRefreshes;
        int canvasRepaints;
        public void updateParameter(boolean immediate) { parameterRefreshes++; }
        public void repaintCanvas(boolean immediate) { canvasRepaints++; }
    }

    public static final class Undo {
        final float before;
        final java.util.ArrayList<SimpleUndo> edits = new java.util.ArrayList<>();
        Undo(float before) { this.before = before; }
        public boolean add(SimpleUndo undo, boolean force) {
            if (!force) throw new IllegalArgumentException();
            edits.add(undo);
            return true;
        }
        void undo() {
            for (int index = edits.size() - 1; index >= 0; index--) edits.get(index).undo();
        }
        void redo() {
            for (SimpleUndo edit : edits) edit.redo();
        }
    }

    public static final class SimpleUndo {
        final ParameterSet target;
        final ParameterSet undoData;
        ParameterSet redoData;
        public SimpleUndo(String name, ParameterSet target, Object copyContext) {
            this.target = target;
            this.undoData = target.copyForUndo();
        }
        void undo() {
            redoData = target.copyForUndo();
            target.restore(undoData);
        }
        void redo() {
            target.restore(redoData);
        }
    }

    public static final class EditMode {
        final ParameterSet set;
        int beginCalls;
        int endCalls;
        int committedEdits;
        int cancelledEdits;
        boolean activeEdit;
        Undo activeUndo;
        Undo committedUndo;
        EditMode(ParameterSet set) { this.set = set; }
        public Undo begin(String action) {
            if (activeEdit) throw new IllegalStateException("previous edit not finished");
            beginCalls++;
            activeEdit = true;
            activeUndo = new Undo(set.parameters.get(0).value);
            return activeUndo;
        }
        public boolean end(boolean cancelled, Object undoRedoCallback) {
            if (!activeEdit) throw new IllegalStateException("no active edit");
            endCalls++;
            activeEdit = false;
            if (cancelled) {
                cancelledEdits++;
            } else {
                committedEdits++;
                committedUndo = activeUndo;
            }
            activeUndo = null;
            return !cancelled;
        }
        void undo() { committedUndo.undo(); }
        void redo() { committedUndo.redo(); }
    }

    public static final class MainFrame {
        final ParameterPalette palette;
        MainFrame(ParameterPalette palette) { this.palette = palette; }
        public ParameterPalette parameterPalette() { return palette; }
    }

    public static final class ParameterPalette {
        final ParameterPaletteView view;
        ParameterPalette(ParameterPaletteView view) { this.view = view; }
        public ParameterPaletteView view() { return view; }
    }

    public static final class ParameterPaletteView {
        final ParameterOperation operation;
        ParameterPaletteView(ParameterOperation operation) { this.operation = operation; }
        public ParameterOperation operation() { return operation; }
    }

    public static final class ParameterOperation {
        final ParameterSet set;
        int calls;
        float lastValue;
        boolean fail;
        ParameterOperation(ParameterSet set) { this.set = set; }
        public void setValue(ParameterSource source, float value) {
            calls++;
            if (fail) throw new IllegalStateException("native write failed");
            Parameter parameter = set.parameters.stream()
                .filter(candidate -> candidate.source() == source)
                .findFirst()
                .orElseThrow();
            parameter.value = Math.max(source.minimum(), Math.min(value, source.maximum()));
            lastValue = parameter.value;
            set.updateVersion++;
        }
    }

    public record Id(String value) {
        public String value() { return value; }
    }

    static final class Fixture {
        final Parameter parameter;
        final ParameterSet parameterSet;
        final ParameterOperation operation;
        final CompletePack completePack;
        final MainFrame mainFrame;
        final EditMode editMode;
        final Document document;

        Fixture(String id, float value) {
            parameter = new Parameter("ParamAngleX", value);
            parameterSet = new ParameterSet(List.of(parameter));
            Model model = new Model(parameterSet);
            editMode = new EditMode(parameterSet);
            document = new Document(new ModelSource(id, model), editMode);
            operation = new ParameterOperation(parameterSet);
            completePack = new CompletePack();
            mainFrame = new MainFrame(new ParameterPalette(new ParameterPaletteView(operation)));
        }
    }
}
