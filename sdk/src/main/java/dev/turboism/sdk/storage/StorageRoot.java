package dev.turboism.sdk.storage;

/**
 * The three directories a plugin may write to: {@code DATA} for
 * user-meaningful content, {@code STATE} for runtime-managed bookkeeping,
 * and {@code CACHE} for content that may be discarded.
 */
public enum StorageRoot {
    DATA,
    STATE,
    CACHE
}
