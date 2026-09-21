package dev.turboism.validation.editprotocol;

import dev.turboism.sdk.cubism.command.EditorExternalAppSettingsRequest;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.TurboismPlugin;

import dev.turboism.tests.plugin.EditProtocolValidationHostClose;

import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.Window;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Task-local probe that makes the external protocol client run unattended.
 *
 * <p>Responsibilities, all kill-switched by
 * {@code -Dturboism.editProtocol.validation.enabled=false}:
 * <ul>
 *   <li>Start the host's own external-application service on port 22033 through the
 *       verified {@code EXTERNAL_APP_SETTING} typed command (retrying until the editor
 *       finishes booting).</li>
 *   <li>Pre-seed native plugin authorization: tokens listed in
 *       {@code state/<id>/auth-allow.txt} are written through
 *       {@code CExternalAppAuthManager.setAuth(token, null, true)}, the same persisted
 *       grant the native approval UI would produce, then read back through
 *       {@code getAuth(token, null)} so {@code auth.granted} is only recorded for a
 *       grant the host itself confirms. The {@code null} origin matches the probe
 *       connection, which carries no {@code Origin} header; {@code RegisterPlugin}
 *       therefore takes the already-authorized path and no native dialog appears.</li>
 *   <li>Answer Turboism's edit-approval dialog: while {@code approval.mode} exists its
 *       content ({@code approve}|{@code deny}) is applied to the "Turboism Edit Session"
 *       JOptionPane. Without the file the dialog is left untouched — production stays
 *       manual.</li>
 *   <li>Close the host through the shared bounded close helper once the external client
 *       publishes a terminal result for this run.</li>
 * </ul>
 */
public final class EditProtocolHostValidationPlugin implements TurboismPlugin {

    private static final String ENABLED_PROPERTY =
        "turboism.editProtocol.validation.enabled";
    private static final String EDIT_DIALOG_TITLE = "Turboism Edit Session";
    private static final int EXTERNAL_APP_PORT = 22033;
    private static final String RESULT_FILE =
        "edit-protocol-host-validation-result.properties";
    private static final String EVIDENCE_FILE =
        "edit-protocol-host-probe.properties";
    private static final long RESULT_TIMEOUT_MILLIS = 1_200_000L;
    private static final long SERVICE_DEADLINE_MILLIS = 150_000L;

    private PluginContext context;
    private PluginLogger logger;
    private volatile boolean enabled;
    private final List<String> evidence = new CopyOnWriteArrayList<>();
    private final java.util.Set<String> grantedTokens =
        ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> unverifiedTokens =
        ConcurrentHashMap.newKeySet();
    private final AtomicInteger dialogDecisions = new AtomicInteger();
    private final AtomicInteger dialogDenials = new AtomicInteger();
    private final java.util.Set<Window> answeredDialogs =
        ConcurrentHashMap.newKeySet();
    private Thread serviceThread;
    private Thread authThread;
    private Thread dialogThread;
    private Thread resultThread;
    private volatile Method setAuthMethod;
    private volatile Method getAuthMethod;
    private volatile Object authManager;
    private volatile boolean authManagerResolved;

    @Override
    public void init(final PluginContext context) {
        this.context = context;
        this.logger = context.logger();
        logger.info("Edit-protocol host validation probe initialized");
    }

    @Override
    public void enable() {
        if (serviceThread != null) return;
        if (!Boolean.parseBoolean(
            System.getProperty(ENABLED_PROPERTY, "true"))) {
            logger.info("EDIT_PROTOCOL_PROBE_DISABLED "
                + ENABLED_PROPERTY + "=false");
            return;
        }
        enabled = true;
        snapshotHistory("history.before");
        serviceThread = start("turboism-edit-protocol-service",
            this::enableExternalAppService);
        authThread = start("turboism-edit-protocol-auth", this::seedAuthorization);
        dialogThread = start("turboism-edit-protocol-dialog", this::answerDialogs);
        resultThread = start("turboism-edit-protocol-result", this::awaitResult);
        logger.info("EDIT_PROTOCOL_PROBE_READY");
    }

    @Override
    public void disable() {
        stopAll();
    }

    @Override
    public void shutdown() {
        stopAll();
    }

