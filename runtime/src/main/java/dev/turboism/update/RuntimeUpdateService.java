package dev.turboism.update;

import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.RuntimeTimerHandle;
import dev.turboism.core.runtime.RuntimeTimerSubmission;
import dev.turboism.internal.core.CoreFrameworkUpdates;
import dev.turboism.internal.core.CoreUpdateService;
import dev.turboism.runtime.exec.RuntimeThreads;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.runtime.RuntimeSettingsService;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Runtime-owned bounded checker for release-channel discovery documents published at
 * {@code https://api.turboism.dev/v1/releases/<channel>.json}.
 *
 * <p>The runtime owns transport, validation, persistence, scheduling and
 * cancellation. Selection and presentation stay in the framework shell, which only
 * receives immutable snapshots and invokes explicitly user-driven operations.</p>
 */
public final class RuntimeUpdateService implements CoreUpdateService {
    public static final Duration STARTUP_DELAY = Duration.ofSeconds(15);
    public static final Duration AUTOMATIC_INTERVAL = Duration.ofHours(24);
    private static final String CHANNEL_SWITCH_BUSY_MESSAGE =
            "Finish or cancel the active framework update before changing the update channel.";

    private final Path home;
    private final RuntimeScheduler scheduler;
    private final RuntimeSettingsService runtimeSettings;
    private final Function<String, UpdateTransport> transportFactory;
    private final Clock clock;
    private final Duration startupDelay;
    private final Duration automaticInterval;
    private final UpdatePreferencesStore preferencesStore;
    private final Consumer<String> diagnostic;
    private final ExecutorService executor;
    private final InstalledBuild installed;
    private final Object lock = new Object();
    private final CopyOnWriteArrayList<Consumer<Snapshot>> listeners = new CopyOnWriteArrayList<>();

    private UpdateTransport transport;
    private UpdateDiscoveryParser parser;
    private UpdateStateStore stateStore;
    private UpdatePreferencesStoreState preferences;
    private UpdateStateStore.State state;
    private Snapshot snapshot;
    private volatile dev.turboism.internal.core.CoreFrameworkUpdates frameworkUpdates =
            dev.turboism.internal.core.CoreFrameworkUpdates.unavailable();
    private RuntimeTimerHandle automaticTimer;
    private CompletionStage<UpdateTransport.Response> transportStage;
    private CompletableFuture<Snapshot> inFlight;
    private boolean inFlightAutomatic;
    private boolean inFlightUserInitiated;
    private String lastRemindedIdentity;
    private long generation;
    private boolean started;
    private boolean closed;

    public RuntimeUpdateService(
            final Path home, final RuntimeScheduler scheduler, final RuntimeSettingsService runtimeSettings) {
        this(
                home,
                scheduler,
                runtimeSettings,
                (Function<String, UpdateTransport>) HttpUpdateTransport::new,
                Clock.systemUTC(),
                InstalledBuild.forHome(home),
                STARTUP_DELAY,
                AUTOMATIC_INTERVAL,
                ignored -> {});
    }

    public RuntimeUpdateService(
            final Path home,
            final RuntimeScheduler scheduler,
            final RuntimeSettingsService runtimeSettings,
            final UpdateTransport transport,
            final Clock clock) {
        this(
                home,
                scheduler,
                runtimeSettings,
                transport,
                clock,
                InstalledBuild.forHome(home),
                STARTUP_DELAY,
                AUTOMATIC_INTERVAL,
                ignored -> {});
    }

    /** Full deterministic construction seam used by focused runtime tests with a single transport. */
    public RuntimeUpdateService(
            final Path home,
            final RuntimeScheduler scheduler,
            final RuntimeSettingsService runtimeSettings,
            final UpdateTransport transport,
            final Clock clock,
            final InstalledBuild installed,
            final Duration startupDelay,
            final Duration automaticInterval,
            final Consumer<String> diagnostic) {
        this(
                home,
                scheduler,
                runtimeSettings,
                (Function<String, UpdateTransport>) ignoredChannel -> Objects.requireNonNull(transport, "transport"),
                clock,
                installed,
                startupDelay,
                automaticInterval,
                diagnostic);
    }

    /**
     * Creates a channel-aware update service resolving one transport per active release channel.
     *
     * @param home Turboism home directory
     * @param scheduler runtime timer scheduler
     * @param runtimeSettings runtime settings service
     * @param transportFactory channel-to-transport factory
     * @param clock time source
     * @param installed installed build identity
     * @param startupDelay initial check delay after startup
     * @param automaticInterval recurring check interval
     * @param diagnostic diagnostic consumer
     * @return configured update service
     */
    public static RuntimeUpdateService channelAware(
            final Path home,
            final RuntimeScheduler scheduler,
            final RuntimeSettingsService runtimeSettings,
            final Function<String, UpdateTransport> transportFactory,
            final Clock clock,
            final InstalledBuild installed,
            final Duration startupDelay,
            final Duration automaticInterval,
            final Consumer<String> diagnostic) {
        return new RuntimeUpdateService(
                home,
                scheduler,
                runtimeSettings,
                transportFactory,
                clock,
                installed,
                startupDelay,
                automaticInterval,
                diagnostic);
    }

    /** Sole construction path: every transport is resolved per active channel. */
    public RuntimeUpdateService(
            final Path home,
            final RuntimeScheduler scheduler,
            final RuntimeSettingsService runtimeSettings,
            final Function<String, UpdateTransport> transportFactory,
            final Clock clock,
            final InstalledBuild installed,
            final Duration startupDelay,
            final Duration automaticInterval,
            final Consumer<String> diagnostic) {
        this.home = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.runtimeSettings = Objects.requireNonNull(runtimeSettings, "runtimeSettings");
        this.transportFactory = Objects.requireNonNull(transportFactory, "transportFactory");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.installed = Objects.requireNonNull(installed, "installed");
        this.startupDelay = requireNonNegative(startupDelay, "startupDelay");
        this.automaticInterval = requirePositive(automaticInterval, "automaticInterval");
        this.diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
        this.preferencesStore = new UpdatePreferencesStore(this.home, diagnostic);
        final String fallbackChannel = UpdatePreferencesStore.defaultSelectableChannel(installed.channel());
        final Preferences loadedPreferences = preferencesStore.read(fallbackChannel);
        this.preferences = new UpdatePreferencesStoreState(
                loadedPreferences.automaticChecksEnabled(), loadedPreferences.selectedChannel());
        this.transport = Objects.requireNonNull(transportFactory.apply(this.preferences.updateChannel()), "transport");
        this.parser = new UpdateDiscoveryParser(this.preferences.updateChannel());
        this.stateStore = new UpdateStateStore(this.home, this.preferences.updateChannel(), diagnostic);
        this.state = sanitizeState(stateStore.read());
        this.snapshot = Snapshot.idle(installed.versionText());
        this.executor = RuntimeThreads.serialLane(new UpdateThreadFactory());
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public Snapshot snapshot() {
        synchronized (lock) {
            return snapshot;
        }
    }

    @Override
    public dev.turboism.internal.core.CoreFrameworkUpdates frameworkUpdates() {
        return frameworkUpdates;
    }

    /** Product-owned binding only; never construct authority from a feed or a local status marker. */
    public void bindFrameworkUpdates(final dev.turboism.internal.core.CoreFrameworkUpdates updates) {
        Objects.requireNonNull(updates, "updates");
        synchronized (lock) {
            if (closed) throw new IllegalStateException("Update service is closed");
            if (frameworkUpdates != dev.turboism.internal.core.CoreFrameworkUpdates.unavailable()) {
                throw new IllegalStateException("Framework installer is already bound");
            }
            frameworkUpdates = updates;
        }
    }

    /**
     * Returns complete metadata from the last successful check only while an update is available.
     * This is not signature authentication, installation evidence or permission to apply files.
     */
    public Optional<UpdateDiscovery.Candidate> incrementalCandidate() {
        synchronized (lock) {
            if (closed || inFlight != null || safeModeLocked() || snapshot.status() != Status.UPDATE_AVAILABLE) {
                return Optional.empty();
            }
            return state.cachedResult()
                    .flatMap(UpdateDiscovery::candidate)
                    .filter(candidate -> candidate.incremental().isPresent());
        }
    }

    /**
     * Builds a read-only staging request on the caller's IO worker. No cached status or caller hash map
     * can establish installation authority. No product keyring/receipt writer is enabled by this API.
     */
    @SuppressWarnings("ReferenceEquality") // Exact successful check snapshot, including repeated identical responses.
    public IncrementalRequest incrementalRequest(final FrameworkInstallationReceiptLoader loader)
            throws java.io.IOException, InterruptedException,
                    UpdateManifestVerifier.UpdateManifestVerificationException {
        final Snapshot checked;
        final String activeChannel;
        final UpdateDiscovery.Candidate candidate;
        synchronized (lock) {
            checked = snapshot;
            activeChannel = preferences.updateChannel();
            candidate = incrementalCandidate()
                    .orElseThrow(() -> new java.io.IOException("No current successful incremental discovery"));
        }
        final UpdateIncrementalDiscovery incremental = candidate.incremental().orElseThrow();
        if (!activeChannel.equals(incremental.channel())) {
            throw new java.io.IOException("Discovery candidate channel differs from active update channel");
        }
        final FrameworkInstallationReceiptLoader.Evidence evidence = loader.load();
        if (!installed.version().equals(InstalledBuild.from(evidence.loaded()).version())
                || !installed.buildNumber().equals(evidence.loaded().buildNumber())
                || !installed.channel().equals(evidence.loaded().channel().wireName())) {
            throw new java.io.IOException("Discovery service and loaded installation differ");
        }
        final UpdateVerificationContext.Validation fresh = new UpdateVerificationContext.Validation() {
            @Override
            public void validate() throws java.io.IOException {
                synchronized (lock) {
                    if (snapshot != checked
                            || !activeChannel.equals(preferences.updateChannel())
                            || !incrementalCandidate().equals(Optional.of(candidate))) {
                        throw new java.io.IOException("Successful discovery changed or is no longer available");
                    }
                }
            }

            @Override
            public void claimCommit(final Runnable claim) throws java.io.IOException {
                synchronized (lock) {
                    validate();
                    claim.run(); // No IO while holding the service lock. This is the admission linearization point.
                }
            }
        };
        fresh.validate();
        final boolean optedIn = preferencesStore.readAll().updateChannel().isPresent();
        return new IncrementalRequest(
                candidate,
                evidence.root(),
                UpdateVerificationContext.fromReceipt(candidate, loader, evidence, fresh, optedIn));
    }

    /** A request bound to a live discovery snapshot; every operation reloads the old receipt. */
    public static final class IncrementalRequest {
        private final UpdateDiscovery.Candidate candidate;
        private final Path installation;
        private final UpdateVerificationContext context;

        private IncrementalRequest(
                final UpdateDiscovery.Candidate candidate,
                final Path installation,
                final UpdateVerificationContext context) {
            this.candidate = candidate;
            this.installation = installation;
            this.context = context;
        }

        CompletableFuture<FrameworkUpdateCoordinator.Status> downloadWith(
                final FrameworkUpdateCoordinator coordinator, final FrameworkUpdateDownloader downloader) {
            return coordinator.download(downloader, candidate, context);
        }

        /**
         * Stages this request's candidate off the caller thread.
         *
         * <p>Bytes are verified against the manifest before they count as staged, and progress is reported
         * through the supplied consumer. The returned future fails when verification or the installation
         * root bound to this request no longer matches.</p>
         *
         * @param downloader downloader that owns transport, retries and staging
         * @param progress progress consumer, may be {@code null}
         * @return completion carrying the verified staged download
         */
        public CompletableFuture<FrameworkUpdateDownloader.Downloaded> stage(
                final FrameworkUpdateDownloader downloader,
                final Consumer<FrameworkUpdateDownloader.Progress> progress) {
            return downloader.stage(candidate, context, installation, progress);
        }

        /**
         * Resumes a previously staged task of this request instead of downloading it again.
         *
         * @param taskId staged task id recorded by the earlier attempt
         * @param downloader downloader that owns the staged task store
         * @param progress progress consumer, may be {@code null}
         * @return completion carrying the recovered, re-verified staged download
         */
        public CompletableFuture<FrameworkUpdateDownloader.Downloaded> recover(
                final String taskId,
                final FrameworkUpdateDownloader downloader,
                final Consumer<FrameworkUpdateDownloader.Progress> progress) {
            return downloader.recover(taskId, candidate, context, installation, progress);
        }
    }

    @Override
    public Preferences preferences() {
        synchronized (lock) {
            // The stored override stays optional: an installation that never switched keeps following
            // its packaged channel, and that absence is what authorizes cross-channel admission.
            return new Preferences(preferences.automaticChecksEnabled(), preferences.selectedChannel());
        }
    }

    @Override
    public String updateChannel() {
        synchronized (lock) {
            return preferences.updateChannel();
        }
    }

    @Override
    public void start() {
        Snapshot toDeliver = null;
        long deliveryGeneration = -1;
        synchronized (lock) {
            if (closed || started) return;
            started = true;
            if (safeModeLocked()) {
                toDeliver = setSnapshotLocked(unavailableSnapshot(false));
                deliveryGeneration = generation;
            } else if (!preferences.automaticChecksEnabled()) {
                toDeliver = setSnapshotLocked(disabledSnapshot(false));
                deliveryGeneration = generation;
            } else {
                scheduleAutomaticLocked(sessionStartDelayLocked());
            }
        }
        deliver(toDeliver, deliveryGeneration);
    }

    @Override
    public CompletionStage<Snapshot> checkManual() {
        return request(true);
    }

    @Override
    public PreferenceSaveResult savePreferences(final Preferences requested) {
        Objects.requireNonNull(requested, "requested");
        Snapshot toDeliver = null;
        long deliveryGeneration = -1;
        synchronized (lock) {
            if (closed) return PreferenceSaveResult.failed("Automatic update checks are closed.");
            final String nextChannel = requested.selectedChannel().orElse(preferences.updateChannel());
            final boolean channelChanged = !preferences.updateChannel().equals(nextChannel);
            if (channelChanged) {
                final CoreFrameworkUpdates.Phase phase =
                        frameworkUpdates.snapshot().phase();
                if (phase == CoreFrameworkUpdates.Phase.DOWNLOADING
                        || phase == CoreFrameworkUpdates.Phase.APPLY_PENDING) {
                    return PreferenceSaveResult.failed(CHANNEL_SWITCH_BUSY_MESSAGE);
                }
            }
            final PreferenceSaveResult saved = preferencesStore.save(requested);
            if (!saved.saved()) return saved;
            preferences = new UpdatePreferencesStoreState(requested.automaticChecksEnabled(), Optional.of(nextChannel));
            if (channelChanged) {
                parser = new UpdateDiscoveryParser(nextChannel);
                stateStore = new UpdateStateStore(home, nextChannel, diagnostic);
                transport = Objects.requireNonNull(transportFactory.apply(nextChannel), "transport");
                lastRemindedIdentity = null;
                state = new UpdateStateStore.State(state.lastAutomaticAttempt(), Optional.empty(), Optional.empty());
                if (!stateStore.save(state).saved()) {
                    report("UPDATE_STATE_WRITE_FAILED");
                }
                generation++;
                final Snapshot reset = resetSnapshotLocked(requested.automaticChecksEnabled());
                if (inFlight != null) {
                    final CompletableFuture<Snapshot> cancelled = inFlight;
                    cancelTransportLocked();
                    inFlight = null;
                    inFlightAutomatic = false;
                    inFlightUserInitiated = false;
                    cancelled.complete(reset);
                }
                toDeliver = setSnapshotLocked(reset);
                deliveryGeneration = generation;
                if (!requested.automaticChecksEnabled()) {
                    cancelAutomaticTimerLocked();
                } else if (started && !safeModeLocked() && automaticTimer == null) {
                    // A channel switch is an explicit user request for that channel's news, not a session start.
                    scheduleAutomaticLocked(Duration.ZERO);
                }
            } else if (!requested.automaticChecksEnabled()) {
                cancelAutomaticTimerLocked();
                if (inFlight != null && inFlightAutomatic && !inFlightUserInitiated) {
                    generation++;
                    final CompletableFuture<Snapshot> cancelled = inFlight;
                    cancelTransportLocked();
                    inFlight = null;
                    inFlightAutomatic = false;
                    inFlightUserInitiated = false;
                    cancelled.complete(disabledSnapshot(false));
                    toDeliver = setSnapshotLocked(disabledSnapshot(false));
                    deliveryGeneration = generation;
                } else if (inFlight != null && inFlightUserInitiated) {
                    inFlightAutomatic = false;
                } else {
                    generation++;
                    toDeliver = setSnapshotLocked(disabledSnapshot(false));
                    deliveryGeneration = generation;
                }
            } else if (started && !safeModeLocked()) {
                if (snapshot.status() == Status.DISABLED) {
                    toDeliver = setSnapshotLocked(Snapshot.idle(installed.versionText()));
                    deliveryGeneration = generation;
                }
                if (automaticTimer == null && inFlight == null) {
                    scheduleAutomaticLocked(sessionStartDelayLocked());
                }
            }
        }
        deliver(toDeliver, deliveryGeneration);
        return PreferenceSaveResult.success();
    }

    @Override
    public Registration subscribe(final Consumer<Snapshot> listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.addIfAbsent(listener);
        final AtomicBoolean removed = new AtomicBoolean();
        return () -> {
            if (removed.compareAndSet(false, true)) listeners.remove(listener);
        };
    }

    @Override
    public void close() {
        CompletableFuture<Snapshot> pending = null;
        synchronized (lock) {
            if (closed) return;
            closed = true;
            generation++;
            cancelAutomaticTimerLocked();
            cancelTransportLocked();
            if (inFlight != null) {
                pending = inFlight;
                inFlight = null;
                inFlightAutomatic = false;
                inFlightUserInitiated = false;
                pending.complete(closedSnapshot());
            }
            snapshot = closedSnapshot();
            listeners.clear();
        }
        try {
            frameworkUpdates.close();
        } finally {
            executor.shutdownNow();
        }
    }

    private CompletionStage<Snapshot> request(final boolean manual) {
        CompletableFuture<Snapshot> result;
        Snapshot toDeliver = null;
        long deliveryGeneration = -1;
        boolean launch = false;
        long operationGeneration = -1;
        synchronized (lock) {
            if (closed) return CompletableFuture.completedFuture(closedSnapshot());
            if (safeModeLocked()) {
                toDeliver = setSnapshotLocked(unavailableSnapshot(manual));
                deliveryGeneration = generation;
                result = CompletableFuture.completedFuture(toDeliver);
            } else if (!manual && !preferences.automaticChecksEnabled()) {
                toDeliver = setSnapshotLocked(disabledSnapshot(false));
                deliveryGeneration = generation;
                result = CompletableFuture.completedFuture(toDeliver);
            } else if (installed.version().isEmpty()) {
                toDeliver = setSnapshotLocked(unavailableSnapshot(manual));
                deliveryGeneration = generation;
                result = CompletableFuture.completedFuture(toDeliver);
            } else if (inFlight != null) {
                if (!manual) {
                    inFlightAutomatic = true;
                    persistAutomaticAttemptLocked();
                }
                if (manual && !inFlightUserInitiated) {
                    inFlightUserInitiated = true;
                    toDeliver = setSnapshotLocked(checkingSnapshot(true));
                    deliveryGeneration = generation;
                }
                result = inFlight;
            } else {
                result = new CompletableFuture<>();
                inFlight = result;
                inFlightAutomatic = !manual;
                inFlightUserInitiated = manual;
                operationGeneration = generation;
                toDeliver = setSnapshotLocked(checkingSnapshot(manual));
                deliveryGeneration = generation;
                if (!manual && !persistAutomaticAttemptLocked()) {
                    final Snapshot unavailable = unavailableSnapshot(false);
                    inFlight = null;
                    inFlightAutomatic = false;
                    inFlightUserInitiated = false;
                    result.complete(unavailable);
                    toDeliver = setSnapshotLocked(unavailable);
                    deliveryGeneration = generation;
                } else {
                    launch = true;
                }
            }
        }
        deliver(toDeliver, deliveryGeneration);
        if (launch) {
            final CompletableFuture<Snapshot> submitted = result;
            final long submittedGeneration = operationGeneration;
            try {
                executor.execute(() -> performFetch(submittedGeneration, submitted));
            } catch (RuntimeException rejected) {
                finishFailure(submittedGeneration, submitted, "UPDATE_WORK_UNAVAILABLE");
            }
        }
        return result;
    }

    private void performFetch(final long operationGeneration, final CompletableFuture<Snapshot> future) {
        final UpdateTransport activeTransport;
        final Optional<String> etag;
        synchronized (lock) {
            if (closed || generation != operationGeneration || inFlight != future) return;
            activeTransport = transport;
            etag = state.etag();
        }
        try {
            final CompletionStage<UpdateTransport.Response> stage = activeTransport.fetch(etag);
            if (stage == null) throw new IllegalStateException("update transport returned null");
            final boolean accepted;
            synchronized (lock) {
                accepted = !closed && generation == operationGeneration && inFlight == future;
                if (accepted) transportStage = stage;
            }
            if (!accepted) {
                cancelTransportStage(stage);
                return;
            }
            stage.whenComplete((response, failure) -> {
                if (failure != null) finishFailure(operationGeneration, future, "UPDATE_NETWORK_UNAVAILABLE");
                else finishResponse(operationGeneration, future, response);
            });
        } catch (RuntimeException failure) {
            finishFailure(operationGeneration, future, "UPDATE_NETWORK_UNAVAILABLE");
        }
    }

    private void finishResponse(
            final long operationGeneration,
            final CompletableFuture<Snapshot> future,
            final UpdateTransport.Response response) {
        Snapshot result = null;
        long deliveryGeneration = -1;
        boolean automatic = false;
        synchronized (lock) {
            if (closed || generation != operationGeneration || inFlight != future) return;
            automatic = inFlightAutomatic;
            final boolean userInitiated = inFlightUserInitiated;
            inFlight = null;
            inFlightAutomatic = false;
            inFlightUserInitiated = false;
            transportStage = null;
            result = interpretResponseLocked(response, userInitiated);
            snapshot = result;
            future.complete(result);
            deliveryGeneration = generation;
            if (automatic && preferences.automaticChecksEnabled() && !safeModeLocked()) {
                scheduleAutomaticLocked(nextAutomaticDelayLocked());
            }
        }
        deliver(result, deliveryGeneration);
    }

    private void finishFailure(
            final long operationGeneration, final CompletableFuture<Snapshot> future, final String code) {
        Snapshot result;
        long deliveryGeneration;
        boolean automatic;
        synchronized (lock) {
            if (closed || generation != operationGeneration || inFlight != future) return;
            automatic = inFlightAutomatic;
            final boolean userInitiated = inFlightUserInitiated;
            inFlight = null;
            inFlightAutomatic = false;
            inFlightUserInitiated = false;
            transportStage = null;
            report(code);
            result = setSnapshotLocked(unavailableSnapshot(userInitiated));
            future.complete(result);
            deliveryGeneration = generation;
            if (automatic && preferences.automaticChecksEnabled() && !safeModeLocked()) {
                scheduleAutomaticLocked(nextAutomaticDelayLocked());
            }
        }
        deliver(result, deliveryGeneration);
    }

    private Snapshot interpretResponseLocked(final UpdateTransport.Response response, final boolean userInitiated) {
        if (response == null) {
            report("UPDATE_RESPONSE_INVALID");
            return unavailableSnapshot(userInitiated);
        }
        final byte[] body = response.body();
        if (body.length > UpdateDiscoveryParser.MAX_BYTES) {
            report("UPDATE_RESPONSE_TOO_LARGE");
            return unavailableSnapshot(userInitiated);
        }
        final Optional<UpdateDiscovery> discovery;
        if (response.statusCode() == 304) {
            discovery = state.cachedResult();
            if (discovery.isEmpty()) {
                report("UPDATE_CACHE_MISSING_FOR_304");
                return unavailableSnapshot(userInitiated);
            }
            if (response.etag().isPresent()) {
                state = new UpdateStateStore.State(state.lastAutomaticAttempt(), discovery, response.etag());
                final UpdateStateStore.SaveResult saved = stateStore.save(state);
                if (!saved.saved()) report("UPDATE_STATE_WRITE_FAILED");
            }
        } else if (response.statusCode() == 200) {
            try {
                discovery = Optional.of(parser.parse(body));
            } catch (IllegalArgumentException invalid) {
                report("UPDATE_DISCOVERY_INVALID");
                return unavailableSnapshot(userInitiated);
            }
            state = new UpdateStateStore.State(state.lastAutomaticAttempt(), discovery, response.etag());
            final UpdateStateStore.SaveResult saved = stateStore.save(state);
            if (!saved.saved()) report("UPDATE_STATE_WRITE_FAILED");
        } else {
            report("UPDATE_HTTP_UNAVAILABLE");
            return unavailableSnapshot(userInitiated);
        }
        return resultFor(discovery.orElseThrow(), userInitiated);
    }

    private Snapshot resultFor(final UpdateDiscovery discovery, final boolean userInitiated) {
        if (discovery.status() == UpdateDiscovery.Status.UNAVAILABLE) {
            report("UPDATE_CHANNEL_UNAVAILABLE");
            return unavailableSnapshot(userInitiated);
        }
        if (discovery.candidate().isEmpty()) {
            return new Snapshot(
                    Status.UP_TO_DATE,
                    installed.versionText(),
                    Optional.empty(),
                    OptionalLong.empty(),
                    userInitiated,
                    false);
        }
        final UpdateDiscovery.Candidate candidate = discovery.candidate().orElseThrow();
        if (!isNewer(candidate)) {
            final boolean installedCandidate = installed.channel().equals(discovery.channel())
                    && installed.version().filter(candidate.version()::equals).isPresent()
                    && installed.buildNumber().equals(candidate.buildNumber());
            return new Snapshot(
                    Status.UP_TO_DATE,
                    installed.versionText(),
                    Optional.empty(),
                    OptionalLong.empty(),
                    userInitiated,
                    false,
                    Optional.empty(),
                    Optional.empty(),
                    installedCandidate ? candidate.releaseNotes() : java.util.Map.of());
        }
        final String identity = candidate.identity();
        final boolean reminder = !identity.equals(lastRemindedIdentity);
        if (reminder) lastRemindedIdentity = identity;
        final Optional<UpdateDiscovery.Asset> matchedAsset = candidate.pickInstallerAsset(installed.variant());
        return new Snapshot(
                Status.UPDATE_AVAILABLE,
                installed.versionText(),
                Optional.of(candidate.versionText()),
                candidate.buildNumber(),
                userInitiated,
                reminder,
                matchedAsset.map(UpdateDiscovery.Asset::url),
                matchedAsset.map(UpdateDiscovery.Asset::name),
                candidate.releaseNotes());
    }

    /**
     * Decides whether the advertised candidate is newer than the installed build.
     *
     * <p>A recorded build number is authoritative when both sides have one: equal
     * numbers are never an update, and a lower number is never advertised as one.
     * When either side has no recorded number, comparison falls back to the
     * numeric version so legacy stable installations keep working without an
     * invented build number.</p>
     */
    private boolean isNewer(final UpdateDiscovery.Candidate candidate) {
        final UpdateVersion local = installed.version().orElse(null);
        if (local == null) return false;
        final int versionOrder = candidate.version().compareTo(local);
        if (versionOrder < 0) return false;
        if (versionOrder == 0
                && UpdateDiscovery.CHANNEL_STABLE.equals(installed.channel())
                && UpdateDiscovery.CHANNEL_BETA.equals(preferences.updateChannel())) {
            return false;
        }
        if (candidate.buildNumber().isPresent() && installed.buildNumber().isPresent()) {
            final long advertised = candidate.buildNumber().getAsLong();
            final long current = installed.buildNumber().getAsLong();
            if (advertised == current) {
                if (!candidate.version().equals(local)) {
                    report("UPDATE_BUILD_IDENTITY_CONFLICT");
                }
                return false;
            }
            return advertised > current;
        }
        if (versionOrder == 0
                && !UpdateDiscovery.CHANNEL_STABLE.equals(installed.channel())
                && UpdateDiscovery.CHANNEL_STABLE.equals(preferences.updateChannel())) {
            return true;
        }
        return versionOrder > 0;
    }

    private Snapshot resetSnapshotLocked(final boolean automaticChecksEnabled) {
        if (safeModeLocked()) return unavailableSnapshot(false);
        if (!automaticChecksEnabled) return disabledSnapshot(false);
        return Snapshot.idle(installed.versionText());
    }

    private boolean persistAutomaticAttemptLocked() {
        final Instant now = clock.instant();
        final UpdateStateStore.State requested =
                new UpdateStateStore.State(Optional.of(now), state.cachedResult(), state.etag());
        final UpdateStateStore.SaveResult saved = stateStore.save(requested);
        if (saved.saved()) {
            state = requested;
            return true;
        }
        report("UPDATE_STATE_WRITE_FAILED");
        return false;
    }

    private void scheduleAutomaticLocked(final Duration delay) {
        cancelAutomaticTimerLocked();
        if (closed || !started || !preferences.automaticChecksEnabled() || safeModeLocked()) return;
        final RuntimeTimerSubmission submission;
        try {
            submission = scheduler.schedule(delay, () -> request(false));
        } catch (RuntimeException rejected) {
            report("UPDATE_TIMER_REJECTED");
            return;
        }
        if (submission.accepted()) automaticTimer = submission.handle();
        else report("UPDATE_TIMER_REJECTED");
    }

    /**
     * Delay before the first automatic check of a session.
     *
     * <p>Every launch checks once, shortly after startup, rather than waiting out the remainder of
     * the interval from a previous session: a user who has just opened the editor should learn about
     * a new release then, not up to a day later. The interval still governs the cadence of further
     * automatic checks within the same session, which is what keeps a long-running editor from
     * polling the service.</p>
     */
    private Duration sessionStartDelayLocked() {
        return startupDelay;
    }

    private Duration nextAutomaticDelayLocked() {
        return state.lastAutomaticAttempt()
                .map(value -> remainingUntil(value.plus(automaticInterval), startupDelay))
                .orElse(startupDelay);
    }

    private Duration remainingUntil(final Instant target, final Duration dueDelay) {
        final Duration remaining = Duration.between(clock.instant(), target);
        return remaining.isNegative() || remaining.isZero() ? dueDelay : remaining;
    }

    private UpdateStateStore.State sanitizeState(final UpdateStateStore.State loaded) {
        final Optional<Instant> attempt = loaded.lastAutomaticAttempt();
        if (attempt.isPresent()) {
            final Instant now = clock.instant();
            if (attempt.orElseThrow().isAfter(now)) {
                report("UPDATE_STATE_FUTURE_TIMESTAMP");
                return new UpdateStateStore.State(Optional.empty(), loaded.cachedResult(), loaded.etag());
            }
        }
        return loaded;
    }

    private boolean safeModeLocked() {
        try {
            return runtimeSettings.read().safeMode();
        } catch (RuntimeException unavailable) {
            report("UPDATE_SETTINGS_UNAVAILABLE");
            return true;
        }
    }

    private void cancelAutomaticTimerLocked() {
        final RuntimeTimerHandle timer = automaticTimer;
        automaticTimer = null;
        if (timer != null) timer.cancel();
    }

    private void cancelTransportLocked() {
        final CompletionStage<UpdateTransport.Response> stage = transportStage;
        transportStage = null;
        cancelTransportStage(stage);
    }

    private void cancelTransportStage(final CompletionStage<?> stage) {
        if (stage instanceof java.util.concurrent.Future<?> future) {
            try {
                future.cancel(true);
            } catch (RuntimeException ignored) {
                // Some CompletionStage implementations expose a non-cancellable Future view.
            }
        }
    }

    private Snapshot setSnapshotLocked(final Snapshot value) {
        snapshot = Objects.requireNonNull(value, "value");
        return value;
    }

    private Snapshot checkingSnapshot(final boolean userInitiated) {
        return new Snapshot(
                Status.CHECKING, installed.versionText(), Optional.empty(), OptionalLong.empty(), userInitiated, false);
    }

    private Snapshot unavailableSnapshot(final boolean userInitiated) {
        return new Snapshot(
                Status.UNAVAILABLE,
                installed.versionText(),
                Optional.empty(),
                OptionalLong.empty(),
                userInitiated,
                false);
    }

    private Snapshot disabledSnapshot(final boolean userInitiated) {
        return new Snapshot(
                Status.DISABLED, installed.versionText(), Optional.empty(), OptionalLong.empty(), userInitiated, false);
    }

    private Snapshot closedSnapshot() {
        return new Snapshot(
                Status.CLOSED, installed.versionText(), Optional.empty(), OptionalLong.empty(), false, false);
    }

    private void deliver(final Snapshot value, final long expectedGeneration) {
        if (value == null || expectedGeneration < 0) return;
        final List<Consumer<Snapshot>> recipients;
        synchronized (lock) {
            if (closed || generation != expectedGeneration) return;
            recipients = new ArrayList<>(listeners);
        }
        for (Consumer<Snapshot> listener : recipients) {
            try {
                listener.accept(value);
            } catch (RuntimeException failure) {
                report("UPDATE_LISTENER_FAILED");
            }
        }
    }

    private void report(final String code) {
        try {
            diagnostic.accept(code);
        } catch (RuntimeException ignored) {
            // Diagnostics must not turn a bounded background operation into a host failure.
        }
    }

    private static Duration requireNonNegative(final Duration value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative()) throw new IllegalArgumentException(name + " must not be negative");
        return value;
    }

    private static Duration requirePositive(final Duration value, final String name) {
        requireNonNegative(value, name);
        if (value.isZero()) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private record UpdatePreferencesStoreState(boolean automaticChecksEnabled, Optional<String> selectedChannel) {
        String updateChannel() {
            return selectedChannel.orElse(CoreUpdateService.CHANNEL_STABLE);
        }
    }

    private static final class UpdateThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(final Runnable task) {
            return RuntimeThreads.daemonThreadsWithFixedName(
                            "turboism-update-check", RuntimeThreads.contextClassLoader(RuntimeUpdateService.class))
                    .newThread(task);
        }
    }
}
