package dev.turboism.performance;

import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.performance.PerformanceProbeService;
import dev.turboism.sdk.performance.PerformanceSnapshot;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.Registration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Plugin-owned admission and subscription boundary over a performance sampler. */
public final class PermissionCheckedPerformanceProbeService
    implements PerformanceProbeService, AutoCloseable {

    private final PerformanceProbeService delegate;
    private final PermissionChecker permissionChecker;
    private final AutoCloseable ownedDelegate;
    private final Object lifecycle = new Object();
    private final List<OwnedRegistration> registrations = new ArrayList<>();
    private volatile boolean closed;
    private boolean delegateClosed;

    public PermissionCheckedPerformanceProbeService(
        final PerformanceProbeService delegate,
        final PermissionChecker permissionChecker
    ) {
        this(delegate, permissionChecker, null);
    }

    private PermissionCheckedPerformanceProbeService(
        final PerformanceProbeService delegate,
        final PermissionChecker permissionChecker,
        final AutoCloseable ownedDelegate
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.permissionChecker = Objects.requireNonNull(permissionChecker, "permissionChecker");
        this.ownedDelegate = ownedDelegate;
    }

    /**
     * Binds a service and every registration it admits to one plugin scope.
     *
     * @param delegate session-shared or plugin-owned sampler
     * @param permissionChecker plugin permission boundary
     * @param scope scope that invalidates this service and cancels its registrations
     * @param ownedDelegate sampler to close with the plugin, or null for a shared sampler
     * @return the scope-owned wrapper
     * @throws IllegalStateException when the scope has already closed; owned resources are released
     */
    public static PermissionCheckedPerformanceProbeService bind(
        final PerformanceProbeService delegate,
        final PermissionChecker permissionChecker,
        final DisposableScope scope,
        final AutoCloseable ownedDelegate
    ) {
        Objects.requireNonNull(scope, "scope");
        final PermissionCheckedPerformanceProbeService service =
            new PermissionCheckedPerformanceProbeService(delegate, permissionChecker, ownedDelegate);
        try {
            scope.register(service);
            return service;
        } catch (RuntimeException | Error failure) {
            try { service.close(); }
            catch (RuntimeException | Error cleanup) {
                if (cleanup != failure) failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    @Override
    public PerformanceSnapshot snapshot() {
        checkPermission();
        requireOpen();
        return delegate.snapshot();
    }

    @Override
    public Registration sample(
        final Duration interval,
        final Consumer<PerformanceSnapshot> consumer
    ) {
        checkPermission();
        Objects.requireNonNull(interval, "interval");
        final OwnedRegistration registration = new OwnedRegistration(
            Objects.requireNonNull(consumer, "consumer")
        );
        synchronized (lifecycle) {
            requireOpen();
            registrations.add(registration);
        }
        try {
            // A delegate may block or re-enter scope disposal: never call it under our monitor.
            registration.bind(Objects.requireNonNull(
                delegate.sample(interval, registration::deliver), "sampling registration"
            ));
            requireOpen();
            return registration;
        } catch (RuntimeException | Error failure) {
            try { registration.close(); }
            catch (RuntimeException | Error cleanup) {
                if (cleanup != failure) failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    /** Cancels future callbacks; a callback admitted before closing may finish without blocking disposal. */
    @Override public void close() {
        final List<OwnedRegistration> pending;
        synchronized (lifecycle) {
            closed = true;
            pending = List.copyOf(registrations);
        }
        Throwable failure = null;
        for (OwnedRegistration registration : pending) {
            try { registration.close(); }
            catch (Throwable cleanup) { failure = append(failure, cleanup); }
        }
        synchronized (lifecycle) {
            if (ownedDelegate != null && !delegateClosed) {
                try {
                    ownedDelegate.close();
                    delegateClosed = true;
                } catch (Throwable cleanup) {
                    failure = append(failure, cleanup);
                }
            }
        }
        if (failure instanceof Error fatal) throw fatal;
        if (failure != null) throw new IllegalStateException("Performance scope cleanup failed", failure);
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("Plugin performance scope is closed");
    }

    private void checkPermission() {
        permissionChecker.check(PermissionIds.TURBOISM_PERFORMANCE_STATS_READ, "performance.stats.read");
    }

    private static Throwable append(final Throwable first, final Throwable next) {
        if (first == null) return next;
        if (first == next) return first;
        if (next instanceof Error && !(first instanceof Error)) {
            next.addSuppressed(first);
            return next;
        }
        first.addSuppressed(next);
        return first;
    }

    private final class OwnedRegistration implements Registration {
        private Consumer<PerformanceSnapshot> consumer;
        private Registration handle;
        private boolean cancelled;

        private OwnedRegistration(final Consumer<PerformanceSnapshot> consumer) {
            this.consumer = consumer;
        }

        private void bind(final Registration value) {
            synchronized (this) {
                if (!cancelled) {
                    handle = value;
                    return;
                }
            }
            // Disposal may have completed before the delegate returned its handle.
            value.close();
        }

        private void deliver(final PerformanceSnapshot snapshot) {
            final Consumer<PerformanceSnapshot> admitted;
            synchronized (this) {
                admitted = cancelled || closed ? null : consumer;
            }
            if (admitted != null) admitted.accept(snapshot);
        }

        @Override public void close() {
            final Registration toClose;
            synchronized (this) {
                if (cancelled) return;
                cancelled = true;
                consumer = null;
                toClose = handle;
                handle = null;
            }
            synchronized (lifecycle) { registrations.remove(this); }
            if (toClose != null) toClose.close();
        }
    }
}
