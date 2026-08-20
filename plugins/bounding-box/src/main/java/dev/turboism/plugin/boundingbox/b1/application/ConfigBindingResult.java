package dev.turboism.plugin.boundingbox.b1.application;

/**
 * Outcome of one settings-binding operation. Every operation completes with one of these rather
 * than by throwing, so the plugin's lifecycle never fails on a config problem.
 */
public enum ConfigBindingResult {
    /** The operation reached the store and the in-memory confirmed settings now match it. */
    APPLIED,
    /** The requested settings already equalled the confirmed settings; nothing was written. */
    UNCHANGED,
    /** The binding was disabled, or a newer epoch superseded this operation mid-flight. */
    DISABLED,
    /** The store's revision moved under the operation and a retry did not resolve it. */
    REVISION_CONFLICT,
    /**
     * A multi-key write failed partway: some keys were persisted and some were not, so the store
     * holds a mixture. The confirmed settings are re-read rather than assumed.
     */
    PARTIAL_PERSISTENCE,
    /** The plugin lacks the config permission the operation required. */
    PERMISSION_DENIED,
    /** The store rejected a value as invalid for its key. */
    INVALID_VALUE,
    /** The config registry was absent, not yet initialized, or failed unexpectedly. */
    RUNTIME_UNAVAILABLE
}
