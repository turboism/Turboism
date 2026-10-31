package dev.turboism.update;

import dev.turboism.internal.core.CoreFrameworkUpdates;
import dev.turboism.sdk.plugin.Registration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Asynchronous product-to-UI adapter. Receipt loading and all admission IO stay off the caller's thread. */
public final class RuntimeFrameworkUpdates implements CoreFrameworkUpdates, AutoCloseable {
    @FunctionalInterface interface PreparedDownload {
        CompletableFuture<FrameworkUpdateCoordinator.Status> start();
    }
    @FunctionalInterface interface Admission {
        PreparedDownload prepare() throws Exception;
    }

    private final FrameworkUpdateCoordinator coordinator;
    private final Executor io;
    private final Admission admission;
    private final Runnable unsubscribe;
    private final Object gate = new Object();
    private final CopyOnWriteArrayList<Consumer<Snapshot>> listeners = new CopyOnWriteArrayList<>();
    private volatile Snapshot state = new Snapshot(Phase.IDLE, "", 0, 0, "");
    private CompletableFuture<Snapshot> pending;
    private long generation;
    private boolean closed;

    public RuntimeFrameworkUpdates(final RuntimeUpdateService discovery,
            final FrameworkInstallationReceiptLoader receipt, final FrameworkUpdateDownloader downloader,
            final FrameworkUpdateCoordinator coordinator, final Executor io) {
        this(coordinator, io, () -> {
            final var request = discovery.incrementalRequest(receipt);
            return () -> request.downloadWith(coordinator, downloader);
        });
    }

    RuntimeFrameworkUpdates(final FrameworkUpdateCoordinator coordinator, final Executor io, final Admission admission) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.io = Objects.requireNonNull(io, "io");
        this.admission = Objects.requireNonNull(admission, "admission");
        unsubscribe = coordinator.subscribe(s -> publish(convert(s)));
    }

    @Override public Snapshot snapshot() { return state; }

    @Override public CompletableFuture<Snapshot> download() {
        final long token;
        final CompletableFuture<Snapshot> result;
        synchronized (gate) {
            if (closed || state.phase() == Phase.APPLY_PENDING || state.phase() == Phase.STAGED) {
                return CompletableFuture.completedFuture(state);
            }
            if (pending != null && !pending.isDone()) return pending;
            token = ++generation;
            result = new CompletableFuture<>();
            pending = result;
            publish(new Snapshot(Phase.DOWNLOADING, "", 0, 0, ""));
        }
        try {
            io.execute(() -> {
                try {
                    // May hash the complete installed payload. Never hold gate while doing that IO.
                    final PreparedDownload prepared = admission.prepare();
                    final CompletableFuture<FrameworkUpdateCoordinator.Status> transfer;
                    synchronized (gate) {
                        if (closed || token != generation) {
                            result.complete(state);
                            return;
                        }
                        transfer = prepared.start();
                    }
                    transfer.whenComplete((status, failure) -> finish(token, result, failure));
                } catch (Exception failure) {
                    finish(token, result, failure);
                }
            });
        } catch (RuntimeException rejected) {
            finish(token, result, rejected);
        }
        return result;
    }

    private void finish(final long token, final CompletableFuture<Snapshot> result, final Throwable failure) {
        synchronized (gate) {
            if (!closed && token == generation && failure != null) {
                publish(new Snapshot(Phase.FAILED, state.version(), 0, 0, failure.getClass().getSimpleName()));
            }
            result.complete(state);
        }
    }

    @Override public void cancel() {
        synchronized (gate) {
            if (closed || state.phase() != Phase.DOWNLOADING) return;
            ++generation;
            coordinator.cancel();
            publish(new Snapshot(Phase.CANCELLED, state.version(), 0, 0, ""));
            if (pending != null) pending.complete(state);
        }
    }

    @Override public CompletableFuture<Snapshot> installOnExit() {
        synchronized (gate) {
            if (closed || state.phase() != Phase.STAGED) return CompletableFuture.completedFuture(state);
        }
        return coordinator.applyOnExit().thenApply(RuntimeFrameworkUpdates::convert);
    }

    @Override public Registration subscribe(final Consumer<Snapshot> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
        listener.accept(state);
        return () -> listeners.remove(listener);
    }

    private void publish(final Snapshot next) {
        state = next;
        for (final Consumer<Snapshot> listener : listeners) {
            try { listener.accept(next); } catch (RuntimeException ignored) { /* UI must not break the transfer. */ }
        }
    }

    private static Snapshot convert(final FrameworkUpdateCoordinator.Status status) {
        return new Snapshot(Phase.valueOf(status.state().name()), status.version(),
                status.bytesVerified(), status.bytesTotal(), status.message().orElse(""));
    }

    @Override public void close() {
        cancel();
        synchronized (gate) { closed = true; ++generation; }
        unsubscribe.run();
        coordinator.close();
        listeners.clear();
    }
}
