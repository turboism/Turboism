package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.EditorPartStructureSelectorContract;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.model.Part;
import dev.turboism.sdk.cubism.model.PartId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorPartStructureAccessTest {


    @Test
    void addsCopiesAndDeletesPartsThroughNativeUndo() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(
            resolver(true), "session-a");
        final var parts = access.active().parts();

        final Part created = parts.add("NewPart");
        assertEquals(new PartId("NewPart"), created.id());
        assertEquals(2, fixture.root.children.size());
        assertEquals("NewPart", fixture.root.children.get(1).id.value);
        assertEquals(1, fixture.editMode.edits.size());
        assertTrue(fixture.document.dirty);
        assertEquals(1, fixture.source.updateCount);
        assertTrue(fixture.editMode.edits.get(0).captured);
        assertEquals(500, fixture.root.children.get(1).defaultOrder);

        final Part copied = parts.copy(new PartId("PartChild"));
        assertEquals(3, fixture.root.children.size());
        assertEquals(2, fixture.editMode.edits.size());
        assertEquals("PartChild_1", copied.id().value());

        parts.remove(new PartId("NewPart"));
        assertEquals(2, fixture.root.children.size());
        assertEquals(3, fixture.editMode.edits.size());
        assertEquals(1, fixture.handler.lastRemoved.size());
        assertEquals("NewPart", fixture.handler.lastRemoved.get(0).id.value);

        parts.add("NewPart");
        assertThrows(IllegalArgumentException.class, () -> parts.add("NewPart"));
    }

    @Test
    void missingCapabilityFailsClosedBeforeAnyWrite() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(
            resolver(false), "session-a");
        assertThrows(UnsupportedOperationException.class, () -> access.active().parts().add("NewPart"));
        assertThrows(UnsupportedOperationException.class, () -> access.active().parts().copy(new PartId("PartChild")));
        assertThrows(UnsupportedOperationException.class, () -> access.active().parts().remove(new PartId("PartChild")));
        assertEquals(0, fixture.editMode.edits.size());
        assertTrue(!fixture.document.dirty);
    }

    @Test
    void absentPartFailsClosed() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(
            resolver(true), "session-a");
        final var parts = access.active().parts();
        assertThrows(java.util.NoSuchElementException.class, () -> parts.copy(new PartId("Missing")));
        assertThrows(java.util.NoSuchElementException.class, () -> parts.remove(new PartId("Missing")));
        assertThrows(java.util.NoSuchElementException.class, () -> parts.add("NewPart", new PartId("Missing")));
        assertEquals(0, fixture.editMode.edits.size());
    }

    private static VerifiedMemberResolver resolver(final boolean includeCapability) {
        final List<StaticSelector> selectors = new ArrayList<>();
        selectors.add(StaticSelector.classSelector("cubism.editor-model.app-controller.class", internal(Host.class)));
        selectors.add(StaticSelector.staticMethod("cubism.editor-model.app-controller.instance", internal(Host.class), "instance",
            desc(Host.class), StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
        selectors.add(method("cubism.editor-model.app-controller.current-document", Host.class, "currentDocument", desc(Document.class)));
        selectors.add(method("cubism.editor-model.app-controller.complete-pack", Host.class, "completePack", desc(CompletePack.class)));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.modeling-document.class", internal(Document.class)));
        selectors.add(method("cubism.editor-model.modeling-document.model-source", Document.class, "modelSource", desc(ModelSource.class)));
        selectors.add(method("cubism.editor-model.modeling-document.edit-mode", Document.class, "editMode", desc(EditMode.class)));
        selectors.add(method("cubism.editor-model.model-source.guid", ModelSource.class, "guid", desc(Id.class)));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.model.class", internal(Model.class)));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.guid.class", internal(Id.class)));
        selectors.add(method("cubism.editor-model.guid.value", Id.class, "value", "()Ljava/lang/String;"));
        selectors.add(method("cubism.editor-model.modeling-document.mark-dirty", Document.class, "markDirty", "()V"));
        selectors.add(method("cubism.editor-model.edit-mode.begin", EditMode.class, "begin", "(Ljava/lang/String;)" + type(GroupUndo.class)));
        selectors.add(method("cubism.editor-model.edit-mode.end", EditMode.class, "end", "(ZLjava/lang/Object;)V"));
        selectors.add(method("cubism.editor-model.undo.add", GroupUndo.class, "add", "(" + type(Undo.class) + "Z)Z"));
        selectors.add(method("cubism.editor-model.undo.add-listener", Undo.class, "addListener", "(" + type(Listener.class) + ")Z"));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.undo-listener.class", internal(Listener.class)));
        selectors.add(method("cubism.editor-model.model-source.update-instances", ModelSource.class, "updateInstances", "()V"));
        selectors.add(method("cubism.editor-model.complete-pack.update-part-palette", CompletePack.class, "updateParts", "(Z)V"));
        selectors.add(method("cubism.editor-model.complete-pack.repaint-canvas", CompletePack.class, "repaint", "(Z)V"));
        selectors.add(method("cubism.editor-model.model-source.parts", ModelSource.class, "parts", "()Ljava/util/List;"));
        selectors.add(method("cubism.editor-model.model-source.current-instance", ModelSource.class, "currentInstance", desc(Model.class)));
        selectors.add(method("cubism.editor-model.model-source.root-part", ModelSource.class, "rootPart", desc(PartSource.class)));
        selectors.add(method("cubism.editor-model.model-source.handler", ModelSource.class, "handler", desc(ModelHandler.class)));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.model-handler.class", internal(ModelHandler.class)));
        selectors.add(StaticSelector.staticMethod("cubism.editor-model.model-handler.create-free-id-default", internal(ModelHandler.class),
            "createFreeIdDefault", "(L" + internal(ModelHandler.class) + ";L" + internal(Id.class) + ";L" + internal(Object.class)
                + ";ILjava/lang/Object;)L" + internal(Id.class) + ";", StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
        selectors.add(method("cubism.editor-model.model-handler.remove-objects", ModelHandler.class, "removeObjects",
            "(Ljava/util/List;L" + internal(Model.class) + ";Z)L" + internal(Undo.class) + ";"));
        selectors.add(StaticSelector.staticMethod("cubism.editor-model.copy-helper.copy", internal(CopyHelper.class),
            "copy", "(L" + internal(Object.class) + ";L" + internal(Object.class) + ";ILjava/lang/Object;)L" + internal(Object.class)
                + ";", StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.part.class", internal(PartView.class)));
        selectors.add(method("cubism.editor-model.part.source", PartView.class, "source", desc(PartSource.class)));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.part-source.class", internal(PartSource.class)));
        selectors.add(StaticSelector.constructor("cubism.editor-model.part-source.create", internal(PartSource.class),
            "(L" + internal(ModelSource.class) + ";)V", StaticSelector.ACCESS_PUBLIC));
        selectors.add(method("cubism.editor-model.part-source.id", PartSource.class, "id", desc(Id.class)));
        selectors.add(method("cubism.editor-model.part-source.set-id", PartSource.class, "setId", "(L" + internal(Id.class) + ";)V"));
        selectors.add(method("cubism.editor-model.part-source.set-guid", PartSource.class, "setGuid", "(L" + internal(Id.class) + ";)V"));
        selectors.add(method("cubism.editor-model.part-source.set-local-name", PartSource.class, "setLocalName", "(Ljava/lang/String;)V"));
        selectors.add(method("cubism.editor-model.part-source.set-default-order", PartSource.class, "setDefaultOrder", "(I)V"));
        selectors.add(method("cubism.editor-model.part-source.children", PartSource.class, "children", "()Ljava/util/List;"));
        selectors.add(method("cubism.editor-model.part-source.parent", PartSource.class, "parent", desc(PartSource.class)));
        selectors.add(method("cubism.editor-model.part-source.handler", PartSource.class, "handler", desc(PartHandler.class)));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.part-handler.class", internal(PartHandler.class)));
        selectors.add(method("cubism.editor-model.part-handler.add-part-child", PartHandler.class, "addPartChild",
            "(L" + internal(Object.class) + ";I)L" + internal(Undo.class) + ";"));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.part-id.class", internal(Id.class)));
        selectors.add(StaticSelector.constructor("cubism.editor-model.part-id.create", internal(Id.class),
            "(Ljava/lang/String;)V", StaticSelector.ACCESS_PUBLIC));
        selectors.add(method("cubism.editor-model.part-id.value", Id.class, "value", "()Ljava/lang/String;"));
        selectors.add(StaticSelector.constructor("cubism.editor-model.part-guid.create", internal(Id.class),
            "()V", StaticSelector.ACCESS_PUBLIC));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.id.class", internal(Id.class)));
        selectors.add(method("cubism.editor-model.id.value", Id.class, "value", "()Ljava/lang/String;"));
        selectors.add(method("cubism.editor-model.model.parts", Model.class, "parts", "()Ljava/util/List;"));
        return TestVerifiedResolvers.create(
            "5.3.02", "adapter.editor-model.readwrite",
            includeCapability
                ? java.util.Set.of("cubism.editor-model.read", EditorPartStructureSelectorContract.CAPABILITY_ID,
                    dev.turboism.mapping.verification.EditorPartTreeSelectorContract.CAPABILITY_ID)
                : java.util.Set.of("cubism.editor-model.read"),
            selectors, Host.class.getClassLoader());
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
        final List<PartSource> sources = new ArrayList<>();
        final PartSource root = new PartSource("ROOT");
        final ModelHandler handler = new ModelHandler();
        Model model = new Model();
        int updateCount;
        public Id guid() { return new Id("model-a"); }
        public Model currentInstance() { return model; }
        public List<PartSource> parts() { return sources; }
        public PartSource rootPart() { return root; }
        public ModelHandler handler() { return handler; }
        public void updateInstances() { updateCount++; }
    }

    public static final class Model {
        final List<PartView> parts = new ArrayList<>();
        public List<PartView> parts() { return parts; }
    }

    public static final class PartView {
        final Id id;
        final PartSource source;
        PartView(final String id, final PartSource source) { this.id = new Id(id); this.source = source; }
        public Id id() { return id; }
        public PartSource source() { return source; }
    }

    public static final class PartSource {
        final Id id;
        final PartHandler handler;
        final List<PartSource> children = new ArrayList<>();
        PartSource parent;
        int defaultOrder = 500;
        PartSource(final String id) { this.id = new Id(id); this.handler = new PartHandler(this); }
        public PartSource(final ModelSource ignored) { this.id = new Id(""); this.handler = new PartHandler(this); }
        public Id id() { return id; }
        public void setId(final Id id) { this.id.value = id.value; }
        public void setGuid(final Id ignored) { }
        public void setLocalName(final String ignored) { }
        public void setDefaultOrder(final int order) { defaultOrder = order; }
        public List<PartSource> children() { return children; }
        public PartSource parent() { return parent; }
        public PartHandler handler() { return handler; }
    }

    public static final class PartHandler {
        final PartSource owner;
        PartHandler(final PartSource owner) { this.owner = owner; }
        public Undo addPartChild(final Object child, final int index) {
            final PartSource source = (PartSource) child;

            source.parent = owner;
            owner.children.add(index, source);
            final ModelSource modelSource = Host.document.source;
            modelSource.sources.add(source);
            modelSource.model.parts.add(new PartView(source.id.value, source));
            return new Undo(true);
        }
    }

    public static final class ModelHandler {
        static int nextId = 1;
        public static Id createFreeIdDefault(final ModelHandler handler, final Id base, final Object idMap, final int flags, final Object marker) {
            return new Id(base.value + "_" + (nextId++));
        }
        public Undo removeObjects(final List<?> objects, final Model model, final boolean flag) {
            for (Object object : objects) {
                final PartSource source = (PartSource) object;
                if (source.parent != null) source.parent.children.remove(source);
                model.parts.removeIf(part -> part.source == source);
                Host.document.source.sources.remove(source);
            }
            lastRemoved.clear();
            for (Object object : objects) lastRemoved.add((PartSource) object);
            return new Undo(false);
        }
        List<PartSource> lastRemoved = new ArrayList<>();
    }

    public static final class CopyHelper {
        public static Object copy(final Object source, final Object copyParam, final int flags, final Object marker) {
            final PartSource original = (PartSource) source;
            final PartSource copy = new PartSource(original.id.value);
            copy.defaultOrder = original.defaultOrder;
            copy.parent = original.parent;
            return copy;
        }
    }

    public static final class Id {
        String value;
        public Id() { this.value = ""; }
        public Id(final String value) { this.value = value; }
        public String value() { return value; }
    }

    public static final class EditMode {
        final List<Undo> edits = new ArrayList<>();
        boolean aborted;
        public GroupUndo begin(final String name) { return new GroupUndo(edits); }
        public void end(final boolean abort, final Object ignored) { aborted = abort; }
    }

    public static final class GroupUndo {
        final List<Undo> edits;
        GroupUndo(final List<Undo> edits) { this.edits = edits; }
        public boolean add(final Undo undo, final boolean significant) { edits.add(undo); return true; }
    }

    public static final class Undo {
        final boolean captured;
        Undo(final boolean captured) { this.captured = captured; }
        public boolean addListener(final Listener listener) { return true; }
    }

    @FunctionalInterface public interface Listener { void changed(Object ignored); }

    public static final class CompletePack {
        int partRefreshCount;
        int repaintCount;
        public void updateParts(final boolean immediate) { partRefreshCount++; }
        public void repaint(final boolean immediate) { repaintCount++; }
    }

    private static final class Fixture {
        final ModelSource source;
        final PartSource childSource = new PartSource("PartChild");
        final Document document;
        final EditMode editMode;
        final CompletePack pack;
        final PartHandler partHandler;
        final ModelHandler handler;
        final PartSource root;

        Fixture() {
            source = new ModelSource();
            root = source.root;
            handler = source.handler;
            partHandler = root.handler;
            source.sources.add(childSource);
            childSource.parent = root;
            root.children.add(childSource);
            source.model.parts.add(new PartView("PartChild", childSource));
            document = new Document(source);
            editMode = document.editMode;
            pack = document.pack;
        }
    }
}
