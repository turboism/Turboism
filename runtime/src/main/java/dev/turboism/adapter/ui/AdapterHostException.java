package dev.turboism.adapter.ui;

import java.util.Objects;

/**
 * Unchecked failure raised when a host adapter call cannot be completed, carrying the
 * safe-mode code and the capability ID that the caller should degrade.
 *
 * <p>Only {@link SafeModeDiagnostic.Code#TIMEOUT}, {@link SafeModeDiagnostic.Code#MAPPING_NOT_VERIFIED}
 * and {@link SafeModeDiagnostic.Code#VALIDATION_FAILURE} may be constructed; any other code is
 * rejected with {@link IllegalArgumentException}. The exception message is for logs only and is
 * never copied into the diagnostic handed back to plugins.</p>
 */
public final class AdapterHostException extends RuntimeException {

    private final SafeModeDiagnostic.Code code;
    private final String capabilityId;

    public AdapterHostException(
        final SafeModeDiagnostic.Code code,
        final String capabilityId,
        final String message
    ) {
        super(requireText(message, "message"));
        this.code = requireSupportedCode(code);
        this.capabilityId = requireText(capabilityId, "capabilityId");
    }

    /**
     * Translates this failure into the diagnostic that is safe to persist and expose to plugins.
     * Host-supplied exception text is deliberately dropped and replaced with a fixed message, so
     * nothing from the host leaks into stored diagnostics.
     *
     * @return a diagnostic carrying this failure's code and capability ID
     * @throws IllegalStateException if the code is outside the supported set (unreachable while the
     *     constructor guard holds)
     */
    public SafeModeDiagnostic diagnostic() {
        // Never forward host exception text into persisted diagnostics.
        return switch (code) {
            case TIMEOUT -> SafeModeDiagnostic.timeout(capabilityId, "Host adapter call timed out.");
            case MAPPING_NOT_VERIFIED -> SafeModeDiagnostic.mappingNotVerified(capabilityId);
            case VALIDATION_FAILURE -> SafeModeDiagnostic.validationFailure(
                capabilityId,
                "Host adapter rejected the request."
            );
            default -> throw new IllegalStateException("Unsupported adapter host failure code " + code);
        };
    }

    private static SafeModeDiagnostic.Code requireSupportedCode(final SafeModeDiagnostic.Code code) {
        Objects.requireNonNull(code, "code");
        if (code == SafeModeDiagnostic.Code.TIMEOUT
            || code == SafeModeDiagnostic.Code.MAPPING_NOT_VERIFIED
            || code == SafeModeDiagnostic.Code.VALIDATION_FAILURE) {
            return code;
        }
        throw new IllegalArgumentException(
            "AdapterHostException supports TIMEOUT, MAPPING_NOT_VERIFIED, or VALIDATION_FAILURE only"
        );
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
