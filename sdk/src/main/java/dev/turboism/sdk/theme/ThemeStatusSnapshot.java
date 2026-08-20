package dev.turboism.sdk.theme;

/**
 * An immutable description of the theme in effect at a moment in time.
 *
 * <p>Construction rejects a {@code null} or blank {@code themeId} or {@code displayName} with
 * {@link IllegalArgumentException}, so a snapshot always names a theme.
 *
 * @param themeId stable identifier of the active theme, never blank
 * @param displayName the theme's human-readable name as shown to the user, never blank
 * @param dark {@code true} when the theme is a dark variant, which UI code uses to choose contrasting
 *             assets such as icon tints
 */
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
