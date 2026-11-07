package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.AnimationAttribute;
import dev.turboism.sdk.cubism.model.AnimationAttributeKind;
import dev.turboism.sdk.cubism.model.AnimationBezierHandle;
import dev.turboism.sdk.cubism.model.AnimationCurveType;
import dev.turboism.sdk.cubism.model.AnimationKeyframe;
import dev.turboism.sdk.cubism.model.AnimationScene;
import dev.turboism.sdk.cubism.model.AnimationTrack;
import dev.turboism.sdk.cubism.model.AnimationTrackKind;
import dev.turboism.sdk.cubism.model.Point2;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Verified projection of animation scene timelines: track trees, effect
 * attributes, keyframe curves, and undo-wrapped writes.
 *
 * <p>Every member read goes through the verified resolver; the caller decides
 * read admission through {@code authorizesFeature} with the timeline read
 * contract and degrades to a shallow document when the exact evidence is
 * absent. Writes additionally require the timeline edit capability and run
 * inside the native {@code ACEditMode} undo envelope: {@code beginEdit} →
 * {@code SimpleUndo} snapshot → {@code GroupUndo.addEdit} → mutation →
 * {@code endEdit}.</p>
 *
 * <p>This class resolves the scene/track/attribute tree and hands it to the projections in
 * {@link EditorTimelineProjections}; the host plumbing those projections write through lives in
 * {@link EditorTimelineAccessCore}.</p>
 */
final class EditorAnimationTimelineAccess {

    private static final int MAX_TRACK_DEPTH = 64;
    private static final String PARAMETER_ATTRIBUTE_PREFIX = "live2dParam_";

    private final VerifiedMemberResolver resolver;
    private final EditorTimelineAccessCore core;

    EditorAnimationTimelineAccess(
            final VerifiedMemberResolver resolver,
            final EditorParameterCombinedAccess.ModelGuard modelGuard,
            final String identity,
            final Object model) {
        this(resolver, modelGuard, identity, model, null);
    }

