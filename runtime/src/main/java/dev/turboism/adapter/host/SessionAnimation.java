package dev.turboism.adapter.host;

import java.util.List;
import java.util.Objects;

final class SessionAnimationDocument implements dev.turboism.sdk.cubism.model.AnimationDocument {
    private final DynamicCubismModelAccess host;
    private final long generation;
    private final dev.turboism.sdk.cubism.model.AnimationDocument delegate;

    SessionAnimationDocument(
            final DynamicCubismModelAccess host,
            final long generation,
            final dev.turboism.sdk.cubism.model.AnimationDocument delegate) {
        this.host = Objects.requireNonNull(host, "host");
        this.generation = generation;
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public String animationName() {
        return host.guarded(generation, delegate::animationName);
    }

    @Override
    public int sceneCount() {
        return host.guarded(generation, delegate::sceneCount);
    }

    @Override
    public java.util.Optional<String> currentSceneName() {
        return host.guarded(generation, delegate::currentSceneName);
    }

    @Override
    public List<String> sceneNames() {
        return host.guarded(generation, delegate::sceneNames);
    }

    @Override
    public List<dev.turboism.sdk.cubism.model.AnimationScene> scenes() {
        return host.guarded(generation, delegate::scenes).stream()
                .map(scene -> (dev.turboism.sdk.cubism.model.AnimationScene)
                        new SessionAnimationScene(host, generation, scene))
                .toList();
    }
}

final class SessionAnimationScene implements dev.turboism.sdk.cubism.model.AnimationScene {
    private final DynamicCubismModelAccess host;
    private final long generation;
    private final dev.turboism.sdk.cubism.model.AnimationScene delegate;

    SessionAnimationScene(
            final DynamicCubismModelAccess host,
            final long generation,
            final dev.turboism.sdk.cubism.model.AnimationScene delegate) {
        this.host = Objects.requireNonNull(host, "host");
        this.generation = generation;
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public String name() {
        return host.guarded(generation, delegate::name);
    }

    @Override
    public String guid() {
        return host.guarded(generation, delegate::guid);
    }

    @Override
    public java.util.Optional<String> tag() {
        return host.guarded(generation, delegate::tag);
    }

    @Override
    public java.util.Map<Integer, String> markers() {
        return host.guarded(generation, delegate::markers);
    }

    @Override
    public int startFrame() {
        return host.guarded(generation, delegate::startFrame);
    }

    @Override
    public int durationFrames() {
        return host.guarded(generation, delegate::durationFrames);
    }

    @Override
    public double framesPerSecond() {
        return host.guarded(generation, delegate::framesPerSecond);
    }

    @Override
    public int width() {
        return host.guarded(generation, delegate::width);
    }

    @Override
    public int height() {
        return host.guarded(generation, delegate::height);
    }

    @Override
    public boolean loopMotion() {
        return host.guarded(generation, delegate::loopMotion);
    }

    @Override
    public int workspaceStartFrame() {
        return host.guarded(generation, delegate::workspaceStartFrame);
    }

    @Override
    public int workspaceEndFrame() {
        return host.guarded(generation, delegate::workspaceEndFrame);
    }

    @Override
    public List<dev.turboism.sdk.cubism.model.AnimationTrack> tracks() {
        return host.guarded(generation, delegate::tracks).stream()
                .map(track -> (dev.turboism.sdk.cubism.model.AnimationTrack)
                        new SessionAnimationTrack(host, generation, track))
                .toList();
    }

    @Override
    public int playheadFrame() {
        return host.guarded(generation, delegate::playheadFrame);
    }

    @Override
    public void seekTo(final int frame) {
        host.guardedVoid(generation, () -> delegate.seekTo(frame));
    }

    @Override
    public boolean current() {
        return host.guarded(generation, delegate::current);
    }

    @Override
    public void activate() {
        host.guardedVoid(generation, delegate::activate);
    }

    @Override
    public dev.turboism.sdk.cubism.model.AnimationCurveType defaultCurveType() {
        return host.guarded(generation, delegate::defaultCurveType);
    }

    @Override
    public void setDefaultCurveType(final dev.turboism.sdk.cubism.model.AnimationCurveType curveType) {
        host.guardedVoid(generation, () -> delegate.setDefaultCurveType(curveType));
    }

    @Override
    public void rename(final String name) {
        host.guardedVoid(generation, () -> delegate.rename(name));
    }
}

final class SessionAnimationTrack implements dev.turboism.sdk.cubism.model.AnimationTrack {
    private final DynamicCubismModelAccess host;
    private final long generation;
    private final dev.turboism.sdk.cubism.model.AnimationTrack delegate;

