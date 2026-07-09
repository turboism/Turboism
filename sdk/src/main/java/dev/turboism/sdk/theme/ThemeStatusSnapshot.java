package dev.turboism.sdk.theme;

public record ThemeStatusSnapshot(
    String themeId,
    String displayName,
    boolean dark
) {
    public ThemeStatusSnapshot {
        if (themeId == null || themeId.isBlank()) {
            throw new IllegalArgumentException("themeId must not be null or blank");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be null or blank");
        }
    }
}
