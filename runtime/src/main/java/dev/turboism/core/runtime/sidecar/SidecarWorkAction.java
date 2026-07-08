package dev.turboism.core.runtime.sidecar;

/**
 * Marker for the type of work a sidecar unit represents.
 *
 * <p>This is intentionally a closed enum; the runtime sidecar is not yet open
 * to arbitrary action identifiers.
 */
public enum SidecarWorkAction {
    EXECUTE,
    QUERY
}
