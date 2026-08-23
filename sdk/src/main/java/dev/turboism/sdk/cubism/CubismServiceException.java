package dev.turboism.sdk.cubism;

import java.util.Objects;

/**
 * Checked failure from a Cubism service call.
 *
 * <p>Carries a stable diagnostic code alongside the message so callers can branch on the failure
 * without parsing text.</p>
 */
public class CubismServiceException extends Exception {

    private final String code;

    /**
     * Creates a service failure.
     *
     * @param code the stable diagnostic code
     * @param message the human-readable failure description
     * @throws NullPointerException when {@code code} is null
     */
    public CubismServiceException(String code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    /**
     * Creates a service failure wrapping an underlying cause.
     *
     * @param code the stable diagnostic code
     * @param message the human-readable failure description
     * @param cause the underlying failure
     * @throws NullPointerException when {@code code} is null
     */
    public CubismServiceException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    /**
     * Returns the stable diagnostic code.
     *
     * @return the failure code, suitable for branching
     */
    public String code() {
        return code;
    }

    /**
     * Returns the failure description.
     *
     * @return the message, equivalent to {@link #getMessage()}
     */
    public String message() {
        return getMessage();
    }
}
