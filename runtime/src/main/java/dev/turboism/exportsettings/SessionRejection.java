package dev.turboism.exportsettings;

/**
 * Bounded rejection carrying a stable failure identity for one armed protected-export
 * session: the {@code protected-export.*} key the terminal report publishes plus an
 * optional bounded diagnostic detail.
 *
 * <p>Thrown from anywhere in the session — the orchestrator's phases and the
 * {@link ProtectedExportModelCensus} drift checks alike — and unwrapped unchanged
 * across the EDT boundary, so the failure identity a caller sees never depends on
 * which thread observed the fault first. The stack trace is deliberately unused;
 * only the key and the detail are published.</p>
 */
final class SessionRejection extends RuntimeException {

    final String failureKey;
    final String detail;

    SessionRejection(final String failureKey) {
        this(failureKey, null);
    }

    SessionRejection(final String failureKey, final String detail) {
        super(failureKey);
        this.failureKey = failureKey;
        this.detail = detail;
    }
}
