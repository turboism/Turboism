package dev.turboism.plugin.backup.b1.application;

/** Outcome of a WebDAV config binding operation. */
public enum ConfigBindingResult {
    APPLIED,
    DISABLED,
    PERMISSION_DENIED,
    RUNTIME_UNAVAILABLE
}
