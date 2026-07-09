package dev.turboism.sdk.ui;

public record OverlayContribution(String id, String anchor, int priority) {
    public OverlayContribution {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be null or blank");
        }
        if (anchor == null || anchor.isBlank()) {
            throw new IllegalArgumentException("anchor must not be null or blank");
        }
    }
}
