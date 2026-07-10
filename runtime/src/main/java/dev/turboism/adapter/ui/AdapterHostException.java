package dev.turboism.adapter.ui;

import java.util.Objects;

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

    public SafeModeDiagnostic diagnostic() {
        // Never forward host exception text into persisted diagnostics.
        return switch (code) {
            case TIMEOUT -> SafeModeDiagnostic.timeout(capabilityId, "Host adapter call timed out.");
            case VALIDATION_FAILURE -> SafeModeDiagnostic.validationFailure(
                capabilityId,
                "Host adapter rejected the request."
            );
            default -> throw new IllegalStateException("Unsupported adapter host failure code " + code);
        };
    }

    private static SafeModeDiagnostic.Code requireSupportedCode(final SafeModeDiagnostic.Code code) {
        Objects.requireNonNull(code, "code");
        if (code == SafeModeDiagnostic.Code.TIMEOUT || code == SafeModeDiagnostic.Code.VALIDATION_FAILURE) {
            return code;
        }
        throw new IllegalArgumentException("AdapterHostException supports TIMEOUT or VALIDATION_FAILURE only");
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
