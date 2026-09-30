package dev.turboism.plugin.psdimport.domain;

/**
 * The state of the PSD-import action lifecycle.
 *
 * <p>{@code DISABLED} and {@code ENABLED} are freely interchangeable; {@code SHUTDOWN} is terminal
 * — once reached, enable and disable requests are rejected rather than honoured.
 */
public enum PsdLifecycleState {
    DISABLED,
    ENABLED,
    SHUTDOWN
}
