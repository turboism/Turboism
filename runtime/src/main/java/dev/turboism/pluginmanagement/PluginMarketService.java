package dev.turboism.pluginmanagement;

import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.core.version.PluginVersion;
import dev.turboism.core.version.VersionRange;
import dev.turboism.distribution.LocalPluginJarPreparer;
import dev.turboism.distribution.PreparedPluginJar;
import dev.turboism.internal.core.CorePluginManagement.OperationResult;
import dev.turboism.internal.core.CorePluginManagement.PluginDetails;
import dev.turboism.internal.core.CorePluginManagement.PluginInfo;
import dev.turboism.pluginmanagement.PluginCatalog.CatalogPlugin;
import dev.turboism.pluginmanagement.PluginCatalog.CatalogRelease;
import dev.turboism.pluginmanagement.PluginCatalogClient.CatalogFetchResult;
import dev.turboism.pluginmanagement.PluginCatalogClient.DownloadControl;
import dev.turboism.pluginmanagement.PluginCatalogClient.DownloadFailure;
import dev.turboism.pluginmanagement.PluginCatalogClient.DownloadProgress;
import dev.turboism.pluginmanagement.PluginCatalogVerifier.PluginCatalogVerificationException;
import dev.turboism.preview.PluginHotReloadCoordinator;
import dev.turboism.update.UpdateFileSupport;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Service for browsing the verified Plugin Directory catalog v2, checking compatibility,
 * and performing verified installs and updates.
 */
public final class PluginMarketService implements AutoCloseable {

    /** Default bounded queue capacity for market executor submissions. */
    private static final int DEFAULT_QUEUE_CAPACITY = 64;

    /** Installation and compatibility status category of a catalog plugin. */
    public enum StatusType {
        NOT_INSTALLED,
        INSTALLED,
        UPDATE_AVAILABLE,
        INCOMPATIBLE
    }

    /**
     * Point-in-time market status for one plugin against the local runtime installation.
     *
     * @param type status category
     * @param installedVersion currently installed version string when present
     * @param targetRelease target release offered for installation or update
     * @param incompatibleReason human-readable incompatibility explanation when incompatible
     */
    public record PluginMarketStatus(
            StatusType type,
            Optional<String> installedVersion,
            Optional<CatalogRelease> targetRelease,
            Optional<String> incompatibleReason) {}

    /** A single local installation read shared by all rows of a market refresh. */
    public record MarketSnapshot(
            PluginCatalog catalog, List<PluginInfo> installed, Map<String, PluginMarketStatus> statuses) {
        public MarketSnapshot {
            Objects.requireNonNull(catalog, "catalog");
            installed = List.copyOf(installed);
            statuses = Map.copyOf(statuses);
        }
    }

    private static final class MarketFuture<T> extends CompletableFuture<T> {
        private volatile MarketTask<T> task;

        void setTask(final MarketTask<T> task) {
            this.task = task;
            whenComplete((ignoredResult, ignoredError) -> {
                this.task = null;
            });
        }

        @Override
        public boolean cancel(final boolean mayInterruptIfRunning) {
            if (isCancelled()) {
                return true;
            }
            final MarketTask<T> t = this.task;
            if (t != null) {
                if (!t.cancelQueued()) {
                    return false;
                }
            }
            final boolean c = super.cancel(mayInterruptIfRunning);
            if (c) {
                this.task = null;
            }
            return c;
        }
    }

    private final Path home;
    private final Path pluginsDirectory;
    private final Path stagingPackages;
    private final PluginCatalogClient client;
    private final PluginCatalogVerifier verifier;
    private final PluginCatalogStateStore stateStore;
    private final PendingPluginOperations pending;
    private final RuntimePluginManagementService managementService;
    private final Supplier<Optional<String>> cubismVersionSupplier;
    private final PluginVersion turboismApiVersion;
    private final Consumer<String> logSink;
    private final ExecutorService marketExecutor;
    private final BlockingQueue<Runnable> taskQueue;

    private final Object lifecycleLock = new Object();
    private boolean closed = false;
    private MarketTask<?> runningTask = null;

    private volatile PluginHotReloadCoordinator hotReloadCoordinator;
    private volatile PluginCatalog currentCatalog;

