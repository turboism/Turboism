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
        final Path seed = validationStateRoot().getParent().resolve("config/dev.turboism.plugin.acp/settings.properties");
        final byte[] seedBefore;
        try { seedBefore = Files.readAllBytes(seed); }
        catch (IOException failure) {
            ledger.fail("settingsSeedRead", failure.getClass().getSimpleName());
            finish(ledger);
            return;
        }
        try {
            // Queue inputs are immutable; only the copied task settings should be writable.
            if (!seed.toFile().setWritable(true)) throw new IllegalStateException("Task-local ACP settings could not be made writable");
            if (!awaitStdioLaunch()) {
                ledger.fail("stdioLaunchPublished", "mcp_connection_had_no_stdio_launch_before_timeout");
                finish(ledger);
                return;
            }
            ledger.pass("stdioLaunchPublished");
            captureMcpWindow(ledger);
            checkWindowsHarnessProbes(ledger);

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
            captureAgentUi(ledger);
            final Map<String, String> settings = readProperties(seed);
            if ("fake-session-1".equals(settings.get("acpSessionId"))) ledger.pass("sessionBindingPersisted");
            else ledger.fail("sessionBindingPersisted", "owned_session_binding_not_saved");
            final Path indexes = validationStateRoot().resolve("dev.turboism.plugin.acp/session-index");
            try (var paths = Files.list(indexes)) {
                final var files = paths.filter(Files::isRegularFile).toList();
                if (files.size() == 1) {
                    final String index = Files.readString(files.get(0));
                    if (index.contains("fake-session-1") && !index.contains("foreign-cli-session")) ledger.pass("ownedSessionIndexPersisted");
                    else ledger.fail("ownedSessionIndexPersisted", "local_index_contains_unowned_sessions");
                } else ledger.fail("ownedSessionIndexPersisted", "unexpected_index_file_count");
            } catch (IOException failure) { ledger.fail("ownedSessionIndexPersisted", failure.getClass().getSimpleName()); }
        } catch (RuntimeException failure) {
            logger.error("ACP host validation probe failed", failure);
            ledger.fail("probeUnexpected", failure.getClass().getSimpleName());
        } finally {
            // The runner verifies seeded home files after exit; retain persistence evidence
            // above, then restore the task copy without touching frozen queue inputs.
            try { Files.write(seed, seedBefore); }
            catch (IOException failure) { ledger.fail("settingsSeedRestored", failure.getClass().getSimpleName()); }
            seed.toFile().setWritable(false);
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

    private void captureMcpWindow(final Ledger ledger) throws InterruptedException {
        context.services().require(ActionCatalogService.class)
                .invoke("dev.turboism.plugin.mcp", "turboism.mcp.connection.open");
        final long deadline = System.currentTimeMillis() + 15_000L;
        while (enabled && System.currentTimeMillis() < deadline) {
            final var captured = new java.util.concurrent.atomic.AtomicBoolean();
            try {
                javax.swing.SwingUtilities.invokeAndWait(() -> {
                    for (java.awt.Window window : java.awt.Window.getWindows()) {
                        if (!window.isVisible() || !"turboism-mcp.connection".equals(window.getName())) continue;
                        final var image = new java.awt.image.BufferedImage(window.getWidth(), window.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
                        final var graphics = image.createGraphics();
                        try { window.printAll(graphics); }
                        finally { graphics.dispose(); }
                        try {
                            javax.imageio.ImageIO.write(image, "png", validationStateRoot().resolve("mcp-connection-window.png").toFile());
                            ledger.pass("mcpConnectionWindowOpened");
                        } catch (IOException failure) { ledger.fail("mcpConnectionWindowOpened", failure.getClass().getSimpleName()); }
                        window.setVisible(false);
                        captured.set(true);
                        return;
                    }
                });
            } catch (java.lang.reflect.InvocationTargetException failure) {
                ledger.fail("mcpConnectionWindowOpened", failure.getCause().getClass().getSimpleName());
                return;
            }
            if (captured.get()) return;
            Thread.sleep(200L);
        }
        ledger.fail("mcpConnectionWindowOpened", "window_not_visible_before_timeout");
    }

    /** Exercises the production Windows .cmd launcher and stderr probes without real credentials. */
    private void checkWindowsHarnessProbes(final Ledger ledger) {
        if (!System.getProperty("os.name", "").startsWith("Windows")) {
            ledger.fail("windowsHarnessProbes", "windows_host_required");
            return;
        }
        final Path root = validationStateRoot().resolve("windows harness fixtures");
        final Path jar = validationStateRoot().getParent().resolve("plugins/acp.jar");
        try (var loader = new java.net.URLClassLoader(new java.net.URL[] { jar.toUri().toURL() }, getClass().getClassLoader())) {
            Files.createDirectories(root);
            final Class<?> catalog = loader.loadClass("dev.turboism.plugin.acp.AgentCatalog");
            final Class<?> profileType = loader.loadClass("dev.turboism.plugin.acp.AgentProfile");
            final Class<?> availability = loader.loadClass("dev.turboism.plugin.acp.AgentAvailability");
            final var profile = catalog.getDeclaredMethod("profile", String.class);
            final var available = availability.getDeclaredMethod("available", profileType, Path.class);
            final var version = availability.getDeclaredMethod("version", Path.class);
            final var discover = availability.getDeclaredMethod("discover", profileType, java.util.List.class, java.util.function.Consumer.class);
            profile.setAccessible(true);
            available.setAccessible(true);
            version.setAccessible(true);
            discover.setAccessible(true);
            for (String id : java.util.List.of("claude", "codex", "pi")) {
                final Path executable = root.resolve(id + ".cmd");
                Files.writeString(executable, "@echo off\r\nif \"%~1\"==\"--version\" (\r\n echo harness 1.2.3 1>&2\r\n exit /b 0\r\n)\r\necho stream-json --input-format rpc --extension app-server 1>&2\r\nexit /b 0\r\n");
                final Object descriptor = ((Optional<?>) profile.invoke(null, id)).orElseThrow();
                discover.invoke(null, descriptor, java.util.List.of(executable), (java.util.function.Consumer<String>) message -> logger.info("ACP_HOST_HARNESS_PROBE " + message));
                if (!Boolean.TRUE.equals(available.invoke(null, descriptor, executable))) {
                    throw new IllegalStateException(id + " batch probe failed");
                }
                if (!"1.2.3".equals(version.invoke(null, executable))) {
                    throw new IllegalStateException(id + " stderr version missing");
                }
                ledger.pass("windowsHarnessProbe." + id);
            }
            ledger.pass("windowsHarnessProbes");
        } catch (Exception failure) {
            logger.error("ACP_HOST_PROBE Windows harness probes failed", failure);
            ledger.fail("windowsHarnessProbes", failure.getClass().getSimpleName());
        }
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

    /** Captures only the task's Agent window and verifies its actual Swing controls. */
    private void captureAgentUi(final Ledger ledger) throws InterruptedException {
        final boolean renderRequired = "5303".equals(System.getProperty("turboism.validation.hostVersion"));
        final byte[] renderPng;
        final Map<String, ?> renderMetadata;
        final java.awt.image.BufferedImage expectedRender;
        try {
            final Path root = validationStateRoot().resolve(AGENT_PLUGIN_ID);
            renderPng = renderRequired ? Files.readAllBytes(root.resolve("agent-native-render.png")) : null;
            renderMetadata = renderRequired ? dev.turboism.sdk.json.Json.parseObject(Files.readString(root.resolve("agent-native-render.json"))) : null;
            expectedRender = renderRequired ? javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(renderPng)) : null;
            if (renderRequired && (!"cubism.native.renderPixels".equals(renderMetadata.get("renderer"))
                    || !java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(renderPng)).equals(renderMetadata.get("sha256"))))
                throw new IllegalStateException("Render evidence identity/hash mismatch");
        } catch (IOException | RuntimeException | java.security.NoSuchAlgorithmException failure) {
            ledger.fail("nativeRenderSwing", failure.getClass().getSimpleName());
            return;
        }
        final long deadline = System.currentTimeMillis() + 15_000L;
        while (enabled && System.currentTimeMillis() < deadline) {
            final java.util.concurrent.atomic.AtomicBoolean captured = new java.util.concurrent.atomic.AtomicBoolean();
            try {
                javax.swing.SwingUtilities.invokeAndWait(() -> {
                    for (java.awt.Window window : java.awt.Window.getWindows()) {
                        if (!window.isVisible()) continue;
                        final java.awt.Component component = findComponent(window, "turboism-acp.composer-agent");
                        if (!(component instanceof javax.swing.JButton button) || !button.isEnabled()) continue;
                        final java.awt.Component sessionList = findComponent(window, "turboism-acp.sessions");
                        if (sessionList instanceof javax.swing.JList<?> list && list.getModel().getSize() == 0) continue;
                        if (renderRequired) {
                            final var transcript = findComponent(window, "turboism-acp.transcript");
                            if (!(transcript instanceof javax.swing.JTextPane pane)) continue;
                            final var inspection = AcpRenderUiEvidence.inspect(pane, renderMetadata, renderPng, expectedRender);
                            ledger.data("ui.nativeRenderInspection", dev.turboism.sdk.json.Json.stringify(inspection.details()));
                            final var displayed = inspection.image();
                            if (displayed == null) {
                                if (System.currentTimeMillis() >= deadline - 750L) {
                                    final var diagnostic = new java.awt.image.BufferedImage(window.getWidth(), window.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
                                    final var graphics = diagnostic.createGraphics();
                                    try { window.printAll(graphics); } finally { graphics.dispose(); }
                                    try { javax.imageio.ImageIO.write(diagnostic, "png", validationStateRoot().resolve("acp-agent-window-failure.png").toFile()); }
                                    catch (IOException failure) { ledger.data("ui.failureCaptureError", failure.getClass().getSimpleName()); }
                                }
                                continue; // Wait for the production preview SwingWorker.
                            }
                            try {
                                javax.imageio.ImageIO.write(displayed, "png", validationStateRoot().resolve("acp-transcript-render.png").toFile());
                                ledger.pass("nativeRenderSwing");
                                ledger.data("ui.nativeRenderSha256", String.valueOf(renderMetadata.get("sha256")));
                                ledger.data("ui.nativeRenderPixels", "256x256_exact_argb");
                                ledger.data("ui.nativeRenderProvenance", "tool_capture_document_model_revision_dimensions");
                                ledger.pass("nativeRenderNoBase64Text");
                            } catch (IOException failure) { ledger.fail("nativeRenderSwing", failure.getClass().getSimpleName()); }
                        }
                        final java.awt.Color foreground = button.getForeground();
                        ledger.data("ui.harnessForeground", String.format("#%06x", foreground.getRGB() & 0xffffff));
                        if (Math.max(foreground.getRed(), Math.max(foreground.getGreen(), foreground.getBlue())) < 128) ledger.pass("harnessForeground");
                        else ledger.fail("harnessForeground", "enabled_harness_has_a_muted_foreground");
                        final java.awt.Component sessions = findComponent(window, "turboism-acp.sessions");
                        if (sessions instanceof javax.swing.JList<?> list && list.getModel().getSize() == 1) ledger.pass("onlyOwnedSessionVisible");
                        else ledger.fail("onlyOwnedSessionVisible", "foreign_cli_session_was_imported_or_owned_session_missing");
                        final java.awt.Container optionBar = button.getParent();
                        final boolean onlyMinimalControls = optionBar.getComponentCount() == 3
                                && findComponent(optionBar, "turboism-acp.composer-permission-mode") != null;
                        if (onlyMinimalControls) ledger.pass("unsupportedOptionsHidden");
                        else ledger.fail("unsupportedOptionsHidden", "minimal_agent_still_has_unsupported_option_controls_" + optionBar.getComponentCount());
                        final java.awt.image.BufferedImage screenshot = new java.awt.image.BufferedImage(window.getWidth(), window.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
                        final java.awt.Graphics2D graphics = screenshot.createGraphics();
                        try { window.printAll(graphics); }
                        finally { graphics.dispose(); }
                        try {
                            final Path image = validationStateRoot().resolve("acp-agent-window.png");
                            javax.imageio.ImageIO.write(screenshot, "png", image.toFile());
                            ledger.pass("agentWindowScreenshot");
                        } catch (IOException failure) { ledger.fail("agentWindowScreenshot", failure.getClass().getSimpleName()); }
                        captured.set(true);
                        return;
                    }
                });
            } catch (java.lang.reflect.InvocationTargetException failure) {
                ledger.fail("agentUi", failure.getCause().getClass().getSimpleName());
                return;
            }
            if (captured.get()) {
                captureInteractiveShowcase(ledger);
                return;
            }
            Thread.sleep(200L);
        }
        ledger.fail("agentUi", renderRequired ? "native_render_pixels_or_provenance_not_displayed_before_timeout" : "connected_agent_window_not_visible_before_timeout");
    }

    private void captureInteractiveShowcase(final Ledger ledger) throws InterruptedException {
        final java.util.concurrent.atomic.AtomicReference<java.awt.Window> targetWindow =
                new java.util.concurrent.atomic.AtomicReference<>();
        try {
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                for (java.awt.Window candidate : java.awt.Window.getWindows()) {
                    if (!candidate.isVisible()) continue;
                    final java.awt.Component prompt = findComponent(candidate, "turboism-acp.prompt");
                    final java.awt.Component send = findComponent(candidate, "turboism-acp.send");
                    if (prompt instanceof javax.swing.text.JTextComponent input
                            && send instanceof javax.swing.JButton sendButton) {
                        targetWindow.set(candidate);
                        input.setText("请检查当前模型的头部角度与呼吸参数，平滑过渡并做视口复核");
                        sendButton.doClick();
                        return;
                    }
                }
            });
        } catch (java.lang.reflect.InvocationTargetException failure) {
            ledger.fail("uiShowcasePrompt", failure.getCause().getClass().getSimpleName());
            return;
        }
        final java.awt.Window window = targetWindow.get();
        if (window == null) {
            ledger.fail("uiShowcasePrompt", "agent_window_not_found");
            return;
        }

        // Wait for the modal permission dialog, capture it cleanly, and click "Allow for session".
        final long permDeadline = System.currentTimeMillis() + 20_000L;
        boolean permCaptured = false;
        while (enabled && System.currentTimeMillis() < permDeadline && !permCaptured) {
            final java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
            try {
                javax.swing.SwingUtilities.invokeAndWait(() -> {
                    for (java.awt.Window w : java.awt.Window.getWindows()) {
                        if (!w.isVisible() || !"turboism-acp.permission".equals(w.getName())) continue;
                        final java.awt.Component allowSession =
                                findComponent(w, "turboism-acp.permission.allow-session");
                        if (!(allowSession instanceof javax.swing.JButton allowButton)) continue;
                        w.validate();
                        captureWindowImage(w, validationStateRoot().resolve("acp-permission-dialog.png"));
                        captureCompositeImage(window, w, validationStateRoot().resolve("acp-permission-overlay.png"));
                        allowButton.doClick();
                        done.set(true);
                        return;
                    }
                });
            } catch (java.lang.reflect.InvocationTargetException failure) {
                ledger.fail("uiShowcasePermission", failure.getCause().getClass().getSimpleName());
                return;
            }
            if (done.get()) permCaptured = true;
            else Thread.sleep(100L);
        }
        if (permCaptured) ledger.pass("uiShowcasePermission");
        else ledger.fail("uiShowcasePermission", "permission_dialog_not_observed");

        // Wait for the turn to complete (send button re-enabled and TPS pill visible).
        final long turnDeadline = System.currentTimeMillis() + 20_000L;
        boolean turnDone = false;
        while (enabled && System.currentTimeMillis() < turnDeadline && !turnDone) {
            final java.util.concurrent.atomic.AtomicBoolean ready = new java.util.concurrent.atomic.AtomicBoolean();
            try {
                javax.swing.SwingUtilities.invokeAndWait(() -> {
                    final java.awt.Component agentBtn = findComponent(window, "turboism-acp.composer-agent");
                    final java.awt.Component todoPill = findComponent(window, "turboism-acp.todo-pill");
                    final java.awt.Component tpsPill = findComponent(window, "turboism-acp.tps-pill");
                    if (agentBtn instanceof javax.swing.JButton btn
                            && btn.isEnabled()
                            && todoPill != null
                            && todoPill.isVisible()
                            && tpsPill != null
                            && tpsPill.isVisible()) {
                        ready.set(true);
                    }
                });
            } catch (java.lang.reflect.InvocationTargetException ignored) {
                break;
            }
            if (ready.get()) turnDone = true;
            else Thread.sleep(120L);
        }
        if (!turnDone) {
            ledger.fail("uiShowcaseTurn", "showcase_turn_did_not_settle");
            return;
        }
        ledger.pass("uiShowcaseTurn");

        try {
            // 1. Overview screenshot with unified activity group, markdown reply, timestamps, and Todo & TPS pills.
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                final java.awt.Component scrollComp = findComponent(window, "turboism-acp.transcript-scroll");
                if (scrollComp instanceof javax.swing.JScrollPane sp) {
                    sp.getVerticalScrollBar().setValue(sp.getVerticalScrollBar().getMaximum());
                }
                window.validate();
                captureWindowImage(window, validationStateRoot().resolve("acp-showcase-overview.png"));
            });

            // 2. Bounded scrollable model selector popup with filter field.
            captureButtonPopup(window, "turboism-acp.composer-option.model", "acp-showcase-model-menu.png");

            // 3. Todo popover & TPS popover.
            captureButtonPopup(window, "turboism-acp.todo-pill", "acp-showcase-todo-popover.png");
            captureButtonPopup(window, "turboism-acp.tps-pill", "acp-showcase-tps-popover.png");

            // 4. Permission mode menu showing Ask / Session trusted / Auto approve.
            captureButtonPopup(window, "turboism-acp.composer-option.permission", "acp-showcase-permission-menu.png");

            // 5. Expand the showcase turn's activity group (last ▸) to capture nested Thinking + Tools rows.
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                final java.awt.Component transcriptComp = findComponent(window, "turboism-acp.transcript");
                if (transcriptComp instanceof javax.swing.JTextPane pane) {
                    final String text = pane.getText();
                    final int idx = text.lastIndexOf("▸");
                    if (idx >= 0) {
                        pane.setCaretPosition(idx + 1);
                        final javax.swing.Action toggle = pane.getActionMap().get("turboism-acp.toggle-disclosure");
                        if (toggle != null) {
                            toggle.actionPerformed(new java.awt.event.ActionEvent(
                                    pane, java.awt.event.ActionEvent.ACTION_PERFORMED, "toggle"));
                        }
                    }
                    final java.awt.Component scrollComp = findComponent(window, "turboism-acp.transcript-scroll");
                    if (scrollComp instanceof javax.swing.JScrollPane sp) {
                        sp.getVerticalScrollBar().setValue(sp.getVerticalScrollBar().getMaximum());
                    }
                }
                window.validate();
                captureWindowImage(window, validationStateRoot().resolve("acp-showcase-expanded-activity.png"));
            });

            // 6. Expand one nested Thinking disclosure to verify inner thought text rendering.
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                final java.awt.Component transcriptComp = findComponent(window, "turboism-acp.transcript");
                if (transcriptComp instanceof javax.swing.JTextPane pane) {
                    final String text = pane.getText();
                    final int idx = text.lastIndexOf("▸");
                    if (idx >= 0) {
                        pane.setCaretPosition(idx + 1);
                        final javax.swing.Action toggle = pane.getActionMap().get("turboism-acp.toggle-disclosure");
                        if (toggle != null) {
                            toggle.actionPerformed(new java.awt.event.ActionEvent(
                                    pane, java.awt.event.ActionEvent.ACTION_PERFORMED, "toggle"));
                        }
                    }
                    final java.awt.Component scrollComp = findComponent(window, "turboism-acp.transcript-scroll");
                    if (scrollComp instanceof javax.swing.JScrollPane sp) {
                        sp.getVerticalScrollBar().setValue(sp.getVerticalScrollBar().getMaximum());
                    }
                }
                window.validate();
                captureWindowImage(window, validationStateRoot().resolve("acp-showcase-expanded-thinking.png"));
            });

            // 7. Simulate dark theme semantic defaults in-place inside the host to capture dark theme following, then restore.
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                final java.util.Map<String, Object> saved = new java.util.LinkedHashMap<>();
                final java.util.Map<String, java.awt.Color> dark = java.util.Map.ofEntries(
                        java.util.Map.entry("CubismCommon.background", new java.awt.Color(0x1E1F22)),
                        java.util.Map.entry("CubismCommon.surface", new java.awt.Color(0x2B2D30)),
                        java.util.Map.entry("CubismCommon.inputBackground", new java.awt.Color(0x25262A)),
                        java.util.Map.entry("CubismCommon.foreground", new java.awt.Color(0xDFE1E5)),
                        java.util.Map.entry("CubismCommon.mutedForeground", new java.awt.Color(0x8C919A)),
                        java.util.Map.entry("CubismCommon.blue", new java.awt.Color(0x548AF7)),
                        java.util.Map.entry("CubismCommon.border", new java.awt.Color(0x3E4148)),
                        java.util.Map.entry("CubismCommon.selectionBackground", new java.awt.Color(0x2E436E)),
                        java.util.Map.entry("CubismCommon.selectionForeground", new java.awt.Color(0xFFFFFF)));
                for (var entry : dark.entrySet()) {
                    saved.put(entry.getKey(), javax.swing.UIManager.get(entry.getKey()));
                    javax.swing.UIManager.put(entry.getKey(), entry.getValue());
                }
                try {
                    javax.swing.SwingUtilities.updateComponentTreeUI(window);
                    // Flush queued refreshTheme invokeLater tasks synchronously.
                    for (java.awt.Component child : ((javax.swing.RootPaneContainer) window).getContentPane().getComponents()) {
                        child.validate();
                    }
                    window.validate();
                } finally {
                    // Schedule capture after invokeLater(refreshTheme) runs, so keep dark values briefly.
                }
                // Save restoration map on client property for next invokeAndWait step.
                ((javax.swing.RootPaneContainer) window)
                        .getRootPane()
                        .putClientProperty("acp.probe.savedTheme", saved);
            });
            // Let invokeLater(this::refreshTheme) run on EDT, capture dark window + model menu, then restore original theme.
            Thread.sleep(250L);
            captureButtonPopup(window, "turboism-acp.composer-option.model", "acp-showcase-dark-theme.png");
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                final Object raw = ((javax.swing.RootPaneContainer) window)
                        .getRootPane()
                        .getClientProperty("acp.probe.savedTheme");
                if (raw instanceof java.util.Map<?, ?> saved) {
                    for (var entry : saved.entrySet()) {
                        javax.swing.UIManager.put(entry.getKey(), entry.getValue());
                    }
                    ((javax.swing.RootPaneContainer) window)
                            .getRootPane()
                            .putClientProperty("acp.probe.savedTheme", null);
                    javax.swing.SwingUtilities.updateComponentTreeUI(window);
                }
            });
            Thread.sleep(150L);
            ledger.pass("uiShowcaseScreenshots");
        } catch (Exception failure) {
            logger.error("ACP showcase screenshot capture failed", failure);
            ledger.fail("uiShowcaseScreenshots", failure.getClass().getSimpleName());
        }
    }

    private void captureButtonPopup(
            final java.awt.Window window, final String buttonName, final String outputFileName)
            throws InterruptedException, java.lang.reflect.InvocationTargetException {
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            final java.awt.Component comp = findComponent(window, buttonName);
            if (comp instanceof javax.swing.JButton button) {
                button.doClick();
            }
        });
        Thread.sleep(180L);
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            javax.swing.JPopupMenu activePopup = null;
            for (javax.swing.MenuElement element :
                    javax.swing.MenuSelectionManager.defaultManager().getSelectedPath()) {
                if (element instanceof javax.swing.JPopupMenu popup && popup.isShowing()) {
                    activePopup = popup;
                }
            }
            if (activePopup == null) {
                activePopup = findVisiblePopup();
            }
            if (activePopup != null) {
                activePopup.validate();
                captureCompositePopup(window, activePopup, validationStateRoot().resolve(outputFileName));
                javax.swing.MenuSelectionManager.defaultManager().clearSelectedPath();
                activePopup.setVisible(false);
            } else {
                captureWindowImage(window, validationStateRoot().resolve(outputFileName));
            }
        });
        Thread.sleep(80L);
    }

    private static javax.swing.JPopupMenu findVisiblePopup() {
        for (java.awt.Window w : java.awt.Window.getWindows()) {
            if (!w.isVisible()) continue;
            final javax.swing.JPopupMenu found = findPopupIn(w);
            if (found != null) return found;
        }
        return null;
    }

    private static javax.swing.JPopupMenu findPopupIn(final java.awt.Component comp) {
        if (comp instanceof javax.swing.JPopupMenu popup && popup.isShowing()) return popup;
        if (comp instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                final javax.swing.JPopupMenu found = findPopupIn(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void captureWindowImage(final java.awt.Window window, final Path target) {
        final int w = Math.max(1, window.getWidth());
        final int h = Math.max(1, window.getHeight());
        final java.awt.image.BufferedImage img =
                new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        final java.awt.Graphics2D g = img.createGraphics();
        try {
            window.printAll(g);
        } finally {
            g.dispose();
        }
        try {
            javax.imageio.ImageIO.write(img, "png", target.toFile());
        } catch (IOException ignored) {
        }
    }

    private static void captureCompositeImage(
            final java.awt.Window base, final java.awt.Window overlay, final Path target) {
        final java.awt.Point baseLoc = base.getLocationOnScreen();
        final java.awt.Point overLoc = overlay.getLocationOnScreen();
        final int minX = Math.min(baseLoc.x, overLoc.x);
        final int minY = Math.min(baseLoc.y, overLoc.y);
        final int maxX = Math.max(baseLoc.x + base.getWidth(), overLoc.x + overlay.getWidth());
        final int maxY = Math.max(baseLoc.y + base.getHeight(), overLoc.y + overlay.getHeight());
        final java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                Math.max(1, maxX - minX), Math.max(1, maxY - minY), java.awt.image.BufferedImage.TYPE_INT_RGB);
        final java.awt.Graphics2D g = img.createGraphics();
        try {
            g.translate(baseLoc.x - minX, baseLoc.y - minY);
            base.printAll(g);
            g.translate(-(baseLoc.x - minX), -(baseLoc.y - minY));
            g.setColor(new java.awt.Color(0, 0, 0, 72));
            g.fillRect(baseLoc.x - minX, baseLoc.y - minY, base.getWidth(), base.getHeight());
            g.translate(overLoc.x - minX, overLoc.y - minY);
            overlay.printAll(g);
        } finally {
            g.dispose();
        }
        try {
            javax.imageio.ImageIO.write(img, "png", target.toFile());
        } catch (IOException ignored) {
        }
    }

    private static void captureCompositePopup(
            final java.awt.Window base, final javax.swing.JPopupMenu popup, final Path target) {
        final java.awt.Point baseLoc = safeScreenLocation(base, null);
        final java.awt.Component invoker = popup.getInvoker();
        final java.awt.Point fallback = invoker != null && invoker.isShowing()
                ? new java.awt.Point(
                        safeScreenLocation(invoker, baseLoc).x,
                        Math.max(baseLoc.y, safeScreenLocation(invoker, baseLoc).y - popup.getHeight() - 4))
                : baseLoc;
        final java.awt.Point popLoc = safeScreenLocation(popup, fallback);
        final int minX = Math.min(baseLoc.x, popLoc.x);
        final int minY = Math.min(baseLoc.y, popLoc.y);
        final int maxX = Math.max(baseLoc.x + base.getWidth(), popLoc.x + popup.getWidth());
        final int maxY = Math.max(baseLoc.y + base.getHeight(), popLoc.y + popup.getHeight());
        final java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                Math.max(1, maxX - minX), Math.max(1, maxY - minY), java.awt.image.BufferedImage.TYPE_INT_RGB);
        final java.awt.Graphics2D g = img.createGraphics();
        try {
            g.translate(baseLoc.x - minX, baseLoc.y - minY);
            base.printAll(g);
            g.translate(-(baseLoc.x - minX) + (popLoc.x - minX), -(baseLoc.y - minY) + (popLoc.y - minY));
            popup.printAll(g);
        } finally {
            g.dispose();
        }
        try {
            javax.imageio.ImageIO.write(img, "png", target.toFile());
        } catch (IOException ignored) {
        }
    }

    private static java.awt.Point safeScreenLocation(
            final java.awt.Component comp, final java.awt.Point zeroFallback) {
        try {
            final java.awt.Point loc = comp.getLocationOnScreen();
            if (zeroFallback != null && loc.x == 0 && loc.y == 0 && (zeroFallback.x != 0 || zeroFallback.y != 0)) {
                return zeroFallback;
            }
            return loc;
        } catch (RuntimeException notShowing) {
            return zeroFallback != null ? zeroFallback : new java.awt.Point(0, 0);
        }
    }

    private static java.awt.Component findComponent(final java.awt.Component component, final String name) {
        if (name.equals(component.getName())) return component;
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                final java.awt.Component found = findComponent(child, name);
                if (found != null) return found;
            }
        }
        return null;
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
        dismissWelcomeGuideIfVisible();
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

    private void dismissWelcomeGuideIfVisible() {
        try {
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                for (java.awt.Window window : java.awt.Window.getWindows()) {
                    if (window instanceof javax.swing.JDialog dialog && dialog.isVisible()) {
                        final String title = dialog.getTitle();
                        if (title != null && title.contains("Turboism") && !"turboism-acp.permission".equals(dialog.getName())) {
                            dialog.dispatchEvent(new java.awt.event.WindowEvent(
                                    dialog, java.awt.event.WindowEvent.WINDOW_CLOSING));
                            if (dialog.isVisible()) dialog.setVisible(false);
                        }
                    }
                }
            });
        } catch (Exception ignored) {
        }
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
