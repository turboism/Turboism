package dev.turboism.adapter.cubism.editor;

import dev.turboism.adapter.cubism.editor.host.EditorHostThread;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.AnimationAttribute;
import dev.turboism.sdk.cubism.model.AnimationAttributeKind;
import dev.turboism.sdk.cubism.model.AnimationBezierHandle;
import dev.turboism.sdk.cubism.model.AnimationCurveType;
import dev.turboism.sdk.cubism.model.AnimationKeyframe;
import dev.turboism.sdk.cubism.model.AnimationScene;
import dev.turboism.sdk.cubism.model.AnimationTrack;
import dev.turboism.sdk.cubism.model.AnimationTrackKind;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Read/write projections over one Editor animation timeline: the scene and track snapshot views
 * plus the keyframe-carrying attribute view the SDK authoring API talks to.
 *
 * <p>Every projection holds the values the verified host returned when the timeline was read, so
 * the SDK can reason about a stable snapshot; each write re-resolves the live instance through
 * {@link EditorTimelineAccessCore} and fails closed when the handle went stale instead of writing
 * into the wrong scene.</p>
 */
final class EditorTimelineProjections {

    private EditorTimelineProjections() {}

    static HandleData handleData(final EditorTimelineAccessCore core, final Object controlPoint) {
        if (controlPoint == null) {
            return null;
        }
        core.requireInstance(
                "cubism.editor-model.bezier-ctrl-point.class",
                controlPoint,
                "Editor animation bezier control point is invalid.");
        return new HandleData(
                core.floatValue(
                        core.resolver.invoke("cubism.editor-model.bezier-ctrl-point.pos", controlPoint),
                        "Editor animation bezier handle frame"),
                core.doubleValue(
                        core.resolver.invoke("cubism.editor-model.bezier-ctrl-point.value", controlPoint),
                        "Editor animation bezier handle value"),
                core.flag(
                        core.resolver.invoke("cubism.editor-model.bezier-ctrl-point.corner", controlPoint),
                        "Editor animation bezier handle corner flag"));
    }

    static Optional<AnimationBezierHandle> bezierHandle(
            final EditorTimelineAccessCore core, final Object controlPoint) {
        final HandleData handle = handleData(core, controlPoint);
        return handle == null
                ? Optional.empty()
                : Optional.of(new AnimationBezierHandle(handle.frame(), handle.value(), handle.corner()));
    }

    static final class ProjectedScene implements AnimationScene {

        private final EditorTimelineAccessCore core;
        private final Object sceneSource;
        private final Object fileContent;
        private final String name;
        private final String guid;
        private final Optional<String> tag;
        private final Map<Integer, String> markers;
        private final int startFrame;
        private final int durationFrames;
        private final double framesPerSecond;
        private final int width;
        private final int height;
        private final boolean loopMotion;
        private final int workspaceStartFrame;
        private final int workspaceEndFrame;
        private final List<AnimationTrack> tracks;

