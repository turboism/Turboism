package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.selector.EditorParameterBindingBatchWriteSelectorContract;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.ParameterBindingTarget;
import dev.turboism.sdk.cubism.model.ParameterBindingTransferPlan;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EditorParameterBindingMorphTransferTest {
    private static final ParameterBindingTarget OWNER =
        ParameterBindingTarget.artMesh(new ArtMeshId("mesh"));

    @Test
    void transfersAllMorphPointsWithWideRangeAndOneUndo() {
        final Fixture fixture = fixture(-3.0F, 3.0F, -2.0F, 0.25F, 2.0F);

        access(fixture).transferMorphClamped(plan(true));

        assertEquals(List.of("to", "to", "to"), fixture.objectSource.set.targets.stream()
            .map(target -> target.parameterGuid.value).toList());
        assertEquals(List.of(2.0F, -0.25F, -2.0F), fixture.objectSource.set.targets.stream()
            .map(target -> target.keyValue).toList());
        assertEquals(1, fixture.editMode.beginCount);
        assertEquals(1, fixture.editMode.endCount);
        assertEquals(1, fixture.editMode.edits.size(), "one user Undo must cover the whole row");

        fixture.editMode.undo();
        assertEquals(List.of("from", "from", "from"), fixture.objectSource.set.targets.stream()
            .map(target -> target.parameterGuid.value).toList());
        assertEquals(List.of(-2.0F, 0.25F, 2.0F), fixture.objectSource.set.targets.stream()
            .map(target -> target.keyValue).toList());
    }

    @Test
    void keepsEachMorphPointValueUnchanged() {
        final Fixture fixture = fixture(-1.0F, 0.5F, -2.0F, 0.25F, 2.0F);

        access(fixture).transferMorphClamped(plan(false));

        // Native reference semantics: the whole setting moves unchanged, no remap, no clamp.
        assertEquals(List.of(-2.0F, 0.25F, 2.0F), fixture.objectSource.set.targets.stream()
            .map(target -> target.keyValue).toList());
    }
    @Test
    void duplicateMappedMorphPointsFailBeforeAnyMutation() {
        final Fixture fixture = fixture(-2.0F, 2.0F, 1.0F, 1.0000005F, -1.0F);

        assertThrows(
            IllegalStateException.class,
            () -> access(fixture).transferMorphClamped(plan(false))
        );

        assertEquals(0, fixture.editMode.beginCount);
        assertEquals(List.of("from", "from", "from"), fixture.objectSource.set.targets.stream()
            .map(target -> target.parameterGuid.value).toList());
        assertEquals(List.of(1.0F, 1.0000005F, -1.0F), fixture.objectSource.set.targets.stream()
            .map(target -> target.keyValue).toList());
    }

    @Test
    void destinationAlreadyBoundFailsBeforeAnyMutation() {
        final Fixture fixture = fixture(-2.0F, 2.0F, 0.1F, 0.2F, 0.3F);
        fixture.objectSource.set.targets.add(new HostMorphTarget("to", 0.9F));

        assertThrows(
            IllegalStateException.class,
            () -> access(fixture).transferMorphClamped(plan(false))
        );

        assertEquals(0, fixture.editMode.beginCount);
        assertEquals("from", fixture.objectSource.set.targets.get(0).parameterGuid.value);
        assertEquals(0.1F, fixture.objectSource.set.targets.get(0).keyValue);
    }

    @Test
    void keyformDestinationAlreadyBoundFailsBeforeAnyMutation() {
        final Fixture fixture = fixture(-2.0F, 2.0F, 0.1F, 0.2F, 0.3F);
        fixture.objectSource.keyformGrid.targetBound = true;

        assertThrows(
            IllegalStateException.class,
            () -> access(fixture).transferMorphClamped(plan(false))
        );

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
        return new ParameterBindingTransferPlan(
            new ParameterId("from"),
            new ParameterId("to"),
            List.of(OWNER),
            invert
        );
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
            (model, parameterId) -> fixture.parameters.get(parameterId.value())
        );
    }

    private static Fixture fixture(
        final float minimum,
        final float maximum,
        final float... values
    ) {
        return new Fixture(minimum, maximum, values);
    }

    private static VerifiedMemberResolver resolver() {
        return TestVerifiedResolvers.create(
            "5.3.02",
            EditorParameterBindingBatchWriteSelectorContract.ADAPTER_SLICE_ID,
            Set.of(EditorParameterBindingBatchWriteSelectorContract.TRANSFER_CAPABILITY_ID),
            selectors(),
            EditorParameterBindingMorphTransferTest.class.getClassLoader()
        );
    }

    private static List<StaticSelector> selectors() {
        return List.of(
            StaticSelector.staticMethod(
                "cubism.editor-model.app-controller.instance",
                internal(Host.class),
                "instance",
                descriptor(Host.class),
                StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC
            ),
            method("cubism.editor-model.app-controller.current-document", Host.class, "currentDocument", descriptor(Document.class)),
            method("cubism.editor-model.app-controller.complete-pack", Host.class, "completePack", descriptor(Pack.class)),
            method("cubism.editor-model.modeling-document.edit-mode", Document.class, "editMode", descriptor(EditMode.class)),
            method("cubism.editor-model.modeling-document.mark-dirty", Document.class, "markDirty", descriptor(void.class)),
            method("cubism.editor-model.edit-mode.begin", EditMode.class, "begin", descriptor(GroupUndo.class, String.class)),
            method("cubism.editor-model.edit-mode.end", EditMode.class, "end", descriptor(void.class, boolean.class, Object.class)),
            method("cubism.editor-model.undo.add", GroupUndo.class, "add", descriptor(boolean.class, Undo.class, boolean.class)),
            method("cubism.editor-model.undo.add-listener", Undo.class, "addListener", descriptor(void.class, Listener.class)),
            StaticSelector.classSelector("cubism.editor-model.undo-listener.class", internal(Listener.class)),
            method("cubism.editor-model.model-source.update-instances", ModelSource.class, "updateInstances", descriptor(void.class)),
            method("cubism.editor-model.complete-pack.update-parameter", Pack.class, "updateParameter", descriptor(void.class, boolean.class)),
            method("cubism.editor-model.complete-pack.update-part-palette", Pack.class, "updatePartPalette", descriptor(void.class, boolean.class)),
            method("cubism.editor-model.complete-pack.update-deformer-palette", Pack.class, "updateDeformerPalette", descriptor(void.class, boolean.class)),
            method("cubism.editor-model.complete-pack.repaint-canvas", Pack.class, "repaintCanvas", descriptor(void.class, boolean.class)),
            method("cubism.editor-model.parameter-controllable.keyform-grid", ObjectSource.class, "keyformGrid", descriptor(KeyformGrid.class)),
            method("cubism.editor-model.keyform-grid.find-binding", KeyformGrid.class, "findBinding", descriptor(Object.class, HostParameterGuid.class)),
            method("cubism.editor-model.parameter-controllable.morph-target-set", ObjectSource.class, "morphTargetSet", descriptor(MorphTargetSet.class)),
            StaticSelector.classSelector("cubism.editor-model.morph-target-set.class", internal(MorphTargetSet.class)),
            method("cubism.editor-model.morph-target-set.morph-targets", MorphTargetSet.class, "morphTargets", descriptor(List.class)),
            StaticSelector.classSelector("cubism.editor-model.morph-target.class", internal(HostMorphTarget.class)),
            method("cubism.editor-model.morph-target.parameter-guid", HostMorphTarget.class, "parameterGuid", descriptor(HostParameterGuid.class)),
            method("cubism.editor-model.morph-target.key-value", HostMorphTarget.class, "keyValue", descriptor(Float.class)),
            method("cubism.editor-model.model-source.parameter-source-set", ModelSource.class, "parameterSourceSet", descriptor(ParameterSourceSet.class)),
            StaticSelector.classSelector("cubism.editor-model.parameter-source-set.class", internal(ParameterSourceSet.class)),
            method("cubism.editor-model.parameter-source-set.get", ParameterSourceSet.class, "get", descriptor(ParameterSource.class, HostParameterGuid.class)),
            method("cubism.editor-model.parameter-source.id", ParameterSource.class, "id", descriptor(HostParameterId.class)),
            method("cubism.editor-model.id.value", HostParameterId.class, "value", descriptor(String.class)),
            method("cubism.editor-model.parameter-source.guid", ParameterSource.class, "guid", descriptor(HostParameterGuid.class)),
            method("cubism.editor-model.parameter-source.minimum", ParameterSource.class, "minimum", descriptor(float.class)),
            method("cubism.editor-model.parameter-source.maximum", ParameterSource.class, "maximum", descriptor(float.class)),
            StaticSelector.field(
                "cubism.editor-model.morph-target-utils.instance",
                internal(MorphTargetUtils.class),
                "INSTANCE",
                type(MorphTargetUtils.class),
                StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC
            ),
            method(
                "cubism.editor-model.morph-target.change-parameter",
                MorphTargetUtils.class,
                "changeParameter",
                descriptor(Undo.class, HostMorphTarget.class, HostParameterGuid.class, Float.class)
            )
        );
    }

    private static StaticSelector method(
        final String alias,
        final Class<?> owner,
        final String name,
        final String descriptor
    ) {
        return StaticSelector.method(alias, internal(owner), name, descriptor, StaticSelector.ACCESS_PUBLIC);
    }

    private static String descriptor(final Class<?> returnType, final Class<?>... parameters) {
        return "(" + Arrays.stream(parameters).map(EditorParameterBindingMorphTransferTest::type)
            .reduce("", String::concat) + ")" + type(returnType);
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

    public static final class Host {
        private static final Host INSTANCE = new Host();
        private static Document current;

        public static Host instance() { return INSTANCE; }
        public Document currentDocument() { return current; }
        public Pack completePack() { return current.pack; }
    }

    public static final class Document {
        final ModelSource source;
        final EditMode editMode = new EditMode();
        final Pack pack = new Pack();
        boolean dirty;

        Document(final ModelSource source) { this.source = source; }
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
        final KeyformGrid keyformGrid = new KeyformGrid();
        public MorphTargetSet morphTargetSet() { return set; }
        public KeyformGrid keyformGrid() { return keyformGrid; }
    }

    public static final class KeyformGrid {
        boolean targetBound;

        public Object findBinding(final HostParameterGuid ignored) {
            return targetBound ? new Object() : null;
        }
    }

    public static final class MorphTargetSet {
        final List<HostMorphTarget> targets = new ArrayList<>();
        public List<HostMorphTarget> morphTargets() { return targets; }
    }

    public static final class HostMorphTarget {
        HostParameterGuid parameterGuid;
        float keyValue;

        HostMorphTarget(final String parameter, final float value) {
            parameterGuid = new HostParameterGuid(parameter);
            keyValue = value;
        }

        public HostParameterGuid parameterGuid() { return parameterGuid; }
        public Float keyValue() { return keyValue; }
        void set(final HostParameterGuid parameter, final float value) {
            parameterGuid = parameter;
            keyValue = value;
        }
    }

    public static final class MorphTargetUtils {
        public static final MorphTargetUtils INSTANCE = new MorphTargetUtils();

        public Undo changeParameter(
            final HostMorphTarget target,
            final HostParameterGuid parameter,
            final Float value
        ) {
            return new ChangeUndo(target, parameter, value);
        }
    }

    public static final class ParameterSourceSet {
        final List<ParameterSource> values = new ArrayList<>();

        public ParameterSource get(final HostParameterGuid guid) {
            return values.stream().filter(source -> source.guid.value.equals(guid.value))
                .findFirst().orElse(null);
        }
    }

    public static final class ParameterSource {
        final HostParameterId id;
        final HostParameterGuid guid;
        final float minimum;
        final float maximum;

        ParameterSource(final String value, final float minimum, final float maximum) {
            id = new HostParameterId(value);
            guid = new HostParameterGuid(value);
            this.minimum = minimum;
            this.maximum = maximum;
        }

        public HostParameterId id() { return id; }
        public HostParameterGuid guid() { return guid; }
        public float minimum() { return minimum; }
        public float maximum() { return maximum; }
    }

    public static final class HostParameterId {
        final String value;
        HostParameterId(final String value) { this.value = value; }
        public String value() { return value; }
    }

    public static final class HostParameterGuid {
        final String value;
        HostParameterGuid(final String value) { this.value = value; }
    }

    public interface Listener {
        void changed(Object ignored);
    }

    public static class Undo {
        public void addListener(final Listener listener) { }
        public void undo() { }
        public void redo() { }
    }

    public static final class ChangeUndo extends Undo {
        final HostMorphTarget target;
        final HostParameterGuid newParameter;
        final float newValue;
        HostParameterGuid oldParameter;
        float oldValue;

        ChangeUndo(final HostMorphTarget target, final HostParameterGuid parameter, final Float value) {
            this.target = target;
            newParameter = parameter;
            newValue = value;
            redo();
        }

        @Override public void undo() { target.set(oldParameter, oldValue); }
        @Override public void redo() {
            oldParameter = target.parameterGuid;
            oldValue = target.keyValue;
            target.set(newParameter, newValue);
        }
    }

    public static final class GroupUndo {
        final List<Undo> entries = new ArrayList<>();

        public boolean add(final Undo undo, final boolean significant) {
            entries.add(undo);
            return true;
        }

        void undo() {
            for (int index = entries.size() - 1; index >= 0; index--) entries.get(index).undo();
        }

        void redo() {
            entries.forEach(Undo::redo);
        }
    }

    public static final class EditMode {
        final List<GroupUndo> edits = new ArrayList<>();
        int beginCount;
        int endCount;
        boolean cancelled;
        private GroupUndo current;

        public GroupUndo begin(final String name) {
            beginCount++;
            current = new GroupUndo();
            return current;
        }

        public void end(final boolean cancel, final Object ignored) {
            endCount++;
            cancelled = cancel;
            if (!cancel && current != null) edits.add(current);
            current = null;
        }

        void undo() {
            final GroupUndo group = edits.remove(edits.size() - 1);
            group.undo();
        }
    }

    public static final class Pack {
        public void updateParameter(final boolean refresh) { }
        public void updatePartPalette(final boolean refresh) { }
        public void updateDeformerPalette(final boolean refresh) { }
        public void repaintCanvas(final boolean refresh) { }
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
        }
    }
}
