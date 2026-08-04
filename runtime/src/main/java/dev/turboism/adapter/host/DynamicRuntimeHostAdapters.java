package dev.turboism.adapter.host;

import dev.turboism.adapter.RuntimeHostAdapters;
import dev.turboism.adapter.cubism.ProjectWorkspaceAdapter;
import dev.turboism.adapter.ui.StatusToolbarAdapter;
import dev.turboism.adapter.ui.UiSurfaceAdapter;
import dev.turboism.sdk.plugin.Registration;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Stable adapter bundle whose calls are leased to one current host-session bundle. */
final class DynamicRuntimeHostAdapters {

    private final Object callGate = new Object();
    private final ThreadLocal<Integer> adapterCallDepth = ThreadLocal.withInitial(() -> 0);
    private final Set<TrackedRegistration> registrations = ConcurrentHashMap.newKeySet();
    private final RuntimeHostAdapters safeMode = RuntimeHostAdapters.safeMode();
    private final RuntimeHostAdapters view = createView();

    private RuntimeHostAdapters current = safeMode;
    private boolean acceptingCalls;
    private int inFlight;
    private Runnable onOutermostAdapterCallComplete = () -> { };

    RuntimeHostAdapters view() {
        return view;
    }

    void onOutermostAdapterCallComplete(final Runnable callback) {
        synchronized (callGate) {
            onOutermostAdapterCallComplete = Objects.requireNonNull(callback, "callback");
        }
    }

    void connect(final RuntimeHostAdapters adapters) {
        synchronized (callGate) {
            current = Objects.requireNonNull(adapters, "adapters");
            acceptingCalls = true;
        }
    }

