package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.EditorModelNameWriteSelectorContract;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Editor model-name authoring write: the standard transaction envelope
 * (edit mode begin/end, SimpleUndo over the ICopyable model source, undo
 * registration, dirty marking, complete-pack refresh) and never bypasses Undo.
 */
class EditorModelNameWriteTest {

    @Test
    void writesModelNameThroughUndoEnvelopeAndRefreshes() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var access = new EditorBackedCubismModelAccess(
            resolver(true), "session-a"
        );
        final var model = access.active();
        assertEquals("Original", model.name());

        model.setName("Renamed");

        assertEquals("Renamed", fixture.document.source.name);
        assertTrue(fixture.document.dirty);
        assertEquals(1, fixture.document.editMode.edits.size());
        assertEquals(1, fixture.document.pack.parameterRefreshCount);
        assertEquals(1, fixture.document.pack.repaintCount);
        assertEquals(1, fixture.document.source.updateCount);
        assertEquals("Renamed", access.active().name());
    }

    @Test
    void sameNameSkipsTheUndoEnvelope() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(
            resolver(true), "session-a"
        ).active();

        model.setName("Original");

        assertEquals(0, fixture.document.editMode.edits.size());
        assertTrue(!fixture.document.dirty);
        assertEquals(0, fixture.document.source.updateCount);
    }

    @Test
    void writeFailsClosedWithoutExactWriteEvidence() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(
            resolver(false), "session-a"
        ).active();

        assertThrows(UnsupportedOperationException.class, () -> model.setName("Renamed"));
        assertEquals("Original", fixture.document.source.name);
    }

    @Test
    void blankNameIsRejectedBeforeAnyHostCall() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(
            resolver(true), "session-a"
        ).active();

        assertThrows(IllegalArgumentException.class, () -> model.setName("  "));
        assertEquals("Original", fixture.document.source.name);
        assertEquals(0, fixture.document.editMode.edits.size());
    }

    private static VerifiedMemberResolver resolver(final boolean includeWrite) {
        final java.util.HashSet<String> capabilities = new java.util.HashSet<>();
        capabilities.add(dev.turboism.mapping.verification.EditorObjectReadSelectorContract.CAPABILITY_ID);
        if (includeWrite) {
            capabilities.add(EditorModelNameWriteSelectorContract.CAPABILITY_ID);
        }
        return TestVerifiedResolvers.create(
            "5.3.02",
            EditorModelNameWriteSelectorContract.ADAPTER_SLICE_ID,
            capabilities,
            selectors(),
            Host.class.getClassLoader()
        );
    }

    private static List<StaticSelector> selectors() {
        final List<StaticSelector> values = new ArrayList<>();
        values.add(StaticSelector.classSelector("cubism.editor-model.app-controller.class", internal(Host.class)));
        values.add(StaticSelector.staticMethod("cubism.editor-model.app-controller.instance", internal(Host.class), "instance", "()L" + internal(Host.class) + ";", StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
        values.add(method("cubism.editor-model.app-controller.current-document", Host.class, "currentDocument", "()L" + internal(Document.class) + ";"));
        values.add(method("cubism.editor-model.app-controller.complete-pack", Host.class, "completePack", "()L" + internal(CompletePack.class) + ";"));
        values.add(StaticSelector.classSelector("cubism.editor-model.modeling-document.class", internal(Document.class)));
        values.add(method("cubism.editor-model.modeling-document.model-source", Document.class, "modelSource", "()L" + internal(ModelSource.class) + ";"));
        values.add(method("cubism.editor-model.modeling-document.edit-mode", Document.class, "editMode", "()L" + internal(EditMode.class) + ";"));
        values.add(method("cubism.editor-model.modeling-document.mark-dirty", Document.class, "markDirty", "()V"));
        values.add(method("cubism.editor-model.edit-mode.begin", EditMode.class, "begin", "(Ljava/lang/String;)" + type(GroupUndo.class)));
        values.add(method("cubism.editor-model.edit-mode.end", EditMode.class, "end", "(ZLjava/lang/Object;)V"));
        values.add(method("cubism.editor-model.undo.add", GroupUndo.class, "add", "(" + type(Undo.class) + "Z)Z"));
        values.add(StaticSelector.classSelector("cubism.editor-model.model-source.class", internal(ModelSource.class)));
        values.add(method("cubism.editor-model.model-source.name", ModelSource.class, "name", "()Ljava/lang/String;"));
        values.add(method("cubism.editor-model.model-source.set-name", ModelSource.class, "setName", "(Ljava/lang/String;)V"));
        values.add(method("cubism.editor-model.model-source.update-instances", ModelSource.class, "updateInstances", "()V"));
        values.add(method("cubism.editor-model.model-source.guid", ModelSource.class, "guid", "()L" + internal(Id.class) + ";"));
        values.add(method("cubism.editor-model.model-source.current-instance", ModelSource.class, "currentInstance", "()L" + internal(Model.class) + ";"));
        values.add(StaticSelector.classSelector("cubism.editor-model.model.class", internal(Model.class)));
        values.add(method("cubism.editor-model.guid.value", Id.class, "value", "()Ljava/lang/String;"));
        values.add(StaticSelector.constructor("cubism.editor-model.simple-undo.create", internal(Undo.class), "(Ljava/lang/String;" + type(ModelSource.class) + type(Object.class) + ")V", StaticSelector.ACCESS_PUBLIC));
        values.add(method("cubism.editor-model.complete-pack.update-parameter", CompletePack.class, "updateParameter", "(Z)V"));
        values.add(method("cubism.editor-model.complete-pack.repaint-canvas", CompletePack.class, "repaintCanvas", "(Z)V"));
        return List.copyOf(values);
    }

    private static StaticSelector method(
        final String alias,
        final Class<?> owner,
        final String name,
        final String descriptor
    ) {
        return StaticSelector.method(
            alias, internal(owner), name, descriptor, StaticSelector.ACCESS_PUBLIC
        );
    }

    private static String internal(final Class<?> type) {
        return type.getName().replace('.', '/');
    }

    private static String type(final Class<?> type) {
        return "L" + internal(type) + ";";
    }

    private static final class Fixture {
        final Document document = new Document();
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
            return document.pack;
        }
    }

    public static final class Document {
        final ModelSource source = new ModelSource();
        final EditMode editMode = new EditMode();
        final CompletePack pack = new CompletePack();
        boolean dirty;

        public ModelSource modelSource() {
            return source;
        }

        public EditMode editMode() {
            return editMode;
        }

        public void markDirty() {
            dirty = true;
        }
    }

    public static final class CompletePack {
        int parameterRefreshCount;
        int repaintCount;

        public void updateParameter(final boolean immediate) {
            parameterRefreshCount++;
        }

        public void repaintCanvas(final boolean immediate) {
            repaintCount++;
        }
    }

    public static final class Undo {
        public Undo() {
        }

        public Undo(final String name, final ModelSource source, final Object ignored) {
        }
    }

    public static final class GroupUndo {
        final List<Undo> edits = new ArrayList<>();

        public boolean add(final Undo undo, final boolean merge) {
            edits.add(undo);
            return true;
        }
    }

    public static final class EditMode {
        final List<GroupUndo> edits = new ArrayList<>();

        public GroupUndo begin(final String label) {
            final GroupUndo undo = new GroupUndo();
            edits.add(undo);
            return undo;
        }

        public void end(final boolean abort, final Object ignored) {
        }
    }

    public static final class ModelSource {
        String name = "Original";
        int updateCount;

        public String name() {
            return name;
        }

        public void setName(final String name) {
            this.name = name;
        }

        public void updateInstances() {
            updateCount++;
        }

        public Id guid() {
            return new Id("model-a");
        }

        private final Model instance = new Model();

        public Model currentInstance() {
            return instance;
        }
    }

    public static final class Model {
    }

    public static final class Id {
        private final String value;

        Id(final String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }
}
