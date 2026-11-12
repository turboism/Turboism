package dev.turboism.plugin.deformerbatchrename;

import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.ui.window.TurboismProgressDialog;
import java.util.Objects;

/**
 * Localized reading stages backed by the shared modeless progress window.
 *
 * <p>The window is the same one the PSD clip-mask import uses, so a long read looks the same
 * everywhere. It offers no cancellation: the read is bounded by the model size and the caller owns
 * the operation lifecycle.</p>
 */
final class RenameReadProgress {

    private final TurboismProgressDialog dialog;
    private final String readingText;
    private final String preparingText;

    RenameReadProgress(final PluginLocalization localization) {
        final PluginLocalization i18n = Objects.requireNonNull(localization, "localization");
        readingText = i18n.text("progress.reading");
        preparingText = i18n.text("progress.preparing");
        dialog = new TurboismProgressDialog(i18n.text("progress.title"), readingText);
    }

    /** Opens the window, or brings an existing window to the front. */
    void show() {
        dialog.show();
    }

    /** Reports that the model is being read. */
    void reading() {
        dialog.setMessage(readingText);
    }

    /** Reports that the preview is being derived from the finished read. */
    void preparing() {
        dialog.setMessage(preparingText);
    }

    /** Brings an already open window to the front without moving it. */
    void focus() {
        dialog.focus();
    }

    /** Disposes the window; later calls have no effect. */
    void close() {
        dialog.close();
    }
}
