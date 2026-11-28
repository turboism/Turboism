package dev.turboism.adapter.cubism.optimization.animation;

import dev.turboism.adapter.cubism.optimization.TargetClassTable;
import dev.turboism.adapter.cubism.optimization.drag.EditDragResponsivenessTarget;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Defines reviewed host classes, members, and descriptors for timeline and Graph Editor acceleration.
 *
 * <p>Covers indexed child-track iteration, cached timecode/decimal formatting, and binary-searched
 * Graph Editor horizontal viewport culling while preserving exact rendered output and evaluation
 * state.</p>
 */
public record AnimationAccelerationTarget(
        HostArtifactDigest digest,
        String version,
        String trackContextOwner,
        String timelineUtilsOwner,
        List<String> targetClasses) {

    /** Shared startup toggle property gating edit-mode acceleration hooks. */
    public static final String ENABLE_PROPERTY = EditDragResponsivenessTarget.ENABLE_PROPERTY;

    /** Explicit validation override property isolated from launcher policy. */
    public static final String VALIDATION_PROPERTY = "turboism.validation.animationAcceleration";

    /** Target-class table base resource name. */
    public static final String TARGET_CLASSES_RESOURCE = "animation-acceleration";

    /** Internal name of {@code CMvTrack_Group_Instance}. */
    public static final String TRACK_GROUP_INSTANCE_OWNER =
            "com/live2d/cubism/doc/animation/movie/track/CMvTrack_Group_Instance";

    /** Internal name of the shared frame-time box {@code movie/core/a}. */
    public static final String FRAME_TIME_OWNER = "com/live2d/cubism/doc/animation/movie/core/a";

    /** Internal name of {@code CMvMovieInfo}. */
    public static final String MOVIE_INFO_OWNER = "com/live2d/cubism/doc/animation/movie/core/CMvMovieInfo";

    /** Internal name of {@code CMutableSequence}. */
    public static final String MUTABLE_SEQUENCE_OWNER =
            "com/live2d/cubism/doc/animation/movie/effect/attr/value/CMutableSequence";

    /** Internal name of {@code CBezierPt}. */
    public static final String BEZIER_PT_OWNER = "com/live2d/cubism/doc/animation/movie/effect/attr/value/CBezierPt";

    /** Internal name of {@code CBezierCtrlPt}. */
    public static final String BEZIER_CTRL_PT_OWNER =
            "com/live2d/cubism/doc/animation/movie/effect/attr/value/CBezierCtrlPt";

    /** Internal name of {@code CSeqPt}. */
    public static final String SEQ_PT_OWNER = "com/live2d/cubism/doc/animation/movie/effect/attr/value/CSeqPt";

    /** Internal name of {@code CCurveType}. */
    public static final String CURVE_TYPE_OWNER = "com/live2d/cubism/doc/animation/movie/effect/attr/value/CCurveType";

    /** Internal name of {@code HeaderRowEditPanel}. */
    public static final String HEADER_PANEL_OWNER =
            "com/live2d/cubism/view/palette/timeline/insidePanel/headerPanel/HeaderRowEditPanel";

    /** Internal name of {@code GraphEditor}. */
    public static final String GRAPH_EDITOR_OWNER =
            "com/live2d/cubism/view/palette/timeline/insidePanel/graphEditor/GraphEditor";

    /** Internal name of {@code GraphEditor$e} handle descriptor value object. */
    public static final String GRAPH_HANDLE_OWNER =
            "com/live2d/cubism/view/palette/timeline/insidePanel/graphEditor/GraphEditor$e";

    /** Internal name of {@code GraphEditor$m} curve-type switch map. */
    public static final String GRAPH_SWITCH_MAP_OWNER =
            "com/live2d/cubism/view/palette/timeline/insidePanel/graphEditor/GraphEditor$m";

    /** Internal name of {@code CArrayList}. */
    public static final String ARRAY_LIST_OWNER = "com/live2d/type/CArrayList";

    /** Internal name of {@code CModel}. */
    public static final String MODEL_OWNER = "com/live2d/cubism/doc/model/CModel";

    /** Internal name of {@code CCustomPaintComponent}. */
    public static final String CUSTOM_PAINT_COMP_OWNER = "com/live2d/ui/control/CCustomPaintComponent";

    /** Internal name of {@code CColor}. */
    public static final String CCOLOR_OWNER = "com/live2d/type/CColor";

    /** Internal name of legacy track evaluation context {@code E}. */
    public static final String TRACK_CONTEXT_E_OWNER = "com/live2d/cubism/doc/animation/movie/track/E";

    /** Internal name of current track evaluation context {@code F}. */
    public static final String TRACK_CONTEXT_F_OWNER = "com/live2d/cubism/doc/animation/movie/track/F";

    /** Internal name of alternate track evaluation context {@code H}. */
    public static final String TRACK_CONTEXT_H_OWNER = "com/live2d/cubism/doc/animation/movie/track/H";

    /** Internal name of legacy timeline palette utilities {@code s}. */
    public static final String TIMELINE_UTILS_S_OWNER = "com/live2d/cubism/view/palette/timeline/Utils/s";

    /** Internal name of current timeline palette utilities {@code v}. */
    public static final String TIMELINE_UTILS_V_OWNER = "com/live2d/cubism/view/palette/timeline/Utils/v";

    /** Rewritten owner count across all reviewed host builds. */
    public static final int EXPECTED_OWNERS = 5;

    private static final String METHOD_UPDATE_TEST_IMPL = "updateTrackInstance_testImpl";
    private static final String METHOD_UPDATE_TEST_IMPL_FOR_FA = "updateTrackInstance_testImpl_forFA";
    private static final String METHOD_GET_DISPLAY_SUBFRAME = "getDisplayTimeSubFrameString";
    private static final String DESC_GET_DISPLAY_SUBFRAME = "(I)Ljava/lang/String;";
    private static final String METHOD_PAINT_EXE = "paint_exe";
    private static final String DESC_PAINT_EXE = "(Lcom/live2d/ui/g;)V";
    private static final String METHOD_DRAW_CURVE = "drawCurve";
    private static final String DESC_DRAW_CURVE =
            "(Lcom/live2d/ui/g;Lcom/live2d/cubism/doc/animation/movie/effect/attr/value/CMutableSequence;Z)V";

    private static final Map<String, List<String>> TARGET_TABLE = TargetClassTable.load(TARGET_CLASSES_RESOURCE);

    /** Validates and freezes an animation acceleration target contract. */
    public AnimationAccelerationTarget {
        Objects.requireNonNull(digest, "digest");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(trackContextOwner, "trackContextOwner");
        Objects.requireNonNull(timelineUtilsOwner, "timelineUtilsOwner");
        targetClasses = List.copyOf(Objects.requireNonNull(targetClasses, "targetClasses"));
    }

    /**
     * Resolves the reviewed animation-acceleration target contract for {@code digest}.
     *
     * @param digest host artifact SHA-256 digest
     * @return resolved target contract, or empty when unreviewed or missing required entries
     */
    public static Optional<AnimationAccelerationTarget> of(final HostArtifactDigest digest) {
        Objects.requireNonNull(digest, "digest");
        final Optional<String> version = ReviewedHostArtifacts.cubismVersionOf(digest);
        if (version.isEmpty()) {
            return Optional.empty();
        }
        final List<String> classes = TARGET_TABLE.get(version.get());
        if (classes == null) {
            return Optional.empty();
        }
        final String ctxOwner = classes.contains(TRACK_CONTEXT_E_OWNER)
                ? TRACK_CONTEXT_E_OWNER
                : classes.contains(TRACK_CONTEXT_F_OWNER)
                        ? TRACK_CONTEXT_F_OWNER
                        : classes.contains(TRACK_CONTEXT_H_OWNER) ? TRACK_CONTEXT_H_OWNER : null;
        final String utilsOwner = classes.contains(TIMELINE_UTILS_S_OWNER)
                ? TIMELINE_UTILS_S_OWNER
                : classes.contains(TIMELINE_UTILS_V_OWNER) ? TIMELINE_UTILS_V_OWNER : null;
        if (ctxOwner == null || utilsOwner == null) {
            return Optional.empty();
        }
        return Optional.of(new AnimationAccelerationTarget(digest, version.get(), ctxOwner, utilsOwner, classes));
    }

    /**
     * Returns all target class names mapped for a reviewed Cubism version.
     *
     * @param version dotted Cubism release string
     * @return immutable list of reviewed class internal names
     */
    public static List<String> targetClasses(final String version) {
        return TargetClassTable.classes(TARGET_CLASSES_RESOURCE, version);
    }

    /**
     * Returns the full version-keyed target-class table map.
     *
     * @return immutable map from dotted version to target-class list
     */
    public static Map<String, List<String>> targetClassesByVersion() {
        return TARGET_TABLE;
    }

    /** Returns the descriptor of {@code updateTrackInstance_testImpl}. */
    public String updateTrackInstanceTestImplDescriptor() {
        return "(Lcom/live2d/cubism/view/context/CEViewContext;L" + FRAME_TIME_OWNER + ";L" + trackContextOwner + ";)V";
    }

    /** Returns the descriptor of {@code CMvTrack_Group_Instance.updateTrackInstance_testImpl_forFA}. */
    public String updateTrackInstanceForFaDescriptor() {
        return "(Lcom/live2d/cubism/view/context/CEViewContext;L"
                + FRAME_TIME_OWNER
                + ";L"
                + trackContextOwner
                + ";Lkotlin/jvm/functions/Function1;Lkotlin/jvm/functions/Function1;)V";
    }

    /** Returns the expected count of rewritten owner classes for this host build. */
    public int expectedOwners() {
        return EXPECTED_OWNERS;
    }

    /** Returns the immutable ordered list of rewritten owner internal names for this host build. */
    public List<String> rewrittenOwners() {
        return List.of(
                TRACK_GROUP_INSTANCE_OWNER,
                MOVIE_INFO_OWNER,
                timelineUtilsOwner,
                HEADER_PANEL_OWNER,
                GRAPH_EDITOR_OWNER);
    }

    /** Method name of {@code updateTrackInstance_testImpl}. */
    public static String updateTrackInstanceTestImplMethod() {
        return METHOD_UPDATE_TEST_IMPL;
    }

    /** Method name of {@code CMvTrack_Group_Instance.updateTrackInstance_testImpl_forFA}. */
    public static String updateTrackInstanceForFaMethod() {
        return METHOD_UPDATE_TEST_IMPL_FOR_FA;
    }

    /** Method name of {@code CMvMovieInfo.getDisplayTimeSubFrameString}. */
    public static String getDisplaySubframeMethod() {
        return METHOD_GET_DISPLAY_SUBFRAME;
    }

    /** Method descriptor of {@code CMvMovieInfo.getDisplayTimeSubFrameString}. */
    public static String getDisplaySubframeDescriptor() {
        return DESC_GET_DISPLAY_SUBFRAME;
    }

    /** Method name of {@code HeaderRowEditPanel.paint_exe} and {@code GraphEditor.paint_exe}. */
    public static String paintExeMethod() {
        return METHOD_PAINT_EXE;
    }

    /** Method descriptor of {@code paint_exe}. */
    public static String paintExeDescriptor() {
        return DESC_PAINT_EXE;
    }

    /** Method name of {@code GraphEditor.drawCurve}. */
    public static String drawCurveMethod() {
        return METHOD_DRAW_CURVE;
    }

    /** Method descriptor of {@code GraphEditor.drawCurve}. */
    public static String drawCurveDescriptor() {
        return DESC_DRAW_CURVE;
    }
}
