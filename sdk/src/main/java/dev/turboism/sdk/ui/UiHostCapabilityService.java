package dev.turboism.sdk.ui;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import dev.turboism.sdk.ui.context.ContextSourceSnapshot;
import dev.turboism.sdk.ui.filter.PaletteFilterRegistry;
import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;
import dev.turboism.sdk.ui.toolbar.PaletteToolbarRegistry;

import java.util.Objects;
import java.util.Optional;

/**
 * UI-host service surface for SDK-only plugins.
 *
 * <p>Plugins submit descriptors and receive registration handles or SDK-safe
 * values. Implementations own host UI adaptation, scheduling, placement,
 * disposal, and file/dialog/status behavior. This API must not expose Swing,
 * AWT, Cubism host widgets, native handles, or raw host objects.</p>
 */
public interface UiHostCapabilityService {

    Registration contributeOverlay(OverlayContribution contribution);

    Registration contributeBoundingBoxOverlayButton(BoundingBoxOverlayButton contribution);

    ContextSourceSnapshot contextSource();

    ViewportSnapshot viewport();

    Registration openDialog(DialogRequest request);

    /**
     * Requests a yes/no confirmation for the given dialog descriptor.
     *
     * <p>Implementations must perform the same permission checks as
     * {@link #openDialog(DialogRequest)} and must not expose host widgets.
     * Returning {@code false} means the user declined or the host cancelled.</p>
     */
    boolean confirmDialog(DialogRequest request);

    /**
     * Opens a bounded runtime-rendered choice dialog without blocking the caller.
     *
     * <p>Returns immediately after scheduling the dialog on the UI thread. The
     * listener receives the selected option id and the secondary action id (or
     * {@code null}s for accept/cancel) once the user closes the dialog.</p>
     */
    default void openChoiceDialog(
        final ChoiceDialogRequest request,
        final ChoiceDialogResultListener listener
    ) {
        throw new UnsupportedOperationException("async choice dialogs are not available");
    }

    /**
     * Contributes a runtime-rendered panel owned by the calling plugin.
     * Control action IDs resolve through that plugin's {@code ActionRegistry}.
     */

    /** Opens a bounded runtime-rendered single-choice dialog. */
    default Optional<String> choose(final ChoiceDialogRequest request) {
        throw new UnsupportedOperationException("choice dialogs are not available");
    }

    Registration contributeEmbeddedPanel(EmbeddedPanelContribution contribution);

    /**
     * Contributes a toolkit-neutral control to the shared Turboism settings window.
     * Requires {@code turboism.ui.settings.contribute}; the registration is owned
     * by the calling plugin's disposable scope.
     */
    default Registration contributeSettings(
        final dev.turboism.sdk.ui.settings.SettingsContribution contribution
    ) {
        Objects.requireNonNull(contribution, "contribution");
        throw new UnsupportedOperationException("settings contribution is unavailable");
    }

    /**
     * Activates an embedded panel owned by the calling plugin.
     *
     * <p>Hosts that do not provide a verified panel surface fail closed.</p>
     */
    default void activateEmbeddedPanel(final EmbeddedPanelId panelId) {
        throw new UnsupportedOperationException("embedded-panel activation is unavailable");
    }

    /**
     * Activates an embedded panel owned by the calling plugin as a floating
     * window (Photoshop-style popup). Hosts that do not provide a verified
     * floating surface fail closed to plain activation.
     */
    default void activateEmbeddedPanelFloating(final EmbeddedPanelId panelId) {
        activateEmbeddedPanel(panelId);
    }

    /**
     * Injects a collapsible section into an existing embedded panel (including the
     * Turboism tab {@code turboism.panel.main}).
     *
     * <p>Injected sections are appended after the panel's declared content during
     * render synthesis and ordered by {@code order} then {@code sectionId}.
     * Hosts that do not provide a verified panel surface fail closed.</p>
     */
    default Registration contributeCollapsibleSection(
        final CollapsibleSectionContribution contribution
    ) {
        throw new UnsupportedOperationException("collapsible-section contribution is unavailable");
    }

    Optional<String> requestFile(FileChooserRequest request);

    /**
     * Reports the host's active color theme mode (Cubism light/dark). Used by
     * plugins to filter base-compatible options such as theme packages.
     */
    default UiHostColorMode currentColorMode() {
        return UiHostColorMode.LIGHT;
    }

