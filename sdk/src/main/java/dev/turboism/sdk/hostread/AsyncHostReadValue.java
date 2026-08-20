package dev.turboism.sdk.hostread;

/**
 * Marker for the payloads an asynchronous host read may deliver.
 *
 * <p>The hierarchy is sealed so every {@link AsyncHostReadIntent} maps to exactly one permitted
 * implementation and callers can switch over the results exhaustively.
 */
public sealed interface AsyncHostReadValue permits ProjectWorkspaceSnapshot {
}
