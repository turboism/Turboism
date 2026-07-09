package dev.turboism.plugin.uitheme;

import dev.turboism.plugin.uitheme.service.ThemePackageStatusService;
import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.TurboismPlugin;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;

import java.util.function.Consumer;

public final class UiThemePlugin implements TurboismPlugin {

    private static final String STATUS_ACTION_ID = "ui-theme.package.status.check";
    private static final String STATUS_ACTION_LABEL = "Check Theme Package Status";
    private static final String IMPORT_ACTION_ID = "ui-theme.package.import";
    private static final String IMPORT_ACTION_LABEL = "Import Theme Package";

    private PluginContext context;
    private PluginLogger logger;
    private ThemePackageStatusService themePackageStatusService;

    @Override
    public void init(PluginContext context) {
        this.context = context;
        this.logger = context.logger();
        this.themePackageStatusService = new ThemePackageStatusService(
            () -> this.context.cubismRead().themeStatus(),
            this.context.uiHost()
        );
        logger.info("UiThemePlugin initialized");
    }

    @Override
    public void enable() {
        registerContextMenu(
            new ContextMenuRegistry.ContextMenuContribution(
                "ui-theme.toggle",
                "Toggle Theme",
                null,
                "workspace",
                40
            )
        );
        registerContextMenu(
            new ContextMenuRegistry.ContextMenuContribution(
                "ui-theme.apply",
                "Apply Theme",
                null,
                "workspace",
                41
            )
        );
        registerAction(STATUS_ACTION_ID, STATUS_ACTION_LABEL, ignored -> themePackageStatusService.checkThemeStatus());
        registerAction(IMPORT_ACTION_ID, IMPORT_ACTION_LABEL, ignored -> themePackageStatusService.handleThemePackageImport());
        logger.info("UiThemePlugin enabled: context menus and theme package actions enrolled in disposable scope");
    }

    @Override
    public void disable() {
        logger.info("UiThemePlugin disabled");
    }

    @Override
    public void shutdown() {
        logger.info("UiThemePlugin shutdown");
    }

    private void registerContextMenu(ContextMenuRegistry.ContextMenuContribution contribution) {
        Registration registration = context.contextMenu().contribute(contribution);
        context.disposableScope().register(registration);
    }

    private void registerAction(
        final String id,
        final String label,
        final Consumer<ActionRegistry.ActionContext> handler
    ) {
        Registration registration = context.actions().register(id, new ActionRegistry.Action() {
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
