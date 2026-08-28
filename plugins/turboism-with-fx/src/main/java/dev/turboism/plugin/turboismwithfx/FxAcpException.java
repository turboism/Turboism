package dev.turboism.plugin.turboismwithfx;

/** Checked failure crossing the managed-or-custom fx ACP process boundary. */
final class FxAcpException extends Exception {

    FxAcpException(final String message) {
        super(message);
    }

    FxAcpException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
