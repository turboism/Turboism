package dev.turboism.sdk.ui.resource;

/** Current presentation availability; never an authorization or semantic-history confidence. */
public enum UiIconAvailability {
    /** The current verified binding has a validated resource ready for display. */
    AVAILABLE,
    /** No native resource provider is installed, or it has been disposed. */
    SERVICE_UNAVAILABLE,
    /** The active host identity or requested resource family has not been admitted. */
    HOST_UNVERIFIED,
    /** The admitted resource is missing, invalid, or exceeds its validation budget. */
    RESOURCE_UNAVAILABLE
}
