package dev.turboism.core.runtime.psd;

import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.cubism.psd.PsdEditFile;
import dev.turboism.sdk.cubism.psd.PsdFileOperationResult;
import dev.turboism.sdk.cubism.psd.PsdFileRevision;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.task.PluginCompletionFuture;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Runtime-owned {@link PsdEditFile} handle for one exported raw image.
 *
 * <p>Every operation re-validates plugin ownership, the model-session binding and current
 * permissions through the owning registry and permission checker; the handle exposes no path,
 * command or byte access. Opening hands the already validated allocation to the operating system's
 * default application, subscriptions publish runtime-issued staged revisions, and {@link #stop()}
 * revokes admission without deleting any file or closing the external application.</p>
 *
 * <p>This class does not implement the filesystem watcher: the session coordinator observes the
 * allocation and calls {@link #publishStableSave(PsdStableSnapshot.Snapshot)} with a staged,
 * digest-verified snapshot on its own bounded worker lane.</p>
 */
final class RuntimePsdEditFile implements PsdEditFile {
    static final int MAX_SUBSCRIPTIONS = 8;

    private static final String OPEN_OPERATION = "model.textures.psdFile.openInDefaultApplication";
    private static final String OBSERVE_OPERATION = "model.textures.psdFile.observeSaves";
    private static final String PUBLISH_OPERATION = "model.textures.psdFile.publishStableSave";

    private final String pluginId;
    private final PsdEditRegistry.Binding binding;
    private final PsdTemporaryFile allocation;
    private final PsdEditRegistry registry;
    private final PermissionChecker permissionChecker;
    private final BooleanSupplier activeScope;
    private final Consumer<Runnable> continuationDispatcher;
    private final Executor executor;
    private final PsdDefaultApplicationLauncher launcher;
    private final PsdSaveWatcher watcher;

    private final Object stateLock = new Object();
    private final IdentityHashMap<Registration, Consumer<PsdFileRevision>> subscriptions = new IdentityHashMap<>();
    private final ArrayDeque<PsdFileRevision> issuedRevisions = new ArrayDeque<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final CompletableFuture<Void> drained = new CompletableFuture<>();
    private boolean stopped;

    RuntimePsdEditFile(
            final String pluginId,
            final PsdEditRegistry.Binding binding,
            final PsdTemporaryFile allocation,
            final PsdEditRegistry registry,
            final PermissionChecker permissionChecker,
            final BooleanSupplier activeScope,
            final Consumer<Runnable> continuationDispatcher,
            final Executor executor,
            final PsdDefaultApplicationLauncher launcher,
            final PsdSaveWatcher.Scheduler scheduler,
            final LongSupplier nanoClock,
            final String baselineDigest) {
        this.pluginId = requireText(pluginId, "pluginId");
        this.binding = Objects.requireNonNull(binding, "binding");
        this.allocation = Objects.requireNonNull(allocation, "allocation");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.permissionChecker = Objects.requireNonNull(permissionChecker, "permissionChecker");
        this.activeScope = Objects.requireNonNull(activeScope, "activeScope");
        this.continuationDispatcher = Objects.requireNonNull(continuationDispatcher, "continuationDispatcher");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.launcher = Objects.requireNonNull(launcher, "launcher");
        this.watcher = new PsdSaveWatcher(
                this.allocation,
                this::publishStableSave,
                new PsdSaveDebouncer(requireDigest(baselineDigest)),
                scheduler,
                nanoClock,
                PsdSaveWatcher.DEFAULT_POLL_MILLIS,
                PsdSaveWatcher.COMPENSATION_DELAY_MILLIS);
    }

    /**
     * Records the export baseline token so a later stable save can retire the superseded stage, then
     * starts watching the allocation. The baseline digest is never reported as an external save.
     */
    void beginWatching(final PsdFileRevision baseline) {
        synchronized (stateLock) {
            issuedRevisions.addLast(Objects.requireNonNull(baseline, "baseline"));
        }
        watcher.start();
    }

    @Override
    public CompletionStage<PsdFileOperationResult> openInDefaultApplication() {
        final PluginCompletionFuture<PsdFileOperationResult> completion = completion();
        final AtomicBoolean settled = new AtomicBoolean(false);
        try {
            requireOperational();
            permissionChecker.check(PermissionIds.TURBOISM_PROCESS, OPEN_OPERATION);
            permissionChecker.check(PermissionIds.TURBOISM_FILE_READ, OPEN_OPERATION);
            final Path path = registry.requireFile(binding, this).validatedPath();
            inFlight.incrementAndGet();
            try {
                executor.execute(() -> open(completion, settled, path));
            } catch (RejectedExecutionException queueRejected) {
                endWork();
                settle(completion, settled, result(PsdFileOperationResult.Status.REJECTED, "PSD_OPEN;queue=rejected"));
            }
        } catch (CubismPermissionException denied) {
            settle(completion, settled, result(PsdFileOperationResult.Status.REJECTED, "PSD_OPEN;permission=denied"));
        } catch (IllegalStateException inactive) {
            settle(completion, settled, result(PsdFileOperationResult.Status.UNAVAILABLE, "PSD_OPEN;handle=inactive"));
        } catch (SecurityException foreign) {
            settle(
                    completion,
                    settled,
                    result(PsdFileOperationResult.Status.REJECTED, "PSD_OPEN;handle=foreign_or_revoked"));
        } catch (IOException invalidAllocation) {
            settle(completion, settled, result(PsdFileOperationResult.Status.FAILED, "PSD_OPEN;allocation=invalid"));
        }
        return completion.stage();
    }

    @Override
    public Registration observeSaves(final Consumer<PsdFileRevision> listener) {
        Objects.requireNonNull(listener, "listener");
        final Registration registration = new Subscription();
        synchronized (stateLock) {
            requireOperational();
            permissionChecker.check(PermissionIds.TURBOISM_CUBISM_MODEL_READ, OBSERVE_OPERATION);
            permissionChecker.check(PermissionIds.TURBOISM_FILE_READ, OBSERVE_OPERATION);
            permissionChecker.check(PermissionIds.TURBOISM_FILE_WRITE, OBSERVE_OPERATION);
            registry.requireFile(binding, this);
            if (subscriptions.size() >= MAX_SUBSCRIPTIONS) {
                throw new IllegalStateException("PSD subscription quota exceeded");
            }
            subscriptions.put(registration, listener);
        }
        return registration;
    }

    @Override
    public CompletionStage<PsdFileOperationResult> stop() {
        final PluginCompletionFuture<PsdFileOperationResult> completion = completion();
        final AtomicBoolean settled = new AtomicBoolean(false);
        revokeInternal();
        if (inFlight.get() == 0) {
            drained.complete(null);
        }
        drained.whenComplete((ignored, failure) -> settle(
                completion, settled, result(PsdFileOperationResult.Status.STOPPED, "PSD_STOP;inFlight=settled")));
        return completion.stage();
    }

    /** This handle's runtime session binding; never exposed through the SDK. */
    PsdEditRegistry.Binding binding() {
        return binding;
    }

    /** Runtime-owned teardown used by the issuing service; publishes no plugin result. */
    void revokeInternal() {
        registry.revoke(this);
        watcher.close();
        synchronized (stateLock) {
            stopped = true;
            subscriptions.clear();
        }
    }

    /**
     * Publishes one already staged stable save. Only the runtime coordinator calls this, on its own
     * serialized worker lane, after digest verification; a foreign or superseded request fails
     * closed instead of notifying subscribers.
     */
    void publishStableSave(final PsdStableSnapshot.Snapshot snapshot) throws IOException {
        final PsdFileRevision revision;
        synchronized (stateLock) {
            requireOperational();
            permissionChecker.check(PermissionIds.TURBOISM_CUBISM_MODEL_READ, PUBLISH_OPERATION);
            permissionChecker.check(PermissionIds.TURBOISM_FILE_READ, PUBLISH_OPERATION);
            permissionChecker.check(PermissionIds.TURBOISM_FILE_WRITE, PUBLISH_OPERATION);
            Objects.requireNonNull(snapshot, "snapshot");
            while (issuedRevisions.size() >= PsdEditRegistry.MAX_REVISIONS_PER_HANDLE) {
                registry.retireRevision(binding, this, issuedRevisions.removeFirst());
            }
            revision = registry.issueRevision(binding, this, snapshot);
            issuedRevisions.addLast(revision);
        }
        deliver(revision);
    }

    private void open(
            final PluginCompletionFuture<PsdFileOperationResult> completion,
            final AtomicBoolean settled,
            final Path path) {
        try {
            launcher.launch(path);
            settle(completion, settled, result(PsdFileOperationResult.Status.OPENED, "PSD_OPEN;launch=accepted"));
        } catch (IOException | RuntimeException launchFailure) {
            settle(completion, settled, result(PsdFileOperationResult.Status.FAILED, "PSD_OPEN;launch=failed"));
        } finally {
            endWork();
        }
    }

    private void deliver(final PsdFileRevision revision) {
        final List<Consumer<PsdFileRevision>> listeners;
        synchronized (stateLock) {
            if (stopped || subscriptions.isEmpty()) return;
            listeners = new ArrayList<>(subscriptions.values());
        }
        for (Consumer<PsdFileRevision> listener : listeners) {
            final Runnable delivery = () -> {
                try {
                    listener.accept(revision);
                } catch (RuntimeException isolated) {
                    // Documented contract: one failing consumer never blocks the other consumers or
                    // the observation lane. The stage file is retained for the OS to clean up.
                }
            };
            try {
                continuationDispatcher.accept(delivery);
            } catch (RuntimeException schedulerUnavailable) {
                // A plugin lane that is closing drops this notification. The next stable save
                // re-reads current state, so no revision is silently treated as consumed.
            }
        }
    }

    private void endWork() {
        if (inFlight.decrementAndGet() == 0 && drained.isDone() == false && stopped) {
            drained.complete(null);
        }
    }

    private void settle(
            final PluginCompletionFuture<PsdFileOperationResult> completion,
            final AtomicBoolean settled,
            final PsdFileOperationResult result) {
        if (!settled.compareAndSet(false, true)) return;
        final Runnable publication = () -> completion.settle(result);
        try {
            continuationDispatcher.accept(publication);
        } catch (RuntimeException dispatcherFailure) {
            completion.settle(result);
        }
    }

    private static String requireDigest(final String digest) {
        Objects.requireNonNull(digest, "digest");
        if (!digest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("baseline digest must be a lowercase SHA-256 digest");
        }
        return digest;
    }

    private PluginCompletionFuture<PsdFileOperationResult> completion() {
        return new PluginCompletionFuture<>(continuationDispatcher, this::scopeIsActive);
    }

    private void requireOperational() {
        if (stopped || !scopeIsActive()) {
            throw new IllegalStateException("PSD edit handle is stopped or its plugin scope is inactive");
        }
    }

    private boolean scopeIsActive() {
        try {
            return activeScope.getAsBoolean();
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private static PsdFileOperationResult result(final PsdFileOperationResult.Status status, final String diagnostic) {
        return new PsdFileOperationResult(status, diagnostic);
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    /** Removes exactly this subscription; it never revokes the handle or stops other subscribers. */
    private final class Subscription implements Registration {
        @Override
        public void close() {
            synchronized (stateLock) {
                subscriptions.remove(this);
            }
        }
    }
}
