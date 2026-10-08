package dev.turboism.validation.acp;

import dev.turboism.sdk.action.ActionCatalogService;
import dev.turboism.sdk.action.ActionDescriptor;
import dev.turboism.sdk.cubism.model.ModelObjectService;
import dev.turboism.sdk.mcp.McpConnectionService;
import dev.turboism.sdk.mcp.McpHttpConnection;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.TurboismPlugin;

import dev.turboism.tests.plugin.McpValidationHostClose;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Drives the {@code acp} exact-host validation inside the editor and publishes the terminal
 * evidence file.
 *
 * <p>The probe waits for the MCP connection to publish a stdio launch descriptor, opens the ACP
 * Agent window through the public action catalog (the first open auto-connects), waits for the
 * fake agent's terminal result file, cross-reads the restored document through the SDK, and
 * closes the host through the shared validation close helper.</p>
 */
public final class AcpHostValidationPlugin implements TurboismPlugin {

    private static final String RESULT_FILE = "acp-host-validation.properties";
    private static final String AGENT_RESULT_RELATIVE = "dev.turboism.plugin.acp/agent-result.properties";
    private static final String AGENT_PLUGIN_ID = "dev.turboism.plugin.acp";
    private static final String AGENT_OPEN_ACTION_ID = "turboism-acp.open";
    private static final String RENAME_PREFIX = "ACP-Validated-Rename";
    private static final long CONNECT_TIMEOUT_MILLIS = 360_000L;
    private static final long AGENT_RESULT_TIMEOUT_MILLIS = 900_000L;

    private PluginContext context;
    private PluginLogger logger;
    private volatile boolean enabled;
    private Thread watcher;

    @Override
    public void init(final PluginContext pluginContext) {
        context = pluginContext;
        logger = pluginContext.logger();
        logger.info("ACP host validation probe initialized");
    }

    @Override
    public void enable() {
        if (watcher != null) return;
        enabled = true;
        watcher = new Thread(this::run, "turboism-acp-host-validation-probe");
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

    private void run() {
        try {
            runSteps();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return;
        }
    }

    private void runSteps() throws InterruptedException {
        final Ledger ledger = new Ledger();
        final Path agentResult = validationStateRoot().resolve(AGENT_RESULT_RELATIVE);
        try {
            if (!awaitStdioLaunch()) {
                ledger.fail("stdioLaunchPublished", "mcp_connection_had_no_stdio_launch_before_timeout");
                finish(ledger);
                return;
            }
            ledger.pass("stdioLaunchPublished");

            if (!invokeAgentWindow()) {
                ledger.fail("agentWindowOpened", "acp_open_action_was_not_listed_before_timeout");
                finish(ledger);
                return;
            }
            ledger.pass("agentWindowOpened");

            final Map<String, String> agent = awaitAgentResult(agentResult);
            if (agent.isEmpty()) {
                ledger.fail("agentResultTerminal", "agent_result_file_never_reached_a_terminal_state");
                finish(ledger);
                return;
            }
            copyAgentAssertions(ledger, agent);
            ledger.pass("agentResultTerminal");

            crossRead(ledger, agent);
        } catch (RuntimeException failure) {
            logger.error("ACP host validation probe failed", failure);
            ledger.fail("probeUnexpected", failure.getClass().getSimpleName());
        }
        finish(ledger);
    }

    private boolean awaitStdioLaunch() throws InterruptedException {
        final long deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MILLIS;
        while (enabled && System.currentTimeMillis() < deadline) {
            final McpConnectionService service = context.services()
                    .find(McpConnectionService.class)
                    .orElseGet(McpConnectionService::unavailable);
            final Optional<McpHttpConnection> current = service.current();
            if (current.isPresent() && current.get().stdioLaunch().isPresent()) {
                return true;
            }
            Thread.sleep(500L);
        }
        return false;
    }

    private boolean invokeAgentWindow() throws InterruptedException {
        final long deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MILLIS;
        while (enabled && System.currentTimeMillis() < deadline) {
            final ActionCatalogService catalog = context.services()
                    .find(ActionCatalogService.class)
                    .orElseGet(ActionCatalogService::unavailable);
            final boolean listed = catalog.actions().stream()
                    .anyMatch(action -> AGENT_PLUGIN_ID.equals(action.pluginId())
                            && AGENT_OPEN_ACTION_ID.equals(action.actionId()));
            if (listed) {
                logger.info("ACP_HOST_PROBE opening agent window via action catalog");
                catalog.invoke(AGENT_PLUGIN_ID, AGENT_OPEN_ACTION_ID);
                return true;
            }
            Thread.sleep(500L);
        }
        return false;
    }

    private Map<String, String> awaitAgentResult(final Path agentResult) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + AGENT_RESULT_TIMEOUT_MILLIS;
        while (enabled && System.currentTimeMillis() < deadline) {
            final Map<String, String> document = readProperties(agentResult);
            if (!document.isEmpty() && terminal(document)) return document;
            Thread.sleep(500L);
        }
        return Map.of();
    }

