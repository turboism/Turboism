package dev.turboism.sdk.event;

import dev.turboism.sdk.PreviewApi;

/**
 * Stable subscriber ordering bands, from earliest to latest admission into each
 * subscriber owner's delivery lane. Priorities order callbacks deterministically
 * within one publication snapshot; they do not serialize asynchronous callbacks
 * owned by different plugins.
 */
@PreviewApi
public enum EventPriority {
    HIGHEST,
    HIGH,
    NORMAL,
    LOW,
    LOWEST
}