    public PluginMarketService(
            final Path home,
            final PluginCatalogClient client,
            final PluginCatalogVerifier verifier,
            final PluginCatalogStateStore stateStore,
            final PendingPluginOperations pending,
            final RuntimePluginManagementService managementService,
            final Supplier<Optional<String>> cubismVersionSupplier,
            final PluginVersion turboismApiVersion,
            final Consumer<String> logSink) {
        this(
                home,
                client,
                verifier,
                stateStore,
                pending,
                managementService,
                cubismVersionSupplier,
                turboismApiVersion,
                logSink,
                DEFAULT_QUEUE_CAPACITY);
    }

    PluginMarketService(
            final Path home,
            final PluginCatalogClient client,
            final PluginCatalogVerifier verifier,
            final PluginCatalogStateStore stateStore,
            final PendingPluginOperations pending,
            final RuntimePluginManagementService managementService,
            final Supplier<Optional<String>> cubismVersionSupplier,
            final PluginVersion turboismApiVersion,
            final Consumer<String> logSink,
            final int queueCapacity) {
        this.home = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
        this.pluginsDirectory = this.home.resolve("plugins");
        this.stagingPackages = this.home.resolve("state/runtime/plugin-management/packages");
        this.client = Objects.requireNonNull(client, "client");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.pending = Objects.requireNonNull(pending, "pending");
        this.managementService = Objects.requireNonNull(managementService, "managementService");
        this.cubismVersionSupplier = Objects.requireNonNull(cubismVersionSupplier, "cubismVersionSupplier");
        this.turboismApiVersion = Objects.requireNonNull(turboismApiVersion, "turboismApiVersion");
        this.logSink = Objects.requireNonNull(logSink, "logSink");
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("queueCapacity must be positive: " + queueCapacity);
        }
        this.taskQueue = new ArrayBlockingQueue<>(queueCapacity);
        this.marketExecutor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                this.taskQueue,
                r -> {
                    final Thread t = new Thread(r, "Turboism-PluginMarket");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    /** Returns the number of tasks currently waiting in the executor queue. */
    int queueSize() {
        return taskQueue.size();
    }

    /**
     * Binds the runtime hot-reload coordinator to apply compatible releases without restarting.
     *
     * @param coordinator the active hot-reload coordinator
     */
    public void bindHotReloadCoordinator(final PluginHotReloadCoordinator coordinator) {
        this.hotReloadCoordinator = coordinator;
    }

    /**
     * @return the most recently verified catalog instance, or {@code null} if none loaded yet
     */
    public PluginCatalog currentCatalog() {
        return currentCatalog;
    }

    /** Loads the catalog and resolves local state without doing filesystem work on the caller thread. */
    public CompletionStage<MarketSnapshot> loadSnapshot(final boolean forceRefresh) {
        return loadCatalog(forceRefresh).thenCompose(this::snapshot);
    }

    /** Refreshes local installation state once, then resolves every row from that immutable snapshot. */
    public CompletionStage<MarketSnapshot> snapshot(final PluginCatalog catalog) {
        Objects.requireNonNull(catalog, "catalog");
        return submitTask(() -> executeSnapshot(catalog), null);
    }

    private MarketSnapshot executeSnapshot(final PluginCatalog catalog) {
        final List<PluginInfo> installed = List.copyOf(managementService.plugins());
        final Map<String, PluginInfo> byId = new LinkedHashMap<>();
        installed.forEach(plugin -> byId.put(plugin.id(), plugin));
        final Optional<String> activeCubism = cubismVersionSupplier.get();
        final Map<String, PluginMarketStatus> statuses = new LinkedHashMap<>();
        for (CatalogPlugin plugin : catalog.plugins()) {
            statuses.put(plugin.id(), resolveStatus(plugin, byId.get(plugin.id()), activeCubism));
        }
        return new MarketSnapshot(catalog, installed, statuses);
    }

    /** Reads bundled documentation off the UI thread, only when a plugin is selected. */
    public CompletionStage<Optional<String>> loadReadme(final String pluginId) {
        Objects.requireNonNull(pluginId, "pluginId");
        return submitTask(() -> managementService.details(pluginId).flatMap(PluginDetails::readme), null);
    }

    /** Persists the desired enabled state off the UI thread. */
    public CompletionStage<OperationResult> setEnabled(final String pluginId, final boolean enabled) {
        Objects.requireNonNull(pluginId, "pluginId");
        return submitTask(() -> managementService.setEnabled(pluginId, enabled), null);
    }

    /** Stages removal off the UI thread. */
    public CompletionStage<OperationResult> uninstall(final String pluginId) {
        Objects.requireNonNull(pluginId, "pluginId");
        return submitTask(() -> managementService.uninstall(pluginId), null);
    }

    /**
     * Loads the verified v2 catalog: first validates cached state if available,
     * then attempts ETag revalidation over HTTPS. Never yields unverified data.
     */
    public CompletionStage<PluginCatalog> loadCatalog(final boolean forceRefresh) {
        return submitTask(() -> executeLoadCatalog(forceRefresh), null);
    }

    private PluginCatalog executeLoadCatalog(final boolean forceRefresh) {
        final PluginCatalogStateStore.State persisted = stateStore.read();
        if (!forceRefresh
                && persisted.cachedCatalogBytes().isPresent()
                && persisted.cachedSignatureBytes().isPresent()) {
            try {
                final PluginCatalog cached = verifier.verify(
                        persisted.cachedCatalogBytes().get(),
                        persisted.cachedSignatureBytes().get());
                currentCatalog = cached;
            } catch (PluginCatalogVerificationException e) {
                logSink.accept("Cached catalog verification failed: " + e.getMessage());
            }
        }

        final Optional<String> etag = forceRefresh ? Optional.empty() : persisted.etag();
        final CatalogFetchResult fetch;
        try {
            fetch = client.fetchCatalog(etag);
        } catch (IOException netError) {
            if (currentCatalog != null) {
                return currentCatalog;
            }
            throw new IllegalStateException("Failed to fetch plugin catalog: " + netError.getMessage(), netError);
        }

        final byte[] catalogBytes;
        final byte[] signatureBytes;
        final String newEtag;
        if (fetch instanceof CatalogFetchResult.NotModified notModified) {
            if (persisted.cachedCatalogBytes().isEmpty()
                    || persisted.cachedSignatureBytes().isEmpty()) {
                throw new IllegalStateException("Catalog 304 response with empty local cache");
            }
            catalogBytes = persisted.cachedCatalogBytes().get();
            signatureBytes = persisted.cachedSignatureBytes().get();
            newEtag = notModified.etag().orElse(persisted.etag().orElse(""));
        } else if (fetch instanceof CatalogFetchResult.Fetched fetched) {
            catalogBytes = fetched.catalogBytes();
            signatureBytes = fetched.signatureBytes();
            newEtag = fetched.etag().orElse("");
        } else {
            throw new IllegalStateException("Unexpected fetch result type");
        }

        try {
            final PluginCatalog verified =
                    verifier.verifyAndAdvance(catalogBytes, signatureBytes, stateStore, Optional.of(newEtag));
            currentCatalog = verified;
            return verified;
        } catch (PluginCatalogVerificationException verifyError) {
            if (currentCatalog != null) {
                return currentCatalog;
            }
            throw new IllegalStateException("Catalog verification failed: " + verifyError.getMessage(), verifyError);
        }
    }

    /**
     * Resolves the market status of a catalog plugin against the currently installed runtime plugins.
     */
    public PluginMarketStatus resolveStatus(final CatalogPlugin plugin) {
        final List<PluginInfo> installedList = managementService.plugins();
        final PluginInfo installed = installedList.stream()
                .filter(p -> p.id().equals(plugin.id()))
                .findFirst()
                .orElse(null);

        return resolveStatus(plugin, installed, cubismVersionSupplier.get());
    }

    private PluginMarketStatus resolveStatus(
            final CatalogPlugin plugin, final PluginInfo installed, final Optional<String> activeCubism) {
        final List<CatalogRelease> compatibleReleases = plugin.releases().stream()
                .filter(CatalogRelease::isActive)
                .filter(r -> isCompatible(r, activeCubism))
                .sorted(Comparator.comparing((CatalogRelease r) -> PluginVersion.parse(r.version()))
                        .reversed())
                .toList();

        if (compatibleReleases.isEmpty()) {
            final CatalogRelease latestActive = plugin.releases().stream()
                    .filter(CatalogRelease::isActive)
                    .max(Comparator.comparing(r -> PluginVersion.parse(r.version())))
                    .orElse(null);
            final String reason = latestActive != null
                    ? incompatibilityReason(latestActive, activeCubism)
                    : "No compatible releases available";
            return new PluginMarketStatus(
                    StatusType.INCOMPATIBLE,
                    installed != null ? Optional.of(installed.version()) : Optional.empty(),
                    Optional.ofNullable(latestActive),
                    Optional.of(reason));
        }

        final CatalogRelease bestRelease = compatibleReleases.get(0);
        if (installed != null) {
            final PluginVersion currentVer = PluginVersion.parse(installed.version());
            final PluginVersion bestVer = PluginVersion.parse(bestRelease.version());
            if (bestVer.compareTo(currentVer) > 0) {
                return new PluginMarketStatus(
                        StatusType.UPDATE_AVAILABLE,
                        Optional.of(installed.version()),
                        Optional.of(bestRelease),
                        Optional.empty());
            }
            return new PluginMarketStatus(
                    StatusType.INSTALLED, Optional.of(installed.version()), Optional.of(bestRelease), Optional.empty());
        }

        return new PluginMarketStatus(
                StatusType.NOT_INSTALLED, Optional.empty(), Optional.of(bestRelease), Optional.empty());
    }

    private boolean isCompatible(final CatalogRelease release, final Optional<String> activeCubism) {
        if (!VersionRange.parse(release.turboismApi()).contains(turboismApiVersion)) {
            return false;
        }
        if (release.requiresCubism()) {
            if (activeCubism.isEmpty()) {
                return false;
            }
            if (!release.cubismVersions().contains(activeCubism.get())) {
                return false;
            }
        }
        return true;
    }

    private String incompatibilityReason(final CatalogRelease release, final Optional<String> activeCubism) {
        if (!VersionRange.parse(release.turboismApi()).contains(turboismApiVersion)) {
            return "Requires Turboism API " + release.turboismApi() + " (current is " + turboismApiVersion + ")";
        }
        if (release.requiresCubism()) {
            if (activeCubism.isEmpty()) {
                return "Requires Cubism host";
            }
            if (!release.cubismVersions().contains(activeCubism.get())) {
                return "Cubism " + activeCubism.get() + " is not in supported versions";
            }
        }
        return "Release is incompatible";
    }

    /**
     * Downloads, strictly verifies, and installs or updates a catalog release.
     * Uses hot-load/hot-reload where safe, and falls back to staging otherwise.
     */
    public CompletionStage<OperationResult> installOrUpdate(final CatalogPlugin plugin, final CatalogRelease release) {
        return installOrUpdate(plugin, release, new DownloadControl(), ignored -> {});
    }

    /** Installs with download progress and cancellation admitted until the file/journal commit begins. */
    public CompletionStage<OperationResult> installOrUpdate(
            final CatalogPlugin plugin,
            final CatalogRelease release,
            final DownloadControl control,
            final Consumer<DownloadProgress> progress) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(release, "release");
        Objects.requireNonNull(control, "control");
        Objects.requireNonNull(progress, "progress");

        return submitTask(() -> executeInstallOrUpdate(plugin, release, control, progress), control);
    }

