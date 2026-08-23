package dev.turboism.sdk.event;

import dev.turboism.sdk.PreviewApi;

/**
 * Stable subscriber ordering bands, from earliest to latest invocation.
 */
@PreviewApi
public enum EventPriority {
    HIGHEST,
    HIGH,
    NORMAL,
    LOW,
    LOWEST
}
