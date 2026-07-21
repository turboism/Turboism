package dev.turboism.adapter.cubism.core;

import dev.turboism.mapping.verification.VerifiedAccessException;

import java.util.Objects;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Projects one generation-bound Core model lease into immutable adapter-owned structure.
 *
 * <p>The read lock keeps prebound call sites alive for the complete scoped read. Closing the
 * tracer waits for active reads before releasing all reflection/class references.</p>
 */
final class CoreStructuralTracer implements AutoCloseable {

    private final String providerId;
    private final String artifactProfile;
    private final CoreCallSiteTable callSites;
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock();
    private boolean closed;

    CoreStructuralTracer(
        final String providerId,
        final String artifactProfile,
        final CoreCallSiteTable callSites
    ) {
        this.providerId = requireText(providerId, "providerId");
        this.artifactProfile = requireText(artifactProfile, "artifactProfile");
        this.callSites = Objects.requireNonNull(callSites, "callSites");
    }

    CoreProviderResult<CoreStructuralSnapshot> trace(final CoreModelLease lease) {
        Objects.requireNonNull(lease, "lease");
        lifecycle.readLock().lock();
        try {
            if (closed) {
                return failed(
                    CoreProviderFailure.Code.ADAPTER_UNAVAILABLE,
                    "Core structural tracer is closed."
                );
            }
            if (!providerId.equals(lease.providerId())
                || !artifactProfile.equals(lease.artifactProfile())) {
                return failed(
                    CoreProviderFailure.Code.EVIDENCE_REJECTED,
                    "Core model lease does not match the admitted provider profile."
                );
            }
            try {
                final CoreStructuralSnapshot snapshot = lease.readForProvider(
                    rawModel -> callSites.project(
                        rawModel,
                        lease.generation(),
                        lease.modelIdentity(),
                        providerId,
                        artifactProfile
                    )
                );
                return CoreProviderResult.success(snapshot);
            } catch (CoreModelLeaseException exception) {
                return failed(
                    leaseFailureCode(exception.failure().code()),
                    "Core model lease is not valid for this structural read."
                );
            } catch (VerifiedAccessException exception) {
                return failed(
                    exception.failureKind()
                        == VerifiedAccessException.FailureKind.RESOLUTION
                            ? CoreProviderFailure.Code.RESOLUTION_FAILED
                            : CoreProviderFailure.Code.INVOCATION_FAILED,
                    "Verified Core structural selector failed safely."
                );
            } catch (CoreStructuralValidationException
                     | IllegalArgumentException exception) {
                return failed(
                    CoreProviderFailure.Code.INVALID_STRUCTURE,
                    "Core structural data could not be normalized safely."
                );
            } catch (RuntimeException exception) {
                return failed(
                    CoreProviderFailure.Code.INVALID_STRUCTURE,
                    "Core structural read failed during safe normalization."
                );
            }
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    @Override
    public void close() {
        lifecycle.writeLock().lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            callSites.close();
        } finally {
            lifecycle.writeLock().unlock();
        }
    }

    private static CoreProviderFailure.Code leaseFailureCode(
        final CoreModelFailure.Code code
    ) {
        return switch (code) {
            case LEASE_CLOSED -> CoreProviderFailure.Code.LEASE_CLOSED;
            case STALE_GENERATION -> CoreProviderFailure.Code.STALE_GENERATION;
            default -> CoreProviderFailure.Code.ADAPTER_UNAVAILABLE;
        };
    }

    private static <T> CoreProviderResult<T> failed(
        final CoreProviderFailure.Code code,
        final String message
    ) {
        return CoreProviderResult.failed(new CoreProviderFailure(code, message));
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