        ProjectedScene(
                final EditorTimelineAccessCore core,
                final Object sceneSource,
                final Object fileContent,
                final String name,
                final String guid,
                final Optional<String> tag,
                final Map<Integer, String> markers,
                final int startFrame,
                final int durationFrames,
                final double framesPerSecond,
                final int width,
                final int height,
                final boolean loopMotion,
                final int workspaceStartFrame,
                final int workspaceEndFrame,
                final List<AnimationTrack> tracks) {
            this.core = core;
            this.sceneSource = sceneSource;
            this.fileContent = fileContent;
            this.name = name;
            this.guid = guid;
            this.tag = tag;
            this.markers = markers;
            this.startFrame = startFrame;
            this.durationFrames = durationFrames;
            this.framesPerSecond = framesPerSecond;
            this.width = width;
            this.height = height;
            this.loopMotion = loopMotion;
            this.workspaceStartFrame = workspaceStartFrame;
            this.workspaceEndFrame = workspaceEndFrame;
            this.tracks = tracks;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String guid() {
            return guid;
        }

        @Override
        public Optional<String> tag() {
            return tag;
        }

        @Override
        public Map<Integer, String> markers() {
            return markers;
        }

        @Override
        public int startFrame() {
            return startFrame;
        }

        @Override
        public int durationFrames() {
            return durationFrames;
        }

        @Override
        public double framesPerSecond() {
            return framesPerSecond;
        }

        @Override
        public int width() {
            return width;
        }

        @Override
        public int height() {
            return height;
        }

        @Override
        public boolean loopMotion() {
            return loopMotion;
        }

        @Override
        public int workspaceStartFrame() {
            return workspaceStartFrame;
        }

        @Override
        public int workspaceEndFrame() {
            return workspaceEndFrame;
        }

        @Override
        public List<AnimationTrack> tracks() {
            return tracks;
        }

        @Override
        public void rename(final String newName) {
            EditorHostThread.dispatch("Cubism animation scene write", () -> {
                renameOnEdt(newName);
                return null;
            });
        }

        private void renameOnEdt(final String newName) {
            Objects.requireNonNull(newName, "name");
            if (newName.isBlank()) {
                throw new IllegalArgumentException("scene name must be non-blank");
            }
            core.requireWriteAuthorization();
            core.requireCurrent();
            final Object sceneDocument = core.sceneDocument(fileContent, sceneSource);
            core.writeWithSceneBasicDataUndo(
                    sceneDocument,
                    sceneSource,
                    "Turboism: Rename Animation Scene",
                    () -> core.resolver.invoke(
                            "cubism.editor-model.scene-source.set-scene-name", sceneSource, newName));
        }

        @Override
        public int playheadFrame() {
            core.requirePlaybackAuthorization();
            core.requireCurrent();
            final Object time = currentTime(firstSceneInstance());
            return core.intValue(
                    core.resolver.invoke("cubism.editor-model.scene-time.frame", time), "Editor animation scene time");
        }

        @Override
        public void seekTo(final int frame) {
            EditorHostThread.dispatch("Cubism animation playback", () -> {
                seekToOnEdt(frame);
                return null;
            });
        }

        private void seekToOnEdt(final int frame) {
            core.requirePlaybackAuthorization();
            core.requireCurrent();
            final List<?> instances = sceneInstances();
            for (Object instance : instances) {
                core.resolver.invoke("cubism.editor-model.scene-time.set-frame", currentTime(instance), frame);
            }
            repaint(core.sceneDocument(fileContent, sceneSource));
        }

        @Override
        public boolean current() {
            core.requireSceneEditAuthorization();
            core.requireCurrent();
            final Object currentDoc =
                    core.resolver.invoke("cubism.editor-model.animation-file-content.current-scene-doc", fileContent);
            return currentDoc != null
                    && core.resolver.invoke("cubism.editor-model.scene-document.scene-source", currentDoc)
                            == sceneSource;
        }

        @Override
        public void activate() {
            EditorHostThread.dispatch("Cubism animation scene activation", () -> {
                activateOnEdt();
                return null;
            });
        }

        private void activateOnEdt() {
            core.requireSceneEditAuthorization();
            core.requireCurrent();
            final Object sceneDocument = core.sceneDocument(fileContent, sceneSource);
            core.resolver.invoke(
                    "cubism.editor-model.animation-file-content.set-current-scene-doc", fileContent, sceneDocument);
            final Object animation =
                    core.resolver.invoke("cubism.editor-model.scene-document.animation", sceneDocument);
            core.resolver.invoke("cubism.editor-model.animation.set-current-scene", animation, sceneSource);
            repaint(sceneDocument);
        }

        @Override
        public AnimationCurveType defaultCurveType() {
            core.requireSceneEditAuthorization();
            core.requireCurrent();
            return core.curveType(
                    core.resolver.invoke("cubism.editor-model.scene-source.default-curve-type", sceneSource));
        }

        @Override
        public void setDefaultCurveType(final AnimationCurveType curveType) {
            EditorHostThread.dispatch("Cubism animation scene write", () -> {
                setDefaultCurveTypeOnEdt(curveType);
                return null;
            });
        }

        private void setDefaultCurveTypeOnEdt(final AnimationCurveType curveType) {
            Objects.requireNonNull(curveType, "curveType");
            core.requireSceneEditAuthorization();
            core.requireCurrent();
            final Object sceneDocument = core.sceneDocument(fileContent, sceneSource);
            core.writeWithSceneBasicDataUndo(
                    sceneDocument,
                    sceneSource,
                    "Turboism: Set Default Curve Type",
                    () -> core.resolver.invoke(
                            "cubism.editor-model.scene-source.set-default-curve-type",
                            sceneSource,
                            core.curveTypeConstant(curveType)));
        }

        private List<?> sceneInstances() {
            final List<?> instances = core.list(
                    core.resolver.invoke("cubism.editor-model.scene-source.scene-instances", sceneSource),
                    "Editor animation scene instances");
            if (instances.isEmpty()) {
                throw new IllegalStateException("Editor animation scene has no live instance.");
            }
            for (Object instance : instances) {
                core.requireInstance(
                        "cubism.editor-model.scene-instance.class",
                        instance,
                        "Editor animation scene instance is invalid.");
            }
            return instances;
        }

        private Object firstSceneInstance() {
            return sceneInstances().get(0);
        }

        private Object currentTime(final Object instance) {
            final Object time = core.resolver.invoke("cubism.editor-model.scene-instance.current-time", instance);
            if (time == null) {
                throw core.unavailable("Editor animation scene time is invalid.");
            }
            return time;
        }

        /** Repaints the canvas through the document's complete pack. */
        private void repaint(final Object sceneDocument) {
            core.resolver.invoke(
                    "cubism.editor-model.complete-pack.repaint-canvas",
                    core.resolver.invoke("cubism.editor-model.scene-document.complete-pack", sceneDocument),
                    Boolean.TRUE);
        }
    }

