package dev.turboism.core.runtime.sidecar;

public final class SidecarDispatchException extends RuntimeException {

    private final String diagnosticCode;

    public SidecarDispatchException(final String diagnosticCode, final String message) {
        super(message);
        if (diagnosticCode == null || diagnosticCode.isBlank()) {
            throw new IllegalArgumentException("diagnosticCode must not be blank");
        }
        this.diagnosticCode = diagnosticCode;
    }

    public String diagnosticCode() {
        return diagnosticCode;
    }
}
