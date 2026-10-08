package dev.turboism.adapter.cubism.core;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Lifecycle gate for an Editor-owned active Core model.
 *
 * <p>Replacement and source close wait for all scoped leases, then forget the old reference.
 * Neither transition calls {@code close}, {@code delete}, or any other lifecycle method on the
 * borrowed model.</p>
 */
final class BorrowedCoreModelSource implements ActiveCoreModelSource {

    private final Object monitor = new Object();
    private final List<Runnable> modelClearedListeners = new CopyOnWriteArrayList<>();

    private Object activeModel;
    private String modelIdentity;
    private long generation;
    private int activeLeases;
    private boolean transitioning;
    private boolean closed;
    private boolean releaseRequested;
    private final Set<String> refusedPublishIdentities = new HashSet<>();

    /**
     * Marks a publish identity as refused: a receiver the verified Core model type did not
     * accept must leave every read identity derived from it fail-closed for all joins and
     * readers sharing this source, instead of falling through to a previously published
     * model that belongs to a different binding. A later accepted publication under the
     * same identity clears the refusal.
     */
    @Override
    public void refusePublication(final String identity) {
        synchronized (monitor) {
            refusedPublishIdentities.add(requireText(identity, "identity"));
        }
    }

    /**
     * Reports whether {@code identity} equals or derives from ({@code refused + ":"})
     * a publish identity whose publication was refused. The {@code ":"} boundary keeps a
     * sibling id prefix (for example {@code model-a2}) from being poisoned by a refused
     * {@code model-a}.
     */
    @Override
    public boolean isRefusedPublication(final String identity) {
        synchronized (monitor) {
            for (final String refused : refusedPublishIdentities) {
                if (identity.equals(refused) || identity.startsWith(refused + ":")) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Publishes an Editor-owned borrowed model after a verified acquisition adapter resolves it.
     */
    void publishBorrowedModel(final Object model, final String identity) {
        transitionTo(
            Objects.requireNonNull(model, "model"),
            requireText(identity, "identity")
        );
    }

    /**
     * Best-effort variant of {@link #publishBorrowedModel} for lazy first-access publication.
     * Returns false when the source is closed (publish fails); deduplication is left to the
     * Editor-side caller, which attempts at most once per binding identity.
     */
    @Override
    public boolean tryPublishBorrowedModel(final Object model, final String identity) {
        try {
            publishBorrowedModel(model, identity);
            return true;
        } catch (IllegalStateException closed) {
            return false;
        }
    }

    /** Clears the active model without taking ownership of the previous reference. */
    void clearBorrowedModel() {
        transitionTo(null, null);
    }

    /**
     * Non-blocking release request used when the publishing binding is known to be gone. The
     * model is forgotten as soon as no lease is outstanding; a later publication cancels the
     * pending release, so a re-bound document is never disturbed.
     */
    @Override
    public void releaseWhenIdle() {
        final boolean cleared;
        synchronized (monitor) {
            if (closed || activeModel == null) {
                return;
            }
            releaseRequested = true;
            cleared = applyIdleReleaseLocked();
        }
        if (cleared) {
            fireModelCleared();
        }
    }

    @Override
    public Object publishedModel() {
        synchronized (monitor) {
            return activeModel;
        }
    }

    @Override
    public void onModelCleared(final Runnable listener) {
        modelClearedListeners.add(Objects.requireNonNull(listener, "listener"));
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
        final boolean cleared;
        synchronized (monitor) {
            interrupted |= awaitTransitionCompletion();
            if (closed) {
                restoreInterrupt(interrupted);
                return;
            }
            transitioning = true;
            releaseRequested = false;
            try {
                interrupted |= awaitLeaseRelease();
                final long nextGeneration = Math.incrementExact(generation);
                cleared = activeModel != null;
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
        if (cleared) {
            fireModelCleared();
        }
    }

    private void transitionTo(final Object replacement, final String identity) {
        boolean interrupted = false;
        final boolean cleared;
        synchronized (monitor) {
            interrupted |= awaitTransitionCompletion();
            if (closed) {
                restoreInterrupt(interrupted);
                throw new IllegalStateException("Active Core model source is closed.");
            }
            transitioning = true;
            releaseRequested = false;
            try {
                interrupted |= awaitLeaseRelease();
                final long nextGeneration = Math.incrementExact(generation);
                cleared = activeModel != null && replacement == null;
                activeModel = replacement;
                modelIdentity = identity;
                generation = nextGeneration;
                if (identity != null) {
                    refusedPublishIdentities.remove(identity);
                }
            } finally {
                transitioning = false;
                monitor.notifyAll();
            }
        }
        restoreInterrupt(interrupted);
        if (cleared) {
            fireModelCleared();
        }
    }

    /**
     * Applies a pending non-blocking release once no lease is outstanding. Must be called under
     * the monitor; returns true when the model was actually forgotten.
     */
    private boolean applyIdleReleaseLocked() {
        if (!releaseRequested || activeLeases != 0 || activeModel == null
            || transitioning || closed) {
            return false;
        }
        releaseRequested = false;
        generation = Math.incrementExact(generation);
        activeModel = null;
        modelIdentity = null;
        return true;
    }

    private void fireModelCleared() {
        for (final Runnable listener : modelClearedListeners) {
            listener.run();
        }
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

    /**
     * @return the generation of the currently published borrowed model, read under the source
     *     monitor; it is incremented on every replacement and on close, so a lease holder can
     *     detect that the model it borrowed is no longer the active one
     */
    public long currentGeneration() {
        synchronized (monitor) {
            return generation;
        }
    }

    private void releaseLease() {
        final boolean cleared;
        synchronized (monitor) {
            if (activeLeases <= 0) {
                throw new IllegalStateException("Core model lease accounting underflow.");
            }
            activeLeases--;
            cleared = applyIdleReleaseLocked();
            if (activeLeases == 0) {
                monitor.notifyAll();
            }
        }
        if (cleared) {
            fireModelCleared();
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
