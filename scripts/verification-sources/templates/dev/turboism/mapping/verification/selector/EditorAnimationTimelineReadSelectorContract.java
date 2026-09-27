package dev.turboism.mapping.verification.selector;

import java.util.Set;

/**
 * Exact additive selector contract for Editor animation timeline reads:
 * scene metadata, track trees, effect attributes, and keyframe curves.
 *
 * <p>Evidence (exact public class-file observation, Cubism 5.3.02):
 * {@code CSceneSource} exposes {@code getGuid()}, {@code getTag()},
 * {@code getMarker()}, {@code getMovieInfo()}, and {@code getRootTrack()}.
 * {@code CMvMovieInfo} exposes frame range, fps, output size, loop flag, and
 * work-area bounds. {@code ICMvTrack_Source} exposes name, guid, start,
 * duration, visibility/editability/mute/repeat flags, keyframe positions, and
 * its {@code CMvEffectManager}; {@code CMvTrack_Group_Source#getChildTracks()}
 * nests the tree. Each {@code ICMvEffect} exposes id and {@code ICMvAttr[]};
 * each {@code ICMvAttr} exposes id, name, guid, flags,
 * {@code getKeyFrames()}, and {@code getValue(int)}. {@code CMvAttrF} exposes
 * {@code getValueData()} whose {@code CMutableSequence} exposes
 * {@code getCurveType(int)} by frame position and {@code getPoint(int)}
 * yielding {@code CBezierPt} with prev/next {@code CBezierCtrlPt} handles.
 * Parameter curves are attributes of {@code CMvEffect_Live2DParameter} keyed
 * by {@code live2dParam_}-prefixed ids.</p>
 */
public final class EditorAnimationTimelineReadSelectorContract {

    public static final String CUBISM_VERSION = "${record:cubism-5.3.02-editor-model.json:cubismVersion}";

    public static final String ADAPTER_SLICE_ID = "${record:cubism-5.2.03-editor-model.json:adapterSliceId}";

    public static final String CAPABILITY_ID = "cubism.editor-model.animation-timeline.read";

    /**
     * Selector aliases this contract newly contributes to the 5.3.02 record.
     * Other Cubism versions must exclude exactly this subset while keeping the
     * aliases shared with earlier features.
     */
    public static final Set<String> RECORD_ALIASES = Set.of(
        "cubism.editor-model.scene-source.guid",
        "cubism.editor-model.scene-source.tag",
        "cubism.editor-model.scene-source.marker",
        "cubism.editor-model.scene-source.movie-info",
        "cubism.editor-model.scene-source.root-track",
        "cubism.editor-model.movie-info.class",
        "cubism.editor-model.movie-info.start-frame",
        "cubism.editor-model.movie-info.duration",
        "cubism.editor-model.movie-info.fps",
        "cubism.editor-model.movie-info.width",
        "cubism.editor-model.movie-info.height",
        "cubism.editor-model.movie-info.loop-motion",
        "cubism.editor-model.movie-info.workspace-start",
        "cubism.editor-model.movie-info.workspace-end",
        "cubism.editor-model.track-source.class",
        "cubism.editor-model.track-source.name",
        "cubism.editor-model.track-source.guid",
        "cubism.editor-model.track-source.start",
        "cubism.editor-model.track-source.duration",
        "cubism.editor-model.track-source.editable",
        "cubism.editor-model.track-source.visible",
        "cubism.editor-model.track-source.mute",
        "cubism.editor-model.track-source.repeat",
        "cubism.editor-model.track-source.key-frames",
        "cubism.editor-model.track-source.effect-manager",
        "cubism.editor-model.track-group.class",
        "cubism.editor-model.track-group.children",
        "cubism.editor-model.track-model.class",
        "cubism.editor-model.track-model.model",
        "cubism.editor-model.track-scene.class",
        "cubism.editor-model.track-scene.resource-scene-guid",
        "cubism.editor-model.track-image.class",
        "cubism.editor-model.track-guide-image.class",
        "cubism.editor-model.track-text.class",
        "cubism.editor-model.track-moc3.class",
        "cubism.editor-model.track-sound.class",
        "cubism.editor-model.effect-manager.class",
        "cubism.editor-model.effect-manager.effects",
        "cubism.editor-model.effect.class",
        "cubism.editor-model.effect.id",
        "cubism.editor-model.effect.attrs",
        "cubism.editor-model.effect-parameter.class",
        "cubism.editor-model.attr.class",
        "cubism.editor-model.attr.id",
        "cubism.editor-model.attr.name",
        "cubism.editor-model.attr.guid",
        "cubism.editor-model.attr.active",
        "cubism.editor-model.attr.editable",
        "cubism.editor-model.attr.key-frames",
        "cubism.editor-model.attr.value",
        "cubism.editor-model.attr-f.class",
        "cubism.editor-model.attr-f.value-data",
        "cubism.editor-model.attr-i.class",
        "cubism.editor-model.attr-pt.class",
        "cubism.editor-model.mutable-sequence.class",
        "cubism.editor-model.mutable-sequence.curve-type",
        "cubism.editor-model.mutable-sequence.point",
        "cubism.editor-model.bezier-point.class",
        "cubism.editor-model.bezier-point.prev",
        "cubism.editor-model.bezier-point.next",
        "cubism.editor-model.bezier-ctrl-point.class",
        "cubism.editor-model.bezier-ctrl-point.pos",
        "cubism.editor-model.bezier-ctrl-point.value",
        "cubism.editor-model.bezier-ctrl-point.corner",
        "cubism.editor-model.curve-type.class",
        "cubism.editor-model.curve-type.linear",
        "cubism.editor-model.curve-type.bezier",
        "cubism.editor-model.curve-type.smooth",
        "cubism.editor-model.curve-type.step",
        "cubism.editor-model.curve-type.inverse-step"
    );

    /** Aliases already carried by earlier Editor-model read contracts. */
    private static final Set<String> SHARED_ALIASES = Set.of(
        "cubism.editor-model.scene-source.class",
        "cubism.editor-model.scene-source.scene-name",
        "cubism.editor-model.guid.value",
        "cubism.editor-model.id.value",
        "cubism.editor-model.model-source.guid",
        "cubism.editor-model.vector2.class",
        "cubism.editor-model.vector2.x",
        "cubism.editor-model.vector2.y"
    );

    public static final Set<String> REQUIRED_ALIASES = union(RECORD_ALIASES, SHARED_ALIASES);

    private static Set<String> union(final Set<String> left, final Set<String> right) {
        final java.util.HashSet<String> values = new java.util.HashSet<>(left);
        values.addAll(right);
        return Set.copyOf(values);
    }

    private EditorAnimationTimelineReadSelectorContract() {
    }
}
