package dev.turboism.adapter.cubism.core;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Short-lived, borrowed model lease scoped to one provider/profile and model generation.
 *
 * <p>The raw model is visible only to a package-private scoped callback. Closing this lease
 * releases borrow bookkeeping and deliberately never invokes any method on the Core model.</p>
 */
final class CoreModelLease implements AutoCloseable {

    private final long generation;
    private final String modelIdentity;
    private final String providerId;
    private final String artifactProfile;
    private final LongSupplier currentGeneration;
    private Object borrowedModel;
    private Runnable release;
    private boolean closed;

    CoreModelLease(
        final long generation,
        final String modelIdentity,
        final String providerId,
        final String artifactProfile,
        final Object borrowedModel,
        final LongSupplier currentGeneration,
        final Runnable release
    ) {
        if (generation < 0) {
            throw new IllegalArgumentException("generation must not be negative");
        }
        this.generation = generation;
        this.modelIdentity = requireText(modelIdentity, "modelIdentity");
        this.providerId = requireText(providerId, "providerId");
        this.artifactProfile = requireText(artifactProfile, "artifactProfile");
        this.borrowedModel = Objects.requireNonNull(borrowedModel, "borrowedModel");
        this.currentGeneration = Objects.requireNonNull(
            currentGeneration,
            "currentGeneration"
        );
        this.release = Objects.requireNonNull(release, "release");
    }

    long generation() {
        return generation;
    }

    String modelIdentity() {
        return modelIdentity;
    }

    String providerId() {
        return providerId;
    }

    String artifactProfile() {
        return artifactProfile;
    }

    synchronized boolean isOpen() {
        return !closed;
    }

    /**
     * Runs one internal Core read while the lease owns the borrowed reference.
     *
     * <p>The callback must return only adapter-owned data. It must not retain or return the raw
     * model. This method is package-private so plugin and SDK code cannot invoke it.</p>
     */
    synchronized <T> T readForProvider(final Function<Object, T> operation) {
        Objects.requireNonNull(operation, "operation");
        if (closed) {
            throw failure(
                CoreModelFailure.Code.LEASE_CLOSED,
                "Borrowed Core model lease is closed."
            );
        }
        requireCurrentGeneration();
        final T result = operation.apply(borrowedModel);
        requireCurrentGeneration();
        return result;
    }

    private void requireCurrentGeneration() {
        if (currentGeneration.getAsLong() != generation) {
            throw failure(
                CoreModelFailure.Code.STALE_GENERATION,
                "Borrowed Core model lease generation is stale."
            );
        }
    }

    @Override
    public void close() {
        final Runnable releaseAction;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            borrowedModel = null;
            releaseAction = release;
            release = () -> { };
        }
        releaseAction.run();
    }

    private static CoreModelLeaseException failure(
        final CoreModelFailure.Code code,
        final String message
    ) {
        return new CoreModelLeaseException(new CoreModelFailure(code, message));
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