    static final class ProjectedTrack implements AnimationTrack {

        private final EditorTimelineAccessCore core;
        private final Object trackSource;
        private final Object sceneSource;
        private final Object fileContent;
        private final String guid;
        private final String name;
        private final AnimationTrackKind kind;
        private final int startFrame;
        private final int durationFrames;
        private final List<Integer> keyframeFrames;
        private final boolean visible;
        private final boolean editable;
        private final boolean muted;
        private final boolean repeat;
        private final List<AnimationTrack> children;
        private final List<AnimationAttribute> attributes;
        private final Optional<String> linkedModelGuid;
        private final Optional<String> linkedSceneGuid;

        ProjectedTrack(
                final EditorTimelineAccessCore core,
                final Object trackSource,
                final Object sceneSource,
                final Object fileContent,
                final String guid,
                final String name,
                final AnimationTrackKind kind,
                final int startFrame,
                final int durationFrames,
                final List<Integer> keyframeFrames,
                final boolean visible,
                final boolean editable,
                final boolean muted,
                final boolean repeat,
                final List<AnimationTrack> children,
                final List<AnimationAttribute> attributes,
                final Optional<String> linkedModelGuid,
                final Optional<String> linkedSceneGuid) {
            this.core = core;
            this.trackSource = trackSource;
            this.sceneSource = sceneSource;
            this.fileContent = fileContent;
            this.guid = guid;
            this.name = name;
            this.kind = kind;
            this.startFrame = startFrame;
            this.durationFrames = durationFrames;
            this.keyframeFrames = keyframeFrames;
            this.visible = visible;
            this.editable = editable;
            this.muted = muted;
            this.repeat = repeat;
            this.children = children;
            this.attributes = attributes;
            this.linkedModelGuid = linkedModelGuid;
            this.linkedSceneGuid = linkedSceneGuid;
        }

        @Override
        public String guid() {
            return guid;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public AnimationTrackKind kind() {
            return kind;
        }

        @Override
        public int startFrame() {
            return startFrame;
        }

        @Override
        public int durationFrames() {
            return durationFrames;
        }

        @Override
        public List<Integer> keyframeFrames() {
            return keyframeFrames;
        }

        @Override
        public boolean visible() {
            return visible;
        }

        @Override
        public boolean editable() {
            return editable;
        }

        @Override
        public boolean muted() {
            return muted;
        }

        @Override
        public boolean repeat() {
            return repeat;
        }

        @Override
        public List<AnimationTrack> children() {
            return children;
        }

        @Override
        public List<AnimationAttribute> attributes() {
            return attributes;
        }

        @Override
        public Optional<String> linkedModelGuid() {
            return linkedModelGuid;
        }

        @Override
        public Optional<String> linkedSceneGuid() {
            return linkedSceneGuid;
        }
    }

    static final class ProjectedAttribute implements AnimationAttribute {

        private final EditorTimelineAccessCore core;
        private final Object attribute;
        private final Object trackSource;
        private final Object sceneSource;
        private final Object fileContent;
        private final String id;
        private final String name;
        private final String guid;
        private final String effectId;
        private final Optional<ParameterId> parameterId;
        private final AnimationAttributeKind kind;
        private final boolean active;
        private final boolean editable;
        private final List<AnimationKeyframe> keyframes;

