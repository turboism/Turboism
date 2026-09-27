package dev.turboism.i18n;

/**
 * Where the localization machinery reports its own problems — a missing key, an unloadable
 * catalog, a pattern that will not format.
 *
 * <p>Diagnostics are informational: recording one never aborts localization, which always falls
 * back to a marker or the next catalog in the chain. Implementations are called from whichever
 * thread performs the lookup, including the Cubism host thread, so a sink must be cheap and must
 * not block or throw — a throwing sink propagates into the caller's translation call.
 */
@FunctionalInterface
public interface LocalizationDiagnosticSink {
    /**
     * Records one localization problem.
     *
     * <p>Called on whichever thread performs the lookup; implementations must be cheap and
     * must not block or throw — a throwing sink propagates into the caller's translation
     * call.</p>
     *
     * @param diagnostic the observed problem
     */
    void record(LocalizationDiagnostic diagnostic);
}
