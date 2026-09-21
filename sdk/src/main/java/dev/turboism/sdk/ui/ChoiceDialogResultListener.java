package dev.turboism.sdk.ui;


/**
 * Receives the result of a non-blocking choice dialog.
 *
 * <p>{@code optionId} is the currently selected option when the user accepted
 * or ran a secondary action, or {@code null} when the dialog was cancelled.
 * {@code actionId} is the secondary action id, or {@code null} when the user
 * pressed the primary accept button or cancelled.</p>
 */
@FunctionalInterface
public interface ChoiceDialogResultListener {

    /**
     * Called once when the dialog closes.
     *
     * @param optionId the selected option id, or {@code null} on cancel
     * @param actionId the secondary action id, or {@code null} for accept/cancel
     */
    void onResult(String optionId, String actionId);
}
