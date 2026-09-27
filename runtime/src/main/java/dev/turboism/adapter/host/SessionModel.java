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


    final class SessionModel implements CubismModel {

    final DynamicCubismModelAccess host;
    final long generation;
    final long modelGeneration;
    final ModelId modelId;
    final CubismModel delegate;

    SessionModel(
        final DynamicCubismModelAccess host,
        final long generation,
        final long modelGeneration,
        final ModelId modelId,
        final CubismModel delegate
    ) {
        this.host = Objects.requireNonNull(host, "host");
        this.generation = generation;
        this.modelGeneration = modelGeneration;
        this.modelId = Objects.requireNonNull(modelId, "modelId");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    long generation() {
        return generation;
    }

    CubismModel delegate() {
        return delegate;
    }

        @Override
        public dev.turboism.sdk.cubism.id.ModelId id() {
            return host.current(generation, CubismModel::id, delegate);
        }

        @Override public String name() {
            return host.current(generation, CubismModel::name, delegate);
        }
        @Override public void setName(final String name) {
            host.guardedVoid(generation, () -> delegate.setName(name));
        }
        @Override public List<dev.turboism.sdk.cubism.model.ModelInstance> modelInstances() {
            return host.current(generation, CubismModel::modelInstances, delegate);
        }
        @Override public java.util.Optional<dev.turboism.sdk.cubism.model.ModelInstance> currentModelInstance() {
            return host.current(generation, CubismModel::currentModelInstance, delegate);
        }
        @Override public boolean modelEditing() {
            return host.current(generation, CubismModel::modelEditing, delegate);
        }
        @Override public dev.turboism.sdk.cubism.core.MocInfo mocInfo() {
            return host.current(generation, CubismModel::mocInfo, delegate);
        }
        @Override public dev.turboism.sdk.cubism.model.ParameterDefinitions parameterDefinitions() {
            return new SessionParameterDefinitions(
                host, generation,
                host.current(generation, CubismModel::parameterDefinitions, delegate)
            );
        }
        @Override public dev.turboism.sdk.cubism.model.ModelStatistics statistics() {
            return host.current(generation, CubismModel::statistics, delegate);
        }

        @Override public java.util.List<dev.turboism.sdk.cubism.clipmask.PsdClipMaskDocumentSnapshot> psdDocuments() {
            return host.current(generation, CubismModel::psdDocuments, delegate);
        }

        @Override public dev.turboism.sdk.cubism.model.ModelProfile profile() {
            return host.current(generation, CubismModel::profile, delegate);
        }

        @Override public dev.turboism.sdk.cubism.model.PhysicsSettings physicsSettings() {
            return host.current(generation, CubismModel::physicsSettings, delegate);
        }

        @Override public dev.turboism.sdk.cubism.model.AutoYure autoYure() {
            return host.current(generation, CubismModel::autoYure, delegate);
        }

        @Override public List<dev.turboism.sdk.cubism.model.AnimationDocument> animationDocuments() {
            return host.current(generation, CubismModel::animationDocuments, delegate).stream()
                .map(document -> (dev.turboism.sdk.cubism.model.AnimationDocument)
                    new SessionAnimationDocument(host, generation, document))
                .toList();
        }

        @Override public dev.turboism.sdk.cubism.model.ModelTextures textures() {
            return new SessionModelTextures(
                host, generation,
                host.current(generation, CubismModel::textures, delegate)
            );
        }

        @Override
        public boolean defaultKeyformLocked() {
            return host.current(generation, CubismModel::defaultKeyformLocked, delegate);
        }

        @Override
        public void setDefaultKeyformLocked(final boolean locked) {
            host.guardedVoid(generation, () -> delegate.setDefaultKeyformLocked(locked));
        }

        @Override
        public dev.turboism.sdk.cubism.model.ModelEditLevel editLevel() {
            return host.current(generation, CubismModel::editLevel, delegate);
        }

        @Override
        public void setEditLevel(final dev.turboism.sdk.cubism.model.ModelEditLevel level) {
            host.guardedVoid(generation, () -> delegate.setEditLevel(level));
        }

        @Override
        public Parameters parameters() {
            return new SessionParameters(
                host, generation,
                modelGeneration,
                modelId,
                host.current(generation, CubismModel::parameters, delegate)
            );
        }

        @Override
        public ParameterGroups parameterGroups() {
            return new SessionParameterGroups(
                host, generation,
                modelGeneration,
                modelId,
                host.current(generation, CubismModel::parameterGroups, delegate)
            );
        }

        @Override
        public dev.turboism.sdk.cubism.model.ParameterBindingOperations parameterBindings(
            final dev.turboism.sdk.cubism.id.ParameterId parameterId
        ) {
            final dev.turboism.sdk.cubism.model.ParameterBindingOperations operations = host.current(
                generation,
                model -> model.parameterBindings(parameterId),
                delegate
            );
            return new dev.turboism.sdk.cubism.model.ParameterBindingOperations() {
                @Override public void bind(
                    final dev.turboism.sdk.cubism.model.ParameterBindingTarget target,
                    final List<dev.turboism.sdk.cubism.model.ParameterBindingPoint> points
                ) {
                    host.guardedVoid(generation, () -> operations.bind(target, points));
                }
                @Override public void createPoint(
                    final dev.turboism.sdk.cubism.model.ParameterBindingTarget target,
                    final dev.turboism.sdk.cubism.model.ParameterBindingPoint point
                ) {
                    host.guardedVoid(generation, () -> operations.createPoint(target, point));
                }
                @Override public void movePoint(
                    final dev.turboism.sdk.cubism.model.ParameterBindingTarget target,
                    final dev.turboism.sdk.cubism.id.ParameterBindingPointId pointId,
                    final float value
                ) {
                    host.guardedVoid(generation, () -> operations.movePoint(target, pointId, value));
                }
                @Override public void deletePoint(
                    final dev.turboism.sdk.cubism.model.ParameterBindingTarget target,
                    final dev.turboism.sdk.cubism.id.ParameterBindingPointId pointId
                ) {
                    host.guardedVoid(generation, () -> operations.deletePoint(target, pointId));
                }
                @Override public void unbind(
                    final dev.turboism.sdk.cubism.model.ParameterBindingTarget target
                ) {
                    host.guardedVoid(generation, () -> operations.unbind(target));
                }
            };
        }

        @Override
        public dev.turboism.sdk.cubism.model.ParameterBindingBatchOperations parameterBindingBatch() {
            final dev.turboism.sdk.cubism.model.ParameterBindingBatchOperations operations = host.current(
                generation,
                CubismModel::parameterBindingBatch,
                delegate
            );
            return new dev.turboism.sdk.cubism.model.ParameterBindingBatchOperations() {
                @Override public void invert(
                    final List<dev.turboism.sdk.cubism.model.ParameterBindingTarget> targets
                ) {
                    host.guardedVoid(generation, () -> operations.invert(targets));
                }
                @Override public void transfer(
                    final dev.turboism.sdk.cubism.model.ParameterBindingTransferPlan plan
                ) {
                    host.guardedVoid(generation, () -> operations.transfer(plan));
                }
                @Override public void transferClamped(
                    final dev.turboism.sdk.cubism.model.ParameterBindingTransferPlan plan
                ) {
                    host.guardedVoid(generation, () -> operations.transferClamped(plan));
                }
                @Override public void transferMorphClamped(
                    final dev.turboism.sdk.cubism.model.ParameterBindingTransferPlan plan
                ) {
                    host.guardedVoid(generation, () -> operations.transferMorphClamped(plan));
                }
            };
        }

        @Override
        public Canvas canvas() {
            return new SessionCanvas(host, generation, host.current(generation, CubismModel::canvas, delegate));
        }

        @Override
        public Parts parts() {
            return new SessionParts(
                host, generation,
                modelGeneration,
                modelId,
                host.current(generation, CubismModel::parts, delegate)
            );
        }

        @Override
        public Drawables drawables() {
            return new SessionDrawables(
                host, generation,
                modelGeneration,
                modelId,
                host.current(generation, CubismModel::drawables, delegate)
            );
        }

        @Override
        public Deformers deformers() {
            return new SessionDeformers(
                host, generation,
                modelGeneration,
                modelId,
                host.current(generation, CubismModel::deformers, delegate)
            );
        }

        @Override
        public WarpDeformers warpDeformers() {
            return new SessionWarpDeformers(
                host, generation,
                host.current(generation, CubismModel::warpDeformers, delegate)
            );
        }

        @Override
        public RotationDeformers rotationDeformers() {
            return new SessionRotationDeformers(
                host, generation,
                host.current(generation, CubismModel::rotationDeformers, delegate)
            );
        }

        @Override
        public Glues glues() {
            return new SessionGlues(host, generation, host.current(generation, CubismModel::glues, delegate));
        }

        @Override
        public void update() {
            host.guardedVoid(generation, delegate::update);
        }

        @Override
        public void replaceArtMeshClipMasks(final List<ClipMaskReplacement> replacements) {
            host.guardedVoid(generation, () -> delegate.replaceArtMeshClipMasks(replacements));
        }
    }

