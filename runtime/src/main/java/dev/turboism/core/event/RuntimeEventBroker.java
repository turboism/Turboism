package dev.turboism.core.event;

import dev.turboism.core.runtime.PluginTask;
import dev.turboism.core.runtime.RuntimeCancellationToken;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.work.PluginWorkSubmission;
import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.event.EventPriority;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.Registration;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Session-scoped registry and dispatcher shared by plugin event-bus facades. */
public final class RuntimeEventBroker {

    private static final String EVENT_TASK_TYPE = "event.subscribe";
    private static final String DEFAULT_CAPABILITY = "none";
    private static final int DEFAULT_MAILBOX_CAPACITY = 64;
    private static final Comparator<Subscription<? extends EventBus.TurboismEvent>> SUBSCRIPTION_ORDER =
        Comparator
            .comparingInt((Subscription<? extends EventBus.TurboismEvent> value) ->
                value.priority().ordinal()
            )
            .thenComparing(value -> value.owner().pluginId())
            .thenComparingLong(value -> value.owner().generation())
            .thenComparingInt(Subscription::entrypointOrdinal)
            .thenComparingInt(Subscription::methodOrdinal)
            .thenComparingLong(Subscription::sequence);

    private final RuntimeScheduler scheduler;
    private final Object subscriptionLock = new Object();
    private final RuntimeEventContractCatalog contractCatalog = new RuntimeEventContractCatalog();
    private final PublicEventRouteCatalog publicRoutes = new PublicEventRouteCatalog();
    private final int mailboxCapacity;
    private final AtomicLong sequence = new AtomicLong();
    private final ConcurrentMap<String, AtomicLong> generations = new ConcurrentHashMap<>();
    private final ConcurrentMap<PluginEventOwnerKey, OwnerState> owners = new ConcurrentHashMap<>();
    private final ConcurrentMap<Class<? extends EventBus.TurboismEvent>, CopyOnWriteArrayList<Subscription<? extends EventBus.TurboismEvent>>> subscribers =
        new ConcurrentHashMap<>();
    private final ConcurrentMap<Class<?>, List<Subscription<? extends EventBus.TurboismEvent>>> dispatchPlans =
        new ConcurrentHashMap<>();
    private final Consumer<DeliveryDiagnostic> diagnosticSink;
    private final Consumer<SubscriberFailure> subscriberFailureSink;
    private final ConcurrentMap<PluginEventOwnerKey, EventFailureInterceptor> failureInterceptors =
        new ConcurrentHashMap<>();
    private final ConcurrentMap<Class<?>, AtomicReference<?>> runtimeObservationBaselines =
        new ConcurrentHashMap<>();
    private final ConcurrentMap<Class<?>, EventBus.TurboismEvent> retainedRuntimeEvents =
        new ConcurrentHashMap<>();

    public RuntimeEventBroker(final RuntimeScheduler scheduler) {
        this(scheduler, DEFAULT_MAILBOX_CAPACITY, ignored -> { }, ignored -> { });
    }

    RuntimeEventBroker(final RuntimeScheduler scheduler, final int mailboxCapacity) {
        this(scheduler, mailboxCapacity, ignored -> { }, ignored -> { });
    }

    public RuntimeEventBroker(
        final RuntimeScheduler scheduler,
        final int mailboxCapacity,
        final Consumer<DeliveryDiagnostic> diagnosticSink
    ) {
        this(scheduler, mailboxCapacity, diagnosticSink, ignored -> { });
    }

