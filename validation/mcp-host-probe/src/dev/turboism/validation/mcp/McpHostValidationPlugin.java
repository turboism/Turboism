package dev.turboism.validation.mcp;

import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.TurboismPlugin;

import javax.swing.SwingUtilities;
import java.awt.Frame;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.nio.file.Path;

/** Closes the exact host after the external MCP validation client publishes a terminal result. */
public final class McpHostValidationPlugin implements TurboismPlugin {

    private static final String RESULT_FILE = "mcp-host-validation.properties";
    private static final long RESULT_TIMEOUT_MILLIS = 900_000L;

    private PluginContext context;
    private PluginLogger logger;
    private volatile boolean enabled;
    private Thread watcher;

    @Override
    public void init(final PluginContext context) {
        this.context = context;
        this.logger = context.logger();
        logger.info("MCP host validation probe initialized");
    }

    @Override
    public void enable() {
        if (watcher != null) return;
        enabled = true;
        watcher = new Thread(this::awaitResult, "turboism-mcp-host-validation-close");
        watcher.setDaemon(true);
        watcher.start();
    }

    @Override
    public void disable() {
        stopWatcher();
    }

    @Override
    public void shutdown() {
        stopWatcher();
    }

    private void stopWatcher() {
        enabled = false;
        final Thread running = watcher;
        watcher = null;
        if (running != null) running.interrupt();
    }

    private void awaitResult() {
        final Path result = validationStateRoot().resolve(RESULT_FILE);
        final long deadline = System.currentTimeMillis() + RESULT_TIMEOUT_MILLIS;
        while (enabled && System.currentTimeMillis() < deadline) {
            if (terminalResult(result)) {
                logger.info("MCP_HOST_VALIDATION_RESULT observed=" + result.getFileName());
                requestAutomatedHostClose();
                return;
            }
            try {
                Thread.sleep(500L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (enabled) logger.warn("MCP host validation probe timed out waiting for terminal result");
    }

    private static boolean terminalResult(final Path result) {
        if (!Files.isRegularFile(result)) return false;
        try {
            return Files.readAllLines(result).stream()
                .anyMatch(line -> line.equals("status=PASS") || line.equals("status=FAIL"));
        } catch (Exception ignored) {
            return false;
        }
    }

    private Path validationStateRoot() {
        final Path pluginState = context.paths().stateDir();
        final Path root = pluginState.getParent();
        if (root == null) throw new IllegalStateException("plugin state directory has no state root");
        return root;
    }

    private void requestAutomatedHostClose() {
        try {
            final Runnable request = () -> {
                Frame modelFrame = null;
                Frame cubismFrame = null;
                Frame fallbackFrame = null;
                for (Frame frame : Frame.getFrames()) {
                    if (!frame.isVisible()) continue;
                    if (fallbackFrame == null) fallbackFrame = frame;
                    final String title = frame.getTitle();
                    if (title != null && title.contains(".cmo3")) {
                        modelFrame = frame;
                        break;
                    }
                    if (cubismFrame == null && title != null && title.contains("Cubism")) {
                        cubismFrame = frame;
                    }
                }
                final Frame frame = modelFrame != null
                    ? modelFrame : cubismFrame != null ? cubismFrame : fallbackFrame;
                if (frame == null) {
                    logger.warn("Automated MCP host close skipped: no visible Cubism frame");
                    return;
                }
                frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING));
            };
            if (SwingUtilities.isEventDispatchThread()) request.run();
            else SwingUtilities.invokeAndWait(request);
        } catch (Exception failure) {
            logger.error("Automated MCP host close request failed", failure);
        }
    }
}
