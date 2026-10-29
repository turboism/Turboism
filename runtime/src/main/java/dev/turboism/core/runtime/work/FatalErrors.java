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
 * Multi-step cleanups may delay rethrowing fatal failures via {@link #mergeForCleanup(Throwable, Throwable)}
 * until all mandatory release steps execute, annotated locally with {@code @containment-exempt}.
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

    /**
     * Merges a cleanup or secondary failure into a primary failure, ensuring a fatal JVM condition
     * is always prioritized as the primary failure and suppressing the other failure onto it.
     *
     * <p>When either {@code primary} or {@code secondary} is an {@link InterruptedException}, the
     * current thread's interrupt flag is restored so pending cancellation is preserved across cleanup.
     * When {@code secondary} is fatal and {@code primary} is not fatal, {@code secondary} is
     * promoted to primary and {@code primary} is suppressed on it. Otherwise, {@code primary} remains
     * primary and {@code secondary} is suppressed on it. Nulls and identical instances are safely handled
     * without self-suppression.</p>
     *
     * @param primary initial or accumulated failure, nullable
     * @param secondary subsequent cleanup failure, nullable
     * @return the prioritized failure to propagate
     */
    public static Throwable mergeForCleanup(final Throwable primary, final Throwable secondary) {
        if (primary instanceof InterruptedException || secondary instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        if (primary == null) {
            return secondary;
        }
        if (secondary == null || primary == secondary) {
            return primary;
        }
        final boolean primaryFatal = isFatal(primary);
        final boolean secondaryFatal = isFatal(secondary);
        if (secondaryFatal && !primaryFatal) {
            secondary.addSuppressed(primary);
            return secondary;
        }
        primary.addSuppressed(secondary);
        return primary;
    }

    /**
     * Prepares {@code failure} to be rethrown in a {@code throw} statement: if {@code failure} is
     * an {@link Error}, it is thrown immediately; if it is a {@link RuntimeException}, it is returned
     * for the caller to throw; otherwise it is wrapped in an {@link IllegalStateException} and returned.
     *
     * @param failure the caught failure to rethrow
     * @return a RuntimeException suitable for throwing, or never returns if failure is an Error
     */
    public static RuntimeException propagate(final Throwable failure) {
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure instanceof RuntimeException runtime) {
            return runtime;
        }
        return new IllegalStateException(failure);
    }
}
