package dev.turboism.sdk.appearance;

import dev.turboism.sdk.PreviewApi;

/**
 * The underlying light/dark foundation an appearance is built on.
 *
 * <p>{@code NATIVE} means the Editor's own appearance is left in charge rather than a plugin
 * choosing a polarity.
 */
@PreviewApi
public enum AppearanceBase {
    NATIVE,
    LIGHT,
    DARK
}
