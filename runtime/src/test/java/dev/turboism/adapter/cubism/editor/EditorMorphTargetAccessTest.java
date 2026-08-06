package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.EditorMorphTargetSelectorContract;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.MorphTarget;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorMorphTargetAccessTest {

    @Test
    void readsAndWritesMorphTargetBindingsThroughNativeUndo() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final EditorMorphTargetAccess access = new EditorMorphTargetAccess(
            resolver(true), (identity, model) -> { });

        final var targets = access.morphTargets("session-a", fixture.source, fixture.model, fixture.objectSource);
        final List<MorphTarget> all = targets.all();
        assertEquals(2, all.size());
        assertEquals(new ParameterId("EyeParam"), all.get(0).parameterId());
        assertEquals(1.0F, all.get(0).keyValue());
        assertEquals("keyform-EyeParam", all.get(0).keyformGuid().orElseThrow());
        assertEquals(new ParameterId("MouthParam"), all.get(1).parameterId());
        assertEquals(0.5F, all.get(1).keyValue());

        final MorphTarget found = targets.find(new ParameterId("EyeParam"));
        found.setParameterAndKeyValue(new ParameterId("MouthParam"), 0.75F);
        assertEquals("MouthParam", fixture.target1.parameter.value);
        assertEquals(0.75F, fixture.target1.keyValue);
        assertEquals(1, fixture.editMode.edits.size());
        assertTrue(fixture.document.dirty);
        assertEquals(1, fixture.source.updateCount);
        assertTrue(fixture.editMode.edits.get(0).captured);
        assertTrue(!fixture.editMode.aborted);

        found.setParameterAndKeyValue(new ParameterId("MouthParam"), 0.75F);
        assertEquals(1, fixture.editMode.edits.size());
    }

    @Test
    void missingCapabilityFailsClosed() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final EditorMorphTargetAccess access = new EditorMorphTargetAccess(
            resolver(false), (identity, model) -> { });
        assertThrows(UnsupportedOperationException.class,
            () -> access.morphTargets("session-a", fixture.source, fixture.model, fixture.objectSource));
    }

    @Test
    void absentParameterTargetFailsClosed() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final EditorMorphTargetAccess access = new EditorMorphTargetAccess(
            resolver(true), (identity, model) -> { });
        final var targets = access.morphTargets("session-a", fixture.source, fixture.model, fixture.objectSource);
        assertThrows(java.util.NoSuchElementException.class, () -> targets.find(new ParameterId("Missing")));
    }

    private static VerifiedMemberResolver resolver(final boolean includeCapability) {
        final List<StaticSelector> selectors = new ArrayList<>();
        selectors.add(StaticSelector.classSelector("cubism.editor-model.app-controller.class", internal(Host.class)));
        selectors.add(StaticSelector.staticMethod("cubism.editor-model.app-controller.instance", internal(Host.class), "instance",
            desc(Host.class), StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
        selectors.add(method("cubism.editor-model.app-controller.current-document", Host.class, "currentDocument", desc(Document.class)));
        selectors.add(method("cubism.editor-model.app-controller.complete-pack", Host.class, "completePack", desc(CompletePack.class)));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.modeling-document.class", internal(Document.class)));
        selectors.add(method("cubism.editor-model.modeling-document.edit-mode", Document.class, "editMode", desc(EditMode.class)));
        selectors.add(method("cubism.editor-model.modeling-document.mark-dirty", Document.class, "markDirty", "()V"));
        selectors.add(method("cubism.editor-model.edit-mode.begin", EditMode.class, "begin", "(Ljava/lang/String;)" + type(GroupUndo.class)));
        selectors.add(method("cubism.editor-model.edit-mode.end", EditMode.class, "end", "(ZLjava/lang/Object;)V"));
        selectors.add(method("cubism.editor-model.undo.add", GroupUndo.class, "add", "(" + type(Undo.class) + "Z)Z"));
        selectors.add(method("cubism.editor-model.undo.add-listener", Undo.class, "addListener", "(" + type(Listener.class) + ")Z"));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.undo-listener.class", internal(Listener.class)));
        selectors.add(method("cubism.editor-model.model-source.update-instances", ModelSource.class, "updateInstances", "()V"));
        selectors.add(method("cubism.editor-model.complete-pack.update-part-palette", CompletePack.class, "updateParts", "(Z)V"));
        selectors.add(method("cubism.editor-model.complete-pack.repaint-canvas", CompletePack.class, "repaint", "(Z)V"));
        selectors.add(method("cubism.editor-model.parameter-controllable.morph-target-set", ObjectSource.class, "morphTargetSet", desc(MorphTargetSet.class)));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.morph-target-set.class", internal(MorphTargetSet.class)));
        selectors.add(method("cubism.editor-model.morph-target-set.morph-targets", MorphTargetSet.class, "morphTargets", "()Ljava/util/List;"));
        selectors.add(method("cubism.editor-model.morph-target-set.create-undo", MorphTargetSet.class, "createUndo", "(Ljava/lang/String;)" + type(Undo.class)));
        selectors.add(method("cubism.editor-model.morph-target-set.remove", MorphTargetSet.class, "remove", "(L" + internal(HostMorphTarget.class) + ";)V"));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.morph-target.class", internal(HostMorphTarget.class)));
        selectors.add(method("cubism.editor-model.morph-target.parameter-guid", HostMorphTarget.class, "parameterGuid", desc(HostParameterGuid.class)));
        selectors.add(method("cubism.editor-model.morph-target.key-value", HostMorphTarget.class, "keyValue", "()Ljava/lang/Float;"));
        selectors.add(method("cubism.editor-model.morph-target.keyform-guid", HostMorphTarget.class, "keyformGuid", desc(HostParameterGuid.class)));
        selectors.add(method("cubism.editor-model.morph-target.set-parameter-and-key-value", HostMorphTarget.class, "setParameterAndKeyValue",
            "(L" + internal(HostParameterGuid.class) + ";Ljava/lang/Float;)V"));
        selectors.add(method("cubism.editor-model.morph-target.set-parameter", HostMorphTarget.class, "setParameter",
            "(L" + internal(HostParameterGuid.class) + ";)V"));
        selectors.add(method("cubism.editor-model.model-source.parameter-source-set", ModelSource.class, "parameterSourceSet", desc(ParameterSourceSet.class)));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.parameter-source-set.class", internal(ParameterSourceSet.class)));
        selectors.add(method("cubism.editor-model.parameter-source-set.get", ParameterSourceSet.class, "get", "(L" + internal(HostParameterGuid.class) + ";)L" + internal(ParameterSource.class) + ";"));
        selectors.add(method("cubism.editor-model.parameter-source-set.get-by-id", ParameterSourceSet.class, "getById", "(L" + internal(HostParameterId.class) + ";)L" + internal(ParameterSource.class) + ";"));
        selectors.add(method("cubism.editor-model.parameter-source.id", ParameterSource.class, "id", desc(HostParameterId.class)));
        selectors.add(method("cubism.editor-model.parameter-source.guid", ParameterSource.class, "guid", desc(HostParameterGuid.class)));
        selectors.add(StaticSelector.constructor("cubism.editor-model.parameter-id.create", internal(HostParameterId.class), "(Ljava/lang/String;)V", StaticSelector.ACCESS_PUBLIC));
        selectors.add(method("cubism.editor-model.id.value", HostParameterId.class, "value", "()Ljava/lang/String;"));
        selectors.add(method("cubism.editor-model.guid.value", HostParameterGuid.class, "value", "()Ljava/lang/String;"));
        selectors.add(method("cubism.editor-model.form-guid.value", HostParameterGuid.class, "value", "()Ljava/lang/String;"));
        return TestVerifiedResolvers.create(
            "5.3.02", "adapter.editor-model.readwrite",
            includeCapability
                ? java.util.Set.of("cubism.editor-model.read",
                    EditorMorphTargetSelectorContract.READ_CAPABILITY_ID,
                    EditorMorphTargetSelectorContract.WRITE_CAPABILITY_ID)
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
        final ParameterSourceSet sourceSet = new ParameterSourceSet();
        int updateCount;
        public ParameterSourceSet parameterSourceSet() { return sourceSet; }
        public void updateInstances() { updateCount++; }
    }

    public static final class ObjectSource {
        final MorphTargetSet set = new MorphTargetSet();
        public MorphTargetSet morphTargetSet() { return set; }
    }

    public static final class MorphTargetSet {
        final List<HostMorphTarget> targets = new ArrayList<>();
        public List<HostMorphTarget> morphTargets() { return targets; }
        public Undo createUndo(final String name) { return new Undo(true); }
        public void remove(final HostMorphTarget target) { targets.remove(target); }
    }

    public static final class HostMorphTarget {
        HostParameterGuid parameter;
        float keyValue;
        HostMorphTarget(final String parameter, final float keyValue) {
            this.parameter = new HostParameterGuid(parameter);
            this.keyValue = keyValue;
        }
        public HostParameterGuid parameterGuid() { return parameter; }
        public Float keyValue() { return keyValue; }
        public HostParameterGuid keyformGuid() { return new HostParameterGuid("keyform-" + parameter.value); }
        public void setParameterAndKeyValue(final HostParameterGuid parameter, final Float keyValue) {
            this.parameter = parameter;
            this.keyValue = keyValue;
        }
        public void setParameter(final HostParameterGuid parameter) {
            this.parameter = parameter;
        }
    }

    public static final class ParameterSourceSet {
        final List<ParameterSource> all = new ArrayList<>();
        public ParameterSource get(final HostParameterGuid guid) {
            return all.stream().filter(s -> s.guid.value.equals(guid.value)).findFirst().orElse(null);
        }
        public ParameterSource getById(final HostParameterId id) {
            return all.stream().filter(s -> s.id.value.equals(id.value)).findFirst().orElse(null);
        }
    }

    public static final class ParameterSource {
        final HostParameterId id;
        final HostParameterGuid guid;
        ParameterSource(final String id) { this.id = new HostParameterId(id); this.guid = new HostParameterGuid(id); }
        public HostParameterId id() { return id; }
        public HostParameterGuid guid() { return guid; }
    }

    public static final class HostParameterId {
        final String value;
        public HostParameterId(final String value) { this.value = value; }
        public String value() { return value; }
    }

    public static final class HostParameterGuid {
        final String value;
        public HostParameterGuid(final String value) { this.value = value; }
        public String value() { return value; }
    }

    public static final class EditMode {
        final List<Undo> edits = new ArrayList<>();
        boolean aborted;
        public GroupUndo begin(final String name) { return new GroupUndo(edits); }
        public void end(final boolean abort, final Object ignored) { aborted = abort; }
    }

    public static final class GroupUndo extends Undo {
        final List<Undo> edits;
        GroupUndo(final List<Undo> edits) { this.edits = edits; }
        public boolean add(final Undo undo, final boolean significant) { edits.add(undo); return true; }
    }

    public static class Undo {
        final boolean captured;
        Undo() { this.captured = false; }
        Undo(final boolean captured) { this.captured = captured; }
        public boolean addListener(final Listener listener) { return true; }
    }

    @FunctionalInterface public interface Listener { void changed(Object ignored); }

    public static final class CompletePack {
        int repaintCount;
        public void updateParts(final boolean immediate) { }
        public void repaint(final boolean immediate) { repaintCount++; }
    }

    private static final class Fixture {
        final ModelSource source = new ModelSource();
        final ObjectSource objectSource = new ObjectSource();
        final Object model = new Object();
        final HostMorphTarget target1 = new HostMorphTarget("EyeParam", 1.0F);
        final HostMorphTarget target2 = new HostMorphTarget("MouthParam", 0.5F);
        final Document document;
        final EditMode editMode;

        Fixture() {
            source.sourceSet.all.add(new ParameterSource("EyeParam"));
            source.sourceSet.all.add(new ParameterSource("MouthParam"));
            objectSource.set.targets.add(target1);
            objectSource.set.targets.add(target2);
            document = new Document(source);
            editMode = document.editMode;
        }
    }
}
