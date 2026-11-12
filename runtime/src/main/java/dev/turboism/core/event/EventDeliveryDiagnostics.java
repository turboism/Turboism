package dev.turboism.core.event;

import dev.turboism.core.runtime.work.FatalErrors;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Owns the diagnostics of one broker session: delivery codes, the once-per-route de-duplication
 * that keeps a hot failure from flooding the sink, and the slow-delivery clock.
 *
 * <p>Every sink call is contained. A diagnostic consumer is observer code owned by the host, so a
 * throwing or slow consumer must never change publication, delivery, or lifecycle behavior. Fatal
 * VM failures still propagate because no containment policy can make them recoverable.
 *
 * <p>De-duplication sets are keyed by owner and concrete event type, never by route, so one
 * misbehaving subscriber cannot mask a second one behind a shared diagnostic budget. They are
 * released when the owning generation closes.
 */
final class EventDeliveryDiagnostics {

    private static final String INTERNAL_PLUGIN_ID = "dev.turboism.runtime.internal";
    private static final String NO_EVENT_TYPE = "";
    private static final PluginEventOwnerKey INTERNAL_OWNER =
            new PluginEventOwnerKey(INTERNAL_PLUGIN_ID, PluginEventOwnerKey.INTERNAL_GENERATION);

    private final Consumer<RuntimeEventBroker.DeliveryDiagnostic> diagnosticSink;
    private final Duration slowDeliveryThreshold;
    private final Set<OwnerRoute> deniedDeliveries = ConcurrentHashMap.newKeySet();
    private final Set<OwnerRoute> slowDeliveries = ConcurrentHashMap.newKeySet();

    EventDeliveryDiagnostics(
            final Consumer<RuntimeEventBroker.DeliveryDiagnostic> diagnosticSink,
            final Duration slowDeliveryThreshold) {
        this.diagnosticSink = Objects.requireNonNull(diagnosticSink, "diagnosticSink");
        this.slowDeliveryThreshold = Objects.requireNonNull(slowDeliveryThreshold, "slowDeliveryThreshold");
    }

    /** Returns the pseudo-owner that attributes broker-internal failures in diagnostics. */
    static PluginEventOwnerKey internalOwner() {
        return INTERNAL_OWNER;
    }

    /** Emits one diagnostic, containing every failure raised by the sink itself. */
    void report(
            final PluginEventOwnerKey owner,
            final String eventType,
            final RuntimeEventBroker.DeliveryDiagnostic.Code code) {
        report(new RuntimeEventBroker.DeliveryDiagnostic(owner, eventType, code));
    }

    /** Emits one pre-built diagnostic, containing every failure raised by the sink itself. */
    void report(final RuntimeEventBroker.DeliveryDiagnostic diagnostic) {
        try {
            diagnosticSink.accept(diagnostic);
        } catch (Throwable ignored) {
            FatalErrors.rethrowIfFatal(ignored);
            // Diagnostics must never alter publication or host-operation behavior.
        }
    }

    /** Reports a scheduler refusal that carries no concrete event type. */
    void reportSchedulerRejected(final PluginEventOwnerKey owner) {
        report(owner, NO_EVENT_TYPE, RuntimeEventBroker.DeliveryDiagnostic.Code.SCHEDULER_REJECTED);
    }

    /**
     * Records one permission denial.
     *
     * @return {@code true} when this is the first denial for the owner and type, so the caller
     *     reports it; a repeated denial stays silent to keep the sink bounded
     */
    boolean recordDeniedDelivery(final PluginEventOwnerKey owner, final Class<?> eventType) {
        return deniedDeliveries.add(new OwnerRoute(owner, eventType));
    }

    /**
     * Reports one subscriber that ran past the slow-delivery threshold.
     *
     * <p>The subscriber is never interrupted: the caller still receives its final candidate, and
     * the diagnostic records that the callback overran rather than that it was stopped.
     */
    void reportSlowDeliveryIfOverrun(
            final PluginEventOwnerKey owner, final Class<?> eventType, final long startedNanos) {
        if (System.nanoTime() - startedNanos < slowDeliveryThreshold.toNanos()) {
            return;
        }
        if (slowDeliveries.add(new OwnerRoute(owner, eventType))) {
            report(owner, eventType.getName(), RuntimeEventBroker.DeliveryDiagnostic.Code.SUBSCRIBER_SLOW);
        }
    }

    /** Releases the de-duplication state of one closed generation. */
    void release(final PluginEventOwnerKey owner) {
        deniedDeliveries.removeIf(route -> route.owner().equals(owner));
        slowDeliveries.removeIf(route -> route.owner().equals(owner));
    }

    private record OwnerRoute(PluginEventOwnerKey owner, Class<?> eventType) {}
}
