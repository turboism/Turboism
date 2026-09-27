package dev.turboism.mapping.verification.selector;

import java.util.HashSet;
import java.util.Set;

/**
 * Exact additive selector contract for Editor animation scene operations:
 * playhead/playback control, scene ordering and activation, scene-level curve
 * defaults, and evaluated-parameter recording/baking.
 *
 * <p>Evidence (exact public class-file observation, Cubism 5.2.03 / 5.3.02 /
 * 5.3.03 — identical members on all three unless noted):
 * {@code CSceneSource#getSceneInstances()} yields the live
 * {@code CSceneInstance} list; each instance exposes
 * {@code getCurrentTime()} (a mutable {@code movie.core.a} holding the
 * playhead frame via {@code a()}/{@code a(int)}) and
 * {@code getRootTrack()}/{@code getAllTracks()}. Seeking sets the frame on the
 * instance's time object and repaints via the shared complete pack.</p>
 *
 * <p>Scene display order is name-derived: the host re-sorts
 * {@code sceneDocs} by {@code CSceneSource#getSceneName} inside
 * {@code sortSceneDocsByDisplayOrder()}, so index-based reordering is not a
 * host-supported mutation — ordering is expressed through
 * {@code AnimationScene#rename}. Activation writes
 * {@code CAnimationFileContent#setCurrentSceneDoc} and
 * {@code CAnimation#setCurrentScene}. Curve defaults write through
 * {@code CSceneSource#setDefaultParameterCurveType}.</p>
 *
 * <p>Evaluated recording/baking positions the instance's time object, then
 * calls {@code ICMvTrack_Instance#updateTrackInstance_testImpl(CEViewContext,
 * movie.core.a, flags)} — the same call the scene view's {@code updateScene}
 * performs — with a freshly constructed flags object
 * ({@code movie.track.F(boolean)} on 5.3.x, {@code movie.track.H(boolean)} on
 * 5.2.03; the owner differs per version while the alias stays fixed). The
 * evaluated parameter value is read through
 * {@code CMvTrack_Live2DModel_Instance#getParameterSet()} →
 * {@code CParameterSet#getParameters()} → {@code CParameter#getValue()}, then
 * written through the existing timeline write aliases.</p>
 */
public final class EditorAnimationSceneOperationSelectorContract {

    public static final String ADAPTER_SLICE_ID = "${record:cubism-5.2.03-editor-model.json:adapterSliceId}";

    public static final String PLAYBACK_CAPABILITY_ID =
        "cubism.editor-model.animation-playback";
    public static final String SCENE_EDIT_CAPABILITY_ID =
        "cubism.editor-model.animation-scene-edit";
    public static final String EVAL_CAPABILITY_ID =
        "cubism.editor-model.animation-eval";

    /** Record aliases consumed by playhead read + seek. */
    public static final Set<String> PLAYBACK_RECORD_ALIASES = Set.of(
        "cubism.editor-model.scene-source.scene-instances",
        "cubism.editor-model.scene-instance.class",
        "cubism.editor-model.scene-instance.current-time",
        "cubism.editor-model.scene-time.frame",
        "cubism.editor-model.scene-time.set-frame"
    );

    /** Record aliases consumed by scene activation and curve defaults. */
    public static final Set<String> SCENE_EDIT_RECORD_ALIASES = Set.of(
        "cubism.editor-model.animation.set-current-scene",
        "cubism.editor-model.animation-file-content.current-scene-doc",
        "cubism.editor-model.animation-file-content.set-current-scene-doc",
        "cubism.editor-model.scene-document.animation",
        "cubism.editor-model.scene-handler.create",
        "cubism.editor-model.scene-handler.basic-undo",
        "cubism.editor-model.scene-source.default-curve-type",
        "cubism.editor-model.scene-source.set-default-curve-type"
    );

    /** Record aliases consumed by evaluated recording + baking. */
    public static final Set<String> EVAL_RECORD_ALIASES = Set.of(
        "cubism.editor-model.scene-instance.root-track",
        "cubism.editor-model.scene-instance.all-tracks",
        "cubism.editor-model.track-instance.update",
        "cubism.editor-model.track-instance.source",
        "cubism.editor-model.track-model-instance.class",
        "cubism.editor-model.track-model-instance.parameter-set",
        "cubism.editor-model.eval-flags.create",
        "cubism.editor-model.scene-document.view-contexts"
    );

    /** Every alias this contract newly contributes to the records. */
    public static final Set<String> RECORD_ALIASES = union(
        union(PLAYBACK_RECORD_ALIASES, SCENE_EDIT_RECORD_ALIASES),
        EVAL_RECORD_ALIASES
    );

    /**
     * Required for playhead reads and seeks: the instance chain plus the
     * envelope aliases, since repainting resolves the scene document's
     * complete pack.
     */
    public static final Set<String> PLAYBACK_REQUIRED_ALIASES = union(
        PLAYBACK_RECORD_ALIASES,
        EditorAnimationTimelineEditSelectorContract.ENVELOPE_ALIASES
    );

    /** Required for scene activation and curve defaults. */
    public static final Set<String> SCENE_EDIT_REQUIRED_ALIASES = union(
        SCENE_EDIT_RECORD_ALIASES,
        EditorAnimationTimelineEditSelectorContract.ENVELOPE_ALIASES
    );

    /**
     * Required for evaluated recording/baking: the eval chain (view contexts,
     * flags, track instances), the playback chain (instance + time object),
     * the evaluated-parameter read chain (shared model-read aliases), and the
     * full timeline-write roster since results land as keyframes.
     */
    public static final Set<String> EVAL_REQUIRED_ALIASES = union(
        union(
            union(EVAL_RECORD_ALIASES, PLAYBACK_RECORD_ALIASES),
            Set.of(
                "cubism.editor-model.parameter-set.parameters",
                "cubism.editor-model.parameter.id",
                "cubism.editor-model.parameter.value",
                "cubism.editor-model.id.value"
            )
        ),
        EditorAnimationTimelineEditSelectorContract.WRITE_REQUIRED_ALIASES
    );

    private static Set<String> union(final Set<String> left, final Set<String> right) {
        final HashSet<String> values = new HashSet<>(left);
        values.addAll(right);
        return Set.copyOf(values);
    }

    private EditorAnimationSceneOperationSelectorContract() {
    }
}
