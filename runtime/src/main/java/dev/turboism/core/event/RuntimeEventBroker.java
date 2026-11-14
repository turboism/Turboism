package dev.turboism.core.event;

import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.internal.Requirements;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.event.EventPriority;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.Registration;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Session-scoped registry and dispatcher shared by plugin event-bus facades.
 *
 * <p>This type is the stable seam the rest of {@code :runtime} depends on. It owns no routing,
 * mailbox, or diagnostic policy of its own: {@link EventSubscriptionRegistry} owns the routes,
 * {@link EventOwnerGenerations} owns generation lifecycle and mailboxes,
 * {@link EventDeliveryPipeline} owns admission and authorization, and
 * {@link EventDeliveryDiagnostics} owns the report sinks. The broker keeps only the state that
 * spans those collaborators — retained runtime observations, failure advice, and subscription
 * demand listeners — plus the public API that callers already use.
 */
public final class RuntimeEventBroker {

    private static final int DEFAULT_MAILBOX_CAPACITY = 64;
    private static final Duration DEFAULT_SLOW_DELIVERY = Duration.ofSeconds(5);

    private final EventSubscriptionRegistry registry = new EventSubscriptionRegistry();
    private final EventOwnerGenerations generations;
    private final EventDeliveryDiagnostics diagnostics;
    private final EventDeliveryPipeline pipeline;
    private final RuntimeEventContractCatalog contractCatalog = new RuntimeEventContractCatalog();
    private final PublicEventRouteCatalog publicRoutes = new PublicEventRouteCatalog();
    private final Consumer<SubscriberFailure> subscriberFailureSink;
    private final ConcurrentMap<PluginEventOwnerKey, EventFailureInterceptor> failureInterceptors =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<Class<?>, AtomicReference<?>> runtimeObservationBaselines = new ConcurrentHashMap<>();
    private final ConcurrentMap<Class<?>, EventBus.TurboismEvent> retainedRuntimeEvents = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<Class<?>>> subscriptionDemandListeners = new CopyOnWriteArrayList<>();

    public RuntimeEventBroker(final RuntimeScheduler scheduler) {
        this(scheduler, DEFAULT_MAILBOX_CAPACITY, ignored -> {}, ignored -> {});
    }

    RuntimeEventBroker(final RuntimeScheduler scheduler, final int mailboxCapacity) {
        this(scheduler, mailboxCapacity, ignored -> {}, ignored -> {});
    }

    public RuntimeEventBroker(
            final RuntimeScheduler scheduler,
            final int mailboxCapacity,
            final Consumer<DeliveryDiagnostic> diagnosticSink) {
        this(scheduler, mailboxCapacity, diagnosticSink, ignored -> {});
    }

    public RuntimeEventBroker(
            final RuntimeScheduler scheduler,
            final int mailboxCapacity,
            final Consumer<DeliveryDiagnostic> diagnosticSink,
            final Consumer<SubscriberFailure> subscriberFailureSink) {
        this(scheduler, mailboxCapacity, diagnosticSink, subscriberFailureSink, DEFAULT_SLOW_DELIVERY);
    }

    RuntimeEventBroker(
            final RuntimeScheduler scheduler,
            final int mailboxCapacity,
            final Consumer<DeliveryDiagnostic> diagnosticSink,
            final Consumer<SubscriberFailure> subscriberFailureSink,
            final Duration slowDeliveryThreshold) {
        final RuntimeScheduler value = Objects.requireNonNull(scheduler, "scheduler");
        if (mailboxCapacity < 1) {
            throw new IllegalArgumentException("mailboxCapacity must be positive");
        }
        this.subscriberFailureSink = Objects.requireNonNull(subscriberFailureSink, "subscriberFailureSink");
        this.generations = new EventOwnerGenerations(value, mailboxCapacity);
        this.diagnostics = new EventDeliveryDiagnostics(diagnosticSink, slowDeliveryThreshold);
        this.pipeline = new EventDeliveryPipeline(value, generations, registry, diagnostics, publicRoutes);
    }

    /** Validates shared public event payload classes before plugin code is initialized. */
    public void preflight(final PluginDescriptor descriptor) {
        publicRoutes.preflight(descriptor);
    }

