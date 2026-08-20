package dev.turboism.sdk.config;

/**
 * Why a typed config schema registration was refused by the runtime.
 *
 * <p>Distinct from {@link ConfigSchemaValidationError}, which covers schemas that are malformed in
 * themselves rather than rejected by the host.
 */
public enum ConfigRegistrationError {
    PERMISSION_DENIED,
    RUNTIME_UNAVAILABLE,
    REGISTRATION_FAILED
}
