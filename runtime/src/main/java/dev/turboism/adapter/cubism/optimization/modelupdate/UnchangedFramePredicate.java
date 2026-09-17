package dev.turboism.adapter.cubism.optimization.modelupdate;

import java.util.Objects;

/**
 * Pure conservative skip decision for the model full-update entry.
 *
 * <p>Returns {@code true} only when every observable input to the native update is provably
 * identical to the input of the last completed real update, and the host's own
 * {@code lastUpdatedParameterSet} still equals the live parameter set value-for-value.
 * Any null, any unknown, any difference — or any evaluator-side failure — means
 * {@code false} (run native). Contains no host references so the full truth table is
 * testable offline.</p>
 */
public final class UnchangedFramePredicate {

    /** First conservative reason a frame cannot skip the native full update. */
    public enum Decision {
        SKIP,
        NO_BASELINE,
        MISSING_REQUIRED_STATE,
        MODEL_OR_DOCUMENT_CHANGED,
        TEMPORAL_MODE_ACTIVE,
        SELECTION_MODE_ACTIVE,
        CONTEXT_PARAM_PRESENT,
        MUTABLE_STACKS,
        PARAMETERS_CHANGED,
        PARAMETER_VERSION_CHANGED,
        DOCUMENT_MODIFIED,
        VIEW_OR_EDIT_MODE_CHANGED,
        APPEARANCE_CHANGED,
        DRAW_SETTINGS_CHANGED,
        VIEW_SETTINGS_CHANGED,
        UPDATE_CONTEXT_CHANGED,
        SELECTION_CHANGED,
        RENDER_HASH_CHANGED,
        CONFLICT_CHANGED,
        UPDATER_FLAGS_CHANGED,
        ARGUMENT_FLAGS_CHANGED
    }

    private UnchangedFramePredicate() { }

    /** Minimal indexed view over a host parameter set's parameter list. */
    public interface ParamSet {
        /** Number of parameters, or {@code -1} when the set is absent. */
        int size();
        /** Identity of the parameter at {@code index}. */
        Object idAt(int index);
        /** Current value of the parameter at {@code index}. */
        float valueAt(int index);
    }

    /**
     * One frame of predicate inputs read by the bridge before the entry call.
     * Reference fields use identity comparison; floating fields compare by raw bits.
     * Fields that have no counterpart on the host version are neutral
     * ({@code axRenderHash == null}, {@code axStacksEmpty == true},
     * {@code conflictPolygon == false}).
     */
    public record Frame(
            Object model,
            Object modelingView,
            Object document,
            long documentLastModified,
            int parameterSetUpdateVersion,
            Object viewMode,
            Object editMode,
            float appearanceSettingD,
            boolean optimizeArtMesh,
            boolean optimizeDeformer,
            boolean optimizeDrawOrder,
            boolean optimizeHierarchy,
            boolean maskWarningHint,
            boolean blendModeWarningHint,
            boolean hideSelectedState,
            boolean highLightDeformerChild,
            boolean randomPoseAnimation,
            boolean externalAppAnimation,
            boolean recording,
            boolean developSettingH,
            boolean developSettingK,
            boolean formAnimationGate,
            boolean modelEditing,
            boolean updaterFlagA,
            boolean updaterFlagB,
            boolean updateContextPresent,
            boolean updateContextA,
            boolean updateContextB,
            float updateContextC,
            boolean updateContextD,
            boolean updateContextE,
            boolean updateContextF,
            Object updateContextView,
            Object updateContextEditMode,
            java.util.List<Object> updateContextSelection,
            Object axRenderHash,
            boolean axStacksEmpty,
            boolean conflictPolygon,
            Object contextParam,
            boolean argAllowAnimation,
            boolean argFormAnimation) {
    }

    /**
     * The full skip conjunction. {@code previous} is the stored frame of the last
     * completed real update; {@code null} means none has completed and the answer is
     * always {@code false}.
     *
     * @param current inputs read before this entry call
     * @param previous inputs stored by the last completed real update, or null
     * @param parameters live parameter set view
     * @param lastUpdated the model's own last-updated parameter set view
     * @return true only when a skip is provably semantics-preserving
     */
    public static boolean test(final Frame current, final Frame previous,
                               final ParamSet parameters, final ParamSet lastUpdated) {
        return check(current, previous, parameters, lastUpdated) == Decision.SKIP;
    }

