package dev.turboism.sdk.ui;


/**
 * Receives the result of a non-blocking color picker.
 *
 * <p>{@code accepted} is {@code true} when the user confirmed a color;
 * {@code colorHex} is then a canonical {@code #RRGGBB} value. On cancel
 * {@code accepted} is {@code false} and {@code colorHex} is {@code null}.</p>
 */
@FunctionalInterface
public interface ColorPickerResultListener {

    /**
     * Called once when the picker closes.
     *
     * @param accepted whether the user confirmed a color
     * @param colorHex the canonical {@code #RRGGBB} value, or {@code null} on cancel
     */
    void onResult(boolean accepted, String colorHex);
}
