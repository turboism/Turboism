package dev.turboism.core.runtime.sidecar;

/**
 * The outcome of one sidecar run.
 *
 * <p>Build these through {@link #success(String)}, {@link #error(String, String)},
 * and {@link #timeout(String)} rather than the canonical constructor, which does
 * not itself enforce that the error fields agree with the kind. Every string
 * component is normalized from {@code null} to the empty string, so unused fields
 * read as {@code ""} rather than {@code null}.</p>
 *
 * @param kind         which of the three outcomes occurred
 * @param payload      the worker’s standard output, empty for failures
 * @param errorCode    machine-readable failure code, empty on success
 * @param errorMessage human-readable failure detail (typically the worker’s
 *                     standard error), empty on success
 * @throws IllegalArgumentException when {@code kind} is {@code null}
 */
public record SidecarResult(
    Kind kind,
    String payload,
    String errorCode,
    String errorMessage
) {

    public SidecarResult {
        if (kind == null) {
            throw new IllegalArgumentException("kind must not be null");
        }
        payload = payload == null ? "" : payload;
        errorCode = errorCode == null ? "" : errorCode;
        errorMessage = errorMessage == null ? "" : errorMessage;
    }

    /**
     * @param payload the worker’s standard output
     * @return a {@code SUCCESS} result carrying the payload and no error fields
     */
    public static SidecarResult success(final String payload) {
        return new SidecarResult(Kind.SUCCESS, payload, "", "");
    }

    /**
     * @param errorCode    machine-readable failure code
     * @param errorMessage human-readable detail
     * @return an {@code ERROR} result with an empty payload
     */
    public static SidecarResult error(final String errorCode, final String errorMessage) {
        return new SidecarResult(Kind.ERROR, "", errorCode, errorMessage);
    }

    /**
     * @param errorMessage human-readable detail, typically the destroyed process’s
     *                     standard error
     * @return a {@code TIMEOUT} result with an empty payload and the fixed error code
     *     {@code SIDECAR_TIMEOUT}
     */
    public static SidecarResult timeout(final String errorMessage) {
        return new SidecarResult(Kind.TIMEOUT, "", "SIDECAR_TIMEOUT", errorMessage);
    }

    public enum Kind {
        SUCCESS,
        ERROR,
        TIMEOUT
    }
}
