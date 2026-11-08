package dev.turboism.plugin.uitheme;

import dev.turboism.plugin.uitheme.service.BuiltinThemeAppearanceService;
import dev.turboism.plugin.uitheme.service.ThemeEditorService;
import dev.turboism.plugin.uitheme.service.ThemeManagerService;
import dev.turboism.plugin.uitheme.service.ThemeNotifications;
import dev.turboism.plugin.uitheme.service.ThemePackageRepository;
import dev.turboism.plugin.uitheme.service.ThemePackageStatusService;
import dev.turboism.plugin.uitheme.service.ThemePackageTransferService;
import dev.turboism.plugin.uitheme.service.ThemeSelectionConfig;
import dev.turboism.plugin.uitheme.service.ThemeSelectionService;
import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.appearance.AppearanceRestoreResult;
import dev.turboism.sdk.appearance.AppearanceService;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.TurboismPlugin;
import dev.turboism.sdk.ui.UiHostCapabilityService;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Plugin entrypoint for UI theming: contributes the theme-package status check, the theme manager,
 * and the built-in theme apply action, and wires the services behind them.
 *
 * <p>All collaborators are constructed in {@code init} from the supplied {@link PluginContext} and
 * reach the host only through SDK surfaces — appearance, storage, user files, localization and the
 * UI host — so the plugin holds no direct Cubism reference of its own. Registrations made here are
 * released through the returned {@link Registration} handles when the plugin is disabled.</p>
 *
 * <p>Action handlers never wait for an SDK result. The runtime delivers storage, config and file
 * results on this plugin's own worker, so a handler that waited would hold the only worker able to
 * release it. Each workflow instead composes its stages, which also makes a disable or re-enable
 * drop the work it superseded: every workflow is stamped with the lifecycle generation it was
 * started under.</p>
 */
public final class UiThemePlugin implements TurboismPlugin {

    private static final String STATUS_ACTION_ID = "ui-theme.package.status.check";
    private static final String STATUS_ACTION_LABEL = "Check Theme Package Status";
    private static final String MANAGER_ACTION_ID = "ui-theme.manager.open";
    private static final String MANAGER_ACTION_LABEL = "Theme Manager";
    private static final String APPLY_BUILTIN_ACTION_ID = "ui-theme.appearance.apply-builtin";
    private static final String APPLY_BUILTIN_ACTION_LABEL = "Apply Built-in Theme";
    /** Marks "no open request owns the manager"; request identities start above it. */
    private static final long NO_OPEN_REQUEST = 0L;

    private PluginContext context;
    private PluginLogger logger;
    private ThemePackageStatusService themePackageStatusService;
    private ThemeManagerService themeManagerService;
    private BuiltinThemeAppearanceService builtinThemeAppearanceService;
    private ThemeEditorService themeEditorService;

    private final AtomicLong workflowGeneration = new AtomicLong();
    private final AtomicLong openRequestCounter = new AtomicLong();
    // Which request may present the manager. A stale workflow must not free the slot of a newer one.
    private final AtomicLong activeOpenRequest = new AtomicLong(NO_OPEN_REQUEST);
    // Listing every built-in and user package is only needed when the manager is opened, so the
    // cache is rebuilt on that first open after each enable instead of at load time.
    private volatile boolean themeCacheInitialized;

    @Override
    public void init(final PluginContext context) {
        this.context = context;
        this.logger = context.logger();
        this.themePackageStatusService = new ThemePackageStatusService(
                () -> this.context.cubismRead().themeStatus(),
                this.context.services().require(UiHostCapabilityService.class));
        this.builtinThemeAppearanceService = new BuiltinThemeAppearanceService(
                getClass().getClassLoader(),
                this.context.services().require(AppearanceService.class),
                this.context.services().require(UiHostCapabilityService.class),
                this.context.localization());
        final ThemeSelectionConfig selectionConfig = new ThemeSelectionConfig(this.context.config());
        // Bootstrap barrier: init runs on the plugin lifecycle lane, never on the plugin worker that
        // settles this config read, so the store is loaded before any action can reach it.
        selectionConfig.initialize().toCompletableFuture().join();
        final ThemePackageRepository repository = new ThemePackageRepository(this.context.storage());
        this.themeEditorService = new ThemeEditorService(
                this.context.services().require(UiHostCapabilityService.class),
                repository,
                this.context.services().require(AppearanceService.class),
                this.context.localization(),
                logger);
        this.themeManagerService = new ThemeManagerService(
                this.context.services().require(UiHostCapabilityService.class),
                builtinThemeAppearanceService,
                repository,
                new ThemePackageTransferService(this.context.userFiles()),
                new ThemeSelectionService(this.context.services().require(AppearanceService.class), selectionConfig),
                selectionConfig,
                logger,
                this.context.localization(),
                this.themeEditorService);
        this.themeEditorService.setBackToManager(this.themeManagerService::open);
        this.themeEditorService.setOnSaved(() -> themeManagerService
                .refreshCache()
                .whenComplete((rebuilt, failure) ->
                        ThemeNotifications.workflowFailed(logger, ThemeNotifications.WORKFLOW_SAVE_REFRESH, failure)));
        logger.info("UiThemePlugin initialized");
    }

