package dev.turboism.automesh;

import dev.turboism.core.runtime.contract.RuntimeCancellationToken;
import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshAlgorithm;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshCompute;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshRequest;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Runs {@link AutoMeshCompute} on an off-thread lane under a wall-clock budget and a cancellation
 * signal, and turns every algorithm fault into an {@link AutoMeshResult.Rejected}.
 *
 * <p>The lane is supplied by the caller: the runtime owns the threads, so this class never creates
 * one. A production binding passes the same bounded work lane the runtime gives plugin work; a test
 * passes a deterministic executor.</p>
 *
 * <p>The Editor UI thread is never used for compute. The thread that waits is also never blocked:
 * {@link #submit} returns a handle whose completion settles either with one result per request, with
 * a rejection, or with a timeout. Cancellation and timeout stop the <em>waiting</em> only — a compute
 * that ignores its token keeps the lane thread until it returns, so cancellation is cooperative by
 * contract.</p>
 */
public final class AutoMeshExecutor {

    /** Longest one algorithm run may hold the lane before the runtime stops waiting for it. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    private static final String REASON_COMPUTE_FAILED = "compute-failed";

    private final Executor computeLane;
    private final Duration timeout;

    /**
     * Creates an executor with {@link #DEFAULT_TIMEOUT}.
     *
     * @param computeLane the off-thread lane owned by the runtime caller
     */
    public AutoMeshExecutor(final Executor computeLane) {
        this(computeLane, DEFAULT_TIMEOUT);
    }

    /**
     * Creates an executor with an explicit budget.
     *
     * @param computeLane the off-thread lane owned by the runtime caller
     * @param timeout the wall-clock budget of one run; it must be positive
     */
    public AutoMeshExecutor(final Executor computeLane, final Duration timeout) {
        this.computeLane = Objects.requireNonNull(computeLane, "computeLane");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    /**
     * Starts one run of {@code algorithm} over every request of a selection.
     *
     * @param algorithm the algorithm whose compute function runs
     * @param requests one request per selected target, in selection order
     * @return a handle that settles once, on the lane thread or on a timeout thread
     * @throws IllegalArgumentException when {@code algorithm} has no compute function
     */
    public Handle submit(final AutoMeshAlgorithm algorithm, final List<AutoMeshRequest> requests) {
        Objects.requireNonNull(algorithm, "algorithm");
        Objects.requireNonNull(requests, "requests");
        final AutoMeshCompute compute = algorithm.compute();
        if (compute == null) {
            throw new IllegalArgumentException("the native algorithm has no compute function: " + algorithm.id());
        }
        final List<AutoMeshRequest> snapshot = List.copyOf(requests);
        final RuntimeCancellationToken cancellation = new RuntimeCancellationToken();
        final CompletableFuture<List<AutoMeshResult>> results = new CompletableFuture<>();
        try {
            computeLane.execute(() -> {
                if (results.isDone()) {
                    return;
                }
                results.complete(computeAll(compute, snapshot, cancellation));
            });
        } catch (RuntimeException refused) {
            // A lane that refuses admission is reported through the completion stage, because the
            // caller must never receive an algorithm or scheduler fault as a thrown exception.
            FatalErrors.rethrowIfFatal(refused);
            results.completeExceptionally(refused);
        }
        return new BoundedHandle(
                results.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS), results, cancellation, snapshot.size());
    }

    private static List<AutoMeshResult> computeAll(
            final AutoMeshCompute compute,
            final List<AutoMeshRequest> requests,
            final RuntimeCancellationToken cancellation) {
        final List<AutoMeshResult> results = new ArrayList<>(requests.size());
        for (final AutoMeshRequest request : requests) {
            if (cancellation.isCancellationRequested()) {
                return cancelledResults(requests.size());
            }
            results.add(computeOne(compute, request, cancellation));
        }
        return List.copyOf(results);
    }

    private static AutoMeshResult computeOne(
            final AutoMeshCompute compute, final AutoMeshRequest request, final RuntimeCancellationToken cancellation) {
        try {
            final AutoMeshResult result = compute.compute(request, cancellation);
            return result == null
                    ? new AutoMeshResult.Rejected(REASON_COMPUTE_FAILED, "The algorithm returned no result.")
                    : result;
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return new AutoMeshResult.Rejected(REASON_COMPUTE_FAILED, AutoMeshFailures.describe(failure));
        }
    }

    private static List<AutoMeshResult> cancelledResults(final int requestCount) {
        final List<AutoMeshResult> results = new ArrayList<>(requestCount);
        for (int index = 0; index < requestCount; index++) {
            results.add(AutoMeshResult.Cancelled.INSTANCE);
        }
        return List.copyOf(results);
    }

    /** One submitted run: its completion and the cancellation request that stops it. */
    public interface Handle {

        /**
         * Reports the run's settled outcome.
         *
         * @return a stage carrying one result per request; a timeout completes it exceptionally
         *     with a {@link java.util.concurrent.TimeoutException}, and a fatal JVM condition
         *     completes it exceptionally with that error
         */
        CompletionStage<List<AutoMeshResult>> completion();

        /**
         * Requests cancellation and settles the run as cancelled.
         *
         * @return {@code true} when this call settled the run, {@code false} when it had already
         *     finished and its results stay valid
         */
        boolean cancel();
    }

    private static final class BoundedHandle implements Handle {

        private final CompletableFuture<List<AutoMeshResult>> bounded;
        private final CompletableFuture<List<AutoMeshResult>> results;
        private final RuntimeCancellationToken cancellation;
        private final int requestCount;

        private BoundedHandle(
                final CompletableFuture<List<AutoMeshResult>> bounded,
                final CompletableFuture<List<AutoMeshResult>> results,
                final RuntimeCancellationToken cancellation,
                final int requestCount) {
            this.bounded = bounded;
            this.results = results;
            this.cancellation = cancellation;
            this.requestCount = requestCount;
        }

        @Override
        public CompletionStage<List<AutoMeshResult>> completion() {
            return bounded;
        }

        @Override
        public boolean cancel() {
            cancellation.cancel();
            return results.complete(cancelledResults(requestCount));
        }
    }
}
