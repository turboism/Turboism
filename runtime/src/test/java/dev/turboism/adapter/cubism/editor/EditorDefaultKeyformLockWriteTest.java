package dev.turboism.adapter.cubism.editor;

import static dev.turboism.adapter.cubism.editor.EditorTestFixtures.desc;
import static dev.turboism.adapter.cubism.editor.EditorTestFixtures.internal;
import static dev.turboism.adapter.cubism.editor.EditorTestFixtures.method;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.adapter.cubism.editor.transaction.RuntimeAuthoringTransactionProvider;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorDefaultKeyformLockReadSelectorContract;
import dev.turboism.mapping.verification.selector.EditorDefaultKeyformLockWriteSelectorContract;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOptions;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOutcome;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

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
        var model = new EditorBackedCubismModelAccess(resolver(true), "session-a").active();

        assertEquals(false, model.defaultKeyformLocked());

        model.setDefaultKeyformLocked(true);

        assertEquals(true, model.defaultKeyformLocked());
        assertEquals(1, fixture.document.undoManager().entries().size(), "one native Undo entry per write");
        assertEquals(
                "Turboism: Set Default Keyform Lock",
                fixture.document.undoManager().entries().get(0).presentationName(),
                "the frozen Undo label is the write label");
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
        assertEquals(1, fixture.document.undoManager().entries().size(), "unchanged state must not create history");
    }

    /**
     * Ambient atomicity (design C2 §7 Q1): a migrated envelope write refuses to join an ambient root
     * it could not roll back, and the rejected write leaves no Undo entry, no dirty mark and no
     * refresh behind. The resolver here does not authorize the edit-session row the join needs.
     */
    @Test
    void lockWriteInsideAmbientTransactionFailsClosedWithoutTheJoinRow() {
        Fixture fixture = new Fixture("model-a");
        Host.install(fixture);
        var access = new EditorBackedCubismModelAccess(resolver(true), "session-a");
        var model = access.active();
        var service = ((RuntimeAuthoringTransactionProvider) access).authoringTransactions("plugin.test");

        var result = service.execute(AuthoringTransactionOptions.of("Grouped lock write"), () -> {
            model.setDefaultKeyformLocked(true);
            return null;
        });

        assertEquals(AuthoringTransactionOutcome.ROLLED_BACK, result.outcome());
        assertEquals(false, model.defaultKeyformLocked(), "the rejected write must not reach the model");
        assertTrue(fixture.document.undoManager().entries().isEmpty(), "a rejected write admits no Undo entry");
        assertEquals(0, fixture.document.dirtyUpdates);
        assertEquals(0, fixture.operation.refreshes);
        assertEquals(0, fixture.completePack.parameterRefreshes);
        assertEquals(0, fixture.completePack.canvasRepaints);
    }

    @Test
    void lockChangesFailClosedWithoutSeparateWriteEvidence() {
        Fixture fixture = new Fixture("model-a");
        Host.install(fixture);
        var model = new EditorBackedCubismModelAccess(resolver(false), "session-a").active();

        assertEquals(false, model.defaultKeyformLocked());
        assertThrows(UnsupportedOperationException.class, () -> model.setDefaultKeyformLocked(true));
        assertEquals(false, model.defaultKeyformLocked());
        assertTrue(fixture.document.undoManager().entries().isEmpty());
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
        final java.util.Set<String> capabilities = writeAuthorized
                ? java.util.Set.of(
                        "cubism.editor-model.read",
                        "cubism.editor-model.write",
                        "cubism.editor-history.read",
                        EditorDefaultKeyformLockReadSelectorContract.CAPABILITY_ID,
                        EditorDefaultKeyformLockWriteSelectorContract.CAPABILITY_ID)
                : java.util.Set.of(
                        "cubism.editor-model.read",
                        "cubism.editor-model.write",
                        "cubism.editor-history.read",
                        EditorDefaultKeyformLockReadSelectorContract.CAPABILITY_ID);
        return TestVerifiedResolvers.create(
                "5.3.02",
                "adapter.editor-model.readwrite",
                capabilities,
                List.of(
                        StaticSelector.classSelector("cubism.editor-model.app-controller.class", host),
                        StaticSelector.staticMethod(
                                "cubism.editor-model.app-controller.instance",
                                host,
                                "instance",
                                desc(Host.class),
                                StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC),
                        method(
                                "cubism.editor-model.app-controller.current-document",
                                Host.class,
                                "currentDocument",
                                desc(Document.class)),
                        method(
                                "cubism.editor-model.app-controller.complete-pack",
                                Host.class,
                                "completePack",
                                desc(CompletePack.class)),
                        method(
                                "cubism.editor-model.app-controller.main-frame",
                                Host.class,
                                "mainFrame",
                                desc(MainFrame.class)),
                        StaticSelector.classSelector("cubism.editor-model.modeling-document.class", document),
                        method(
                                "cubism.editor-model.modeling-document.model-source",
                                Document.class,
                                "modelSource",
                                desc(ModelSource.class)),
                        method(
                                "cubism.editor-model.modeling-document.edit-mode",
                                Document.class,
                                "editMode",
                                desc(EditMode.class)),
                        method("cubism.editor-model.modeling-document.mark-dirty", Document.class, "markDirty", "()V"),
                        StaticSelector.classSelector("cubism.editor-model.model-source.class", source),
                        method("cubism.editor-model.model-source.guid", ModelSource.class, "guid", desc(Id.class)),
                        method(
                                "cubism.editor-model.model-source.current-instance",
                                ModelSource.class,
                                "currentInstance",
                                desc(Model.class)),
                        method(
                                "cubism.editor-model.model-source.default-keyform-locked",
                                ModelSource.class,
                                "defaultKeyformLocked",
                                "()Z"),
                        method(
                                "cubism.editor-model.model-source.set-default-keyform-locked",
                                ModelSource.class,
                                "setDefaultKeyformLocked",
                                "(Z)V"),
                        StaticSelector.classSelector("cubism.editor-model.model.class", model),
                        StaticSelector.classSelector("cubism.editor-model.guid.class", id),
                        method("cubism.editor-model.guid.value", Id.class, "value", "()Ljava/lang/String;"),
                        StaticSelector.classSelector("cubism.editor-model.complete-pack.class", completePack),
                        method(
                                "cubism.editor-model.complete-pack.update-parameter",
                                CompletePack.class,
                                "updateParameter",
                                "(Z)V"),
                        method(
                                "cubism.editor-model.complete-pack.repaint-canvas",
                                CompletePack.class,
                                "repaintCanvas",
                                "(Z)V"),
                        StaticSelector.classSelector("cubism.editor-model.main-frame.class", mainFrame),
                        method(
                                "cubism.editor-model.main-frame.parameter-palette",
                                MainFrame.class,
                                "parameterPalette",
                                desc(ParameterPalette.class)),
                        StaticSelector.classSelector("cubism.editor-model.parameter-palette.class", palette),
                        method(
                                "cubism.editor-model.parameter-palette.view",
                                ParameterPalette.class,
                                "view",
                                desc(ParameterPaletteView.class)),
                        StaticSelector.classSelector("cubism.editor-model.parameter-palette-view.class", paletteView),
                        method(
                                "cubism.editor-model.parameter-palette-view.operation",
                                ParameterPaletteView.class,
                                "operation",
                                desc(ParameterOperation.class)),
                        StaticSelector.classSelector("cubism.editor-model.parameter-operation.class", operation),
                        method(
                                "cubism.editor-model.parameter-operation.refresh",
                                ParameterOperation.class,
                                "refresh",
                                "(Z)V"),
                        StaticSelector.classSelector("cubism.editor-model.edit-mode.class", editMode),
                        method(
                                "cubism.editor-model.edit-mode.begin",
                                EditMode.class,
                                "begin",
                                "(Ljava/lang/String;)L" + undo + ";"),
                        method("cubism.editor-model.edit-mode.end", EditMode.class, "end", "(ZLjava/lang/Object;)Z"),
                        StaticSelector.classSelector("cubism.editor-model.undo.class", undo),
                        method("cubism.editor-model.undo.add", Undo.class, "add", "(Ljava/lang/Object;Z)Z"),
                        method(
                                "cubism.editor-model.undo.add-listener",
                                Undo.class,
                                "addListener",
                                "(L" + internal(UndoListener.class) + ";)Z"),
                        StaticSelector.classSelector(
                                "cubism.editor-model.undo-listener.class", internal(UndoListener.class)),
                        StaticSelector.field(
                                "cubism.editor-model.undo.local-simple-factory-instance",
                                internal(LocalSimpleUndoFactory.class),
                                "INSTANCE",
                                "L" + internal(LocalSimpleUndoFactory.class) + ";",
                                StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC),
                        method(
                                "cubism.editor-model.undo.local-simple-factory-create",
                                LocalSimpleUndoFactory.class,
                                "create",
                                "(Ljava/lang/String;ZL" + internal(Action.class) + ";L"
                                        + internal(Action.class) + ";L" + internal(UpdateAction.class) + ";)L"
                                        + internal(SimpleUndo.class) + ";"),
                        method(
                                "cubism.editor-history.document.undo-manager",
                                Document.class,
                                "undoManager",
                                desc(UndoManager.class)),
                        StaticSelector.classSelector(
                                "cubism.editor-history.manager.class", internal(UndoManager.class)),
                        method(
                                "cubism.editor-history.manager.entries",
                                UndoManager.class,
                                "entries",
                                "()Ljava/util/List;"),
                        method("cubism.editor-history.manager.position", UndoManager.class, "position", "()I"),
                        method("cubism.editor-history.manager.can-undo", UndoManager.class, "canUndo", "()Z"),
                        method("cubism.editor-history.manager.can-redo", UndoManager.class, "canRedo", "()Z"),
                        StaticSelector.classSelector("cubism.editor-history.entry.class", internal(Undo.class)),
                        method(
                                "cubism.editor-history.entry.presentation-name",
                                Undo.class,
                                "presentationName",
                                "()Ljava/lang/String;"),
                        method("cubism.editor-history.entry.significant", Undo.class, "significant", "()Z")),
                Host.class.getClassLoader());
    }

    public interface Copyable {
        Object snapshot();

        void restore(Object snapshot);
    }

    public interface UndoListener {
        void executed(Object event);
    }

    public interface Action {
        void run();
    }

    public interface UpdateAction {
        void run(Object ignored);
    }

    public record Id(String value) {
        public String value() {
            return value;
        }
    }

    public static final class Model {}

    public static final class ModelSource implements Copyable {
        final Id guid;
        final Model model = new Model();
        boolean locked;

        ModelSource(final String id) {
            guid = new Id(id);
        }

        public Id guid() {
            return guid;
        }

        public Model currentInstance() {
            return model;
        }

        public boolean defaultKeyformLocked() {
            return locked;
        }

        public void setDefaultKeyformLocked(final boolean value) {
            locked = value;
        }

        @Override
        public Object snapshot() {
            return Boolean.valueOf(locked);
        }

        @Override
        public void restore(final Object snapshot) {
            locked = (Boolean) snapshot;
        }
    }

    public static final class Document {
        final ModelSource source;
        final EditMode editMode;
        final UndoManager undoManager;
        int dirtyUpdates;

        Document(final ModelSource source, final EditMode editMode) {
            this.source = source;
            this.editMode = editMode;
            this.undoManager = new UndoManager(editMode);
        }

        public ModelSource modelSource() {
            return source;
        }

        public EditMode editMode() {
            return editMode;
        }

        public UndoManager undoManager() {
            return undoManager;
        }

        public void markDirty() {
            dirtyUpdates++;
        }
    }

    /** Native undo-manager projection: one history entry per committed edit bracket. */
    public static final class UndoManager {
        private final EditMode editMode;

        UndoManager(final EditMode editMode) {
            this.editMode = editMode;
        }

        public List<Undo> entries() {
            return List.copyOf(editMode.entries);
        }

        public int position() {
            return editMode.entries.size();
        }

        public boolean canUndo() {
            return !editMode.entries.isEmpty();
        }

        public boolean canRedo() {
            return false;
        }
    }

    public static final class CompletePack {
        int parameterRefreshes;
        int canvasRepaints;

        public void updateParameter(final boolean immediate) {
            parameterRefreshes++;
        }

        public void repaintCanvas(final boolean immediate) {
            canvasRepaints++;
        }
    }

    public static final class ParameterOperation {
        int refreshes;

        public void refresh(final boolean immediate) {
            refreshes++;
        }
    }

    public static final class ParameterPaletteView {
        final ParameterOperation operation;

        ParameterPaletteView(final ParameterOperation operation) {
            this.operation = operation;
        }

        public ParameterOperation operation() {
            return operation;
        }
    }

    public static final class ParameterPalette {
        final ParameterPaletteView view;

        ParameterPalette(final ParameterPaletteView view) {
            this.view = view;
        }

        public ParameterPaletteView view() {
            return view;
        }
    }

    public static final class MainFrame {
        final ParameterPalette palette;

        MainFrame(final ParameterPalette palette) {
            this.palette = palette;
        }

        public ParameterPalette parameterPalette() {
            return palette;
        }
    }

    public static final class SimpleUndo {
        final Action redo;
        final Action undo;
        final UpdateAction updated;

        SimpleUndo(final Action redo, final Action undo, final UpdateAction updated) {
            this.redo = redo;
            this.undo = undo;
            this.updated = updated;
        }

        void undo() {
            undo.run();
            updated.run(null);
        }

        void redo() {
            redo.run();
            updated.run(null);
        }
    }

    public static final class LocalSimpleUndoFactory {
        public static final LocalSimpleUndoFactory INSTANCE = new LocalSimpleUndoFactory();

        public SimpleUndo create(
                final String name,
                final boolean ignored,
                final Action redo,
                final Action undo,
                final UpdateAction updated) {
            return new SimpleUndo(redo, undo, updated);
        }
    }

    public static final class Undo {
        final String label;
        final List<SimpleUndo> edits = new ArrayList<>();
        final List<UndoListener> listeners = new ArrayList<>();

        Undo(final String label) {
            this.label = label;
        }

        public String presentationName() {
            return label;
        }

        public boolean significant() {
            return true;
        }

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
        final List<Undo> entries = new ArrayList<>();
        Undo active;
        Undo committed;

        public Undo begin(final String action) {
            active = new Undo(action);
            return active;
        }

        public boolean end(final boolean cancelled, final Object callback) {
            if (!cancelled) {
                committed = active;
                entries.add(active);
            }
            active = null;
            return !cancelled;
        }

        void undo() {
            committed.undo();
        }

        void redo() {
            committed.redo();
        }
    }

    public static final class Host {
        static final Host INSTANCE = new Host();
        static Document currentDocument;
        static CompletePack completePack;
        static MainFrame mainFrame;

        public static Host instance() {
            return INSTANCE;
        }

        public Document currentDocument() {
            return currentDocument;
        }

        public CompletePack completePack() {
            return completePack;
        }

        public MainFrame mainFrame() {
            return mainFrame;
        }

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
        final MainFrame mainFrame = new MainFrame(new ParameterPalette(new ParameterPaletteView(operation)));

        Fixture(final String id) {
            source = new ModelSource(id);
            document = new Document(source, editMode);
        }
    }
}
