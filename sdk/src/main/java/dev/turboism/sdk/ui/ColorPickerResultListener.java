package dev.turboism.sdk.ui;

import dev.turboism.sdk.PreviewApi;

/**
 * Receives the result of a non-blocking color picker.
 *
 * <p>{@code accepted} is {@code true} when the user confirmed a color;
 * {@code colorHex} is then a canonical {@code #RRGGBB} value. On cancel
 * {@code accepted} is {@code false} and {@code colorHex} is {@code null}.</p>
 */
@PreviewApi
@FunctionalInterface
public interface ColorPickerResultListener {

    void onResult(boolean accepted, String colorHex);
}