    EditorAnimationTimelineAccess(
            final VerifiedMemberResolver resolver,
            final EditorParameterCombinedAccess.ModelGuard modelGuard,
            final String identity,
            final Object model,
            final dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringTransactionCoordinator
                    authoringCoordinator) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.core = new EditorTimelineAccessCore(resolver, modelGuard, identity, model, authoringCoordinator);
    }

    List<AnimationScene> scenes(final List<?> sceneSources, final Object fileContent) {
        final List<AnimationScene> scenes = new ArrayList<>(sceneSources.size());
        for (Object sceneSource : sceneSources) {
            scenes.add(scene(sceneSource, fileContent));
        }
        return List.copyOf(scenes);
    }

    // ------------------------------------------------------------------
    // scene projection
    // ------------------------------------------------------------------

    private AnimationScene scene(final Object sceneSource, final Object fileContent) {
        core.requireInstance(
                "cubism.editor-model.scene-source.class", sceneSource, "Editor animation scene is invalid.");
        final String name = core.text(
                core.resolver.invoke("cubism.editor-model.scene-source.scene-name", sceneSource),
                "Editor animation scene name");
        final String guid = core.guidValue(
                core.resolver.invoke("cubism.editor-model.scene-source.guid", sceneSource),
                "Editor animation scene guid");
        final Object rawTag = core.resolver.invoke("cubism.editor-model.scene-source.tag", sceneSource);
        if (rawTag != null && !(rawTag instanceof String)) {
            throw core.unavailable("Editor animation scene tag is invalid.");
        }
        final Optional<String> tag =
                rawTag instanceof String text && !text.isBlank() ? Optional.of(text) : Optional.empty();
        final Object rawMarkers = core.resolver.invoke("cubism.editor-model.scene-source.marker", sceneSource);
        final Object movieInfo = core.resolver.invoke("cubism.editor-model.scene-source.movie-info", sceneSource);
        core.requireInstance(
                "cubism.editor-model.movie-info.class", movieInfo, "Editor animation scene movie info is invalid.");
        final int startFrame = core.intValue(
                core.resolver.invoke("cubism.editor-model.movie-info.start-frame", movieInfo),
                "Editor animation scene start frame");
        final int durationFrames = core.intValue(
                core.resolver.invoke("cubism.editor-model.movie-info.duration", movieInfo),
                "Editor animation scene duration");
        final double framesPerSecond = core.doubleValue(
                core.resolver.invoke("cubism.editor-model.movie-info.fps", movieInfo), "Editor animation scene fps");
        final int width = core.intValue(
                core.resolver.invoke("cubism.editor-model.movie-info.width", movieInfo),
                "Editor animation scene width");
        final int height = core.intValue(
                core.resolver.invoke("cubism.editor-model.movie-info.height", movieInfo),
                "Editor animation scene height");
        final boolean loopMotion = core.flag(
                core.resolver.invoke("cubism.editor-model.movie-info.loop-motion", movieInfo),
                "Editor animation scene loop flag");
        final int workspaceStart = core.intValue(
                core.resolver.invoke("cubism.editor-model.movie-info.workspace-start", movieInfo),
                "Editor animation scene work-area start");
        final int workspaceEnd = core.intValue(
                core.resolver.invoke("cubism.editor-model.movie-info.workspace-end", movieInfo),
                "Editor animation scene work-area end");
        final Object rootTrack = core.resolver.invoke("cubism.editor-model.scene-source.root-track", sceneSource);
        final Map<Integer, String> markers = markers(rawMarkers);
        final List<AnimationTrack> tracks = tracks(rootTrack, sceneSource, fileContent);
        return new EditorTimelineProjections.ProjectedScene(
                core,
                sceneSource,
                fileContent,
                name,
                guid,
                tag,
                markers,
                startFrame,
                durationFrames,
                framesPerSecond,
                width,
                height,
                loopMotion,
                workspaceStart,
                workspaceEnd,
                tracks);
    }

    private Map<Integer, String> markers(final Object rawMarkers) {
        if (rawMarkers == null) {
            return Map.of();
        }
        if (!(rawMarkers instanceof Map<?, ?> map)) {
            throw core.unavailable("Editor animation scene markers are invalid.");
        }
        final Map<Integer, String> markers = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof Integer frame) || !(entry.getValue() instanceof String label)) {
                throw core.unavailable("Editor animation scene marker is invalid.");
            }
            markers.put(frame, label);
        }
        return Map.copyOf(markers);
    }

    // ------------------------------------------------------------------
    // track projection
    // ------------------------------------------------------------------

    private List<AnimationTrack> tracks(final Object rootTrack, final Object sceneSource, final Object fileContent) {
        core.requireInstance(
                "cubism.editor-model.track-group.class", rootTrack, "Editor animation root track is invalid.");
        final List<AnimationTrack> tracks = new ArrayList<>();
        for (Object child : core.list(
                core.resolver.invoke("cubism.editor-model.track-group.children", rootTrack),
                "Editor animation root track children")) {
            tracks.add(track(child, sceneSource, fileContent, 1));
        }
        return List.copyOf(tracks);
    }

    private AnimationTrack track(
            final Object trackSource, final Object sceneSource, final Object fileContent, final int depth) {
        if (depth > MAX_TRACK_DEPTH) {
            throw core.unavailable("Editor animation track tree exceeds the supported depth.");
        }
        core.requireInstance(
                "cubism.editor-model.track-source.class", trackSource, "Editor animation track is invalid.");
        final String guid = core.guidValue(
                core.resolver.invoke("cubism.editor-model.track-source.guid", trackSource),
                "Editor animation track guid");
        final String name = core.text(
                core.resolver.invoke("cubism.editor-model.track-source.name", trackSource),
                "Editor animation track name");
        final int startFrame = core.intValue(
                core.resolver.invoke("cubism.editor-model.track-source.start", trackSource),
                "Editor animation track start");
        final int durationFrames = core.intValue(
                core.resolver.invoke("cubism.editor-model.track-source.duration", trackSource),
                "Editor animation track duration");
        final boolean visible = core.flag(
                core.resolver.invoke("cubism.editor-model.track-source.visible", trackSource),
                "Editor animation track visibility");
        final boolean editable = core.flag(
                core.resolver.invoke("cubism.editor-model.track-source.editable", trackSource),
                "Editor animation track editability");
        final boolean muted = core.flag(
                core.resolver.invoke("cubism.editor-model.track-source.mute", trackSource),
                "Editor animation track mute");
        final boolean repeat = core.flag(
                core.resolver.invoke("cubism.editor-model.track-source.repeat", trackSource),
                "Editor animation track repeat");
        final List<Integer> keyframeFrames = core.intArray(
                core.resolver.invoke("cubism.editor-model.track-source.key-frames", trackSource),
                "Editor animation track keyframes");
        final AnimationTrackKind kind = trackKind(trackSource);
        final List<AnimationTrack> children;
        if (kind == AnimationTrackKind.GROUP) {
            final List<AnimationTrack> nested = new ArrayList<>();
            for (Object child : core.list(
                    core.resolver.invoke("cubism.editor-model.track-group.children", trackSource),
                    "Editor animation track children")) {
                nested.add(track(child, sceneSource, fileContent, depth + 1));
            }
            children = List.copyOf(nested);
        } else {
            children = List.of();
        }
        final List<AnimationAttribute> attributes = attributes(trackSource, sceneSource, fileContent);
        final Optional<String> linkedModelGuid = linkedGuid(
                trackSource,
                "cubism.editor-model.track-model.class",
                "cubism.editor-model.track-model.model",
                "cubism.editor-model.model-source.guid",
                "Editor animation linked model guid");
        final Optional<String> linkedSceneGuid = linkedGuid(
                trackSource,
                "cubism.editor-model.track-scene.class",
                "cubism.editor-model.track-scene.resource-scene-guid",
                null,
                "Editor animation linked scene guid");
        return new EditorTimelineProjections.ProjectedTrack(
                core,
                trackSource,
                sceneSource,
                fileContent,
                guid,
                name,
                kind,
                startFrame,
                durationFrames,
                keyframeFrames,
                visible,
                editable,
                muted,
                repeat,
                children,
                attributes,
                linkedModelGuid,
                linkedSceneGuid);
    }

    private AnimationTrackKind trackKind(final Object trackSource) {
        if (core.resolver.isInstance("cubism.editor-model.track-group.class", trackSource)) {
            return AnimationTrackKind.GROUP;
        }
        if (core.resolver.isInstance("cubism.editor-model.track-model.class", trackSource)) {
            return AnimationTrackKind.LIVE2D_MODEL;
        }
        if (core.resolver.isInstance("cubism.editor-model.track-scene.class", trackSource)) {
            return AnimationTrackKind.SCENE;
        }
        if (core.resolver.isInstance("cubism.editor-model.track-image.class", trackSource)) {
            return AnimationTrackKind.IMAGE;
        }
        if (core.resolver.isInstance("cubism.editor-model.track-guide-image.class", trackSource)) {
            return AnimationTrackKind.GUIDE_IMAGE;
        }
        if (core.resolver.isInstance("cubism.editor-model.track-text.class", trackSource)) {
            return AnimationTrackKind.TEXT;
        }
        if (core.resolver.isInstance("cubism.editor-model.track-moc3.class", trackSource)) {
            return AnimationTrackKind.MOC3;
        }
        if (core.resolver.isInstance("cubism.editor-model.track-sound.class", trackSource)) {
            return AnimationTrackKind.SOUND;
        }
        return AnimationTrackKind.OTHER;
    }

    private Optional<String> linkedGuid(
            final Object trackSource,
            final String trackClassAlias,
            final String linkAlias,
            final String linkedGuidAlias,
            final String label) {
        if (!core.resolver.isInstance(trackClassAlias, trackSource)) {
            return Optional.empty();
        }
        final Object linked = core.resolver.invoke(linkAlias, trackSource);
        if (linked == null) {
            return Optional.empty();
        }
        final Object rawGuid = linkedGuidAlias == null ? linked : core.resolver.invoke(linkedGuidAlias, linked);
        return Optional.of(core.guidValue(rawGuid, label));
    }

    // ------------------------------------------------------------------
    // attribute projection
    // ------------------------------------------------------------------

    private List<AnimationAttribute> attributes(
            final Object trackSource, final Object sceneSource, final Object fileContent) {
        final Object effectManager =
                core.resolver.invoke("cubism.editor-model.track-source.effect-manager", trackSource);
        if (effectManager == null) {
            return List.of();
        }
        core.requireInstance(
                "cubism.editor-model.effect-manager.class",
                effectManager,
                "Editor animation effect manager is invalid.");
        final List<AnimationAttribute> attributes = new ArrayList<>();
        for (Object effect : core.objectArray(
                core.resolver.invoke("cubism.editor-model.effect-manager.effects", effectManager),
                "Editor animation effects")) {
            core.requireInstance("cubism.editor-model.effect.class", effect, "Editor animation effect is invalid.");
            final boolean parameterEffect =
                    core.resolver.isInstance("cubism.editor-model.effect-parameter.class", effect);
            final String effectId = core.text(
                    core.resolver.invoke(
                            "cubism.editor-model.id.value",
                            core.resolver.invoke("cubism.editor-model.effect.id", effect)),
                    "Editor animation effect id");
            for (Object attribute : core.objectArray(
                    core.resolver.invoke("cubism.editor-model.effect.attrs", effect),
                    "Editor animation effect attributes")) {
                attributes.add(attribute(attribute, effectId, parameterEffect, trackSource, sceneSource, fileContent));
            }
        }
        return List.copyOf(attributes);
    }

    private AnimationAttribute attribute(
            final Object attribute,
            final String effectId,
            final boolean parameterEffect,
            final Object trackSource,
            final Object sceneSource,
            final Object fileContent) {
        core.requireInstance("cubism.editor-model.attr.class", attribute, "Editor animation attribute is invalid.");
        final String id = core.text(
                core.resolver.invoke(
                        "cubism.editor-model.id.value", core.resolver.invoke("cubism.editor-model.attr.id", attribute)),
                "Editor animation attribute id");
        final String name = core.text(
                core.resolver.invoke("cubism.editor-model.attr.name", attribute), "Editor animation attribute name");
        final String guid = core.guidValue(
                core.resolver.invoke("cubism.editor-model.attr.guid", attribute), "Editor animation attribute guid");
        final boolean active = core.flag(
                core.resolver.invoke("cubism.editor-model.attr.active", attribute),
                "Editor animation attribute active flag");
        final boolean editable = core.flag(
                core.resolver.invoke("cubism.editor-model.attr.editable", attribute),
                "Editor animation attribute editability");
        final AnimationAttributeKind kind = attributeKind(attribute);
        final Optional<ParameterId> parameterId = parameterEffect
                        && id.startsWith(PARAMETER_ATTRIBUTE_PREFIX)
                        && id.length() > PARAMETER_ATTRIBUTE_PREFIX.length()
                ? Optional.of(new ParameterId(id.substring(PARAMETER_ATTRIBUTE_PREFIX.length())))
                : Optional.empty();
        final List<AnimationKeyframe> projected = List.copyOf(keyframes(attribute, kind));
        return new EditorTimelineProjections.ProjectedAttribute(
                core,
                attribute,
                trackSource,
                sceneSource,
                fileContent,
                id,
                name,
                guid,
                effectId,
                parameterId,
                kind,
                active,
                editable,
                projected);
    }

    private List<AnimationKeyframe> keyframes(final Object attribute, final AnimationAttributeKind kind) {
        final int[] keyFrames = core.rawIntArray(
                core.resolver.invoke("cubism.editor-model.attr.key-frames", attribute),
                "Editor animation attribute keyframes");
        final Object sequence = kind == AnimationAttributeKind.FLOAT
                ? core.resolver.invoke("cubism.editor-model.attr-f.value-data", attribute)
                : null;
        final boolean mutableSequence =
                sequence != null && core.resolver.isInstance("cubism.editor-model.mutable-sequence.class", sequence);
        final List<AnimationKeyframe> keyframes = new ArrayList<>(keyFrames.length);
        for (int frame : keyFrames) {
            keyframes.add(keyframe(attribute, sequence, mutableSequence, kind, frame));
        }
        return keyframes;
    }

    private AnimationAttributeKind attributeKind(final Object attribute) {
        if (core.resolver.isInstance("cubism.editor-model.attr-f.class", attribute)) {
            return AnimationAttributeKind.FLOAT;
        }
        if (core.resolver.isInstance("cubism.editor-model.attr-i.class", attribute)) {
            return AnimationAttributeKind.INTEGER;
        }
        if (core.resolver.isInstance("cubism.editor-model.attr-pt.class", attribute)) {
            return AnimationAttributeKind.POINT;
        }
        return AnimationAttributeKind.OTHER;
    }

    private AnimationKeyframe keyframe(
            final Object attribute,
            final Object sequence,
            final boolean mutableSequence,
            final AnimationAttributeKind kind,
            final int frame) {
        final Object rawValue = core.resolver.invoke("cubism.editor-model.attr.value", attribute, frame);
        final OptionalDouble value;
        final Optional<Point2> pointValue;
        if (kind == AnimationAttributeKind.POINT
                && core.resolver.isInstance("cubism.editor-model.vector2.class", rawValue)) {
            value = OptionalDouble.empty();
            pointValue = Optional.of(new Point2(
                    core.floatValue(core.resolver.invoke("cubism.editor-model.vector2.x", rawValue), "Editor point X"),
                    core.floatValue(
                            core.resolver.invoke("cubism.editor-model.vector2.y", rawValue), "Editor point Y")));
        } else if (rawValue instanceof Number number) {
            value = OptionalDouble.of(number.doubleValue());
            pointValue = Optional.empty();
        } else {
            value = OptionalDouble.empty();
            pointValue = Optional.empty();
        }
        final Optional<AnimationCurveType> curveType;
        final Optional<AnimationBezierHandle> inHandle;
        final Optional<AnimationBezierHandle> outHandle;
        if (mutableSequence) {
            curveType = Optional.of(core.curveType(
                    core.resolver.invoke("cubism.editor-model.mutable-sequence.curve-type", sequence, frame)));
            final Object point = core.resolver.invoke("cubism.editor-model.mutable-sequence.point", sequence, frame);
            if (point == null) {
                inHandle = Optional.empty();
                outHandle = Optional.empty();
            } else {
                core.requireInstance(
                        "cubism.editor-model.bezier-point.class", point, "Editor animation bezier point is invalid.");
                inHandle = EditorTimelineProjections.bezierHandle(
                        core, core.resolver.invoke("cubism.editor-model.bezier-point.prev", point));
                outHandle = EditorTimelineProjections.bezierHandle(
                        core, core.resolver.invoke("cubism.editor-model.bezier-point.next", point));
            }
        } else {
            curveType = Optional.empty();
            inHandle = Optional.empty();
            outHandle = Optional.empty();
        }
        return new AnimationKeyframe() {
            @Override
            public int frame() {
                return frame;
            }

            @Override
            public OptionalDouble value() {
                return value;
            }

            @Override
            public Optional<Point2> pointValue() {
                return pointValue;
            }

            @Override
            public Optional<AnimationCurveType> curveType() {
                return curveType;
            }

            @Override
            public Optional<AnimationBezierHandle> inHandle() {
                return inHandle;
            }

            @Override
            public Optional<AnimationBezierHandle> outHandle() {
                return outHandle;
            }
        };
    }
}
