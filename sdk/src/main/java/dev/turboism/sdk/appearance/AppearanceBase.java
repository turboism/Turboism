package dev.turboism.sdk.appearance;


/**
 * The underlying light/dark foundation an appearance is built on.
 *
 * <p>{@code NATIVE} means the Editor's own appearance is left in charge rather than a plugin
 * choosing a polarity.
 */
public enum AppearanceBase {
    NATIVE,
    LIGHT,
    DARK
}
