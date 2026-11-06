package dev.turboism.kernel;

import dev.turboism.core.event.RuntimeEventBroker;
import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.sdk.plugin.DisposableScope;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * One generation admitted on the bounded lifecycle lane, from its first lane task to the last
 * stage that releases what it built.
 *
 * <p>The module owns exactly the lifecycle protocol every admitted kind shares: the admission
 * guard exists before the worker starts, the deadline is measured from the worker's own start, a
 * fenced lease can never commit, and teardown is <em>fence first</em> — event delivery and SDK
 * admission stop before any destructive stage — with one-shot stage semantics. Each stage is
 * attempted at most once and records its own outcome, so an unprovable pass is <em>retained</em>
 * in {@link RetainedPluginGenerations} and re-driven on the lane instead of being released: the
 * resource loader is never closed while the disposable scope is still unclosed, an executor set
 * captured at fencing is released only at the terminal tail, and the event owner is closed last.
 *
 * <p>Kind-specific content — the classloader and entrypoint rollback bodies of a plugin, the
 * shell handle of the runtime-owned shell — is supplied by an {@link Content} adapter; the
 * adapter never writes a stage flag. Admission gaps that end this generation's work
 * ({@link Gate}) are reported to the caller, which alone decides what an operator-visible verdict
 * is: this module publishes none, so a kind that must stay silent (the shell is not a plugin
 * lifecycle participant) can use it unchanged.
 *
 * <p>The only adapter so far is the plugin kind; the runtime-owned shell is the second one the
 * design brings over. The module never names either, so a kind that must not participate in
 * plugin lifecycle events can adopt it unchanged.
 */
final class AdmittedGeneration {

    /** Where a teardown pass is coming from. Adapters map it to their own stage bodies. */
    enum Cause {
        /** A load that failed, timed out or was rejected: tear down what the attempt built. */
        LOAD_FAILED,
        /** A live generation's ordered close. */
        CLOSE
    }

    /**
     * The gate that ended a teardown pass early. Every gate is retryable: the generation stays
     * retained and the same pass is re-driven once the gate opens.
     */
    enum Gate {
        /** Not deferred. */
        NONE,
        /** Admitted event callbacks have not quiesced. */
        EVENT_QUIESCENCE,
        /** Backup work has not quiesced. */
        BACKUP_QUIESCENCE,
        /** SDK calls admitted before the fence have not drained. */
        SDK_DRAIN,
        /** The caller's "other generations are inert" barrier has not opened. */
        OTHER_GENERATIONS
    }

    /**
     * Kind-specific content of one generation. The plugin kind (a JAR, entrypoints, hook
     * registries, lifecycle events) supplies one content object per phase — admission and close own
     * different collaborators — and the shell kind (a shell handle, no classloader of its own, no
     * lifecycle events) follows. Every method exists because the kinds genuinely differ; several
     * are no-ops for one of them.
     *
     * @param <T> the value the commit section publishes
     */
    interface Content<T> {

        /** Lane id, log attribution and retention key. */
        String id();

        /**
         * @return {@code true} when this kind owns a plugin executor set that must be captured at
         *     fencing and released at the terminal tail. The shell has no subscriber-derived
         *     executor surface, so it claims nothing.
         */
        boolean claimsExecutors();

        /**
         * Publishes this generation's handles into {@code shared}. Runs before the first lane task
         * is submitted, so the admission guard a fence needs always exists.
         */
        void bind(GenerationState shared);

        /**
         * The lane task body up to but excluding activation. May commit through {@code lease}.
         *
         * @return the value the commit section published
         */
        T start(GenerationState shared, PluginLifecycleLease lease) throws Exception;

        /**
         * Captures this kind's executor set. Called at every fence, so it must be idempotent; the
         * shell does nothing.
         */
        void claimExecutors(GenerationState shared, Cause cause);

        /** Drops every hook registration this generation owns. May throw: the pass then fails. */
        void unregisterHooks(GenerationState shared);