    public RuntimeEventBroker(
        final RuntimeScheduler scheduler,
        final int mailboxCapacity,
        final Consumer<DeliveryDiagnostic> diagnosticSink,
        final Consumer<SubscriberFailure> subscriberFailureSink
    ) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        if (mailboxCapacity < 1) {
            throw new IllegalArgumentException("mailboxCapacity must be positive");
        }
        this.mailboxCapacity = mailboxCapacity;
        this.diagnosticSink = Objects.requireNonNull(diagnosticSink, "diagnosticSink");
        this.subscriberFailureSink = Objects.requireNonNull(
            subscriberFailureSink,
            "subscriberFailureSink"
        );
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
        return (AtomicReference<T>) runtimeObservationBaselines.computeIfAbsent(
            keyType,
            ignored -> new AtomicReference<>()
        );
    }

    /** Admits one inactive plugin generation whose subscriptions can be staged before activation. */
    public Owner admit(final String pluginId) {
        return admit(requireText(pluginId, "pluginId"), null);
    }

    /** Admits one generation and registers its descriptor-declared public event contracts. */
    public Owner admit(final PluginDescriptor descriptor) {
        final PluginDescriptor value = Objects.requireNonNull(descriptor, "descriptor");
        return admit(requireText(value.id(), "descriptor.id"), value);
    }

    private Owner admit(final String id, final PluginDescriptor descriptor) {
        final long generation = generations
            .computeIfAbsent(id, ignored -> new AtomicLong())
            .incrementAndGet();
        final PluginEventOwnerKey key = new PluginEventOwnerKey(id, generation);
        final OwnerState state = new OwnerState(key, mailboxCapacity);
        if (owners.putIfAbsent(key, state) != null) {
            throw new IllegalStateException("Plugin event owner generation already exists: " + key);
        }
        try {
            if (descriptor != null) {
                publicRoutes.admit(key, descriptor);
            }
            return new Owner(this, key);
        } catch (RuntimeException | Error failure) {
            owners.remove(key, state);
            throw failure;
        }
    }

    /** Returns the active generation-zero owner used by compatibility integrations. */
    public PluginEventOwnerKey legacyOwner(final String pluginId) {
        final String id = requireText(pluginId, "pluginId");
        final PluginEventOwnerKey key = new PluginEventOwnerKey(id, 0L);
        owners.computeIfAbsent(key, ignored -> OwnerState.active(key, mailboxCapacity));
        return key;
    }

    /** Registers a compatibility subscription under the plugin generation-zero owner. */
    public <T extends EventBus.TurboismEvent> Registration subscribe(
        final String pluginId,
        final Class<T> type,
        final Consumer<T> listener
    ) {
        return subscribe(legacyOwner(pluginId), type, listener);
    }

    /** Registers a typed subscription owned by an exact active plugin generation. */
    public <T extends EventBus.TurboismEvent> Registration subscribe(
        final PluginEventOwnerKey owner,
        final Class<T> type,
        final Consumer<T> listener
    ) {
        publicRoutes.requireSubscription(owner, type);
        return subscribe(owner, type, EventPriority.NORMAL, 0, 0, true, listener);
    }

    /** Registers a deterministically ordered adapter subscription for a legacy hook. */
    public <T extends EventBus.TurboismEvent> Registration subscribeAdapter(
        final PluginEventOwnerKey owner,
        final Class<T> type,
        final int entrypointOrdinal,
        final int methodOrdinal,
        final Consumer<T> listener
    ) {
        return subscribe(
            owner,
            type,
            EventPriority.NORMAL,
            entrypointOrdinal,
            methodOrdinal,
            false,
            listener
        );
    }

    private <T extends EventBus.TurboismEvent> Registration subscribe(
        final PluginEventOwnerKey owner,
        final Class<T> type,
        final EventPriority priority,
        final int entrypointOrdinal,
        final int methodOrdinal,
        final boolean deliverWhileEnabling,
        final Consumer<T> listener
    ) {
        final PluginEventOwnerKey key = Objects.requireNonNull(owner, "owner");
        final Subscription<T> subscription;
        synchronized (requireOwner(key).monitor()) {
            requireSubscribableOwner(key);
            subscription = new Subscription<>(
                sequence.incrementAndGet(),
                key,
                Objects.requireNonNull(type, "type"),
                Objects.requireNonNull(priority, "priority"),
                entrypointOrdinal,
                methodOrdinal,
                deliverWhileEnabling,
                Objects.requireNonNull(listener, "listener")
            );
            synchronized (subscriptionLock) {
                final CopyOnWriteArrayList<Subscription<? extends EventBus.TurboismEvent>> route =
                    subscribers.computeIfAbsent(type, ignored -> new CopyOnWriteArrayList<>());
                route.add(subscription);
                route.sort(SUBSCRIPTION_ORDER);
                dispatchPlans.clear();
            }
        }
        if (requireOwner(key).lifecycleSnapshot() == OwnerLifecycle.ACTIVE) {
            replayRetained(subscription);
        }
        return () -> remove(type, subscription);
    }

    /** Registers annotated subscribers under the plugin generation-zero owner. */
    public List<Registration> registerAnnotated(
        final String pluginId,
        final List<EventSubscriberDescriptor> descriptors
    ) {
        return registerAnnotated(legacyOwner(pluginId), descriptors, List.of());
    }

    /** Registers validated annotated subscribers for an exact plugin generation. */
    public List<Registration> registerAnnotated(
        final PluginEventOwnerKey owner,
        final List<EventSubscriberDescriptor> descriptors
    ) {
        return registerAnnotated(owner, descriptors, List.of());
    }

    /** Registers subscribers and their entrypoint failure advice for one plugin generation. */
    public List<Registration> registerAnnotated(
        final PluginEventOwnerKey owner,
        final List<EventSubscriberDescriptor> descriptors,
        final List<?> entrypoints
    ) {
        final PluginEventOwnerKey key = requireSubscribableOwner(owner);
        final List<EventSubscriberDescriptor> values = List.copyOf(
            Objects.requireNonNull(descriptors, "descriptors")
        );
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
     * do not prevent later subscribers. Fatal VM failures still propagate.</p>
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
        final java.util.function.Function<EventBus.TurboismEvent, Float> candidate
    ) {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(eventFactory, "eventFactory");
        Objects.requireNonNull(candidate, "candidate");
        float current = initialValue;
        final List<Subscription<? extends EventBus.TurboismEvent>> route = dispatchPlan(eventType);
        for (Subscription<? extends EventBus.TurboismEvent> subscription : route) {
            final OwnerState owner = owners.get(subscription.owner());
            if (owner == null || !owner.beginSynchronousDeliverySnapshot(subscription)) {
                continue;
            }
            try {
                try (TransformCallback callback = Objects.requireNonNull(
                    eventFactory.apply(current),
                    "eventFactory result"
                )) {
                    final EventBus.TurboismEvent event = Objects.requireNonNull(
                        callback.event(),
                        "transform event"
                    );
                    if (!eventType.isInstance(event) || !subscription.type().isInstance(event)) {
                        throw new IllegalArgumentException(
                            "Runtime transform factory produced an incompatible event: "
                                + event.getClass().getName()
                        );
                    }
                    try {
                        subscription.deliverDispatched(event);
                        final Float transformed = candidate.apply(event);
                        if (transformed != null && Float.isFinite(transformed)) {
                            current = transformed;
                        }
                    } catch (ThreadDeath | VirtualMachineError fatal) {
                        throw fatal;
                    } catch (Throwable failure) {
                        diagnose(new DeliveryDiagnostic(
                            owner.key(),
                            eventType.getName(),
                            DeliveryDiagnostic.Code.SUBSCRIBER_FAILED
                        ));
                        // The checkpoint in current remains authoritative for later subscribers.
                    }
                }
            } finally {
                owner.deliveryFinished();
            }
        }
        return current;
    }

    /**
     * Invokes Runtime-owned synchronous reference transforms with checkpoint semantics.
     * Null or validator-rejected results restore the preceding valid candidate.
     */
    public <T> T publishRuntimeTransform(
        final Class<? extends EventBus.TurboismEvent> eventType,
        final T initialValue,
        final java.util.function.Function<T, ? extends TransformCallback> eventFactory,
        final java.util.function.Function<EventBus.TurboismEvent, T> candidate,
        final java.util.function.Predicate<T> validator
    ) {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(initialValue, "initialValue");
        Objects.requireNonNull(eventFactory, "eventFactory");
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(validator, "validator");
        T current = initialValue;
        final List<Subscription<? extends EventBus.TurboismEvent>> route = dispatchPlan(eventType);
        for (Subscription<? extends EventBus.TurboismEvent> subscription : route) {
            final OwnerState owner = owners.get(subscription.owner());
            if (owner == null || !owner.beginSynchronousDeliverySnapshot(subscription)) {
                continue;
            }
            try {
                try (TransformCallback callback = Objects.requireNonNull(
                    eventFactory.apply(current),
                    "eventFactory result"
                )) {
                    final EventBus.TurboismEvent event = Objects.requireNonNull(
                        callback.event(),
                        "transform event"
                    );
                    if (!eventType.isInstance(event) || !subscription.type().isInstance(event)) {
                        throw new IllegalArgumentException(
                            "Runtime transform factory produced an incompatible event: "
                                + event.getClass().getName()
                        );
                    }
                    try {
                        subscription.deliverDispatched(event);
                        final T transformed = candidate.apply(event);
                        if (transformed != null && validator.test(transformed)) {
                            current = transformed;
                        }
                    } catch (ThreadDeath | VirtualMachineError fatal) {
                        throw fatal;
                    } catch (Throwable failure) {
                        diagnose(new DeliveryDiagnostic(
                            owner.key(),
                            eventType.getName(),
                            DeliveryDiagnostic.Code.SUBSCRIBER_FAILED
                        ));
                        // The checkpoint in current remains authoritative for later subscribers.
                    }
                }
            } finally {
                owner.deliveryFinished();
            }
        }
        return current;
    }

    /** One callback-scoped Runtime transform event. */
    public interface TransformCallback extends AutoCloseable {
        EventBus.TurboismEvent event();
        @Override void close();
    }

    private Registration subscribeDescriptor(
        final PluginEventOwnerKey owner,
        final EventSubscriberDescriptor descriptor,
        final EventSubscriberInvoker invoker
    ) {
        @SuppressWarnings({"unchecked", "rawtypes"})
        final Registration registration = subscribe(
            owner,
            (Class) descriptor.eventType(),
            descriptor.priority(),
            descriptor.entrypointOrdinal(),
            descriptor.methodOrdinal(),
            true,
            event -> invokeSafely(
                owner,
                invoker,
                descriptor,
                (EventBus.TurboismEvent) event
            )
        );
        return registration;
    }

    private void invokeSafely(
        final PluginEventOwnerKey owner,
        final EventSubscriberInvoker invoker,
        final EventSubscriberDescriptor descriptor,
        final EventBus.TurboismEvent event
    ) {
        try {
            invoker.invoke(descriptor, event);
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            final EventFailureInterceptor interceptor = failureInterceptors.get(owner);
            final boolean advised = interceptor != null
                && interceptor.intercept(owner.pluginId(), descriptor, event, failure);
            recordSubscriberFailure(owner, descriptor, event, failure, advised);
            if (failure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(
                "Event subscriber failed: " + descriptor.canonicalSignature(),
                failure
            );
        }
    }

    private void recordSubscriberFailure(
        final PluginEventOwnerKey owner,
        final EventSubscriberDescriptor descriptor,
        final EventBus.TurboismEvent event,
        final Throwable failure,
        final boolean advised
    ) {
        try {
            subscriberFailureSink.accept(new SubscriberFailure(
                owner,
                descriptor.failureBoundary(),
                event.getClass().getName(),
                failure.getClass().getName(),
                advised
            ));
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // Structured failure collection cannot alter subscriber containment.
        }
    }

    /** Publishes a plugin-owned event from the compatibility generation-zero owner. */
    public <T extends EventBus.TurboismEvent> void publish(
        final String publisherPluginId,
        final T event
    ) {
        final PluginEventOwnerKey publisher = legacyOwner(publisherPluginId);
        requireActiveOwner(publisher, "publish");
        final T value = Objects.requireNonNull(event, "event");
        contractCatalog.requirePluginPublicationAllowed(publisher, value);
        publicRoutes.requirePublication(publisher, value);
        publishExact(value);
    }

    /** Publishes a plugin-owned event after exact-owner and contract validation. */
    public <T extends EventBus.TurboismEvent> void publish(
        final PluginEventOwnerKey publisher,
        final T event
    ) {
        requireActiveOwner(publisher, "publish");
        final T value = Objects.requireNonNull(event, "event");
        contractCatalog.requirePluginPublicationAllowed(publisher, value);
        publicRoutes.requirePublication(publisher, value);
        publishExact(value);
    }

    <T extends EventBus.TurboismEvent> void publishExact(
        final PluginEventOwnerKey publisher,
        final T event
    ) {
        requireActiveOwner(publisher, "publish");
        final T value = Objects.requireNonNull(event, "event");
        contractCatalog.requirePluginPublicationAllowed(publisher, value);
        publicRoutes.requirePublication(publisher, value);
        publishExact(value);
    }

    private <T extends EventBus.TurboismEvent> void publishExact(final T event) {
        final List<Subscription<? extends EventBus.TurboismEvent>> route;
        synchronized (subscriptionLock) {
            final CopyOnWriteArrayList<Subscription<? extends EventBus.TurboismEvent>> current =
                subscribers.get(event.getClass());
            route = current == null ? List.of() : List.copyOf(current);
        }
        for (Subscription<? extends EventBus.TurboismEvent> subscription : route) {
            if (publicRoutes.mayReceive(subscription.owner(), event.getClass())) {
                enqueue(event, subscription);
            }
        }
    }

    /** Publishes a Runtime-owned event from a reviewed Runtime integration point. */
    public <T extends EventBus.TurboismEvent> void publishRuntime(final T event) {
        publishRuntime(event, false);
    }

    /** Publishes and retains the latest Runtime-owned observation for late subscriber replay. */
    public <T extends EventBus.TurboismEvent> void publishRuntimeRetained(final T event) {
        publishRuntime(event, true);
    }

    private <T extends EventBus.TurboismEvent> void publishRuntime(
        final T event,
        final boolean retain
    ) {
        final T value = Objects.requireNonNull(event, "event");
        if (retain) {
            retainedRuntimeEvents.put(value.getClass(), value);
        }
        for (Subscription<? extends EventBus.TurboismEvent> subscription : dispatchPlan(
            value.getClass()
        )) {
            if (publicRoutes.mayReceive(subscription.owner(), value.getClass())) {
                enqueue(value, subscription);
            }
        }
    }

    private List<Subscription<? extends EventBus.TurboismEvent>> dispatchPlan(
        final Class<?> concreteType
    ) {
        final List<Subscription<? extends EventBus.TurboismEvent>> cached =
            dispatchPlans.get(concreteType);
        if (cached != null) {
            return cached;
        }
        synchronized (subscriptionLock) {
            final List<Subscription<? extends EventBus.TurboismEvent>> current =
                dispatchPlans.get(concreteType);
            if (current != null) {
                return current;
            }
            final List<Subscription<? extends EventBus.TurboismEvent>> planned =
                subscribers.entrySet().stream()
                    .filter(entry -> entry.getKey().isAssignableFrom(concreteType))
                    .flatMap(entry -> entry.getValue().stream())
                    .sorted(SUBSCRIPTION_ORDER)
                    .toList();
            dispatchPlans.put(concreteType, planned);
            return planned;
        }
    }

    private void replayRetained(
        final Subscription<? extends EventBus.TurboismEvent> subscription
    ) {
        retainedRuntimeEvents.forEach((concreteType, event) -> {
            if (subscription.type().isAssignableFrom(concreteType)
                && publicRoutes.mayReceive(subscription.owner(), concreteType)) {
                enqueue(event, subscription);
            }
        });
    }

    private void replayRetained(final PluginEventOwnerKey owner) {
        subscribers.values().stream()
            .flatMap(List::stream)
            .filter(subscription -> subscription.owner().equals(owner))
            .forEach(this::replayRetained);
    }

    private <T extends EventBus.TurboismEvent> void remove(
        final Class<T> type,
        final Subscription<T> subscription
    ) {
        subscription.deactivate();
        synchronized (subscriptionLock) {
            final CopyOnWriteArrayList<Subscription<? extends EventBus.TurboismEvent>>
                eventSubscribers = subscribers.get(type);
            if (eventSubscribers == null) {
                return;
            }
            eventSubscribers.remove(subscription);
            dispatchPlans.clear();
            if (eventSubscribers.isEmpty()) {
                subscribers.remove(type, eventSubscribers);
            }
        }
    }

    private <T extends EventBus.TurboismEvent> void enqueue(
        final T event,
        final Subscription<? extends EventBus.TurboismEvent> subscription
    ) {
        if (!subscription.active() || !subscription.type().isInstance(event)) {
            return;
        }
        final OwnerState owner = owners.get(subscription.owner());
        if (owner == null || !subscription.accepts(owner.lifecycleSnapshot())) {
            return;
        }
        final EnqueueResult result = owner.enqueue(new Delivery(event, subscription));
        if (result == EnqueueResult.SCHEDULE) {
            scheduleDrain(owner);
        } else if (result == EnqueueResult.SATURATED) {
            diagnose(new DeliveryDiagnostic(
                owner.key(),
                event.getClass().getName(),
                DeliveryDiagnostic.Code.MAILBOX_SATURATED
            ));
        }
    }

    private void scheduleDrain(final OwnerState owner) {
        final RuntimeCancellationToken token = owner.drainToken();
        final PluginWorkSubmission submission = scheduler.submitLightweight(
            new PluginTask(
                EVENT_TASK_TYPE,
                owner.key().pluginId(),
                owner.key().pluginId() + " event generation " + owner.key().generation(),
                DEFAULT_CAPABILITY
            ),
            token,
            () -> drain(owner)
        );
        if (!submission.accepted()) {
            owner.rejectDrain();
            diagnose(new DeliveryDiagnostic(
                owner.key(),
                "",
                DeliveryDiagnostic.Code.SCHEDULER_REJECTED
            ));
        }
    }

    private void drain(final OwnerState owner) {
        try {
            while (true) {
                final Delivery delivery = owner.nextDelivery();
                if (delivery == null) {
                    break;
                }
                try {
                    delivery.deliver(owner.key());
                } catch (ThreadDeath | VirtualMachineError fatal) {
                    throw fatal;
                } catch (Throwable failure) {
                    diagnose(new DeliveryDiagnostic(
                        owner.key(),
                        delivery.event().getClass().getName(),
                        DeliveryDiagnostic.Code.SUBSCRIBER_FAILED
                    ));
                } finally {
                    owner.deliveryFinished();
                }
            }
        } finally {
            final boolean reschedule = owner.drainFinished();
            if (reschedule) {
                scheduleDrain(owner);
            }
        }
    }

    private void diagnose(final DeliveryDiagnostic diagnostic) {
        try {
            diagnosticSink.accept(diagnostic);
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // Diagnostics must never alter publication or host-operation behavior.
        }
    }

    private PluginEventOwnerKey requireSubscribableOwner(final PluginEventOwnerKey owner) {
        final OwnerState state = requireOwner(owner);
        synchronized (state.monitor()) {
            if (state.lifecycle() != OwnerLifecycle.ADMITTED
                && state.lifecycle() != OwnerLifecycle.INITIALIZING
                && state.lifecycle() != OwnerLifecycle.ENABLING
                && state.lifecycle() != OwnerLifecycle.ACTIVE) {
                throw new IllegalStateException(
                    "Plugin event owner does not accept subscriptions: " + owner
                        + " state=" + state.lifecycle()
                );
            }
        }
        return owner;
    }

    private OwnerState requireActiveOwner(
        final PluginEventOwnerKey owner,
        final String operation
    ) {
        final OwnerState state = requireOwner(owner);
        synchronized (state.monitor()) {
            if (state.lifecycle() != OwnerLifecycle.ACTIVE
                && state.lifecycle() != OwnerLifecycle.ENABLING
                && state.lifecycle() != OwnerLifecycle.INITIALIZING) {
                throw new IllegalStateException(
                    "Plugin event owner cannot " + operation + ": " + owner
                        + " state=" + state.lifecycle()
                );
            }
        }
        return state;
    }

    private OwnerState requireOwner(final PluginEventOwnerKey owner) {
        final PluginEventOwnerKey key = Objects.requireNonNull(owner, "owner");
        final OwnerState state = owners.get(key);
        if (state == null) {
            throw new IllegalStateException("Unknown plugin event owner: " + key);
        }
        return state;
    }

    private void beginInitializing(final PluginEventOwnerKey owner) {
        final OwnerState state = requireOwner(owner);
        synchronized (state.monitor()) {
            if (state.lifecycle() != OwnerLifecycle.ADMITTED) {
                throw new IllegalStateException(
                    "Plugin event owner cannot begin initializing: " + owner
                        + " state=" + state.lifecycle()
                );
            }
            state.lifecycle(OwnerLifecycle.INITIALIZING);
        }
    }

    private void beginEnabling(final PluginEventOwnerKey owner) {
        final OwnerState state = requireOwner(owner);
        synchronized (state.monitor()) {
            if (state.lifecycle() != OwnerLifecycle.INITIALIZING) {
                throw new IllegalStateException(
                    "Plugin event owner cannot begin enabling: " + owner
                        + " state=" + state.lifecycle()
                );
            }
            state.lifecycle(OwnerLifecycle.ENABLING);
        }
    }

    private void activate(final PluginEventOwnerKey owner) {
        final OwnerState state = requireOwner(owner);
        synchronized (state.monitor()) {
            if (state.lifecycle() != OwnerLifecycle.ADMITTED
                && state.lifecycle() != OwnerLifecycle.ENABLING) {
                throw new IllegalStateException(
                    "Plugin event owner cannot activate: " + owner
                        + " state=" + state.lifecycle()
                );
            }
            publicRoutes.activate(owner);
            state.lifecycle(OwnerLifecycle.ACTIVE);
        }
        replayRetained(owner);
    }

    private void beginClosing(final PluginEventOwnerKey owner) {
        final OwnerState state = owners.get(Objects.requireNonNull(owner, "owner"));
        if (state == null) {
            return;
        }
        synchronized (state.monitor()) {
            if (state.lifecycle() == OwnerLifecycle.CLOSING
                || state.lifecycle() == OwnerLifecycle.QUIESCED
                || state.lifecycle() == OwnerLifecycle.CLOSED) {
                return;
            }
            state.lifecycle(OwnerLifecycle.CLOSING);
            state.cancelPending();
        }
        removeOwnerSubscriptions(owner);
    }

    private boolean awaitQuiescence(
        final PluginEventOwnerKey owner,
        final Duration timeout
    ) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        final OwnerState state = owners.get(Objects.requireNonNull(owner, "owner"));
        if (state == null) {
            return true;
        }
        final long timeoutNanos = timeout.toNanos();
        final long deadline = System.nanoTime() + timeoutNanos;
        boolean interrupted = false;
        synchronized (state.monitor()) {
            if (state.lifecycle() != OwnerLifecycle.CLOSING
                && state.lifecycle() != OwnerLifecycle.QUIESCED
                && state.lifecycle() != OwnerLifecycle.CLOSED) {
                throw new IllegalStateException(
                    "Plugin event owner must begin closing before quiescence: " + owner
                );
            }
            while (!state.quiescent()) {
                final long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    if (interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return false;
                }
                try {
                    final long millis = remaining / 1_000_000L;
                    final int nanos = (int) (remaining % 1_000_000L);
                    state.monitor().wait(millis, nanos);
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            if (state.lifecycle() == OwnerLifecycle.CLOSING) {
                state.lifecycle(OwnerLifecycle.QUIESCED);
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return true;
    }

    private void close(final PluginEventOwnerKey owner) {
        final OwnerState state = owners.get(Objects.requireNonNull(owner, "owner"));
        if (state == null) {
            return;
        }
        synchronized (state.monitor()) {
            if (state.lifecycle() != OwnerLifecycle.QUIESCED
                && state.lifecycle() != OwnerLifecycle.CLOSED) {
                throw new IllegalStateException(
                    "Plugin event owner must quiesce before close: " + owner
                        + " state=" + state.lifecycle()
                );
            }
            state.lifecycle(OwnerLifecycle.CLOSED);
        }
        owners.remove(owner, state);
        failureInterceptors.remove(owner);
        publicRoutes.remove(owner);
    }

    private OwnerLifecycle lifecycle(final PluginEventOwnerKey owner) {
        final OwnerState state = owners.get(Objects.requireNonNull(owner, "owner"));
        if (state == null) {
            return OwnerLifecycle.CLOSED;
        }
        synchronized (state.monitor()) {
            return state.lifecycle();
        }
    }

    private long droppedDeliveries(final PluginEventOwnerKey owner) {
        final OwnerState state = owners.get(Objects.requireNonNull(owner, "owner"));
        if (state == null) {
            return 0L;
        }
        synchronized (state.monitor()) {
            return state.droppedDeliveries();
        }
    }

    private void removeOwnerSubscriptions(final PluginEventOwnerKey owner) {
        synchronized (subscriptionLock) {
            subscribers.forEach((type, route) -> {
                route.removeIf(subscription -> {
                    if (!subscription.owner().equals(owner)) {
                        return false;
                    }
                    subscription.deactivate();
                    return true;
                });
                if (route.isEmpty()) {
                    subscribers.remove(type, route);
                }
            });
            dispatchPlans.clear();
        }
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
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
        public List<Registration> registerAnnotated(
            final List<EventSubscriberDescriptor> descriptors
        ) {
            return broker.registerAnnotated(key, descriptors);
        }

        /** Registers subscribers and entrypoint failure advice owned by this generation. */
        public List<Registration> registerAnnotated(
            final List<EventSubscriberDescriptor> descriptors,
            final List<?> entrypoints
        ) {
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

    public enum OwnerLifecycle {
        ADMITTED,
        INITIALIZING,
        ENABLING,
        ACTIVE,
        CLOSING,
        QUIESCED,
        CLOSED
    }

    /** Structured, contained failure raised while delivering one event subscriber. */
    public record SubscriberFailure(
        PluginEventOwnerKey owner,
        String operationId,
        String eventType,
        String exceptionType,
        boolean advised
    ) {
        /** Validates and normalizes one contained subscriber failure record. */
        public SubscriberFailure {
            owner = Objects.requireNonNull(owner, "owner");
            operationId = requireText(operationId, "operationId");
            eventType = requireText(eventType, "eventType");
            exceptionType = requireText(exceptionType, "exceptionType");
        }
    }

    /** Structured diagnostic emitted when an event delivery cannot be admitted or completed. */
    public record DeliveryDiagnostic(
        PluginEventOwnerKey owner,
        String eventType,
        Code code
    ) {
        /** Validates and normalizes one structured event delivery diagnostic. */
        public DeliveryDiagnostic {
            owner = Objects.requireNonNull(owner, "owner");
            eventType = Objects.requireNonNull(eventType, "eventType");
            code = Objects.requireNonNull(code, "code");
        }

        public enum Code {
            MAILBOX_SATURATED,
            SCHEDULER_REJECTED,
            SUBSCRIBER_FAILED
        }
    }

    private enum EnqueueResult {
        SCHEDULE,
        QUEUED,
        SATURATED,
        REJECTED_LIFECYCLE
    }

    private static final class OwnerState {
        private final PluginEventOwnerKey key;
        private final int mailboxCapacity;
        private final Object monitor = new Object();
        private final ArrayDeque<Delivery> mailbox = new ArrayDeque<>();
        private OwnerLifecycle lifecycle;
        private RuntimeCancellationToken drainToken = new RuntimeCancellationToken();
        private boolean drainScheduled;
        private int runningDeliveries;
        private long droppedDeliveries;

        private OwnerState(final PluginEventOwnerKey key, final int mailboxCapacity) {
            this(key, mailboxCapacity, OwnerLifecycle.ADMITTED);
        }

        private OwnerState(
            final PluginEventOwnerKey key,
            final int mailboxCapacity,
            final OwnerLifecycle lifecycle
        ) {
            this.key = key;
            this.mailboxCapacity = mailboxCapacity;
            this.lifecycle = lifecycle;
        }

        private static OwnerState active(
            final PluginEventOwnerKey key,
            final int mailboxCapacity
        ) {
            return new OwnerState(key, mailboxCapacity, OwnerLifecycle.ACTIVE);
        }

        private PluginEventOwnerKey key() {
            return key;
        }

        private Object monitor() {
            return monitor;
        }

        private OwnerLifecycle lifecycleSnapshot() {
            synchronized (monitor) {
                return lifecycle;
            }
        }

        private OwnerLifecycle lifecycle() {
            return lifecycle;
        }

        private void lifecycle(final OwnerLifecycle value) {
            lifecycle = value;
        }

        private EnqueueResult enqueue(final Delivery delivery) {
            synchronized (monitor) {
                if (lifecycle != OwnerLifecycle.ACTIVE
                    && lifecycle != OwnerLifecycle.ENABLING
                    && lifecycle != OwnerLifecycle.INITIALIZING) {
                    return EnqueueResult.REJECTED_LIFECYCLE;
                }
                if (mailbox.size() >= mailboxCapacity) {
                    droppedDeliveries++;
                    return EnqueueResult.SATURATED;
                }
                mailbox.addLast(delivery);
                if (drainScheduled) {
                    return EnqueueResult.QUEUED;
                }
                drainScheduled = true;
                drainToken = new RuntimeCancellationToken();
                return EnqueueResult.SCHEDULE;
            }
        }

        private boolean beginSynchronousDeliverySnapshot(
            final Subscription<? extends EventBus.TurboismEvent> subscription
        ) {
            synchronized (monitor) {
                if (!subscription.accepts(lifecycle)) {
                    return false;
                }
                runningDeliveries++;
                return true;
            }
        }

        private RuntimeCancellationToken drainToken() {
            synchronized (monitor) {
                return drainToken;
            }
        }

        private Delivery nextDelivery() {
            synchronized (monitor) {
                if (lifecycle != OwnerLifecycle.ACTIVE
                    && lifecycle != OwnerLifecycle.ENABLING
                    && lifecycle != OwnerLifecycle.INITIALIZING) {
                    droppedDeliveries += mailbox.size();
                    mailbox.clear();
                    return null;
                }
                final Delivery delivery = mailbox.pollFirst();
                if (delivery != null) {
                    runningDeliveries++;
                }
                return delivery;
            }
        }

        private void deliveryFinished() {
            synchronized (monitor) {
                runningDeliveries--;
                monitor.notifyAll();
            }
        }

        private boolean drainFinished() {
            synchronized (monitor) {
                drainScheduled = false;
                if ((lifecycle == OwnerLifecycle.ACTIVE
                    || lifecycle == OwnerLifecycle.ENABLING
                    || lifecycle == OwnerLifecycle.INITIALIZING)
                    && !mailbox.isEmpty()) {
                    drainScheduled = true;
                    drainToken = new RuntimeCancellationToken();
                    return true;
                }
                monitor.notifyAll();
                return false;
            }
        }

        private void rejectDrain() {
            synchronized (monitor) {
                droppedDeliveries += mailbox.size();
                mailbox.clear();
                drainScheduled = false;
                monitor.notifyAll();
            }
        }

        private void cancelPending() {
            droppedDeliveries += mailbox.size();
            mailbox.clear();
            drainToken.cancel();
            monitor.notifyAll();
        }

        private boolean quiescent() {
            return !drainScheduled && runningDeliveries == 0;
        }

        private long droppedDeliveries() {
            return droppedDeliveries;
        }
    }

    private static final class Subscription<T extends EventBus.TurboismEvent> {
        private final long sequence;
        private final PluginEventOwnerKey owner;
        private final Class<T> type;
        private final EventPriority priority;
        private final int entrypointOrdinal;
        private final int methodOrdinal;
        private final boolean deliverWhileEnabling;
        private final Consumer<T> listener;
        private volatile boolean active = true;

        private Subscription(
            final long sequence,
            final PluginEventOwnerKey owner,
            final Class<T> type,
            final EventPriority priority,
            final int entrypointOrdinal,
            final int methodOrdinal,
            final boolean deliverWhileEnabling,
            final Consumer<T> listener
        ) {
            this.sequence = sequence;
            this.owner = owner;
            this.type = type;
            this.priority = priority;
            this.entrypointOrdinal = entrypointOrdinal;
            this.methodOrdinal = methodOrdinal;
            this.deliverWhileEnabling = deliverWhileEnabling;
            this.listener = listener;
        }

        private long sequence() {
            return sequence;
        }

        private PluginEventOwnerKey owner() {
            return owner;
        }

        private Class<T> type() {
            return type;
        }

        private EventPriority priority() {
            return priority;
        }

        private int entrypointOrdinal() {
            return entrypointOrdinal;
        }

        private int methodOrdinal() {
            return methodOrdinal;
        }

        private boolean active() {
            return active;
        }

        private boolean accepts(final OwnerLifecycle lifecycle) {
            return lifecycle == OwnerLifecycle.ACTIVE
                || (deliverWhileEnabling
                    && (lifecycle == OwnerLifecycle.ENABLING
                        || lifecycle == OwnerLifecycle.INITIALIZING));
        }

        private void deactivate() {
            active = false;
        }

        private void deliverDispatched(final EventBus.TurboismEvent event) {
            if (type.isInstance(event)) {
                listener.accept(type.cast(event));
            }
        }
    }

    private record Delivery(
        EventBus.TurboismEvent event,
        Subscription<? extends EventBus.TurboismEvent> subscription
    ) {
        private void deliver(final PluginEventOwnerKey owner) {
            if (subscription.owner().equals(owner)) {
                subscription.deliverDispatched(event);
            }
        }
    }
}
