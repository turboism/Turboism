package dev.turboism.plugin.psdclipmaskimport;

import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.ui.window.TurboismProgressDialog;
import java.util.Objects;

/** Localized import stages backed by the shared modeless progress window. */
final class PsdClipMaskImportProgressDialog implements PsdClipMaskImportProgress {
    private final TurboismProgressDialog dialog;
    private final String preparingText;
    private final String confirmationText;
    private final String applyingText;

    PsdClipMaskImportProgressDialog(final PluginLocalization localization) {
        final PluginLocalization i18n = Objects.requireNonNull(localization, "localization");
        preparingText = i18n.text("psd.clip-mask-import.progress.preparing");
        confirmationText = i18n.text("psd.clip-mask-import.progress.confirming");
        applyingText = i18n.text("psd.clip-mask-import.progress.applying");
        dialog = new TurboismProgressDialog(i18n.text("psd.clip-mask-import.progress.title"), preparingText);
    }

    @Override
    public void show() {
        dialog.show();
    }

    @Override
    public void preparing() {
        dialog.setMessage(preparingText);
    }

    @Override
    public void awaitingConfirmation() {
        dialog.setMessage(confirmationText);
    }

    @Override
    public void applying() {
        dialog.setMessage(applyingText);
    }

    @Override
    public void focus() {
        dialog.focus();
    }

    @Override
    public boolean cancellationRequested() {
        return false;
    }

    @Override
    public void close() {
        dialog.close();
    }
}