    private static boolean terminal(final Map<String, String> document) {
        final String runId = System.getProperty("turboism.validation.runId");
        return runId != null && runId.equals(document.get("runId"))
                && ("PASS".equals(document.get("status")) || "FAIL".equals(document.get("status")));
    }

    private void crossRead(final Ledger ledger, final Map<String, String> agent) {
        final Optional<ModelObjectService> objects = context.services().find(ModelObjectService.class);
        if (objects.isEmpty()) {
            ledger.fail("crossRenameCleanedUp", "sdk_model_object_service_unavailable");
            return;
        }
        // The SDK service directory does not expose CubismHistory; the native history balance is
        // covered by the agent's own turboism.history.read assertions (historyPositionBalanced).
        ledger.data("assertion.crossHistoryPosition.status", "NOT_APPLICABLE");
        ledger.data(
                "assertion.crossHistoryPosition.reason",
                "cubism_history_not_in_service_directory_covered_by_agent_historyPositionBalanced");

        final boolean renamedLeftover = objects.get().list().stream()
                .anyMatch(descriptor -> descriptor.name().startsWith(RENAME_PREFIX));
        if (renamedLeftover) {
            ledger.fail("crossRenameCleanedUp", "an_object_still_carries_the_rename_prefix");
            return;
        }
        ledger.pass("crossRenameCleanedUp");
        ledger.data("assertion.persistence.status", "NOT_APPLICABLE");
        ledger.data(
                "assertion.persistence.reason",
                "covered_by_existing_mcp_evidence_textureSaveReopen");
    }

    private void copyAgentAssertions(final Ledger ledger, final Map<String, String> agent) {
        agent.forEach((key, value) -> {
            if (!key.startsWith("assertion.") || !key.endsWith(".status")) return;
            final String assertion = key.substring("assertion.".length(), key.length() - ".status".length());
            ledger.data("assertion.agent." + assertion + ".status", value);
            if ("FAIL".equals(value)) {
                final String reason = agent.get(key.substring(0, key.length() - ".status".length()) + ".reason");
                if (reason != null) ledger.data("assertion.agent." + assertion + ".reason", reason);
            }
        });
    }

