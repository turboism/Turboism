package dev.turboism.plugin.backup.b1.application;

/** Outcome of a WebDAV config binding operation. */
public enum ConfigBindingResult {
    APPLIED,
    UNCHANGED,
    DISABLED,
    PARTIAL_PERSISTENCE,
    REVISION_CONFLICT,
    PERMISSION_DENIED,
    INVALID_VALUE,
    RUNTIME_UNAVAILABLE
}
