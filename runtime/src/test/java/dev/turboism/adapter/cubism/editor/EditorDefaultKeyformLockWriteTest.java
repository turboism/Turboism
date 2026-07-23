package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.EditorDefaultKeyformLockReadSelectorContract;
import dev.turboism.mapping.verification.EditorDefaultKeyformLockWriteSelectorContract;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EditorDefaultKeyformLockWriteTest {

    @AfterEach
    void clearHost() {
        Host.currentDocument = null;
        Host.completePack = null;
        Host.mainFrame = null;
    }

    @Test
    void lockChangesUseOneUndoableEditorTransaction() {
        Fixture fixture = new Fixture("model-a");
        Host.install(fixture);
        var model = new EditorBackedCubismModelAccess(
            resolver(true), "session-a"
        ).active();

        assertEquals(false, model.defaultKeyformLocked());

        model.setDefaultKeyformLocked(true);

        assertEquals(true, model.defaultKeyformLocked());
        assertEquals(1, fixture.editMode.beginCalls);
        assertEquals(1, fixture.editMode.committedEdits);
        assertEquals(1, fixture.document.dirtyUpdates);
        assertEquals(1, fixture.operation.refreshes);
        assertEquals(1, fixture.completePack.parameterRefreshes);
        assertEquals(1, fixture.completePack.canvasRepaints);

        fixture.editMode.undo();
        assertEquals(false, model.defaultKeyformLocked());
        fixture.editMode.redo();
        assertEquals(true, model.defaultKeyformLocked());
        assertEquals(3, fixture.operation.refreshes);
        assertEquals(3, fixture.completePack.parameterRefreshes);
        assertEquals(3, fixture.completePack.canvasRepaints);

        model.setDefaultKeyformLocked(true);
        assertEquals(1, fixture.editMode.beginCalls, "unchanged state must not create history");
    }

    @Test
    void lockChangesFailClosedWithoutSeparateWriteEvidence() {
        Fixture fixture = new Fixture("model-a");
        Host.install(fixture);
        var model = new EditorBackedCubismModelAccess(
            resolver(false), "session-a"
        ).active();

        assertEquals(false, model.defaultKeyformLocked());
        assertThrows(
            UnsupportedOperationException.class,
            () -> model.setDefaultKeyformLocked(true)
        );
        assertEquals(false, model.defaultKeyformLocked());
        assertEquals(0, fixture.editMode.beginCalls);
        assertEquals(0, fixture.document.dirtyUpdates);
    }

    private static VerifiedMemberResolver resolver(final boolean writeAuthorized) {
        final String host = internal(Host.class);
        final String document = internal(Document.class);
        final String source = internal(ModelSource.class);
        final String model = internal(Model.class);
        final String id = internal(Id.class);
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
                EditorDefaultKeyformLockReadSelectorContract.CAPABILITY_ID,
                EditorDefaultKeyformLockWriteSelectorContract.CAPABILITY_ID
            )
            : java.util.Set.of(
                "cubism.editor-model.read",
                "cubism.editor-model.write",
                EditorDefaultKeyformLockReadSelectorContract.CAPABILITY_ID
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
                method("cubism.editor-model.model-source.default-keyform-locked", ModelSource.class, "defaultKeyformLocked", "()Z"),
                method("cubism.editor-model.model-source.set-default-keyform-locked", ModelSource.class, "setDefaultKeyformLocked", "(Z)V"),
                StaticSelector.classSelector("cubism.editor-model.model.class", model),
                StaticSelector.classSelector("cubism.editor-model.guid.class", id),
                method("cubism.editor-model.guid.value", Id.class, "value", "()Ljava/lang/String;"),
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
                StaticSelector.constructor(
                    "cubism.editor-model.simple-undo.create",
                    internal(SimpleUndo.class),
                    "(Ljava/lang/String;L" + copyable + ";Ljava/lang/Object;)V",
                    StaticSelector.ACCESS_PUBLIC
                )
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
        public String value() {
            return value;
        }
    }

    public static final class Model {
    }

    public static final class ModelSource implements Copyable {
        final Id guid;
        final Model model = new Model();
        boolean locked;

        ModelSource(final String id) {
            guid = new Id(id);
        }

        public Id guid() { return guid; }
        public Model currentInstance() { return model; }
        public boolean defaultKeyformLocked() { return locked; }
        public void setDefaultKeyformLocked(final boolean value) { locked = value; }
        @Override public Object snapshot() { return Boolean.valueOf(locked); }
        @Override public void restore(final Object snapshot) { locked = (Boolean) snapshot; }
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

        void redo() {
            target.restore(after);
        }
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
            for (int index = edits.size() - 1; index >= 0; index--) {
                edits.get(index).undo();
            }
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
        final EditMode editMode = new EditMode();
        final Document document;
        final CompletePack completePack = new CompletePack();
        final ParameterOperation operation = new ParameterOperation();
        final MainFrame mainFrame = new MainFrame(
            new ParameterPalette(new ParameterPaletteView(operation))
        );

        Fixture(final String id) {
            source = new ModelSource(id);
            document = new Document(source, editMode);
        }
    }
}
