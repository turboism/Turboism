package dev.turboism.core.runtime.work;

/**
 * Shared classification for {@code catch (Throwable)} containment sites: which failures are
 * containable plugin or host faults and which are fatal JVM conditions that must keep
 * propagating.
 *
 * <p>{@link VirtualMachineError} (including {@link OutOfMemoryError} and
 * {@link StackOverflowError}) and {@link ThreadDeath} are always fatal: containing them
 * leaves a degraded runtime alive and breaks thread-stop semantics. {@link LinkageError}
 * is deliberately containable — it usually means one plugin JAR is incompatible, and the
 * site can diagnose and disable that plugin rather than aborting the whole work lane.
 * {@link InterruptedException} stays containable too, but {@link #rethrowIfFatal(Throwable)}
 * restores the interrupt flag first so deferred cancellation is not silently dropped.
 *
 * <p>Every {@code catch (Throwable)} site must call {@link #rethrowIfFatal(Throwable)} (or
 * an equivalent fatal-first catch clause) before applying its own diagnostics, so the
 * policy stays searchable and auditable instead of being re-decided per call site.
 */
public final class FatalErrors {

    private FatalErrors() {}

    /**
     * Rethrows {@code failure} when it is a fatal JVM condition — a
     * {@link VirtualMachineError} or {@link ThreadDeath} — and returns otherwise, letting
     * the caller proceed with ordinary containment handling. When {@code failure} is an
     * {@link InterruptedException}, the current thread's interrupt flag is restored before
     * returning so the pending cancellation still reaches the next blocking call.
     *
     * @param failure the caught throwable to classify
     */
    public static void rethrowIfFatal(final Throwable failure) {
        if (failure instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        if (isFatal(failure)) {
            throw fatal(failure);
        }
    }

    /**
     * Returns whether {@code failure} is a fatal JVM condition that must escape
     * containment, for sites that branch instead of delegating to
     * {@link #rethrowIfFatal(Throwable)}.
     *
     * @param failure the throwable to classify
     * @return {@code true} when {@code failure} is a {@link VirtualMachineError} or
     *     {@link ThreadDeath}
     */
    public static boolean isFatal(final Throwable failure) {
        return failure instanceof VirtualMachineError || failure instanceof ThreadDeath;
    }

    private static Error fatal(final Throwable failure) {
        return (Error) failure;
    }
}
