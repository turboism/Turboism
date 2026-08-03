package dev.turboism.sdk.ui;

import dev.turboism.sdk.PreviewApi;

import java.util.Map;

/**
 * Receives the result of a non-blocking form dialog.
 *
 * <p>{@code accepted} is {@code true} when the user pressed the primary accept
 * button (or a secondary action); {@code actionId} is the secondary action id
 * or {@code null} for accept/cancel. {@code values} maps field ids to their
 * final values; it is empty when the dialog was cancelled.</p>
 */
@PreviewApi
@FunctionalInterface
public interface FormDialogResultListener {

    void onResult(boolean accepted, String actionId, Map<String, String> values);
}