    SessionAnimationTrack(
            final DynamicCubismModelAccess host,
            final long generation,
            final dev.turboism.sdk.cubism.model.AnimationTrack delegate) {
        this.host = Objects.requireNonNull(host, "host");
        this.generation = generation;
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public String guid() {
        return host.guarded(generation, delegate::guid);
    }

    @Override
    public String name() {
        return host.guarded(generation, delegate::name);
    }

    @Override
    public dev.turboism.sdk.cubism.model.AnimationTrackKind kind() {
        return host.guarded(generation, delegate::kind);
    }

    @Override
    public int startFrame() {
        return host.guarded(generation, delegate::startFrame);
    }

    @Override
    public int durationFrames() {
        return host.guarded(generation, delegate::durationFrames);
    }

    @Override
    public List<Integer> keyframeFrames() {
        return host.guarded(generation, delegate::keyframeFrames);
    }

    @Override
    public boolean visible() {
        return host.guarded(generation, delegate::visible);
    }

    @Override
    public boolean editable() {
        return host.guarded(generation, delegate::editable);
    }

    @Override
    public boolean muted() {
        return host.guarded(generation, delegate::muted);
    }

    @Override
    public boolean repeat() {
        return host.guarded(generation, delegate::repeat);
    }

    @Override
    public List<dev.turboism.sdk.cubism.model.AnimationTrack> children() {
        return host.guarded(generation, delegate::children).stream()
                .map(child -> (dev.turboism.sdk.cubism.model.AnimationTrack)
                        new SessionAnimationTrack(host, generation, child))
                .toList();
    }

    @Override
    public List<dev.turboism.sdk.cubism.model.AnimationAttribute> attributes() {
        return host.guarded(generation, delegate::attributes).stream()
                .map(attribute -> (dev.turboism.sdk.cubism.model.AnimationAttribute)
                        new SessionAnimationAttribute(host, generation, attribute))
                .toList();
    }

    @Override
    public java.util.Optional<String> linkedModelGuid() {
        return host.guarded(generation, delegate::linkedModelGuid);
    }

    @Override
    public java.util.Optional<String> linkedSceneGuid() {
        return host.guarded(generation, delegate::linkedSceneGuid);
    }
}

final class SessionAnimationAttribute implements dev.turboism.sdk.cubism.model.AnimationAttribute {
    final DynamicCubismModelAccess host;
    final long generation;
    final dev.turboism.sdk.cubism.model.AnimationAttribute delegate;