    private OperationResult executeInstallOrUpdate(
            final CatalogPlugin plugin,
            final CatalogRelease release,
            final DownloadControl control,
            final Consumer<DownloadProgress> progress) {
        if (control.isCancelled()) {
            return cancelledInstall();
        }
        final Optional<String> activeCubism = cubismVersionSupplier.get();
        if (!isCompatible(release, activeCubism)) {
            return OperationResult.rejected("PLUGIN_INCOMPATIBLE", incompatibilityReason(release, activeCubism));
        }

        final Path tempDownload;
        try {
            Files.createDirectories(stagingPackages);
            tempDownload = Files.createTempFile(stagingPackages, "market-download-", ".jar");
        } catch (IOException e) {
            logSink.accept("PLUGIN_DOWNLOAD_IO_FAILED: " + e.getMessage());
            return OperationResult.rejected(
                    "PLUGIN_DOWNLOAD_IO_FAILED", "Cannot create plugin download file: " + e.getMessage());
        }

        try {
            client.downloadArtifact(
                    URI.create(release.artifact().url()),
                    release.artifact().size(),
                    release.artifact().sha256(),
                    tempDownload,
                    control,
                    progress);
        } catch (IOException downloadError) {
            try {
                Files.deleteIfExists(tempDownload);
            } catch (IOException ignored) {
            }
            final String code =
                    downloadError instanceof DownloadFailure failure ? failure.code() : "PLUGIN_DOWNLOAD_FAILED";
            logSink.accept(code + ": " + downloadError.getMessage());
            return OperationResult.rejected(code, "Failed to download plugin package: " + downloadError.getMessage());
        }

        // Inspect and prepare staged JAR
        final LocalPluginJarPreparer preparer = new LocalPluginJarPreparer();
        final LocalPluginJarPreparer.Preparation prep = preparer.prepare(tempDownload, stagingPackages);
        try {
            Files.deleteIfExists(tempDownload);
        } catch (IOException ignored) {
        }

        if (prep instanceof LocalPluginJarPreparer.PreparationRejected rejected) {
            return OperationResult.rejected(rejected.code(), "Staged package rejected by security preflight");
        }

        final PreparedPluginJar prepared = ((LocalPluginJarPreparer.Prepared) prep).value();
        if (control.isCancelled()) {
            pending.discardIfUnreferenced(prepared.stagedJar());
            return cancelledInstall();
        }
        if (!prepared.descriptorSha256().equalsIgnoreCase(release.artifact().descriptorSha256())) {
            pending.discardIfUnreferenced(prepared.stagedJar());
            return OperationResult.rejected(
                    "DESCRIPTOR_SHA256_MISMATCH", "Embedded descriptor SHA-256 does not match catalog pin");
        }

        final boolean hotSafe = release.hotReload()
                && hotReloadCoordinator != null
                && pending.canHotApply(plugin.id())
                && hotReloadCoordinator.isHotReloadSafe(prepared.stagedJar());

        // Commit gate: begins commit on DownloadControl
        if (Thread.currentThread().isInterrupted() || !control.beginCommit()) {
            pending.discardIfUnreferenced(prepared.stagedJar());
            return cancelledInstall();
        }

        if (hotSafe) {
            final Path targetJar = pluginsDirectory.resolve(plugin.id() + ".jar");
            final boolean wasInstalled = Files.isRegularFile(targetJar, LinkOption.NOFOLLOW_LINKS);
            try {
                Files.createDirectories(pluginsDirectory);
                final PluginHotReloadCoordinator.HotReloadResult result;
                if (wasInstalled) {
                    result = hotReloadCoordinator.hotReplace(plugin.id(), prepared.stagedJar(), targetJar);
                } else {
                    UpdateFileSupport.copyAtomic(prepared.stagedJar(), targetJar);
                    result = hotReloadCoordinator.hotLoad(targetJar);
                    if (!result.succeeded()) Files.deleteIfExists(targetJar);
                }
                if (result.succeeded()) {
                    pending.completeHotApply(prepared);
                    return OperationResult.accepted("PLUGIN_HOT_APPLIED", "Plugin installed and applied immediately.");
                }
                if (result.rolledBack() && "HOT_RELOAD_FAILED".equals(result.code())) {
                    pending.completeHotApply(prepared);
                    return OperationResult.rejected(
                            "PLUGIN_HOT_UPDATE_ROLLED_BACK",
                            "Plugin update failed to start; previous version restored.");
                }
                logSink.accept("Hot lifecycle operation deferred: " + result.code() + ": " + result.message());
            } catch (IOException | RuntimeException failure) {
                logSink.accept("Hot lifecycle operation failed; falling back to staging: " + failure.getMessage());
            }
            return stageForRestart(prepared);
        } else {
            return stageForRestart(prepared);
        }
    }

