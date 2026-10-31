package dev.turboism.pluginmanagement;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.turboism.core.FrameworkBuildInfo;
import dev.turboism.core.descriptor.DescriptorParseException;
import dev.turboism.core.descriptor.PluginDescriptorParser;
import dev.turboism.core.plugin.PluginJarContract;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.RuntimeTimerHandle;
import dev.turboism.core.runtime.RuntimeTimerSubmission;
import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.core.version.PluginVersion;
import dev.turboism.core.version.VersionRange;
import dev.turboism.internal.core.CoreUpdateService;
import dev.turboism.pluginmanagement.PluginAutoUpdatePlanner.PlannedUpdate;
import dev.turboism.pluginmanagement.PluginCatalog.CatalogArtifact;
import dev.turboism.pluginmanagement.PluginCatalog.CatalogDependency;
import dev.turboism.pluginmanagement.PluginCatalog.CatalogRelease;
import dev.turboism.pluginmanagement.PluginCatalogClient.CatalogFetchResult;
import dev.turboism.pluginmanagement.PluginCatalogVerifier.PluginCatalogVerificationException;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.runtime.RuntimeSettingsService;
import dev.turboism.update.UpdateFileSupport;
import dev.turboism.update.UpdatePreferencesStore;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Background automatic update service for installed first-party official plugins.
 *
 * <p>Runs 15 seconds after startup, repeats every 24 hours, and supports on-demand checks.
 * Downloads verified artifacts from approved GitHub Release hosts, cross-checks embedded
 * descriptors and {@code cubism-versions.json}, and stages updates via
 * {@link PendingPluginOperations#stage(Path)} for atomic application at the next launch
 * without modifying {@code disabledPlugins}.</p>
 */
public final class RuntimePluginAutoUpdater implements PluginAutoUpdateControl {

    /** Delay after shell startup before the first automatic plugin update check runs. */
    public static final Duration STARTUP_DELAY = Duration.ofSeconds(15);

    /** Interval between recurring background plugin update checks within a session. */
    public static final Duration AUTOMATIC_INTERVAL = Duration.ofHours(24);

    static volatile Runnable testBeforeStageHook;
    static volatile Runnable testBetweenStageAndHotApplyHook;

    private static final String DESCRIPTOR_ENTRY = "META-INF/turboism/plugin.json";
    private static final String CUBISM_VERSIONS_ENTRY = "META-INF/turboism/cubism-versions.json";
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final Path home;
    private final Path pluginsDirectory;
    private final ConfinedPluginFiles confinedFiles;
    private final RuntimeScheduler scheduler;
    private final RuntimeSettingsService runtimeSettings;
    private final PluginCatalogClient client;
    private final PluginCatalogVerifier verifier;
    private final Clock clock;
    private final FrameworkBuildInfo.Channel channel;
    private final Supplier<Optional<String>> cubismVersion;
    private final PluginVersion turboismApiVersion;
    private final Duration startupDelay;
    private final Duration automaticInterval;
    private final Consumer<String> diagnostic;
    private final UpdatePreferencesStore preferencesStore;
    private final PluginCatalogStateStore stateStore;
    private final PendingPluginOperations pending;
    private final ExecutorService executor;
    private final Object lock = new Object();
    private final CopyOnWriteArrayList<Consumer<Snapshot>> listeners = new CopyOnWriteArrayList<>();

    private volatile dev.turboism.preview.PluginHotReloadCoordinator hotReloadCoordinator;
    private boolean automaticEnabled;
    private Snapshot snapshot = Snapshot.idle();
    private RuntimeTimerHandle automaticTimer;
    private CompletableFuture<Snapshot> inFlight;
    private boolean inFlightAutomatic;
    private boolean inFlightUserInitiated;
    private String lastRemindedSignature = "";
    private long generation;
    private boolean started;
    private boolean closed;

    /** Creates a production plugin auto-updater for the given home, scheduler, settings, and host version source. */
    public RuntimePluginAutoUpdater(
            final Path home,
            final RuntimeScheduler scheduler,
            final RuntimeSettingsService runtimeSettings,
            final Supplier<Optional<String>> cubismVersion,
            final Consumer<String> diagnostic) {
        this(
                home,
                scheduler,
                runtimeSettings,
                new PluginCatalogClient(),
                new PluginCatalogVerifier(),
                Clock.systemUTC(),
                FrameworkBuildInfo.current().channel(),
                cubismVersion,
                PluginAutoUpdatePlanner.DEFAULT_TURBOISM_API_VERSION,
                STARTUP_DELAY,
                AUTOMATIC_INTERVAL,
                diagnostic);
    }

    /** Full deterministic construction seam used by unit tests. */
    public RuntimePluginAutoUpdater(
            final Path home,
            final RuntimeScheduler scheduler,
            final RuntimeSettingsService runtimeSettings,
            final PluginCatalogClient client,
            final PluginCatalogVerifier verifier,
            final Clock clock,
            final FrameworkBuildInfo.Channel channel,
            final Supplier<Optional<String>> cubismVersion,
            final PluginVersion turboismApiVersion,
            final Duration startupDelay,
            final Duration automaticInterval,
            final Consumer<String> diagnostic) {
        this.home = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
        this.pluginsDirectory = this.home.resolve("plugins");
        this.confinedFiles = new ConfinedPluginFiles(this.home);
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.runtimeSettings = Objects.requireNonNull(runtimeSettings, "runtimeSettings");
        this.client = Objects.requireNonNull(client, "client");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.channel = Objects.requireNonNull(channel, "channel");
        this.cubismVersion = Objects.requireNonNull(cubismVersion, "cubismVersion");
        this.turboismApiVersion = Objects.requireNonNull(turboismApiVersion, "turboismApiVersion");
        this.startupDelay = requireNonNegative(startupDelay, "startupDelay");
        this.automaticInterval = requirePositive(automaticInterval, "automaticInterval");
        this.diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
        this.preferencesStore = new UpdatePreferencesStore(this.home, this.diagnostic);
        this.stateStore = new PluginCatalogStateStore(this.home, this.diagnostic);
        this.pending = new PendingPluginOperations(this.home);
        this.automaticEnabled = preferencesStore.automaticPluginUpdatesEnabled();
        this.executor = Executors.newSingleThreadExecutor(new PluginUpdaterThreadFactory());
    }

    /** Binds the runtime hot-reload coordinator to apply updates without restart when safe. */
    public void bindHotReloadCoordinator(final dev.turboism.preview.PluginHotReloadCoordinator coordinator) {
        this.hotReloadCoordinator = coordinator;
    }

    @Override
    public boolean autoUpdateAvailable() {
        return true;
    }

    @Override
    public Snapshot pluginUpdateSnapshot() {
        synchronized (lock) {
            return snapshot;
        }
    }

    @Override
    public boolean automaticPluginUpdatesEnabled() {
        synchronized (lock) {
            return automaticEnabled;
        }
    }

    @Override
    public CoreUpdateService.PreferenceSaveResult saveAutomaticPluginUpdatesEnabled(final boolean enabled) {
        Snapshot toDeliver = null;
        long deliveryGeneration = -1L;
        synchronized (lock) {
            if (closed) {
                return CoreUpdateService.PreferenceSaveResult.failed("Automatic plugin updates are closed.");
            }
            final CoreUpdateService.PreferenceSaveResult saved =
                    preferencesStore.saveAutomaticPluginUpdatesEnabled(enabled);
            if (!saved.saved()) {
                return saved;
            }
            automaticEnabled = enabled;
            if (!enabled) {
                cancelAutomaticTimerLocked();
                if (inFlight != null && inFlightAutomatic && !inFlightUserInitiated) {
                    generation++;
                    final CompletableFuture<Snapshot> cancelled = inFlight;
                    inFlight = null;
                    inFlightAutomatic = false;
                    inFlightUserInitiated = false;
                    final Snapshot disabled = new Snapshot(Status.DISABLED, Map.of(), false, false);
                    snapshot = disabled;
                    cancelled.complete(disabled);
                    toDeliver = disabled;
                    deliveryGeneration = generation;
                } else if (inFlight != null && inFlightUserInitiated) {
                    inFlightAutomatic = false;
                } else {
                    generation++;
                    final Snapshot disabled = new Snapshot(Status.DISABLED, Map.of(), false, false);
                    snapshot = disabled;
                    toDeliver = disabled;
                    deliveryGeneration = generation;
                }
            } else if (started && !safeModeLocked()) {
                if (snapshot.status() == Status.DISABLED) {
                    generation++;
                    snapshot = Snapshot.idle();
                    toDeliver = snapshot;
                    deliveryGeneration = generation;
                }
                if (automaticTimer == null && inFlight == null) {
                    scheduleAutomaticLocked(startupDelay);
                }
            }
        }
        deliver(toDeliver, deliveryGeneration);
        return CoreUpdateService.PreferenceSaveResult.success();
    }

    @Override
    public void startPluginAutoUpdater() {
        Snapshot toDeliver = null;
        long deliveryGeneration = -1L;
        synchronized (lock) {
            if (closed || started) return;
            started = true;
            if (safeModeLocked()) {
                generation++;
                snapshot = new Snapshot(Status.UNAVAILABLE, Map.of(), false, false);
                toDeliver = snapshot;
                deliveryGeneration = generation;
            } else if (!automaticEnabled) {
                generation++;
                snapshot = new Snapshot(Status.DISABLED, Map.of(), false, false);
                toDeliver = snapshot;
                deliveryGeneration = generation;
            } else {
                scheduleAutomaticLocked(startupDelay);
            }
        }
        deliver(toDeliver, deliveryGeneration);
    }

    @Override
    public CompletionStage<Snapshot> checkForPluginUpdates() {
        return request(true);
    }

    @Override
    public Registration subscribePluginUpdates(final Consumer<Snapshot> listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.addIfAbsent(listener);
        final AtomicBoolean removed = new AtomicBoolean();
        return () -> {
            if (removed.compareAndSet(false, true)) {
                listeners.remove(listener);
            }
        };
    }

    @Override
    public void close() {
        CompletableFuture<Snapshot> pendingFuture = null;
        synchronized (lock) {
            if (closed) return;
            closed = true;
            generation++;
            cancelAutomaticTimerLocked();
            final Snapshot closedSnapshot = new Snapshot(Status.CLOSED, Map.of(), false, false);
            if (inFlight != null) {
                pendingFuture = inFlight;
                inFlight = null;
                inFlightAutomatic = false;
                inFlightUserInitiated = false;
                pendingFuture.complete(closedSnapshot);
            }
            snapshot = closedSnapshot;
            listeners.clear();
        }
        executor.shutdownNow();
    }

    private CompletionStage<Snapshot> request(final boolean manual) {
        CompletableFuture<Snapshot> result;
        Snapshot toDeliver = null;
        long deliveryGeneration = -1L;
        boolean launch = false;
        long operationGeneration = -1L;
        synchronized (lock) {
            if (closed) {
                return CompletableFuture.completedFuture(new Snapshot(Status.CLOSED, Map.of(), false, false));
            }
            if (safeModeLocked()) {
                generation++;
                snapshot = new Snapshot(Status.UNAVAILABLE, Map.of(), manual, false);
                toDeliver = snapshot;
                deliveryGeneration = generation;
                result = CompletableFuture.completedFuture(toDeliver);
            } else if (!manual && !automaticEnabled) {
                generation++;
                snapshot = new Snapshot(Status.DISABLED, Map.of(), false, false);
                toDeliver = snapshot;
                deliveryGeneration = generation;
                result = CompletableFuture.completedFuture(toDeliver);
            } else if (inFlight != null) {
                if (!manual) {
                    inFlightAutomatic = true;
                }
                if (manual && !inFlightUserInitiated) {
                    inFlightUserInitiated = true;
                    generation++;
                    snapshot = new Snapshot(Status.CHECKING, snapshot.stagedUpdates(), true, false);
                    toDeliver = snapshot;
                    deliveryGeneration = generation;
                }
                result = inFlight;
            } else {
                result = new CompletableFuture<>();
                inFlight = result;
                inFlightAutomatic = !manual;
                inFlightUserInitiated = manual;
                generation++;
                operationGeneration = generation;
                snapshot = new Snapshot(Status.CHECKING, snapshot.stagedUpdates(), manual, false);
                toDeliver = snapshot;
                deliveryGeneration = generation;
                launch = true;
            }
        }
        deliver(toDeliver, deliveryGeneration);
        if (launch) {
            final CompletableFuture<Snapshot> submitted = result;
            final long submittedGeneration = operationGeneration;
            try {
                executor.execute(() -> performUpdateCycle(submittedGeneration, submitted));
            } catch (RuntimeException rejected) {
                finishCycle(submittedGeneration, submitted, Outcome.unavailable("PLUGIN_UPDATE_WORK_UNAVAILABLE"));
            }
        }
        return result;
    }

    private void performUpdateCycle(final long operationGeneration, final CompletableFuture<Snapshot> future) {
        synchronized (lock) {
            if (closed || generation != operationGeneration || inFlight != future) return;
            if (!inFlightUserInitiated && (!automaticEnabled || safeModeLocked())) return;
        }
        Outcome outcome;
        try {
            outcome = executeUpdateCycle(operationGeneration, future);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            outcome = Outcome.unavailable("PLUGIN_UPDATE_UNEXPECTED_FAILURE");
        }
        finishCycle(operationGeneration, future, outcome);
    }

    private Outcome executeUpdateCycle(final long operationGeneration, final CompletableFuture<Snapshot> future) {
        if (PluginUpdateGate.isAutoUpdateBlocked(home)) {
            report("PLUGIN_UPDATE_GATE_LOCKED");
            return Outcome.unavailable("PLUGIN_UPDATE_GATE_LOCKED");
        }
        if (pending.recoveryRequired()) {
            report("PLUGIN_PENDING_RECOVERY_REQUIRED");
            return Outcome.unavailable("PLUGIN_PENDING_RECOVERY_REQUIRED");
        }
        final InstalledState installedState = readInstalledState();
        if (installedState == null) {
            return Outcome.unavailable("PLUGIN_INSTALLED_SCAN_FAILED");
        }

        final PluginCatalogStateStore.State currentState = stateStore.read();
        final Instant now = clock.instant();
        stateStore.save(currentState.withLastAutomaticAttempt(now));

        final CatalogFetchResult fetchResult;
        try {
            fetchResult = client.fetchCatalog(currentState.etag());
        } catch (IOException networkFailure) {
            report("PLUGIN_CATALOG_NETWORK_UNAVAILABLE");
            return Outcome.unavailable("PLUGIN_CATALOG_NETWORK_UNAVAILABLE");
        }
        if (!isOperationCurrent(operationGeneration, future)) {
            return Outcome.cancelled();
        }

        final PluginCatalog catalog;
        try {
            if (fetchResult instanceof CatalogFetchResult.NotModified notModified) {
                if (currentState.cachedCatalogBytes().isEmpty()
                        || currentState.cachedSignatureBytes().isEmpty()) {
                    report("PLUGIN_CATALOG_CACHE_MISSING_FOR_304");
                    return Outcome.unavailable("PLUGIN_CATALOG_CACHE_MISSING_FOR_304");
                }
                catalog = verifier.verify(
                        currentState.cachedCatalogBytes().orElseThrow(),
                        currentState.cachedSignatureBytes().orElseThrow());
                stateStore.save(stateStore.read().withEtag(notModified.etag()));
            } else if (fetchResult instanceof CatalogFetchResult.Fetched fetched) {
                catalog = verifier.verifyAndAdvance(
                        fetched.catalogBytes(), fetched.signatureBytes(), stateStore, fetched.etag());
            } else {
                throw new IllegalStateException("unexpected CatalogFetchResult");
            }
        } catch (PluginCatalogVerificationException rejected) {
            report(rejected.code());
            return Outcome.unavailable(rejected.code());
        } catch (RuntimeException invalid) {
            report("PLUGIN_CATALOG_INVALID");
            return Outcome.unavailable("PLUGIN_CATALOG_INVALID");
        }

        if (installedState.effectiveVersions().isEmpty()) {
            return Outcome.upToDate(installedState.alreadyStagedUpdates());
        }

        final Optional<String> activeCubism = normalizeCubismVersion(cubismVersion.get());
        final PluginAutoUpdatePlanner planner = new PluginAutoUpdatePlanner(channel, activeCubism, turboismApiVersion);

        final Map<String, PluginVersion> workingVersions = new HashMap<>(installedState.effectiveVersions());
        final Map<String, String> stagedUpdates = new LinkedHashMap<>(installedState.alreadyStagedUpdates());
        final Set<String> excludedReleaseKeys = new HashSet<>();
        final Set<String> finalizedPluginIds = new HashSet<>();

        while (isOperationCurrent(operationGeneration, future)) {
            final Map<String, PluginVersion> remainingTargets = new HashMap<>(workingVersions);
            finalizedPluginIds.forEach(remainingTargets::remove);
            if (remainingTargets.isEmpty()) {
                break;
            }
            final List<PlannedUpdate> planned =
                    planner
                            .plan(catalog, workingVersions, installedState.pendingUninstallIds(), excludedReleaseKeys)
                            .stream()
                            .filter(update -> !finalizedPluginIds.contains(update.pluginId()))
                            .toList();
            if (planned.isEmpty()) {
                break;
            }
            final PlannedUpdate next = planned.get(0);
            if (!dependenciesSatisfied(next.release(), workingVersions)) {
                excludedReleaseKeys.add(next.releaseKey());
                continue;
            }
            if (PluginUpdateGate.isAutoUpdateBlocked(home)) {
                report("PLUGIN_UPDATE_GATE_LOCKED");
                break;
            }
            final StageAttempt attempt = downloadVerifyAndStage(next, activeCubism);
            if (attempt == StageAttempt.FALLBACK_RELEASE) {
                excludedReleaseKeys.add(next.releaseKey());
                continue;
            }
            finalizedPluginIds.add(next.pluginId());
            if (attempt == StageAttempt.HOT_APPLIED) {
                workingVersions.put(next.pluginId(), next.targetVersion());
            } else if (attempt == StageAttempt.STAGED) {
                workingVersions.put(next.pluginId(), next.targetVersion());
                stagedUpdates.put(next.pluginId(), next.release().version());
                stateStore.recordStagedUpdate(next.pluginId(), next.release().version());
            }
        }

        return stagedUpdates.isEmpty() ? Outcome.upToDate(Map.of()) : Outcome.staged(stagedUpdates);
    }

    private StageAttempt downloadVerifyAndStage(final PlannedUpdate update, final Optional<String> activeCubism) {
        final CatalogArtifact artifact = update.release().artifact();
        Path tempJar = null;
        try {
            tempJar = createPrivateTempJar();
            client.downloadArtifact(URI.create(artifact.url()), artifact.size(), artifact.sha256(), tempJar);
            final JarCheckResult check = inspectDownloadedJar(update, tempJar, activeCubism);
            if (check == JarCheckResult.CUBISM_SIDECAR_INCOMPATIBLE) {
                report("PLUGIN_UPDATE_CUBISM_SIDECAR_INCOMPATIBLE");
                return StageAttempt.FALLBACK_RELEASE;
            }
            if (check != JarCheckResult.VALID) {
                report("PLUGIN_UPDATE_JAR_REJECTED");
                return StageAttempt.FAILED;
            }
            final Runnable hook = testBeforeStageHook;
            if (hook != null) {
                hook.run();
            }
            synchronized (PluginUpdateGate.rootLock(home)) {
                if (PluginUpdateGate.isAutoUpdateBlocked(home)) {
                    report("PLUGIN_UPDATE_GATE_LOCKED");
                    return StageAttempt.FAILED;
                }
                final PendingPluginOperations.StagedInstall staged = pending.stage(tempJar);
                if (!staged.accepted() || staged.prepared() == null) {
                    report(staged.code());
                    return StageAttempt.FAILED;
                }
                final byte[] expectedDescHash = java.util.HexFormat.of().parseHex(artifact.descriptorSha256());
                final byte[] actualDescHash =
                        java.util.HexFormat.of().parseHex(staged.prepared().descriptorSha256());
                if (!MessageDigest.isEqual(expectedDescHash, actualDescHash)) {
                    report("PLUGIN_UPDATE_DESCRIPTOR_HASH_MISMATCH");
                    return StageAttempt.FAILED;
                }
                final Runnable betweenHook = testBetweenStageAndHotApplyHook;
                if (betweenHook != null) {
                    betweenHook.run();
                }
                if (update.release().hotReload()
                        && hotReloadCoordinator != null
                        && pending.canHotApply(update.pluginId())) {
                    final Path target = pluginsDirectory.resolve(update.pluginId() + ".jar");
                    if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                            && hotReloadCoordinator.isHotReloadSafe(
                                    staged.prepared().stagedJar())) {
                        final Path stagingDir = home.resolve("state/runtime/plugin-management/packages");
                        final Path backup = stagingDir.resolve(
                                ".backup-" + update.pluginId() + "-" + java.util.UUID.randomUUID() + ".jar");
                        boolean replaced = false;
                        boolean applied = false;
                        try {
                            Files.copy(target, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            UpdateFileSupport.copyAtomic(staged.prepared().stagedJar(), target);
                            replaced = true;
                            final var reloadRes = hotReloadCoordinator.hotReload(update.pluginId(), target, backup);
                            if (reloadRes.succeeded()) {
                                Files.deleteIfExists(staged.prepared().stagedJar());
                                applied = true;
                                return StageAttempt.HOT_APPLIED;
                            }
                        } catch (IOException | RuntimeException failure) {
                            // Keep staged in the pending journal for restart, including Windows file locks.
                        } finally {
                            try {
                                if (replaced && !applied) {
                                    UpdateFileSupport.copyAtomic(backup, target);
                                }
                                Files.deleteIfExists(backup);
                            } catch (IOException | RuntimeException failure) {
                                // Retain the backup if restoration failed.
                                report("PLUGIN_UPDATE_BACKUP_CLEANUP_FAILED");
                            }
                        }
                    }
                }
                return StageAttempt.STAGED;
            }
        } catch (IOException | RuntimeException failure) {
            report("PLUGIN_UPDATE_DOWNLOAD_FAILED");
            return StageAttempt.FAILED;
        } finally {
            if (tempJar != null) {
                try {
                    Files.deleteIfExists(tempJar);
                } catch (IOException ignored) {
                    // Best-effort cleanup of temp artifact.
                }
            }
        }
    }

    static JarCheckResult inspectDownloadedJar(
            final PlannedUpdate update, final Path tempJar, final Optional<String> activeCubism) {
        final CatalogRelease release = update.release();
        final CatalogArtifact artifact = release.artifact();
        try {
            if (Files.size(tempJar) != artifact.size()) {
                return JarCheckResult.INVALID;
            }
            try (JarFile jar = new JarFile(tempJar.toFile())) {
                final JarEntry descriptorEntry = jar.getJarEntry(DESCRIPTOR_ENTRY);
                if (descriptorEntry == null || descriptorEntry.isDirectory()) {
                    return JarCheckResult.INVALID;
                }
                final byte[] descriptorBytes;
                try (InputStream stream = jar.getInputStream(descriptorEntry)) {
                    descriptorBytes = stream.readAllBytes();
                }
                final byte[] actualDescHash = PluginCatalogVerifier.sha256Bytes(descriptorBytes);
                final byte[] expectedDescHash = java.util.HexFormat.of().parseHex(artifact.descriptorSha256());
                if (!MessageDigest.isEqual(expectedDescHash, actualDescHash)) {
                    return JarCheckResult.INVALID;
                }
                final PluginDescriptor descriptor =
                        new PluginDescriptorParser().parse(new java.io.ByteArrayInputStream(descriptorBytes));
                if (!descriptor.id().equals(update.pluginId())
                        || !descriptor.version().equals(release.version())
                        || !descriptor.category().orElse("").equals(release.category())
                        || !descriptor.tags().equals(release.tags())
                        || !descriptor.turboismApi().equals(release.turboismApi())
                        || descriptor.environment().requiresCubism() != release.requiresCubism()) {
                    return JarCheckResult.INVALID;
                }
                try {
                    PluginJarContract.validate(descriptor, entryNames(jar), tempJar.toString());
                } catch (PluginJarContract.PluginJarContractException contractViolation) {
                    return JarCheckResult.INVALID;
                }
                if (release.requiresCubism()) {
                    if (activeCubism.isEmpty()) {
                        return JarCheckResult.CUBISM_SIDECAR_INCOMPATIBLE;
                    }
                    final JarEntry cubismSidecar = jar.getJarEntry(CUBISM_VERSIONS_ENTRY);
                    if (cubismSidecar != null) {
                        if (cubismSidecar.isDirectory()) {
                            return JarCheckResult.INVALID;
                        }
                        try (InputStream stream = jar.getInputStream(cubismSidecar)) {
                            final JsonNode root = JSON.readTree(stream);
                            if (root == null || !root.isObject()) {
                                return JarCheckResult.INVALID;
                            }
                            final JsonNode pluginField = root.get("plugin");
                            if (pluginField != null
                                    && (!pluginField.isTextual()
                                            || !update.pluginId().equals(pluginField.textValue()))) {
                                return JarCheckResult.INVALID;
                            }
                            final JsonNode supported = root.get("supportedVersions");
                            if (supported == null || !supported.isArray()) {
                                return JarCheckResult.INVALID;
                            }
                            boolean found = false;
                            final String requiredHost = activeCubism.orElseThrow();
                            for (JsonNode item : supported) {
                                if (item != null && item.isTextual() && requiredHost.equals(item.textValue())) {
                                    found = true;
                                    break;
                                }
                            }
                            if (!found) {
                                return JarCheckResult.CUBISM_SIDECAR_INCOMPATIBLE;
                            }
                        }
                    }
                }
            }
            return JarCheckResult.VALID;
        } catch (IOException | DescriptorParseException | RuntimeException failure) {
            return JarCheckResult.INVALID;
        }
    }

    private static List<String> entryNames(final JarFile jar) {
        final List<String> names = new ArrayList<>();
        final Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            final JarEntry entry = entries.nextElement();
            if (!entry.isDirectory()) {
                names.add(entry.getName());
            }
        }
        return names;
    }

    private InstalledState readInstalledState() {
        try {
            confinedFiles.rejectLinks(home);
            final Map<String, PluginVersion> installedVersions = new LinkedHashMap<>();
            if (Files.isDirectory(pluginsDirectory, LinkOption.NOFOLLOW_LINKS)) {
                confinedFiles.rejectLinks(pluginsDirectory);
                try (var entries = Files.list(pluginsDirectory)) {
                    for (Path jar : entries.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                            .filter(path -> path.getFileName().toString().endsWith(".jar"))
                            .sorted()
                            .toList()) {
                        final Optional<PluginArchiveMetadata> metadata = PluginArchiveMetadata.read(jar);
                        if (metadata.isEmpty()) continue;
                        final PluginArchiveMetadata value = metadata.orElseThrow();
                        try {
                            final PluginVersion parsedVersion = PluginVersion.parse(value.version());
                            installedVersions.merge(
                                    value.id(),
                                    parsedVersion,
                                    (existing, candidate) -> candidate.compareTo(existing) > 0 ? candidate : existing);
                        } catch (IllegalArgumentException ignored) {
                            // Ignore non-strict local version strings during auto-update scan.
                        }
                    }
                }
            }
            final Set<String> pendingUninstalls = new HashSet<>();
            final Map<String, String> alreadyStaged = new LinkedHashMap<>();
            final Map<String, String> persistedStaged = stateStore.read().stagedUpdates();
            for (PendingPluginOperations.Operation operation : pending.operations()) {
                if ("UNINSTALL".equals(operation.type())) {
                    pendingUninstalls.add(operation.pluginId());
                } else if ("INSTALL".equals(operation.type())) {
                    try {
                        final PluginVersion stagedVersion = PluginVersion.parse(operation.version());
                        final PluginVersion current = installedVersions.get(operation.pluginId());
                        if (current != null && stagedVersion.compareTo(current) > 0) {
                            installedVersions.put(operation.pluginId(), stagedVersion);
                        }
                        if (persistedStaged.containsKey(operation.pluginId())) {
                            alreadyStaged.put(operation.pluginId(), operation.version());
                        }
                    } catch (IllegalArgumentException ignored) {
                        // Ignore non-strict pending version.
                    }
                }
            }
            return new InstalledState(installedVersions, pendingUninstalls, alreadyStaged);
        } catch (IOException | RuntimeException failure) {
            report("PLUGIN_INSTALLED_SCAN_FAILED");
            return null;
        }
    }

    private void finishCycle(
            final long operationGeneration, final CompletableFuture<Snapshot> future, final Outcome outcome) {
        Snapshot result = null;
        long deliveryGeneration = -1L;
        synchronized (lock) {
            if (closed || generation != operationGeneration || inFlight != future) return;
            final boolean automatic = inFlightAutomatic;
            final boolean userInitiated = inFlightUserInitiated;
            inFlight = null;
            inFlightAutomatic = false;
            inFlightUserInitiated = false;
            generation++;
            deliveryGeneration = generation;
            result = switch (outcome.kind()) {
                case STAGED -> {
                    final String sig = outcome.stagedUpdates().toString();
                    final boolean reminder = !sig.equals(lastRemindedSignature);
                    if (reminder) {
                        lastRemindedSignature = sig;
                    }
                    yield new Snapshot(Status.UPDATES_STAGED, outcome.stagedUpdates(), userInitiated, reminder);
                }
                case UP_TO_DATE -> new Snapshot(Status.UP_TO_DATE, outcome.stagedUpdates(), userInitiated, false);
                case UNAVAILABLE -> new Snapshot(Status.UNAVAILABLE, snapshot.stagedUpdates(), userInitiated, false);
                case CANCELLED ->
                    automaticEnabled
                            ? new Snapshot(Status.IDLE, snapshot.stagedUpdates(), userInitiated, false)
                            : new Snapshot(Status.DISABLED, Map.of(), false, false);
            };
            snapshot = result;
            future.complete(result);
            if (automatic && automaticEnabled && !safeModeLocked()) {
                scheduleAutomaticLocked(automaticInterval);
            }
        }
        deliver(result, deliveryGeneration);
    }

    private boolean isOperationCurrent(final long operationGeneration, final CompletableFuture<Snapshot> future) {
        synchronized (lock) {
            return !closed && generation == operationGeneration && inFlight == future;
        }
    }

    private void scheduleAutomaticLocked(final Duration delay) {
        cancelAutomaticTimerLocked();
        if (closed || !started || !automaticEnabled || safeModeLocked()) return;
        final RuntimeTimerSubmission submission;
        try {
            submission = scheduler.schedule(delay, () -> request(false));
        } catch (RuntimeException rejected) {
            report("PLUGIN_UPDATE_TIMER_REJECTED");
            return;
        }
        if (submission.accepted()) {
            automaticTimer = submission.handle();
        } else {
            report("PLUGIN_UPDATE_TIMER_REJECTED");
        }
    }

    private void cancelAutomaticTimerLocked() {
        final RuntimeTimerHandle timer = automaticTimer;
        automaticTimer = null;
        if (timer != null) {
            try {
                timer.cancel();
            } catch (RuntimeException ignored) {
                // Best-effort timer cancellation.
            }
        }
    }

    private boolean safeModeLocked() {
        try {
            return runtimeSettings.read().safeMode();
        } catch (RuntimeException failure) {
            report("PLUGIN_UPDATE_SETTINGS_UNAVAILABLE");
            return true;
        }
    }

    @SuppressWarnings("ReferenceEquality")
    private void deliver(final Snapshot candidate, final long expectedGeneration) {
        if (candidate == null) return;
        for (Consumer<Snapshot> listener : listeners) {
            synchronized (lock) {
                if (closed || generation != expectedGeneration || snapshot != candidate) return;
            }
            try {
                listener.accept(candidate);
            } catch (RuntimeException ignored) {
                // Listener exceptions must not break delivery to other subscribers.
            }
        }
    }

    private void report(final String code) {
        try {
            diagnostic.accept(code);
        } catch (RuntimeException ignored) {
            // Diagnostics are best-effort.
        }
    }

    private static boolean dependenciesSatisfied(
            final CatalogRelease release, final Map<String, PluginVersion> availableVersions) {
        for (CatalogDependency dependency : release.dependencies()) {
            if (!dependency.isRequired()) continue;
            final PluginVersion available = availableVersions.get(dependency.id());
            if (available == null) return false;
            try {
                if (!VersionRange.parse(dependency.version()).contains(available)) {
                    return false;
                }
            } catch (IllegalArgumentException invalid) {
                return false;
            }
        }
        return true;
    }

    private static Path createPrivateTempJar() throws IOException {
        try {
            return Files.createTempFile(
                    "turboism-plugin-update-",
                    ".jar",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException ignored) {
            return Files.createTempFile("turboism-plugin-update-", ".jar");
        }
    }

    private static Optional<String> normalizeCubismVersion(final Optional<String> raw) {
        if (raw == null || raw.isEmpty()) return Optional.empty();
        final String trimmed = raw.orElseThrow().trim();
        return trimmed.isEmpty() ? Optional.empty() : Optional.of(trimmed);
    }

    private static Duration requireNonNegative(final Duration duration, final String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isNegative()) throw new IllegalArgumentException(name + " must not be negative");
        return duration;
    }

    private static Duration requirePositive(final Duration duration, final String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }

    private enum StageAttempt {
        STAGED,
        HOT_APPLIED,
        FALLBACK_RELEASE,
        FAILED
    }

    enum JarCheckResult {
        VALID,
        CUBISM_SIDECAR_INCOMPATIBLE,
        INVALID
    }

    private record InstalledState(
            Map<String, PluginVersion> effectiveVersions,
            Set<String> pendingUninstallIds,
            Map<String, String> alreadyStagedUpdates) {}

    private record Outcome(Kind kind, Map<String, String> stagedUpdates, String code) {
        enum Kind {
            STAGED,
            UP_TO_DATE,
            UNAVAILABLE,
            CANCELLED
        }

        static Outcome staged(final Map<String, String> updates) {
            return new Outcome(Kind.STAGED, Map.copyOf(updates), "PLUGIN_UPDATES_STAGED");
        }

        static Outcome upToDate(final Map<String, String> existingStaged) {
            return new Outcome(Kind.UP_TO_DATE, Map.copyOf(existingStaged), "PLUGIN_UPDATES_UP_TO_DATE");
        }

        static Outcome unavailable(final String code) {
            return new Outcome(Kind.UNAVAILABLE, Map.of(), code);
        }

        static Outcome cancelled() {
            return new Outcome(Kind.CANCELLED, Map.of(), "PLUGIN_UPDATES_CANCELLED");
        }
    }

    private static final class PluginUpdaterThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(final Runnable runnable) {
            final Thread thread = new Thread(runnable, "turboism-plugin-auto-updater");
            thread.setDaemon(true);
            return thread;
        }
    }
}