        /**
         * Quiesces backup work before destructive stages.
         *
         * @return {@code false} when backup work did not quiesce; the pass defers and is re-driven
         */
        boolean quiesceBackup(GenerationState shared);

        /**
         * One-shot teardown body: disable+shutdown, or the shell's own close. Its own failures are
         * the adapter's verdict data — a thrown {@code shutdown()} still lets this generation be
         * released once disposal is proven — so the module only tracks that it was attempted.
         */
        void closeBody(GenerationState shared, Cause cause);

        /** One-shot disposal of the disposable scope. */
        StageOutcome closeScope(GenerationState shared);

        /** Disposal of the released resource loader, reported after the scope stage. */
        StageOutcome closeResources(GenerationState shared);

        /** Terminal stage after disposal: the plugin unload verdict; no-op for the shell. */
        void finishDisposal(GenerationState shared);

        /** Releases this kind's executor set at the terminal tail; the shell does nothing. */
        void releaseExecutors(GenerationState shared, Cause cause);

        /**
         * Operator-visible wording this kind uses when a pass leaves its cleanup incomplete.
         *
         * @return the log message, or {@code null} for a kind whose own verdict data already
         *     reports incompleteness
         */
        String incompleteMessage();

        /** Operator-visible wording this kind uses when its generation is retained. */
        String retainedMessage();
    }

    /**
     * Outcome of one one-shot stage.
     *
     * @param attempted whether the stage ran; the module keeps its one-shot flag only when it did
     * @param done whether the stage completed; a stage that ran and failed keeps its recorded
     *     outcome and is never retried — a retry of {@code DisposableScope.close()} would be an
     *     empty success that erases the failure and frees the resource loader early
     */
    record StageOutcome(boolean attempted, boolean done) {

        static StageOutcome succeeded() {
            return new StageOutcome(true, true);
        }

        /** Attempted and failed: sticky, never retried. */
        static StageOutcome failed() {
            return new StageOutcome(true, false);
        }
    }

    /**
     * What one teardown pass proved.
     *
     * @param complete every mandatory stage ran and disposal was proven
     * @param gate the gate that ended the pass early, or {@link Gate#NONE}
     * @param disposalProven the scope and the resource loader verifiably closed
     */
    record TeardownReport(boolean complete, Gate gate, boolean disposalProven) {

        TeardownReport {
            Objects.requireNonNull(gate, "gate");
        }

        /** @return {@code true} when a retryable gate deferred this pass */
        boolean deferred() {
            return gate != Gate.NONE;
        }
    }

    /**
     * Composition the module works from.
     *
     * @param policy deadlines and quiescence bounds
     * @param lane the bounded lane every lifecycle body runs on
     * @param retention where an unprovable generation is tracked
     * @param log operator-visible log
     */
    record Admission(
            PluginLifecyclePolicy policy,
            PluginLifecycleLane lane,
            RetainedPluginGenerations retention,
            KernelLog log) {

        Admission {
            Objects.requireNonNull(policy, "policy");
            Objects.requireNonNull(lane, "lane");
            Objects.requireNonNull(retention, "retention");
            Objects.requireNonNull(log, "log");
        }
    }

    /**
     * Mutable state of one generation: the handles the adapters build plus every sticky one-shot
     * flag. Written by the module only; adapters publish handles through {@link Content#bind} and
     * {@link Content#start}. Fields an awaiting caller touches while the lane worker is still
     * running (fencing, retention probes) are volatile.
     */
    static final class GenerationState {
        volatile PluginGenerationGuard guard;
        volatile RuntimeEventBroker.Owner eventOwner;
        volatile DisposableScope scope;
        volatile long workerStartedNanos;
        volatile long completedNanos;
        volatile boolean cleanupComplete;
        volatile boolean eventOwnerQuiesced;
        volatile boolean hooksAttempted;
        volatile boolean backupAttempted;
        volatile boolean backupDone;
        volatile boolean bodyAttempted;
        volatile boolean scopeAttempted;
        volatile boolean scopeClosed;
        volatile boolean resourcesAttempted;
        volatile boolean resourcesClosed;
        volatile boolean disposalFinished;
        volatile boolean executorReleased;
        volatile boolean eventOwnerCloseAttempted;
        volatile boolean eventOwnerClosed;
        volatile boolean retentionLogged;
    }