    private static OperationResult cancelledInstall() {
        return OperationResult.rejected("PLUGIN_DOWNLOAD_CANCELLED", "Plugin installation cancelled before commit.");
    }

    private OperationResult stageForRestart(final PreparedPluginJar prepared) {
        final var staged = pending.stage(prepared);
        if (staged.accepted()) {
            return OperationResult.accepted(
                    staged.code(), "Plugin installation is pending; restart Cubism to apply it.");
        }
        return OperationResult.rejected(staged.code(), "Failed to stage plugin package");
    }

    /**
     * Resolves localized name and summary for the specified catalog plugin using the given display locale.
     */
    public static CatalogPluginLocalized localizePlugin(final CatalogPlugin plugin, final Locale locale) {
        final Map<String, PluginCatalog.CatalogLocalization> locs = plugin.localizations();
        final String tag = locale.toLanguageTag();
        final String lang = locale.getLanguage();

        PluginCatalog.CatalogLocalization found = locs.get(tag);
        if (found == null && "zh".equals(lang)) {
            final String country = locale.getCountry();
            if ("TW".equalsIgnoreCase(country) || "HK".equalsIgnoreCase(country) || "MO".equalsIgnoreCase(country)) {
                found = locs.get("zh-Hant");
            } else {
                found = locs.get("zh-Hans");
            }
        }
        if (found == null) {
            found = locs.get(lang);
        }
        if (found == null) {
            found = locs.get("en");
        }
        final String name = found != null ? found.name() : plugin.name();
        final String summary = found != null ? found.summary() : plugin.summary();
        return new CatalogPluginLocalized(name, summary);
    }

