package dev.turboism.validation.mcp;

import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.TurboismPlugin;

import dev.turboism.validation.NativeFreeStartup;
import javax.swing.SwingUtilities;
import dev.turboism.tests.plugin.McpValidationHostClose;
import dev.turboism.tests.plugin.McpTexturePersistenceProbe;
import dev.turboism.tests.plugin.McpGeometryRollbackProbe;
import dev.turboism.tests.plugin.McpAuthorizedOpenProbe;
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
    private Thread startupHelper;
    private String lastDialogObservation = "";
    private final java.util.Set<String> approvedOpenPurposes = new java.util.HashSet<>();

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
        // The audited responder must run for every standalone variant: the native
        // save path also reaches the FREE save-restriction dialog without startFree
        // (observed: unassisted 5.3.04 run wedged inside saveDocument's modal
        // showMessageDialog). Inside a session the coordinator owns prompt
        // assistance, so this loop stays off to keep one audited responder.
        if (!Boolean.getBoolean("turboism.validation.session")) {
            startupHelper = new Thread(this::assistStartup, "turboism-mcp-native-prompts");
            startupHelper.setDaemon(true);
            startupHelper.start();
        }
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
        final Thread helper = startupHelper;
        startupHelper = null;
        if (helper != null) helper.interrupt();
    }

    /** Remains responsive while the watcher waits for texture save/reopen on the EDT. */
    private void assistStartup() {
        while (enabled) {
            try {
                SwingUtilities.invokeAndWait(() -> {
                    if (!enabled) return;
                    final String observation = NativeFreeStartup.describeDialogs();
                    if (!observation.equals(lastDialogObservation)) {
                        lastDialogObservation = observation;
                        logger.info("MCP_VALIDATION_DIALOG_OBSERVATION " + observation);
                    }
                    if (!Boolean.getBoolean("turboism.validation.readOnlyInspection")
                            && !headEditWorkload()) approveTaskOpenChooser();
                    final String action = NativeFreeStartup.select();
                    if (action != null) logger.info("MCP_VALIDATION_" + action);
                });
                Thread.sleep(500L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (java.lang.reflect.InvocationTargetException failure) {
                logger.error("MCP native prompt helper failed", failure.getCause());
                return;
            }
        }
    }

    private static boolean headEditWorkload() {
        return Boolean.getBoolean("turboism.validation.headEditing") || Boolean.getBoolean("turboism.validation.headPoseQA");
    }

    /** Test-only UI approval: each exact READ purpose once, only the owned task fixture. */
    private void approveTaskOpenChooser() {
        final String runId = System.getProperty("turboism.validation.runId", "");
        if (!runId.matches("[a-zA-Z0-9-]{8,96}")) return;
        final String writeTitle = "MCP file access (WRITE): Turboism validation SAVE AS " + runId;
        final String savedTitle = "MCP file access (READ): Turboism validation SAVED CMO3 OPEN " + runId;
        final var titles = java.util.Set.of("MCP file access (READ): Turboism validation CMO3 OPEN " + runId,
                "MCP file access (READ): Turboism validation CMO3 FRESH OPEN " + runId, writeTitle, savedTitle,
                "MCP file access (READ): Turboism validation ORIGINAL CMO3 RESTORE " + runId);
        final Path fixture = Path.of(System.getProperty("turboism.validation.fixture", "")).toAbsolutePath();
        try {
            if (!fixture.getFileName().toString().equals(runId + ".cmo3")
                    || !fixture.getParent().getFileName().toString().equals(runId)
                    || !Files.isRegularFile(fixture, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    || !fixture.equals(fixture.toRealPath())
                    || !validationStateRoot().getParent().toRealPath().getParent().equals(fixture.getParent())) return;
            for (java.awt.Window window : java.awt.Window.getWindows()) {
                if (!window.isShowing() || !(window instanceof java.awt.Dialog dialog)
                        || !titles.contains(dialog.getTitle()) || approvedOpenPurposes.contains(dialog.getTitle())) continue;
                final String title = dialog.getTitle();
                boolean write = title.equals(writeTitle);
                final javax.swing.JFileChooser chooser = findChooser(dialog);
                if (chooser == null || !title.equals(chooser.getDialogTitle())
                        || chooser.getDialogType() != (write ? javax.swing.JFileChooser.SAVE_DIALOG : javax.swing.JFileChooser.OPEN_DIALOG)
                        || chooser.isMultiSelectionEnabled() || chooser.isAcceptAllFileFilterUsed()
                        || !(chooser.getFileFilter() instanceof javax.swing.filechooser.FileNameExtensionFilter filter)
                        || !java.util.Arrays.equals(filter.getExtensions(), new String[] {"cmo3"})) return;
                Path target = (write || title.equals(savedTitle))
                        ? McpAuthorizedOpenProbe.authorizedOutput(validationStateRoot().getParent(), fixture, runId) : fixture;
                if (write) {
                    target = McpAuthorizedOpenProbe.authorizedNewOutput(validationStateRoot().getParent(), fixture, runId);
                    Files.createDirectories(target.getParent());
                } else if (!Files.isRegularFile(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                        || !target.equals(target.toRealPath())) return;
                approvedOpenPurposes.add(title);
                chooser.setSelectedFile(target.toFile());
                chooser.approveSelection();
                logger.info("MCP_VALIDATION_TASK_OPEN_CHOOSER_APPROVED");
                return;
            }
        } catch (Exception failure) {
            logger.info("MCP_VALIDATION_TASK_OPEN_CHOOSER_REJECTED");
        }
    }

    private static javax.swing.JFileChooser findChooser(java.awt.Container parent) {
        for (java.awt.Component child : parent.getComponents()) {
            if (child instanceof javax.swing.JFileChooser chooser) return chooser;
            if (child instanceof java.awt.Container nested) {
                var chooser = findChooser(nested);
                if (chooser != null) return chooser;
            }
        }
        return null;
    }

    private void awaitResult() {
        if (dev.turboism.validation.SessionGate.enabled()
                && dev.turboism.validation.SessionGate.awaitTurn(
                    context.paths().stateDir(), logger::info) == null) {
            return;
        }
        if (Boolean.getBoolean("turboism.validation.ownedMocOnly")) {
            if (!awaitTaskDocument()) {
                CoreOwnedMocProbe.notStarted(context, validationStateRoot(), "Task fixture readiness failed or timed out");
                if (dev.turboism.validation.SessionGate.enabled()) {
                    dev.turboism.validation.SessionGate.complete(context.paths().stateDir(), "FAIL");
                }
                return;
            }
            if (!CoreOwnedMocProbe.run(context, validationStateRoot())) {
                if (dev.turboism.validation.SessionGate.enabled()) {
                    dev.turboism.validation.SessionGate.complete(context.paths().stateDir(), "FAIL");
                }
                return;
            }
            if (dev.turboism.validation.SessionGate.enabled()) {
                dev.turboism.validation.SessionGate.complete(context.paths().stateDir(), "PASS");
                return;
            }
            requestAutomatedHostClose("core-owned-moc-result.properties");
            return;
        }
        if (Boolean.getBoolean("turboism.validation.ownedMoc")) {
            if (!CoreOwnedMocProbe.run(context, validationStateRoot())) return;
        }
        final Path result = validationStateRoot().resolve(RESULT_FILE);
        final long deadline = System.currentTimeMillis() + RESULT_TIMEOUT_MILLIS;
        final var freshOpen = new McpAuthorizedOpenProbe(context, validationStateRoot());
        final var productionPersistence = new McpAuthorizedOpenProbe(context, validationStateRoot(), true);
        boolean textureRoundTripHandled = false;
        boolean geometryRollbackHandled = false;
        boolean geometryPersistenceHandled = false;
        boolean authoringDiagnosticHandled = false;
        while (enabled && System.currentTimeMillis() < deadline) {
            if (!authoringDiagnosticHandled
                    && Files.isRegularFile(validationStateRoot().resolve("mcp-authoring-diagnostic-request"))) {
                authoringDiagnosticHandled = true;
                inspectStoredAuthoring();
            }
            if (!Boolean.getBoolean("turboism.validation.readOnlyInspection") && !headEditWorkload() && !Boolean.getBoolean("turboism.validation.headPersistence") && !textureRoundTripHandled) {
                textureRoundTripHandled = McpTexturePersistenceProbe.runIfRequested(context, validationStateRoot());
            }
            if (!Boolean.getBoolean("turboism.validation.readOnlyInspection") && !geometryRollbackHandled) {
                geometryRollbackHandled = McpGeometryRollbackProbe.runIfRequested(context, validationStateRoot());
            }
            if (!Boolean.getBoolean("turboism.validation.readOnlyInspection") && !headEditWorkload() && !Boolean.getBoolean("turboism.validation.headPersistence") && !geometryPersistenceHandled) {
                geometryPersistenceHandled = McpTexturePersistenceProbe.runGeometryIfRequested(context, validationStateRoot());
            }
            if (!Boolean.getBoolean("turboism.validation.readOnlyInspection")
                    && !headEditWorkload()) {
                freshOpen.poll();
                productionPersistence.poll();
            }
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

    /** Test-only typed SDK read diagnostics; no raw host access, setters or exception messages. */
    private void inspectStoredAuthoring() {
        final var rows = new java.util.ArrayList<java.util.Map<String, Object>>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                final var model = context.cubism().model().active();
                for (final var deformer : model.deformers().all().stream().limit(32).toList()) {
                    final var row = new java.util.LinkedHashMap<String, Object>();
                    row.put("objectId", deformer.id().value());
                    row.put("warp", deformer instanceof dev.turboism.sdk.cubism.model.WarpDeformer);
                    row.put("rotation", deformer instanceof dev.turboism.sdk.cubism.model.RotationDeformer);
                    try {
                        if (deformer instanceof dev.turboism.sdk.cubism.model.WarpDeformer warp) {
                            final var snapshot = warp.authoringSnapshot();
                            row.put("rows", snapshot.rows());
                            row.put("columns", snapshot.columns());
                            row.put("formCount", snapshot.forms().size());
                        } else if (deformer instanceof dev.turboism.sdk.cubism.model.RotationDeformer rotation) {
                            row.put("formCount", rotation.authoringSnapshot().forms().size());
                        }
                        row.put("status", deformer instanceof dev.turboism.sdk.cubism.model.WarpDeformer
                                || deformer instanceof dev.turboism.sdk.cubism.model.RotationDeformer
                                ? "SNAPSHOT_READ" : "BASE_INTERFACE_ONLY");
                    } catch (RuntimeException failure) {
                        Throwable cause = failure;
                        for (int depth = 0; depth < 8 && cause.getCause() != null; depth++) cause = cause.getCause();
                        row.put("status", "FAILED");
                        row.put("exceptionClass", cause.getClass().getName());
                        row.put("stack", java.util.Arrays.stream(cause.getStackTrace()).limit(8)
                                .map(frame -> frame.getClassName() + "." + frame.getMethodName() + ":" + frame.getLineNumber())
                                .toList());
                    }
                    rows.add(row);
                }
            });
            Files.writeString(validationStateRoot().resolve("mcp-authoring-diagnostic.json"),
                    dev.turboism.sdk.json.Json.stringify(java.util.Map.of(
                            "runId", System.getProperty("turboism.validation.runId"), "objects", rows)));
        } catch (Exception failure) {
            logger.warn("Typed stored authoring diagnostic failed: " + failure.getClass().getSimpleName());
        }
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
        requestAutomatedHostClose(RESULT_FILE);
    }

    /** Keeps standalone Core validation bound to the Runner's loaded fixture before normal close. */
    private boolean awaitTaskDocument() {
        final String fixture = System.getProperty("turboism.validation.fixtureName", "");
        if (fixture.isBlank()) return false;
        final long deadline = System.currentTimeMillis() + 120_000L;
        while (enabled && System.currentTimeMillis() < deadline) {
            try {
                final java.util.concurrent.FutureTask<Boolean> ready = new java.util.concurrent.FutureTask<>(() -> {
                    int matched = 0;
                    for (final var window : java.awt.Window.getWindows()) {
                        if (window instanceof java.awt.Dialog dialog && dialog.isVisible() && dialog.isModal()) return false;
                        if (window instanceof java.awt.Frame frame && frame.isVisible() && frame.isDisplayable()
                                && frame.getTitle().contains(fixture)) matched++;
                    }
                    return matched == 1;
                });
                SwingUtilities.invokeLater(ready);
                // Model loading runs on the EDT and can exceed five seconds. Use the
                // existing overall startup deadline rather than abandoning its queued read.
                if (ready.get(Math.max(1L, deadline - System.currentTimeMillis()),
                        java.util.concurrent.TimeUnit.MILLISECONDS)) return true;
                Thread.sleep(500L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            } catch (Exception failure) {
                logger.error("Standalone Core fixture readiness failed", failure);
                return false;
            }
        }
        logger.warn("Standalone Core fixture readiness timed out; no native assertions executed");
        return false;
    }

    private void requestAutomatedHostClose(final String resultFile) {
        try {
            final String outcome = McpValidationHostClose.request(
                Boolean.getBoolean("turboism.validation.exitOnComplete"),
                enabled,
                terminalResult(validationStateRoot().resolve(resultFile)),
                System.getProperty("turboism.validation.runId"),
                System.getProperty("turboism.validation.hostVersion")
            );
            logger.info("MCP_HOST_CLOSE " + outcome);
        } catch (Exception failure) {
            logger.error("Automated MCP host close request failed", failure);
        }
    }
}
