package dev.turboism.plugin.parametersync;

import dev.turboism.plugin.parametersync.domain.ParameterSyncData;
import dev.turboism.plugin.parametersync.domain.SyncRuleSet;
import dev.turboism.plugin.parametersync.service.ParameterSyncEngine;
import dev.turboism.plugin.parametersync.service.ParameterSyncStorage;
import dev.turboism.plugin.parametersync.ui.ParameterSyncDialog;
import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.cubism.CubismPlugin;
import dev.turboism.sdk.cubism.ProjectContentSnapshot;
import dev.turboism.sdk.cubism.SelectionSnapshot;
import dev.turboism.sdk.cubism.event.ParameterValueEvent;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.event.SubscribeEvent;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.StatusNotification;
import dev.turboism.sdk.ui.UiHostCapabilityService;
import dev.turboism.sdk.ui.UiScheduler;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import dev.turboism.sdk.ui.context.ContextMenuRegistry.ContextMenuContribution;
import dev.turboism.sdk.ui.context.ContextMenuRegistry.Location;
import dev.turboism.sdk.ui.context.ContextMenuRegistry.ObjectKind;
import dev.turboism.sdk.ui.context.ContextMenuSelection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Parameter Sync Plugin entrypoint.
 *
 * <p>Enables real-time synchronized value propagation between Cubism parameters
 * according to user-defined mapping rules, with cross-session persistence and
 * re-entrancy cycle prevention.</p>
 */
public final class ParameterSyncPlugin implements CubismPlugin {

    public static final String ACTION_OPEN_SYNC = "parameter-sync.open";
    public static final String ACTION_OPEN_MANAGER = "parameter-sync.open-manager";
    public static final String ACTION_QUICK_SYNC = "parameter-sync.quick-sync";
    public static final String ACTION_TOGGLE_GLOBAL = "parameter-sync.toggle-global";

    public static final String CONTEXT_MENU_SYNC_ID = "parameter-sync.menu.sync";
    public static final String CONTEXT_MENU_MANAGER_ID = "parameter-sync.menu.manager";
    public static final String CONTEXT_MENU_QUICK_SYNC_ID = "parameter-sync.menu.quick-sync";

    private static final int MENU_PRIORITY = 85;

    private PluginContext context;
    private PluginLogger logger;
    private PluginLocalization i18n;
    private ScheduledExecutorService asyncExecutor;
    private ParameterSyncStorage storage;
    private ParameterSyncEngine engine;

    private final Map<String, SyncRuleSet> modelRuleSets = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<SyncRuleSet>> activeLoads = new ConcurrentHashMap<>();
    private final Set<String> loadedGuids = ConcurrentHashMap.newKeySet();
    private final List<Registration> registrations = new ArrayList<>();
    private final AtomicBoolean enabled = new AtomicBoolean(false);

    private final ConcurrentHashMap<String, Float> pendingTargetValues = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Float> lastAppliedValues = new ConcurrentHashMap<>();
    private final AtomicBoolean syncScheduled = new AtomicBoolean(false);
    private final Map<String, Parameter> parameterCache = new ConcurrentHashMap<>();
    private volatile CubismModel cachedActiveModel;