    private Thread start(final String name, final Runnable body) {
        final Thread thread = new Thread(body, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private void stopAll() {
        enabled = false;
        for (final Thread thread
            : new Thread[]{serviceThread, authThread, dialogThread, resultThread}) {
            if (thread != null) thread.interrupt();
        }
        serviceThread = authThread = dialogThread = resultThread = null;
    }

    private void record(final String line) {
        evidence.add(line);
        publishEvidence();
    }

    private Path stateDir() {
        return context.paths().stateDir();
    }

    private Path stateRoot() {
        final Path parent = stateDir().getParent();
        if (parent == null) {
            throw new IllegalStateException("plugin state directory has no parent");
        }
        return parent;
    }

    private void publishEvidence() {
        try {
            final Path path = stateRoot().resolve(EVIDENCE_FILE);
            final Path temp = path.resolveSibling("." + EVIDENCE_FILE + ".tmp");
            Files.write(temp, evidence, StandardCharsets.UTF_8);
            Files.move(temp, path,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception failure) {
            logger.warn("edit-protocol probe evidence write failed: " + failure);
        }
    }

    // ------------------------------------------------------------------
    // service enable
    // ------------------------------------------------------------------

    private void enableExternalAppService() {
        final long deadline = System.currentTimeMillis() + SERVICE_DEADLINE_MILLIS;
        int attempts = 0;
        while (enabled && System.currentTimeMillis() < deadline) {
            attempts++;
            try {
                final var result = context.editorCommands().execute(
                    new EditorExternalAppSettingsRequest(EXTERNAL_APP_PORT, false));
                record("service.attempt=" + attempts + " status=" + result.status());
                if (result.executed()) {
                    logger.info("EDIT_PROTOCOL_SERVICE_STARTED port="
                        + EXTERNAL_APP_PORT);
                    record("service.port=" + EXTERNAL_APP_PORT);
                    return;
                }
            } catch (Exception failure) {
                record("service.attempt=" + attempts + " error="
                    + failure.getClass().getSimpleName());
            }
            sleep(2_000L);
        }
        if (enabled) {
            logger.warn("EDIT_PROTOCOL_SERVICE_TIMEOUT after " + attempts + " attempts");
            record("service.result=TIMEOUT");
        }
    }

    // ------------------------------------------------------------------
    // native authorization pre-seed
    // ------------------------------------------------------------------

    private void seedAuthorization() {
        while (enabled) {
            try {
                for (final String token : readAllowTokens()) {
                    if (!grantedTokens.contains(token) && grant(token)) {
                        grantedTokens.add(token);
                        record("auth.granted=" + mask(token));
                    }
                }
            } catch (Exception failure) {
                record("auth.error=" + failure.getClass().getSimpleName());
            }
            sleep(250L);
        }
    }

    private List<String> readAllowTokens() {
        try {
            final Path file = stateDir().resolve("auth-allow.txt");
            if (!Files.isRegularFile(file)) return List.of();
            return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();
        } catch (Exception failure) {
            return List.of();
        }
    }

    private boolean grant(final String token) {
        if (!authManagerResolved) {
            resolveAuthManager();
        }
        if (authManager == null || setAuthMethod == null) {
            return false;
        }
        try {
            if (!Boolean.TRUE.equals(readAuth(token))) {
                setAuthMethod.invoke(authManager, token, null, Boolean.TRUE);
            }
            // The grant only counts when the manager itself confirms it; a failed
            // readback stays ungranted so the poll retries rather than trusting
            // setAuth blindly.
            if (getAuthMethod == null || Boolean.TRUE.equals(readAuth(token))) {
                return true;
            }
            if (unverifiedTokens.add(token)) {
                record("auth.unverified=" + mask(token));
            }
            return false;
        } catch (Exception failure) {
            record("auth.error=" + failure.getClass().getSimpleName()
                + " token=" + mask(token));
            return false;
        }
    }

    private Boolean readAuth(final String token) {
        if (authManager == null || getAuthMethod == null) {
            return null;
        }
        try {
            return (Boolean) getAuthMethod.invoke(authManager, token, null);
        } catch (Exception failure) {
            return null;
        }
    }

    private void resolveAuthManager() {
        final String name = "com.live2d.cubism.doc.webSocket.CExternalAppAuthManager";
        for (final ClassLoader loader : new ClassLoader[]{
            Thread.currentThread().getContextClassLoader(),
            getClass().getClassLoader(),
            ClassLoader.getSystemClassLoader()}) {
            if (loader == null) continue;
            try {
                final Class<?> manager = Class.forName(name, true, loader);
                final Field instance = manager.getDeclaredField("INSTANCE");
                instance.setAccessible(true);
                authManager = instance.get(null);
                setAuthMethod = manager.getMethod(
                    "setAuth", String.class, String.class, boolean.class);
                try {
                    getAuthMethod = manager.getMethod(
                        "getAuth", String.class, String.class);
                } catch (Throwable missing) {
                    record("auth.verify=unsupported");
                }
                authManagerResolved = true;
                record("auth.manager=RESOLVED loader=" + loader.getClass().getSimpleName());
                return;
            } catch (Throwable ignored) {
                // try the next loader
            }
        }
        record("auth.manager=UNRESOLVED");
    }

    private static String mask(final String token) {
        return token.length() <= 8
            ? "***"
            : token.substring(0, 4) + "…" + token.substring(token.length() - 4);
    }

    // ------------------------------------------------------------------
    // Turboism edit-approval dialog auto-answer
    // ------------------------------------------------------------------

    private void answerDialogs() {
        while (enabled) {
            try {
                final String mode = readApprovalMode();
                if (mode != null) {
                    for (final Window window : Window.getWindows()) {
                        if (window instanceof JDialog dialog
                            && dialog.isShowing()
                            && !answeredDialogs.contains(dialog)
                            && EDIT_DIALOG_TITLE.equals(dialog.getTitle())
                            && answeredDialogs.add(dialog)) {
                            answer(dialog, mode);
                        }
                    }
                }
            } catch (Exception failure) {
                record("dialog.error=" + failure.getClass().getSimpleName());
            }
            sleep(100L);
        }
    }

    /** {@return "approve"|"deny" or null when the mode file is absent} */
    private String readApprovalMode() {
        try {
            final Path file = stateDir().resolve("approval.mode");
            if (!Files.isRegularFile(file)) return null;
            final String mode = Files.readString(file, StandardCharsets.UTF_8).trim();
            return mode.equals("deny") ? "deny" : "approve";
        } catch (Exception failure) {
            return "approve";
        }
    }

    private void answer(final JDialog dialog, final String mode) {
        final int option = mode.equals("deny")
            ? JOptionPane.NO_OPTION : JOptionPane.YES_OPTION;
        for (final var component : dialog.getContentPane().getComponents()) {
            if (component instanceof JOptionPane pane) {
                SwingUtilities.invokeLater(() -> pane.setValue(option));
                final int count = dialogDecisions.incrementAndGet();
                if (option == JOptionPane.NO_OPTION) dialogDenials.incrementAndGet();
                record("dialog.decision=" + mode + " count=" + count);
                logger.info("EDIT_PROTOCOL_DIALOG decision=" + mode);
                return;
            }
        }
    }

    // ------------------------------------------------------------------
    // terminal result -> evidence -> bounded host close
    // ------------------------------------------------------------------

    private void awaitResult() {
        final Path result = stateRoot().resolve(RESULT_FILE);
        final long deadline = System.currentTimeMillis() + RESULT_TIMEOUT_MILLIS;
        while (enabled && System.currentTimeMillis() < deadline) {
            if (terminalResult(result)) {
                logger.info("EDIT_PROTOCOL_RESULT observed=" + result.getFileName());
                snapshotHistory("history.after");
                record("dialog.decisions=" + dialogDecisions.get());
                record("dialog.denials=" + dialogDenials.get());
                record("auth.tokens=" + grantedTokens.size());
                requestAutomatedHostClose();
                return;
            }
            sleep(500L);
        }
        if (enabled) {
            logger.warn("edit-protocol probe timed out waiting for terminal result");
        }
    }

    private static boolean terminalResult(final Path result) {
        if (!Files.isRegularFile(result)) return false;
        try {
            final List<String> lines = Files.readAllLines(result, StandardCharsets.UTF_8);
            final String runId = System.getProperty("turboism.validation.runId");
            return runId != null && lines.contains("runId=" + runId)
                && lines.stream().anyMatch(
                    line -> line.equals("status=PASS") || line.equals("status=FAIL"));
        } catch (Exception ignored) {
            return false;
        }
    }

    private void snapshotHistory(final String prefix) {
        try {
            final HistorySnapshot snapshot = context.cubism().history().snapshot();
            record(prefix + ".availability=" + snapshot.availability());
            record(prefix + ".entries=" + snapshot.entries().size());
            record(prefix + ".position=" + snapshot.position());
            record(prefix + ".canUndo=" + snapshot.canUndo());
            record(prefix + ".canRedo=" + snapshot.canRedo());
        } catch (Exception failure) {
            record(prefix + ".error=" + failure.getClass().getSimpleName());
        }
    }

    private void requestAutomatedHostClose() {
        try {
            final String outcome = EditProtocolValidationHostClose.request(
                Boolean.getBoolean("turboism.validation.exitOnComplete"),
                enabled,
                terminalResult(stateRoot().resolve(RESULT_FILE)),
                System.getProperty("turboism.validation.runId"),
                System.getProperty("turboism.validation.hostVersion")
            );
            logger.info("EDIT_PROTOCOL_HOST_CLOSE " + outcome);
            record("close.outcome=" + outcome);
        } catch (Exception failure) {
            logger.error("Automated edit-protocol host close request failed", failure);
            record("close.error=" + failure.getClass().getSimpleName()
                + " message=" + String.valueOf(failure.getMessage()));
        }
    }

    private static void sleep(final long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