    /**
     * Returns session-scoped mutable observation state owned by Runtime rather than any plugin
     * generation. The key type identifies one observation family and must be shared-parent code.
     */
    @SuppressWarnings("unchecked")
    public <T> AtomicReference<T> observationBaseline(final Class<T> keyType) {
        Objects.requireNonNull(keyType, "keyType");
        return (AtomicReference<T>)
                runtimeObservationBaselines.computeIfAbsent(keyType, ignored -> new AtomicReference<>());
    }

    /**
     * Registers a runtime-internal demand listener notified after each subscription is added.
     * Listeners receive the subscribed (possibly root/supertype) event type and decide whether
     * the subscription creates observation demand — for example the selection observer parks
     * itself while no subscription can receive its event. Listener failures are diagnosed as
     * subscriber failures and never abort registration.
     */
    public void addSubscriptionDemandListener(final Consumer<Class<?>> listener) {
        subscriptionDemandListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * True when at least one active subscription could receive deliveries of {@code eventType}
     * — that is, a subscription whose registered type is assignable from the concrete type.
     * This is a demand probe only; delivery-time permission filtering still applies per owner.
     */
    public boolean hasObserversFor(final Class<? extends EventBus.TurboismEvent> eventType) {
        Objects.requireNonNull(eventType, "eventType");
        for (EventSubscription<? extends EventBus.TurboismEvent> subscription : registry.dispatchPlan(eventType)) {
            if (subscription.active()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Binds the permission checker a plugin-facing facade was granted to an event
     * owner. Bound owners are filtered per delivered concrete event type; an owner
     * that never gets a facade binding is a runtime-internal participant (raw broker
     * subscriptions are a compatibility seam not exposed through the public SDK)
     * and delivers unfiltered.
     */
    void bindOwnerPermissions(final PluginEventOwnerKey owner, final PermissionChecker permissionChecker) {
        pipeline.bindOwnerPermissions(owner, permissionChecker);
    }

    /** Admits one inactive plugin generation whose subscriptions can be staged before activation. */
    public Owner admit(final String pluginId) {
        return admit(Requirements.text(pluginId, "pluginId"), null);
    }

    /** Admits one generation and registers its descriptor-declared public event contracts. */
    public Owner admit(final PluginDescriptor descriptor) {
        final PluginDescriptor value = Objects.requireNonNull(descriptor, "descriptor");
        return admit(Requirements.text(value.id(), "descriptor.id"), value);
    }

    private Owner admit(final String id, final PluginDescriptor descriptor) {
        final PluginEventOwnerKey key = generations.nextGeneration(id);
        final EventOwnerGenerations.OwnerMailbox mailbox = generations.register(key);
        try {
            if (descriptor != null) {
                publicRoutes.admit(key, descriptor);
            }
            return new Owner(this, key);
        } catch (RuntimeException | Error failure) {
            generations.unregister(key, mailbox);
            throw failure;
        }
    }

    /** Returns the active generation-zero owner shared by plugin contexts and integrations. */
    public PluginEventOwnerKey pluginOwner(final String pluginId) {
        final PluginEventOwnerKey key = new PluginEventOwnerKey(Requirements.text(pluginId, "pluginId"), 0L);
        generations.registerActive(key);
        return key;
    }

    /** Registers a typed subscription owned by an exact active plugin generation. */
    public <T extends EventBus.TurboismEvent> Registration subscribe(
            final PluginEventOwnerKey owner, final Class<T> type, final Consumer<T> listener) {
        publicRoutes.requireSubscription(owner, type);
        return subscribe(owner, type, EventPriority.NORMAL, 0, 0, true, true, listener);
    }

    /** Registers a deterministically ordered adapter subscription for a legacy hook. */
    public <T extends EventBus.TurboismEvent> Registration subscribeAdapter(
            final PluginEventOwnerKey owner,
            final Class<T> type,
            final int entrypointOrdinal,
            final int methodOrdinal,
            final Consumer<T> listener) {
        // Legacy hook adapters are already permission-gated when the registry builds
        // them (intercept/observe flags); they must not be filtered again at delivery.
        return subscribe(owner, type, EventPriority.NORMAL, entrypointOrdinal, methodOrdinal, false, false, listener);
    }

    private <T extends EventBus.TurboismEvent> Registration subscribe(
            final PluginEventOwnerKey owner,
            final Class<T> type,
            final EventPriority priority,
            final int entrypointOrdinal,
            final int methodOrdinal,
            final boolean deliverWhileEnabling,
            final boolean permissionFiltered,
            final Consumer<T> listener) {
        final PluginEventOwnerKey key = Objects.requireNonNull(owner, "owner");
        final EventSubscription<T> subscription;
        // The owner monitor is held across registration so a lifecycle transition cannot
        // interleave with the route that transition is about to fence.
        synchronized (generations.require(key).monitor()) {
            generations.requireSubscribable(key);
            subscription = registry.register(
                    key,
                    type,
                    priority,
                    entrypointOrdinal,
                    methodOrdinal,
                    deliverWhileEnabling,
                    permissionFiltered,
                    listener);
        }
        if (generations.require(key).lifecycleSnapshot() == OwnerLifecycle.ACTIVE) {
            replayRetained(subscription);
        }
        notifySubscriptionDemand(type);
        return () -> registry.remove(type, subscription);
    }

    private void notifySubscriptionDemand(final Class<?> subscribedType) {
        for (Consumer<Class<?>> listener : subscriptionDemandListeners) {
            try {
                listener.accept(subscribedType);
            } catch (Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                diagnostics.report(
                        EventDeliveryDiagnostics.internalOwner(),
                        subscribedType.getName(),
                        DeliveryDiagnostic.Code.SUBSCRIBER_FAILED);
            }
        }
    }

    /** Registers validated annotated subscribers for an exact plugin generation. */
    public List<Registration> registerAnnotated(
            final PluginEventOwnerKey owner, final List<EventSubscriberDescriptor> descriptors) {
        return registerAnnotated(owner, descriptors, List.of());
    }

    /** Registers subscribers and their entrypoint failure advice for one plugin generation. */
    public List<Registration> registerAnnotated(
            final PluginEventOwnerKey owner,
            final List<EventSubscriberDescriptor> descriptors,
            final List<?> entrypoints) {
        final PluginEventOwnerKey key = generations.requireSubscribable(owner);
        final List<EventSubscriberDescriptor> values = List.copyOf(Objects.requireNonNull(descriptors, "descriptors"));
        values.forEach(descriptor -> publicRoutes.requireSubscription(key, descriptor.eventType()));
        final EventFailureInterceptor interceptor = new EventFailureInterceptor(entrypoints);
        failureInterceptors.put(key, interceptor);
        final EventSubscriberInvoker invoker = new EventSubscriberInvoker();
        return List.copyOf(values.stream()
                .map(descriptor -> subscribeDescriptor(key, descriptor, invoker))
                .toList());
    }

    /**
     * Invokes Runtime-owned synchronous transform subscribers in deterministic order.
     *
     * <p>Each callback receives an event created from the preceding valid candidate.
     * Ordinary callback failures and rejected candidates restore that checkpoint and
     * do not prevent later subscribers. Fatal VM failures still propagate. A callback
     * that overruns the slow-delivery threshold is diagnosed once per owner and event
     * type; it is never interrupted, so the transform result is unchanged.</p>
     *
     * @param eventType concrete transform event state to route
     * @param initialValue initial candidate before subscribers
     * @param eventFactory opens one callback-scoped event from the current candidate
     * @param candidate extracts and validates the candidate after a successful callback
     * @return the final valid candidate
     */
    public float publishRuntimeTransform(
            final Class<? extends EventBus.TurboismEvent> eventType,
            final float initialValue,
            final java.util.function.Function<Float, ? extends TransformCallback> eventFactory,
            final java.util.function.Function<EventBus.TurboismEvent, Float> candidate) {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(eventFactory, "eventFactory");
        Objects.requireNonNull(candidate, "candidate");
        // A non-finite float is not a usable transform result, so it restores the checkpoint.
        return publishRuntimeTransform(
                eventType, Float.valueOf(initialValue), eventFactory, candidate, Float::isFinite);
    }

    /**
     * Invokes Runtime-owned synchronous reference transforms with checkpoint semantics.
     * Null or validator-rejected results restore the preceding valid candidate. A callback
     * that overruns the slow-delivery threshold is diagnosed once per owner and event type;
     * it is never interrupted, so the transform result is unchanged.
     */
    public <T> T publishRuntimeTransform(
            final Class<? extends EventBus.TurboismEvent> eventType,
            final T initialValue,
            final java.util.function.Function<T, ? extends TransformCallback> eventFactory,
            final java.util.function.Function<EventBus.TurboismEvent, T> candidate,
            final java.util.function.Predicate<T> validator) {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(initialValue, "initialValue");
        Objects.requireNonNull(eventFactory, "eventFactory");
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(validator, "validator");
        T current = initialValue;
        for (EventSubscription<? extends EventBus.TurboismEvent> subscription : registry.dispatchPlan(eventType)) {
            final EventOwnerGenerations.OwnerMailbox owner = generations.find(subscription.owner());
            if (owner == null
                    || !pipeline.mayDeliver(subscription, eventType)
                    || !owner.beginSynchronousDeliverySnapshot(subscription)) {
                continue;
            }
            final long startedNanos = System.nanoTime();
            try {
                try (TransformCallback callback =
                        Objects.requireNonNull(eventFactory.apply(current), "eventFactory result")) {
                    final EventBus.TurboismEvent event = Objects.requireNonNull(callback.event(), "transform event");
                    requireCompatibleTransformEvent(eventType, subscription, event);
                    try {
                        subscription.deliverDispatched(event);
                        final T transformed = candidate.apply(event);
                        if (transformed != null && validator.test(transformed)) {
                            current = transformed;
                        }
                    } catch (Throwable failure) {
                        FatalErrors.rethrowIfFatal(failure);
                        diagnostics.report(owner.key(), eventType.getName(), DeliveryDiagnostic.Code.SUBSCRIBER_FAILED);
                        // The checkpoint in current remains authoritative for later subscribers.
                    }
                }
            } finally {
                owner.deliveryFinished();
                diagnostics.reportSlowDeliveryIfOverrun(owner.key(), eventType, startedNanos);
            }
        }
        return current;
    }

    /**
     * Rejects a transform event that does not match the published type or the route's registered
     * type. A mismatched event would otherwise reach a listener that never agreed to receive it.
     */
    private static void requireCompatibleTransformEvent(
            final Class<? extends EventBus.TurboismEvent> eventType,
            final EventSubscription<? extends EventBus.TurboismEvent> subscription,
            final EventBus.TurboismEvent event) {
        if (!eventType.isInstance(event) || !subscription.type().isInstance(event)) {
            throw new IllegalArgumentException("Runtime transform factory produced an incompatible event: "
                    + event.getClass().getName());
        }
    }

    /** One callback-scoped Runtime transform event. */
    public interface TransformCallback extends AutoCloseable {
        /**
         * @return the event this callback may transform in place; valid only while the
         *         callback is open
         */
        EventBus.TurboismEvent event();

        @Override
        void close();
    }

    private Registration subscribeDescriptor(
            final PluginEventOwnerKey owner,
            final EventSubscriberDescriptor descriptor,
            final EventSubscriberInvoker invoker) {
        @SuppressWarnings({"unchecked", "rawtypes"})
        final Registration registration = subscribe(
                owner,
                (Class) descriptor.eventType(),
                descriptor.priority(),
                descriptor.entrypointOrdinal(),
                descriptor.methodOrdinal(),
                true,
                true,
                event -> invokeSafely(owner, invoker, descriptor, (EventBus.TurboismEvent) event));
        return registration;
    }

    private void invokeSafely(
            final PluginEventOwnerKey owner,
            final EventSubscriberInvoker invoker,
            final EventSubscriberDescriptor descriptor,
            final EventBus.TurboismEvent event) {
        try {
            invoker.invoke(descriptor, event);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            final EventFailureInterceptor interceptor = failureInterceptors.get(owner);
            final boolean advised =
                    interceptor != null && interceptor.intercept(owner.pluginId(), descriptor, event, failure);
            recordSubscriberFailure(owner, descriptor, event, failure, advised);
            if (failure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Event subscriber failed: " + descriptor.canonicalSignature(), failure);
        }
    }

    private void recordSubscriberFailure(
            final PluginEventOwnerKey owner,
            final EventSubscriberDescriptor descriptor,
            final EventBus.TurboismEvent event,
            final Throwable failure,
            final boolean advised) {
        try {
            subscriberFailureSink.accept(new SubscriberFailure(
                    owner,
                    descriptor.failureBoundary(),
                    event.getClass().getName(),
                    failure.getClass().getName(),
                    advised));
        } catch (Throwable ignored) {
            FatalErrors.rethrowIfFatal(ignored);
            // Structured failure collection cannot alter subscriber containment.
        }
    }

    /** Publishes a plugin-owned event after exact-owner and contract validation. */
    public <T extends EventBus.TurboismEvent> void publish(final PluginEventOwnerKey publisher, final T event) {
        generations.requireActive(publisher, "publish");
        final T value = Objects.requireNonNull(event, "event");
        contractCatalog.requirePluginPublicationAllowed(publisher, value);
        publicRoutes.requirePublication(publisher, value);
        pipeline.publishExact(value);
    }

    /** Publishes a Runtime-owned event from a reviewed Runtime integration point. */
    public <T extends EventBus.TurboismEvent> void publishRuntime(final T event) {
        publishRuntime(event, false);
    }

    /** Publishes and retains the latest Runtime-owned observation for late subscriber replay. */
    public <T extends EventBus.TurboismEvent> void publishRuntimeRetained(final T event) {
        publishRuntime(event, true);
    }

    private <T extends EventBus.TurboismEvent> void publishRuntime(final T event, final boolean retain) {
        final T value = Objects.requireNonNull(event, "event");
        if (retain) {
            retainedRuntimeEvents.put(value.getClass(), value);
        }
        pipeline.publishRouted(value);
    }

    private void replayRetained(final EventSubscription<? extends EventBus.TurboismEvent> subscription) {
        retainedRuntimeEvents.forEach((concreteType, event) -> {
            if (subscription.type().isAssignableFrom(concreteType)
                    && pipeline.mayDeliver(subscription, concreteType)
                    && publicRoutes.mayReceive(subscription.owner(), concreteType)) {
                pipeline.enqueue(event, subscription);
            }
        });
    }

    private void replayRetained(final PluginEventOwnerKey owner) {
        registry.routesOf(owner).forEach(this::replayRetained);
    }

    private void beginInitializing(final PluginEventOwnerKey owner) {
        generations.beginInitializing(owner);
    }

    private void beginEnabling(final PluginEventOwnerKey owner) {
        generations.beginEnabling(owner);
    }

    private void activate(final PluginEventOwnerKey owner) {
        generations.activate(owner, () -> publicRoutes.activate(owner));
        replayRetained(owner);
    }

    private void beginClosing(final PluginEventOwnerKey owner) {
        if (generations.beginClosing(owner)) {
            registry.removeOwner(owner);
        }
    }

    private boolean awaitQuiescence(final PluginEventOwnerKey owner, final Duration timeout) {
        return generations.awaitQuiescence(owner, timeout);
    }

    private void close(final PluginEventOwnerKey owner) {
        if (!generations.close(owner)) {
            return;
        }
        failureInterceptors.remove(owner);
        publicRoutes.remove(owner);
        pipeline.releaseOwner(owner);
        diagnostics.release(owner);
    }

    private void claimExecutors(final PluginEventOwnerKey key) {
        generations.claimExecutors(key);
    }

    private void releaseExecutors(final PluginEventOwnerKey key) {
        generations.releaseExecutors(key);
    }

    private void discardExecutors(final PluginEventOwnerKey key) {
        generations.discardExecutors(key);
    }

    private OwnerLifecycle lifecycle(final PluginEventOwnerKey owner) {
        return generations.lifecycle(owner);
    }

    private long droppedDeliveries(final PluginEventOwnerKey owner) {
        return generations.droppedDeliveries(owner);
    }

    /** Lifecycle handle retained by the exact plugin generation that was admitted. */
    public static final class Owner {
        private final RuntimeEventBroker broker;
        private final PluginEventOwnerKey key;

        private Owner(final RuntimeEventBroker broker, final PluginEventOwnerKey key) {
            this.broker = broker;
            this.key = key;
        }

        /** Returns the exact plugin identifier and generation owned by this handle. */
        public PluginEventOwnerKey key() {
            return key;
        }

        /** Registers validated annotated subscribers owned by this generation. */
        public List<Registration> registerAnnotated(final List<EventSubscriberDescriptor> descriptors) {
            return broker.registerAnnotated(key, descriptors);
        }

        /** Registers subscribers and entrypoint failure advice owned by this generation. */
        public List<Registration> registerAnnotated(
                final List<EventSubscriberDescriptor> descriptors, final List<?> entrypoints) {
            return broker.registerAnnotated(key, descriptors, entrypoints);
        }

        /** Advances this owner into plugin initialization. */
        public void beginInitializing() {
            broker.beginInitializing(key);
        }

        /** Advances this owner into plugin enabling. */
        public void beginEnabling() {
            broker.beginEnabling(key);
        }

        /** Activates staged subscriptions for event delivery. */
        public void activate() {
            broker.activate(key);
        }

        /** Stops new owner work and begins delivery shutdown. */
        public void beginClosing() {
            broker.beginClosing(key);
        }

        /**
         * Claims this generation's plugin executor set for lifecycle reclamation. Call at
         * fencing; the captured set is what {@link #releaseExecutors()} will shut down.
         * Idempotent per generation.
         */
        public void claimExecutors() {
            broker.claimExecutors(key);
        }

        /**
         * Releases this generation's claimed executor set: removed from the registry when still
         * mapped, then shut down either way. Safe to call after {@link #close()} — the failed
         * load path closes the owner before its executor release runs — and idempotent.
         */
        public void releaseExecutors() {
            broker.releaseExecutors(key);
        }

        /**
         * Aborts this generation's claimed executor set without waiting: removed from the
         * registry when still mapped, then discarded either way. Safe to call after
         * {@link #close()} and idempotent. Intended for failed generations whose owner is
         * already fenced, where a graceful shutdown has nothing admissible left to drain.
         */
        public void discardExecutors() {
            broker.discardExecutors(key);
        }

        /** Waits up to the timeout for this owner mailbox to become quiescent. */
        public boolean awaitQuiescence(final Duration timeout) {
            return broker.awaitQuiescence(key, timeout);
        }

        /** Releases this generation and all of its subscriptions and retained references. */
        public void close() {
            broker.close(key);
        }

        /** Returns the current lifecycle state of this owner generation. */
        public OwnerLifecycle lifecycle() {
            return broker.lifecycle(key);
        }

        /** Returns the number of deliveries dropped by this owner mailbox. */
        public long droppedDeliveries() {
            return broker.droppedDeliveries(key);
        }
    }

    /** Lifecycle state of one event-owner mailbox inside the broker. */
    public enum OwnerLifecycle {
        /** The owner is registered but not yet initializing; deliveries may queue. */
        ADMITTED,
        /** The owner is being initialized. */
        INITIALIZING,
        /** The owner is being enabled. */
        ENABLING,
        /** The owner receives deliveries normally. */
        ACTIVE,
        /** The owner is closing; new deliveries stop being admitted. */
        CLOSING,
        /** In-flight deliveries drained; the owner no longer runs callbacks. */
        QUIESCED,
        /** The owner is fully closed and its mailbox is released. */
        CLOSED
    }

    /** Structured, contained failure raised while delivering one event subscriber. */
    public record SubscriberFailure(
            PluginEventOwnerKey owner, String operationId, String eventType, String exceptionType, boolean advised) {
        /** Validates and normalizes one contained subscriber failure record. */
        public SubscriberFailure {
            owner = Objects.requireNonNull(owner, "owner");
            operationId = Requirements.text(operationId, "operationId");
            eventType = Requirements.text(eventType, "eventType");
            exceptionType = Requirements.text(exceptionType, "exceptionType");
        }
    }

    /** Structured diagnostic emitted when an event delivery cannot be admitted or completed. */
    public record DeliveryDiagnostic(PluginEventOwnerKey owner, String eventType, Code code) {
        /** Validates and normalizes one structured event delivery diagnostic. */
        public DeliveryDiagnostic {
            owner = Objects.requireNonNull(owner, "owner");
            eventType = Objects.requireNonNull(eventType, "eventType");
            code = Objects.requireNonNull(code, "code");
        }

        /** Why one delivery could not be admitted or completed. */
        public enum Code {
            /** The owner mailbox was full and the delivery was dropped. */
            MAILBOX_SATURATED,
            /** The scheduler refused the drain task. */
            SCHEDULER_REJECTED,
            /** The subscriber callback threw; the failure was contained. */
            SUBSCRIBER_FAILED,
            /** The bound permission checker denied delivery of the concrete event type. */
            DELIVERY_PERMISSION_DENIED,
            /** The subscriber callback exceeded the slow-delivery threshold. */
            SUBSCRIBER_SLOW
        }
    }
}
