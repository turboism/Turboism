package dev.turboism.adapter.cubism.core;

import java.util.Objects;

/**
 * Lifecycle gate for an Editor-owned active Core model.
 *
 * <p>Replacement and source close wait for all scoped leases, then forget the old reference.
 * Neither transition calls {@code close}, {@code delete}, or any other lifecycle method on the
 * borrowed model.</p>
 */
final class BorrowedCoreModelSource implements ActiveCoreModelSource {

    private final Object monitor = new Object();

    private Object activeModel;
    private String modelIdentity;
    private long generation;
    private int activeLeases;
    private boolean transitioning;
    private boolean closed;

    /**
     * Publishes an Editor-owned borrowed model after a verified acquisition adapter resolves it.
     */
    void publishBorrowedModel(final Object model, final String identity) {
        transitionTo(
            Objects.requireNonNull(model, "model"),
            requireText(identity, "identity")
        );
    }

    /** Clears the active model without taking ownership of the previous reference. */
    void clearBorrowedModel() {
        transitionTo(null, null);
    }

    @Override
    public CoreModelAcquisition acquire(final CorePublicApiProvider provider) {
        Objects.requireNonNull(provider, "provider");
        final boolean providerAvailable = provider.available();
        final String providerId = requireText(provider.providerId(), "provider.providerId()");
        final String artifactProfile = requireText(
            provider.artifactProfile(),
            "provider.artifactProfile()"
        );

        synchronized (monitor) {
            if (closed) {
                return CoreModelAcquisition.failed(
                    CoreModelFailure.Code.SOURCE_CLOSED,
                    "Active Core model source is closed."
                );
            }
            if (transitioning) {
                return CoreModelAcquisition.failed(
                    CoreModelFailure.Code.TRANSITION_IN_PROGRESS,
                    "Active Core model source is changing generation."
                );
            }
            if (!providerAvailable) {
                return CoreModelAcquisition.failed(
                    CoreModelFailure.Code.ADAPTER_UNAVAILABLE,
                    "Core public API provider is unavailable."
                );
            }
            if (activeModel == null) {
                return CoreModelAcquisition.failed(
                    CoreModelFailure.Code.MODEL_UNAVAILABLE,
                    "No verified active Core model is available."
                );
            }

            activeLeases++;
            return CoreModelAcquisition.acquired(new CoreModelLease(
                generation,
                modelIdentity,
                providerId,
                artifactProfile,
                activeModel,
                this::currentGeneration,
                this::releaseLease
            ));
        }
    }

    @Override
    public void close() {
        boolean interrupted = false;
        synchronized (monitor) {
            interrupted |= awaitTransitionCompletion();
            if (closed) {
                restoreInterrupt(interrupted);
                return;
            }
            transitioning = true;
            try {
                interrupted |= awaitLeaseRelease();
                final long nextGeneration = Math.incrementExact(generation);
                activeModel = null;
                modelIdentity = null;
                generation = nextGeneration;
                closed = true;
            } finally {
                transitioning = false;
                monitor.notifyAll();
            }
        }
        restoreInterrupt(interrupted);
    }

    private void transitionTo(final Object replacement, final String identity) {
        boolean interrupted = false;
        synchronized (monitor) {
            interrupted |= awaitTransitionCompletion();
            if (closed) {
                restoreInterrupt(interrupted);
                throw new IllegalStateException("Active Core model source is closed.");
            }
            transitioning = true;
            try {
                interrupted |= awaitLeaseRelease();
                final long nextGeneration = Math.incrementExact(generation);
                activeModel = replacement;
                modelIdentity = identity;
                generation = nextGeneration;
            } finally {
                transitioning = false;
                monitor.notifyAll();
            }
        }
        restoreInterrupt(interrupted);
    }

    private boolean awaitTransitionCompletion() {
        boolean interrupted = false;
        while (transitioning) {
            try {
                monitor.wait();
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        return interrupted;
    }

    private boolean awaitLeaseRelease() {
        boolean interrupted = false;
        while (activeLeases != 0) {
            try {
                monitor.wait();
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        return interrupted;
    }

    private long currentGeneration() {
        synchronized (monitor) {
            return generation;
        }
    }

    private void releaseLease() {
        synchronized (monitor) {
            if (activeLeases <= 0) {
                throw new IllegalStateException("Core model lease accounting underflow.");
            }
            activeLeases--;
            if (activeLeases == 0) {
                monitor.notifyAll();
            }
        }
    }

    private static void restoreInterrupt(final boolean interrupted) {
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