    /**
     * Localized name and summary for presentation in the market UI.
     *
     * @param name localized display name
     * @param summary localized summary description
     */
    public record CatalogPluginLocalized(String name, String summary) {}

    private <T> MarketFuture<T> submitTask(final Callable<T> callable, final DownloadControl downloadControl) {
        synchronized (lifecycleLock) {
            if (closed) {
                final MarketFuture<T> failed = new MarketFuture<>();
                failed.completeExceptionally(new RejectedExecutionException("PluginMarketService is closed"));
                return failed;
            }
            final MarketFuture<T> future = new MarketFuture<>();
            final MarketTask<T> task = new MarketTask<>(callable, future, downloadControl);
            try {
                marketExecutor.execute(task);
                return future;
            } catch (RejectedExecutionException ex) {
                task.cancelled.set(true);
                future.completeExceptionally(new RejectedExecutionException("Market service queue is full", ex));
                return future;
            }
        }
    }

    @Override
    public void close() {
        final List<MarketTask<?>> tasksToCancel = new ArrayList<>();
        final DownloadControl activeDownload;

        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            closed = true;

            if (runningTask != null) {
                activeDownload = runningTask.downloadControl;
            } else {
                activeDownload = null;
            }

            final List<Runnable> queued = new ArrayList<>();
            taskQueue.drainTo(queued);
            for (Runnable r : queued) {
                if (r instanceof MarketTask<?> task) {
                    task.cancelled.set(true);
                    tasksToCancel.add(task);
                }
            }
        }