        ProjectedAttribute(
                final EditorTimelineAccessCore core,
                final Object attribute,
                final Object trackSource,
                final Object sceneSource,
                final Object fileContent,
                final String id,
                final String name,
                final String guid,
                final String effectId,
                final Optional<ParameterId> parameterId,
                final AnimationAttributeKind kind,
                final boolean active,
                final boolean editable,
                final List<AnimationKeyframe> keyframes) {
            this.core = core;
            this.attribute = attribute;
            this.trackSource = trackSource;
            this.sceneSource = sceneSource;
            this.fileContent = fileContent;
            this.id = id;
            this.name = name;
            this.guid = guid;
            this.effectId = effectId;
            this.parameterId = parameterId;
            this.kind = kind;
            this.active = active;
            this.editable = editable;
            this.keyframes = keyframes;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String guid() {
            return guid;
        }

        @Override
        public String effectId() {
            return effectId;
        }

        @Override
        public Optional<ParameterId> parameterId() {
            return parameterId;
        }

        @Override
        public AnimationAttributeKind kind() {
            return kind;
        }

        @Override
        public boolean active() {
            return active;
        }

        @Override
        public boolean editable() {
            return editable;
        }

        @Override
        public List<AnimationKeyframe> keyframes() {
            return keyframes;
        }

        @Override
        public void setKeyframe(final int frame, final double value) {
            EditorHostThread.dispatch("Cubism animation keyframe write", () -> {
                setKeyframeOnEdt(frame, value);
                return null;
            });
        }

        private void setKeyframeOnEdt(final int frame, final double value) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("keyframe value must be finite");
            }
            if (kind == AnimationAttributeKind.POINT) {
                throw new IllegalArgumentException("point attributes require x/y keyframe values");
            }
            requireWritableAttribute();
            final Object sceneDocument = core.sceneDocument(fileContent, sceneSource);
            core.write(
                    sceneDocument,
                    attribute,
                    "Turboism: Set Animation Keyframe",
                    () -> core.resolver.invoke("cubism.editor-model.attr.set-value-auto", attribute, frame, value));
        }

        @Override
        public void setKeyframe(final int frame, final double value, final AnimationCurveType curveType) {
            EditorHostThread.dispatch("Cubism animation keyframe write", () -> {
                setKeyframeOnEdt(frame, value, curveType);
                return null;
            });
        }

        private void setKeyframeOnEdt(final int frame, final double value, final AnimationCurveType curveType) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("keyframe value must be finite");
            }
            Objects.requireNonNull(curveType, "curveType");
            if (kind != AnimationAttributeKind.FLOAT) {
                throw new IllegalArgumentException("curve types only apply to float attributes");
            }
            requireWritableAttribute();
            if (core.mutableSequence(attribute) == null) {
                throw new IllegalStateException("Editor animation attribute sequence is not mutable.");
            }
            final Object sceneDocument = core.sceneDocument(fileContent, sceneSource);
            core.write(
                    sceneDocument,
                    attribute,
                    "Turboism: Set Animation Keyframe",
                    () -> core.resolver.invoke(
                            "cubism.editor-model.attr-f.set-value-curve",
                            attribute,
                            frame,
                            value,
                            core.curveTypeConstant(curveType)));
        }

        @Override
        public void setKeyframe(final int frame, final float x, final float y) {
            EditorHostThread.dispatch("Cubism animation keyframe write", () -> {
                setKeyframeOnEdt(frame, x, y);
                return null;
            });
        }

        private void setKeyframeOnEdt(final int frame, final float x, final float y) {
            if (!Float.isFinite(x) || !Float.isFinite(y)) {
                throw new IllegalArgumentException("keyframe coordinates must be finite");
            }
            if (kind != AnimationAttributeKind.POINT) {
                throw new IllegalArgumentException("x/y keyframes only apply to point attributes");
            }
            requireWritableAttribute();
            final Object sceneDocument = core.sceneDocument(fileContent, sceneSource);
            core.write(
                    sceneDocument,
                    attribute,
                    "Turboism: Set Animation Keyframe",
                    () -> core.resolver.invoke("cubism.editor-model.attr-pt.set-value-auto", attribute, frame, x, y));
        }

        @Override
        public void removeKeyframe(final int frame) {
            EditorHostThread.dispatch("Cubism animation keyframe write", () -> {
                removeKeyframeOnEdt(frame);
                return null;
            });
        }

        private void removeKeyframeOnEdt(final int frame) {
            if (!core.keyframeFrames(attribute).contains(frame)) {
                return;
            }
            requireWritableAttribute();
            final Object sceneDocument = core.sceneDocument(fileContent, sceneSource);
            core.write(
                    sceneDocument,
                    attribute,
                    "Turboism: Remove Animation Keyframe",
                    () -> core.resolver.invoke("cubism.editor-model.attr.remove-value-auto", attribute, frame));
        }

        @Override
        public int offsetKeyframes(final int frameDelta) {
            if (frameDelta == 0) {
                return keyframes.size();
            }
            return EditorHostThread.dispatch(
                    "Cubism animation keyframe write",
                    () -> transformKeyframes(
                            frame -> frame + frameDelta, KeyData::shiftedTo, "Turboism: Offset Animation Keyframes"));
        }

        @Override
        public int scaleKeyframeTimes(final double factor, final int originFrame) {
            if (!Double.isFinite(factor) || factor <= 0.0) {
                throw new IllegalArgumentException("scale factor must be positive and finite");
            }
            return EditorHostThread.dispatch(
                    "Cubism animation keyframe write",
                    () -> transformKeyframes(
                            frame -> originFrame + (int) Math.round((frame - originFrame) * factor),
                            (key, newFrame) -> key.scaledTo(newFrame, originFrame, factor),
                            "Turboism: Scale Animation Keyframe Times"));
        }

