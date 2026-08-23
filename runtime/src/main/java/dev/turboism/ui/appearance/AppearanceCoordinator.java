package dev.turboism.ui.appearance;

import dev.turboism.sdk.appearance.AppearanceApplyResult;
import dev.turboism.sdk.appearance.AppearanceChangedEvent;
import dev.turboism.sdk.appearance.AppearanceRequest;
import dev.turboism.sdk.appearance.AppearanceRestoreResult;
import dev.turboism.sdk.appearance.AppearanceStatus;
import dev.turboism.core.event.RuntimeEventBroker;

import java.util.Objects;
import java.util.Optional;

/** Global runtime owner of appearance arbitration, baseline capture and restoration. */
public final class AppearanceCoordinator implements AutoCloseable {

    private final Object monitor = new Object();
    private final AppearanceHostProvider provider;
    private volatile RuntimeEventBroker eventBroker;
    private Owner activeOwner;
    private AppearanceHostProvider.RestorePoint restorePoint;
    private AppearanceStatus status;
    private boolean closed;

    public AppearanceCoordinator(final AppearanceHostProvider provider) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.status = provider.readStatus();
    }

    /** Attaches the session Broker used for Runtime-owned appearance observations. */
    public void attachEventBroker(final RuntimeEventBroker broker) {
        final RuntimeEventBroker value = Objects.requireNonNull(broker, "broker");
        synchronized (monitor) {
            if (eventBroker != null && eventBroker != value) {
                throw new IllegalStateException("Appearance event Broker is already attached");
            }
            eventBroker = value;
        }
    }

    /**
     * @return the current appearance state: the cached state of the active override
     *     while one plugin owns appearance, otherwise a freshly read host status
     */
    public AppearanceStatus current() {
        synchronized (monitor) {
            if (activeOwner == null) {
                status = provider.readStatus();
            }
            return status;
        }
    }

    /**
     * Applies a plugin appearance override, arbitrating single ownership.
     *
     * <p>The first successful apply captures a restore point and makes the caller the
     * exclusive owner; another plugin, or the same plugin at a different generation,
     * is then rejected until {@link #restore} or {@link #close} releases ownership. The
     * request must quote the current revision, guarding against lost updates. On a
     * successful change the coordinator publishes an {@code AppearanceChangedEvent}.</p>
     *
     * <p>If the host apply throws, the coordinator rolls back to the restore point and
     * releases ownership, reporting {@code FAILED_RESTORED} — or {@code FAILED_RESTORE}
     * when the rollback itself failed and the host is left as the failed apply left it.</p>
     *
     * @param pluginId         id of the requesting plugin, non-blank
     * @param pluginGeneration the plugin’s load generation, so a reloaded plugin does not
     *                         inherit the previous instance’s ownership; must not be negative
     * @param request          the desired appearance, including the expected revision
     * @return the outcome together with the resulting status and an optional diagnostic id;
     *     {@code UNAVAILABLE} when no host provider is present, {@code REJECTED} on a
     *     revision or ownership conflict, {@code NO_CHANGE} when the host was already in
     *     that state
     * @throws IllegalStateException when the coordinator is closed
     * @throws NullPointerException when {@code request} or {@code pluginId} is {@code null}
     * @throws IllegalArgumentException when {@code pluginId} is blank or the generation is negative
     */
    public AppearanceApplyResult apply(
        final String pluginId,
        final long pluginGeneration,
        final AppearanceRequest request
    ) {
        final Owner requester = new Owner(pluginId, pluginGeneration);
        Objects.requireNonNull(request, "request");
        synchronized (monitor) {
            requireOpen();
            if (!provider.isAvailable()) {
                status = provider.readStatus();
                return applyResult(
                    AppearanceApplyResult.Outcome.UNAVAILABLE,
                    "appearance.provider.unavailable"
                );
            }
            if (request.expectedRevision() != status.revision()) {
                return applyResult(
                    AppearanceApplyResult.Outcome.REJECTED,
                    "appearance.revision.conflict"
                );
            }
            if (activeOwner != null && !activeOwner.equals(requester)) {
                return applyResult(
                    AppearanceApplyResult.Outcome.REJECTED,
                    "appearance.owner.conflict"
                );
            }
            final AppearanceStatus previous = status;
            if (activeOwner == null) {
                restorePoint = provider.captureRestorePoint();
                activeOwner = requester;
            }
            try {
                final AppearanceHostProvider.ApplyOutcome outcome = provider.apply(request);
                status = appliedStatus(request, previous.revision() + (outcome == AppearanceHostProvider.ApplyOutcome.APPLIED ? 1 : 0));
                if (outcome == AppearanceHostProvider.ApplyOutcome.APPLIED) {
                    publish(new AppearanceChangedEvent(previous, status, pluginId));
                    return applyResult(AppearanceApplyResult.Outcome.APPLIED, null);
                }
                return applyResult(AppearanceApplyResult.Outcome.NO_CHANGE, null);
            } catch (RuntimeException applyFailure) {
                return restoreAfterFailedApply(previous, pluginId, applyFailure);
            }
        }
    }

    /**
     * Releases this plugin’s appearance override and returns the host to the captured
     * baseline.
     *
     * <p>Only the current owner at the same generation can restore; anyone else — and
     * any call after {@link #close} — gets {@code NO_OWNED_OVERRIDE} without touching
     * the host. A restore that changes the state publishes an
     * {@code AppearanceChangedEvent}.</p>
     *
     * @param pluginId         id of the plugin releasing its override
     * @param pluginGeneration the load generation that acquired the override
     * @return {@code RESTORED} on success; {@code NO_OWNED_OVERRIDE} when the caller does
     *     not own the current override; {@code UNAVAILABLE} when the provider or restore
     *     point is gone; {@code FAILED_RESTORE} when the host rejected the rollback, in
     *     which case ownership is retained
     * @throws NullPointerException when {@code pluginId} is {@code null}
     * @throws IllegalArgumentException when {@code pluginId} is blank or the generation is negative
     */
    public AppearanceRestoreResult restore(
        final String pluginId,
        final long pluginGeneration
    ) {
        final Owner requester = new Owner(pluginId, pluginGeneration);
        synchronized (monitor) {
            if (closed) {
                return restoreResult(AppearanceRestoreResult.Outcome.NO_OWNED_OVERRIDE, null);
            }
            if (activeOwner == null || !activeOwner.equals(requester)) {
                return restoreResult(AppearanceRestoreResult.Outcome.NO_OWNED_OVERRIDE, null);
            }
            if (!provider.isAvailable() || restorePoint == null) {
                return restoreResult(
                    AppearanceRestoreResult.Outcome.UNAVAILABLE,
                    "appearance.provider.unavailable"
                );
            }
            final AppearanceStatus previous = status;
            try {
                provider.restore(restorePoint);
                status = provider.readStatus();
                activeOwner = null;
                restorePoint = null;
                if (!status.equals(previous)) {
                    publish(new AppearanceChangedEvent(previous, status, pluginId));
                }
                return restoreResult(AppearanceRestoreResult.Outcome.RESTORED, null);
            } catch (RuntimeException restoreFailure) {
                return restoreResult(
                    AppearanceRestoreResult.Outcome.FAILED_RESTORE,
                    "appearance.restore.failed"
                );
            }
        }
    }

    @Override
    public void close() {
        synchronized (monitor) {
            if (closed) {
                return;
            }
            if (activeOwner != null && restorePoint != null && provider.isAvailable()) {
                try {
                    provider.restore(restorePoint);
                    status = provider.readStatus();
                } catch (RuntimeException ignored) {
                    // Runtime shutdown remains fail-closed; diagnostics are reported by the caller.
                }
            }
            activeOwner = null;
            restorePoint = null;
            closed = true;
        }
    }

    private AppearanceApplyResult restoreAfterFailedApply(
        final AppearanceStatus previous,
        final String pluginId,
        final RuntimeException applyFailure
    ) {
        try {
            provider.restore(restorePoint);
            status = provider.readStatus();
            activeOwner = null;
            restorePoint = null;
            return applyResult(
                AppearanceApplyResult.Outcome.FAILED_RESTORED,
                "appearance.apply.failed-restored"
            );
        } catch (RuntimeException restoreFailure) {
            applyFailure.addSuppressed(restoreFailure);
            return applyResult(
                AppearanceApplyResult.Outcome.FAILED_RESTORE,
                "appearance.apply.restore-failed"
            );
        }
    }

    private void publish(final AppearanceChangedEvent event) {
        final RuntimeEventBroker broker = eventBroker;
        if (broker != null) {
            broker.publishRuntime(event);
        }
    }

    private AppearanceStatus appliedStatus(
        final AppearanceRequest request,
        final long revision
    ) {
        return new AppearanceStatus(
            AppearanceStatus.Availability.AVAILABLE,
            AppearanceStatus.Source.PLUGIN_OVERLAY,
            Optional.of(request.appearanceId()),
            request.base(),
            revision,
            Optional.empty()
        );
    }

    private AppearanceApplyResult applyResult(
        final AppearanceApplyResult.Outcome outcome,
        final String diagnosticId
    ) {
        return new AppearanceApplyResult(
            outcome,
            status,
            Optional.ofNullable(diagnosticId)
        );
    }

    private AppearanceRestoreResult restoreResult(
        final AppearanceRestoreResult.Outcome outcome,
        final String diagnosticId
    ) {
        return new AppearanceRestoreResult(
            outcome,
            status,
            Optional.ofNullable(diagnosticId)
        );
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Appearance coordinator is closed");
        }
    }

    private record Owner(String pluginId, long generation) {
        private Owner {
            Objects.requireNonNull(pluginId, "pluginId");
            if (pluginId.isBlank()) {
                throw new IllegalArgumentException("pluginId must not be blank");
            }
            if (generation < 0) {
                throw new IllegalArgumentException("generation must not be negative");
            }
        }
    }
}