    /**
     * Atomically stops issuing leases to the old delegate, waits for every issued lease to finish,
     * then closes all registrations returned by those calls. No external callback runs while the
     * call gate is held.
     */
    void deactivate() throws Exception {
        final Set<TrackedRegistration> detached;
        boolean interrupted = false;
        synchronized (callGate) {
            current = safeMode;
            acceptingCalls = false;
            while (inFlight != 0) {
                try {
                    callGate.wait();
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
            detached = Set.copyOf(registrations);
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }

        Throwable first = null;
        for (TrackedRegistration registration : detached) {
            try {
                registration.closeFromSession();
            } catch (Throwable throwable) {
                if (first == null) {
                    first = throwable;
                } else {
                    first.addSuppressed(throwable);
                }
            }
        }
        rethrow(first);
    }

    private RuntimeHostAdapters createView() {
        return new RuntimeHostAdapters(
            () -> call(adapters -> adapters.themeStatus().themeStatus()),
            () -> call(adapters -> adapters.renderStatus().renderStatus()),
            new ProjectWorkspaceAdapter() {
                @Override
                public AdapterResult<java.util.Optional<dev.turboism.sdk.cubism.ProjectSnapshot>> activeProject() {
                    return call(adapters -> adapters.projectWorkspace().activeProject());
                }

                @Override
                public AdapterResult<java.util.Optional<dev.turboism.sdk.cubism.DocumentSnapshot>> activeDocument() {
                    return call(adapters -> adapters.projectWorkspace().activeDocument());
                }

                @Override
                public AdapterResult<java.util.Optional<dev.turboism.sdk.cubism.WorkspaceSnapshot>> workspace() {
                    return call(adapters -> adapters.projectWorkspace().workspace());
                }

                @Override
                public AdapterResult<dev.turboism.sdk.hostread.ProjectWorkspaceSnapshot>
                    projectWorkspaceSnapshot() {
                    return call(adapters -> adapters.projectWorkspace().projectWorkspaceSnapshot());
                }
            },
            () -> call(adapters -> adapters.clipMaskRead().clipMasks()),
            new StatusToolbarAdapter() {
                @Override
                public AdapterResult<Registration> notifyStatus(
                    final dev.turboism.sdk.ui.StatusNotification notification
                ) {
                    return call(adapters -> track(adapters.statusToolbar().notifyStatus(notification)));
                }
            },
            new UiSurfaceAdapter() {
                @Override
                public AdapterResult<Registration> openDialog(
                    final dev.turboism.sdk.ui.DialogRequest request
                ) {
                    return call(adapters -> track(adapters.uiSurface().openDialog(request)));
                }

                @Override
                public AdapterResult<Boolean> confirmDialog(
                    final dev.turboism.sdk.ui.DialogRequest request
                ) {
                    return call(adapters -> adapters.uiSurface().confirmDialog(request));
                }

                @Override
                public AdapterResult<java.util.Optional<String>> requestFile(
                    final dev.turboism.sdk.ui.FileChooserRequest request
                ) {
                    return call(adapters -> adapters.uiSurface().requestFile(request));
                }
            }
        );
    }

    private <T> T call(final Function<RuntimeHostAdapters, T> operation) {
        final CallLease lease = acquireLease();
        adapterCallDepth.set(adapterCallDepth.get() + 1);
        T result = null;
        Throwable primary = null;
        try {
            result = operation.apply(lease.adapters());
        } catch (Throwable throwable) {
            primary = throwable;
        }

        releaseLease(lease);
        final int remainingDepth = adapterCallDepth.get() - 1;
        final boolean outermost = remainingDepth == 0;
        if (outermost) {
            adapterCallDepth.remove();
        } else {
            adapterCallDepth.set(remainingDepth);
        }

        Throwable deferredCleanupFailure = null;
        if (outermost && lease.counted()) {
            final Runnable callback;
            synchronized (callGate) {
                callback = onOutermostAdapterCallComplete;
            }
            try {
                callback.run();
            } catch (Throwable throwable) {
                deferredCleanupFailure = throwable;
            }
        }

        if (primary != null) {
            if (deferredCleanupFailure != null && deferredCleanupFailure != primary) {
                primary.addSuppressed(deferredCleanupFailure);
            }
            return rethrowCallFailure(primary);
        }
        if (deferredCleanupFailure != null) {
            return rethrowCallFailure(deferredCleanupFailure);
        }
        return result;
    }

    private static <T> T rethrowCallFailure(final Throwable throwable) {
        DynamicRuntimeHostAdapters.<RuntimeException>sneakyThrow(throwable);
        throw new AssertionError("unreachable");
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(final Throwable throwable) throws T {
        throw (T) throwable;
    }

    private CallLease acquireLease() {
        synchronized (callGate) {
            if (!acceptingCalls) {
                return new CallLease(safeMode, false);
            }
            inFlight++;
            return new CallLease(current, true);
        }
    }

    private void releaseLease(final CallLease lease) {
        if (!lease.counted()) {
            return;
        }
        synchronized (callGate) {
            inFlight--;
            if (inFlight == 0) {
                callGate.notifyAll();
            }
        }
    }

    boolean isInAdapterCallOnCurrentThread() {
        return adapterCallDepth.get() > 0;
    }

    private StatusToolbarAdapter.AdapterResult<Registration> track(
        final StatusToolbarAdapter.AdapterResult<Registration> result
    ) {
        if (!result.isAvailable()) {
            return result;
        }
        return StatusToolbarAdapter.AdapterResult.available(track(result.value().orElseThrow()));
    }

    private UiSurfaceAdapter.AdapterResult<Registration> track(
        final UiSurfaceAdapter.AdapterResult<Registration> result
    ) {
        if (!result.isAvailable()) {
            return result;
        }
        return UiSurfaceAdapter.AdapterResult.available(track(result.value().orElseThrow()));
    }

    private Registration track(final Registration registration) {
        final TrackedRegistration tracked = new TrackedRegistration(
            Objects.requireNonNull(registration, "registration")
        );
        registrations.add(tracked);
        return tracked;
    }

    int trackedRegistrationCountForTest() {
        return registrations.size();
    }

    private final class TrackedRegistration implements Registration {
        private final Registration delegate;
        private CloseState state = CloseState.OPEN;
        private CompletableFuture<Void> inFlightClose;
        private Thread closeOwner;

        private TrackedRegistration(final Registration delegate) {
            this.delegate = delegate;
        }

        @Override
        public void close() {
            closeAttempt();
        }

        private void closeFromSession() {
            closeAttempt();
        }

        private void closeAttempt() {
            final CloseAttempt attempt = beginClose();
            if (attempt.owner()) {
                executeClose(attempt.completion());
                return;
            }
            await(attempt.completion());
        }

        private synchronized CloseAttempt beginClose() {
            if (state == CloseState.CLOSED) {
                return new CloseAttempt(false, CompletableFuture.completedFuture(null));
            }
            if (state == CloseState.CLOSING) {
                if (closeOwner == Thread.currentThread()) {
                    return new CloseAttempt(false, CompletableFuture.completedFuture(null));
                }
                return new CloseAttempt(false, inFlightClose);
            }
            state = CloseState.CLOSING;
            closeOwner = Thread.currentThread();
            inFlightClose = new CompletableFuture<>();
            return new CloseAttempt(true, inFlightClose);
        }

        private void executeClose(final CompletableFuture<Void> completion) {
            try {
                delegate.close();
                synchronized (this) {
                    state = CloseState.CLOSED;
                    closeOwner = null;
                    inFlightClose = null;
                }
                registrations.remove(this);
                completion.complete(null);
            } catch (Throwable throwable) {
                synchronized (this) {
                    state = CloseState.OPEN;
                    closeOwner = null;
                    inFlightClose = null;
                }
                completion.completeExceptionally(throwable);
                rethrowUnchecked(throwable);
            }
        }
    }

    private static void await(final CompletableFuture<Void> completion) {
        try {
            completion.join();
        } catch (CompletionException exception) {
            rethrowUnchecked(exception.getCause());
        }
    }

    private static void rethrow(final Throwable throwable) throws Exception {
        if (throwable == null) {
            return;
        }
        if (throwable instanceof Error error) {
            throw error;
        }
        if (throwable instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (throwable instanceof Exception exception) {
            throw exception;
        }
        throw new RuntimeException(throwable);
    }

    private static void rethrowUnchecked(final Throwable throwable) {
        if (throwable instanceof Error error) {
            throw error;
        }
        if (throwable instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        throw new CompletionException(throwable);
    }

    private enum CloseState {
        OPEN,
        CLOSING,
        CLOSED
    }

    private record CallLease(RuntimeHostAdapters adapters, boolean counted) {
    }

    private record CloseAttempt(boolean owner, CompletableFuture<Void> completion) {
    }
}
