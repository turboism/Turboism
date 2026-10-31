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
    private java.util.function.BooleanSupplier capability = () -> true;
    private Registration discoverySubscription = () -> {};
    private Runnable ownedShutdown = () -> {};

    public RuntimeFrameworkUpdates(final RuntimeUpdateService discovery,
            final FrameworkInstallationReceiptLoader receipt, final FrameworkUpdateDownloader downloader,
            final FrameworkUpdateCoordinator coordinator, final Executor io) {
        this(coordinator, io, () -> {
            final var request = discovery.incrementalRequest(receipt);
            return () -> request.downloadWith(coordinator, downloader);
        });
        capability = () -> discovery.incrementalCandidate().isPresent();
        discoverySubscription = discovery.subscribe(s -> publish(state));
    }

    RuntimeFrameworkUpdates(final FrameworkUpdateCoordinator coordinator, final Executor io, final Admission admission) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.io = Objects.requireNonNull(io, "io");
        this.admission = Objects.requireNonNull(admission, "admission");
        unsubscribe = coordinator.subscribe(s -> publish(convert(s)));
    }

    @Override public Snapshot snapshot() {
        final Snapshot current = state;
        return current.phase() == Phase.IDLE && !capability.getAsBoolean()
                ? new Snapshot(Phase.UNAVAILABLE, "", 0, 0, "") : current;
    }

    /** Production startup seam: absence of release activation or dedicated keys leaves installation unavailable. */
    public static CoreFrameworkUpdates createConfigured(final java.nio.file.Path home,
            final RuntimeUpdateService discovery, final Consumer<String> diagnostic) {
        java.util.concurrent.ExecutorService worker = null;
        java.util.concurrent.ScheduledExecutorService timer = null;
        try {
            final var policy = FrameworkUpdateClientPolicy.bundled();
            if (!policy.enabled()) return CoreFrameworkUpdates.unavailable();
            final var keys = FrameworkUpdateTrust.bundled().activeKeys();
            if (keys.isEmpty()) throw new java.io.IOException("Framework signing authority is not provisioned");
            final var info = dev.turboism.core.FrameworkBuildInfo.current();
            final java.nio.file.Path root = home.toAbsolutePath().normalize();
            final java.nio.file.Path agent = FrameworkRuntimeArtifact.resolve();
            if (!root.resolve("turboism-agent.jar").equals(agent) || info.dirty()
                    || !info.buildKind().equals("ci") || info.buildNumber().isEmpty()) {
                throw new java.io.IOException("Numbered source-bound standard installation required");
            }
            final java.nio.file.Path area = java.nio.file.Path.of(System.getProperty("user.home"),
                    ".cache", "turboism", "self-update").toAbsolutePath().normalize();
            if (area.startsWith(root) || root.startsWith(area)) {
                throw new java.io.IOException("Update cache must be outside the installation");
            }
            java.nio.file.Files.createDirectories(area);
            worker = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                final Thread thread = new Thread(r, "turboism-framework-update");
                thread.setDaemon(true);
                return thread;
            });
            timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                final Thread thread = new Thread(r, "turboism-framework-update-timeout");
                thread.setDaemon(true);
                return thread;
            });
            final var verifier = new UpdateManifestVerifier(keys);
            final var downloader = new FrameworkUpdateDownloader(area.resolve("resources"), policy.source(),
                    verifier, worker, timer);
            final var coordinator = new FrameworkUpdateCoordinator(root, area.resolve("stages"),
                    FrameworkUpdateHealth.workDirectory(root), agent,
                    java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java"), worker,
                    null, () -> ProcessHandle.current().pid());
            final var result = new RuntimeFrameworkUpdates(discovery,
                    new FrameworkInstallationReceiptLoader(root, verifier), downloader, coordinator, worker);
            final var ownedWorker = worker;
            final var ownedTimer = timer;
            result.ownedShutdown = () -> { ownedWorker.shutdownNow(); ownedTimer.shutdownNow(); };
            return result;
        } catch (Exception unavailable) {
            if (worker != null) worker.shutdownNow();
            if (timer != null) timer.shutdownNow();
            diagnostic.accept("FRAMEWORK_INSTALL_UNAVAILABLE: " + unavailable.getClass().getSimpleName());
            return CoreFrameworkUpdates.unavailable();
        }
    }

    @Override public CompletableFuture<Snapshot> download() {
        final long token;
        final CompletableFuture<Snapshot> result;
        synchronized (gate) {
            if (closed || state.phase() == Phase.APPLY_PENDING || state.phase() == Phase.STAGED
                    || !capability.getAsBoolean()) {
                return CompletableFuture.completedFuture(snapshot());
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
        listener.accept(snapshot());
        return () -> listeners.remove(listener);
    }

    private void publish(final Snapshot next) {
        state = next;
        for (final Consumer<Snapshot> listener : listeners) {
            try { listener.accept(snapshot()); } catch (RuntimeException ignored) { /* UI must not break the transfer. */ }
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
        discoverySubscription.close();
        try { coordinator.close(); } finally { ownedShutdown.run(); }
        listeners.clear();
    }
}