        @Override
        public int quantizeKeyframes(final int stepFrames) {
            if (stepFrames <= 0) {
                throw new IllegalArgumentException("step must be positive");
            }
            return EditorHostThread.dispatch(
                    "Cubism animation keyframe write",
                    () -> transformKeyframes(
                            frame -> (int) (Math.round(frame / (double) stepFrames) * stepFrames),
                            KeyData::shiftedTo,
                            "Turboism: Quantize Animation Keyframes"));
        }

        @Override
        public int copyKeyframesFrom(final AnimationAttribute source, final boolean replace) {
            return EditorHostThread.dispatch(
                    "Cubism animation keyframe write", () -> copyKeyframesFromOnEdt(source, replace));
        }

        private int copyKeyframesFromOnEdt(final AnimationAttribute source, final boolean replace) {
            Objects.requireNonNull(source, "source");
            if (!(source instanceof ProjectedAttribute projected)) {
                throw new IllegalArgumentException("source attribute must come from an Editor animation timeline");
            }
            if (projected.kind != kind) {
                throw new IllegalArgumentException(
                        "source attribute kind " + projected.kind + " does not match " + kind);
            }
            requireWritableAttribute();
            final List<KeyData> sourceKeys = projected.captureKeys();
            if (sourceKeys.isEmpty() && !replace) {
                return 0;
            }
            final Object sceneDocument = core.sceneDocument(fileContent, sceneSource);
            core.write(
                    sceneDocument,
                    attribute,
                    "Turboism: Copy Animation Keyframes",
                    () -> replaceKeys(sourceKeys, replace));
            return sourceKeys.size();
        }

        @Override
        public int applyCurveType(final AnimationCurveType curveType) {
            Objects.requireNonNull(curveType, "curveType");
            return applyCurveType(curveType, Integer.MIN_VALUE, Integer.MAX_VALUE);
        }

        @Override
        public int applyCurveType(final AnimationCurveType curveType, final int fromFrame, final int toFrame) {
            return EditorHostThread.dispatch(
                    "Cubism animation keyframe write", () -> applyCurveTypeOnEdt(curveType, fromFrame, toFrame));
        }

        private int applyCurveTypeOnEdt(final AnimationCurveType curveType, final int fromFrame, final int toFrame) {
            Objects.requireNonNull(curveType, "curveType");
            if (fromFrame > toFrame) {
                throw new IllegalArgumentException("fromFrame must be <= toFrame");
            }
            if (kind != AnimationAttributeKind.FLOAT) {
                throw new IllegalArgumentException("curve types only apply to float attributes");
            }
            requireWritableAttribute();
            final Object sequence = core.mutableSequence(attribute);
            if (sequence == null) {
                throw new IllegalStateException("Editor animation attribute sequence is not mutable.");
            }
            final List<Integer> targets = new ArrayList<>();
            for (int frame : core.keyframeFrames(attribute)) {
                if (frame >= fromFrame && frame <= toFrame) {
                    targets.add(frame);
                }
            }
            if (targets.isEmpty()) {
                return 0;
            }
            final Object sceneDocument = core.sceneDocument(fileContent, sceneSource);
            core.write(sceneDocument, attribute, "Turboism: Apply Animation Curve Type", () -> {
                for (int frame : targets) {
                    core.resolver.invoke(
                            "cubism.editor-model.mutable-sequence.set-curve-type",
                            sequence,
                            frame,
                            core.curveTypeConstant(curveType));
                }
            });
            return targets.size();
        }

        @Override
        public void recordKeyframe(final int frame, final AnimationCurveType curveType) {
            EditorHostThread.dispatch("Cubism animation keyframe write", () -> {
                recordKeyframeOnEdt(frame, curveType);
                return null;
            });
        }

        private void recordKeyframeOnEdt(final int frame, final AnimationCurveType curveType) {
            Objects.requireNonNull(curveType, "curveType");
            core.requireEvalAuthorization();
            requireWritableAttribute();
            final Object parameterSet = modelParameterSet();
            final double value = evaluatedParameterValue(parameterSet);
            final Object sceneDocument = core.sceneDocument(fileContent, sceneSource);
            core.write(
                    sceneDocument,
                    attribute,
                    "Turboism: Record Animation Keyframe",
                    () -> core.resolver.invoke(
                            "cubism.editor-model.attr-f.set-value-curve",
                            attribute,
                            frame,
                            value,
                            core.curveTypeConstant(curveType)));
        }

