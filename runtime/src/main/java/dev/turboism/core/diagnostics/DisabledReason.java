package dev.turboism.core.diagnostics;

/**
 * Why the runtime refused to keep a plugin active.
 *
 * <p>Ordered roughly along the lifecycle: descriptor and dependency resolution
 * problems, then permission refusal, then classloading, loading, and enable
 * failures. {@code UNKNOWN} is the fallback when no more specific cause was
 * determined.</p>
 */
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
