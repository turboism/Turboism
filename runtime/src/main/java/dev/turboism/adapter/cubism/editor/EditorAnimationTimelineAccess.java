package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorAnimationSceneOperationSelectorContract;
import dev.turboism.mapping.verification.selector.EditorAnimationTimelineEditSelectorContract;
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
import java.util.Comparator;
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
 */
final class EditorAnimationTimelineAccess {

    private static final int MAX_TRACK_DEPTH = 64;
    private static final String PARAMETER_ATTRIBUTE_PREFIX = "live2dParam_";

    private final VerifiedMemberResolver resolver;
    private final EditorParameterCombinedAccess.ModelGuard modelGuard;
    private final String identity;
    private final Object model;

    EditorAnimationTimelineAccess(
        final VerifiedMemberResolver resolver,
        final EditorParameterCombinedAccess.ModelGuard modelGuard,
        final String identity,
        final Object model
    ) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.modelGuard = Objects.requireNonNull(modelGuard, "modelGuard");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.model = Objects.requireNonNull(model, "model");
    }

    private boolean writeAuthorized() {
        return resolver.authorizesFeature(
            EditorAnimationTimelineEditSelectorContract.ADAPTER_SLICE_ID,
            EditorAnimationTimelineEditSelectorContract.WRITE_CAPABILITY_ID,
            EditorAnimationTimelineEditSelectorContract.WRITE_REQUIRED_ALIASES
        );
    }

    private void requireWriteAuthorization() {
        if (!writeAuthorized()) {
            throw new UnsupportedOperationException(
                "Animation timeline writing is unavailable without exact verified host evidence."
            );
        }
    }

    private void requirePlaybackAuthorization() {
        if (!resolver.authorizesFeature(
            EditorAnimationSceneOperationSelectorContract.ADAPTER_SLICE_ID,
            EditorAnimationSceneOperationSelectorContract.PLAYBACK_CAPABILITY_ID,
            EditorAnimationSceneOperationSelectorContract.PLAYBACK_REQUIRED_ALIASES
        )) {
            throw new UnsupportedOperationException(
                "Animation playback control is unavailable without exact verified host evidence."
            );
        }
    }

    private void requireSceneEditAuthorization() {
        if (!resolver.authorizesFeature(
            EditorAnimationSceneOperationSelectorContract.ADAPTER_SLICE_ID,
            EditorAnimationSceneOperationSelectorContract.SCENE_EDIT_CAPABILITY_ID,
            EditorAnimationSceneOperationSelectorContract.SCENE_EDIT_REQUIRED_ALIASES
        )) {
            throw new UnsupportedOperationException(
                "Animation scene management is unavailable without exact verified host evidence."
            );
        }
    }

    private void requireEvalAuthorization() {
        if (!resolver.authorizesFeature(
            EditorAnimationSceneOperationSelectorContract.ADAPTER_SLICE_ID,
            EditorAnimationSceneOperationSelectorContract.EVAL_CAPABILITY_ID,
            EditorAnimationSceneOperationSelectorContract.EVAL_REQUIRED_ALIASES
        )) {
            throw new UnsupportedOperationException(
                "Animation evaluated writing is unavailable without exact verified host evidence."
            );
        }
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
        requireInstance(
            "cubism.editor-model.scene-source.class",
            sceneSource,
            "Editor animation scene is invalid."
        );
        final String name = text(
            resolver.invoke("cubism.editor-model.scene-source.scene-name", sceneSource),
            "Editor animation scene name"
        );
        final String guid = guidValue(
            resolver.invoke("cubism.editor-model.scene-source.guid", sceneSource),
            "Editor animation scene guid"
        );
        final Object rawTag = resolver.invoke(
            "cubism.editor-model.scene-source.tag", sceneSource
        );
        if (rawTag != null && !(rawTag instanceof String)) {
            throw unavailable("Editor animation scene tag is invalid.");
        }
        final Optional<String> tag = rawTag instanceof String text && !text.isBlank()
            ? Optional.of(text)
            : Optional.empty();
        final Object rawMarkers = resolver.invoke(
            "cubism.editor-model.scene-source.marker", sceneSource
        );
        final Object movieInfo = resolver.invoke(
            "cubism.editor-model.scene-source.movie-info", sceneSource
        );
        requireInstance(
            "cubism.editor-model.movie-info.class",
            movieInfo,
            "Editor animation scene movie info is invalid."
        );
        final int startFrame = intValue(
            resolver.invoke("cubism.editor-model.movie-info.start-frame", movieInfo),
            "Editor animation scene start frame"
        );
        final int durationFrames = intValue(
            resolver.invoke("cubism.editor-model.movie-info.duration", movieInfo),
            "Editor animation scene duration"
        );
        final double framesPerSecond = doubleValue(
            resolver.invoke("cubism.editor-model.movie-info.fps", movieInfo),
            "Editor animation scene fps"
        );
        final int width = intValue(
            resolver.invoke("cubism.editor-model.movie-info.width", movieInfo),
            "Editor animation scene width"
        );
        final int height = intValue(
            resolver.invoke("cubism.editor-model.movie-info.height", movieInfo),
            "Editor animation scene height"
        );
        final boolean loopMotion = flag(
            resolver.invoke("cubism.editor-model.movie-info.loop-motion", movieInfo),
            "Editor animation scene loop flag"
        );
        final int workspaceStart = intValue(
            resolver.invoke("cubism.editor-model.movie-info.workspace-start", movieInfo),
            "Editor animation scene work-area start"
        );
        final int workspaceEnd = intValue(
            resolver.invoke("cubism.editor-model.movie-info.workspace-end", movieInfo),
            "Editor animation scene work-area end"
        );
        final Object rootTrack = resolver.invoke(
            "cubism.editor-model.scene-source.root-track", sceneSource
        );
        final Map<Integer, String> markers = markers(rawMarkers);
        final List<AnimationTrack> tracks = tracks(rootTrack, sceneSource, fileContent);
        return new ProjectedScene(
            sceneSource, fileContent,
            name, guid, tag, markers, startFrame, durationFrames, framesPerSecond,
            width, height, loopMotion, workspaceStart, workspaceEnd, tracks
        );
    }

    private final class ProjectedScene implements AnimationScene {
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

        private ProjectedScene(
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
            final List<AnimationTrack> tracks
        ) {
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

        @Override public String name() { return name; }
        @Override public String guid() { return guid; }
        @Override public Optional<String> tag() { return tag; }
        @Override public Map<Integer, String> markers() { return markers; }
        @Override public int startFrame() { return startFrame; }
        @Override public int durationFrames() { return durationFrames; }
        @Override public double framesPerSecond() { return framesPerSecond; }
        @Override public int width() { return width; }
        @Override public int height() { return height; }
        @Override public boolean loopMotion() { return loopMotion; }
        @Override public int workspaceStartFrame() { return workspaceStartFrame; }
        @Override public int workspaceEndFrame() { return workspaceEndFrame; }
        @Override public List<AnimationTrack> tracks() { return tracks; }

        @Override public void rename(final String newName) {
            Objects.requireNonNull(newName, "name");
            if (newName.isBlank()) {
                throw new IllegalArgumentException("scene name must be non-blank");
            }
            requireWriteAuthorization();
            modelGuard.requireCurrent(identity, model);
            final Object sceneDocument = sceneDocument(fileContent, sceneSource);
            writeWithSceneBasicDataUndo(sceneDocument, sceneSource,
                "Turboism: Rename Animation Scene",
                () -> resolver.invoke(
                    "cubism.editor-model.scene-source.set-scene-name",
                    sceneSource, newName
                ));
        }

        @Override public int playheadFrame() {
            requirePlaybackAuthorization();
            modelGuard.requireCurrent(identity, model);
            final Object time = currentTime(firstSceneInstance());
            return intValue(
                resolver.invoke("cubism.editor-model.scene-time.frame", time),
                "Editor animation scene time"
            );
        }

        @Override public void seekTo(final int frame) {
            requirePlaybackAuthorization();
            modelGuard.requireCurrent(identity, model);
            final List<?> instances = sceneInstances();
            for (Object instance : instances) {
                resolver.invoke(
                    "cubism.editor-model.scene-time.set-frame",
                    currentTime(instance), frame
                );
            }
            repaint(sceneDocument(fileContent, sceneSource));
        }

        @Override public boolean current() {
            requireSceneEditAuthorization();
            modelGuard.requireCurrent(identity, model);
            final Object currentDoc = resolver.invoke(
                "cubism.editor-model.animation-file-content.current-scene-doc",
                fileContent
            );
            return currentDoc != null
                && resolver.invoke(
                    "cubism.editor-model.scene-document.scene-source", currentDoc
                ) == sceneSource;
        }

        @Override public void activate() {
            requireSceneEditAuthorization();
            modelGuard.requireCurrent(identity, model);
            final Object sceneDocument = sceneDocument(fileContent, sceneSource);
            resolver.invoke(
                "cubism.editor-model.animation-file-content.set-current-scene-doc",
                fileContent, sceneDocument
            );
            final Object animation = resolver.invoke(
                "cubism.editor-model.scene-document.animation", sceneDocument
            );
            resolver.invoke(
                "cubism.editor-model.animation.set-current-scene",
                animation, sceneSource
            );
            repaint(sceneDocument);
        }

        @Override public AnimationCurveType defaultCurveType() {
            requireSceneEditAuthorization();
            modelGuard.requireCurrent(identity, model);
            return curveType(resolver.invoke(
                "cubism.editor-model.scene-source.default-curve-type", sceneSource
            ));
        }

        @Override public void setDefaultCurveType(final AnimationCurveType curveType) {
            Objects.requireNonNull(curveType, "curveType");
            requireSceneEditAuthorization();
            modelGuard.requireCurrent(identity, model);
            final Object sceneDocument = sceneDocument(fileContent, sceneSource);
            writeWithSceneBasicDataUndo(sceneDocument, sceneSource,
                "Turboism: Set Default Curve Type",
                () -> resolver.invoke(
                    "cubism.editor-model.scene-source.set-default-curve-type",
                    sceneSource, curveTypeConstant(curveType)
                ));
        }

        private List<?> sceneInstances() {
            final List<?> instances = list(
                resolver.invoke(
                    "cubism.editor-model.scene-source.scene-instances", sceneSource
                ),
                "Editor animation scene instances"
            );
            if (instances.isEmpty()) {
                throw new IllegalStateException(
                    "Editor animation scene has no live instance.");
            }
            for (Object instance : instances) {
                requireInstance(
                    "cubism.editor-model.scene-instance.class",
                    instance,
                    "Editor animation scene instance is invalid."
                );
            }
            return instances;
        }

        private Object firstSceneInstance() {
            return sceneInstances().get(0);
        }

        private Object currentTime(final Object instance) {
            final Object time = resolver.invoke(
                "cubism.editor-model.scene-instance.current-time", instance
            );
            if (time == null) {
                throw unavailable("Editor animation scene time is invalid.");
            }
            return time;
        }

        /** Repaints the canvas through the document's complete pack. */
        private void repaint(final Object sceneDocument) {
            resolver.invoke(
                "cubism.editor-model.complete-pack.repaint-canvas",
                resolver.invoke(
                    "cubism.editor-model.scene-document.complete-pack", sceneDocument
                ),
                Boolean.TRUE
            );
        }
    }

    private Map<Integer, String> markers(final Object rawMarkers) {
        if (rawMarkers == null) {
            return Map.of();
        }
        if (!(rawMarkers instanceof Map<?, ?> map)) {
            throw unavailable("Editor animation scene markers are invalid.");
        }
        final Map<Integer, String> markers = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof Integer frame)
                || !(entry.getValue() instanceof String label)) {
                throw unavailable("Editor animation scene marker is invalid.");
            }
            markers.put(frame, label);
        }
        return Map.copyOf(markers);
    }

    // ------------------------------------------------------------------
    // track projection
    // ------------------------------------------------------------------

    private List<AnimationTrack> tracks(
        final Object rootTrack,
        final Object sceneSource,
        final Object fileContent
    ) {
        requireInstance(
            "cubism.editor-model.track-group.class",
            rootTrack,
            "Editor animation root track is invalid."
        );
        final List<AnimationTrack> tracks = new ArrayList<>();
        for (Object child : list(
            resolver.invoke("cubism.editor-model.track-group.children", rootTrack),
            "Editor animation root track children"
        )) {
            tracks.add(track(child, sceneSource, fileContent, 1));
        }
        return List.copyOf(tracks);
    }

    private AnimationTrack track(
        final Object trackSource,
        final Object sceneSource,
        final Object fileContent,
        final int depth
    ) {
        if (depth > MAX_TRACK_DEPTH) {
            throw unavailable("Editor animation track tree exceeds the supported depth.");
        }
        requireInstance(
            "cubism.editor-model.track-source.class",
            trackSource,
            "Editor animation track is invalid."
        );
        final String guid = guidValue(
            resolver.invoke("cubism.editor-model.track-source.guid", trackSource),
            "Editor animation track guid"
        );
        final String name = text(
            resolver.invoke("cubism.editor-model.track-source.name", trackSource),
            "Editor animation track name"
        );
        final int startFrame = intValue(
            resolver.invoke("cubism.editor-model.track-source.start", trackSource),
            "Editor animation track start"
        );
        final int durationFrames = intValue(
            resolver.invoke("cubism.editor-model.track-source.duration", trackSource),
            "Editor animation track duration"
        );
        final boolean visible = flag(
            resolver.invoke("cubism.editor-model.track-source.visible", trackSource),
            "Editor animation track visibility"
        );
        final boolean editable = flag(
            resolver.invoke("cubism.editor-model.track-source.editable", trackSource),
            "Editor animation track editability"
        );
        final boolean muted = flag(
            resolver.invoke("cubism.editor-model.track-source.mute", trackSource),
            "Editor animation track mute"
        );
        final boolean repeat = flag(
            resolver.invoke("cubism.editor-model.track-source.repeat", trackSource),
            "Editor animation track repeat"
        );
        final List<Integer> keyframeFrames = intArray(
            resolver.invoke("cubism.editor-model.track-source.key-frames", trackSource),
            "Editor animation track keyframes"
        );
        final AnimationTrackKind kind = trackKind(trackSource);
        final List<AnimationTrack> children;
        if (kind == AnimationTrackKind.GROUP) {
            final List<AnimationTrack> nested = new ArrayList<>();
            for (Object child : list(
                resolver.invoke("cubism.editor-model.track-group.children", trackSource),
                "Editor animation track children"
            )) {
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
            "Editor animation linked model guid"
        );
        final Optional<String> linkedSceneGuid = linkedGuid(
            trackSource,
            "cubism.editor-model.track-scene.class",
            "cubism.editor-model.track-scene.resource-scene-guid",
            null,
            "Editor animation linked scene guid"
        );
        return new ProjectedTrack(
            trackSource, sceneSource, fileContent,
            guid, name, kind, startFrame, durationFrames, keyframeFrames,
            visible, editable, muted, repeat, children, attributes,
            linkedModelGuid, linkedSceneGuid
        );
    }

    private final class ProjectedTrack implements AnimationTrack {
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

        private ProjectedTrack(
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
            final Optional<String> linkedSceneGuid
        ) {
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

        @Override public String guid() { return guid; }
        @Override public String name() { return name; }
        @Override public AnimationTrackKind kind() { return kind; }
        @Override public int startFrame() { return startFrame; }
        @Override public int durationFrames() { return durationFrames; }
        @Override public List<Integer> keyframeFrames() { return keyframeFrames; }
        @Override public boolean visible() { return visible; }
        @Override public boolean editable() { return editable; }
        @Override public boolean muted() { return muted; }
        @Override public boolean repeat() { return repeat; }
        @Override public List<AnimationTrack> children() { return children; }
        @Override public List<AnimationAttribute> attributes() { return attributes; }
        @Override public Optional<String> linkedModelGuid() { return linkedModelGuid; }
        @Override public Optional<String> linkedSceneGuid() { return linkedSceneGuid; }
    }

    private AnimationTrackKind trackKind(final Object trackSource) {
        if (resolver.isInstance("cubism.editor-model.track-group.class", trackSource)) {
            return AnimationTrackKind.GROUP;
        }
        if (resolver.isInstance("cubism.editor-model.track-model.class", trackSource)) {
            return AnimationTrackKind.LIVE2D_MODEL;
        }
        if (resolver.isInstance("cubism.editor-model.track-scene.class", trackSource)) {
            return AnimationTrackKind.SCENE;
        }
        if (resolver.isInstance("cubism.editor-model.track-image.class", trackSource)) {
            return AnimationTrackKind.IMAGE;
        }
        if (resolver.isInstance("cubism.editor-model.track-guide-image.class", trackSource)) {
            return AnimationTrackKind.GUIDE_IMAGE;
        }
        if (resolver.isInstance("cubism.editor-model.track-text.class", trackSource)) {
            return AnimationTrackKind.TEXT;
        }
        if (resolver.isInstance("cubism.editor-model.track-moc3.class", trackSource)) {
            return AnimationTrackKind.MOC3;
        }
        if (resolver.isInstance("cubism.editor-model.track-sound.class", trackSource)) {
            return AnimationTrackKind.SOUND;
        }
        return AnimationTrackKind.OTHER;
    }

    private Optional<String> linkedGuid(
        final Object trackSource,
        final String trackClassAlias,
        final String linkAlias,
        final String linkedGuidAlias,
        final String label
    ) {
        if (!resolver.isInstance(trackClassAlias, trackSource)) {
            return Optional.empty();
        }
        final Object linked = resolver.invoke(linkAlias, trackSource);
        if (linked == null) {
            return Optional.empty();
        }
        final Object rawGuid = linkedGuidAlias == null
            ? linked
            : resolver.invoke(linkedGuidAlias, linked);
        return Optional.of(guidValue(rawGuid, label));
    }

    // ------------------------------------------------------------------
    // attribute projection
    // ------------------------------------------------------------------

    private List<AnimationAttribute> attributes(
        final Object trackSource,
        final Object sceneSource,
        final Object fileContent
    ) {
        final Object effectManager = resolver.invoke(
            "cubism.editor-model.track-source.effect-manager", trackSource
        );
        if (effectManager == null) {
            return List.of();
        }
        requireInstance(
            "cubism.editor-model.effect-manager.class",
            effectManager,
            "Editor animation effect manager is invalid."
        );
        final List<AnimationAttribute> attributes = new ArrayList<>();
        for (Object effect : objectArray(
            resolver.invoke("cubism.editor-model.effect-manager.effects", effectManager),
            "Editor animation effects"
        )) {
            requireInstance(
                "cubism.editor-model.effect.class",
                effect,
                "Editor animation effect is invalid."
            );
            final boolean parameterEffect = resolver.isInstance(
                "cubism.editor-model.effect-parameter.class", effect
            );
            final String effectId = text(
                resolver.invoke(
                    "cubism.editor-model.id.value",
                    resolver.invoke("cubism.editor-model.effect.id", effect)
                ),
                "Editor animation effect id"
            );
            for (Object attribute : objectArray(
                resolver.invoke("cubism.editor-model.effect.attrs", effect),
                "Editor animation effect attributes"
            )) {
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
        final Object fileContent
    ) {
        requireInstance(
            "cubism.editor-model.attr.class",
            attribute,
            "Editor animation attribute is invalid."
        );
        final String id = text(
            resolver.invoke(
                "cubism.editor-model.id.value",
                resolver.invoke("cubism.editor-model.attr.id", attribute)
            ),
            "Editor animation attribute id"
        );
        final String name = text(
            resolver.invoke("cubism.editor-model.attr.name", attribute),
            "Editor animation attribute name"
        );
        final String guid = guidValue(
            resolver.invoke("cubism.editor-model.attr.guid", attribute),
            "Editor animation attribute guid"
        );
        final boolean active = flag(
            resolver.invoke("cubism.editor-model.attr.active", attribute),
            "Editor animation attribute active flag"
        );
        final boolean editable = flag(
            resolver.invoke("cubism.editor-model.attr.editable", attribute),
            "Editor animation attribute editability"
        );
        final AnimationAttributeKind kind = attributeKind(attribute);
        final Optional<ParameterId> parameterId = parameterEffect
            && id.startsWith(PARAMETER_ATTRIBUTE_PREFIX)
            && id.length() > PARAMETER_ATTRIBUTE_PREFIX.length()
            ? Optional.of(new ParameterId(id.substring(PARAMETER_ATTRIBUTE_PREFIX.length())))
            : Optional.empty();
        final List<AnimationKeyframe> projected = List.copyOf(keyframes(attribute, kind));
        return new ProjectedAttribute(
            attribute, trackSource, sceneSource, fileContent,
            id, name, guid, effectId, parameterId, kind, active, editable, projected
        );
    }

    private List<AnimationKeyframe> keyframes(
        final Object attribute,
        final AnimationAttributeKind kind
    ) {
        final int[] keyFrames = rawIntArray(
            resolver.invoke("cubism.editor-model.attr.key-frames", attribute),
            "Editor animation attribute keyframes"
        );
        final Object sequence = kind == AnimationAttributeKind.FLOAT
            ? resolver.invoke("cubism.editor-model.attr-f.value-data", attribute)
            : null;
        final boolean mutableSequence = sequence != null
            && resolver.isInstance("cubism.editor-model.mutable-sequence.class", sequence);
        final List<AnimationKeyframe> keyframes = new ArrayList<>(keyFrames.length);
        for (int frame : keyFrames) {
            keyframes.add(keyframe(attribute, sequence, mutableSequence, kind, frame));
        }
        return keyframes;
    }

    private final class ProjectedAttribute implements AnimationAttribute {
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

        private ProjectedAttribute(
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
            final List<AnimationKeyframe> keyframes
        ) {
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

        @Override public String id() { return id; }
        @Override public String name() { return name; }
        @Override public String guid() { return guid; }
        @Override public String effectId() { return effectId; }
        @Override public Optional<ParameterId> parameterId() { return parameterId; }
        @Override public AnimationAttributeKind kind() { return kind; }
        @Override public boolean active() { return active; }
        @Override public boolean editable() { return editable; }
        @Override public List<AnimationKeyframe> keyframes() { return keyframes; }

        @Override public void setKeyframe(final int frame, final double value) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("keyframe value must be finite");
            }
            if (kind == AnimationAttributeKind.POINT) {
                throw new IllegalArgumentException(
                    "point attributes require x/y keyframe values");
            }
            requireWritableAttribute();
            final Object sceneDocument = sceneDocument(fileContent, sceneSource);
            write(sceneDocument, attribute, "Turboism: Set Animation Keyframe",
                () -> resolver.invoke(
                    "cubism.editor-model.attr.set-value-auto",
                    attribute, frame, value
                ));
        }

        @Override public void setKeyframe(
            final int frame,
            final double value,
            final AnimationCurveType curveType
        ) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("keyframe value must be finite");
            }
            Objects.requireNonNull(curveType, "curveType");
            if (kind != AnimationAttributeKind.FLOAT) {
                throw new IllegalArgumentException(
                    "curve types only apply to float attributes");
            }
            requireWritableAttribute();
            if (mutableSequence(attribute) == null) {
                throw new IllegalStateException(
                    "Editor animation attribute sequence is not mutable.");
            }
            final Object sceneDocument = sceneDocument(fileContent, sceneSource);
            write(sceneDocument, attribute, "Turboism: Set Animation Keyframe",
                () -> resolver.invoke(
                    "cubism.editor-model.attr-f.set-value-curve",
                    attribute, frame, value, curveTypeConstant(curveType)
                ));
        }

        @Override public void setKeyframe(final int frame, final float x, final float y) {
            if (!Float.isFinite(x) || !Float.isFinite(y)) {
                throw new IllegalArgumentException("keyframe coordinates must be finite");
            }
            if (kind != AnimationAttributeKind.POINT) {
                throw new IllegalArgumentException(
                    "x/y keyframes only apply to point attributes");
            }
            requireWritableAttribute();
            final Object sceneDocument = sceneDocument(fileContent, sceneSource);
            write(sceneDocument, attribute, "Turboism: Set Animation Keyframe",
                () -> resolver.invoke(
                    "cubism.editor-model.attr-pt.set-value-auto",
                    attribute, frame, x, y
                ));
        }

        @Override public void removeKeyframe(final int frame) {
            if (!keyframeFrames(attribute).contains(frame)) {
                return;
            }
            requireWritableAttribute();
            final Object sceneDocument = sceneDocument(fileContent, sceneSource);
            write(sceneDocument, attribute, "Turboism: Remove Animation Keyframe",
                () -> resolver.invoke(
                    "cubism.editor-model.attr.remove-value-auto",
                    attribute, frame
                ));
        }

        @Override public int offsetKeyframes(final int frameDelta) {
            if (frameDelta == 0) {
                return keyframes.size();
            }
            return transformKeyframes(frame -> frame + frameDelta,
                KeyData::shiftedTo,
                "Turboism: Offset Animation Keyframes");
        }

        @Override public int scaleKeyframeTimes(final double factor, final int originFrame) {
            if (!Double.isFinite(factor) || factor <= 0.0) {
                throw new IllegalArgumentException("scale factor must be positive and finite");
            }
            return transformKeyframes(
                frame -> originFrame + (int) Math.round((frame - originFrame) * factor),
                (key, newFrame) -> key.scaledTo(newFrame, originFrame, factor),
                "Turboism: Scale Animation Keyframe Times"
            );
        }

        @Override public int quantizeKeyframes(final int stepFrames) {
            if (stepFrames <= 0) {
                throw new IllegalArgumentException("step must be positive");
            }
            return transformKeyframes(
                frame -> (int) (Math.round(frame / (double) stepFrames) * stepFrames),
                KeyData::shiftedTo,
                "Turboism: Quantize Animation Keyframes"
            );
        }

        @Override public int copyKeyframesFrom(
            final AnimationAttribute source,
            final boolean replace
        ) {
            Objects.requireNonNull(source, "source");
            if (!(source instanceof ProjectedAttribute projected)) {
                throw new IllegalArgumentException(
                    "source attribute must come from an Editor animation timeline");
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
            final Object sceneDocument = sceneDocument(fileContent, sceneSource);
            write(sceneDocument, attribute, "Turboism: Copy Animation Keyframes",
                () -> replaceKeys(sourceKeys, replace));
            return sourceKeys.size();
        }

        @Override public int applyCurveType(final AnimationCurveType curveType) {
            Objects.requireNonNull(curveType, "curveType");
            return applyCurveType(curveType, Integer.MIN_VALUE, Integer.MAX_VALUE);
        }

        @Override public int applyCurveType(
            final AnimationCurveType curveType,
            final int fromFrame,
            final int toFrame
        ) {
            Objects.requireNonNull(curveType, "curveType");
            if (fromFrame > toFrame) {
                throw new IllegalArgumentException("fromFrame must be <= toFrame");
            }
            if (kind != AnimationAttributeKind.FLOAT) {
                throw new IllegalArgumentException(
                    "curve types only apply to float attributes");
            }
            requireWritableAttribute();
            final Object sequence = mutableSequence(attribute);
            if (sequence == null) {
                throw new IllegalStateException(
                    "Editor animation attribute sequence is not mutable.");
            }
            final List<Integer> targets = new ArrayList<>();
            for (int frame : keyframeFrames(attribute)) {
                if (frame >= fromFrame && frame <= toFrame) {
                    targets.add(frame);
                }
            }
            if (targets.isEmpty()) {
                return 0;
            }
            final Object sceneDocument = sceneDocument(fileContent, sceneSource);
            write(sceneDocument, attribute, "Turboism: Apply Animation Curve Type",
                () -> {
                    for (int frame : targets) {
                        resolver.invoke(
                            "cubism.editor-model.mutable-sequence.set-curve-type",
                            sequence, frame, curveTypeConstant(curveType)
                        );
                    }
                });
            return targets.size();
        }

        @Override public void recordKeyframe(
            final int frame,
            final AnimationCurveType curveType
        ) {
            Objects.requireNonNull(curveType, "curveType");
            requireEvalAuthorization();
            requireWritableAttribute();
            final Object parameterSet = modelParameterSet();
            final double value = evaluatedParameterValue(parameterSet);
            final Object sceneDocument = sceneDocument(fileContent, sceneSource);
            write(sceneDocument, attribute, "Turboism: Record Animation Keyframe",
                () -> resolver.invoke(
                    "cubism.editor-model.attr-f.set-value-curve",
                    attribute, frame, value, curveTypeConstant(curveType)
                ));
        }

        @Override public int bakeEvaluated(
            final int fromFrame,
            final int toFrame,
            final int stepFrames,
            final AnimationCurveType curveType
        ) {
            Objects.requireNonNull(curveType, "curveType");
            if (fromFrame > toFrame) {
                throw new IllegalArgumentException("fromFrame must be <= toFrame");
            }
            if (stepFrames <= 0) {
                throw new IllegalArgumentException("step must be positive");
            }
            requireEvalAuthorization();
            requireWritableAttribute();
            final Object sceneInstance = firstSceneInstance();
            final Object parameterSet = modelParameterSet(sceneInstance);
            final Object rootTrack = resolver.invoke(
                "cubism.editor-model.scene-instance.root-track", sceneInstance
            );
            final Object time = currentTime(sceneInstance);
            final Object sceneDocument = sceneDocument(fileContent, sceneSource);
            final Object viewContext = viewContext(sceneDocument);
            final Object flags = resolver.construct(
                "cubism.editor-model.eval-flags.create", Boolean.FALSE
            );
            final int originalFrame = intValue(
                resolver.invoke("cubism.editor-model.scene-time.frame", time),
                "Editor animation scene time"
            );
            final int[] written = {0};
            write(sceneDocument, attribute, "Turboism: Bake Evaluated Keyframes",
                () -> {
                    try {
                        for (long frame = fromFrame; frame <= toFrame; frame += stepFrames) {
                            resolver.invoke(
                                "cubism.editor-model.scene-time.set-frame",
                                time, (int) frame
                            );
                            resolver.invoke(
                                "cubism.editor-model.track-instance.update",
                                rootTrack, viewContext, time, flags
                            );
                            resolver.invoke(
                                "cubism.editor-model.attr-f.set-value-curve",
                                attribute, (int) frame,
                                evaluatedParameterValue(parameterSet),
                                curveTypeConstant(curveType)
                            );
                            written[0]++;
                        }
                    } finally {
                        resolver.invoke(
                            "cubism.editor-model.scene-time.set-frame",
                            time, originalFrame
                        );
                    }
                });
            return written[0];
        }

        /** First live instance of the scene this attribute animates. */
        private Object firstSceneInstance() {
            final List<?> instances = list(
                resolver.invoke(
                    "cubism.editor-model.scene-source.scene-instances", sceneSource
                ),
                "Editor animation scene instances"
            );
            for (Object instance : instances) {
                if (resolver.isInstance(
                    "cubism.editor-model.scene-instance.class", instance)) {
                    return instance;
                }
            }
            throw new IllegalStateException(
                "Editor animation scene has no live instance.");
        }

        private Object currentTime(final Object instance) {
            final Object time = resolver.invoke(
                "cubism.editor-model.scene-instance.current-time", instance
            );
            if (time == null) {
                throw unavailable("Editor animation scene time is invalid.");
            }
            return time;
        }

        /** Evaluated model parameter set on this attribute's track instance. */
        private Object modelParameterSet() {
            return modelParameterSet(firstSceneInstance());
        }

        private Object modelParameterSet(final Object sceneInstance) {
            if (parameterId.isEmpty()) {
                throw new IllegalStateException(
                    "Editor animation attribute " + id + " carries no model parameter.");
            }
            for (Object trackInstance : list(
                resolver.invoke(
                    "cubism.editor-model.scene-instance.all-tracks", sceneInstance
                ),
                "Editor animation track instances"
            )) {
                if (!resolver.isInstance(
                    "cubism.editor-model.track-model-instance.class", trackInstance)) {
                    continue;
                }
                if (resolver.invoke(
                    "cubism.editor-model.track-instance.source", trackInstance
                ) == trackSource) {
                    return resolver.invoke(
                        "cubism.editor-model.track-model-instance.parameter-set",
                        trackInstance
                    );
                }
            }
            throw new IllegalStateException(
                "Editor animation track has no live model instance.");
        }

        /** Current evaluated value of this attribute's bound parameter. */
        private double evaluatedParameterValue(final Object parameterSet) {
            final String wanted = parameterId.orElseThrow().value();
            for (Object parameter : list(
                resolver.invoke(
                    "cubism.editor-model.parameter-set.parameters", parameterSet
                ),
                "Editor animation evaluated parameters"
            )) {
                final Object parameterIdValue = resolver.invoke(
                    "cubism.editor-model.parameter.id", parameter
                );
                if (wanted.equals(text(
                    resolver.invoke("cubism.editor-model.id.value", parameterIdValue),
                    "Editor animation parameter id"
                ))) {
                    return doubleValue(
                        resolver.invoke("cubism.editor-model.parameter.value", parameter),
                        "Editor animation parameter value"
                    );
                }
            }
            throw new IllegalStateException(
                "Editor animation parameter " + wanted + " is not evaluated.");
        }

        private Object viewContext(final Object sceneDocument) {
            final List<?> contexts = list(
                resolver.invoke(
                    "cubism.editor-model.scene-document.view-contexts", sceneDocument
                ),
                "Editor animation scene view contexts"
            );
            if (contexts.isEmpty()) {
                throw new IllegalStateException(
                    "Editor animation scene has no open view context.");
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
            final java.util.function.IntUnaryOperator mapping,
            final KeyframeRemap remap,
            final String label
        ) {
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
            final Object sceneDocument = sceneDocument(fileContent, sceneSource);
            write(sceneDocument, attribute, label, () -> {
                for (KeyData key : source) {
                    resolver.invoke(
                        "cubism.editor-model.attr.remove-value-auto",
                        attribute, key.frame()
                    );
                }
                insertKeys(mapped);
            });
            return mapped.size();
        }

        private void replaceKeys(final List<KeyData> keys, final boolean replace) {
            if (replace) {
                for (int frame : keyframeFrames(attribute)) {
                    resolver.invoke(
                        "cubism.editor-model.attr.remove-value-auto",
                        attribute, frame
                    );
                }
            }
            insertKeys(keys);
        }

        private void insertKeys(final List<KeyData> keys) {
            final boolean handles = kind == AnimationAttributeKind.FLOAT
                && keys.stream().anyMatch(KeyData::hasHandles);
            for (KeyData key : keys) {
                insertKey(key);
            }
            if (handles) {
                restoreHandles(keys);
                resolver.invoke(
                    "cubism.editor-model.mutable-sequence.force-update",
                    mutableSequence(attribute)
                );
            }
        }

        private void insertKey(final KeyData key) {
            switch (kind) {
                case FLOAT -> {
                    if (key.curveType() != null) {
                        resolver.invoke(
                            "cubism.editor-model.attr-f.set-value-curve",
                            attribute, key.frame(), key.value(),
                            curveTypeConstant(key.curveType())
                        );
                    } else {
                        resolver.invoke(
                            "cubism.editor-model.attr.set-value-auto",
                            attribute, key.frame(), key.value()
                        );
                    }
                }
                case INTEGER -> resolver.invoke(
                    "cubism.editor-model.attr-i.set-value-auto",
                    attribute, key.frame(), key.value()
                );
                case POINT -> resolver.invoke(
                    "cubism.editor-model.attr-pt.set-value-auto",
                    attribute, key.frame(), key.pointX(), key.pointY()
                );
                default -> throw new IllegalStateException(
                    "Editor animation attribute kind " + kind + " does not carry keys.");
            }
        }

        /** Re-applies stored bezier handles onto the freshly inserted keys. */
        private void restoreHandles(final List<KeyData> keys) {
            final Object sequence = mutableSequence(attribute);
            if (sequence == null) {
                return;
            }
            for (KeyData key : keys) {
                if (!key.hasHandles()) {
                    continue;
                }
                final Object point = resolver.invoke(
                    "cubism.editor-model.mutable-sequence.point", sequence, key.frame()
                );
                if (point == null) {
                    continue;
                }
                requireInstance(
                    "cubism.editor-model.bezier-point.class",
                    point,
                    "Editor animation bezier point is invalid."
                );
                restoreHandle(
                    resolver.invoke("cubism.editor-model.bezier-point.prev", point),
                    key.inHandle()
                );
                restoreHandle(
                    resolver.invoke("cubism.editor-model.bezier-point.next", point),
                    key.outHandle()
                );
            }
        }

        private void restoreHandle(final Object controlPoint, final HandleData handle) {
            if (controlPoint == null || handle == null) {
                return;
            }
            requireInstance(
                "cubism.editor-model.bezier-ctrl-point.class",
                controlPoint,
                "Editor animation bezier control point is invalid."
            );
            resolver.invoke(
                "cubism.editor-model.bezier-ctrl-point.set-pos",
                controlPoint, handle.frame()
            );
            resolver.invoke(
                "cubism.editor-model.bezier-ctrl-point.set-value",
                controlPoint, handle.value()
            );
            resolver.invoke(
                "cubism.editor-model.bezier-ctrl-point.set-corner",
                controlPoint, handle.corner()
            );
        }

        /** Snapshots this attribute's live keys for move/copy transforms. */
        private List<KeyData> captureKeys() {
            final Object sequence = kind == AnimationAttributeKind.FLOAT
                ? mutableSequence(attribute)
                : null;
            final List<KeyData> keys = new ArrayList<>();
            for (int frame : keyframeFrames(attribute)) {
                final double value;
                final float[] point;
                if (kind == AnimationAttributeKind.POINT) {
                    value = Double.NaN;
                    point = pointValueOf(attribute, frame);
                } else {
                    value = scalarValue(attribute, frame);
                    point = null;
                }
                final AnimationCurveType curveType;
                final HandleData inHandle;
                final HandleData outHandle;
                if (sequence != null) {
                    curveType = curveType(
                        resolver.invoke(
                            "cubism.editor-model.mutable-sequence.curve-type",
                            sequence, frame
                        )
                    );
                    final Object bezier = resolver.invoke(
                        "cubism.editor-model.mutable-sequence.point", sequence, frame
                    );
                    if (bezier == null) {
                        inHandle = null;
                        outHandle = null;
                    } else {
                        requireInstance(
                            "cubism.editor-model.bezier-point.class",
                            bezier,
                            "Editor animation bezier point is invalid."
                        );
                        inHandle = handleData(
                            resolver.invoke("cubism.editor-model.bezier-point.prev", bezier));
                        outHandle = handleData(
                            resolver.invoke("cubism.editor-model.bezier-point.next", bezier));
                    }
                } else {
                    curveType = null;
                    inHandle = null;
                    outHandle = null;
                }
                keys.add(new KeyData(
                    frame, value,
                    point == null ? 0.0F : point[0],
                    point == null ? 0.0F : point[1],
                    curveType, inHandle, outHandle
                ));
            }
            return keys;
        }

        private void requireWritableAttribute() {
            requireWriteAuthorization();
            modelGuard.requireCurrent(identity, model);
            requireInstance(
                "cubism.editor-model.attr.class",
                attribute,
                "Editor animation attribute is invalid."
            );
            if (resolver.invoke("cubism.editor-model.attr.track", attribute) != trackSource) {
                throw new IllegalStateException(
                    "Editor animation attribute reference is stale for the active scene.");
            }
            if (!flag(
                resolver.invoke("cubism.editor-model.attr.editable", attribute),
                "Editor animation attribute editability"
            )) {
                throw new IllegalStateException(
                    "Editor animation attribute is not editable.");
            }
            if (kind == AnimationAttributeKind.FLOAT
                && flag(
                    resolver.invoke("cubism.editor-model.attr-f.read-only", attribute),
                    "Editor animation attribute read-only flag"
                )) {
                throw new IllegalStateException(
                    "Editor animation attribute is read-only.");
            }
        }
    }

    /** Re-keys one captured keyframe onto {@code newFrame}, remapping handles. */
    @FunctionalInterface
    private interface KeyframeRemap {
        KeyData apply(KeyData key, int newFrame);
    }

    /** Live keyframe snapshot used by move/copy transforms. */
    private record KeyData(
        int frame,
        double value,
        float pointX,
        float pointY,
        AnimationCurveType curveType,
        HandleData inHandle,
        HandleData outHandle
    ) {
        boolean hasHandles() {
            return inHandle != null || outHandle != null;
        }

        /** Translates the key and its handles by the same frame delta. */
        KeyData shiftedTo(final int newFrame) {
            final double delta = newFrame - (double) frame;
            return new KeyData(
                newFrame, value, pointX, pointY, curveType,
                inHandle == null ? null : inHandle.shifted(delta),
                outHandle == null ? null : outHandle.shifted(delta)
            );
        }

        /** Scales the key position and its handle times around the origin. */
        KeyData scaledTo(final int newFrame, final int originFrame, final double factor) {
            return new KeyData(
                newFrame, value, pointX, pointY, curveType,
                inHandle == null ? null : inHandle.scaled(originFrame, factor),
                outHandle == null ? null : outHandle.scaled(originFrame, factor)
            );
        }
    }

    /** Stored bezier control point values used to rebuild handles. */
    private record HandleData(float frame, double value, boolean corner) {
        HandleData shifted(final double delta) {
            return new HandleData((float) (frame + delta), value, corner);
        }

        HandleData scaled(final int originFrame, final double factor) {
            return new HandleData(
                (float) (originFrame + ((double) frame - originFrame) * factor),
                value,
                corner
            );
        }
    }

    private AnimationAttributeKind attributeKind(final Object attribute) {
        if (resolver.isInstance("cubism.editor-model.attr-f.class", attribute)) {
            return AnimationAttributeKind.FLOAT;
        }
        if (resolver.isInstance("cubism.editor-model.attr-i.class", attribute)) {
            return AnimationAttributeKind.INTEGER;
        }
        if (resolver.isInstance("cubism.editor-model.attr-pt.class", attribute)) {
            return AnimationAttributeKind.POINT;
        }
        return AnimationAttributeKind.OTHER;
    }

    private AnimationKeyframe keyframe(
        final Object attribute,
        final Object sequence,
        final boolean mutableSequence,
        final AnimationAttributeKind kind,
        final int frame
    ) {
        final Object rawValue = resolver.invoke("cubism.editor-model.attr.value", attribute, frame);
        final OptionalDouble value;
        final Optional<Point2> pointValue;
        if (kind == AnimationAttributeKind.POINT
            && resolver.isInstance("cubism.editor-model.vector2.class", rawValue)) {
            value = OptionalDouble.empty();
            pointValue = Optional.of(new Point2(
                floatValue(resolver.invoke("cubism.editor-model.vector2.x", rawValue), "Editor point X"),
                floatValue(resolver.invoke("cubism.editor-model.vector2.y", rawValue), "Editor point Y")
            ));
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
            curveType = Optional.of(curveType(
                resolver.invoke("cubism.editor-model.mutable-sequence.curve-type", sequence, frame)
            ));
            final Object point = resolver.invoke(
                "cubism.editor-model.mutable-sequence.point", sequence, frame
            );
            if (point == null) {
                inHandle = Optional.empty();
                outHandle = Optional.empty();
            } else {
                requireInstance(
                    "cubism.editor-model.bezier-point.class",
                    point,
                    "Editor animation bezier point is invalid."
                );
                inHandle = bezierHandle(
                    resolver.invoke("cubism.editor-model.bezier-point.prev", point)
                );
                outHandle = bezierHandle(
                    resolver.invoke("cubism.editor-model.bezier-point.next", point)
                );
            }
        } else {
            curveType = Optional.empty();
            inHandle = Optional.empty();
            outHandle = Optional.empty();
        }
        return new AnimationKeyframe() {
            @Override public int frame() { return frame; }
            @Override public OptionalDouble value() { return value; }
            @Override public Optional<Point2> pointValue() { return pointValue; }
            @Override public Optional<AnimationCurveType> curveType() { return curveType; }
            @Override public Optional<AnimationBezierHandle> inHandle() { return inHandle; }
            @Override public Optional<AnimationBezierHandle> outHandle() { return outHandle; }
        };
    }

    // ------------------------------------------------------------------
    // write envelope
    // ------------------------------------------------------------------

    /** Resolves the live scene document owning {@code sceneSource}. */
    private Object sceneDocument(final Object fileContent, final Object sceneSource) {
        requireInstance(
            "cubism.editor-model.animation-file-content.class",
            fileContent,
            "Editor animation file content is invalid."
        );
        for (Object candidate : list(
            resolver.invoke(
                "cubism.editor-model.animation-file-content.scene-docs", fileContent
            ),
            "Editor animation scene documents"
        )) {
            if (resolver.isInstance("cubism.editor-model.scene-document.class", candidate)
                && resolver.invoke(
                    "cubism.editor-model.scene-document.scene-source", candidate
                ) == sceneSource) {
                return candidate;
            }
        }
        throw unavailable("Editor animation scene document is stale.");
    }

    /**
     * Runs {@code mutation} inside the scene document's native Undo envelope:
     * the {@code SimpleUndo} constructor snapshots {@code undoTarget} before the
     * mutation runs, the entry joins the edit group, and the scene edit mode
     * commits it as one undo step.
     */
    /**
     * Same Undo envelope as {@link #write} but the entry is the scene handler's
     * basic-data DTO ({@code SceneSourceBasicData_forUndo}) — the shape Cubism
     * itself uses for scene name/tag/curve-type edits. A whole-scene
     * {@code SimpleUndo} deep copy is both heavier and unsafe here.
     */
    private void writeWithSceneBasicDataUndo(
        final Object sceneDocument,
        final Object sceneSource,
        final String label,
        final Runnable mutation
    ) {
        write(sceneDocument, label,
            () -> resolver.invoke(
                "cubism.editor-model.scene-handler.basic-undo",
                resolver.construct(
                    "cubism.editor-model.scene-handler.create", sceneSource
                ),
                label
            ),
            mutation);
    }

    private void write(
        final Object sceneDocument,
        final Object undoTarget,
        final String label,
        final Runnable mutation
    ) {
        write(sceneDocument, label,
            () -> resolver.construct(
                "cubism.editor-model.simple-undo.create", label, undoTarget, null
            ),
            mutation);
    }

    private void write(
        final Object sceneDocument,
        final String label,
        final java.util.function.Supplier<Object> undoFactory,
        final Runnable mutation
    ) {
        final Object editMode = resolver.invoke(
            "cubism.editor-model.scene-document.current-edit-mode", sceneDocument
        );
        if (editMode == null) {
            throw unavailable("Editor animation scene edit mode is unavailable.");
        }
        final Object completePack = resolver.invoke(
            "cubism.editor-model.scene-document.complete-pack", sceneDocument
        );
        final Object edit = resolver.invoke(
            "cubism.editor-model.edit-mode-base.begin", editMode, label
        );
        boolean completed = false;
        try {
            final Object undo = undoFactory.get();
            final Object accepted = resolver.invoke(
                "cubism.editor-model.undo.add", edit, undo, Boolean.TRUE
            );
            if (!(accepted instanceof Boolean acceptedValue) || !acceptedValue) {
                throw new IllegalStateException(
                    "Cubism rejected the animation timeline Undo entry.");
            }
            final Object listener = resolver.createFunctionalProxy(
                "cubism.editor-model.undo-listener.class",
                ignored -> {
                    resolver.invoke(
                        "cubism.editor-model.complete-pack.update-project", completePack);
                    resolver.invoke(
                        "cubism.editor-model.complete-pack.repaint-canvas",
                        completePack, Boolean.TRUE
                    );
                    return null;
                }
            );
            resolver.invoke("cubism.editor-model.undo.add-listener", undo, listener);
            mutation.run();
            resolver.invoke(
                "cubism.editor-model.complete-pack.update-project", completePack);
            resolver.invoke(
                "cubism.editor-model.complete-pack.repaint-canvas",
                completePack, Boolean.TRUE
            );
            resolver.invoke(
                "cubism.editor-model.scene-document.update-modified", sceneDocument);
            completed = true;
        } finally {
            resolver.invoke(
                "cubism.editor-model.edit-mode-base.end",
                editMode, Boolean.valueOf(!completed), null
            );
        }
    }

    // ------------------------------------------------------------------
    // shared helpers
    // ------------------------------------------------------------------

    private Object mutableSequence(final Object attribute) {
        final Object sequence = resolver.invoke(
            "cubism.editor-model.attr-f.value-data", attribute
        );
        return sequence != null
            && resolver.isInstance("cubism.editor-model.mutable-sequence.class", sequence)
            ? sequence
            : null;
    }

    private List<Integer> keyframeFrames(final Object attribute) {
        return intArray(
            resolver.invoke("cubism.editor-model.attr.key-frames", attribute),
            "Editor animation attribute keyframes"
        );
    }

    private Object curveTypeConstant(final AnimationCurveType curveType) {
        return resolver.readStaticField(switch (curveType) {
            case LINEAR -> "cubism.editor-model.curve-type.linear";
            case BEZIER -> "cubism.editor-model.curve-type.bezier";
            case SMOOTH -> "cubism.editor-model.curve-type.smooth";
            case STEP -> "cubism.editor-model.curve-type.step";
            case INVERSE_STEP -> "cubism.editor-model.curve-type.inverse-step";
        });
    }

    private double scalarValue(final Object attribute, final int frame) {
        final Object rawValue = resolver.invoke(
            "cubism.editor-model.attr.value", attribute, frame
        );
        if (rawValue instanceof Number number) {
            return number.doubleValue();
        }
        throw unavailable("Editor animation keyframe value is invalid.");
    }

    private float[] pointValueOf(final Object attribute, final int frame) {
        final Object rawValue = resolver.invoke(
            "cubism.editor-model.attr.value", attribute, frame
        );
        if (resolver.isInstance("cubism.editor-model.vector2.class", rawValue)) {
            return new float[]{
                floatValue(resolver.invoke("cubism.editor-model.vector2.x", rawValue), "Editor point X"),
                floatValue(resolver.invoke("cubism.editor-model.vector2.y", rawValue), "Editor point Y")
            };
        }
        throw unavailable("Editor animation keyframe point value is invalid.");
    }

    private AnimationCurveType curveType(final Object rawType) {
        if (sameInstance(rawType, "cubism.editor-model.curve-type.linear")) {
            return AnimationCurveType.LINEAR;
        }
        if (sameInstance(rawType, "cubism.editor-model.curve-type.bezier")) {
            return AnimationCurveType.BEZIER;
        }
        if (sameInstance(rawType, "cubism.editor-model.curve-type.smooth")) {
            return AnimationCurveType.SMOOTH;
        }
        if (sameInstance(rawType, "cubism.editor-model.curve-type.step")) {
            return AnimationCurveType.STEP;
        }
        if (sameInstance(rawType, "cubism.editor-model.curve-type.inverse-step")) {
            return AnimationCurveType.INVERSE_STEP;
        }
        throw unavailable("Editor animation curve type is invalid.");
    }

    private HandleData handleData(final Object controlPoint) {
        if (controlPoint == null) {
            return null;
        }
        requireInstance(
            "cubism.editor-model.bezier-ctrl-point.class",
            controlPoint,
            "Editor animation bezier control point is invalid."
        );
        return new HandleData(
            floatValue(
                resolver.invoke("cubism.editor-model.bezier-ctrl-point.pos", controlPoint),
                "Editor animation bezier handle frame"
            ),
            doubleValue(
                resolver.invoke("cubism.editor-model.bezier-ctrl-point.value", controlPoint),
                "Editor animation bezier handle value"
            ),
            flag(
                resolver.invoke("cubism.editor-model.bezier-ctrl-point.corner", controlPoint),
                "Editor animation bezier handle corner flag"
            )
        );
    }

    private Optional<AnimationBezierHandle> bezierHandle(final Object controlPoint) {
        final HandleData handle = handleData(controlPoint);
        return handle == null
            ? Optional.empty()
            : Optional.of(new AnimationBezierHandle(handle.frame(), handle.value(), handle.corner()));
    }

    private boolean sameInstance(final Object value, final String fieldAlias) {
        return value != null && value == resolver.readStaticField(fieldAlias);
    }

    private String guidValue(final Object rawGuid, final String label) {
        if (rawGuid == null) {
            throw unavailable(label + " is unavailable.");
        }
        return text(resolver.invoke("cubism.editor-model.guid.value", rawGuid), label);
    }

    private void requireInstance(final String classAlias, final Object value, final String message) {
        if (!resolver.isInstance(classAlias, value)) {
            throw unavailable(message);
        }
    }

    private static List<?> list(final Object value, final String label) {
        if (!(value instanceof List<?> list)) {
            throw unavailable(label + " is unavailable.");
        }
        return List.copyOf(list);
    }

    private static List<Object> objectArray(final Object value, final String label) {
        if (!(value instanceof Object[] array)) {
            throw unavailable("Editor animation value is unavailable: " + label);
        }
        return List.of(array);
    }

    private static List<Integer> intArray(final Object value, final String label) {
        final int[] frames = rawIntArray(value, label);
        final List<Integer> result = new ArrayList<>(frames.length);
        for (int frame : frames) {
            result.add(frame);
        }
        return List.copyOf(result);
    }

    private static int[] rawIntArray(final Object value, final String label) {
        if (!(value instanceof int[] array)) {
            throw unavailable(label + " is unavailable.");
        }
        return array;
    }

    private static String text(final Object value, final String label) {
        if (!(value instanceof String result) || result.isBlank()) {
            throw unavailable(label + " is invalid.");
        }
        return result;
    }

    private static boolean flag(final Object value, final String label) {
        if (!(value instanceof Boolean result)) {
            throw unavailable(label + " is invalid.");
        }
        return result;
    }

    private static int intValue(final Object value, final String label) {
        if (!(value instanceof Integer result)) {
            throw unavailable(label + " is invalid.");
        }
        return result;
    }

    private static float floatValue(final Object value, final String label) {
        if (!(value instanceof Float result) || !Float.isFinite(result)) {
            throw unavailable(label + " is invalid.");
        }
        return result;
    }

    private static double doubleValue(final Object value, final String label) {
        if (!(value instanceof Number result) || !Double.isFinite(result.doubleValue())) {
            throw unavailable(label + " is invalid.");
        }
        return result.doubleValue();
    }

    private static IllegalStateException unavailable(final String message) {
        return new IllegalStateException(message);
    }
}
