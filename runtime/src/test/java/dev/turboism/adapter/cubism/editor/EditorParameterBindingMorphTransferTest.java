package dev.turboism.adapter.cubism.editor;

import static dev.turboism.adapter.cubism.editor.EditorTestFixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorParameterBindingBatchWriteSelectorContract;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.ParameterBindingTarget;
import dev.turboism.sdk.cubism.model.ParameterBindingTransferPlan;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EditorParameterBindingMorphTransferTest {
    private static final ParameterBindingTarget OWNER = ParameterBindingTarget.artMesh(new ArtMeshId("mesh"));

    @Test
    void selfInversionKeepsMorphBindingsAndCanBeUndone() {
        final Fixture fixture = fixture(-3.0F, 3.0F, -2.0F, 0.25F, 2.0F);
        final HostMorphTarget other = new HostMorphTarget("to", 0.9F);
        fixture.objectSource.set.targets.add(other);

        access(fixture)
                .transferMorphClamped(new ParameterBindingTransferPlan(
                        new ParameterId("from"), new ParameterId("from"), List.of(OWNER), true));

        assertEquals(
                List.of("from", "from", "from", "to"),
                fixture.objectSource.set.targets.stream()
                        .map(target -> target.parameterGuid.value)
                        .toList());
        assertEquals(
                List.of(2.0F, -0.25F, -2.0F, 0.9F),
                fixture.objectSource.set.targets.stream()
                        .map(target -> target.keyValue)
                        .toList());
        assertEquals(1, fixture.editMode.edits.size());
        fixture.editMode.undo();
        assertEquals(
                List.of(-2.0F, 0.25F, 2.0F, 0.9F),
                fixture.objectSource.set.targets.stream()
                        .map(target -> target.keyValue)
                        .toList());
    }

    @Test
    void transfersAllMorphPointsWithWideRangeAndOneUndo() {
        final Fixture fixture = fixture(-3.0F, 3.0F, -2.0F, 0.25F, 2.0F);

        access(fixture).transferMorphClamped(plan(true));

        assertEquals(
                List.of("to", "to", "to"),
                fixture.objectSource.set.targets.stream()
                        .map(target -> target.parameterGuid.value)
                        .toList());
        assertEquals(
                List.of(2.0F, -0.25F, -2.0F),
                fixture.objectSource.set.targets.stream()
                        .map(target -> target.keyValue)
                        .toList());
        assertEquals(1, fixture.editMode.beginCount);
        assertEquals(1, fixture.editMode.endCount);
        assertEquals(1, fixture.editMode.edits.size(), "one user Undo must cover the whole row");

        fixture.editMode.undo();
        assertEquals(
                List.of("from", "from", "from"),
                fixture.objectSource.set.targets.stream()
                        .map(target -> target.parameterGuid.value)
                        .toList());
        assertEquals(
                List.of(-2.0F, 0.25F, 2.0F),
                fixture.objectSource.set.targets.stream()
                        .map(target -> target.keyValue)
                        .toList());
    }

    @Test
    void keepsEachMorphPointValueUnchanged() {
        final Fixture fixture = fixture(-1.0F, 0.5F, -2.0F, 0.25F, 2.0F);

        access(fixture).transferMorphClamped(plan(false));

        // Native reference semantics: the whole setting moves unchanged, no remap, no clamp.
        assertEquals(
                List.of(-2.0F, 0.25F, 2.0F),
                fixture.objectSource.set.targets.stream()
                        .map(target -> target.keyValue)
                        .toList());
    }

    @Test
    void duplicateMappedMorphPointsFailBeforeAnyMutation() {
        final Fixture fixture = fixture(-2.0F, 2.0F, 1.0F, 1.0000005F, -1.0F);

        assertThrows(IllegalStateException.class, () -> access(fixture).transferMorphClamped(plan(false)));

        assertEquals(0, fixture.editMode.beginCount);
        assertEquals(
                List.of("from", "from", "from"),
                fixture.objectSource.set.targets.stream()
                        .map(target -> target.parameterGuid.value)
                        .toList());
        assertEquals(
                List.of(1.0F, 1.0000005F, -1.0F),
                fixture.objectSource.set.targets.stream()
                        .map(target -> target.keyValue)
                        .toList());
    }

    @Test
    void destinationAlreadyBoundFailsBeforeAnyMutation() {
        final Fixture fixture = fixture(-2.0F, 2.0F, 0.1F, 0.2F, 0.3F);
        fixture.objectSource.set.targets.add(new HostMorphTarget("to", 0.9F));

        assertThrows(IllegalStateException.class, () -> access(fixture).transferMorphClamped(plan(false)));

        assertEquals(0, fixture.editMode.beginCount);
        assertEquals("from", fixture.objectSource.set.targets.get(0).parameterGuid.value);
        assertEquals(0.1F, fixture.objectSource.set.targets.get(0).keyValue);
    }

    @Test
    void keyformDestinationAlreadyBoundFailsBeforeAnyMutation() {
        final Fixture fixture = fixture(-2.0F, 2.0F, 0.1F, 0.2F, 0.3F);
        fixture.objectSource.keyformGrid.targetBound = true;

        assertThrows(IllegalStateException.class, () -> access(fixture).transferMorphClamped(plan(false)));

        assertEquals(0, fixture.editMode.beginCount);
        assertEquals("from", fixture.objectSource.set.targets.get(0).parameterGuid.value);
        assertEquals(0.1F, fixture.objectSource.set.targets.get(0).keyValue);
    }

    @Test
    void morphPathRequiresEveryExactVerifiedAlias() {
        final VerifiedMemberResolver resolver = resolver();

        for (String alias : EditorParameterBindingBatchWriteSelectorContract.MORPH_TRANSFER_REQUIRED_ALIASES) {
            assertNotNull(resolver.verifiedSelector(alias), alias);
        }
    }

    private static ParameterBindingTransferPlan plan(final boolean invert) {
        return new ParameterBindingTransferPlan(new ParameterId("from"), new ParameterId("to"), List.of(OWNER), invert);
    }

    private static EditorParameterBindingBatchAccess access(final Fixture fixture) {
        return new EditorParameterBindingBatchAccess(
                resolver(),
                "test-model",
                fixture.modelSource,
                fixture.model,
                (identity, model) -> {
                    if (!"test-model".equals(identity) || fixture.model != model) {
                        throw new IllegalStateException("stale model");
                    }
                },
                (identity, source, model, target) -> fixture.objectSource,
                (model, parameterId) -> fixture.parameters.get(parameterId.value()));
    }

    private static Fixture fixture(final float minimum, final float maximum, final float... values) {
        return new Fixture(minimum, maximum, values);
    }

    private static VerifiedMemberResolver resolver() {
        return TestVerifiedResolvers.create(
                "5.3.02",
                EditorParameterBindingBatchWriteSelectorContract.ADAPTER_SLICE_ID,
                Set.of(EditorParameterBindingBatchWriteSelectorContract.TRANSFER_CAPABILITY_ID),
                selectors(),
                EditorParameterBindingMorphTransferTest.class.getClassLoader());
    }

    private static List<StaticSelector> selectors() {
        return List.of(
                StaticSelector.staticMethod(
                        "cubism.editor-model.app-controller.instance",
                        internal(Host.class),
                        "instance",
                        descriptor(Host.class),
                        StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC),
                method(
                        "cubism.editor-model.app-controller.current-document",
                        Host.class,
                        "currentDocument",
                        descriptor(Document.class)),
                method(
                        "cubism.editor-model.app-controller.complete-pack",
                        Host.class,
                        "completePack",
                        descriptor(CompletePack.class)),
                method(
                        "cubism.editor-model.modeling-document.edit-mode",
                        Document.class,
                        "editMode",
                        descriptor(EditMode.class)),
                method(
                        "cubism.editor-model.modeling-document.mark-dirty",
                        Document.class,
                        "markDirty",
                        descriptor(void.class)),
                method(
                        "cubism.editor-model.edit-mode.begin",
                        EditMode.class,
                        "begin",
                        descriptor(GroupUndo.class, String.class)),
                method(
                        "cubism.editor-model.edit-mode.end",
                        EditMode.class,
                        "end",
                        descriptor(void.class, boolean.class, Object.class)),
                method(
                        "cubism.editor-model.undo.add",
                        GroupUndo.class,
                        "add",
                        descriptor(boolean.class, Undo.class, boolean.class)),
                method(
                        "cubism.editor-model.undo.add-listener",
                        Undo.class,
                        "addListener",
                        descriptor(boolean.class, Listener.class)),
                StaticSelector.classSelector("cubism.editor-model.undo-listener.class", internal(Listener.class)),
                method(
                        "cubism.editor-model.model-source.update-instances",
                        ModelSource.class,
                        "updateInstances",
                        descriptor(void.class)),
                method(
                        "cubism.editor-model.complete-pack.update-parameter",
                        CompletePack.class,
                        "updateParameter",
                        descriptor(void.class, boolean.class)),
                method(
                        "cubism.editor-model.complete-pack.update-part-palette",
                        CompletePack.class,
                        "updatePartPalette",
                        descriptor(void.class, boolean.class)),
                method(
                        "cubism.editor-model.complete-pack.update-deformer-palette",
                        CompletePack.class,
                        "updateDeformerPalette",
                        descriptor(void.class, boolean.class)),
                method(
                        "cubism.editor-model.complete-pack.repaint-canvas",
                        CompletePack.class,
                        "repaintCanvas",
                        descriptor(void.class, boolean.class)),
                method(
                        "cubism.editor-model.parameter-controllable.keyform-grid",
                        ObjectSource.class,
                        "keyformGrid",
                        descriptor(KeyformGrid.class)),
                method(
                        "cubism.editor-model.keyform-grid.find-binding",
                        KeyformGrid.class,
                        "findBinding",
                        descriptor(Object.class, HostParameterGuid.class)),
                method(
                        "cubism.editor-model.parameter-controllable.morph-target-set",
                        ObjectSource.class,
                        "morphTargetSet",
                        descriptor(MorphTargetSet.class)),
                StaticSelector.classSelector(
                        "cubism.editor-model.morph-target-set.class", internal(MorphTargetSet.class)),
                method(
                        "cubism.editor-model.morph-target-set.morph-targets",
                        MorphTargetSet.class,
                        "morphTargets",
                        descriptor(List.class)),
                StaticSelector.classSelector("cubism.editor-model.morph-target.class", internal(HostMorphTarget.class)),
                method(
                        "cubism.editor-model.morph-target.parameter-guid",
                        HostMorphTarget.class,
                        "parameterGuid",
                        descriptor(HostParameterGuid.class)),
                method(
                        "cubism.editor-model.morph-target.key-value",
                        HostMorphTarget.class,
                        "keyValue",
                        descriptor(Float.class)),
                method(
                        "cubism.editor-model.model-source.parameter-source-set",
                        ModelSource.class,
                        "parameterSourceSet",
                        descriptor(ParameterSourceSet.class)),
                StaticSelector.classSelector(
                        "cubism.editor-model.parameter-source-set.class", internal(ParameterSourceSet.class)),
                method(
                        "cubism.editor-model.parameter-source-set.get",
                        ParameterSourceSet.class,
                        "get",
                        descriptor(ParameterSource.class, HostParameterGuid.class)),
                method(
                        "cubism.editor-model.parameter-source.id",
                        ParameterSource.class,
                        "id",
                        descriptor(HostParameterId.class)),
                method("cubism.editor-model.id.value", HostParameterId.class, "value", descriptor(String.class)),
                method(
                        "cubism.editor-model.parameter-source.guid",
                        ParameterSource.class,
                        "guid",
                        descriptor(HostParameterGuid.class)),
                method(
                        "cubism.editor-model.parameter-source.morph-target",
                        ParameterSource.class,
                        "morphTarget",
                        descriptor(boolean.class)),
                method(
                        "cubism.editor-model.parameter-source.minimum",
                        ParameterSource.class,
                        "minimum",
                        descriptor(float.class)),
                method(
                        "cubism.editor-model.parameter-source.maximum",
                        ParameterSource.class,
                        "maximum",
                        descriptor(float.class)),
                StaticSelector.field(
                        "cubism.editor-model.morph-target-utils.instance",
                        internal(MorphTargetUtils.class),
                        "INSTANCE",
                        type(MorphTargetUtils.class),
                        StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC),
                method(
                        "cubism.editor-model.morph-target.change-parameter",
                        MorphTargetUtils.class,
                        "changeParameter",
                        descriptor(Undo.class, HostMorphTarget.class, HostParameterGuid.class, Float.class)));
    }

    private static StaticSelector method(
            final String alias, final Class<?> owner, final String name, final String descriptor) {
        return StaticSelector.method(alias, internal(owner), name, descriptor, StaticSelector.ACCESS_PUBLIC);
    }

    private static String descriptor(final Class<?> returnType, final Class<?>... parameters) {
        return "("
                + Arrays.stream(parameters)
                        .map(EditorParameterBindingMorphTransferTest::type)
                        .reduce("", String::concat)
                + ")" + type(returnType);
    }

    private static String type(final Class<?> type) {
        if (type == void.class) return "V";
        if (type == boolean.class) return "Z";
        if (type == float.class) return "F";
        return "L" + internal(type) + ";";
    }

    private static String internal(final Class<?> type) {
        return type.getName().replace('.', '/');
    }

    private static final class Fixture {
        final Object model = new Object();
        final ModelSource modelSource = new ModelSource();
        final ObjectSource objectSource = new ObjectSource();
        final Map<String, ParameterSource> parameters = new LinkedHashMap<>();
        final Document document;
        final EditMode editMode;

        Fixture(final float minimum, final float maximum, final float... values) {
            parameters.put("from", new ParameterSource("from", -3.0F, 3.0F));
            parameters.put("to", new ParameterSource("to", minimum, maximum));
            modelSource.sourceSet.values.addAll(parameters.values());
            for (float value : values) objectSource.set.targets.add(new HostMorphTarget("from", value));
            document = new Document(modelSource);
            editMode = document.editMode;
            Host.current = document;
            Host.document = document;
        }
    }
}
