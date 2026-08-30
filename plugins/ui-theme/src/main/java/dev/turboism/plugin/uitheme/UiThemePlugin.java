package dev.turboism.plugin.uitheme;

import dev.turboism.plugin.uitheme.service.BuiltinThemeAppearanceService;
import dev.turboism.plugin.uitheme.service.ThemeEditorService;
import dev.turboism.plugin.uitheme.service.ThemeManagerService;
import dev.turboism.plugin.uitheme.service.ThemePackageRepository;
import dev.turboism.plugin.uitheme.service.ThemePackageStatusService;
import dev.turboism.plugin.uitheme.service.ThemePackageTransferService;
import dev.turboism.plugin.uitheme.service.ThemeSelectionConfig;
import dev.turboism.plugin.uitheme.service.ThemeSelectionService;
import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.appearance.AppearanceRestoreResult;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.TurboismPlugin;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * Plugin entrypoint for UI theming: contributes the theme-package status check, the theme manager,
 * and the built-in theme apply action, and wires the services behind them.
 *
 * <p>All collaborators are constructed in {@code init} from the supplied {@link PluginContext} and
 * reach the host only through SDK surfaces — appearance, storage, user files, localization and the
 * UI host — so the plugin holds no direct Cubism reference of its own. Registrations made here are
 * released through the returned {@link Registration} handles when the plugin is disabled.</p>
 */
public final class UiThemePlugin implements TurboismPlugin {

    private static final String STATUS_ACTION_ID = "ui-theme.package.status.check";
    private static final String STATUS_ACTION_LABEL = "Check Theme Package Status";
    private static final String MANAGER_ACTION_ID = "ui-theme.manager.open";
    private static final String MANAGER_ACTION_LABEL = "Theme Manager";
    private static final String APPLY_BUILTIN_ACTION_ID = "ui-theme.appearance.apply-builtin";
    private static final String APPLY_BUILTIN_ACTION_LABEL = "Apply Built-in Theme";

    private PluginContext context;
    private PluginLogger logger;
    private ThemePackageStatusService themePackageStatusService;
    private ThemeManagerService themeManagerService;
    private BuiltinThemeAppearanceService builtinThemeAppearanceService;
    private ThemeEditorService themeEditorService;

    @Override
    public void init(final PluginContext context) {
        this.context = context;
        this.logger = context.logger();
        this.themePackageStatusService = new ThemePackageStatusService(
            () -> this.context.cubismRead().themeStatus(),
            this.context.uiHost()
        );
        this.builtinThemeAppearanceService = new BuiltinThemeAppearanceService(
            getClass().getClassLoader(),
            this.context.appearance(),
            this.context.uiHost(),
            this.context.localization()
        );
        final ThemeSelectionConfig selectionConfig = new ThemeSelectionConfig(this.context.config());
        selectionConfig.initialize().toCompletableFuture().join();
        final ThemePackageRepository repository = new ThemePackageRepository(this.context.storage());
        this.themeEditorService = new ThemeEditorService(
            this.context.uiHost(),
            repository,
            this.context.appearance(),
            this.context.localization(),
            logger
        );
        this.themeManagerService = new ThemeManagerService(
            this.context.uiHost(),
            builtinThemeAppearanceService,
            repository,
            new ThemePackageTransferService(this.context.userFiles()),
            new ThemeSelectionService(this.context.appearance(), selectionConfig),
            selectionConfig,
            logger,
            this.context.localization(),
            this.themeEditorService
        );
        this.themeEditorService.setBackToManager(this.themeManagerService::open);
        this.themeEditorService.setOnSaved(this.themeManagerService::refreshCache);
        logger.info("UiThemePlugin initialized");
    }

    @Override
    public void enable() {
        registerAction(STATUS_ACTION_ID, STATUS_ACTION_LABEL, ignored -> themePackageStatusService.checkThemeStatus());
        registerAction(MANAGER_ACTION_ID, MANAGER_ACTION_LABEL, ignored -> themeManagerService.open());
        registerAction(
            APPLY_BUILTIN_ACTION_ID,
            APPLY_BUILTIN_ACTION_LABEL,
            ignored -> builtinThemeAppearanceService.applyDefault()
        );
        // The Turboism top-level menu keeps a single theme entry; all theme
        // workflows (apply/import/export/delete) live inside the manager window.
        registerMenu("Turboism/" + context.localization().text("menu.themeManager"), MANAGER_ACTION_ID, 40);
        registerContextMenu(new ContextMenuRegistry.ContextMenuContribution(
            MANAGER_ACTION_ID,
            MANAGER_ACTION_LABEL,
            null,
            "workspace",
            40
        ));
        themeManagerService.refreshCache();
        themeManagerService.restorePersistedSelection();
        logger.info("UiThemePlugin enabled: unified theme manager and package actions enrolled in disposable scope");
    }

    @Override
    public void disable() {
        final AppearanceRestoreResult restored = context.appearance().restoreOwnedAppearance()
            .toCompletableFuture().join();
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

    private void registerContextMenu(final ContextMenuRegistry.ContextMenuContribution contribution) {
        final Registration registration = context.contextMenu().contribute(contribution);
        context.disposableScope().register(registration);
    }

    private void registerMenu(final String path, final String actionId, final int order) {
        final Registration registration = context.menus().contribute(new MenuRegistry.MenuContribution() {
            @Override public String menuPath() { return path; }
            @Override public String actionId() { return actionId; }
            @Override public int order() { return order; }
        });
        context.disposableScope().register(registration);
    }

    private void registerAction(
        final String id,
        final String label,
        final Consumer<ActionRegistry.ActionContext> handler
    ) {
        final Registration registration = context.actions().register(id, new ActionRegistry.Action() {
            @Override public String id() { return id; }
            @Override public String label() { return label; }
            @Override public Consumer<ActionRegistry.ActionContext> handler() { return handler; }
        });
        context.disposableScope().register(registration);
    }
}
