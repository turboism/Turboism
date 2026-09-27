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


    final class SessionAnimationDocument
        implements dev.turboism.sdk.cubism.model.AnimationDocument {
        private final DynamicCubismModelAccess host;
        private final long generation;
        private final dev.turboism.sdk.cubism.model.AnimationDocument delegate;

        SessionAnimationDocument(
            final DynamicCubismModelAccess host,
            final long generation,
            final dev.turboism.sdk.cubism.model.AnimationDocument delegate
        ) {
            this.host = Objects.requireNonNull(host, "host");
            this.generation = generation;
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override public String animationName() {
            return host.guarded(generation, delegate::animationName);
        }
        @Override public int sceneCount() {
            return host.guarded(generation, delegate::sceneCount);
        }
        @Override public java.util.Optional<String> currentSceneName() {
            return host.guarded(generation, delegate::currentSceneName);
        }
        @Override public List<String> sceneNames() {
            return host.guarded(generation, delegate::sceneNames);
        }
        @Override public List<dev.turboism.sdk.cubism.model.AnimationScene> scenes() {
            return host.guarded(generation, delegate::scenes).stream()
                .map(scene -> (dev.turboism.sdk.cubism.model.AnimationScene)
                    new SessionAnimationScene(host, generation, scene))
                .toList();
        }
    }

    final class SessionAnimationScene
        implements dev.turboism.sdk.cubism.model.AnimationScene {
        private final DynamicCubismModelAccess host;
        private final long generation;
        private final dev.turboism.sdk.cubism.model.AnimationScene delegate;

        SessionAnimationScene(
            final DynamicCubismModelAccess host,
            final long generation,
            final dev.turboism.sdk.cubism.model.AnimationScene delegate
        ) {
            this.host = Objects.requireNonNull(host, "host");
            this.generation = generation;
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override public String name() {
            return host.guarded(generation, delegate::name);
        }
        @Override public String guid() {
            return host.guarded(generation, delegate::guid);
        }
        @Override public java.util.Optional<String> tag() {
            return host.guarded(generation, delegate::tag);
        }
        @Override public java.util.Map<Integer, String> markers() {
            return host.guarded(generation, delegate::markers);
        }
        @Override public int startFrame() {
            return host.guarded(generation, delegate::startFrame);
        }
        @Override public int durationFrames() {
            return host.guarded(generation, delegate::durationFrames);
        }
        @Override public double framesPerSecond() {
            return host.guarded(generation, delegate::framesPerSecond);
        }
        @Override public int width() {
            return host.guarded(generation, delegate::width);
        }
        @Override public int height() {
            return host.guarded(generation, delegate::height);
        }
        @Override public boolean loopMotion() {
            return host.guarded(generation, delegate::loopMotion);
        }
        @Override public int workspaceStartFrame() {
            return host.guarded(generation, delegate::workspaceStartFrame);
        }
        @Override public int workspaceEndFrame() {
            return host.guarded(generation, delegate::workspaceEndFrame);
        }
        @Override public List<dev.turboism.sdk.cubism.model.AnimationTrack> tracks() {
            return host.guarded(generation, delegate::tracks).stream()
                .map(track -> (dev.turboism.sdk.cubism.model.AnimationTrack)
                    new SessionAnimationTrack(host, generation, track))
                .toList();
        }
        @Override public int playheadFrame() {
            return host.guarded(generation, delegate::playheadFrame);
        }
        @Override public void seekTo(final int frame) {
            host.guardedVoid(generation, () -> delegate.seekTo(frame));
        }
        @Override public boolean current() {
            return host.guarded(generation, delegate::current);
        }
        @Override public void activate() {
            host.guardedVoid(generation, delegate::activate);
        }
        @Override public dev.turboism.sdk.cubism.model.AnimationCurveType defaultCurveType() {
            return host.guarded(generation, delegate::defaultCurveType);
        }
        @Override public void setDefaultCurveType(
            final dev.turboism.sdk.cubism.model.AnimationCurveType curveType
        ) {
            host.guardedVoid(generation, () -> delegate.setDefaultCurveType(curveType));
        }
        @Override public void rename(final String name) {
            host.guardedVoid(generation, () -> delegate.rename(name));
        }
    }

    final class SessionAnimationTrack
        implements dev.turboism.sdk.cubism.model.AnimationTrack {
        private final DynamicCubismModelAccess host;
        private final long generation;
        private final dev.turboism.sdk.cubism.model.AnimationTrack delegate;

        SessionAnimationTrack(
            final DynamicCubismModelAccess host,
            final long generation,
            final dev.turboism.sdk.cubism.model.AnimationTrack delegate
        ) {
            this.host = Objects.requireNonNull(host, "host");
            this.generation = generation;
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override public String guid() {
            return host.guarded(generation, delegate::guid);
        }
        @Override public String name() {
            return host.guarded(generation, delegate::name);
        }
        @Override public dev.turboism.sdk.cubism.model.AnimationTrackKind kind() {
            return host.guarded(generation, delegate::kind);
        }
        @Override public int startFrame() {
            return host.guarded(generation, delegate::startFrame);
        }
        @Override public int durationFrames() {
            return host.guarded(generation, delegate::durationFrames);
        }
        @Override public List<Integer> keyframeFrames() {
            return host.guarded(generation, delegate::keyframeFrames);
        }
        @Override public boolean visible() {
            return host.guarded(generation, delegate::visible);
        }
        @Override public boolean editable() {
            return host.guarded(generation, delegate::editable);
        }
        @Override public boolean muted() {
            return host.guarded(generation, delegate::muted);
        }
        @Override public boolean repeat() {
            return host.guarded(generation, delegate::repeat);
        }
        @Override public List<dev.turboism.sdk.cubism.model.AnimationTrack> children() {
            return host.guarded(generation, delegate::children).stream()
                .map(child -> (dev.turboism.sdk.cubism.model.AnimationTrack)
                    new SessionAnimationTrack(host, generation, child))
                .toList();
        }
        @Override public List<dev.turboism.sdk.cubism.model.AnimationAttribute> attributes() {
            return host.guarded(generation, delegate::attributes).stream()
                .map(attribute -> (dev.turboism.sdk.cubism.model.AnimationAttribute)
                    new SessionAnimationAttribute(host, generation, attribute))
                .toList();
        }
        @Override public java.util.Optional<String> linkedModelGuid() {
            return host.guarded(generation, delegate::linkedModelGuid);
        }
        @Override public java.util.Optional<String> linkedSceneGuid() {
            return host.guarded(generation, delegate::linkedSceneGuid);
        }
    }

    final class SessionAnimationAttribute
        implements dev.turboism.sdk.cubism.model.AnimationAttribute {
    final DynamicCubismModelAccess host;
    final long generation;
    final dev.turboism.sdk.cubism.model.AnimationAttribute delegate;

    SessionAnimationAttribute(
        final DynamicCubismModelAccess host,
        final long generation,
        final dev.turboism.sdk.cubism.model.AnimationAttribute delegate
    ) {
        this.host = Objects.requireNonNull(host, "host");
        this.generation = generation;
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

        private DynamicCubismModelAccess ownerAccess() {
            return host;
        }

        @Override public String id() {
            return host.guarded(generation, delegate::id);
        }
        @Override public String name() {
            return host.guarded(generation, delegate::name);
        }
        @Override public String guid() {
            return host.guarded(generation, delegate::guid);
        }
        @Override public String effectId() {
            return host.guarded(generation, delegate::effectId);
        }
        @Override public java.util.Optional<dev.turboism.sdk.cubism.id.ParameterId> parameterId() {
            return host.guarded(generation, delegate::parameterId);
        }
        @Override public dev.turboism.sdk.cubism.model.AnimationAttributeKind kind() {
            return host.guarded(generation, delegate::kind);
        }
        @Override public boolean active() {
            return host.guarded(generation, delegate::active);
        }
        @Override public boolean editable() {
            return host.guarded(generation, delegate::editable);
        }
        @Override public List<dev.turboism.sdk.cubism.model.AnimationKeyframe> keyframes() {
            return host.guarded(generation, delegate::keyframes);
        }
        @Override public void setKeyframe(final int frame, final double value) {
            host.guardedVoid(generation, () -> delegate.setKeyframe(frame, value));
        }
        @Override public void setKeyframe(
            final int frame,
            final double value,
            final dev.turboism.sdk.cubism.model.AnimationCurveType curveType
        ) {
            host.guardedVoid(generation, () -> delegate.setKeyframe(frame, value, curveType));
        }
        @Override public void setKeyframe(final int frame, final float x, final float y) {
            host.guardedVoid(generation, () -> delegate.setKeyframe(frame, x, y));
        }
        @Override public void removeKeyframe(final int frame) {
            host.guardedVoid(generation, () -> delegate.removeKeyframe(frame));
        }
        @Override public int offsetKeyframes(final int frameDelta) {
            return host.guarded(generation, () -> delegate.offsetKeyframes(frameDelta));
        }
        @Override public int scaleKeyframeTimes(final double factor, final int originFrame) {
            return host.guarded(generation, () -> delegate.scaleKeyframeTimes(factor, originFrame));
        }
        @Override public int quantizeKeyframes(final int stepFrames) {
            return host.guarded(generation, () -> delegate.quantizeKeyframes(stepFrames));
        }
        @Override public int copyKeyframesFrom(
            final dev.turboism.sdk.cubism.model.AnimationAttribute source,
            final boolean replace
        ) {
            Objects.requireNonNull(source, "source");
            final dev.turboism.sdk.cubism.model.AnimationAttribute unwrapped =
                unwrap(host, generation, source);
            return host.guarded(generation, () -> delegate.copyKeyframesFrom(unwrapped, replace));
        }
        @Override public int applyCurveType(
            final dev.turboism.sdk.cubism.model.AnimationCurveType curveType
        ) {
            return host.guarded(generation, () -> delegate.applyCurveType(curveType));
        }
        @Override public int applyCurveType(
            final dev.turboism.sdk.cubism.model.AnimationCurveType curveType,
            final int fromFrame,
            final int toFrame
        ) {
            return host.guarded(
                generation,
                () -> delegate.applyCurveType(curveType, fromFrame, toFrame)
            );
        }
        @Override public void recordKeyframe(
            final int frame,
            final dev.turboism.sdk.cubism.model.AnimationCurveType curveType
        ) {
            host.guardedVoid(generation, () -> delegate.recordKeyframe(frame, curveType));
        }
        @Override public int bakeEvaluated(
            final int fromFrame,
            final int toFrame,
            final int stepFrames,
            final dev.turboism.sdk.cubism.model.AnimationCurveType curveType
        ) {
            return host.guarded(
                generation,
                () -> delegate.bakeEvaluated(fromFrame, toFrame, stepFrames, curveType)
            );
        }

        static dev.turboism.sdk.cubism.model.AnimationAttribute unwrap(
            final DynamicCubismModelAccess host,
            final long expectedGeneration,
            final dev.turboism.sdk.cubism.model.AnimationAttribute value
        ) {
            if (value instanceof SessionAnimationAttribute session
                && session.ownerAccess() == host
                && session.generation == expectedGeneration) {
                return session.delegate;
            }
            throw DynamicCubismModelAccess.staleFailure();
        }
    }

