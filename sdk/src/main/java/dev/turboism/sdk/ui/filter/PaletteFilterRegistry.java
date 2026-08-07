package dev.turboism.sdk.ui.filter;

import dev.turboism.sdk.plugin.Registration;

/**
 * Registry for palette tab filter-box contributions.
 *
 * <p>A contribution asks the runtime to attach a keyword filter box (text field
 * with a clear button) to the toolbar of a named Cubism palette tab. The
 * runtime owns host UI adaptation, row filtering, placement, and disposal;
 * plugins submit descriptors only and never receive host widgets.</p>
 */
public interface PaletteFilterRegistry {

    /** Palette tab identifiers understood by the runtime palette filter host. */
    String PALETTE_PARAMETER = "PARAMETER";
    String PALETTE_DEFORMER = "DEFORMER";
    String PALETTE_SCENE = "SCENE";
    String PALETTE_LOG = "LOG";

    /**
     * Registers a palette filter-box contribution.
     *
     * @param contribution descriptor; must not be {@code null}
     * @return handle whose {@link Registration#close()} removes the filter box
     */
    Registration contribute(PaletteFilterContribution contribution);

    record PaletteFilterContribution(
        String contributionId,
        String paletteId,
        String placeholderKey,
        int order
    ) {}
}
