package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.selector.EditorParameterDefinitionWriteSelectorContract;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.ParameterDefinition;
import dev.turboism.sdk.cubism.model.ParameterType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EditorBackedCubismParameterDefinitionWriteTest {

    @AfterEach
    void clearHost() {
        Host.currentDocument = null;
    }

    @Test
    void definitionUpdateUsesOneNativeEditorOperationWithUndoRedoAndValuePreservation() {
        final Fixture fixture = new Fixture();
        Host.install(fixture);
        final EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(
            resolver(true),
            "session-a"
        );
        final var parameter = access.active().parameters().find(new ParameterId("ParamAngleX"));

        parameter.updateDefinition(new ParameterDefinition(
            new ParameterId("ParamAngleX2"),
            "Renamed Angle X",
            -45.0F,
            5.0F,
            45.0F,
            ParameterType.BLEND_SHAPE,
            true
        ));

        assertThrows(java.util.NoSuchElementException.class, () ->
            access.active().parameters().find(new ParameterId("ParamAngleX"))
        );
        assertDefinition(
            access,
            "ParamAngleX2",
            "Renamed Angle X",
            -45.0F,
            5.0F,
            45.0F,
            ParameterType.BLEND_SHAPE,
            true
        );
        assertEquals(12.0F, access.active().parameters()
            .find(new ParameterId("ParamAngleX2")).getValue());
        assertEquals(1, fixture.propertyEditor.definitionUpdates);
        assertEquals(1, fixture.editMode.beginCalls);
        assertEquals(1, fixture.editMode.endCalls);
        assertEquals(1, fixture.editMode.committedEdits);
        assertEquals(1, fixture.propertyEditor.keepValueRebuilds);
        assertEquals(1, fixture.operation.refreshes);
        assertEquals(0, fixture.operation.finishes);

        fixture.editMode.undo();
        assertDefinition(
            access,
            "ParamAngleX",
            "Angle X",
            -30.0F,
            0.0F,
            30.0F,
            ParameterType.NORMAL,
            false
        );

        fixture.editMode.redo();
        assertDefinition(
            access,
            "ParamAngleX2",
            "Renamed Angle X",
            -45.0F,
            5.0F,
            45.0F,
            ParameterType.BLEND_SHAPE,
            true
        );
        assertEquals(2, fixture.propertyEditor.undoRedoRebuilds);
        assertEquals(3, fixture.operation.refreshes);
        assertEquals(0, fixture.operation.finishes);
    }

    @Test
    void definitionUpdateFailsClosedWithoutSeparatelyVerifiedMetadataWriteCapability() {
        final Fixture fixture = new Fixture();
        Host.install(fixture);
        final EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(
            resolver(false),
            "session-a"
        );
        final var parameter = access.active().parameters().find(new ParameterId("ParamAngleX"));

        assertThrows(UnsupportedOperationException.class, () -> parameter.updateDefinition(
            new ParameterDefinition(
                new ParameterId("ParamAngleX2"),
                "Renamed Angle X",
                -45.0F,
                5.0F,
                45.0F,
                ParameterType.BLEND_SHAPE,
                true
            )
        ));

        assertEquals(0, fixture.propertyEditor.definitionUpdates);
        assertEquals(0, fixture.editMode.beginCalls);
        assertDefinition(
            access,
            "ParamAngleX",
            "Angle X",
            -30.0F,
            0.0F,
            30.0F,
            ParameterType.NORMAL,
            false
        );
    }

    @Test
    void invalidOrDuplicateIdsAreRejectedBeforeTheNativeDefinitionOperation() {
        final Fixture fixture = new Fixture();
        fixture.validator.rejectedIds.add("ParamTaken");
        Host.install(fixture);
        final EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(
            resolver(true),
            "session-a"
        );
        final var parameter = access.active().parameters().find(new ParameterId("ParamAngleX"));

        assertThrows(IllegalArgumentException.class, () -> parameter.updateDefinition(
            new ParameterDefinition(
                new ParameterId("ParamTaken"),
                "Angle X",
                -30.0F,
                0.0F,
                30.0F,
                ParameterType.NORMAL,
                false
            )
        ));

        assertEquals(0, fixture.propertyEditor.definitionUpdates);
        assertEquals(0, fixture.editMode.beginCalls);
        assertDefinition(
            access,
            "ParamAngleX",
            "Angle X",
            -30.0F,
            0.0F,
            30.0F,
            ParameterType.NORMAL,
            false
        );
    }

    @Test
    void boundParameterRejectsBlendShapeChangeBeforeAnyNativeCommitSideEffect() {
        final Fixture fixture = new Fixture();
        fixture.bindNormalParameter();
        Host.install(fixture);
        final EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(
            resolver(true),
            "session-a"
        );
        final var parameter = access.active().parameters().find(new ParameterId("ParamAngleX"));

        final IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            () -> parameter.updateDefinition(new ParameterDefinition(
                new ParameterId("ParamAngleX"),
                "Angle X",
                -30.0F,
                0.0F,
                30.0F,
                ParameterType.BLEND_SHAPE,
                false
            ))
        );

        assertEquals(
            "Cannot change Blend Shape because the parameter is bound to one or more model objects.",
            failure.getMessage()
        );
        assertEquals(0, fixture.propertyEditor.definitionUpdates);
        assertEquals(0, fixture.editMode.beginCalls);
        assertEquals(0, fixture.propertyEditor.keepValueRebuilds);
        assertEquals(0, fixture.operation.refreshes);
        assertDefinition(
            access,
            "ParamAngleX",
            "Angle X",
            -30.0F,
            0.0F,
            30.0F,
            ParameterType.NORMAL,
            false
        );
    }

    @Test
    void boundParameterCanStillChangeMetadataWhenBlendShapeStateIsUnchanged() {
        final Fixture fixture = new Fixture();
        fixture.bindNormalParameter();
        Host.install(fixture);
        final EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(
            resolver(true),
            "session-a"
        );
        final var parameter = access.active().parameters().find(new ParameterId("ParamAngleX"));

        parameter.updateDefinition(new ParameterDefinition(
            new ParameterId("ParamAngleX"),
            "Renamed Angle X",
            -30.0F,
            0.0F,
            30.0F,
            ParameterType.NORMAL,
            false
        ));

        assertEquals(1, fixture.propertyEditor.definitionUpdates);
        assertDefinition(
            access,
            "ParamAngleX",
            "Renamed Angle X",
            -30.0F,
            0.0F,
            30.0F,
            ParameterType.NORMAL,
            false
        );
    }

    @Test
    void stricterHostRangeAndStructuralValidationRunBeforeTheNativeCommit() {
        final Fixture fixture = new Fixture();
        fixture.validator.keysOutsideRange = true;
        Host.install(fixture);
        final EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(
            resolver(true),
            "session-a"
        );
        final var parameter = access.active().parameters().find(new ParameterId("ParamAngleX"));

        assertThrows(IllegalArgumentException.class, () -> parameter.updateDefinition(
            new ParameterDefinition(
                new ParameterId("ParamAngleX"),
                "Angle X",
                10.0F,
                10.0F,
                10.0F,
                ParameterType.NORMAL,
                false
            )
        ));
        assertEquals(0, fixture.operation.propertyEditorLookups);
        assertEquals(0, fixture.operation.validatorLookups);

        assertThrows(IllegalStateException.class, () -> parameter.updateDefinition(
            new ParameterDefinition(
                new ParameterId("ParamAngleX"),
                "Angle X",
                -20.0F,
                0.0F,
                20.0F,
                ParameterType.NORMAL,
                false
            )
        ));
        assertEquals(0, fixture.propertyEditor.definitionUpdates);

        fixture.validator.keysOutsideRange = false;
        fixture.bindNormalParameter();
        assertThrows(IllegalStateException.class, () -> parameter.updateDefinition(
            new ParameterDefinition(
                new ParameterId("ParamAngleX"),
                "Angle X",
                -30.0F,
                0.0F,
                30.0F,
                ParameterType.BLEND_SHAPE,
                false
            )
        ));
        assertEquals(0, fixture.propertyEditor.definitionUpdates);

        fixture.clearBindings();
        fixture.helper.morphTargetEligible = false;
        assertThrows(IllegalStateException.class, () -> parameter.updateDefinition(
            new ParameterDefinition(
                new ParameterId("ParamAngleX"),
                "Angle X",
                -30.0F,
                0.0F,
                30.0F,
                ParameterType.BLEND_SHAPE,
                false
            )
        ));
        assertEquals(0, fixture.propertyEditor.definitionUpdates);
    }

    private static void assertDefinition(
        final EditorBackedCubismModelAccess access,
        final String id,
        final String name,
        final float minimum,
        final float defaultValue,
        final float maximum,
        final ParameterType type,
        final boolean repeat
    ) {
        final var parameter = access.active().parameters().find(new ParameterId(id));
        assertEquals(name, parameter.name().orElseThrow());
        assertEquals(minimum, parameter.getMinimumValue());
        assertEquals(defaultValue, parameter.getDefaultValue());
        assertEquals(maximum, parameter.getMaximumValue());
        assertEquals(type, parameter.type());
        assertEquals(repeat, parameter.repeat().orElseThrow());
    }

    private static VerifiedMemberResolver resolver(final boolean metadataWriteAuthorized) {
        final String host = internal(Host.class);
        final String document = internal(Document.class);
        final String modelSource = internal(ModelSource.class);
        final String model = internal(Model.class);
        final String parameterSet = internal(ParameterSet.class);
        final String parameter = internal(Parameter.class);
        final String source = internal(ParameterSource.class);
        final String id = internal(Id.class);
        final String mainFrame = internal(MainFrame.class);
        final String palette = internal(ParameterPalette.class);
        final String paletteView = internal(ParameterPaletteView.class);
        final String operation = internal(ParameterOperation.class);
        final String propertyEditor = internal(PropertyEditor.class);
        final String validator = internal(Validator.class);
        final String helperOwner = internal(HelperOwner.class);
        final String helper = internal(Helper.class);
        final String callback = internal(HostFunction0.class);
        return TestVerifiedResolvers.create(
            "5.3.02",
            EditorParameterDefinitionWriteSelectorContract.ADAPTER_SLICE_ID,
            metadataWriteAuthorized
                ? java.util.Set.of(
                    "cubism.editor-model.read",
                    "cubism.editor-model.write",
                    EditorParameterDefinitionWriteSelectorContract.CAPABILITY_ID
                )
                : java.util.Set.of(
                    "cubism.editor-model.read",
                    "cubism.editor-model.write"
                ),
            List.of(
                StaticSelector.classSelector("cubism.editor-model.app-controller.class", host),
                StaticSelector.staticMethod("cubism.editor-model.app-controller.instance", host, "instance", desc(Host.class), StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC),
                method("cubism.editor-model.app-controller.current-document", Host.class, "currentDocument", desc(Document.class)),
                method("cubism.editor-model.app-controller.main-frame", Host.class, "mainFrame", desc(MainFrame.class)),
                StaticSelector.classSelector("cubism.editor-model.modeling-document.class", document),
                method("cubism.editor-model.modeling-document.model-source", Document.class, "modelSource", desc(ModelSource.class)),
                method("cubism.editor-model.modeling-document.last-active-view", Document.class, "lastActiveView", "()Ljava/lang/Object;"),
                StaticSelector.classSelector("cubism.editor-model.modeling-view.class", internal(Object.class)),
                method("cubism.editor-model.modeling-view.model", Object.class, "toString", "()Ljava/lang/String;"),
                StaticSelector.classSelector("cubism.editor-model.model-source.class", modelSource),
                method("cubism.editor-model.model-source.guid", ModelSource.class, "guid", desc(Id.class)),
                method("cubism.editor-model.model-source.current-instance", ModelSource.class, "currentInstance", desc(Model.class)),
                method("cubism.editor-model.model-source.all-parameters", ModelSource.class, "allParameters", "()Ljava/util/List;"),
                StaticSelector.classSelector("cubism.editor-model.model.class", model),
                method("cubism.editor-model.model.parameter-set", Model.class, "parameterSet", desc(ParameterSet.class)),
                StaticSelector.classSelector("cubism.editor-model.parameter-set.class", parameterSet),
                method("cubism.editor-model.parameter-set.parameters", ParameterSet.class, "parameters", "()Ljava/util/List;"),
                StaticSelector.classSelector("cubism.editor-model.parameter.class", parameter),
                method("cubism.editor-model.parameter.id", Parameter.class, "id", desc(Id.class)),
                method("cubism.editor-model.parameter.value", Parameter.class, "value", "()F"),
                method("cubism.editor-model.parameter.source", Parameter.class, "source", desc(ParameterSource.class)),
                StaticSelector.classSelector("cubism.editor-model.parameter-source.class", source),
                method("cubism.editor-model.parameter-source.minimum", ParameterSource.class, "minimum", "()F"),
                method("cubism.editor-model.parameter-source.maximum", ParameterSource.class, "maximum", "()F"),
                method("cubism.editor-model.parameter-source.default", ParameterSource.class, "defaultValue", "()F"),
                method("cubism.editor-model.parameter-source.name", ParameterSource.class, "name", "()Ljava/lang/String;"),
                method("cubism.editor-model.parameter-source.repeat", ParameterSource.class, "repeat", "()Z"),
                method("cubism.editor-model.parameter-source.morph-target", ParameterSource.class, "morphTarget", "()Z"),
                method("cubism.editor-model.parameter-source.combined", ParameterSource.class, "combined", "()Z"),
                method("cubism.editor-model.parameter-source.guid", ParameterSource.class, "guid", desc(Id.class)),
                StaticSelector.classSelector("cubism.editor-model.id.class", id),
                method("cubism.editor-model.id.value", Id.class, "value", "()Ljava/lang/String;"),
                StaticSelector.classSelector("cubism.editor-model.guid.class", id),
                method("cubism.editor-model.guid.value", Id.class, "value", "()Ljava/lang/String;"),
                StaticSelector.classSelector("cubism.editor-model.main-frame.class", mainFrame),
                method("cubism.editor-model.main-frame.parameter-palette", MainFrame.class, "parameterPalette", desc(ParameterPalette.class)),
                StaticSelector.classSelector("cubism.editor-model.parameter-palette.class", palette),
                method("cubism.editor-model.parameter-palette.view", ParameterPalette.class, "view", desc(ParameterPaletteView.class)),
                StaticSelector.classSelector("cubism.editor-model.parameter-palette-view.class", paletteView),
                method("cubism.editor-model.parameter-palette-view.operation", ParameterPaletteView.class, "operation", desc(ParameterOperation.class)),
                StaticSelector.classSelector("cubism.editor-model.parameter-operation.class", operation),
                StaticSelector.staticMethod("cubism.editor-model.parameter-operation.property-editor", operation, "propertyEditor", "(L" + operation + ";)L" + propertyEditor + ";", StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC),
                StaticSelector.staticMethod("cubism.editor-model.parameter-operation.validator", operation, "validator", "(L" + operation + ";)L" + validator + ";", StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC),
                method("cubism.editor-model.parameter-operation.refresh", ParameterOperation.class, "refresh", "(Z)V"),
                StaticSelector.classSelector("cubism.editor-model.parameter-property-editor.class", propertyEditor),
                method("cubism.editor-model.parameter-property-editor.update-definition", PropertyEditor.class, "updateDefinition", "(L" + source + ";Ljava/lang/String;FFFLjava/lang/String;ZZZL" + callback + ";)Z"),
                method("cubism.editor-model.parameter-property-editor.rebuild-keep-value", PropertyEditor.class, "rebuildKeepValue", "()V"),
                StaticSelector.classSelector("cubism.editor-model.parameter-validator.class", validator),
                method("cubism.editor-model.parameter-validator.valid-id", Validator.class, "validId", "(Ljava/lang/String;)Z"),
                method("cubism.editor-model.parameter-validator.supports-type", Validator.class, "supportsType", "(Z)Z"),
                method("cubism.editor-model.parameter-validator.reject-type-change", Validator.class, "rejectTypeChange", "(L" + source + ";ZZ)Z"),
                method("cubism.editor-model.parameter-validator.allow-repeat", Validator.class, "allowRepeat", "(L" + source + ";Z)Z"),
                method("cubism.editor-model.parameter-validator.keys-outside-range", Validator.class, "keysOutsideRange", "(L" + id + ";FF)Z"),
                method("cubism.editor-model.parameter-validator.default-change-affects-morph-target", Validator.class, "defaultChangeAffectsMorphTarget", "(L" + source + ";F)Z"),
                StaticSelector.classSelector("cubism.editor-model.parameter-helper-owner.class", helperOwner),
                StaticSelector.classSelector("cubism.editor-model.parameter-helper.class", helper),
                StaticSelector.field("cubism.editor-model.parameter-helper.instance", helperOwner, "INSTANCE", "L" + helper + ";", StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC),
                StaticSelector.classSelector("cubism.editor-model.parameter-controllable.class", internal(ParameterControllable.class)),
                StaticSelector.classSelector("cubism.editor-model.keyform-grid.class", internal(KeyformGrid.class)),
                StaticSelector.classSelector("cubism.editor-model.morph-target-set.class", internal(MorphTargetSet.class)),
                method("cubism.editor-model.parameter-helper.morph-target-eligible", Helper.class, "morphTargetEligible", "(L" + source + ";)Z"),
                method("cubism.editor-model.parameter-source.model-source", ParameterSource.class, "modelSource", desc(ModelSource.class)),
                method("cubism.editor-model.model-source.all-objects", ModelSource.class, "allObjects", "()Ljava/util/List;"),
                method("cubism.editor-model.parameter-controllable.keyform-grid", ParameterControllable.class, "keyformGrid", desc(KeyformGrid.class)),
                method("cubism.editor-model.keyform-grid.contains-parameter", KeyformGrid.class, "contains", "(L" + id + ";)Z"),
                method("cubism.editor-model.parameter-controllable.morph-target-set", ParameterControllable.class, "morphTargetSet", desc(MorphTargetSet.class)),
                method("cubism.editor-model.morph-target-set.contains-parameter", MorphTargetSet.class, "contains", "(L" + id + ";)Z"),
                StaticSelector.constructor("cubism.editor-model.parameter-refresh-callback.create", internal(HostRefreshCallback.class), "(L" + operation + ";)V", 0)
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
        return StaticSelector.method(alias, internal(owner), name, descriptor, StaticSelector.ACCESS_PUBLIC);
    }

    private static String internal(final Class<?> type) {
        return type.getName().replace('.', '/');
    }

    private static String desc(final Class<?> type) {
        return "()L" + internal(type) + ";";
    }

    public static final class Host {
        static final Host INSTANCE = new Host();
        static Document currentDocument;
        static MainFrame mainFrame;
        public static Host instance() { return INSTANCE; }
        public Document currentDocument() { return currentDocument; }
        public MainFrame mainFrame() { return mainFrame; }
        static void install(final Fixture fixture) {
            currentDocument = fixture.document;
            mainFrame = fixture.mainFrame;
        }
    }

    public static final class Document {
        final ModelSource source;
        Document(final ModelSource source) { this.source = source; }
        public ModelSource modelSource() { return source; }
        public Object lastActiveView() { return null; }
    }

    public static final class ModelSource {
        final Id guid = new Id("model-a");
        final ParameterSource source;
        final ParameterSet parameterSet;
        final Model model;
        final List<ParameterControllable> objects = new ArrayList<>();
        ModelSource(final ParameterSource source) {
            this.source = source;
            parameterSet = new ParameterSet(source);
            model = new Model(parameterSet);
        }
        public Id guid() { return guid; }
        public Model currentInstance() { return model; }
        public List<ParameterSource> allParameters() { return List.of(source); }
        public List<ParameterControllable> allObjects() { return List.copyOf(objects); }
        void rebuild(final boolean keepValue) { parameterSet.rebuild(keepValue); }
    }

    public static final class Model {
        final ParameterSet parameterSet;
        Model(final ParameterSet parameterSet) { this.parameterSet = parameterSet; }
        public ParameterSet parameterSet() { return parameterSet; }
    }

    public static final class ParameterSet {
        final ParameterSource source;
        Parameter parameter;
        ParameterSet(final ParameterSource source) {
            this.source = source;
            parameter = new Parameter(source, 12.0F);
        }
        public List<Parameter> parameters() { return List.of(parameter); }
        void rebuild(final boolean keepValue) {
            parameter = new Parameter(source, keepValue ? parameter.value : source.defaultValue);
        }
    }

    public static final class Parameter {
        final ParameterSource source;
        final float value;
        Parameter(final ParameterSource source, final float value) {
            this.source = source;
            this.value = value;
        }
        public Id id() { return source.id; }
        public float value() { return value; }
        public ParameterSource source() { return source; }
    }

    public enum HostParameterType { NORMAL, MORPH_TARGET }

    public static final class ParameterSource {
        final Id guid = new Id("guid-a");
        Id id = new Id("ParamAngleX");
        String name = "Angle X";
        float minimum = -30.0F;
        float defaultValue = 0.0F;
        float maximum = 30.0F;
        HostParameterType type = HostParameterType.NORMAL;
        boolean repeat;
        public float minimum() { return minimum; }
        public float maximum() { return maximum; }
        public float defaultValue() { return defaultValue; }
        public String name() { return name; }
        public boolean repeat() { return repeat; }
        public boolean morphTarget() { return type == HostParameterType.MORPH_TARGET; }
        public boolean combined() { return false; }
        public Id guid() { return guid; }
        public ModelSource modelSource() { return Host.currentDocument.modelSource(); }
        Snapshot snapshot() {
            return new Snapshot(id, name, minimum, defaultValue, maximum, type, repeat);
        }
        void restore(final Snapshot snapshot) {
            id = snapshot.id;
            name = snapshot.name;
            minimum = snapshot.minimum;
            defaultValue = snapshot.defaultValue;
            maximum = snapshot.maximum;
            type = snapshot.type;
            repeat = snapshot.repeat;
        }
    }

    public record Snapshot(
        Id id,
        String name,
        float minimum,
        float defaultValue,
        float maximum,
        HostParameterType type,
        boolean repeat
    ) { }

    public static final class ParameterControllable {
        final KeyformGrid keyformGrid = new KeyformGrid();
        final MorphTargetSet morphTargetSet = new MorphTargetSet();
        public KeyformGrid keyformGrid() { return keyformGrid; }
        public MorphTargetSet morphTargetSet() { return morphTargetSet; }
    }

    public static final class KeyformGrid {
        final java.util.Set<Id> parameters = new java.util.HashSet<>();
        public boolean contains(final Id parameterGuid) { return parameters.contains(parameterGuid); }
    }

    public static final class MorphTargetSet {
        final java.util.Set<Id> parameters = new java.util.HashSet<>();
        public boolean contains(final Id parameterGuid) { return parameters.contains(parameterGuid); }
    }

    public interface HostFunction0 {
        Object invoke();
    }

    public static final class HelperOwner {
        public static final Helper INSTANCE = new Helper();
    }

    public static final class Helper {
        boolean morphTargetEligible = true;
        public boolean morphTargetEligible(final ParameterSource source) {
            return morphTargetEligible;
        }
    }

    public static final class Validator {
        final java.util.Set<String> rejectedIds = new java.util.HashSet<>();
        boolean keysOutsideRange;
        boolean rejectTypeChange;
        boolean supportsType = true;
        boolean allowRepeat = true;
        boolean defaultChangeAffectsMorphTarget;
        public boolean validId(final String id) {
            return !id.isBlank() && !id.contains(" ") && !rejectedIds.contains(id);
        }
        public boolean supportsType(final boolean desiredMorphTarget) { return supportsType; }
        public boolean rejectTypeChange(
            final ParameterSource source,
            final boolean desiredMorphTarget,
            final boolean creating
        ) { return rejectTypeChange; }
        public boolean allowRepeat(final ParameterSource source, final boolean desired) {
            return allowRepeat;
        }
        public boolean keysOutsideRange(final Id guid, final float minimum, final float maximum) {
            return keysOutsideRange;
        }
        public boolean defaultChangeAffectsMorphTarget(
            final ParameterSource source,
            final float defaultValue
        ) { return defaultChangeAffectsMorphTarget; }
    }

    public static final class HostRefreshCallback implements HostFunction0 {
        final ParameterOperation operation;
        HostRefreshCallback(final ParameterOperation operation) {
            this.operation = operation;
        }
        @Override public Object invoke() {
            operation.refresh(true);
            return null;
        }
    }

    public static final class PropertyEditor {
        final ModelSource modelSource;
        final EditMode editMode;
        int definitionUpdates;
        int keepValueRebuilds;
        int undoRedoRebuilds;
        PropertyEditor(final ModelSource modelSource, final EditMode editMode) {
            this.modelSource = modelSource;
            this.editMode = editMode;
        }
        public boolean updateDefinition(
            final ParameterSource source,
            final String name,
            final float minimum,
            final float maximum,
            final float defaultValue,
            final String id,
            final boolean repeat,
            final boolean updateType,
            final boolean morphTarget,
            final HostFunction0 refresh
        ) {
            definitionUpdates++;
            final Undo undo = editMode.begin("Parameter");
            undo.add(new SimpleUndo(source), true);
            source.name = name;
            source.minimum = minimum;
            source.maximum = maximum;
            source.defaultValue = defaultValue;
            source.id = new Id(id);
            source.repeat = repeat;
            if (updateType) {
                source.type = morphTarget
                    ? HostParameterType.MORPH_TARGET
                    : HostParameterType.NORMAL;
            }
            undo.listeners.add(() -> {
                undoRedoRebuilds++;
                modelSource.rebuild(false);
                refresh.invoke();
            });
            editMode.end(false, null);
            return true;
        }
        public void rebuildKeepValue() {
            keepValueRebuilds++;
            modelSource.rebuild(true);
        }
    }

    public static final class ParameterOperation {
        final PropertyEditor propertyEditor;
        final Validator validator;
        int refreshes;
        int finishes;
        ParameterOperation(final PropertyEditor propertyEditor, final Validator validator) {
            this.propertyEditor = propertyEditor;
            this.validator = validator;
        }
        int propertyEditorLookups;
        int validatorLookups;
        public static PropertyEditor propertyEditor(final ParameterOperation operation) {
            operation.propertyEditorLookups++;
            return operation.propertyEditor;
        }
        public static Validator validator(final ParameterOperation operation) {
            operation.validatorLookups++;
            return operation.validator;
        }
        public void refresh(final boolean immediate) { refreshes++; }
        public void finishDefinitionUpdate() { finishes++; }
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

    private static final class SimpleUndo {
        final ParameterSource target;
        final Snapshot undoData;
        Snapshot redoData;
        SimpleUndo(final ParameterSource target) {
            this.target = target;
            undoData = target.snapshot();
        }
        void undo() {
            redoData = target.snapshot();
            target.restore(undoData);
        }
        void redo() { target.restore(redoData); }
    }

    private static final class Undo {
        final ArrayList<SimpleUndo> edits = new ArrayList<>();
        final ArrayList<Runnable> listeners = new ArrayList<>();
        boolean add(final SimpleUndo edit, final boolean force) {
            edits.add(edit);
            return true;
        }
        void undo() {
            for (int index = edits.size() - 1; index >= 0; index--) edits.get(index).undo();
            listeners.forEach(Runnable::run);
        }
        void redo() {
            edits.forEach(SimpleUndo::redo);
            listeners.forEach(Runnable::run);
        }
    }

    public static final class EditMode {
        int beginCalls;
        int endCalls;
        int committedEdits;
        Undo activeUndo;
        Undo committedUndo;
        Undo begin(final String name) {
            beginCalls++;
            activeUndo = new Undo();
            return activeUndo;
        }
        boolean end(final boolean cancelled, final Object callback) {
            endCalls++;
            if (!cancelled) {
                committedEdits++;
                committedUndo = activeUndo;
            }
            activeUndo = null;
            return !cancelled;
        }
        void undo() { committedUndo.undo(); }
        void redo() { committedUndo.redo(); }
    }

    public record Id(String value) {
        public String value() { return value; }
    }

    static final class Fixture {
        final ParameterSource source = new ParameterSource();
        final ModelSource modelSource = new ModelSource(source);
        final Document document = new Document(modelSource);
        final EditMode editMode = new EditMode();
        final Validator validator = new Validator();
        final Helper helper = HelperOwner.INSTANCE;
        final PropertyEditor propertyEditor = new PropertyEditor(modelSource, editMode);
        final ParameterOperation operation = new ParameterOperation(propertyEditor, validator);
        final MainFrame mainFrame = new MainFrame(
            new ParameterPalette(new ParameterPaletteView(operation))
        );
        Fixture() {
            helper.morphTargetEligible = true;
        }
        void bindNormalParameter() {
            final ParameterControllable object = new ParameterControllable();
            object.keyformGrid.parameters.add(source.guid);
            modelSource.objects.add(object);
        }
        void clearBindings() {
            modelSource.objects.clear();
        }
    }
}
