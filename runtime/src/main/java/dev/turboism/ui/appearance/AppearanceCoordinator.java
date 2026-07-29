package dev.turboism.ui.appearance;

import dev.turboism.sdk.appearance.AppearanceApplyResult;
import dev.turboism.sdk.appearance.AppearanceChangedEvent;
import dev.turboism.sdk.appearance.AppearanceRequest;
import dev.turboism.sdk.appearance.AppearanceRestoreResult;
import dev.turboism.sdk.appearance.AppearanceStatus;
import dev.turboism.sdk.event.EventBus;

import java.util.Objects;
import java.util.Optional;

/** Global runtime owner of appearance arbitration, baseline capture and restoration. */
public final class AppearanceCoordinator implements AutoCloseable {

    private final Object monitor = new Object();
    private final AppearanceHostProvider provider;
    private final EventBus eventBus;
    private Owner activeOwner;
    private AppearanceHostProvider.RestorePoint restorePoint;
    private AppearanceStatus status;
    private boolean closed;

    public AppearanceCoordinator(
        final AppearanceHostProvider provider,
        final EventBus eventBus
    ) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.status = provider.readStatus();
    }

    public AppearanceStatus current() {
        synchronized (monitor) {
            if (activeOwner == null) {
                status = provider.readStatus();
            }
            return status;
        }
    }

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
                    eventBus.publish(new AppearanceChangedEvent(previous, status, pluginId));
                    return applyResult(AppearanceApplyResult.Outcome.APPLIED, null);
                }
                return applyResult(AppearanceApplyResult.Outcome.NO_CHANGE, null);
            } catch (RuntimeException applyFailure) {
                return restoreAfterFailedApply(previous, pluginId, applyFailure);
            }
        }
    }

    public AppearanceRestoreResult restore(
        final String pluginId,
        final long pluginGeneration
    ) {
        final Owner requester = new Owner(pluginId, pluginGeneration);
        synchronized (monitor) {
            requireOpen();
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
                    eventBus.publish(new AppearanceChangedEvent(previous, status, pluginId));
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
