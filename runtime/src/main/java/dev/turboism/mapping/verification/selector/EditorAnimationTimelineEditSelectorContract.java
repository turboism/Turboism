package dev.turboism.mapping.verification.selector;

import java.util.HashSet;
import java.util.Set;

/**
 * Exact additive selector contract for Editor animation timeline writes:
 * keyframe upsert/removal/transforms on effect attributes and scene renames.
 *
 * <p>Evidence (exact public class-file observation, Cubism 5.3.02):
 * {@code CAnimationFileContent#getSceneDocs()} yields the {@code CSceneDocument}
 * list; each scene document exposes {@code getSceneSource()},
 * {@code getCurrentEditMode()} and {@code getCompletePack()}. Scene edits run
 * inside the shared {@code ACEditMode} envelope: {@code beginEdit(String)}
 * returns the {@code GroupUndo}, child {@code ACUndoable} entries attach via
 * {@code GroupUndo#addEdit(ACUndoable, boolean)}, and {@code endEdit} commits.
 * {@code SimpleUndo(String, ICopyable, a)} snapshots the attribute's pre-edit
 * state at construction, so a batch of {@code ICMvAttr#setValueAuto(int,Object)},
 * {@code CMvAttrF#setValueAndCurveType}, {@code CMvAttrI#setValueAuto(int,double)},
 * {@code CMvAttrPt#setValueAuto(int,float,float)} and
 * {@code ICMvAttr#removeValueAuto(int)} calls stay atomic and undoable. Stored
 * bezier handles restore through {@code CBezierCtrlPt#setPosF},
 * {@code setDoubleValue$cubism} and {@code setCorner$cubism}; the sequence
 * re-derives via {@code CMutableSequence#forceUpdate()}. Scene renames write
 * through {@code CSceneSource#setSceneName} with the same undo envelope.</p>
 */
public final class EditorAnimationTimelineEditSelectorContract {

    public static final String ADAPTER_SLICE_ID = "adapter.editor-model.readwrite";

    public static final String WRITE_CAPABILITY_ID = "cubism.editor-model.animation-timeline.write";

    /**
     * Selector aliases this contract newly contributes to the 5.3.02 record.
     * Other Cubism versions must exclude exactly this subset while keeping the
     * aliases shared with earlier features.
     */
    public static final Set<String> RECORD_ALIASES = Set.of(
        "cubism.editor-model.animation-file-content.scene-docs",
        "cubism.editor-model.scene-document.class",
        "cubism.editor-model.scene-document.scene-source",
        "cubism.editor-model.scene-document.current-edit-mode",
        "cubism.editor-model.scene-document.complete-pack",
        "cubism.editor-model.scene-document.update-modified",
        "cubism.editor-model.edit-mode-base.begin",
        "cubism.editor-model.edit-mode-base.end",
        "cubism.editor-model.attr.set-value-auto",
        "cubism.editor-model.attr.remove-value-auto",
        "cubism.editor-model.attr.track",
        "cubism.editor-model.attr-f.set-value-curve",
        "cubism.editor-model.attr-f.read-only",
        "cubism.editor-model.attr-i.set-value-auto",
        "cubism.editor-model.attr-pt.set-value-auto",
        "cubism.editor-model.bezier-ctrl-point.set-pos",
        "cubism.editor-model.bezier-ctrl-point.set-value",
        "cubism.editor-model.bezier-ctrl-point.set-corner",
        "cubism.editor-model.mutable-sequence.force-update",
        "cubism.editor-model.mutable-sequence.set-curve-type",
        "cubism.editor-model.scene-source.set-scene-name",
        "cubism.editor-model.complete-pack.update-project"
    );

    /**
     * The alias subset every envelope-based animation write consumes:
     * scene document resolution plus the native edit-mode/Undo transaction.
     * Shared by the scene-operation contracts.
     */
    public static final Set<String> ENVELOPE_ALIASES = Set.of(
        "cubism.editor-model.animation-file-content.scene-docs",
        "cubism.editor-model.scene-document.class",
        "cubism.editor-model.scene-document.scene-source",
        "cubism.editor-model.scene-document.current-edit-mode",
        "cubism.editor-model.scene-document.complete-pack",
        "cubism.editor-model.scene-document.update-modified",
        "cubism.editor-model.edit-mode-base.begin",
        "cubism.editor-model.edit-mode-base.end",
        "cubism.editor-model.simple-undo.create",
        "cubism.editor-model.undo.add",
        "cubism.editor-model.undo.add-listener",
        "cubism.editor-model.undo-listener.class",
        "cubism.editor-model.complete-pack.update-project",
        "cubism.editor-model.complete-pack.repaint-canvas"
    );

    /** Aliases already carried by earlier Editor-model contracts. */
    private static final Set<String> SHARED_ALIASES = Set.of(
        "cubism.editor-model.simple-undo.create",
        "cubism.editor-model.undo.add",
        "cubism.editor-model.undo.add-listener",
        "cubism.editor-model.undo-listener.class",
        "cubism.editor-model.complete-pack.repaint-canvas"
    );

    public static final Set<String> WRITE_REQUIRED_ALIASES = union(
        union(EditorAnimationTimelineReadSelectorContract.REQUIRED_ALIASES, RECORD_ALIASES),
        SHARED_ALIASES
    );

    private static Set<String> union(final Set<String> left, final Set<String> right) {
        final HashSet<String> values = new HashSet<>(left);
        values.addAll(right);
        return Set.copyOf(values);
    }

    private EditorAnimationTimelineEditSelectorContract() {
    }
}
