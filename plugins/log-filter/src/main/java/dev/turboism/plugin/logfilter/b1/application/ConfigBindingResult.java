package dev.turboism.plugin.logfilter.b1.application;

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
