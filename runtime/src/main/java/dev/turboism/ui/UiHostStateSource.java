package dev.turboism.ui;

import dev.turboism.i18n.CubismHostLocale;
import dev.turboism.sdk.ui.DialogRequest;
import dev.turboism.sdk.ui.FileChooserRequest;
import dev.turboism.sdk.ui.ViewportSnapshot;
import dev.turboism.sdk.ui.context.ContextSourceSnapshot;

import java.util.Optional;

/**
 * Runtime seam for UI host state consumed by SDK-facing UI services.
 */
public interface UiHostStateSource {

    UiHostStateSource DEFAULT = new UiHostStateSource() {
    };

    default ContextSourceSnapshot contextSource() {
        return new ContextSourceSnapshot(
            "context-default",
            "workspace",
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
        );
    }

    default ViewportSnapshot viewport() {
        return new ViewportSnapshot("viewport-default", 1, 1, 1.0);
    }

    default Optional<String> chooseFile(FileChooserRequest request) {
        return Optional.empty();
    }

    /**
     * Fake-first confirmation seam. Defaults to confirmed so existing
     * openDialog-only callers keep working; tests and host adapters can override.
     */
    default boolean confirmDialog(DialogRequest request) {
        return true;
    }

    /**
     * Detects the host color mode from the running UIManager; falls back to light.
     * Uses the same luminance heuristic as the legacy theme system.
     */
    default dev.turboism.sdk.ui.UiHostColorMode currentColorMode() {
        final java.awt.Color background = javax.swing.UIManager.getColor("Panel.background");
        if (background != null) {
            final int luma = (background.getRed() * 299 + background.getGreen() * 587
                + background.getBlue() * 114) / 1000;
            return luma < 140
                ? dev.turboism.sdk.ui.UiHostColorMode.DARK
                : dev.turboism.sdk.ui.UiHostColorMode.LIGHT;
        }
        return dev.turboism.sdk.ui.UiHostColorMode.LIGHT;
    }

    /** Resolves the current Cubism UI language (host JVM locale). */
    default java.util.Locale hostLocale() {
        return CubismHostLocale.resolve();
    }

    /** Opens the host file manager at a plugin storage directory; fail-closed by default. */
    default void openDirectory(final dev.turboism.sdk.storage.StoragePath directory) {
        // no-op unless a runtime adapter resolves storage roots
    }
}