    @Override
    public void enable() {
        workflowGeneration.incrementAndGet();
        registerAction(STATUS_ACTION_ID, STATUS_ACTION_LABEL, ignored -> themePackageStatusService.checkThemeStatus());
        themeCacheInitialized = false;
        registerAction(MANAGER_ACTION_ID, MANAGER_ACTION_LABEL, ignored -> openThemeManager());
        registerAction(
                APPLY_BUILTIN_ACTION_ID,
                APPLY_BUILTIN_ACTION_LABEL,
                ignored -> builtinThemeAppearanceService
                        .applyDefault()
                        .whenComplete((settled, failure) -> ThemeNotifications.workflowFailed(
                                logger, ThemeNotifications.WORKFLOW_APPLY_BUILTIN, failure)));
        // The Turboism top-level menu keeps a single theme entry; all theme
        // workflows (apply/import/export/delete) live inside the manager window.
        registerMenu("Turboism/" + context.localization().text("menu.themeManager"), MANAGER_ACTION_ID, 40);
        themeManagerService
                .restorePersistedSelection()
                .whenComplete((ignored, failure) -> ThemeNotifications.workflowFailed(
                        logger, ThemeNotifications.WORKFLOW_RESTORE_SELECTION, failure));
        logger.info("UiThemePlugin enabled: unified theme manager and package actions enrolled in disposable scope");
    }

    /**
     * Opens the manager once the package cache is ready.
     *
     * <p>Returns immediately so the plugin worker stays free to settle the storage results this
     * workflow waits for. A workflow started before a disable or re-enable is dropped instead of
     * opening a window for a lifecycle that already ended, and it releases only its own request, so a
     * newer open keeps its slot.</p>
     */
    private void openThemeManager() {
        final long generation = workflowGeneration.get();
        final long request = openRequestCounter.incrementAndGet();
        if (!activeOpenRequest.compareAndSet(NO_OPEN_REQUEST, request)) {
            return;
        }
        try {
            themeCacheReady(generation)
                    .thenCompose(ignored -> {
                        if (isCurrentGeneration(generation)) {
                            themeManagerService.open();
                        }
                        return CompletableFuture.<Void>completedFuture(null);
                    })
                    .whenComplete((ignored, failure) -> {
                        releaseOpenRequest(request);
                        ThemeNotifications.workflowFailed(logger, ThemeNotifications.WORKFLOW_OPEN_MANAGER, failure);
                    });
        } catch (RuntimeException failure) {
            releaseOpenRequest(request);
            ThemeNotifications.workflowFailed(logger, ThemeNotifications.WORKFLOW_OPEN_MANAGER, failure);
        }
    }

    private void releaseOpenRequest(final long request) {
        activeOpenRequest.compareAndSet(request, NO_OPEN_REQUEST);
    }

    private CompletionStage<Void> themeCacheReady(final long generation) {
        if (themeCacheInitialized) {
            return CompletableFuture.completedFuture(null);
        }
        return themeManagerService.refreshCache().thenRun(() -> {
            if (isCurrentGeneration(generation)) {
                themeCacheInitialized = true;
            }
        });
    }

    private boolean isCurrentGeneration(final long generation) {
        return workflowGeneration.get() == generation;
    }

    @Override
    public void disable() {
        // Fences every workflow still waiting for a host result: nothing it does afterwards may reach
        // the host for a lifecycle that already ended.
        workflowGeneration.incrementAndGet();
        activeOpenRequest.set(NO_OPEN_REQUEST);
        // The appearance host settles synchronously, and this runs on the lifecycle teardown lane
        // rather than a plugin worker, so waiting here cannot deadlock the worker that settles it.
        final AppearanceRestoreResult restored = context.services()
                .require(AppearanceService.class)
                .restoreOwnedAppearance()
                .toCompletableFuture()
                .join();
        if (restored.outcome() != AppearanceRestoreResult.Outcome.RESTORED
                && restored.outcome() != AppearanceRestoreResult.Outcome.NO_OWNED_OVERRIDE) {
            logger.warn("UiThemePlugin disable could not restore owned appearance: "
                    + restored.outcome().name().toLowerCase(Locale.ROOT));
        }
        logger.info("UiThemePlugin disabled");
    }

    @Override
    public void shutdown() {
        logger.info("UiThemePlugin shutdown");
    }

    private void registerMenu(final String path, final String actionId, final int order) {
        final Registration registration = context.menus().contribute(new MenuRegistry.MenuContribution() {
            @Override
            public String menuPath() {
                return path;
            }

            @Override
            public String actionId() {
                return actionId;
            }

            @Override
            public int order() {
                return order;
            }
        });
        context.disposableScope().register(registration);
    }

    private void registerAction(
            final String id, final String label, final Consumer<ActionRegistry.ActionContext> handler) {
        final Registration registration = context.actions().register(id, new ActionRegistry.Action() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String label() {
                return label;
            }

            @Override
            public Consumer<ActionRegistry.ActionContext> handler() {
                return handler;
            }
        });
        context.disposableScope().register(registration);
    }
}
