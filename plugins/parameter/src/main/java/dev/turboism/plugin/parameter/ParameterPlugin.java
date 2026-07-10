package dev.turboism.plugin.parameter;

import dev.turboism.plugin.parameter.service.ParameterCsvService;
import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.TurboismPlugin;

import java.util.function.Consumer;

/**
 * Official SDK-only plugin shell for M13 parameter.csv.import-export.fake.
 */
public final class ParameterPlugin implements TurboismPlugin {

    private PluginContext context;
    private PluginLogger logger;
    private ParameterCsvService csvService;

    @Override
    public void init(final PluginContext context) {
        this.context = context;
        this.logger = context.logger();
        this.csvService = new ParameterCsvService(
            context.cubismRead(),
            context.cubism(),
            context,
            context.uiHost()
        );
        logger.info("ParameterPlugin initialized");
    }

    @Override
    public void enable() {
        try {
            registerAction(
                ParameterCsvService.EXPORT_ACTION_ID,
                "Export Parameters CSV",
                ignored -> csvService.exportCsv()
            );
            registerAction(
                ParameterCsvService.IMPORT_ACTION_ID,
                "Import Parameters CSV",
                ignored -> csvService.importCsv()
            );
        } catch (RuntimeException failure) {
            closeDisposableScopeQuietly();
            throw failure;
        }
        logger.info("ParameterPlugin enabled: parameter CSV export/import actions enrolled in disposable scope");
    }

    @Override
    public void disable() {
        logger.info("ParameterPlugin disabled");
    }

    @Override
    public void shutdown() {
        logger.info("ParameterPlugin shutdown");
    }

    private void registerAction(
        final String id,
        final String label,
        final Consumer<ActionRegistry.ActionContext> handler
    ) {
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

    private void closeDisposableScopeQuietly() {
        try {
            context.disposableScope().close();
        } catch (Exception closeFailure) {
            logger.warn("ParameterPlugin enable rollback close failed: " + closeFailure.getMessage());
        }
    }
}
