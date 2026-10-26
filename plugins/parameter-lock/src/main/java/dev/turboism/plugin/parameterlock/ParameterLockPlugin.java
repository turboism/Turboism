package dev.turboism.plugin.parameterlock;

import dev.turboism.sdk.cubism.CubismPlugin;
import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.ProjectContentSnapshot;
import dev.turboism.sdk.cubism.id.ParameterGroupId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.ParameterGroup;
import dev.turboism.sdk.cubism.model.ParameterGroups;
import dev.turboism.sdk.cubism.model.Parameters;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.UiScheduler;
import dev.turboism.sdk.ui.appearance.PaletteEntry;
import dev.turboism.sdk.ui.appearance.PaletteRowAction;
import dev.turboism.sdk.ui.resource.CubismIcon;
import dev.turboism.sdk.ui.resource.UiIconAvailability;
import dev.turboism.sdk.ui.resource.UiIconRef;
import dev.turboism.sdk.ui.resource.UiResourceService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Official Parameter Lock Plugin.
 *
 * <p>Protects Cubism parameter values from accidental slider manipulation, input,
 * or script mutation by returning the current unmodified value in {@link #beforeSetParameterValue}.</p>
 *
 * <p>Each parameter and parameter group row in the Parameters palette features an independent
 * native lock icon (matching Parts/Deformer row behavior). Parameter group rows recursively
 * control all descendant parameters across nested sub-groups, with cycle prevention. Context-menu
 * actions on the Parameter Tab remain supported as secondary entry points.</p>
 *
 * <p>Host model discovery and UI row action registrations are strictly dispatched on the
 * {@link UiScheduler} thread, while storage persistence runs on a background worker. Periodic
 * activation checks detect model tab switches and document generation changes cleanly using stable
 * document and model identities, without redundant per-tick override rebuilds.</p>
 *
 * <p>Locks are persisted across editor sessions in {@code PluginStorage} under {@code StorageRoot.STATE},
 * keyed by the native model GUID ({@code CModelSource.guid}). Copies created via "Save As" retain
 * the native GUID by host design and thus share the lock configuration.</p>
 */
public final class ParameterLockPlugin implements CubismPlugin {
    @Deprecated(forRemoval = true)
    public static final String TOOLBAR_TOGGLE_ID = "parameter-lock.toolbar.toggle";

    private static final long POLL_INTERVAL_MS = 250;

    private PluginContext context;
    private PluginLogger logger;
    private PluginLocalization i18n;
    private volatile ParameterLockState lockState;
    private ParameterLockStorage storage;
    private ScheduledExecutorService asyncExecutor;
    private final List<Registration> registrations = new ArrayList<>();
    private final Map<String, Registration> activeRowRegistrations = new ConcurrentHashMap<>();
    private final AtomicBoolean enabled = new AtomicBoolean(false);
    private final AtomicLong epoch = new AtomicLong(0);
    private volatile Map<String, CompletableFuture<ParameterLockData>> activeLoads = new ConcurrentHashMap<>();
    private final Set<CompletableFuture<?>> pendingSaves = ConcurrentHashMap.newKeySet();
    private volatile CompletableFuture<Void> activationReady = CompletableFuture.completedFuture(null);

    private Registration pollRegistration;
    private volatile ActivationKey currentActivationKey;
    private volatile boolean fullyDecorated = false;

    /**
     * Stable activation identity based on document and model IDs rather than host wrappers.
     * Host accessors like DynamicCubismModelAccess.active() instantiate a new SessionModel wrapper
     * on every read, so wrapper object identity must not participate in activation checks.
     */
    record ActivationKey(String documentId, String contentId, String modelGuid) {
        ActivationKey {
            documentId = documentId == null ? "" : documentId;
            contentId = contentId == null ? "" : contentId;
            modelGuid = modelGuid == null ? "" : modelGuid;
        }
    }

    @Override
    public void init(final PluginContext context) {
        this.context = Objects.requireNonNull(context, "context");
        this.logger = context.logger();
        this.i18n = context.localization();
        this.asyncExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "parameter-lock-worker");
            thread.setDaemon(true);
            return thread;
        });
        this.lockState = new ParameterLockState();
        this.storage = new ParameterLockStorage(context.storage(), logger, asyncExecutor);
        context.disposableScope().register(this::clearRowDecorations);
        logger.info("ParameterLockPlugin initialized");
    }

    @Override
    public void enable() {
        if (!enabled.compareAndSet(false, true)) {
            return;
        }
        final long currentEpoch = epoch.incrementAndGet();
        activationReady = CompletableFuture.allOf(pendingSaves.toArray(CompletableFuture[]::new))
                .handle((ignored, failure) -> null);
        lockState = new ParameterLockState();
        activeLoads = new ConcurrentHashMap<>();
        currentActivationKey = null;
        fullyDecorated = false;

        // Schedule single initial activation check and poll chain on UI thread
        dispatchToUi(() -> {
            if (!enabled.get() || epoch.get() != currentEpoch) {
                return;
            }
            checkActivationOnce(currentEpoch);
            schedulePoll(currentEpoch, POLL_INTERVAL_MS);
        });
        logger.info("ParameterLockPlugin enabled");
    }

    @Override
    public void disable() {
        if (!enabled.compareAndSet(true, false)) {
            return;
        }
        epoch.incrementAndGet();

        cancelScheduledPoll();

        clearRowDecorations();
        currentActivationKey = null;
        fullyDecorated = false;

        synchronized (registrations) {
            for (final Registration registration : registrations) {
                try {
                    registration.close();
                } catch (RuntimeException ignored) {
                }
            }
            registrations.clear();
        }
        logger.info("ParameterLockPlugin disabled: enforcement cleared, saved lock files preserved");
    }

    @Override
    public void shutdown() {
        disable();
        if (asyncExecutor != null) {
            try {
                CompletableFuture.allOf(pendingSaves.toArray(CompletableFuture[]::new))
                        .get(2, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
                logger.warn("Parameter lock persistence did not finish before shutdown: " + failure.getMessage());
            } finally {
                asyncExecutor.shutdown();
            }
        }
        logger.info("ParameterLockPlugin shutdown");
    }

    // ── Parameter Mutation Interception ──

    @Override
    public float beforeSetParameterValue(final Parameter parameter, final float value) {
        if (!enabled.get() || parameter == null) {
            return value;
        }
        final String currentModelGuid = resolveCurrentActiveModelGuid();
        if (currentModelGuid == null) {
            return value;
        }
        if (!lockState.hasLoaded(currentModelGuid)) {
            triggerAsyncPreload(currentModelGuid);
            return parameter.getValue();
        }
        final String paramId = parameter.id().value();
        if (lockState.isLocked(currentModelGuid, paramId)) {
            return parameter.getValue();
        }
        return value;
    }

    // ── Model Lifecycle Hooks ──

    @Override
    public void onModelOpened(final ProjectContentSnapshot model) {
        handleModelEvent();
    }

    @Override
    public void onModelCreated(final ProjectContentSnapshot model) {
        handleModelEvent();
    }

    @Override
    public void onModelClosed(final ProjectContentSnapshot model) {
        handleModelEvent();
    }

    private void handleModelEvent() {
        if (!enabled.get()) {
            return;
        }
        final long currentEpoch = epoch.get();
        dispatchToUi(() -> {
            if (!enabled.get() || epoch.get() != currentEpoch) {
                return;
            }
            checkActivationOnce(currentEpoch);
            schedulePoll(currentEpoch, POLL_INTERVAL_MS);
        });
    }

    // ── Internal Accessors for Tests & Integration ──

    ParameterLockState lockState() {
        return lockState;
    }

    ParameterLockStorage storage() {
        return storage;
    }

    boolean isParameterLocked(final String modelGuid, final String parameterId) {
        return lockState.isLocked(modelGuid, parameterId);
    }

    Map<String, Registration> activeRowRegistrations() {
        return Collections.unmodifiableMap(activeRowRegistrations);
    }

    boolean isFullyDecorated() {
        return fullyDecorated;
    }

    ActivationKey currentActivationKey() {
        return currentActivationKey;
    }

    UiIconAvailability lockIconAvailability() {
        if (context == null) {
            return UiIconAvailability.SERVICE_UNAVAILABLE;
        }
        final UiResourceService resources =
                context.services().find(UiResourceService.class).orElse(null);
        if (resources == null) {
            return UiIconAvailability.SERVICE_UNAVAILABLE;
        }
        return resources.availability(resources.cubismIcon(CubismIcon.LOCK));
    }

    // ── Activation Checking & Lifecycle Coordination ──

    void checkActivation() {
        checkActivationOnce(epoch.get());
    }

    void checkActivationOnce() {
        checkActivationOnce(epoch.get());
    }

    void checkActivationOnce(final long currentEpoch) {
        if (!enabled.get() || epoch.get() != currentEpoch) {
            return;
        }
        final Optional<CubismModel> modelOpt = activeModelOrNull();
        if (modelOpt.isEmpty()) {
            if (currentActivationKey != null) {
                clearRowDecorations();
                currentActivationKey = null;
                fullyDecorated = false;
            }
            return;
        }

        final CubismModel model = modelOpt.get();
        final ActivationKey key = captureActivationKey(model);
        if (key == null) {
            // Document identity not ready yet; defer decoration to avoid registering against stale scope
            if (currentActivationKey != null) {
                clearRowDecorations();
                currentActivationKey = null;
            }
            fullyDecorated = false;
            return;
        }

        if (!Objects.equals(key, currentActivationKey)) {
            clearRowDecorations();
            currentActivationKey = key;
            fullyDecorated = false;
            loadAndDecorateActiveModel(model, key, currentEpoch);
        } else if (!fullyDecorated) {
            loadAndDecorateActiveModel(model, key, currentEpoch);
        }
    }

    private synchronized void cancelScheduledPoll() {
        if (pollRegistration != null) {
            try {
                pollRegistration.close();
            } catch (RuntimeException ignored) {
            }
            pollRegistration = null;
        }
    }

    private synchronized void schedulePoll(final long currentEpoch, final long delayMs) {
        if (!enabled.get() || epoch.get() != currentEpoch) {
            return;
        }
        cancelScheduledPoll();
        final PluginContext currentContext = this.context;
        if (currentContext == null) {
            return;
        }
        final UiScheduler scheduler = currentContext.uiScheduler();
        if (scheduler != null) {
            pollRegistration = scheduler.runOnUiThreadLater(() -> onPollTick(currentEpoch), Duration.ofMillis(delayMs));
        }
    }

    private void onPollTick(final long currentEpoch) {
        if (!enabled.get() || epoch.get() != currentEpoch) {
            return;
        }
        checkActivationOnce(currentEpoch);
        schedulePoll(currentEpoch, POLL_INTERVAL_MS);
    }

    private void loadAndDecorateActiveModel(final CubismModel model, final ActivationKey key, final long currentEpoch) {
        if (!enabled.get() || epoch.get() != currentEpoch || model == null) {
            return;
        }
        final String modelGuid = model.id().value();
        lockState.setActiveModel(modelGuid);

        if (lockState.hasLoaded(modelGuid)) {
            refreshDecorations(model);
        } else {
            ensureLoaded(modelGuid)
                    .thenAcceptAsync(
                            data -> {
                                dispatchToUi(() -> {
                                    if (!enabled.get() || epoch.get() != currentEpoch) {
                                        return;
                                    }
                                    final Optional<CubismModel> active = activeModelOrNull();
                                    if (active.isPresent()) {
                                        final ActivationKey currentKey = captureActivationKey(active.get());
                                        if (Objects.equals(currentKey, key)) {
                                            refreshDecorations(active.get());
                                        }
                                    }
                                });
                            },
                            asyncExecutor);
        }
    }

    private ActivationKey captureActivationKey(final CubismModel model) {
        if (model == null) {
            return null;
        }
        final String modelGuid = model.id() != null ? model.id().value() : "";
        if (modelGuid.isBlank()) {
            return null;
        }
        String docId = "";
        String contentId = "";
        if (context != null) {
            try {
                final Optional<DocumentSnapshot> docOpt = context.cubism().activeDocument();
                if (docOpt.isPresent()) {
                    final DocumentSnapshot doc = docOpt.get();
                    docId = doc.documentId();
                    contentId = doc.contentId().orElse("");
                } else {
                    // Document is not ready yet; defer activation to avoid decorating against stale scope
                    return null;
                }
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        return new ActivationKey(docId, contentId, modelGuid);
    }

    // ── Row Leading Action Decoration & Group Hierarchy ──

    void refreshDecorations(final CubismModel model) {
        if (!enabled.get() || model == null) {
            return;
        }
        final Optional<CubismModel> active = activeModelOrNull();
        if (active.isEmpty() || !active.get().id().value().equals(model.id().value())) {
            return;
        }
        final String modelGuid = model.id().value();
        final Set<String> activeKeys = new HashSet<>();
        boolean allSucceeded = true;
        int rowsAttempted = 0;

        // 1. Decorate parameters
        try {
            final Parameters parameters = model.parameters();
            if (parameters != null) {
                for (final Parameter parameter : parameters.all()) {
                    rowsAttempted++;
                    final String paramId = parameter.id().value();
                    final String key = "param:" + paramId;
                    activeKeys.add(key);

                    final Optional<PaletteEntry> entryOpt;
                    try {
                        entryOpt = parameter.ui().parameterPaletteEntry();
                    } catch (RuntimeException failure) {
                        logger.debug(
                                "Failed to read parameter palette entry for " + paramId + ": " + failure.getMessage());
                        allSucceeded = false;
                        continue;
                    }
                    if (entryOpt.isEmpty()) {
                        allSucceeded = false;
                        continue;
                    }
                    final PaletteEntry entry = entryOpt.get();
                    final boolean locked = lockState.isLocked(modelGuid, paramId);
                    final PaletteRowAction.State state =
                            locked ? PaletteRowAction.State.LOCKED : PaletteRowAction.State.UNLOCKED;
                    final String tooltip =
                            locked ? i18n.text("row.action.unlock_parameter") : i18n.text("row.action.lock_parameter");
                    final PaletteRowAction action = new PaletteRowAction(
                            new UiIconRef(CubismIcon.LOCK),
                            state,
                            tooltip,
                            () -> handleParameterClick(model, paramId),
                            locked);

                    try {
                        final Registration newReg = entry.overrideLeadingAction(action);
                        final Registration oldReg = activeRowRegistrations.put(key, newReg);
                        if (oldReg != null) {
                            try {
                                oldReg.close();
                            } catch (RuntimeException ignored) {
                            }
                        }
                    } catch (RuntimeException failure) {
                        allSucceeded = false;
                        logger.warn("Failed to register leading action on parameter " + paramId + ": "
                                + failure.getMessage());
                    }
                }
            }
        } catch (RuntimeException failure) {
            allSucceeded = false;
            logger.debug("Failed to query model parameters: " + failure.getMessage());
        }

        // 2. Decorate parameter groups
        try {
            final ParameterGroups groups = model.parameterGroups();
            if (groups != null) {
                for (final ParameterGroup group : groups.all()) {
                    rowsAttempted++;
                    final String groupId = group.id().value();
                    final String key = "group:" + groupId;
                    activeKeys.add(key);

                    final Optional<PaletteEntry> entryOpt;
                    try {
                        entryOpt = group.ui().parameterPaletteEntry();
                    } catch (RuntimeException failure) {
                        logger.debug("Failed to read group palette entry for " + groupId + ": " + failure.getMessage());
                        allSucceeded = false;
                        continue;
                    }
                    if (entryOpt.isEmpty()) {
                        allSucceeded = false;
                        continue;
                    }
                    final PaletteEntry entry = entryOpt.get();
                    final Set<String> descendantIds = collectDescendantParameterIds(model, group.id());
                    final PaletteRowAction.State state;
                    final String tooltip;
                    final Runnable onClick;

                    if (descendantIds.isEmpty()) {
                        state = PaletteRowAction.State.UNLOCKED;
                        tooltip = i18n.text("row.action.empty_group");
                        onClick = () -> {};
                    } else {
                        long lockedCount = 0;
                        for (final String descId : descendantIds) {
                            if (lockState.isLocked(modelGuid, descId)) {
                                lockedCount++;
                            }
                        }
                        if (lockedCount == 0) {
                            state = PaletteRowAction.State.UNLOCKED;
                            tooltip = i18n.text("row.action.lock_all");
                            onClick = () -> handleGroupClick(model, group.id(), true);
                        } else if (lockedCount == descendantIds.size()) {
                            state = PaletteRowAction.State.LOCKED;
                            tooltip = i18n.text("row.action.unlock_all");
                            onClick = () -> handleGroupClick(model, group.id(), false);
                        } else {
                            state = PaletteRowAction.State.MIXED;
                            tooltip = i18n.text("row.action.lock_all");
                            onClick = () -> handleGroupClick(model, group.id(), true);
                        }
                    }

                    final PaletteRowAction action =
                            new PaletteRowAction(new UiIconRef(CubismIcon.LOCK), state, tooltip, onClick);

                    try {
                        final Registration newReg = entry.overrideLeadingAction(action);
                        final Registration oldReg = activeRowRegistrations.put(key, newReg);
                        if (oldReg != null) {
                            try {
                                oldReg.close();
                            } catch (RuntimeException ignored) {
                            }
                        }
                    } catch (RuntimeException failure) {
                        allSucceeded = false;
                        logger.warn(
                                "Failed to register leading action on group " + groupId + ": " + failure.getMessage());
                    }
                }
            }
        } catch (RuntimeException failure) {
            allSucceeded = false;
            logger.debug("Failed to query model parameter groups: " + failure.getMessage());
        }

        // 3. Remove stale registrations
        final Iterator<Map.Entry<String, Registration>> it =
                activeRowRegistrations.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry<String, Registration> entry = it.next();
            if (!activeKeys.contains(entry.getKey())) {
                try {
                    entry.getValue().close();
                } catch (RuntimeException ignored) {
                }
                it.remove();
            }
        }

        this.fullyDecorated = allSucceeded && (rowsAttempted == 0 || !activeRowRegistrations.isEmpty());
    }

    private void clearRowDecorations() {
        for (final Registration reg : activeRowRegistrations.values()) {
            if (reg != null) {
                try {
                    reg.close();
                } catch (RuntimeException ignored) {
                }
            }
        }
        activeRowRegistrations.clear();
    }

    void handleParameterClick(final CubismModel model, final String paramId) {
        if (!enabled.get() || model == null || paramId == null) {
            return;
        }
        final String modelGuid = model.id().value();
        final boolean newlyLocked = lockState.toggleLock(modelGuid, paramId);
        saveLocksAsync(modelGuid);
        logger.info("Toggled parameter lock for " + paramId + " on model " + modelGuid + ": locked=" + newlyLocked);
        dispatchToUi(() -> refreshDecorations(model));
    }

    private void handleGroupClick(final CubismModel model, final ParameterGroupId groupId, final boolean lock) {
        if (!enabled.get() || model == null || groupId == null) {
            return;
        }
        final String modelGuid = model.id().value();
        final Set<String> descendantIds = collectDescendantParameterIds(model, groupId);
        if (descendantIds.isEmpty()) {
            return;
        }
        if (lock) {
            lockState.lockAll(modelGuid, descendantIds);
            logger.info("Locked all descendant parameters for group " + groupId.value() + " on model " + modelGuid
                    + ": " + descendantIds);
        } else {
            lockState.unlockAll(modelGuid, descendantIds);
            logger.info("Unlocked all descendant parameters for group " + groupId.value() + " on model " + modelGuid
                    + ": " + descendantIds);
        }
        saveLocksAsync(modelGuid);
        dispatchToUi(() -> refreshDecorations(model));
    }

    static Set<String> collectDescendantParameterIds(final CubismModel model, final ParameterGroupId groupId) {
        if (model == null || groupId == null) {
            return Set.of();
        }
        final Set<String> result = new LinkedHashSet<>();
        final Set<ParameterGroupId> visited = new HashSet<>();
        collectGroupParametersRecursively(model, groupId, visited, result);
        return Collections.unmodifiableSet(result);
    }

    private static void collectGroupParametersRecursively(
            final CubismModel model,
            final ParameterGroupId groupId,
            final Set<ParameterGroupId> visited,
            final Set<String> accumulator) {
        if (groupId == null || !visited.add(groupId)) {
            return;
        }
        final ParameterGroup group;
        try {
            group = model.parameterGroups().find(groupId);
        } catch (RuntimeException notFound) {
            return;
        }
        if (group == null) {
            return;
        }
        try {
            final List<ParameterId> paramIds = group.parameterIds();
            if (paramIds != null) {
                for (final ParameterId paramId : paramIds) {
                    if (paramId != null
                            && paramId.value() != null
                            && !paramId.value().isBlank()) {
                        accumulator.add(paramId.value());
                    }
                }
            }
        } catch (RuntimeException ignored) {
        }
        try {
            final List<ParameterGroupId> childGroupIds = group.childGroupIds();
            if (childGroupIds != null) {
                for (final ParameterGroupId childId : childGroupIds) {
                    collectGroupParametersRecursively(model, childId, visited, accumulator);
                }
            }
        } catch (RuntimeException ignored) {
        }
    }

    private void dispatchToUi(final Runnable task) {
        if (!enabled.get()) {
            return;
        }
        final PluginContext currentContext = this.context;
        if (currentContext == null) {
            return;
        }
        final UiScheduler scheduler = currentContext.uiScheduler();
        if (scheduler != null) {
            scheduler.runOnUiThread(() -> {
                if (enabled.get()) {
                    task.run();
                }
            });
        } else {
            task.run();
        }
    }

    // ── Internal Helpers ──

    private void triggerAsyncPreload(final String modelGuid) {
        ensureLoaded(modelGuid);
    }

    private CompletableFuture<ParameterLockData> ensureLoaded(final String modelGuid) {
        return ensureLoaded(modelGuid, lockState, activeLoads);
    }

    private CompletableFuture<ParameterLockData> ensureLoaded(
            final String modelGuid,
            final ParameterLockState state,
            final Map<String, CompletableFuture<ParameterLockData>> loads) {
        if (state.hasLoaded(modelGuid)) {
            return CompletableFuture.completedFuture(
                    ParameterLockData.of(modelGuid, state.getLockedParameters(modelGuid)));
        }
        // Capture known parameter IDs from current active model in memory if active matches modelGuid
        final Set<String> knownParamIds = captureModelParameterIds(modelGuid);

        return loads.computeIfAbsent(modelGuid, guid -> {
            final CompletableFuture<ParameterLockData> outcome = new CompletableFuture<>();
            final CompletableFuture<Void> ready = activationReady;
            // Pure disk IO runs on asyncExecutor; outcome completes without depending on EDT
            ready.thenComposeAsync(ignored -> storage.load(guid), asyncExecutor)
                    .thenAcceptAsync(
                            data -> {
                                final Set<String> candidateIds = data != null ? data.lockedParameterIds() : Set.of();
                                final Set<String> validIds = !knownParamIds.isEmpty()
                                        ? filterCandidateIds(knownParamIds, candidateIds)
                                        : candidateIds;
                                state.mergeLoadedLocks(guid, validIds);
                                outcome.complete(data);
                                loads.remove(guid, outcome);
                            },
                            asyncExecutor)
                    .exceptionally(failure -> {
                        logger.warn("Could not load parameter lock state: " + failure.getMessage());
                        outcome.completeExceptionally(failure);
                        loads.remove(guid, outcome);
                        return null;
                    });
            return outcome;
        });
    }

    private Set<String> captureModelParameterIds(final String targetModelGuid) {
        final Optional<CubismModel> active = activeModelOrNull();
        if (active.isPresent()
                && active.get().id() != null
                && active.get().id().value().equals(targetModelGuid)) {
            try {
                final Parameters params = active.get().parameters();
                if (params != null) {
                    final Set<String> ids = new HashSet<>();
                    for (final Parameter p : params.all()) {
                        if (p != null && p.id() != null) {
                            ids.add(p.id().value());
                        }
                    }
                    return Collections.unmodifiableSet(ids);
                }
            } catch (RuntimeException ignored) {
            }
        }
        return Set.of();
    }

    private static Set<String> filterCandidateIds(final Set<String> validModelIds, final Set<String> candidateIds) {
        if (candidateIds == null || candidateIds.isEmpty()) {
            return Set.of();
        }
        final Set<String> filtered = new LinkedHashSet<>();
        for (final String id : candidateIds) {
            if (validModelIds.contains(id)) {
                filtered.add(id);
            }
        }
        return Collections.unmodifiableSet(filtered);
    }

    private void saveLocksAsync(final String modelGuid) {
        final ParameterLockState state = lockState;
        final Map<String, CompletableFuture<ParameterLockData>> loads = activeLoads;
        final CompletableFuture<Boolean> save = ensureLoaded(modelGuid, state, loads)
                .thenComposeAsync(
                        loaded -> storage.save(ParameterLockData.of(modelGuid, state.getLockedParameters(modelGuid))),
                        asyncExecutor)
                .toCompletableFuture();
        trackSave(save, modelGuid);
    }

    private void trackSave(final CompletableFuture<Boolean> save, final String modelGuid) {
        pendingSaves.add(save);
        save.whenComplete((success, failure) -> {
            pendingSaves.remove(save);
            if (failure != null || !Boolean.TRUE.equals(success)) {
                logger.warn("Could not save parameter lock state for model " + modelGuid);
            }
        });
    }

    private String resolveCurrentActiveModelGuid() {
        final Optional<CubismModel> model = activeModelOrNull();
        if (model.isPresent()) {
            final String guid = model.get().id().value();
            lockState.setActiveModel(guid);
            return guid;
        }
        return null;
    }

    private Optional<CubismModel> activeModelOrNull() {
        if (context == null) {
            return Optional.empty();
        }
        try {
            final CubismModelAccess access = context.cubism().model();
            if (access != null && access.isAvailable()) {
                return Optional.of(access.active());
            }
        } catch (RuntimeException ignored) {
        }
        return Optional.empty();
    }
}
