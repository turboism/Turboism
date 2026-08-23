package dev.turboism.plugin.projectpanel.b1.application;

/**
 * The outcome of one configuration binding operation, reported instead of thrown so panel
 * lifecycle code never fails on a persistence problem.
 *
 * <p>{@code APPLIED} means the in-memory confirmed state now matches what is stored;
 * {@code UNCHANGED} means nothing needed writing; {@code DISABLED} means the binding was not
 * enabled, or was disabled or re-enabled while the operation was in flight, so the result was
 * discarded. {@code PARTIAL_PERSISTENCE} is the notable one: some keys were written before a
 * later key failed, so storage holds a mix and the confirmed state has been re-read rather
 * than trusted. The rest report why the store refused: stale revision, missing permission, a
 * value the codec rejected, or an unavailable runtime.
 */
public enum ConfigBindingResult {
    APPLIED,
    UNCHANGED,
    DISABLED,
    REVISION_CONFLICT,
    PARTIAL_PERSISTENCE,
    PERMISSION_DENIED,
    INVALID_VALUE,
    RUNTIME_UNAVAILABLE
}
