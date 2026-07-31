package dev.turboism.plugin.historypanel;

import dev.turboism.plugin.historypanel.service.HistoryPanelService;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.TurboismPlugin;

/**
 * Read-only Photoshop-style history pane plugin.
 *
 * <p>Enable registers the embedded History dock panel and its polling loop; any
 * contribution failure rolls back the disposable scope so partial registrations
 * do not leak. The pane never writes to the document: production move-to is a
 * fail-closed boundary.</p>
 */
public final class HistoryPanelPlugin implements TurboismPlugin {

    private PluginContext context;
    private PluginLogger logger;
    private Registration panelRegistration;

    @Override
    public void init(final PluginContext context) {
        this.context = context;
        this.logger = context.logger();
        logger.info("HistoryPanelPlugin initialized");
    }

    @Override
    public void enable() {
        try {
            final HistoryPanelService service = new HistoryPanelService(
                context.cubism().history(),
                context.uiHost(),
                logger
            );
            panelRegistration = service.enable();
            context.disposableScope().register(panelRegistration);
        } catch (RuntimeException failure) {
            closeDisposableScopeQuietly();
            throw failure;
        }
        logger.info("HistoryPanelPlugin enabled: History dock panel enrolled in disposable scope");
    }

    private void closeDisposableScopeQuietly() {
        try {
            context.disposableScope().close();
        } catch (Exception closeFailure) {
            logger.warn("HistoryPanelPlugin enable rollback close failed: " + closeFailure.getMessage());
        }
    }

    @Override
    public void disable() {
        logger.info("HistoryPanelPlugin disabled");
    }

    @Override
    public void shutdown() {
        logger.info("HistoryPanelPlugin shutdown");
    }
}