    /**
     * One admitted generation waiting for its lane task. The deadline is measured against the
     * worker's own start, so queueing behind other generations does not consume the kind's budget.
     *
     * @param <T> the value the commit section publishes
     */
    static final class Submission<T> {
        private final AdmittedGeneration generation;
        private final PluginLifecycleLane.Invocation<T> invocation;
        private final PluginLifecycleLease lease;
        private final long startedNanos;

        private Submission(
                final AdmittedGeneration generation,
                final PluginLifecycleLane.Invocation<T> invocation,
                final PluginLifecycleLease lease,
                final long startedNanos) {
            this.generation = generation;
            this.invocation = invocation;
            this.lease = lease;
            this.startedNanos = startedNanos;
        }

        /**
         * The handle of the generation this submission admitted, for a kind whose caller keeps the
         * generation open after a committed admission instead of re-adopting it for close. The
         * handle shares this submission's stage state.
         */
        Handle handle() {
            return new Handle(generation);
        }

        /**
         * @return when the worker exits the lane task body — the quiescence signal retention waits
         *     for; a cancelled future is not evidence the code stopped
         */
        CompletableFuture<Void> workerDone() {
            return invocation.workerDone;
        }

        /**
         * Waits for the lane task under the kind's deadline and resolves every failure shape:
         * a timeout and a rejection fence immediately, a failure retains whatever the worker left
         * incomplete, and only a committed success returns a value.
         */
        Outcome<T> await() {
            final Admission admission = generation.admission;
            final GenerationState state = generation.state;
            final long workerStarted = state.workerStartedNanos;
            final Duration budget = generation.deadline;
            final Duration awaitTimeout = workerStarted == 0L
                    ? budget
                    : Duration.ofNanos(Math.max(0L, budget.toNanos() - (System.nanoTime() - workerStarted)));
            final PluginLifecycleLane.AwaitResult<T> result = admission.lane().await(invocation, awaitTimeout, lease);
            final long finishedNanos = state.completedNanos;
            final long effectiveStart = workerStarted == 0L ? startedNanos : workerStarted;
            final long elapsedMillis = Duration.ofNanos(
                            Math.max(0L, (finishedNanos == 0L ? System.nanoTime() : finishedNanos) - effectiveStart))
                    .toMillis();
            return switch (result.outcome) {
                case SUCCEEDED -> new Outcome<>(result.outcome, result.value, null, elapsedMillis);
                case FAILED -> {
                    // The worker's own failure path already fenced and tore down; all that is left
                    // is retaining whatever it could not prove complete.
                    if (!state.cleanupComplete) {
                        generation.retain(invocation.workerDone, Cause.LOAD_FAILED, () -> true, null);
                    }
                    yield new Outcome<>(result.outcome, null, result.failure, elapsedMillis);
                }
                case REJECTED -> {
                    // The task never ran: nothing was constructed. Fence the pre-created guard
                    // anyway so no reference to it can admit work later.
                    generation.fence(Cause.LOAD_FAILED);
                    yield new Outcome<>(result.outcome, null, null, elapsedMillis);
                }
                case TIMED_OUT -> {
                    generation.fence(Cause.LOAD_FAILED);
                    // The worker may have finished its own rollback after the caller stopped
                    // waiting; a complete teardown owes nothing to retention.
                    if (!state.cleanupComplete) {
                        generation.retain(invocation.workerDone, Cause.LOAD_FAILED, () -> true, null);
                    }
                    yield new Outcome<>(result.outcome, null, null, elapsedMillis);
                }
            };
        }
    }

