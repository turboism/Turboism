package dev.turboism.sdk.hostread;

/**
 * The kind of host state an asynchronous read asks for.
 *
 * <p>Each constant is bound to exactly one {@link AsyncHostReadValue} implementation, and
 * {@link AsyncHostReadResult} rejects a successful result whose value does not match its intent.
 * Only project/workspace snapshot reads are admitted today.
 */
public enum AsyncHostReadIntent {
    PROJECT_WORKSPACE_SNAPSHOT
}
