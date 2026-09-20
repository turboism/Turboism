package dev.turboism.validation.mcp;

import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.TurboismPlugin;

import dev.turboism.tests.plugin.McpValidationHostClose;
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
            final var lines = Files.readAllLines(result);
            final String runId = System.getProperty("turboism.validation.runId");
            return runId != null && lines.contains("runId=" + runId)
                && lines.stream().anyMatch(line -> line.equals("status=PASS") || line.equals("status=FAIL"));
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
            final String outcome = McpValidationHostClose.request(
                Boolean.getBoolean("turboism.validation.exitOnComplete"),
                enabled,
                terminalResult(validationStateRoot().resolve(RESULT_FILE)),
                System.getProperty("turboism.validation.runId"),
                System.getProperty("turboism.validation.hostVersion")
            );
            logger.info("MCP_HOST_CLOSE " + outcome);
        } catch (Exception failure) {
            logger.error("Automated MCP host close request failed", failure);
        }
    }
}
