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

    /**
     * Reports whether a live runtime surface backs this instance.
     *
     * @return {@code false} only for the {@link #unavailable()} sentinel
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * Returns this service's fail-closed {@code Unavailable} sentinel.
     *
     * @return the shared singleton; {@link #isAvailable()} is {@code false} only for it
     */
    static PaletteFilterRegistry unavailable() {
        return Unavailable.INSTANCE;
    }

    /** Sentinel returned by {@link #unavailable()}: unsupported calls throw a stable {@link UnsupportedOperationException}. */
    enum Unavailable implements PaletteFilterRegistry {
        INSTANCE;

        @Override public boolean isAvailable() {
            return false;
        }

        @Override public Registration contribute(final PaletteFilterContribution contribution) {
            java.util.Objects.requireNonNull(contribution, "contribution");
            throw new UnsupportedOperationException("paletteFilter registry is not available");
        }
    }


    /**
     * Descriptor of one palette filter-box contribution.
     *
     * @param contributionId stable identifier of this contribution
     * @param paletteId the {@code PALETTE_*} identifier of the palette tab to attach to
     * @param placeholderKey localization key for the filter box's placeholder text
     * @param order ordering position relative to other contributions on the same tab
     */
    record PaletteFilterContribution(
        String contributionId,
        String paletteId,
        String placeholderKey,
        int order
    ) {}
}
