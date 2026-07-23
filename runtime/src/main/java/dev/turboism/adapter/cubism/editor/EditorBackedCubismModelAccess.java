package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.EditorParameterDefinitionWriteSelectorContract;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.Canvas;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.cubism.model.Deformers;
import dev.turboism.sdk.cubism.model.Drawables;
import dev.turboism.sdk.cubism.model.Glues;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.ParameterDefinition;
import dev.turboism.sdk.cubism.model.ParameterType;
import dev.turboism.sdk.cubism.model.Parameters;
import dev.turboism.sdk.cubism.model.Parts;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

/** Generation-bound natural model view over one verified Editor modeling document. */
public final class EditorBackedCubismModelAccess implements CubismModelAccess {

    private final VerifiedMemberResolver resolver;
    private final String sessionIdentity;
    private final EditorParameterCombinedAccess combinedAccess;

    public EditorBackedCubismModelAccess(
        final VerifiedMemberResolver resolver,
        final String sessionIdentity
    ) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.sessionIdentity = requireText(sessionIdentity, "sessionIdentity");
        this.combinedAccess = new EditorParameterCombinedAccess(
            resolver,
            this::requireCurrent,
            this::source
        );
    }

    @Override
    public CubismModel active() {
        final Binding binding = binding();
        return new EditorModel(binding.identity(), binding.model());
    }

    private Binding binding() {
        final Object app = resolver.invokeStatic("cubism.editor-model.app-controller.instance");
        final Object document = app == null ? null : resolver.invoke(
            "cubism.editor-model.app-controller.current-document", app
        );
        if (!resolver.isInstance("cubism.editor-model.modeling-document.class", document)) {
            throw unavailable("The active Cubism document is not a modeling document.");
        }
        final Object source = resolver.invoke(
            "cubism.editor-model.modeling-document.model-source", document
        );
        final Object model = source == null ? null : resolver.invoke(
            "cubism.editor-model.model-source.current-instance", source
        );
        if (!resolver.isInstance("cubism.editor-model.model.class", model)) {
            throw unavailable("No verified active model is available.");
        }
        final Object guid = resolver.invoke("cubism.editor-model.model-source.guid", source);
        final String id = text(resolver.invoke("cubism.editor-model.guid.value", guid));
        return new Binding(sessionIdentity + ":" + id, model);
    }

    private void setParameterValue(
        final String expectedIdentity,
        final Object expectedModel,
        final ParameterId id,
        final float value
    ) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException("value must be finite");
        }
        final Binding currentBinding = binding();
        if (!currentBinding.identity().equals(expectedIdentity)
            || currentBinding.model() != expectedModel) {
            throw new IllegalStateException(
                "Cubism model reference is stale for the active Editor model generation."
            );
        }
        final ParameterBinding binding = parameter(expectedModel, id);
        final float oldValue = number(resolver.invoke(
            "cubism.editor-model.parameter.value", binding.parameter()
        ));
        final Object source = resolver.invoke(
            "cubism.editor-model.parameter.source", binding.parameter()
        );
        final float minimum = number(resolver.invoke(
            "cubism.editor-model.parameter-source.minimum", source
        ));
        final float maximum = number(resolver.invoke(
            "cubism.editor-model.parameter-source.maximum", source
        ));
        final float expectedValue = Math.max(minimum, Math.min(value, maximum));
        if (Float.compare(oldValue, expectedValue) == 0) {
            return;
        }
        final Object app = resolver.invokeStatic("cubism.editor-model.app-controller.instance");
        final Object document = resolver.invoke(
            "cubism.editor-model.app-controller.current-document", app
        );
        final Object parameterSet = resolver.invoke(
            "cubism.editor-model.model.parameter-set", expectedModel
        );
        final Object editMode = resolver.invoke(
            "cubism.editor-model.modeling-document.edit-mode", document
        );
        final Object undo = resolver.invoke(
            "cubism.editor-model.edit-mode.begin", editMode, "Turboism: Set Parameter Value"
        );
        boolean completed = false;
        try {
            final Object parameterUndo = resolver.construct(
                "cubism.editor-model.simple-undo.create",
                "Turboism: Set Parameter Value",
                parameterSet,
                null
            );
            resolver.invoke(
                "cubism.editor-model.undo.add",
                undo,
                parameterUndo,
                Boolean.TRUE
            );
            final Object completePack = resolver.invoke(
                "cubism.editor-model.app-controller.complete-pack", app
            );
            final Object mainFrame = resolver.invoke(
                "cubism.editor-model.app-controller.main-frame", app
            );
            final Object palette = resolver.invoke(
                "cubism.editor-model.main-frame.parameter-palette", mainFrame
            );
            final Object paletteView = resolver.invoke(
                "cubism.editor-model.parameter-palette.view", palette
            );
            final Object operation = resolver.invoke(
                "cubism.editor-model.parameter-palette-view.operation", paletteView
            );
            resolver.invoke(
                "cubism.editor-model.parameter-operation.set-value",
                operation,
                source,
                Float.valueOf(value)
            );
            resolver.invoke(
                "cubism.editor-model.complete-pack.update-parameter",
                completePack,
                Boolean.TRUE
            );
            resolver.invoke(
                "cubism.editor-model.complete-pack.repaint-canvas",
                completePack,
                Boolean.TRUE
            );
            resolver.invoke("cubism.editor-model.modeling-document.mark-dirty", document);
            completed = true;
        } finally {
            resolver.invoke(
                "cubism.editor-model.edit-mode.end",
                editMode,
                Boolean.valueOf(!completed),
                null
            );
        }
        requireCurrent(expectedIdentity, expectedModel);
    }

    private void updateParameterDefinition(
        final String expectedIdentity,
        final Object expectedModel,
        final ParameterId currentId,
        final ParameterDefinition definition
    ) {
        Objects.requireNonNull(definition, "definition");
        if (!resolver.authorizesFeature(
            EditorParameterDefinitionWriteSelectorContract.ADAPTER_SLICE_ID,
            EditorParameterDefinitionWriteSelectorContract.CAPABILITY_ID,
            EditorParameterDefinitionWriteSelectorContract.REQUIRED_ALIASES
        )) {
            throw new UnsupportedOperationException(
                "Parameter definition editing is unavailable without exact verified host evidence."
            );
        }
        requireCurrent(expectedIdentity, expectedModel);
        final ParameterBinding parameterBinding = parameter(expectedModel, currentId);
        final Object source = resolver.invoke(
            "cubism.editor-model.parameter.source",
            parameterBinding.parameter()
        );
        final ParameterDefinition current = definition(source, currentId);
        if (current.equals(definition)) {
            return;
        }
        if (!(definition.minimumValue() < definition.maximumValue())) {
            throw new IllegalArgumentException(
                "Editor parameter definition requires minimum < maximum."
            );
        }

        final Object app = resolver.invokeStatic("cubism.editor-model.app-controller.instance");
        final Object mainFrame = resolver.invoke(
            "cubism.editor-model.app-controller.main-frame",
            app
        );
        final Object palette = resolver.invoke(
            "cubism.editor-model.main-frame.parameter-palette",
            mainFrame
        );
        final Object paletteView = resolver.invoke(
            "cubism.editor-model.parameter-palette.view",
            palette
        );
        final Object operation = resolver.invoke(
            "cubism.editor-model.parameter-palette-view.operation",
            paletteView
        );
        final Object propertyEditor = resolver.invokeStatic(
            "cubism.editor-model.parameter-operation.property-editor",
            operation
        );
        final Object validator = resolver.invokeStatic(
            "cubism.editor-model.parameter-operation.validator",
            operation
        );
        final Object helper = resolver.readStaticField(
            "cubism.editor-model.parameter-helper.instance"
        );

        if (!current.id().equals(definition.id())
            && !booleanValue(resolver.invoke(
                "cubism.editor-model.parameter-validator.valid-id",
                validator,
                definition.id().value()
            ), "Editor parameter ID validation is unavailable.")) {
            throw new IllegalArgumentException(
                "Editor rejected the parameter ID: " + definition.id().value()
            );
        }
        final Object parameterGuid = resolver.invoke(
            "cubism.editor-model.parameter-source.guid",
            source
        );
        if (booleanValue(resolver.invoke(
            "cubism.editor-model.parameter-validator.keys-outside-range",
            validator,
            parameterGuid,
            Float.valueOf(definition.minimumValue()),
            Float.valueOf(definition.maximumValue())
        ), "Editor parameter range validation is unavailable.")) {
            throw new IllegalStateException(
                "The requested range would exclude existing parameter keys."
            );
        }

        final boolean desiredMorphTarget = definition.type() == ParameterType.BLEND_SHAPE;
        final boolean typeChanged = current.type() != definition.type();
        if (typeChanged) {
            if (!booleanValue(resolver.invoke(
                "cubism.editor-model.parameter-validator.supports-type",
                validator,
                Boolean.valueOf(desiredMorphTarget)
            ), "Editor parameter type capability is unavailable.")) {
                throw new IllegalStateException(
                    "The active Editor target does not support the requested parameter type."
                );
            }
            if (hasParameterBindings(source)) {
                throw new IllegalStateException(
                    "Cannot change Blend Shape because the parameter is bound to one or more model objects."
                );
            }
            if (desiredMorphTarget && !booleanValue(resolver.invoke(
                "cubism.editor-model.parameter-helper.morph-target-eligible",
                helper,
                source
            ), "Editor morph-target eligibility is unavailable.")) {
                throw new IllegalStateException(
                    "The parameter's effect-group role blocks Blend Shape conversion."
                );
            }
            if (booleanValue(resolver.invoke(
                "cubism.editor-model.parameter-validator.reject-type-change",
                validator,
                source,
                Boolean.valueOf(desiredMorphTarget),
                Boolean.FALSE
            ), "Editor parameter type validation is unavailable.")) {
                throw new IllegalStateException(
                    "The Editor rejected the requested parameter type change."
                );
            }
        }
        if (current.repeat() != definition.repeat()) {
            if (!booleanValue(resolver.invoke(
                "cubism.editor-model.parameter-validator.allow-repeat",
                validator,
                source,
                Boolean.valueOf(definition.repeat())
            ), "Editor parameter repeat validation is unavailable.")) {
                throw new IllegalStateException(
                    "The Editor rejected the requested repeat setting."
                );
            }
        }

        if (booleanValue(resolver.invoke(
            "cubism.editor-model.parameter-validator.default-change-affects-morph-target",
            validator,
            source,
            Float.valueOf(definition.defaultValue())
        ), "Editor morph-target default validation is unavailable.")) {
            throw new IllegalStateException(
                "Changing this default would affect existing morph-target keyforms."
            );
        }

        final Object refreshCallback = resolver.construct(
            "cubism.editor-model.parameter-refresh-callback.create",
            operation
        );
        final Object updated = resolver.invoke(
            "cubism.editor-model.parameter-property-editor.update-definition",
            propertyEditor,
            source,
            definition.name(),
            Float.valueOf(definition.minimumValue()),
            Float.valueOf(definition.maximumValue()),
            Float.valueOf(definition.defaultValue()),
            definition.id().value(),
            Boolean.valueOf(definition.repeat()),
            Boolean.valueOf(typeChanged),
            Boolean.valueOf(desiredMorphTarget),
            refreshCallback
        );
        if (!booleanValue(updated, "Editor parameter definition update is unavailable.")) {
            throw new IllegalStateException("The Editor rejected the parameter definition update.");
        }
        resolver.invoke(
            "cubism.editor-model.parameter-property-editor.rebuild-keep-value",
            propertyEditor
        );
        refreshDefinitionUi(operation);
        requireCurrent(expectedIdentity, expectedModel);
    }

    private Object source(final Object model, final ParameterId id) {
        return resolver.invoke(
            "cubism.editor-model.parameter.source",
            parameter(model, id).parameter()
        );
    }

    private ParameterDefinition definition(final Object source, final ParameterId id) {
        final Object rawName = resolver.invoke(
            "cubism.editor-model.parameter-source.name",
            source
        );
        if (!(rawName instanceof String name) || name.isBlank()) {
            throw unavailable("Editor parameter name is invalid.");
        }
        final boolean morphTarget = booleanValue(resolver.invoke(
            "cubism.editor-model.parameter-source.morph-target",
            source
        ), "Editor parameter type is invalid.");
        return new ParameterDefinition(
            id,
            name,
            number(resolver.invoke("cubism.editor-model.parameter-source.minimum", source)),
            number(resolver.invoke("cubism.editor-model.parameter-source.default", source)),
            number(resolver.invoke("cubism.editor-model.parameter-source.maximum", source)),
            morphTarget ? ParameterType.BLEND_SHAPE : ParameterType.NORMAL,
            booleanValue(resolver.invoke(
                "cubism.editor-model.parameter-source.repeat",
                source
            ), "Editor parameter repeat flag is invalid.")
        );
    }

    private boolean hasParameterBindings(final Object source) {
        final Object modelSource = resolver.invoke(
            "cubism.editor-model.parameter-source.model-source",
            source
        );
        final Object parameterGuid = resolver.invoke(
            "cubism.editor-model.parameter-source.guid",
            source
        );
        final Object rawObjects = resolver.invoke(
            "cubism.editor-model.model-source.all-objects",
            modelSource
        );
        if (!(rawObjects instanceof Iterable<?> objects)) {
            throw unavailable("Editor parameter binding collection is unavailable.");
        }
        for (Object object : objects) {
            if (!resolver.isInstance("cubism.editor-model.parameter-controllable.class", object)) {
                throw unavailable("Editor parameter binding object is invalid.");
            }
            final Object keyformGrid = resolver.invoke(
                "cubism.editor-model.parameter-controllable.keyform-grid",
                object
            );
            if (booleanValue(resolver.invoke(
                "cubism.editor-model.keyform-grid.contains-parameter",
                keyformGrid,
                parameterGuid
            ), "Editor keyform binding state is unavailable.")) {
                return true;
            }
            final Object morphTargetSet = resolver.invoke(
                "cubism.editor-model.parameter-controllable.morph-target-set",
                object
            );
            if (booleanValue(resolver.invoke(
                "cubism.editor-model.morph-target-set.contains-parameter",
                morphTargetSet,
                parameterGuid
            ), "Editor morph-target binding state is unavailable.")) {
                return true;
            }
        }
        return false;
    }

    private void refreshDefinitionUi(final Object operation) {
        resolver.invoke(
            "cubism.editor-model.parameter-operation.refresh",
            operation,
            Boolean.TRUE
        );
    }

    private void requireCurrent(final String expectedIdentity, final Object expectedModel) {
        final Binding current = binding();
        if (!current.identity().equals(expectedIdentity) || current.model() != expectedModel) {
            throw new IllegalStateException(
                "Cubism model reference is stale for the active Editor model generation."
            );
        }
    }

    private List<ParameterBinding> parameters(final Object model) {
        final Object set = resolver.invoke("cubism.editor-model.model.parameter-set", model);
        final Object raw = resolver.invoke("cubism.editor-model.parameter-set.parameters", set);
        if (!(raw instanceof Iterable<?> iterable)) {
            throw unavailable("Editor parameter collection is unavailable.");
        }
        final List<ParameterBinding> values = new ArrayList<>();
        final java.util.HashSet<String> ids = new java.util.HashSet<>();
        for (Object parameter : iterable) {
            final Object rawId = resolver.invoke("cubism.editor-model.parameter.id", parameter);
            final String id = text(resolver.invoke("cubism.editor-model.id.value", rawId));
            if (!ids.add(id)) {
                throw unavailable("Editor parameter identifiers are not unique.");
            }
            values.add(new ParameterBinding(id, parameter));
        }
        return List.copyOf(values);
    }

    private ParameterBinding parameter(final Object model, final ParameterId id) {
        return parameters(model).stream()
            .filter(value -> value.id().equals(id.value()))
            .findFirst()
            .orElseThrow(() -> new NoSuchElementException(
                "Cubism parameter is absent: " + id.value()
            ));
    }

    private static String text(final Object value) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw unavailable("Verified Editor identity is unavailable.");
        }
        return text;
    }

    private static IllegalStateException unavailable(final String message) {
        return new IllegalStateException(message);
    }

    private final class EditorModel implements CubismModel {
        private final String identity;
        private final Object model;
        private EditorModel(final String identity, final Object model) {
            this.identity = identity;
            this.model = model;
        }
        private void current() { requireCurrent(identity, model); }
        @Override public ModelId id() { current(); return new ModelId(identity.substring(identity.indexOf(':') + 1)); }
        @Override public Parameters parameters() { current(); return new EditorParameters(identity, model); }
        @Override public Canvas canvas() { throw new UnsupportedOperationException("Editor canvas projection is not installed."); }
        @Override public Parts parts() { throw new UnsupportedOperationException("Editor Part projection is not installed."); }
        @Override public Drawables drawables() { throw new UnsupportedOperationException("Editor Drawable projection is not installed."); }
        @Override public Deformers deformers() { throw new UnsupportedOperationException("Editor Deformer projection is not installed."); }
        @Override public Glues glues() { throw new UnsupportedOperationException("Editor Glue projection is not installed."); }
        @Override public void update() { throw new UnsupportedOperationException("Editor model update is not installed."); }
    }

    private final class EditorParameters implements Parameters {
        private final String identity;
        private final Object model;
        private EditorParameters(final String identity, final Object model) {
            this.identity = identity;
            this.model = model;
        }
        private void current() { requireCurrent(identity, model); }
        @Override public List<Parameter> all() {
            current();
            return parameters(model).stream()
                .map(value -> (Parameter) new EditorParameter(identity, model, new ParameterId(value.id())))
                .toList();
        }
        @Override public Parameter find(final ParameterId id) {
            current();
            parameter(model, Objects.requireNonNull(id, "id"));
            return new EditorParameter(identity, model, id);
        }
    }

    private final class EditorParameter implements Parameter {
        private final String identity;
        private final Object model;
        private final ParameterId id;
        private EditorParameter(final String identity, final Object model, final ParameterId id) {
            this.identity = identity;
            this.model = model;
            this.id = id;
        }
        private ParameterBinding current() {
            requireCurrent(identity, model);
            return parameter(model, id);
        }
        private Object source() {
            return resolver.invoke("cubism.editor-model.parameter.source", current().parameter());
        }
        @Override public ParameterId id() { current(); return id; }
        @Override public Optional<String> name() {
            final Object value = resolver.invoke("cubism.editor-model.parameter-source.name", source());
            if (value == null) {
                return Optional.empty();
            }
            if (!(value instanceof String text)) {
                throw unavailable("Editor parameter name is invalid.");
            }
            return text.isBlank() ? Optional.empty() : Optional.of(text);
        }
        @Override public ParameterType type() {
            return booleanValue(resolver.invoke(
                "cubism.editor-model.parameter-source.morph-target", source()
            ), "Editor parameter type is invalid.")
                ? ParameterType.BLEND_SHAPE
                : ParameterType.NORMAL;
        }
        @Override public Optional<Boolean> repeat() {
            return Optional.of(booleanValue(resolver.invoke(
                "cubism.editor-model.parameter-source.repeat", source()
            ), "Editor parameter repeat flag is invalid."));
        }
        @Override public Optional<Boolean> combined() {
            return Optional.of(booleanValue(resolver.invoke(
                "cubism.editor-model.parameter-source.combined", source()
            ), "Editor parameter combined flag is invalid."));
        }
        @Override public Optional<ParameterId> combinedWith() {
            return combinedAccess.partner(identity, model, id);
        }
        @Override public void combineWith(final ParameterId partnerId) {
            combinedAccess.combine(identity, model, id, partnerId);
        }
        @Override public void uncombine() {
            combinedAccess.uncombine(identity, model, id);
        }
        @Override public float getValue() { return number(resolver.invoke("cubism.editor-model.parameter.value", current().parameter())); }
        @Override public float getMinimumValue() { return number(resolver.invoke("cubism.editor-model.parameter-source.minimum", source())); }
        @Override public float getMaximumValue() { return number(resolver.invoke("cubism.editor-model.parameter-source.maximum", source())); }
        @Override public float getDefaultValue() { return number(resolver.invoke("cubism.editor-model.parameter-source.default", source())); }
        @Override public void setValue(final float value) {
            setParameterValue(identity, model, id, value);
        }
        @Override public void updateDefinition(final ParameterDefinition definition) {
            updateParameterDefinition(identity, model, id, definition);
        }
    }

    private static float number(final Object value) {
        if (!(value instanceof Float number) || !Float.isFinite(number)) {
            throw unavailable("Editor parameter value is invalid.");
        }
        return number;
    }

    private static boolean booleanValue(final Object value, final String message) {
        if (!(value instanceof Boolean flag)) {
            throw unavailable(message);
        }
        return flag;
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    private record Binding(String identity, Object model) { }
    private record ParameterBinding(String id, Object parameter) { }
}
