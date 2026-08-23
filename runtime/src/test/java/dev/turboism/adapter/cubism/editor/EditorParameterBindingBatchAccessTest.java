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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EditorParameterBindingBatchAccessTest {
    private static final ParameterBindingTarget TARGET_A =
        ParameterBindingTarget.artMesh(new ArtMeshId("mesh-a"));
    private static final ParameterBindingTarget TARGET_B =
        ParameterBindingTarget.artMesh(new ArtMeshId("mesh-b"));

    @Test
    void containedKeyformsRemapAndUseOneEditAndUndo() {
        final Fixture fixture = fixture(-70.0F, 50.0F, -30.0F, 0.0F, 30.0F);
        final Binding original = fixture.owner(TARGET_A).grid.findBinding("from");

        access(fixture).transferClamped(plan(false, TARGET_A));

        // Remap from the fixture source range [-100, 100] into [-70, 50].
        assertEquals(List.of(-28.0F, -10.0F, 8.0F), fixture.owner(TARGET_A).grid.keys("to"));
        assertSame(original, fixture.owner(TARGET_A).grid.findBinding("to"));
        assertEquals(List.of("change", "rearrange"), fixture.owner(TARGET_A).grid.calls);
        assertEquals(1, fixture.editMode.beginCount);
        assertEquals(1, fixture.edit.undoAdds);
        assertEquals(1, fixture.editMode.endCount);
        assertFalse(fixture.editMode.cancelled);
    }

    @Test
    void remapsMappedValuesInTheSourceOrder() {
        final Fixture fixture = fixture(-10.0F, 50.0F, -30.0F, 0.0F, 30.0F);

        access(fixture).transferClamped(plan(false, TARGET_A));

        // Remap from the fixture source range [-100, 100] into [-10, 50].
        assertEquals(List.of(11.0F, 20.0F, 29.0F), fixture.owner(TARGET_A).grid.keys("to"));
        assertEquals(List.of("change", "rearrange"), fixture.owner(TARGET_A).grid.calls);
    }

    @Test
    void invertedTransferNegatesThenRemapsInSourceOrder() {
        final Fixture fixture = fixture(-10.0F, 10.0F, -30.0F, 0.0F, 30.0F);

        access(fixture).transferClamped(plan(true, TARGET_A));

        // Invert first: [-30, 0, 30] -> [30, 0, -30], then remap [-100, 100] into [-10, 10].
        // Rearrange sorts the mapped coordinates, so the final key list is ascending.
        assertEquals(List.of(-3.0F, 0.0F, 3.0F), fixture.owner(TARGET_A).grid.keys("to"));
        assertEquals(List.of("change", "rearrange"), fixture.owner(TARGET_A).grid.calls);
        assertEquals(List.of(-30.0F, 0.0F, 30.0F), fixture.owner(TARGET_A).grid.rearrangeBefore);
        assertEquals(List.of(3.0F, 0.0F, -3.0F), fixture.owner(TARGET_A).grid.rearrangeAfter);
        assertEquals(
            List.of("keyform-2", "keyform-1", "keyform-0"),
            fixture.owner(TARGET_A).grid.keyformData()
        );
    }

    @Test
    void degenerateSourceRangeKeepsValuesUnremapped() {
        final Fixture fixture = fixture(-70.0F, 50.0F, -30.0F, 0.0F, 30.0F);
        fixture.parameters.put("from", new Source("from", 5.0F, 5.0F));

        access(fixture).transferClamped(plan(false, TARGET_A));

        // |srcMax - srcMin| < 1e-9: values move unchanged, only the parameter changes.
        assertEquals(List.of(-30.0F, 0.0F, 30.0F), fixture.owner(TARGET_A).grid.keys("to"));
        assertEquals(List.of("change", "rearrange"), fixture.owner(TARGET_A).grid.calls);
    }

    @Test
    void duplicateMappedValuesFailBeforeAnyEdit() {
        final Fixture fixture = fixture(-100.0F, 100.0F, 1.0F, 1.0000005F);

        assertThrows(
            IllegalStateException.class,
            () -> access(fixture).transferClamped(plan(false, TARGET_A))
        );

        assertEquals(0, fixture.editMode.beginCount);
        assertEquals(List.of(), fixture.owner(TARGET_A).grid.calls);
        assertEquals("from", fixture.owner(TARGET_A).grid.findBinding("from").parameterGuid);
    }

    @Test
    void anyInvalidTargetFailsBeforeAnotherTargetMutates() {
        final Fixture fixture = fixture(-10.0F, 50.0F, -30.0F, 0.0F, 30.0F);
        fixture.addOwner(TARGET_B); // exists but is not bound to the source parameter
        fixture.owner(TARGET_B).grid.byGuid.clear();

        assertThrows(
            IllegalStateException.class,
            () -> access(fixture).transferClamped(new ParameterBindingTransferPlan(
                new ParameterId("from"),
                new ParameterId("to"),
                List.of(TARGET_A, TARGET_B),
                false
            ))
        );

        assertEquals(0, fixture.editMode.beginCount);
        assertEquals(List.of(), fixture.owner(TARGET_A).grid.calls);
        assertEquals("from", fixture.owner(TARGET_A).grid.findBinding("from").parameterGuid);
        assertEquals(List.of(), fixture.owner(TARGET_B).grid.calls);
    }

    @Test
    void clampedTransferUsesTheExactVerifiedAliases() {
        final VerifiedMemberResolver resolver = resolver();
        for (String alias : List.of(
            "cubism.editor-model.keyform-binding.keys",
            "cubism.editor-model.keyform-grid.rearrange-keys",
            "cubism.editor-model.parameter-source.minimum",
            "cubism.editor-model.parameter-source.maximum"
        )) {
            assertNotNull(resolver.verifiedSelector(alias));
        }
    }

    private static ParameterBindingTransferPlan plan(
        final boolean invert,
        final ParameterBindingTarget target
    ) {
        return new ParameterBindingTransferPlan(
            new ParameterId("from"),
            new ParameterId("to"),
            List.of(target),
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
                fixture.currentChecks++;
            },
            (identity, source, model, target) -> fixture.owner(target),
            (model, parameterId) -> fixture.parameters.get(parameterId.value())
        );
    }

    private static Fixture fixture(
        final float minimum,
        final float maximum,
        final float... values
    ) {
        final Fixture fixture = new Fixture();
        fixture.parameters.put("from", new Source("from", -100.0F, 100.0F));
        fixture.parameters.put("to", new Source("to", minimum, maximum));
        fixture.addOwner(TARGET_A, values);
        Host.current = fixture.document;
        return fixture;
    }

    private static VerifiedMemberResolver resolver() {
        return TestVerifiedResolvers.create(
            "5.3.02",
            EditorParameterBindingBatchWriteSelectorContract.ADAPTER_SLICE_ID,
            Set.of(
                EditorParameterBindingBatchWriteSelectorContract.INVERT_CAPABILITY_ID,
                EditorParameterBindingBatchWriteSelectorContract.TRANSFER_CAPABILITY_ID
            ),
            selectors(),
            EditorParameterBindingBatchAccessTest.class.getClassLoader()
        );
    }

    private static List<StaticSelector> selectors() {
        return List.of(
            StaticSelector.staticMethod(
                "cubism.editor-model.app-controller.instance",
                internal(Host.class),
                "instance",
                descriptor(Host.class),
                StaticSelector.ACCESS_PUBLIC
            ),
            method("cubism.editor-model.app-controller.current-document", Host.class, "currentDocument", descriptor(Document.class)),
            method("cubism.editor-model.app-controller.complete-pack", Host.class, "completePack", descriptor(Pack.class)),
            method("cubism.editor-model.modeling-document.edit-mode", Document.class, "editMode", descriptor(EditMode.class)),
            method("cubism.editor-model.modeling-document.mark-dirty", Document.class, "markDirty", descriptor(void.class)),
            method("cubism.editor-model.edit-mode.begin", EditMode.class, "begin", descriptor(Edit.class, String.class)),
            method("cubism.editor-model.edit-mode.end", EditMode.class, "end", descriptor(void.class, boolean.class, Object.class)),
            method("cubism.editor-model.undo.add", Edit.class, "add", descriptor(boolean.class, Undo.class, boolean.class)),
            method("cubism.editor-model.undo.add-listener", Undo.class, "addListener", descriptor(void.class, UndoListener.class)),
            StaticSelector.classSelector("cubism.editor-model.undo-listener.class", internal(UndoListener.class)),
            method("cubism.editor-model.parameter-controllable-source.handler", ObjectSource.class, "handler", descriptor(Handler.class)),
            method("cubism.editor-model.parameter-controllable-handler.create-undo-for-all-edit", Handler.class, "createUndoForAllEdit", descriptor(Undo.class, String.class)),
            method("cubism.editor-model.parameter-controllable.keyform-grid", ObjectSource.class, "keyformGrid", descriptor(Grid.class)),
            method("cubism.editor-model.keyform-grid.bindings", Grid.class, "bindings", descriptor(List.class)),
            method("cubism.editor-model.keyform-binding.keys", Binding.class, "keys", descriptor(List.class)),
            method("cubism.editor-model.keyform-binding.parameter-guid", Binding.class, "parameterGuid", descriptor(Object.class)),
            method("cubism.editor-model.keyform-grid.find-binding", Grid.class, "findBinding", descriptor(Binding.class, Object.class)),
            method("cubism.editor-model.keyform-grid.reverse-parameter", Grid.class, "reverseParameter", descriptor(void.class, Object.class)),
            method("cubism.editor-model.keyform-grid.change-parameter", Grid.class, "changeParameter", descriptor(void.class, Object.class, Object.class)),
            method("cubism.editor-model.keyform-grid.rearrange-keys", Grid.class, "rearrangeKeyformsOnParameter", descriptor(void.class, Object.class, List.class, List.class)),
            method("cubism.editor-model.parameter.source", Parameter.class, "source", descriptor(Source.class)),
            method("cubism.editor-model.parameter-source.guid", Source.class, "guid", descriptor(Object.class)),
            method("cubism.editor-model.parameter-source.minimum", Source.class, "minimum", descriptor(float.class)),
            method("cubism.editor-model.parameter-source.maximum", Source.class, "maximum", descriptor(float.class)),
            method("cubism.editor-model.model-source.update-instances", ModelSource.class, "updateInstances", descriptor(void.class)),
            method("cubism.editor-model.complete-pack.update-parameter", Pack.class, "updateParameter", descriptor(void.class, boolean.class)),
            method("cubism.editor-model.complete-pack.update-part-palette", Pack.class, "updatePartPalette", descriptor(void.class, boolean.class)),
            method("cubism.editor-model.complete-pack.update-deformer-palette", Pack.class, "updateDeformerPalette", descriptor(void.class, boolean.class)),
            method("cubism.editor-model.complete-pack.repaint-canvas", Pack.class, "repaintCanvas", descriptor(void.class, boolean.class))
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

    private static String internal(final Class<?> type) {
        return type.getName().replace('.', '/');
    }

    private static String descriptor(final Class<?> returnType, final Class<?>... parameters) {
        return "(" + Arrays.stream(parameters).map(EditorParameterBindingBatchAccessTest::typeDescriptor).reduce("", String::concat)
            + ")" + typeDescriptor(returnType);
    }

    private static String typeDescriptor(final Class<?> type) {
        if (type == void.class) return "V";
        if (type == boolean.class) return "Z";
        if (type == float.class) return "F";
        if (type == int.class) return "I";
        if (type.isArray()) return type.getName().replace('.', '/');
        return "L" + internal(type) + ";";
    }

    public static final class Host {
        private static final Host INSTANCE = new Host();
        private static Document current;

        public static Host instance() { return INSTANCE; }
        public Document currentDocument() { return current; }
        public Pack completePack() { return current.pack; }
    }

    public static final class Document {
        private final EditMode editMode;
        private final Pack pack;

        Document(final Fixture fixture) {
            editMode = fixture.editMode;
            pack = fixture.pack;
        }

        public EditMode editMode() { return editMode; }
        public void markDirty() { editMode.fixture.dirtyCount++; }
    }

    public static final class EditMode {
        private final Fixture fixture;
        private int beginCount;
        private int endCount;
        private boolean cancelled;

        EditMode(final Fixture fixture) { this.fixture = fixture; }
        public Edit begin(final String name) {
            beginCount++;
            fixture.edit = new Edit(fixture);
            return fixture.edit;
        }
        public void end(final boolean cancel, final Object ignored) {
            endCount++;
            cancelled = cancel;
        }
    }

    public static final class Edit {
        private final Fixture fixture;
        private int undoAdds;

        Edit(final Fixture fixture) { this.fixture = fixture; }
        public boolean add(final Undo undo, final boolean accepted) {
            undoAdds++;
            return accepted;
        }
    }

    public interface UndoListener {
        void changed(Object value);
    }

    public static final class Undo {
        private final Fixture fixture;
        Undo(final Fixture fixture) { this.fixture = fixture; }
        public void addListener(final UndoListener listener) { fixture.listenerAdds++; }
    }

    public static final class Handler {
        private final Fixture fixture;
        Handler(final Fixture fixture) { this.fixture = fixture; }
        public Undo createUndoForAllEdit(final String name) {
            fixture.undoCreates++;
            return new Undo(fixture);
        }
    }

    public static final class ObjectSource {
        private final Grid grid;
        private final Handler handler;

        ObjectSource(final Fixture fixture, final float... values) {
            grid = new Grid(values);
            handler = new Handler(fixture);
        }

        public Handler handler() { return handler; }
        public Grid keyformGrid() { return grid; }
    }

    public static final class Grid {
        private final Map<Object, Binding> byGuid = new LinkedHashMap<>();
        private final List<String> calls = new ArrayList<>();
        private List<Float> rearrangeBefore;
        private List<Float> rearrangeAfter;
        private List<String> keyformData;

        private record MappedKeyform(float mapped, int originalIndex) { }

        Grid(final float... values) {
            byGuid.put("from", new Binding("from", values));
            keyformData = new ArrayList<>(values.length);
            for (int index = 0; index < values.length; index++) {
                keyformData.add("keyform-" + index);
            }
        }

        public List<Binding> bindings() { return List.copyOf(byGuid.values()); }
        public Binding findBinding(final Object guid) { return byGuid.get(guid); }
        public void changeParameter(final Object source, final Object target) {
            calls.add("change");
            final Binding binding = byGuid.remove(source);
            if (binding == null) throw new IllegalStateException("source binding missing");
            binding.parameterGuid = target;
            byGuid.put(target, binding);
        }
        public void reverseParameter(final Object guid) {
            calls.add("reverse");
            final Binding binding = byGuid.get(guid);
            if (binding == null) throw new IllegalStateException("destination binding missing");
            final ArrayList<Float> reversed = new ArrayList<>(binding.keys);
            java.util.Collections.reverse(reversed);
            binding.keys = List.copyOf(reversed);
            final ArrayList<String> reversedKeyformData = new ArrayList<>(keyformData);
            java.util.Collections.reverse(reversedKeyformData);
            keyformData = List.copyOf(reversedKeyformData);
        }
        public void rearrangeKeyformsOnParameter(
            final Object guid,
            final List<?> before,
            final List<?> after
        ) {
            calls.add("rearrange");
            final Binding binding = byGuid.get(guid);
            if (binding == null) throw new IllegalStateException("destination binding missing");
            if (!binding.keys.equals(before)) throw new IllegalStateException("stale key list");
            rearrangeBefore = before.stream().map(value -> ((Number) value).floatValue()).toList();
            rearrangeAfter = after.stream().map(value -> ((Number) value).floatValue()).toList();

            // Mirror Cubism 5.3.02: map by source-order index, sort mapped coordinates,
            // then move grid keyform data using each pair's original index.
            final List<Float> currentKeys = List.copyOf(binding.keys);
            final List<String> currentKeyformData = List.copyOf(keyformData);
            final ArrayList<MappedKeyform> pairs = new ArrayList<>(currentKeys.size());
            for (int index = 0; index < currentKeys.size(); index++) {
                final float currentValue = currentKeys.get(index);
                final int sourceIndex = before.indexOf(currentValue);
                final float mapped = sourceIndex < 0
                    ? currentValue
                    : ((Number) after.get(sourceIndex)).floatValue();
                pairs.add(new MappedKeyform(mapped, index));
            }
            pairs.sort((left, right) -> Float.compare(left.mapped(), right.mapped()));
            binding.keys = pairs.stream().map(MappedKeyform::mapped).toList();
            keyformData = pairs.stream()
                .map(pair -> currentKeyformData.get(pair.originalIndex()))
                .toList();
        }
        public List<Float> keys(final Object guid) {
            final Binding binding = byGuid.get(guid);
            return binding == null ? List.of() : binding.keys;
        }
        public List<String> keyformData() { return keyformData; }
    }

    public static final class Binding {
        private Object parameterGuid;
        private List<Float> keys;

        Binding(final Object parameterGuid, final float... values) {
            this.parameterGuid = parameterGuid;
            this.keys = new ArrayList<>(values.length);
            for (final float value : values) keys.add(value);
        }

        public List<Float> keys() { return keys; }
        public Object parameterGuid() { return parameterGuid; }
    }

    public static final class Source {
        private final String guid;
        private final float minimum;
        private final float maximum;

        Source(final String guid, final float minimum, final float maximum) {
            this.guid = guid;
            this.minimum = minimum;
            this.maximum = maximum;
        }

        public Object guid() { return guid; }
        public float minimum() { return minimum; }
        public float maximum() { return maximum; }
    }

    public static final class Parameter {
        private final Source source = new Source("unused", -1.0F, 1.0F);
        public Source source() { return source; }
    }

    public static final class ModelSource {
        private final Fixture fixture;
        ModelSource(final Fixture fixture) { this.fixture = fixture; }
        public void updateInstances() { fixture.updateInstancesCount++; }
    }

    public static final class Pack {
        private final Fixture fixture;
        Pack(final Fixture fixture) { this.fixture = fixture; }
        public void updateParameter(final boolean refresh) { fixture.parameterRefreshCount++; }
        public void updatePartPalette(final boolean refresh) { fixture.partRefreshCount++; }
        public void updateDeformerPalette(final boolean refresh) { fixture.deformerRefreshCount++; }
        public void repaintCanvas(final boolean refresh) { fixture.repaintCount++; }
    }

    private static final class Fixture {
        private final Object model = new Object();
        private final ModelSource modelSource;
        private final Map<String, Source> parameters = new LinkedHashMap<>();
        private final Map<ParameterBindingTarget, ObjectSource> owners = new LinkedHashMap<>();
        private final EditMode editMode;
        private final Pack pack;
        private final Document document;
        private int currentChecks;
        private int dirtyCount;
        private int updateInstancesCount;
        private int undoCreates;
        private int listenerAdds;
        private int parameterRefreshCount;
        private int partRefreshCount;
        private int deformerRefreshCount;
        private int repaintCount;
        private Edit edit;

        Fixture() {
            modelSource = new ModelSource(this);
            editMode = new EditMode(this);
            pack = new Pack(this);
            document = new Document(this);
        }

        void addOwner(final ParameterBindingTarget target, final float... values) {
            owners.put(target, new ObjectSource(this, values));
        }

        ObjectSource owner(final ParameterBindingTarget target) {
            return owners.get(target);
        }
    }
}