    /**
     * Returns the first conservative blocker in evaluation order. This is the same
     * decision source used by {@link #test(Frame, Frame, ParamSet, ParamSet)} so
     * diagnostics cannot drift from production behavior.
     */
    public static Decision check(final Frame current, final Frame previous,
                                 final ParamSet parameters, final ParamSet lastUpdated) {
        if (current == null || parameters == null || lastUpdated == null) {
            return Decision.MISSING_REQUIRED_STATE;
        }
        if (previous == null) {
            return Decision.NO_BASELINE;
        }
        // Inputs that must exist for any safe decision at all.
        if (current.model() == null || current.modelingView() == null || current.document() == null
            || !current.updateContextPresent()) {
            return Decision.MISSING_REQUIRED_STATE;
        }
        // Same model and same document generation only.
        if (current.model() != previous.model() || current.document() != previous.document()) {
            return Decision.MODEL_OR_DOCUMENT_CHANGED;
        }
        // Modes that gate work inside the entry method: all must be off now and off then.
        if (current.modelEditing() || previous.modelEditing()
            || current.randomPoseAnimation() || previous.randomPoseAnimation()
            || current.externalAppAnimation() || previous.externalAppAnimation()
            || current.recording() || previous.recording()
            || current.developSettingH() || previous.developSettingH()
            || current.developSettingK() || previous.developSettingK()
            || current.formAnimationGate() || previous.formAnimationGate()) {
            return Decision.TEMPORAL_MODE_ACTIVE;
        }
        // The update reads selection-backed lazy lists only in this mode; never skip there.
        if (current.updateContextA() || previous.updateContextA()) {
            return Decision.SELECTION_MODE_ACTIVE;
        }
        // Movie/track contexts pass a non-null context parameter; never skip there.
        if (current.contextParam() != null || previous.contextParam() != null) {
            return Decision.CONTEXT_PARAM_PRESENT;
        }
        // Caller-pre-populated mutable stacks are an input we cannot compare cheaply.
        if (!current.axStacksEmpty() || !previous.axStacksEmpty()) {
            return Decision.MUTABLE_STACKS;
        }
        // The model's own record: current values must equal the last really-updated set.
        if (!parametersEqual(parameters, lastUpdated)) {
            return Decision.PARAMETERS_CHANGED;
        }
        if (current.parameterSetUpdateVersion() != previous.parameterSetUpdateVersion()) {
            return Decision.PARAMETER_VERSION_CHANGED;
        }
        if (current.documentLastModified() != previous.documentLastModified()) {
            return Decision.DOCUMENT_MODIFIED;
        }
        if (current.viewMode() != previous.viewMode() || current.editMode() != previous.editMode()) {
            return Decision.VIEW_OR_EDIT_MODE_CHANGED;
        }
        if (bits(current.appearanceSettingD()) != bits(previous.appearanceSettingD())) {
            return Decision.APPEARANCE_CHANGED;
        }
        if (current.optimizeArtMesh() != previous.optimizeArtMesh()
            || current.optimizeDeformer() != previous.optimizeDeformer()
            || current.optimizeDrawOrder() != previous.optimizeDrawOrder()
            || current.optimizeHierarchy() != previous.optimizeHierarchy()) {
            return Decision.DRAW_SETTINGS_CHANGED;
        }
        if (current.maskWarningHint() != previous.maskWarningHint()
            || current.blendModeWarningHint() != previous.blendModeWarningHint()
            || current.hideSelectedState() != previous.hideSelectedState()
            || current.highLightDeformerChild() != previous.highLightDeformerChild()) {
            return Decision.VIEW_SETTINGS_CHANGED;
        }
        if (current.updateContextB() != previous.updateContextB()
            || current.updateContextD() != previous.updateContextD()
            || current.updateContextE() != previous.updateContextE()
            || current.updateContextF() != previous.updateContextF()
            || bits(current.updateContextC()) != bits(previous.updateContextC())
            || current.updateContextView() != previous.updateContextView()
            || current.updateContextEditMode() != previous.updateContextEditMode()) {
            return Decision.UPDATE_CONTEXT_CHANGED;
        }
        if (!Objects.equals(current.updateContextSelection(), previous.updateContextSelection())) {
            return Decision.SELECTION_CHANGED;
        }
        if (!Objects.equals(current.axRenderHash(), previous.axRenderHash())) {
            return Decision.RENDER_HASH_CHANGED;
        }
        if (current.conflictPolygon() != previous.conflictPolygon()) {
            return Decision.CONFLICT_CHANGED;
        }
        if (current.updaterFlagA() != previous.updaterFlagA()
            || current.updaterFlagB() != previous.updaterFlagB()) {
            return Decision.UPDATER_FLAGS_CHANGED;
        }
        if (current.argAllowAnimation() != previous.argAllowAnimation()
            || current.argFormAnimation() != previous.argFormAnimation()) {
            return Decision.ARGUMENT_FLAGS_CHANGED;
        }
        return Decision.SKIP;
    }

    /** Exact value-and-identity comparison, indexed, no tolerance, no boxing. */
    private static boolean parametersEqual(final ParamSet current, final ParamSet lastUpdated) {
        final int size = current.size();
        if (size < 0 || size != lastUpdated.size()) {
            return false;
        }
        for (int i = 0; i < size; i++) {
            if (bits(current.valueAt(i)) != bits(lastUpdated.valueAt(i))) {
                return false;
            }
            if (!Objects.equals(current.idAt(i), lastUpdated.idAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static int bits(final float value) {
        return Float.floatToRawIntBits(value);
    }
}
