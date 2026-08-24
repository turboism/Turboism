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

    void onResult(String optionId, String actionId);
}