    @Override
    public void init(final PluginContext context) {
        this.context = Objects.requireNonNull(context, "context");
        this.logger = context.logger();
        this.i18n = context.localization();
        this.asyncExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "parameter-sync-worker");
            thread.setDaemon(true);
            return thread;
        });
        this.storage = new ParameterSyncStorage(context.storage(), logger, asyncExecutor);
        this.engine = new ParameterSyncEngine();
        logger.info("ParameterSyncPlugin initialized");
    }

    @Override
    public void enable() {
        if (!enabled.compareAndSet(false, true)) {
            return;
        }

        registerAction(ACTION_OPEN_SYNC, i18n.text("action.open.sync"), this::handleOpenSync);
        registerAction(ACTION_OPEN_MANAGER, i18n.text("action.open.sync"), this::handleOpenSync);
        registerAction(ACTION_QUICK_SYNC, i18n.text("action.open.sync"), this::handleOpenSync);
        registerAction(ACTION_TOGGLE_GLOBAL, i18n.text("action.toggle.global"), this::handleToggleGlobal);

        registerContextMenu(CONTEXT_MENU_SYNC_ID, ACTION_OPEN_SYNC, i18n.text("menu.parameter.sync"), MENU_PRIORITY);

        cachedActiveModel = null;
        parameterCache.clear();
        pendingTargetValues.clear();
        lastAppliedValues.clear();
        syncScheduled.set(false);
        preloadActiveModel();
        logger.info("ParameterSyncPlugin enabled");
    }

    @Override
    public void disable() {
        if (!enabled.compareAndSet(true, false)) {
            return;
        }

        cachedActiveModel = null;
        parameterCache.clear();
        pendingTargetValues.clear();
        lastAppliedValues.clear();
        syncScheduled.set(false);

        synchronized (registrations) {
            for (final Registration registration : registrations) {
                try {
                    registration.close();
                } catch (RuntimeException ignored) {
                }
            }
            registrations.clear();
        }
        logger.info("ParameterSyncPlugin disabled");
    }

    @Override
    public void shutdown() {
        disable();
        if (asyncExecutor != null) {
            asyncExecutor.shutdown();
        }
        logger.info("ParameterSyncPlugin shutdown");
    }

    // ── First-Class Turboism Event System Subscription ──

    /**
     * Subscribes to typed parameter value change events published by the runtime event broker.
     */
    @SubscribeEvent
    public void onParameterChanged(final ParameterValueEvent.On event) {
        if (!enabled.get() || event == null) {
            return;
        }
        final Parameter parameter = event.parameter();
        if (parameter == null) {
            return;
        }
        handleParameterValueChanged(parameter, event.newValue());
    }

    /**
     * Fallback for legacy hook invocation or direct testing.
     */
    @Override
    public void onParameterValueChanged(final Parameter parameter, final float oldValue, final float newValue) {
        if (!enabled.get() || parameter == null) {
            return;
        }
        handleParameterValueChanged(parameter, newValue);
    }

    private void handleParameterValueChanged(final Parameter parameter, final float newValue) {
        final String paramId = parameter.id().value();

        // 1. Programmatic write suppression: ignore events originating from our own sync writes
        if (engine.suppressionGuard().shouldSuppress(paramId, newValue)) {
            return;
        }

        // 2. Fast active model and rule check (cached, zero full-runtime snapshotting)
        final CubismModel model = getOrResolveActiveModel();
        if (model == null) {
            return;
        }
        final String modelGuid = model.id().value();
        final SyncRuleSet ruleSet = modelRuleSets.get(modelGuid);
        if (ruleSet == null || !ruleSet.isGlobalEnabled()) {
            return;
        }
        if (ruleSet.findActiveRulesForParameter(paramId).isEmpty()) {
            return;
        }

        // 3. Plan sync in-memory (pure math, ~1 microsecond)
        final ParameterSyncEngine.SyncPlan plan =
                engine.planSync(paramId, newValue, ruleSet, id -> lookupCachedParameter(model, id));
        if (plan.isEmpty()) {
            return;
        }

        // 4. Conflate target values
        pendingTargetValues.putAll(plan.plannedValues());

        // 5. Schedule conflated flush on UI thread
        if (syncScheduled.compareAndSet(false, true)) {
            dispatchToUi(this::flushPendingSync);
        }
    }

    private void flushPendingSync() {
        try {
            if (!enabled.get()) {
                pendingTargetValues.clear();
                return;
            }
            final CubismModel model = getOrResolveActiveModel();
            if (model == null) {
                pendingTargetValues.clear();
                return;
            }

            for (final String targetId : new ArrayList<>(pendingTargetValues.keySet())) {
                final Float targetVal = pendingTargetValues.remove(targetId);
                if (targetVal != null) {
                    final Optional<Parameter> targetOpt = lookupCachedParameter(model, targetId);
                    if (targetOpt.isPresent()) {
                        final Parameter targetParam = targetOpt.get();
                        final Float lastVal = lastAppliedValues.get(targetId);
                        if (lastVal != null && Math.abs(lastVal - targetVal) < 1e-4f) {
                            continue;
                        }
                        lastAppliedValues.put(targetId, targetVal);
                        engine.suppressionGuard().markProgrammaticWrite(targetId, targetVal);
                        try {
                            targetParam.setValue(targetVal);
                        } catch (RuntimeException ignored) {
                        }
                    }
                }
            }
        } finally {
            syncScheduled.set(false);
            if (!pendingTargetValues.isEmpty() && syncScheduled.compareAndSet(false, true)) {
                dispatchToUi(this::flushPendingSync);
            }
        }
    }

    private Optional<Parameter> lookupCachedParameter(final CubismModel model, final String paramId) {
        if (paramId == null || model == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(parameterCache.computeIfAbsent(paramId, id -> {
            try {
                return model.parameters().find(new ParameterId(id));
            } catch (RuntimeException e) {
                return null;
            }
        }));
    }

    private CubismModel getOrResolveActiveModel() {
        CubismModel model = cachedActiveModel;
        if (model != null) {
            return model;
        }
        final Optional<CubismModel> active = activeModel();
        if (active.isPresent()) {
            model = active.get();
            cachedActiveModel = model;
            parameterCache.clear();
            return model;
        }
        return null;
    }

    // ── Model Lifecycle Hooks ──

    @Override
    public void onModelOpened(final ProjectContentSnapshot model) {
        cachedActiveModel = null;
        parameterCache.clear();
        preloadActiveModel();
    }

    @Override
    public void onModelCreated(final ProjectContentSnapshot model) {
        cachedActiveModel = null;
        parameterCache.clear();
        preloadActiveModel();
    }

    @Override
    public void onModelClosed(final ProjectContentSnapshot model) {
        cachedActiveModel = null;
        parameterCache.clear();
    }

    // ── Action Handlers ──

    private void handleOpenSync(final ActionRegistry.ActionContext actionContext) {
        final Optional<CubismModel> activeOpt = activeModel();
        if (activeOpt.isEmpty()) {
            notifyStatus("sync.no-model", "WARN", i18n.text("notify.no.active.model"));
            return;
        }
        final CubismModel model = activeOpt.get();
        final String modelGuid = model.id().value();
        final List<String> targetIds = resolveSelectedParameterIds(model, actionContext);
        final String focusedId = !targetIds.isEmpty() ? targetIds.get(0) : null;

        ensureLoaded(modelGuid)
                .thenAccept(ruleSet -> dispatchToUi(() -> {
                    final ParameterSyncDialog dialog = new ParameterSyncDialog(model, focusedId, ruleSet, this, i18n);
                    dialog.setVisible(true);
                }));
    }

    private void handleOpenManager(final ActionRegistry.ActionContext actionContext) {
        handleOpenSync(actionContext);
    }

    private void handleQuickSync(final ActionRegistry.ActionContext actionContext) {
        handleOpenSync(actionContext);
    }

    private void handleToggleGlobal(final ActionRegistry.ActionContext actionContext) {
        final Optional<CubismModel> activeOpt = activeModel();
        if (activeOpt.isEmpty()) {
            notifyStatus("sync.no-model", "WARN", i18n.text("notify.no.active.model"));
            return;
        }
        final String modelGuid = activeOpt.get().id().value();
        ensureLoaded(modelGuid).thenAccept(ruleSet -> {
            final boolean newState = !ruleSet.isGlobalEnabled();
            ruleSet.setGlobalEnabled(newState);
            saveRules(modelGuid);
            final String messageKey = newState ? "notify.global.enabled" : "notify.global.disabled";
            notifyStatus("sync.toggle", "INFO", i18n.text(messageKey));
        });
    }

    // ── Rule Persistence & Cache ──

    public SyncRuleSet getRuleSet(final String modelGuid) {
        if (modelGuid == null) {
            return new SyncRuleSet();
        }
        return modelRuleSets.computeIfAbsent(modelGuid, k -> new SyncRuleSet());
    }

    public CompletableFuture<SyncRuleSet> ensureLoaded(final String modelGuid) {
        if (modelGuid == null || modelGuid.isBlank()) {
            return CompletableFuture.completedFuture(new SyncRuleSet());
        }
        if (loadedGuids.contains(modelGuid)) {
            return CompletableFuture.completedFuture(getRuleSet(modelGuid));
        }
        return activeLoads.computeIfAbsent(
                modelGuid,
                guid -> storage.load(guid)
                        .thenApply(data -> {
                            final SyncRuleSet set = getRuleSet(guid);
                            if (data != null && !data.synchronizedParameterIds().isEmpty()) {
                                set.setAllSynchronized(data.synchronizedParameterIds());
                                set.setGlobalEnabled(data.globalEnabled());
                            }
                            loadedGuids.add(guid);
                            return set;
                        })
                        .whenComplete((res, err) -> activeLoads.remove(guid))
                        .toCompletableFuture());
    }

    public CompletableFuture<Boolean> saveRules(final String modelGuid) {
        if (modelGuid == null || modelGuid.isBlank()) {
            return CompletableFuture.completedFuture(false);
        }
        final SyncRuleSet ruleSet = modelRuleSets.get(modelGuid);
        if (ruleSet == null) {
            return CompletableFuture.completedFuture(false);
        }
        loadedGuids.add(modelGuid);
        final ParameterSyncData data =
                ParameterSyncData.of(modelGuid, ruleSet.isGlobalEnabled(), ruleSet.synchronizedIds());
        return storage.save(data).toCompletableFuture();
    }

    private void preloadActiveModel() {
        activeModel().ifPresent(model -> ensureLoaded(model.id().value()));
    }

    public Optional<CubismModel> activeModel() {
        if (context == null) {
            return Optional.empty();
        }
        try {
            final CubismModelAccess access = context.cubism().model();
            if (access == null || !access.isAvailable()) {
                return Optional.empty();
            }
            return Optional.ofNullable(access.active());
        } catch (RuntimeException failure) {
            return Optional.empty();
        }
    }

    private void dispatchToUi(final Runnable work) {
        if (context == null) {
            return;
        }
        final UiScheduler scheduler = context.uiScheduler();
        if (scheduler != null) {
            scheduler.runOnUiThread(work);
        } else {
            work.run();
        }
    }

    private void registerAction(
            final String actionId,
            final String label,
            final java.util.function.Consumer<ActionRegistry.ActionContext> handler) {
        if (context == null) {
            return;
        }
        final Registration reg = context.actions().register(actionId, new ActionRegistry.Action() {
            @Override
            public String id() {
                return actionId;
            }

            @Override
            public String label() {
                return label;
            }

            @Override
            public java.util.function.Consumer<ActionRegistry.ActionContext> handler() {
                return handler;
            }
        });
        synchronized (registrations) {
            registrations.add(reg);
        }
        context.disposableScope().register(reg);
    }

    private void registerContextMenu(final String id, final String actionId, final String label, final int priority) {
        context.services().find(ContextMenuRegistry.class).ifPresent(registry -> {
            final Registration reg = registry.contribute(new ContextMenuContribution(
                    id, actionId, label, "SYNC", Location.PARAMETER_TAB, Set.of(ObjectKind.PARAMETER), priority));
            synchronized (registrations) {
                registrations.add(reg);
            }
            context.disposableScope().register(reg);
        });
    }

    public void notifyStatus(final String id, final String severity, final String message) {
        if (context == null) {
            return;
        }
        context.services()
                .find(UiHostCapabilityService.class)
                .ifPresent(ui -> ui.notifyStatus(new StatusNotification(id, severity, message)));
    }

    private List<String> resolveSelectedParameterIds(
            final CubismModel model, final ActionRegistry.ActionContext actionContext) {
        final Set<String> result = new HashSet<>();
        if (actionContext != null) {
            actionContext.contextMenuSelection().ifPresent(selection -> {
                for (final ContextMenuSelection.Item item : selection.items()) {
                    if (item.kind() == ObjectKind.PARAMETER) {
                        result.add(item.id());
                    }
                }
            });
        }
        if (result.isEmpty() && context != null) {
            try {
                final SelectionSnapshot selection = context.cubism().runtime().selection();
                selection.activeParameterId().ifPresent(result::add);
            } catch (RuntimeException ignored) {
            }
        }
        return List.copyOf(result);
    }

    public ParameterSyncEngine engine() {
        return engine;
    }

    public ParameterSyncStorage storage() {
        return storage;
    }
}