    SessionAnimationAttribute(
            final DynamicCubismModelAccess host,
            final long generation,
            final dev.turboism.sdk.cubism.model.AnimationAttribute delegate) {
        this.host = Objects.requireNonNull(host, "host");
        this.generation = generation;
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    private DynamicCubismModelAccess ownerAccess() {
        return host;
    }

    @Override
    public String id() {
        return host.guarded(generation, delegate::id);
    }

    @Override
    public String name() {
        return host.guarded(generation, delegate::name);
    }

    @Override
    public String guid() {
        return host.guarded(generation, delegate::guid);
    }

    @Override
    public String effectId() {
        return host.guarded(generation, delegate::effectId);
    }

    @Override
    public java.util.Optional<dev.turboism.sdk.cubism.id.ParameterId> parameterId() {
        return host.guarded(generation, delegate::parameterId);
    }

    @Override
    public dev.turboism.sdk.cubism.model.AnimationAttributeKind kind() {
        return host.guarded(generation, delegate::kind);
    }

    @Override
    public boolean active() {
        return host.guarded(generation, delegate::active);
    }

    @Override
    public boolean editable() {
        return host.guarded(generation, delegate::editable);
    }

    @Override
    public List<dev.turboism.sdk.cubism.model.AnimationKeyframe> keyframes() {
        return host.guarded(generation, delegate::keyframes);
    }

    @Override
    public void setKeyframe(final int frame, final double value) {
        host.guardedVoid(generation, () -> delegate.setKeyframe(frame, value));
    }

    @Override
    public void setKeyframe(
            final int frame, final double value, final dev.turboism.sdk.cubism.model.AnimationCurveType curveType) {
        host.guardedVoid(generation, () -> delegate.setKeyframe(frame, value, curveType));
    }

    @Override
    public void setKeyframe(final int frame, final float x, final float y) {
        host.guardedVoid(generation, () -> delegate.setKeyframe(frame, x, y));
    }

    @Override
    public void removeKeyframe(final int frame) {
        host.guardedVoid(generation, () -> delegate.removeKeyframe(frame));
    }

    @Override
    public int offsetKeyframes(final int frameDelta) {
        return host.guarded(generation, () -> delegate.offsetKeyframes(frameDelta));
    }

    @Override
    public int scaleKeyframeTimes(final double factor, final int originFrame) {
        return host.guarded(generation, () -> delegate.scaleKeyframeTimes(factor, originFrame));
    }

    @Override
    public int quantizeKeyframes(final int stepFrames) {
        return host.guarded(generation, () -> delegate.quantizeKeyframes(stepFrames));
    }

    @Override
    public int copyKeyframesFrom(final dev.turboism.sdk.cubism.model.AnimationAttribute source, final boolean replace) {
        Objects.requireNonNull(source, "source");
        final dev.turboism.sdk.cubism.model.AnimationAttribute unwrapped = unwrap(host, generation, source);
        return host.guarded(generation, () -> delegate.copyKeyframesFrom(unwrapped, replace));
    }

    @Override
    public int applyCurveType(final dev.turboism.sdk.cubism.model.AnimationCurveType curveType) {
        return host.guarded(generation, () -> delegate.applyCurveType(curveType));
    }

    @Override
    public int applyCurveType(
            final dev.turboism.sdk.cubism.model.AnimationCurveType curveType, final int fromFrame, final int toFrame) {
        return host.guarded(generation, () -> delegate.applyCurveType(curveType, fromFrame, toFrame));
    }

    @Override
    public void recordKeyframe(final int frame, final dev.turboism.sdk.cubism.model.AnimationCurveType curveType) {
        host.guardedVoid(generation, () -> delegate.recordKeyframe(frame, curveType));
    }

    @Override
    public int bakeEvaluated(
            final int fromFrame,
            final int toFrame,
            final int stepFrames,
            final dev.turboism.sdk.cubism.model.AnimationCurveType curveType) {
        return host.guarded(generation, () -> delegate.bakeEvaluated(fromFrame, toFrame, stepFrames, curveType));
    }

    static dev.turboism.sdk.cubism.model.AnimationAttribute unwrap(
            final DynamicCubismModelAccess host,
            final long expectedGeneration,
            final dev.turboism.sdk.cubism.model.AnimationAttribute value) {
        if (value instanceof SessionAnimationAttribute session
                && session.ownerAccess() == host
                && session.generation == expectedGeneration) {
            return session.delegate;
        }
        throw DynamicCubismModelAccess.staleFailure();
    }
}