    /**
     * Result of one admission attempt. {@code value} is {@code null} unless the commit section
     * won the lease race.
     */
    record Outcome<T>(PluginLifecycleLane.Outcome kind, T value, Throwable failure, long elapsedMillis) {

        Outcome {
            Objects.requireNonNull(kind, "kind");
        }
    }

    /** Handle of an already admitted generation whose teardown is still owed. */
    static final class Handle {
        private final AdmittedGeneration generation;

        private Handle(final AdmittedGeneration generation) {
            this.generation = generation;
        }

        /** Immediate non-blocking fence; safe while the lane worker is inside plugin code. */
        void fence(final Cause cause) {
            generation.fence(cause);
        }

        /**
         * Runs one ordered teardown pass on the lane under {@link PluginLifecyclePolicy#closeTimeout()}.
         *
         * @param otherGenerationsInert caller-supplied barrier over other retained generations;
         *     the shell body, scope and owner are not released while plugin teardown can still run.
         *     A plugin close passes {@code () -> true}
         */
        CloseAttempt close(final BooleanSupplier otherGenerationsInert) {
            final PluginLifecycleLane.Invocation<TeardownReport> invocation = generation
                    .admission
                    .lane()
                    .submit(
                            generation.content.id(),
                            "close",
                            () -> generation.teardown(Cause.CLOSE, true, otherGenerationsInert));
            final PluginLifecycleLane.AwaitResult<TeardownReport> result = generation
                    .admission
                    .lane()
                    .await(invocation, generation.admission.policy().closeTimeout(), null);
            return new CloseAttempt(result.outcome, result.value, result.failure, invocation.workerDone);
        }

        /**
         * Retains this generation so the watcher re-drives
         * {@link #teardown(Cause, boolean, BooleanSupplier)} instead of dropping the references.
         *
         * <p>Unconditional on purpose: a close retains whenever its teardown is incomplete
         * <em>or</em> a verdict is still owed, and a pass the worker already finished still owes
         * its caller the release decision. A complete no-op pass ends the retention on the next
         * tick.
         *
         * @param onRetainedPass observes every re-drive pass, for a caller that still owes an
         *     operator-visible verdict when the deferred cleanup finally completes
         */
        void retain(
                final CompletableFuture<Void> workerDone,
                final Cause cause,
                final BooleanSupplier otherGenerationsInert,
                final Consumer<TeardownReport> onRetainedPass) {
            generation.retain(workerDone, cause, otherGenerationsInert, onRetainedPass);
        }

        TeardownReport teardown(
                final Cause cause, final boolean awaitEvents, final BooleanSupplier otherGenerationsInert) {
            return generation.teardown(cause, awaitEvents, otherGenerationsInert);
        }
    }

    /**
     * One close pass: the lane outcome, the report of a returned pass, and the future that says
     * when the worker actually exited.
     *
     * @param report the pass report; {@code null} when the task did not return one
     * @param workerDone completes when the worker exits the task body — the quiescence signal
     *     retention waits for; a cancelled future is not evidence the code stopped
     */
    record CloseAttempt(
            PluginLifecycleLane.Outcome outcome,
            TeardownReport report,
            Throwable failure,
            CompletableFuture<Void> workerDone) {

        CloseAttempt {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(workerDone, "workerDone");
        }
    }

    private final Content<?> content;
    private final Admission admission;
    private final GenerationState state;
    private final Duration deadline;

    private AdmittedGeneration(
            final Content<?> content, final Admission admission, final GenerationState state, final Duration deadline) {
        this.content = content;
        this.admission = admission;
        this.state = state;
        this.deadline = deadline;
    }