        @Override
        public int bakeEvaluated(
                final int fromFrame, final int toFrame, final int stepFrames, final AnimationCurveType curveType) {
            return EditorHostThread.dispatch(
                    "Cubism animation keyframe write",
                    () -> bakeEvaluatedOnEdt(fromFrame, toFrame, stepFrames, curveType));
        }

        private int bakeEvaluatedOnEdt(
                final int fromFrame, final int toFrame, final int stepFrames, final AnimationCurveType curveType) {
            Objects.requireNonNull(curveType, "curveType");
            if (fromFrame > toFrame) {
                throw new IllegalArgumentException("fromFrame must be <= toFrame");
            }
            if (stepFrames <= 0) {
                throw new IllegalArgumentException("step must be positive");
            }
            core.requireEvalAuthorization();
            requireWritableAttribute();
            final Object sceneInstance = firstSceneInstance();
            final Object parameterSet = modelParameterSet(sceneInstance);
            final Object rootTrack =
                    core.resolver.invoke("cubism.editor-model.scene-instance.root-track", sceneInstance);
            final Object time = currentTime(sceneInstance);
            final Object sceneDocument = core.sceneDocument(fileContent, sceneSource);
            final Object viewContext = viewContext(sceneDocument);
            final Object flags = core.resolver.construct("cubism.editor-model.eval-flags.create", Boolean.FALSE);
            final int originalFrame = core.intValue(
                    core.resolver.invoke("cubism.editor-model.scene-time.frame", time), "Editor animation scene time");
            final int[] written = {0};
            core.write(sceneDocument, attribute, "Turboism: Bake Evaluated Keyframes", () -> {
                try {
                    for (long frame = fromFrame; frame <= toFrame; frame += stepFrames) {
                        core.resolver.invoke("cubism.editor-model.scene-time.set-frame", time, (int) frame);
                        core.resolver.invoke(
                                "cubism.editor-model.track-instance.update", rootTrack, viewContext, time, flags);
                        core.resolver.invoke(
                                "cubism.editor-model.attr-f.set-value-curve",
                                attribute,
                                (int) frame,
                                evaluatedParameterValue(parameterSet),
                                core.curveTypeConstant(curveType));
                        written[0]++;
                    }
                } finally {
                    core.resolver.invoke("cubism.editor-model.scene-time.set-frame", time, originalFrame);
                }
            });
            return written[0];
        }

        /** First live instance of the scene this attribute animates. */
        private Object firstSceneInstance() {
            final List<?> instances = core.list(
                    core.resolver.invoke("cubism.editor-model.scene-source.scene-instances", sceneSource),
                    "Editor animation scene instances");
            for (Object instance : instances) {
                if (core.resolver.isInstance("cubism.editor-model.scene-instance.class", instance)) {
                    return instance;
                }
            }
            throw new IllegalStateException("Editor animation scene has no live instance.");
        }

        private Object currentTime(final Object instance) {
            final Object time = core.resolver.invoke("cubism.editor-model.scene-instance.current-time", instance);
            if (time == null) {
                throw core.unavailable("Editor animation scene time is invalid.");
            }
            return time;
        }

        /** Evaluated model parameter set on this attribute's track instance. */
        private Object modelParameterSet() {
            return modelParameterSet(firstSceneInstance());
        }

        private Object modelParameterSet(final Object sceneInstance) {
            if (parameterId.isEmpty()) {
                throw new IllegalStateException("Editor animation attribute " + id + " carries no model parameter.");
            }
            for (Object trackInstance : core.list(
                    core.resolver.invoke("cubism.editor-model.scene-instance.all-tracks", sceneInstance),
                    "Editor animation track instances")) {
                if (!core.resolver.isInstance("cubism.editor-model.track-model-instance.class", trackInstance)) {
                    continue;
                }
                if (core.resolver.invoke("cubism.editor-model.track-instance.source", trackInstance) == trackSource) {
                    return core.resolver.invoke(
                            "cubism.editor-model.track-model-instance.parameter-set", trackInstance);
                }
            }
            throw new IllegalStateException("Editor animation track has no live model instance.");
        }

        /** Current evaluated value of this attribute's bound parameter. */
        private double evaluatedParameterValue(final Object parameterSet) {
            final String wanted = parameterId.orElseThrow().value();
            for (Object parameter : core.list(
                    core.resolver.invoke("cubism.editor-model.parameter-set.parameters", parameterSet),
                    "Editor animation evaluated parameters")) {
                final Object parameterIdValue = core.resolver.invoke("cubism.editor-model.parameter.id", parameter);
                if (wanted.equals(core.text(
                        core.resolver.invoke("cubism.editor-model.id.value", parameterIdValue),
                        "Editor animation parameter id"))) {
                    return core.doubleValue(
                            core.resolver.invoke("cubism.editor-model.parameter.value", parameter),
                            "Editor animation parameter value");
                }
            }
            throw new IllegalStateException("Editor animation parameter " + wanted + " is not evaluated.");
        }

