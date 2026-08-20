package dev.turboism.sdk.ui;

/**
 * Declares a plugin overlay to be attached to a named host anchor point.
 *
 * @param id       contribution identity, non-blank
 * @param anchor   host anchor the overlay attaches to, non-blank
 * @param priority ordering weight among overlays sharing the same anchor
 */
public record OverlayContribution(String id, String anchor, int priority) {
    /**
     * Validates the record components.
     *
     * @throws IllegalArgumentException when {@code id} or {@code anchor} is null or blank
     */
    public OverlayContribution {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be null or blank");
        }
        if (anchor == null || anchor.isBlank()) {
            throw new IllegalArgumentException("anchor must not be null or blank");
        }
    }
}