        if (activeDownload != null && activeDownload.canCancel()) {
            activeDownload.cancel();
        }

        for (MarketTask<?> task : tasksToCancel) {
            task.future.completeExceptionally(
                    new CancellationException("PluginMarketService closed before task could execute"));
        }

        marketExecutor.shutdown();
    }

    private final class MarketTask<T> implements Runnable {
        private final Callable<T> callable;
        private final MarketFuture<T> future;
        private final DownloadControl downloadControl;
        private final AtomicBoolean started = new AtomicBoolean(false);
        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        MarketTask(final Callable<T> callable, final MarketFuture<T> future, final DownloadControl downloadControl) {
            this.callable = Objects.requireNonNull(callable, "callable");
            this.future = Objects.requireNonNull(future, "future");
            this.downloadControl = downloadControl;
            this.future.setTask(this);
        }

        boolean cancelQueued() {
            synchronized (lifecycleLock) {
                if (cancelled.get()) {
                    return true;
                }
                if (started.get()) {
                    return false;
                }
                cancelled.set(true);
                taskQueue.remove(this);
                if (downloadControl != null) {
                    downloadControl.cancel();
                }
                return true;
            }
        }

        @Override
        public void run() {
            if (cancelled.get()) {
                return;
            }

            synchronized (lifecycleLock) {
                if (closed || cancelled.get()) {
                    cancelled.set(true);
                    future.completeExceptionally(
                            new CancellationException("PluginMarketService closed before task could execute"));
                    return;
                }
                started.set(true);
                runningTask = this;
            }

            try {
                final T result = callable.call();
                future.complete(result);
            } catch (Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                future.completeExceptionally(failure);
            } finally {
                if (!future.isDone()) {
                    close();
                    future.completeExceptionally(
                            new IllegalStateException("Market task terminated abruptly due to fatal error"));
                }
                synchronized (lifecycleLock) {
                    if (runningTask == this) {
                        runningTask = null;
                    }
                }
            }
        }
    }
}