        private Object viewContext(final Object sceneDocument) {
            final List<?> contexts = core.list(
                    core.resolver.invoke("cubism.editor-model.scene-document.view-contexts", sceneDocument),
                    "Editor animation scene view contexts");
            if (contexts.isEmpty()) {
                throw new IllegalStateException("Editor animation scene has no open view context.");
            }
            return contexts.get(0);
        }

        /**
         * Re-keys every frame through {@code mapping} inside one undo step,
         * preserving values, curve types, and stored bezier handles. Handles are
         * remapped by {@code remap}: translations shift them by the keyframe
         * delta while scaling applies the same affine transform as keyframes.
         */
        private int transformKeyframes(
                final java.util.function.IntUnaryOperator mapping, final KeyframeRemap remap, final String label) {
            requireWritableAttribute();
            final List<KeyData> source = captureKeys();
            if (source.isEmpty()) {
                return 0;
            }
            final List<KeyData> mapped = new ArrayList<>(source.size());
            for (KeyData key : source) {
                mapped.add(remap.apply(key, mapping.applyAsInt(key.frame())));
            }
            mapped.sort(Comparator.comparingInt(KeyData::frame));
            final Object sceneDocument = core.sceneDocument(fileContent, sceneSource);
            core.write(sceneDocument, attribute, label, () -> {
                for (KeyData key : source) {
                    core.resolver.invoke("cubism.editor-model.attr.remove-value-auto", attribute, key.frame());
                }
                insertKeys(mapped);
            });
            return mapped.size();
        }

        private void replaceKeys(final List<KeyData> keys, final boolean replace) {
            if (replace) {
                for (int frame : core.keyframeFrames(attribute)) {
                    core.resolver.invoke("cubism.editor-model.attr.remove-value-auto", attribute, frame);
                }
            }
            insertKeys(keys);
        }

        private void insertKeys(final List<KeyData> keys) {
            final boolean handles =
                    kind == AnimationAttributeKind.FLOAT && keys.stream().anyMatch(KeyData::hasHandles);
            for (KeyData key : keys) {
                insertKey(key);
            }
            if (handles) {
                restoreHandles(keys);
                core.resolver.invoke(
                        "cubism.editor-model.mutable-sequence.force-update", core.mutableSequence(attribute));
            }
        }

        private void insertKey(final KeyData key) {
            switch (kind) {
                case FLOAT -> {
                    if (key.curveType() != null) {
                        core.resolver.invoke(
                                "cubism.editor-model.attr-f.set-value-curve",
                                attribute,
                                key.frame(),
                                key.value(),
                                core.curveTypeConstant(key.curveType()));
                    } else {
                        core.resolver.invoke(
                                "cubism.editor-model.attr.set-value-auto", attribute, key.frame(), key.value());
                    }
                }
                case INTEGER ->
                    core.resolver.invoke(
                            "cubism.editor-model.attr-i.set-value-auto", attribute, key.frame(), key.value());
                case POINT ->
                    core.resolver.invoke(
                            "cubism.editor-model.attr-pt.set-value-auto",
                            attribute,
                            key.frame(),
                            key.pointX(),
                            key.pointY());
                default ->
                    throw new IllegalStateException(
                            "Editor animation attribute kind " + kind + " does not carry keys.");
            }
        }

        /** Re-applies stored bezier handles onto the freshly inserted keys. */
        private void restoreHandles(final List<KeyData> keys) {
            final Object sequence = core.mutableSequence(attribute);
            if (sequence == null) {
                return;
            }
            for (KeyData key : keys) {
                if (!key.hasHandles()) {
                    continue;
                }
                final Object point =
                        core.resolver.invoke("cubism.editor-model.mutable-sequence.point", sequence, key.frame());
                if (point == null) {
                    continue;
                }
                core.requireInstance(
                        "cubism.editor-model.bezier-point.class", point, "Editor animation bezier point is invalid.");
                restoreHandle(core.resolver.invoke("cubism.editor-model.bezier-point.prev", point), key.inHandle());
                restoreHandle(core.resolver.invoke("cubism.editor-model.bezier-point.next", point), key.outHandle());
            }
        }

        private void restoreHandle(final Object controlPoint, final HandleData handle) {
            if (controlPoint == null || handle == null) {
                return;
            }
            core.requireInstance(
                    "cubism.editor-model.bezier-ctrl-point.class",
                    controlPoint,
                    "Editor animation bezier control point is invalid.");
            core.resolver.invoke("cubism.editor-model.bezier-ctrl-point.set-pos", controlPoint, handle.frame());
            core.resolver.invoke("cubism.editor-model.bezier-ctrl-point.set-value", controlPoint, handle.value());
            core.resolver.invoke("cubism.editor-model.bezier-ctrl-point.set-corner", controlPoint, handle.corner());
        }

