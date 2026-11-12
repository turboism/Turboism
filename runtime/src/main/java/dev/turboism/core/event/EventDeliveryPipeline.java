package dev.turboism.core.event;

import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.contract.PluginTask;
import dev.turboism.core.runtime.contract.RuntimeCancellationToken;
import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.core.runtime.work.PluginWorkSubmission;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.event.EventBus;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Owns the asynchronous delivery lane of one broker session: mailbox admission, drain scheduling,
 * and the delivery-time authorization that decides whether a queued event may still run.
 *
 * <p>Authorization is evaluated twice on purpose. The enqueue pass filters what the publisher may
 * route; the drain pass re-evaluates because a grant can be narrowed or revoked while an event
 * waits in the mailbox. Skipping the second check would let a revoked plugin observe an event
 * after its permission was withdrawn.
 *
 * <p>Drains run on the scheduler's event lane with no wall-clock limiter. A slow subscriber is
 * reported instead of interrupted, so a delivery always completes or fails as a whole.
 */
final class EventDeliveryPipeline {

    private static final String EVENT_TASK_TYPE = "event.subscribe";
    private static final String DEFAULT_CAPABILITY = "none";

    private final RuntimeScheduler scheduler;
    private final EventOwnerGenerations generations;
    private final EventSubscriptionRegistry registry;
    private final EventDeliveryDiagnostics diagnostics;
    private final PublicEventRouteCatalog publicRoutes;
    private final ConcurrentMap<PluginEventOwnerKey, PermissionChecker> ownerPermissions = new ConcurrentHashMap<>();

