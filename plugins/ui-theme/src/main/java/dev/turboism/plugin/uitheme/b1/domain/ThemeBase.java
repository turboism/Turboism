package dev.turboism.plugin.uitheme.b1.domain;

/**
 * The appearance a theme's palette is designed against.
 *
 * <p>{@code ANY} means the theme does not commit either way and follows the host's native
 * appearance instead of forcing one.
 */
public enum ThemeBase {
    LIGHT,
    DARK,
    ANY
}