    private void finish(final Ledger ledger) {
        final Path resultFile = validationStateRoot().resolve(RESULT_FILE);
        try {
            ledger.write(resultFile, System.getProperty("turboism.validation.runId"));
        } catch (IOException failure) {
            logger.error("ACP host validation probe could not write " + resultFile, failure);
            return;
        }
        logger.info("ACP_HOST_VALIDATION_RESULT observed=" + resultFile.getFileName());
        Exception firstFailure = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                final String outcome = McpValidationHostClose.request(
                        Boolean.getBoolean("turboism.validation.exitOnComplete"),
                        enabled,
                        Files.isRegularFile(resultFile),
                        System.getProperty("turboism.validation.runId"),
                        System.getProperty("turboism.validation.hostVersion"));
                logger.info("ACP_HOST_CLOSE attempt=" + attempt + " " + outcome);
                return;
            } catch (Exception failure) {
                logger.error("Automated ACP host close attempt " + attempt + " failed", failure);
                if (firstFailure == null) firstFailure = failure;
                try {
                    Thread.sleep(3_000L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        appendWindowDump(resultFile, firstFailure);
    }

    /** Appends every visible window to the result file so a blocking modal can be identified. */
    private void appendWindowDump(final Path resultFile, final Exception failure) {
        try {
            final StringBuilder dump = new StringBuilder();
            dump.append("closeFailure=").append(sanitize(String.valueOf(failure))).append('\n');
            for (java.awt.Window window : java.awt.Window.getWindows()) {
                if (!window.isVisible()) continue;
                final java.awt.Window ownedBy = window.getOwner();
                final String title = window instanceof java.awt.Frame frame
                        ? frame.getTitle()
                        : window instanceof java.awt.Dialog dialog ? dialog.getTitle() : window.getName();
                dump.append("window.")
                        .append(window.getClass().getSimpleName())
                        .append('.')
                        .append(System.identityHashCode(window) & 0xffff)
                        .append('=')
                        .append(sanitize(String.valueOf(title)))
                        .append(" owner=")
                        .append(ownedBy == null
                                ? "none"
                                : sanitize(String.valueOf(ownedBy.getName())))
                        .append('\n');
            }
            Files.writeString(resultFile, dump.toString(), java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException dumpFailure) {
            logger.error("ACP host validation probe could not append the window dump", dumpFailure);
        }
    }

    private static String sanitize(final String text) {
        final String truncated = text.substring(0, Math.min(300, text.length()));
        return truncated.replaceAll("[\s\"]+", "_");
    }

    private Path validationStateRoot() {
        final Path pluginState = context.paths().stateDir();
        final Path root = pluginState.getParent();
        if (root == null) throw new IllegalStateException("plugin state directory has no state root");
        return root;
    }

    private static Map<String, String> readProperties(final Path file) {
        if (!Files.isRegularFile(file)) return Map.of();
        try {
            final LinkedHashMap<String, String> document = new LinkedHashMap<>();
            for (String line : Files.readAllLines(file)) {
                final int separator = line.indexOf('=');
                if (separator <= 0) continue;
                document.put(line.substring(0, separator), line.substring(separator + 1));
            }
            return document;
        } catch (IOException unreadable) {
            return Map.of();
        }
    }

    /** Assertion ledger for the probe's own result file. */
    private static final class Ledger {
        private final LinkedHashMap<String, String> rows = new LinkedHashMap<>();

        void pass(final String assertion) {
            rows.put("assertion." + assertion + ".status", "PASS");
            rows.remove("assertion." + assertion + ".reason");
        }

        void fail(final String assertion, final String reason) {
            rows.put("assertion." + assertion + ".status", "FAIL");
            rows.put("assertion." + assertion + ".reason", reason.replaceAll("\\s+", "_"));
        }

        void data(final String key, final String value) {
            rows.put(key, value);
        }

        void write(final Path file, final String runId) throws IOException {
            final LinkedHashMap<String, String> document = new LinkedHashMap<>();
            document.put("schemaVersion", "1");
            document.put("runId", runId);
            document.put("client", "acp-host-validation-probe");
            document.putAll(rows);
            document.put("status", passed() ? "PASS" : "FAIL");
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            final StringBuilder out = new StringBuilder();
            document.forEach((key, value) -> out.append(key).append('=').append(value).append('\n'));
            Files.writeString(file, out.toString());
        }

        private boolean passed() {
            return rows.entrySet().stream()
                    .filter(entry -> entry.getKey().endsWith(".status"))
                    .allMatch(entry -> "PASS".equals(entry.getValue())
                            || "NOT_APPLICABLE".equals(entry.getValue()));
        }
    }
}
