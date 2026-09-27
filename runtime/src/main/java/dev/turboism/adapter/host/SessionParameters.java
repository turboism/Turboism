package dev.turboism.adapter.host;
import dev.turboism.sdk.cubism.clipmask.ClipMaskReplacement;
import dev.turboism.adapter.cubism.model.ModelObjectProviderUnavailableException;
import dev.turboism.adapter.cubism.model.RuntimeModelObjectCreateProvider;
import dev.turboism.adapter.cubism.editor.transaction.RuntimeAuthoringTransactionProvider;
import dev.turboism.adapter.cubism.edit.RuntimeEditSessionProvider;
import dev.turboism.adapter.cubism.warp.RuntimeWarpMirrorProvider;
import dev.turboism.sdk.cubism.mirror.WarpMirrorBlocker;
import dev.turboism.sdk.cubism.mirror.WarpMirrorBlockerCode;
import dev.turboism.sdk.cubism.mirror.WarpMirrorRequest;
import dev.turboism.sdk.cubism.mirror.WarpMirrorResult;
import dev.turboism.sdk.cubism.mirror.WarpMirrorService;

import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.cubism.model.Canvas;
import dev.turboism.sdk.cubism.model.Color;
import dev.turboism.sdk.cubism.model.BlendMode;
import dev.turboism.sdk.cubism.model.Deformer;
import dev.turboism.sdk.cubism.model.Deformers;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.Drawables;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.RotationDeformer;
import dev.turboism.sdk.cubism.model.RotationDeformerForm;
import dev.turboism.sdk.cubism.model.RotationDeformers;
import dev.turboism.sdk.cubism.model.WarpDeformer;
import dev.turboism.sdk.cubism.model.WarpDeformers;
import dev.turboism.sdk.cubism.model.WarpGrid;
import dev.turboism.sdk.cubism.model.Glue;
import dev.turboism.sdk.cubism.model.GlueId;
import dev.turboism.sdk.cubism.model.Glues;
import dev.turboism.sdk.cubism.model.ModelObjectCreateRequest;
import dev.turboism.sdk.cubism.model.ModelObjectReference;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.ParameterGroup;
import dev.turboism.sdk.cubism.model.ParameterGroups;
import dev.turboism.sdk.cubism.model.Parameters;
import dev.turboism.sdk.cubism.model.Part;
import dev.turboism.sdk.cubism.model.PartId;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOptions;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionResult;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionService;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionWork;
import dev.turboism.sdk.cubism.model.Parts;
import dev.turboism.adapter.cubism.NativeLabelColorAuthoring;
import dev.turboism.adapter.cubism.NativeLabelColorTarget;
import dev.turboism.sdk.ui.appearance.model.DeformerAppearance;
import dev.turboism.sdk.ui.appearance.model.DrawableAppearance;
import dev.turboism.sdk.ui.appearance.model.ParameterAppearance;
import dev.turboism.sdk.ui.appearance.model.ParameterGroupAppearance;
import dev.turboism.sdk.ui.appearance.model.PartAppearance;
import dev.turboism.sdk.ui.appearance.NativeLabelColor;
import dev.turboism.sdk.ui.appearance.NativeLabelColorState;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;


    final class SessionParameterDefinitions
        implements dev.turboism.sdk.cubism.model.ParameterDefinitions {
    private final DynamicCubismModelAccess host;
    final long generation;
    final dev.turboism.sdk.cubism.model.ParameterDefinitions delegate;
    SessionParameterDefinitions(
        final DynamicCubismModelAccess host,
        final long generation,
        final dev.turboism.sdk.cubism.model.ParameterDefinitions delegate
    ) {
        this.host = Objects.requireNonNull(host, "host");
        this.generation = generation;
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }
        @Override public List<dev.turboism.sdk.cubism.model.ParameterDefinition> all() {
            return host.guarded(generation, delegate::all);
        }
        @Override public dev.turboism.sdk.cubism.model.ParameterDefinition find(
            final dev.turboism.sdk.cubism.id.ParameterId id
        ) {
            return host.guarded(generation, () -> delegate.find(id));
        }
    }

    final class SessionParameters implements Parameters {
    private final DynamicCubismModelAccess host;
    final long generation;
    final long modelGeneration;
    final ModelId modelId;
    final Parameters delegate;

    SessionParameters(
        final DynamicCubismModelAccess host,
        final long generation,
        final long modelGeneration,
        final ModelId modelId,
        final Parameters delegate
    ) {
        this.host = Objects.requireNonNull(host, "host");
        this.generation = generation;
        this.modelGeneration = modelGeneration;
        this.modelId = Objects.requireNonNull(modelId, "modelId");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

        @Override
        public List<Parameter> all() {
            return host.guarded(generation, () -> delegate.all().stream()
                .map(value -> (Parameter) new SessionParameter(
                    host, generation, modelGeneration, modelId, value
                ))
                .toList());
        }

        @Override
        public Parameter find(final dev.turboism.sdk.cubism.id.ParameterId id) {
            return host.guarded(
                generation,
                () -> new SessionParameter(
                    host, generation, modelGeneration, modelId, delegate.find(id)
                )
            );
        }

        @Override public Parameter create(
            final dev.turboism.sdk.cubism.model.ParameterDefinition definition
        ) {
            return host.guarded(
                generation,
                () -> new SessionParameter(
                    host, generation, modelGeneration, modelId, delegate.create(definition)
                )
            );
        }

        @Override public Parameter create(
            final dev.turboism.sdk.cubism.model.ParameterDefinition definition,
            final java.util.Optional<dev.turboism.sdk.cubism.id.ParameterGroupId> folderId
        ) {
            return host.guarded(
                generation,
                () -> new SessionParameter(
                    host, generation, modelGeneration, modelId, delegate.create(definition, folderId)
                )
            );
        }

        @Override public Parameter copy(final dev.turboism.sdk.cubism.id.ParameterId id) {
            return host.guarded(
                generation,
                () -> new SessionParameter(
                    host, generation, modelGeneration, modelId, delegate.copy(id)
                )
            );
        }

        @Override public void remove(final dev.turboism.sdk.cubism.id.ParameterId id) {
            host.guardedVoid(generation, () -> delegate.remove(id));
        }

        @Override public java.util.Optional<Parameter> findById(
            final dev.turboism.sdk.cubism.id.ParameterId id
        ) {
            return host.guarded(
                generation,
                () -> delegate.findById(Objects.requireNonNull(id, "id"))
                    .map(value -> (Parameter) new SessionParameter(
                        host, generation, modelGeneration, modelId, value
                    ))
            );
        }

        @Override public java.util.Optional<Parameter> findById(final String id) {
            return findById(new dev.turboism.sdk.cubism.id.ParameterId(
                Objects.requireNonNull(id, "id")
            ));
        }

        @Override public List<Parameter> findByName(final String name) {
            Objects.requireNonNull(name, "name");
            return filter(parameter -> parameter.name().filter(name::equals).isPresent());
        }

        @Override public List<Parameter> search(final String text) {
            Objects.requireNonNull(text, "text");
            final String query = text.toLowerCase(java.util.Locale.ROOT);
            return filter(parameter ->
                parameter.id().value().toLowerCase(java.util.Locale.ROOT).contains(query)
                    || parameter.name()
                        .map(value -> value.toLowerCase(java.util.Locale.ROOT).contains(query))
                        .orElse(false)
            );
        }

        @Override public List<Parameter> filter(
            final java.util.function.Predicate<Parameter> predicate
        ) {
            Objects.requireNonNull(predicate, "predicate");
            return host.guarded(generation, () -> all().stream().filter(predicate).toList());
        }

        @Override public List<Parameter> createMany(
            final List<dev.turboism.sdk.cubism.model.ParameterDefinition> definitions
        ) {
            return createMany(definitions, java.util.Optional.empty());
        }

        @Override public List<Parameter> createMany(
            final List<dev.turboism.sdk.cubism.model.ParameterDefinition> definitions,
            final java.util.Optional<dev.turboism.sdk.cubism.id.ParameterGroupId> folderId
        ) {
            return host.guarded(
                generation,
                () -> delegate.createMany(definitions, folderId).stream()
                    .map(value -> (Parameter) new SessionParameter(
                        host, generation, modelGeneration, modelId, value
                    ))
                    .toList()
            );
        }

        @Override public void removeMany(final List<dev.turboism.sdk.cubism.id.ParameterId> ids) {
            host.guardedVoid(generation, () -> delegate.removeMany(ids));
        }
    }

    final class SessionParameterGroups implements ParameterGroups {
    private final DynamicCubismModelAccess host;
    final long generation;
    final long modelGeneration;
    final ModelId modelId;
    final ParameterGroups delegate;

    SessionParameterGroups(
        final DynamicCubismModelAccess host,
        final long generation,
        final long modelGeneration,
        final ModelId modelId,
        final ParameterGroups delegate
    ) {
        this.host = Objects.requireNonNull(host, "host");
        this.generation = generation;
        this.modelGeneration = modelGeneration;
        this.modelId = Objects.requireNonNull(modelId, "modelId");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }
        @Override public List<ParameterGroup> all() {
            return host.guarded(generation, () -> delegate.all().stream()
                .map(value -> (ParameterGroup) new SessionParameterGroup(
                    host, generation, modelGeneration, modelId, value
                ))
                .toList());
        }

        @Override public ParameterGroup root() {
            return host.guarded(
                generation,
                () -> new SessionParameterGroup(
                    host, generation, modelGeneration, modelId, delegate.root()
                )
            );
        }

        @Override public ParameterGroup find(
            final dev.turboism.sdk.cubism.id.ParameterGroupId id
        ) {
            return host.guarded(
                generation,
                () -> new SessionParameterGroup(
                    host, generation, modelGeneration, modelId, delegate.find(id)
                )
            );
        }

        @Override public ParameterGroup addGroup(final String name) {
            return host.guarded(
                generation,
                () -> new SessionParameterGroup(
                    host, generation, modelGeneration, modelId, delegate.addGroup(name)
                )
            );
        }

        @Override public void removeGroup(
            final dev.turboism.sdk.cubism.id.ParameterGroupId id
        ) {
            host.guardedVoid(generation, () -> delegate.removeGroup(id));
        }

        @Override public void moveParameter(
            final dev.turboism.sdk.cubism.id.ParameterId parameterId,
            final dev.turboism.sdk.cubism.id.ParameterGroupId targetGroupId
        ) {
            host.guardedVoid(generation, () -> delegate.moveParameter(parameterId, targetGroupId));
        }
    }

    final class SessionParameterGroup implements ParameterGroup {
    private final DynamicCubismModelAccess host;
    final long generation;
    final long modelGeneration;
    final ModelId modelId;
    final ParameterGroup delegate;

    SessionParameterGroup(
        final DynamicCubismModelAccess host,
        final long generation,
        final long modelGeneration,
        final ModelId modelId,
        final ParameterGroup delegate
    ) {
        this.host = Objects.requireNonNull(host, "host");
        this.generation = generation;
        this.modelGeneration = modelGeneration;
        this.modelId = Objects.requireNonNull(modelId, "modelId");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }
        @Override public dev.turboism.sdk.cubism.id.ParameterGroupId id() {
            return host.guarded(generation, delegate::id);
        }

        @Override public ParameterGroupAppearance ui() {
            final dev.turboism.sdk.cubism.id.ParameterGroupId id = id();
            return host.appearanceParameterGroup(modelId, id, modelGeneration);
        }
        @Override public java.util.Optional<String> name() {
            return host.guarded(generation, delegate::name);
        }
        @Override public java.util.Optional<dev.turboism.sdk.cubism.id.ParameterGroupId> parentId() {
            return host.guarded(generation, delegate::parentId);
        }
        @Override public List<dev.turboism.sdk.cubism.id.ParameterGroupId> childGroupIds() {
            return host.guarded(generation, delegate::childGroupIds);
        }
        @Override public List<dev.turboism.sdk.cubism.id.ParameterId> parameterIds() {
            return host.guarded(generation, delegate::parameterIds);
        }

        @Override public void rename(final String name) {
            host.guardedVoid(generation, () -> delegate.rename(name));
        }
    }

    final class SessionParameter implements Parameter {
    private final DynamicCubismModelAccess host;
    final long generation;
    final long modelGeneration;
    final ModelId modelId;
    final Parameter delegate;

    SessionParameter(
        final DynamicCubismModelAccess host,
        final long generation,
        final long modelGeneration,
        final ModelId modelId,
        final Parameter delegate
    ) {
        this.host = Objects.requireNonNull(host, "host");
        this.generation = generation;
        this.modelGeneration = modelGeneration;
        this.modelId = Objects.requireNonNull(modelId, "modelId");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

        @Override public dev.turboism.sdk.cubism.id.ParameterId id() {
            return host.guarded(generation, delegate::id);
        }

        @Override public ParameterAppearance ui() {
            final dev.turboism.sdk.cubism.id.ParameterId id = id();
            return host.appearanceParameter(modelId, id, modelGeneration);
        }

        @Override public int index() { return host.guarded(generation, delegate::index); }
        @Override public dev.turboism.sdk.cubism.model.FloatSequence keyValues() {
            return new SessionFloatSequence(host, generation, host.guarded(generation, delegate::keyValues));
        }
        @Override public java.util.Optional<String> name() {
            return host.guarded(generation, delegate::name);
        }
        @Override public dev.turboism.sdk.cubism.model.ParameterType type() {
            return host.guarded(generation, delegate::type);
        }
        @Override public boolean isBlendShape() {
            return host.guarded(generation, delegate::isBlendShape);
        }
        @Override public java.util.Optional<Boolean> repeat() {
            return host.guarded(generation, delegate::repeat);
        }
        @Override public java.util.Optional<Boolean> combined() {
            return host.guarded(generation, delegate::combined);
        }
        @Override public java.util.Optional<dev.turboism.sdk.cubism.id.ParameterId> combinedWith() {
            return host.guarded(generation, delegate::combinedWith);
        }
        @Override public List<dev.turboism.sdk.cubism.model.ParameterBinding> getParameterBindings() {
            return host.guarded(generation, delegate::getParameterBindings);
        }
        @Override public void combineWith(
            final dev.turboism.sdk.cubism.id.ParameterId partnerId
        ) {
            host.guardedVoid(generation, () -> delegate.combineWith(partnerId));
        }
        @Override public void uncombine() {
            host.guardedVoid(generation, delegate::uncombine);
        }
        @Override public float getValue() { return host.guarded(generation, delegate::getValue); }
        @Override public float getMinimumValue() { return host.guarded(generation, delegate::getMinimumValue); }
        @Override public float getMaximumValue() { return host.guarded(generation, delegate::getMaximumValue); }
        @Override public float getDefaultValue() { return host.guarded(generation, delegate::getDefaultValue); }
        @Override public void setValue(final float value) {
            host.guardedVoid(generation, () -> delegate.setValue(value));
        }
        @Override public void resetToDefault() {
            host.guardedVoid(generation, () -> delegate.resetToDefault());
        }
        @Override public void updateDefinition(
            final dev.turboism.sdk.cubism.model.ParameterDefinition definition
        ) {
            host.guardedVoid(generation, () -> delegate.updateDefinition(definition));
        }

    }