    /**
     * Returns the Cubism Editor UI language (host JVM locale), used by plugins
     * to select localized presentation.
     *
     * <p>Returns the <b>effective UI language</b>: zh builds are normalized to
     * {@code zh-Hans}/{@code zh-Hant} (zh-CN/zh-SG → zh-Hans, zh-TW/zh-HK/zh-MO →
     * zh-Hant, other script-less zh such as Wine-rewritten zh-US → zh-Hans);
     * non-zh languages are returned unchanged. The raw host JVM locale may be
     * rewritten by Proton/Wine (e.g. {@code zh-US}) and does not represent the
     * actual UI language.</p>
     *
     * @return the current effective Cubism UI language, never {@code null}
     */
    default java.util.Locale hostLocale() {
        return java.util.Locale.getDefault(java.util.Locale.Category.DISPLAY);
    }

    /**
     * Opens the host file manager at the given plugin storage directory.
     * Implementations must confine the resolved directory to the plugin's
     * storage roots and fail closed when the host cannot open directories.
     */
    default void openDirectory(final dev.turboism.sdk.storage.StoragePath directory) {
        throw new UnsupportedOperationException("open-directory is not available");
    }

    /**
     * Opens a bounded runtime-rendered form dialog (text and color fields)
     * without blocking the caller. The listener receives the field values when
     * the user accepts, a secondary action id when one is pressed, or an empty
     * map on cancel.
     */
    default void openFormDialog(
        final FormDialogRequest request,
        final FormDialogResultListener listener
    ) {
        throw new UnsupportedOperationException("form dialogs are not available");
    }

    /**
     * Opens a bounded runtime-rendered color picker without blocking the caller.
     * The listener receives {@code true} with a canonical {@code #RRGGBB} value
     * when the user confirms, or {@code false} with {@code null} on cancel.
     * {@code initialColorHex} may be {@code null} or a canonical {@code #RRGGBB}
     * value; invalid values fall back to the picker default.
     */
    default void openColorPicker(
        final String id,
        final String title,
        final String initialColorHex,
        final ColorPickerResultListener listener
    ) {
        throw new UnsupportedOperationException("color pickers are not available");
    }

    /**
     * @deprecated Applying a theme requires restarting Cubism Editor; immediate
     *     off-canvas repaint is not a supported capability.
     * @return no refresh; this compatibility method always fails closed
     * @throws UnsupportedOperationException on every call
     */
    @Deprecated(forRemoval = true)
    default boolean refreshOffCanvasAppearance() {
        throw new UnsupportedOperationException(
            "off-canvas refresh is unavailable; restart Cubism Editor after applying a theme"
        );
    }

    /**
     * Replaces the current ordinary bottom-status message and records the same
     * message through the calling plugin's framework logger. Compact resident
     * metrics retain their own keyed slots.
     *
     * @param notification validated status message and severity
     * @return a handle that dismisses this message only while it remains current
     */
    Registration notifyStatus(StatusNotification notification);

    Registration contributeContextMenu(ContextMenuRegistry.ContextMenuContribution contribution);

    Registration contributeMainToolbar(MainToolbarRegistry.MainToolbarContribution contribution);

    Registration contributePaletteToolbar(PaletteToolbarRegistry.PaletteToolbarContribution contribution);

    /**
     * Contributes a vertical icon tool strip to the left or right of the
     * modeling canvas. Hosts that do not provide a verified surface fail
     * closed.
     */
    default Registration contributeVerticalToolbar(final VerticalToolbarContribution contribution) {
        Objects.requireNonNull(contribution, "contribution");
        throw new UnsupportedOperationException("vertical-toolbar contribution is unavailable");
    }

    /**
     * Contributes a horizontal icon tool strip above or below the modeling
     * canvas. Hosts that do not provide a verified surface fail closed.
     */
    default Registration contributeHorizontalToolbar(final HorizontalToolbarContribution contribution) {
        Objects.requireNonNull(contribution, "contribution");
        throw new UnsupportedOperationException("horizontal-toolbar contribution is unavailable");
    }

    /**
     * Contributes a keyword filter box to a palette tab toolbar.
     *
     * <p>Hosts that do not provide a verified palette filter surface fail
     * closed with {@link UnsupportedOperationException}.</p>
     */
    default Registration contributePaletteFilter(PaletteFilterRegistry.PaletteFilterContribution contribution) {
        throw new UnsupportedOperationException("palette filter contribution is unavailable");
    }
}
