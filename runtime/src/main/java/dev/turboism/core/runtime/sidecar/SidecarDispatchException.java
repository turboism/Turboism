package dev.turboism.core.runtime.sidecar;

/**
 * Thrown when sidecar dispatch cannot even be attempted — the dispatcher is
 * disabled, or the supervisor has opened the circuit after repeated crashes.
 *
 * <p>Ordinary in-flight failures are reported as {@link SidecarResult} values
 * instead; this exception means no work was handed over at all. It always
 * carries a non-blank {@linkplain #diagnosticCode() diagnostic code}.</p>
 */
public final class SidecarDispatchException extends RuntimeException {

    private final String diagnosticCode;

    public SidecarDispatchException(final String diagnosticCode, final String message) {
        super(message);
        if (diagnosticCode == null || diagnosticCode.isBlank()) {
            throw new IllegalArgumentException("diagnosticCode must not be blank");
        }
        this.diagnosticCode = diagnosticCode;
    }

    /**
     * @return the stable machine-readable cause code (for example
     *     {@code SIDECAR_DISABLED} or {@code SIDECAR_UNAVAILABLE}), never blank
     */
    public String diagnosticCode() {
        return diagnosticCode;
    }
}
