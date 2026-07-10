package dev.turboism.adapter.ui;

import java.util.Objects;

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

    public static SafeModeDiagnostic adapterUnavailable(final String capabilityId) {
        return new SafeModeDiagnostic(
            Code.ADAPTER_UNAVAILABLE,
            capabilityId,
            "UI adapter is not connected; safe-mode fallback is active."
        );
    }

    public static SafeModeDiagnostic hostVersionUnsupported(final String hostVersion) {
        return new SafeModeDiagnostic(
            Code.HOST_VERSION_UNSUPPORTED,
            "adapter.ui",
            "Host Cubism version " + hostVersion + " is outside supported scope [5.3.0,5.4.0)."
        );
    }

    public static SafeModeDiagnostic capabilityUnavailable(final String capabilityId) {
        return new SafeModeDiagnostic(
            Code.CAPABILITY_UNAVAILABLE,
            capabilityId,
            "Host adapter does not provide capability " + capabilityId + "."
        );
    }

    public static SafeModeDiagnostic timeout(final String capabilityId, final String message) {
        return new SafeModeDiagnostic(Code.TIMEOUT, capabilityId, message);
    }

    public static SafeModeDiagnostic validationFailure(final String capabilityId, final String message) {
        return new SafeModeDiagnostic(Code.VALIDATION_FAILURE, capabilityId, message);
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
        TIMEOUT,
        VALIDATION_FAILURE
    }
}