    EventDeliveryPipeline(
            final RuntimeScheduler scheduler,
            final EventOwnerGenerations generations,
            final EventSubscriptionRegistry registry,
            final EventDeliveryDiagnostics diagnostics,
            final PublicEventRouteCatalog publicRoutes) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.generations = Objects.requireNonNull(generations, "generations");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.publicRoutes = Objects.requireNonNull(publicRoutes, "publicRoutes");
    }

    /**
     * Binds the permission checker a plugin-facing facade was granted to an event owner.
     *
     * <p>Multiple facades may exist for one owner (the legacy compat bus plus the
     * composition-bound bus), so bindings combine monotonically: an event type is deliverable only
     * when every bound checker permits it. A second, broader facade therefore can never widen the
     * authorization of subscriptions created under a narrower grant.
     */
    void bindOwnerPermissions(final PluginEventOwnerKey owner, final PermissionChecker permissionChecker) {
        ownerPermissions.merge(
                Objects.requireNonNull(owner, "owner"),
                Objects.requireNonNull(permissionChecker, "permissionChecker"),
                (first, second) -> (permission, operation) -> {
                    first.check(permission, operation);
                    second.check(permission, operation);
                });
    }

    /** Releases the facade bindings of one closed generation. */
    void releaseOwner(final PluginEventOwnerKey owner) {
        ownerPermissions.remove(owner);
    }

    /** Routes one event to the exact-type routes that may receive it. */
    <T extends EventBus.TurboismEvent> void publishExact(final T event) {
        for (EventSubscription<? extends EventBus.TurboismEvent> subscription : registry.exactRoute(event.getClass())) {
            if (publicRoutes.mayReceive(subscription.owner(), event.getClass())) {
                enqueue(event, subscription);
            }
        }
    }

    /** Routes one event through the assignable-type plan, which covers root and supertype routes. */
    <T extends EventBus.TurboismEvent> void publishRouted(final T event) {
        for (EventSubscription<? extends EventBus.TurboismEvent> subscription :
                registry.dispatchPlan(event.getClass())) {
            if (publicRoutes.mayReceive(subscription.owner(), event.getClass())) {
                enqueue(event, subscription);
            }
        }
    }

    /**
     * Admits one delivery into its owner mailbox, scheduling a drain when the mailbox was idle.
     *
     * <p>A saturated mailbox drops the delivery and reports it once; the publisher is never
     * blocked by a slow subscriber.
     */
    void enqueue(
            final EventBus.TurboismEvent event,
            final EventSubscription<? extends EventBus.TurboismEvent> subscription) {
        if (!subscription.active() || !subscription.type().isInstance(event)) {
            return;
        }
        final EventOwnerGenerations.OwnerMailbox owner = generations.find(subscription.owner());
        if (owner == null
                || !subscription.accepts(owner.lifecycleSnapshot())
                || !mayDeliver(subscription, event.getClass())) {
            return;
        }
        final EventOwnerGenerations.EnqueueResult result = owner.enqueue(new EventDelivery(event, subscription));
        if (result == EventOwnerGenerations.EnqueueResult.SCHEDULE) {
            scheduleDrain(owner);
        } else if (result == EventOwnerGenerations.EnqueueResult.SATURATED) {
            diagnostics.report(
                    owner.key(),
                    event.getClass().getName(),
                    RuntimeEventBroker.DeliveryDiagnostic.Code.MAILBOX_SATURATED);
        }
    }

    private void scheduleDrain(final EventOwnerGenerations.OwnerMailbox owner) {
        final RuntimeCancellationToken token = owner.drainToken();
        final PluginWorkSubmission submission = scheduler.submitEventDelivery(
                new PluginTask(
                        EVENT_TASK_TYPE,
                        owner.key().pluginId(),
                        owner.key().pluginId() + " event generation "
                                + owner.key().generation(),
                        DEFAULT_CAPABILITY),
                token,
                () -> drain(owner));
        if (!submission.accepted()) {
            owner.rejectDrain();
            diagnostics.reportSchedulerRejected(owner.key());
        }
    }

    private void drain(final EventOwnerGenerations.OwnerMailbox owner) {
        try {
            while (true) {
                final EventDelivery delivery = owner.nextDelivery();
                if (delivery == null) {
                    break;
                }
                // Re-authorize at drain time: a grant narrowed or revoked between
                // enqueue and delivery must still suppress the queued event.
                if (!mayDeliver(delivery.subscription(), delivery.event().getClass())) {
                    owner.deliveryFinished();
                    continue;
                }
                final long startedNanos = System.nanoTime();
                try {
                    delivery.deliver(owner.key());
                } catch (Throwable failure) {
                    FatalErrors.rethrowIfFatal(failure);
                    diagnostics.report(
                            owner.key(),
                            delivery.event().getClass().getName(),
                            RuntimeEventBroker.DeliveryDiagnostic.Code.SUBSCRIBER_FAILED);
                } finally {
                    owner.deliveryFinished();
                    diagnostics.reportSlowDeliveryIfOverrun(
                            owner.key(), delivery.event().getClass(), startedNanos);
                }
            }
        } finally {
            if (owner.drainFinished()) {
                scheduleDrain(owner);
            }
        }
    }

    /**
     * Delivery-time domain authorization for plugin-facing subscriptions.
     *
     * <p>The concrete event type's required permissions are evaluated against the owner's
     * facade-bound checker on every delivery, so a broad root or supertype subscription receives
     * exactly the subset its owner may observe and can never intercept a gated transform. Runtime
     * adapter subscriptions and owners without a facade binding are trusted: raw broker callers are
     * not plugin-reachable. Denials are reported once per owner and type.
     */
    boolean mayDeliver(
            final EventSubscription<? extends EventBus.TurboismEvent> subscription, final Class<?> concreteType) {
        if (!subscription.permissionFiltered()) {
            return true;
        }
        final PermissionChecker checker = ownerPermissions.get(subscription.owner());
        if (checker == null) {
            return true;
        }
        @SuppressWarnings("unchecked")
        final Class<? extends EventBus.TurboismEvent> eventType =
                (Class<? extends EventBus.TurboismEvent>) concreteType;
        if (EventSubscriptionPermissionCatalog.isPermitted(eventType, checker)) {
            return true;
        }
        if (diagnostics.recordDeniedDelivery(subscription.owner(), concreteType)) {
            diagnostics.report(
                    subscription.owner(),
                    concreteType.getName(),
                    RuntimeEventBroker.DeliveryDiagnostic.Code.DELIVERY_PERMISSION_DENIED);
        }
        return false;
    }
}
