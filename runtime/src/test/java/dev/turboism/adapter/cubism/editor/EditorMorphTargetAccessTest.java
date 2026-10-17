package dev.turboism.adapter.cubism.editor;

import static dev.turboism.adapter.cubism.editor.EditorTestFixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorMorphTargetSelectorContract;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.MorphTarget;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EditorMorphTargetAccessTest {

    @Test
    void readsAndWritesMorphTargetBindingsThroughNativeUndo() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final EditorMorphTargetAccess access = new EditorMorphTargetAccess(resolver(true), (identity, model) -> {});

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
    void morphTargetWriteUndoRedoRestoresTheOriginalBinding() {
        // Reproduces the matrix morphTarget sequence: the host ChangeMorphTargetParameterUndoRedo
        // registered by MorphTargetParameterUtils.changeMorphTargetParameter_exe must restore the
        // original parameter/key binding on undo, re-apply on redo, and restore again on undo.
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final EditorMorphTargetAccess access = new EditorMorphTargetAccess(resolver(true), (identity, model) -> {});
        final var targets = access.morphTargets("session-a", fixture.source, fixture.model, fixture.objectSource);
        final MorphTarget target = targets.find(new ParameterId("EyeParam"));

        // write: construct-and-redo applies the new binding immediately
        target.setParameterAndKeyValue(new ParameterId("MouthParam"), 0.75F);
        assertEquals("MouthParam", fixture.target1.parameter.value);
        assertEquals(0.75F, fixture.target1.keyValue);
        assertEquals(1, fixture.editMode.edits.size());

        // undo: host undo restores the original parameter and key value
        fixture.editMode.undo();
        assertEquals("EyeParam", fixture.target1.parameter.value);
        assertEquals(1.0F, fixture.target1.keyValue);

        // redo: re-applies the new binding
        fixture.editMode.redo();
        assertEquals("MouthParam", fixture.target1.parameter.value);
        assertEquals(0.75F, fixture.target1.keyValue);

        // undo again: restores the original binding once more
        fixture.editMode.undo();
        assertEquals("EyeParam", fixture.target1.parameter.value);
        assertEquals(1.0F, fixture.target1.keyValue);
    }

    @Test
    void missingCapabilityFailsClosed() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final EditorMorphTargetAccess access = new EditorMorphTargetAccess(resolver(false), (identity, model) -> {});
        assertThrows(
                UnsupportedOperationException.class,
                () -> access.morphTargets("session-a", fixture.source, fixture.model, fixture.objectSource));
    }

    @Test
    void absentParameterTargetFailsClosed() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final EditorMorphTargetAccess access = new EditorMorphTargetAccess(resolver(true), (identity, model) -> {});
        final var targets = access.morphTargets("session-a", fixture.source, fixture.model, fixture.objectSource);
        assertThrows(java.util.NoSuchElementException.class, () -> targets.find(new ParameterId("Missing")));
    }

    private static VerifiedMemberResolver resolver(final boolean includeCapability) {
        final List<StaticSelector> selectors = new ArrayList<>();
        selectors.add(StaticSelector.classSelector("cubism.editor-model.app-controller.class", internal(Host.class)));
        selectors.add(StaticSelector.staticMethod(
                "cubism.editor-model.app-controller.instance",
                internal(Host.class),
                "instance",
                desc(Host.class),
                StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
        selectors.add(method(
                "cubism.editor-model.app-controller.current-document",
                Host.class,
                "currentDocument",
                desc(Document.class)));
        selectors.add(method(
                "cubism.editor-model.app-controller.complete-pack",
                Host.class,
                "completePack",
                desc(CompletePack.class)));
        selectors.add(
                StaticSelector.classSelector("cubism.editor-model.modeling-document.class", internal(Document.class)));
        selectors.add(method(
                "cubism.editor-model.modeling-document.edit-mode", Document.class, "editMode", desc(EditMode.class)));
        selectors.add(method("cubism.editor-model.modeling-document.mark-dirty", Document.class, "markDirty", "()V"));
        selectors.add(method(
                "cubism.editor-model.edit-mode.begin",
                EditMode.class,
                "begin",
                "(Ljava/lang/String;)" + type(GroupUndo.class)));
        selectors.add(method("cubism.editor-model.edit-mode.end", EditMode.class, "end", "(ZLjava/lang/Object;)V"));
        selectors.add(method("cubism.editor-model.undo.add", GroupUndo.class, "add", "(" + type(Undo.class) + "Z)Z"));
        selectors.add(method(
                "cubism.editor-model.undo.add-listener", Undo.class, "addListener", "(" + type(Listener.class) + ")Z"));
        selectors.add(
                StaticSelector.classSelector("cubism.editor-model.undo-listener.class", internal(Listener.class)));
        selectors.add(method(
                "cubism.editor-model.model-source.update-instances", ModelSource.class, "updateInstances", "()V"));
        selectors.add(method(
                "cubism.editor-model.complete-pack.update-part-palette", CompletePack.class, "updateParts", "(Z)V"));
        selectors.add(
                method("cubism.editor-model.complete-pack.repaint-canvas", CompletePack.class, "repaint", "(Z)V"));
        selectors.add(method(
                "cubism.editor-model.parameter-controllable.morph-target-set",
                ObjectSource.class,
                "morphTargetSet",
                desc(MorphTargetSet.class)));
        selectors.add(StaticSelector.classSelector(
                "cubism.editor-model.morph-target-set.class", internal(MorphTargetSet.class)));
        selectors.add(method(
                "cubism.editor-model.morph-target-set.morph-targets",
                MorphTargetSet.class,
                "morphTargets",
                "()Ljava/util/List;"));
        selectors.add(StaticSelector.field(
                "cubism.editor-model.morph-target-utils.instance",
                internal(MorphTargetParameterUtils.class),
                "a",
                "L" + internal(MorphTargetParameterUtils.class) + ";",
                StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
        selectors.add(method(
                "cubism.editor-model.morph-target.change-parameter",
                MorphTargetParameterUtils.class,
                "a",
                "(L" + internal(HostMorphTarget.class) + ";L" + internal(HostParameterGuid.class)
                        + ";Ljava/lang/Float;)" + type(Undo.class)));
        selectors.add(StaticSelector.classSelector(
                "cubism.editor-model.morph-target.class", internal(HostMorphTarget.class)));
        selectors.add(method(
                "cubism.editor-model.morph-target.parameter-guid",
                HostMorphTarget.class,
                "parameterGuid",
                desc(HostParameterGuid.class)));
        selectors.add(method(
                "cubism.editor-model.morph-target.key-value",
                HostMorphTarget.class,
                "keyValue",
                "()Ljava/lang/Float;"));
        selectors.add(method(
                "cubism.editor-model.morph-target.keyform-guid",
                HostMorphTarget.class,
                "keyformGuid",
                desc(HostParameterGuid.class)));
        selectors.add(method(
                "cubism.editor-model.model-source.parameter-source-set",
                ModelSource.class,
                "parameterSourceSet",
                desc(ParameterSourceSet.class)));
        selectors.add(StaticSelector.classSelector(
                "cubism.editor-model.parameter-source-set.class", internal(ParameterSourceSet.class)));
        selectors.add(method(
                "cubism.editor-model.parameter-source-set.get",
                ParameterSourceSet.class,
                "get",
                "(L" + internal(HostParameterGuid.class) + ";)L" + internal(ParameterSource.class) + ";"));
        selectors.add(method(
                "cubism.editor-model.parameter-source-set.get-by-id",
                ParameterSourceSet.class,
                "getById",
                "(L" + internal(HostParameterId.class) + ";)L" + internal(ParameterSource.class) + ";"));
        selectors.add(method(
                "cubism.editor-model.parameter-source.id", ParameterSource.class, "id", desc(HostParameterId.class)));
        selectors.add(method(
                "cubism.editor-model.parameter-source.guid",
                ParameterSource.class,
                "guid",
                desc(HostParameterGuid.class)));
        selectors.add(StaticSelector.constructor(
                "cubism.editor-model.parameter-id.create",
                internal(HostParameterId.class),
                "(Ljava/lang/String;)V",
                StaticSelector.ACCESS_PUBLIC));
        selectors.add(method("cubism.editor-model.id.value", HostParameterId.class, "value", "()Ljava/lang/String;"));
        selectors.add(
                method("cubism.editor-model.guid.value", HostParameterGuid.class, "value", "()Ljava/lang/String;"));
        selectors.add(method(
                "cubism.editor-model.form-guid.value", HostParameterGuid.class, "value", "()Ljava/lang/String;"));
        return TestVerifiedResolvers.create(
                "5.3.02",
                "adapter.editor-model.readwrite",
                includeCapability
                        ? java.util.Set.of(
                                "cubism.editor-model.read",
                                EditorMorphTargetSelectorContract.READ_CAPABILITY_ID,
                                EditorMorphTargetSelectorContract.WRITE_CAPABILITY_ID)
                        : java.util.Set.of("cubism.editor-model.read"),
                selectors,
                Host.class.getClassLoader());
    }

    private static StaticSelector method(
            final String alias, final Class<?> owner, final String name, final String descriptor) {
        return StaticSelector.method(alias, internal(owner), name, descriptor, StaticSelector.ACCESS_PUBLIC);
    }

    private static String internal(final Class<?> type) {
        return type.getName().replace('.', '/');
    }

    private static String type(final Class<?> type) {
        return "L" + internal(type) + ";";
    }

    private static String desc(final Class<?> type) {
        return "()" + type(type);
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
