package dev.turboism.sdk.ui.toolbar;

import dev.turboism.sdk.plugin.Registration;

/**
 * Host-side registry through which a plugin adds buttons to an Editor palette's toolbar.
 *
 * <p>Implementations are supplied by the runtime; plugins never construct one. Contributions are
 * owned by the returned {@link Registration}, which is the only way to take a button away again —
 * there is no remove-by-id operation.</p>
 */
public interface PaletteToolbarRegistry {

    /**
     * Adds one toolbar button to the palette named by the contribution.
     *
     * @param contribution what to add, and where
     * @return a handle whose closure removes the contributed button
     */
    Registration contribute(PaletteToolbarContribution contribution);

    /**
     * A single toolbar button a plugin wants shown in a palette.
     *
     * @param contributionId identifier the plugin gives this contribution, used to tell its own
     *     contributions apart
     * @param actionId identifier of the action invoked when the button is pressed
     * @param labelKey i18n key resolved against the plugin's message bundle for the button label
     * @param iconResourcePath path of the icon resource within the plugin
     * @param paletteId identifier of the Editor palette whose toolbar receives the button
     * @param anchor placement hint relative to existing toolbar content
     * @param order sort key among contributions sharing the same anchor; lower sorts earlier
     */
    record PaletteToolbarContribution(
        String contributionId,
        String actionId,
        String labelKey,
        String iconResourcePath,
        String paletteId,
        String anchor,
        int order
    ) {}
}
