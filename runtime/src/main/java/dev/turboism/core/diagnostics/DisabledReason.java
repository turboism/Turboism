package dev.turboism.core.diagnostics;

public enum DisabledReason {
    INVALID_DESCRIPTOR,
    MISSING_DEPENDENCY,
    VERSION_MISMATCH,
    CYCLIC_DEPENDENCY,
    PERMISSION_DENIED,
    CLASSLOADER_FAILED,
    LOAD_FAILED,
    ENABLE_FAILED,
    UNKNOWN
}
