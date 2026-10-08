package dev.turboism.plugin.acp;

/** Checked failure crossing the external ACP agent process boundary. */
final class AcpException extends Exception {

    private final Long code;

    AcpException(final String message) {
        this(message, (Long) null);
    }

    AcpException(final String message, final Throwable cause) {
        this(message, null, cause);
    }

    AcpException(final String message, final Long code) {
        this(message, code, null);
    }

    private AcpException(final String message, final Long code, final Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /** Returns the JSON-RPC error code reported by the agent, when the failure carried one. */
    Long code() {
        return code;
    }

    /** Returns whether the agent rejected the call until {@code authenticate} succeeds. */
    boolean authRequired() {
        return code != null && code == AcpClient.AUTH_REQUIRED_CODE;
    }
}
