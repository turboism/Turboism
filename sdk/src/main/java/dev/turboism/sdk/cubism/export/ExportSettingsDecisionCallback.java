package dev.turboism.sdk.cubism.export;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.cubism.id.ModelId;

/**
 * Typed decision callback for one embedded-model Export Settings option.
 *
 * <p>The callback is consulted only when the host export flow reports the option as
 * selected. It receives the selected state plus the Turboism-owned document and model
 * identities of the export target. It never receives host objects, paths, or handles.</p>
 */
@CubismEditor({"5.2.03", "5.3.02", "5.3.03"})
@FunctionalInterface
public interface ExportSettingsDecisionCallback {

    /**
     * Decides whether the native embedded-model export may proceed unchanged.
     *
     * @param selected   the option's selected state reported by the host export flow
     * @param documentId the Turboism-owned identity of the current editor document
     * @param modelId    the Turboism-owned identity of the current model
     * @return {@link ExportSettingsDecision#proceedUnchanged()} to leave the native export
     *         unchanged, or {@link ExportSettingsDecision#reject(String)} with one bounded
     *         diagnostic/localization identity to reject it
     */
    ExportSettingsDecision decide(boolean selected, String documentId, ModelId modelId);
}
