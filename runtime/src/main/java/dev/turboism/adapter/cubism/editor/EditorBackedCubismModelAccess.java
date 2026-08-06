package dev.turboism.adapter.cubism.editor;

import dev.turboism.adapter.cubism.NativeLabelColorAuthoring;
import dev.turboism.adapter.cubism.NativeLabelColorTarget;
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
import dev.turboism.sdk.cubism.model.ParameterBindingBatchOperations;
import dev.turboism.sdk.cubism.model.ParameterBindingOperations;
import dev.turboism.sdk.cubism.model.ParameterDefinition;
import dev.turboism.sdk.cubism.model.ParameterGroups;
import dev.turboism.sdk.cubism.model.ParameterType;
import dev.turboism.sdk.cubism.model.Parameters;
import dev.turboism.sdk.cubism.model.Parts;
import dev.turboism.sdk.cubism.model.RotationDeformers;
import dev.turboism.sdk.cubism.model.WarpDeformers;
import dev.turboism.sdk.cubism.model.FloatSequence;
import dev.turboism.sdk.cubism.model.ParameterDefinitions;
import dev.turboism.sdk.ui.appearance.NativeLabelColor;
import dev.turboism.sdk.ui.appearance.NativeLabelColorState;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

/** Generation-bound natural model view over one verified Editor modeling document. */
public final class EditorBackedCubismModelAccess implements CubismModelAccess,
    NativeLabelColorAuthoring {

    private final VerifiedMemberResolver resolver;
    private final String sessionIdentity;
    private final EditorParameterCombinedAccess combinedAccess;
    private final EditorParameterGroupsAccess parameterGroupsAccess;
    private final EditorDefaultKeyformLockAccess defaultKeyformLockAccess;
    private final EditorModelEditLevelAccess editLevelAccess;
    private final EditorPartOpacityAccess partOpacityAccess;
    private final EditorPartStructureAccess partStructureAccess;
    private final EditorParameterStructureAccess parameterStructureAccess;
    private final EditorMorphTargetAccess morphTargetAccess;
    private final EditorModelProfileAccess modelProfileAccess;
    private final EditorObjectReadAccess objectReadAccess;
    private final EditorModelStatisticsAccess statisticsAccess;
    private final Object generationLock = new Object();
    private Object activeDocument;
    private Object activeSource;
    private Object activeModel;
    private long generation;
    private final EditorNativeControlAppearanceAccess nativeControlAppearanceAccess;

    private final EditorDocumentReadAccess documentReadAccess;
    private final dev.turboism.adapter.cubism.core.CoreEvaluatedJoin evaluatedJoin;

    public EditorBackedCubismModelAccess(
        final VerifiedMemberResolver resolver,
        final String sessionIdentity
    ) {
        this(resolver, sessionIdentity, null);
    }

    public EditorBackedCubismModelAccess(
        final VerifiedMemberResolver resolver,
        final String sessionIdentity,
        final dev.turboism.adapter.cubism.core.CoreEvaluatedJoin evaluatedJoin
    ) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.sessionIdentity = requireText(sessionIdentity, "sessionIdentity");
        this.combinedAccess = new EditorParameterCombinedAccess(
            resolver,
            this::requireCurrent,
            this::source
        );
        this.parameterStructureAccess = new EditorParameterStructureAccess(
            resolver,
            this::requireCurrent
        );
        this.parameterGroupsAccess = new EditorParameterGroupsAccess(
            resolver,
            this::requireCurrent,
            this.parameterStructureAccess
        );
        this.defaultKeyformLockAccess = new EditorDefaultKeyformLockAccess(
            resolver,
            this::requireCurrent
        );
        this.editLevelAccess = new EditorModelEditLevelAccess(
            resolver,
            this::requireCurrent
        );
        this.partStructureAccess = new EditorPartStructureAccess(
            resolver,
            this::requireCurrent
        );
        this.morphTargetAccess = new EditorMorphTargetAccess(
            resolver,
            this::requireCurrent,
            evaluatedJoin
        );

        this.documentReadAccess = new EditorDocumentReadAccess(
            resolver,
            this::requireCurrent
        );
        this.modelProfileAccess = new EditorModelProfileAccess(
            resolver,
            this::requireCurrent
        );
        this.partOpacityAccess = new EditorPartOpacityAccess(
            resolver,
            this::requireCurrent,
            this.partStructureAccess,
            this.morphTargetAccess
        );
        this.objectReadAccess = new EditorObjectReadAccess(
            resolver,
            this::requireCurrent,
            this.morphTargetAccess,
            this.evaluatedJoin
        );
        this.statisticsAccess = new EditorModelStatisticsAccess(
            resolver,
            this::requireCurrent
        );
        this.nativeControlAppearanceAccess = new EditorNativeControlAppearanceAccess(
            resolver,
            () -> binding()
        );

        this.evaluatedJoin = evaluatedJoin;
    }

    @Override
    public CubismModel active() {
        final Binding binding = binding();
        return new EditorModel(
            binding.identity(), binding.modelId(), binding.source(), binding.model()
        );
    }

    private Binding binding() {
        final Object app = resolver.invokeStatic("cubism.editor-model.app-controller.instance");
        final Object document = app == null ? null : resolver.invoke(
            "cubism.editor-model.app-controller.current-document", app
        );
        if (!resolver.isInstance("cubism.editor-model.modeling-document.class", document)) {
            final String actualClass = document == null ? "null" : document.getClass().getName();
            throw unavailable(
                "The active Cubism document is not a modeling document. [DEBUG-pb-admission] activeDocumentClass=" + actualClass
                    + " thread=" + Thread.currentThread().getName()
            );
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
        return new Binding(
            bindingIdentity(id, document, source, model), id, document, source, model
        );
    }

    private String bindingIdentity(
        final String modelId,
        final Object document,
        final Object source,
        final Object model
    ) {
        synchronized (generationLock) {
            if (document != activeDocument || source != activeSource || model != activeModel) {
                activeDocument = document;
                activeSource = source;
                activeModel = model;
                generation = Math.incrementExact(generation);
            }
            return sessionIdentity + ":" + modelId + ":" + generation;
        }
    }

    private void setModelName(
        final String expectedIdentity,
        final Object expectedSource,
        final Object expectedModel,
        final String name
    ) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (!resolver.authorizesFeature(
            dev.turboism.mapping.verification.EditorModelNameWriteSelectorContract.ADAPTER_SLICE_ID,
            dev.turboism.mapping.verification.EditorModelNameWriteSelectorContract.CAPABILITY_ID,
            dev.turboism.mapping.verification.EditorModelNameWriteSelectorContract.REQUIRED_ALIASES
        )) {
            throw new UnsupportedOperationException(
                "Model-name editing is unavailable without exact verified host evidence."
            );
        }
        requireCurrent(expectedIdentity, expectedModel);
        final Object currentValue = resolver.invoke("cubism.editor-model.model-source.name", expectedSource);
        if (name.equals(currentValue)) {
            return;
        }
        final Object app = resolver.invokeStatic("cubism.editor-model.app-controller.instance");
        final Object document = resolver.invoke(
            "cubism.editor-model.app-controller.current-document", app
        );
        final Object editMode = resolver.invoke(
            "cubism.editor-model.modeling-document.edit-mode", document
        );
        final Object undo = resolver.invoke(
            "cubism.editor-model.edit-mode.begin", editMode, "Turboism: Set Model Name"
        );
        boolean completed = false;
        try {
            final Object modelUndo = resolver.construct(
                "cubism.editor-model.simple-undo.create",
                "Turboism: Set Model Name",
                expectedSource,
                null
            );
            final Object accepted = resolver.invoke(
                "cubism.editor-model.undo.add",
                undo,
                modelUndo,
                Boolean.TRUE
            );
            if (!(accepted instanceof Boolean value) || !value) {
                throw new IllegalStateException("Cubism rejected the model-name Undo entry.");
            }
            resolver.invoke("cubism.editor-model.model-source.set-name", expectedSource, name);
            resolver.invoke("cubism.editor-model.model-source.update-instances", expectedSource);
            final Object completePack = resolver.invoke(
                "cubism.editor-model.app-controller.complete-pack", app
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

    private dev.turboism.sdk.cubism.core.MocInfo mocInfo() {
        if (evaluatedJoin == null) {
            throw new IllegalStateException(
                "Cubism MOC metadata is unavailable: no Core evaluated join is installed."
            );
        }
        return evaluatedJoin.mocInfo();
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

    private ParameterDefinitions parameterDefinitions(final String identity, final Object model) {
        requireCurrent(identity, model);
        return new ParameterDefinitions() {
            @Override public List<ParameterDefinition> all() {
                requireCurrent(identity, model);
                return parameters(model).stream()
                    .map(value -> definition(
                        resolver.invoke("cubism.editor-model.parameter.source", value.parameter()),
                        new ParameterId(value.id())
                    ))
                    .toList();
            }

            @Override public ParameterDefinition find(final ParameterId id) {
                Objects.requireNonNull(id, "id");
                requireCurrent(identity, model);
                final ParameterBinding value = parameter(model, id);
                return definition(
                    resolver.invoke("cubism.editor-model.parameter.source", value.parameter()),
                    id
                );
            }
        };
    }

    private int parameterIndex(final Object model, final ParameterId id) {
        final List<ParameterBinding> values = parameters(model);
        for (int index = 0; index < values.size(); index++) {
            if (values.get(index).id().equals(id.value())) return index;
        }
        throw new NoSuchElementException("Cubism parameter is absent: " + id.value());
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

    @Override
    public NativeLabelColorState readNativeLabelColor(final NativeLabelColorTarget target) {
        return nativeControlAppearanceAccess.readNativeLabelColor(target);
    }

    @Override
    public void setNativeLabelColor(
        final NativeLabelColorTarget target,
        final NativeLabelColor color
    ) {
        nativeControlAppearanceAccess.setNativeLabelColor(target, color);
    }


    private final class EditorModel implements CubismModel {
        private final String identity;
        private final String modelId;
        private final Object source;
        private final Object model;
        private EditorModel(
            final String identity,
            final String modelId,
            final Object source,
            final Object model
        ) {
            this.identity = identity;
            this.modelId = modelId;
            this.source = source;
            this.model = model;
        }
        private void current() { requireCurrent(identity, model); }
        @Override public ModelId id() { current(); return new ModelId(modelId); }
        @Override public String name() {
            current();
            final Object value = resolver.invoke("cubism.editor-model.model-source.name", source);
            if (value == null) return modelId;
            if (!(value instanceof String text)) {
                throw unavailable("Editor model name is invalid.");
            }
            return text.isBlank() ? modelId : text;
        }

        @Override public void setName(final String name) {
            modelProfileAccess.setName(identity, source, model, name);
        }

        @Override public dev.turboism.sdk.cubism.model.ModelProfile profile() {
            return modelProfileAccess.profile(identity, source, model);
        }
        @Override public ParameterDefinitions parameterDefinitions() {
            return EditorBackedCubismModelAccess.this.parameterDefinitions(identity, model);
        }
        @Override public dev.turboism.sdk.cubism.model.ModelStatistics statistics() {
            current();
            return statisticsAccess.statistics(identity, source, model);
        }
        @Override public boolean defaultKeyformLocked() {
            return defaultKeyformLockAccess.locked(identity, source, model);
        }
        @Override public void setDefaultKeyformLocked(final boolean locked) {
            defaultKeyformLockAccess.setLocked(identity, source, model, locked);
        }
        @Override public dev.turboism.sdk.cubism.model.ModelEditLevel editLevel() {
            return editLevelAccess.level(identity, model);
        }
        @Override public void setEditLevel(
            final dev.turboism.sdk.cubism.model.ModelEditLevel level
        ) {
            editLevelAccess.setLevel(identity, model, level);
        }

        @Override public void setName(final String name) {
            setModelName(identity, source, model, name);
        }

        @Override public dev.turboism.sdk.cubism.core.MocInfo mocInfo() {
            current();
            return EditorBackedCubismModelAccess.this.mocInfo();
        }

        @Override public dev.turboism.sdk.cubism.model.PhysicsSettings physicsSettings() {
            return documentReadAccess.physicsSettings(identity, source, model);
        }

        @Override public dev.turboism.sdk.cubism.model.AutoYure autoYure() {
            return documentReadAccess.autoYure(identity, source, model);
        }

        @Override public List<dev.turboism.sdk.cubism.model.AnimationDocument> animationDocuments() {
            return documentReadAccess.animationDocuments(identity, source, model);
        }
        @Override public Parameters parameters() { current(); return new EditorParameters(identity, model); }
        @Override public ParameterGroups parameterGroups() {
            current();
            return parameterGroupsAccess.groups(identity, source, model);
        }
        @Override public ParameterBindingOperations parameterBindings(final ParameterId parameterId) {
            current();
            parameter(model, Objects.requireNonNull(parameterId, "parameterId"));
            return new EditorParameterBindingAccess(
                resolver,
                identity,
                source,
                model,
                parameterId,
                EditorBackedCubismModelAccess.this::requireCurrent,
                objectReadAccess::parameterBindings,
                objectReadAccess::bindingTargetSource,
                EditorBackedCubismModelAccess.this::source
            );
        }
        @Override public ParameterBindingBatchOperations parameterBindingBatch() {
            current();
            return new EditorParameterBindingBatchAccess(
                resolver,
                identity,
                source,
                model,
                EditorBackedCubismModelAccess.this::requireCurrent,
                objectReadAccess::bindingTargetSource,
                EditorBackedCubismModelAccess.this::source
            );
        }
        @Override public Canvas canvas() { return modelProfileAccess.canvas(identity, source, model); }
        @Override public Parts parts() {
            current();
            return partOpacityAccess.parts(identity, source, model);
        }
        @Override public Drawables drawables() {
            current();
            return objectReadAccess.drawables(identity, source, model);
        }
        @Override public Deformers deformers() {
            current();
            return objectReadAccess.deformers(identity, source, model);
        }
        @Override public WarpDeformers warpDeformers() {
            current();
            return objectReadAccess.warpDeformers(identity, source, model);
        }
        @Override public RotationDeformers rotationDeformers() {
            current();
            return objectReadAccess.rotationDeformers(identity, source, model);
        }
        @Override public Glues glues() { current(); return objectReadAccess.glues(identity, source, model); }
        @Override public void update() {
            current();
            resolver.invoke("cubism.editor-model.model-source.update-instances", source);
            current();
        }
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

        @Override public Parameter create(final ParameterDefinition definition) {
            current();
            return create(definition, java.util.Optional.empty());
        }

        @Override public Parameter create(
            final ParameterDefinition definition,
            final java.util.Optional<dev.turboism.sdk.cubism.id.ParameterGroupId> folderId
        ) {
            current();
            final ParameterId created = parameterStructureAccess.create(
                identity, activeSource, model, definition, folderId);
            return new EditorParameter(identity, model, created);
        }

        @Override public Parameter copy(final ParameterId id) {
            current();
            final ParameterId copied = parameterStructureAccess.copy(
                identity, activeSource, model, id);
            return new EditorParameter(identity, model, copied);
        }

        @Override public void remove(final ParameterId id) {
            current();
            parameterStructureAccess.remove(identity, activeSource, model, id);
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
        @Override public int index() { current(); return parameterIndex(model, id); }
        @Override public FloatSequence keyValues() { current(); return Parameter.super.keyValues(); }
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
        @Override public List<dev.turboism.sdk.cubism.model.ParameterBinding> getParameterBindings() {
            current();
            return objectReadAccess.parameterBindings(identity, binding().source(), model, id);
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

    record Binding(
        String identity,
        String modelId,
        Object document,
        Object source,
        Object model
    ) { }
    private record ParameterBinding(String id, Object parameter) { }
}