    /**
     * Admits one generation: binds its handles, creates the admission guard, and submits the lane
     * task. The caller awaits through {@link Submission#await()}.
     *
     * @param deadline this kind's admission deadline — a plugin load budget, a wider shell load
     *     budget — measured from the worker's own start, not from submission
     */
    static <T> Submission<T> submit(final Content<T> content, final Admission admission, final Duration deadline) {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(admission, "admission");
        Objects.requireNonNull(deadline, "deadline");
        final GenerationState state = new GenerationState();
        content.bind(state);
        final AdmittedGeneration generation = new AdmittedGeneration(content, admission, state, deadline);
        final PluginLifecycleLease lease = new PluginLifecycleLease(content.id());
        final long startedNanos = System.nanoTime();
        final PluginLifecycleLane.Invocation<T> invocation = admission.lane().submit(content.id(), "load", () -> {
            state.workerStartedNanos = System.nanoTime();
            try {
                return content.start(state, lease);
            } catch (Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                // Every failed generation is torn down — ordinary failures too, not only
                // timeouts — before the caller observes the exception.
                generation.teardown(Cause.LOAD_FAILED, true, () -> true);
                throw failure;
            } finally {
                state.completedNanos = System.nanoTime();
            }
        });
        return new Submission<>(generation, invocation, lease, startedNanos);
    }

    /** Adopts an already admitted generation that only owes its ordered teardown. */
    static Handle adopt(final Content<?> content, final Admission admission) {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(admission, "admission");
        final GenerationState state = new GenerationState();
        content.bind(state);
        return new Handle(new AdmittedGeneration(content, admission, state, Duration.ZERO));
    }

    /**
     * One idempotent, fence-first teardown pass in the one order every kind uses: fence, event
     * quiescence, hook unregistration, backup quiescence, the drain barrier, the kind's own body,
     * scope and resource disposal, the kind's terminal stage, the executor tail, and the event
     * owner last.
     *
     * @param awaitEvents whether event quiescence may block up to the policy bound; a retention
     *     re-drive passes {@code false} because readiness is already gated
     * @return what this pass proved; every non-{@link Gate#NONE} gate leaves the remaining stages
     *     untouched for the next pass
     */
    TeardownReport teardown(final Cause cause, final boolean awaitEvents, final BooleanSupplier otherGenerationsInert) {
        fence(cause);
        final Duration quiescenceBound = awaitEvents ? admission.policy().eventQuiescenceTimeout() : Duration.ZERO;
        if (!state.eventOwnerQuiesced) {
            final RuntimeEventBroker.Owner eventOwner = state.eventOwner;
            if (eventOwner == null) {
                state.eventOwnerQuiesced = true;
            } else {
                if (!eventOwner.awaitQuiescence(quiescenceBound)) {
                    return deferred(Gate.EVENT_QUIESCENCE);
                }
                state.eventOwnerQuiesced = true;
            }
        }
        if (!state.hooksAttempted) {
            state.hooksAttempted = true;
            content.unregisterHooks(state);
        }
        if (!state.backupDone) {
            state.backupAttempted = true;
            if (!content.quiesceBackup(state)) {
                return deferred(Gate.BACKUP_QUIESCENCE);
            }
            state.backupDone = true;
        }
        // Admitted SDK work must drain before teardown bodies: disable()/shutdown() can destroy
        // plugin state and resources an in-flight pre-fence call still touches.
        if (state.guard != null && !state.guard.drained()) {
            return deferred(Gate.SDK_DRAIN);
        }
        if (otherGenerationsInert != null && !otherGenerationsInert.getAsBoolean()) {
            return deferred(Gate.OTHER_GENERATIONS);
        }
        if (!state.bodyAttempted) {
            state.bodyAttempted = true;
            content.closeBody(state, cause);
        }
        // One-shot disposal: DisposableScope.close() marks itself closed before running closers,
        // so retrying after a failure would be an empty success that erases the recorded failure
        // and frees the resource loader early. Attempted and outcome stay separate.
        if (!state.scopeAttempted) {
            state.scopeAttempted = true;
            state.scopeClosed = content.closeScope(state).done();
        }
        if (!state.resourcesAttempted) {
            final StageOutcome resources = content.closeResources(state);
            state.resourcesAttempted = resources.attempted();
            state.resourcesClosed = resources.done();
        }
        if (!state.disposalFinished) {
            state.disposalFinished = true;
            content.finishDisposal(state);
        }
        // Executor release sits at the tail — past every deferral return — so late completion
        // submissions during scope close still land on the claimed set, and so the set dies on
        // the terminal pass whether cleanup succeeded or stays retained for a failed step.
        if (!state.executorReleased) {
            state.executorReleased = true;
            content.releaseExecutors(state, cause);
        }
        // The event owner is closed last: it is the delivery surface every other stage may still
        // depend on, and the context factory's scope closer closes it as a backstop.
        if (!state.eventOwnerClosed) {
            state.eventOwnerCloseAttempted = true;
            if (state.eventOwner != null) {
                state.eventOwner.close();
            }
            state.eventOwnerClosed = true;
        }
        // A stage that threw is attempted, not done: it is never retried — one-shot semantics —
        // and its own adapter already recorded the failure, so the pass is still complete once
        // every mandatory stage has been attempted and disposal is proven.
        final boolean complete = state.eventOwnerQuiesced
                && state.hooksAttempted
                && state.backupDone
                && state.bodyAttempted
                && state.scopeClosed
                && state.resourcesClosed
                && state.eventOwnerClosed;
        state.cleanupComplete = complete;
        if (!complete && !state.retentionLogged) {
            state.retentionLogged = true;
            final String message = content.incompleteMessage();
            if (message != null) {
                admission.log().error(content.id(), message, new IllegalStateException(message));
            }
        }
        return new TeardownReport(complete, Gate.NONE, state.scopeClosed && state.resourcesClosed);
    }

