package dev.turboism.adapter.ui;

import java.util.Objects;

/**
 * Immutable record of why a capability fell back to safe mode, carried to the UI and to logs.
 *
 * <p>Messages are runtime-authored and free of host-supplied text, so a diagnostic is always safe
 * to persist. Instances are created through the static factories, one per {@link Code}; the
 * compact constructor rejects a null code and blank capability or message.</p>
 *
 * @param code machine-readable reason the capability degraded
 * @param capability ID of the capability that degraded, never blank
 * @param message human-readable explanation, never blank
 */
public record SafeModeDiagnostic(
    Code code,
    String capability,
    String message
) {

    public SafeModeDiagnostic {
        code = Objects.requireNonNull(code, "code");
        capability = requireText(capability, "capability");
        message = requireText(message, "message");
    }

    /**
     * @param capabilityId capability that cannot be served
     * @return diagnostic for a host adapter that is not connected at all, so every capability it
     *     backs is on its safe-mode fallback
     */
    public static SafeModeDiagnostic adapterUnavailable(final String capabilityId) {
        return new SafeModeDiagnostic(
            Code.ADAPTER_UNAVAILABLE,
            capabilityId,
            "Host adapter is not connected; safe-mode fallback is active."
        );
    }

    /**
     * @param capabilityId capability that cannot be served
     * @param hostVersion version the host reported, echoed into the message
     * @return diagnostic for a host outside the version scope this capability was reviewed against
     */
    public static SafeModeDiagnostic hostVersionUnsupported(
        final String capabilityId,
        final String hostVersion
    ) {
        return new SafeModeDiagnostic(
            Code.HOST_VERSION_UNSUPPORTED,
            capabilityId,
            "Host Cubism version " + hostVersion + " is outside the adapter's supported scope."
        );
    }

    /** @deprecated use {@link #hostVersionUnsupported(String, String)} */
    @Deprecated
    public static SafeModeDiagnostic hostVersionUnsupported(final String hostVersion) {
        return hostVersionUnsupported("adapter.host", hostVersion);
    }

    /**
     * @param capabilityId capability the host does not expose
     * @return diagnostic for a connected, in-scope host that simply does not provide this capability
     */
    public static SafeModeDiagnostic capabilityUnavailable(final String capabilityId) {
        return new SafeModeDiagnostic(
            Code.CAPABILITY_UNAVAILABLE,
            capabilityId,
            "Host adapter does not provide capability " + capabilityId + "."
        );
    }

    /**
     * @param capabilityId capability whose host call exceeded its budget
     * @param message runtime-authored explanation; must not embed host exception text
     * @return diagnostic for a host call that did not return in time
     */
    public static SafeModeDiagnostic timeout(final String capabilityId, final String message) {
        return new SafeModeDiagnostic(Code.TIMEOUT, capabilityId, message);
    }

    /**
     * @param capabilityId capability whose request the host rejected
     * @param message runtime-authored explanation; must not embed host exception text
     * @return diagnostic for a request the host refused as invalid
     */
    public static SafeModeDiagnostic validationFailure(final String capabilityId, final String message) {
        return new SafeModeDiagnostic(Code.VALIDATION_FAILURE, capabilityId, message);
    }

    /**
     * @param capabilityId capability whose mapping or profile evidence is missing
     * @return diagnostic for a capability blocked because its mapping evidence has not been verified
     */
    public static SafeModeDiagnostic mappingNotVerified(final String capabilityId) {
        return new SafeModeDiagnostic(
            Code.MAPPING_NOT_VERIFIED,
            capabilityId,
            "Required mapping/profile evidence is not verified."
        );
    }

    /**
     * @param capabilityId capability whose hook evidence is missing
     * @return diagnostic for a capability blocked because its host hook has not been verified
     */
    public static SafeModeDiagnostic hookNotVerified(final String capabilityId) {
        return new SafeModeDiagnostic(
            Code.HOOK_NOT_VERIFIED,
            capabilityId,
            "Required hook evidence is not verified."
        );
    }

    /**
     * @param capabilityId capability the caller is not permitted to use
     * @return diagnostic for a capability the host refused on permission grounds
     */
    public static SafeModeDiagnostic permissionDenied(final String capabilityId) {
        return new SafeModeDiagnostic(
            Code.PERMISSION_DENIED,
            capabilityId,
            "Permission denied for host adapter capability."
        );
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public enum Code {
        ADAPTER_UNAVAILABLE,
        HOST_VERSION_UNSUPPORTED,
        CAPABILITY_UNAVAILABLE,
        MAPPING_NOT_VERIFIED,
        HOOK_NOT_VERIFIED,
        PERMISSION_DENIED,
        TIMEOUT,
        VALIDATION_FAILURE
    }
}