        /** Snapshots this attribute's live keys for move/copy transforms. */
        private List<KeyData> captureKeys() {
            final Object sequence = kind == AnimationAttributeKind.FLOAT ? core.mutableSequence(attribute) : null;
            final List<KeyData> keys = new ArrayList<>();
            for (int frame : core.keyframeFrames(attribute)) {
                final double value;
                final float[] point;
                if (kind == AnimationAttributeKind.POINT) {
                    value = Double.NaN;
                    point = core.pointValueOf(attribute, frame);
                } else {
                    value = core.scalarValue(attribute, frame);
                    point = null;
                }
                final AnimationCurveType curveType;
                final HandleData inHandle;
                final HandleData outHandle;
                if (sequence != null) {
                    curveType = core.curveType(
                            core.resolver.invoke("cubism.editor-model.mutable-sequence.curve-type", sequence, frame));
                    final Object bezier =
                            core.resolver.invoke("cubism.editor-model.mutable-sequence.point", sequence, frame);
                    if (bezier == null) {
                        inHandle = null;
                        outHandle = null;
                    } else {
                        core.requireInstance(
                                "cubism.editor-model.bezier-point.class",
                                bezier,
                                "Editor animation bezier point is invalid.");
                        inHandle =
                                handleData(core, core.resolver.invoke("cubism.editor-model.bezier-point.prev", bezier));
                        outHandle =
                                handleData(core, core.resolver.invoke("cubism.editor-model.bezier-point.next", bezier));
                    }
                } else {
                    curveType = null;
                    inHandle = null;
                    outHandle = null;
                }
                keys.add(new KeyData(
                        frame,
                        value,
                        point == null ? 0.0F : point[0],
                        point == null ? 0.0F : point[1],
                        curveType,
                        inHandle,
                        outHandle));
            }
            return keys;
        }

        private void requireWritableAttribute() {
            core.requireWriteAuthorization();
            core.requireCurrent();
            core.requireInstance("cubism.editor-model.attr.class", attribute, "Editor animation attribute is invalid.");
            if (core.resolver.invoke("cubism.editor-model.attr.track", attribute) != trackSource) {
                throw new IllegalStateException("Editor animation attribute reference is stale for the active scene.");
            }
            if (!core.flag(
                    core.resolver.invoke("cubism.editor-model.attr.editable", attribute),
                    "Editor animation attribute editability")) {
                throw new IllegalStateException("Editor animation attribute is not editable.");
            }
            if (kind == AnimationAttributeKind.FLOAT
                    && core.flag(
                            core.resolver.invoke("cubism.editor-model.attr-f.read-only", attribute),
                            "Editor animation attribute read-only flag")) {
                throw new IllegalStateException("Editor animation attribute is read-only.");
            }
        }
    }

    /** Re-keys one captured keyframe onto {@code newFrame}, remapping handles. */
    interface KeyframeRemap {
        KeyData apply(KeyData key, int newFrame);
    }

    /** Live keyframe snapshot used by move/copy transforms. */
    record KeyData(
            int frame,
            double value,
            float pointX,
            float pointY,
            AnimationCurveType curveType,
            HandleData inHandle,
            HandleData outHandle) {
        boolean hasHandles() {
            return inHandle != null || outHandle != null;
        }

        /** Translates the key and its handles by the same frame delta. */
        KeyData shiftedTo(final int newFrame) {
            final double delta = newFrame - (double) frame;
            return new KeyData(
                    newFrame,
                    value,
                    pointX,
                    pointY,
                    curveType,
                    inHandle == null ? null : inHandle.shifted(delta),
                    outHandle == null ? null : outHandle.shifted(delta));
        }

        /** Scales the key position and its handle times around the origin. */
        KeyData scaledTo(final int newFrame, final int originFrame, final double factor) {
            return new KeyData(
                    newFrame,
                    value,
                    pointX,
                    pointY,
                    curveType,
                    inHandle == null ? null : inHandle.scaled(originFrame, factor),
                    outHandle == null ? null : outHandle.scaled(originFrame, factor));
        }
    }

    /** Stored bezier control point values used to rebuild handles. */
    record HandleData(float frame, double value, boolean corner) {
        HandleData shifted(final double delta) {
            return new HandleData((float) (frame + delta), value, corner);
        }

        HandleData scaled(final int originFrame, final double factor) {
            return new HandleData((float) (originFrame + ((double) frame - originFrame) * factor), value, corner);
        }
    }
}