    /**
     * Immediately denies new work for this generation: the admission guard closes, event delivery
     * stops, this kind's executor set is captured before any reload can replace it, and the scope
     * seals. Non-blocking and idempotent; safe while the worker is still inside plugin code.
     */
    void fence(final Cause cause) {
        final PluginGenerationGuard guard = state.guard;
        if (guard != null) {
            guard.fence();
        }
        final RuntimeEventBroker.Owner eventOwner = state.eventOwner;
        if (eventOwner != null) {
            try {
                eventOwner.beginClosing();
            } catch (Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                admission.log().error(content.id(), "Generation event owner fencing failed safely", failure);
            }
        }
        // Claiming is idempotent per generation, but it must never run after the terminal
        // executor release: a claim allocates a replacement set for the plugin id that no
        // later stage would shut down again.
        if (!state.executorReleased) {
            content.claimExecutors(state, cause);
        }
        final DisposableScope scope = state.scope;
        if (scope != null) {
            scope.seal();
        }
    }

    private void retain(
            final CompletableFuture<Void> workerDone,
            final Cause cause,
            final BooleanSupplier otherGenerationsInert,
            final Consumer<TeardownReport> onRetainedPass) {
        admission
                .retention()
                .retain(new RetainedPluginGenerations.RetainedGeneration(
                        content.id(),
                        workerDone,
                        state.eventOwner,
                        state.guard,
                        // Dormant only once every stage that can still run plugin code — teardown
                        // body, scope closers, resource disposal — has been attempted and the
                        // executor set is gone. A still-retained generation that has only idle
                        // probes left is inert, and the drain barrier may pass it; anything else
                        // keeps the barrier closed.
                        () -> state.bodyAttempted
                                && state.scopeAttempted
                                && state.resourcesAttempted
                                && state.eventOwnerCloseAttempted
                                && (state.executorReleased || !content.claimsExecutors()),
                        () -> {
                            final TeardownReport report = teardown(cause, false, otherGenerationsInert);
                            if (onRetainedPass != null) {
                                onRetainedPass.accept(report);
                            }
                            return report.complete();
                        }));
        admission.log().warn(content.id(), content.retainedMessage());
    }

    private TeardownReport deferred(final Gate gate) {
        return new TeardownReport(false, gate, state.scopeClosed && state.resourcesClosed);
    }
}
