package dev.turboism.validation.statusbar;

import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.TurboismPlugin;
import dev.turboism.sdk.ui.StatusNotification;

import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Task-local exerciser for the native CX bottom status-bar matrix on an exact
 * Cubism host. It only uses the public SDK ({@code uiHost().notifyStatus}) plus
 * plain JDK AWT/Swing window scanning; it never imports or reflects
 * {@code com.live2d.*} types. All Swing scans run on the EDT and every host
 * mutation is a UI status notification only (no authoring mutation).
 */
public final class StatusBarHostValidationPlugin implements TurboismPlugin {

    private static final String RESULT = "matrix-result.txt";
    private static final String MANAGER_CLEANUP_RESULT = "manager-cleanup-result.txt";
    private static final String MODE_PROPERTY = "turboism.validation.statusBar.mode";
    private static final String PLUGIN_ID = "dev.turboism.validation.statusbar";
    private static final long DOCUMENT_READY_TIMEOUT_MILLIS = 180_000L;
    private static final long SETTLE_STEP_MILLIS = 2_000L;
    private static final long PASS_SETTLE_MILLIS = 3_000L;
    private static final long BOOTSTRAP_SETTLE_MILLIS = 5_000L;

    /** Same local id for the whole matrix; the runtime scopes it by plugin. */
    private static final String STATUS_ID = "status";
    private static final String TOKEN = "turboism-status-probe";

    private PluginLogger logger;
    private PluginContext context;
    private Path stateDir;
    private volatile boolean disableObserved;
    private volatile boolean shutdownObserved;

    @Override
    public void init(final PluginContext context) {
        this.context = context;
        this.logger = context.logger();
        this.stateDir = context.paths().stateDir();
        final Thread exerciser = new Thread(this::runWhenHostReady, "status-bar-host-exerciser");
        exerciser.setDaemon(true);
        exerciser.start();
    }

    @Override
    public void enable() {
        logger.info("STATUS_BAR_EXERCISER_ENABLED");
    }

    @Override
    public void disable() {
        disableObserved = true;
        logger.info("STATUS_BAR_EXERCISER_DISABLED");
    }

    @Override
    public void shutdown() {
        shutdownObserved = true;
        logger.info("STATUS_BAR_EXERCISER_SHUTDOWN");
    }

    private void runWhenHostReady() {
        final String mode = System.getProperty(MODE_PROPERTY, "manager");
        final String expectedVersion = "fail-closed-5203".equals(mode) ? "5.2.03" : "5.3.02";
        final Optional<String> modelId = awaitVerifiedModel(expectedVersion);
        if (modelId.isEmpty()) {
            writeResult(false, mode, "missing", "missing", List.of("readiness timeout"));
            logger.warn("STATUS_BAR_EXERCISER_READY_TIMEOUT"
                + " reason=active-host-or-verified-model-not-present"
                + " timeoutMillis=" + DOCUMENT_READY_TIMEOUT_MILLIS);
            logger.warn("STATUS_BAR_MATRIX_RESULT status=FAIL"
                + " phase=readiness hostActive=false documentSignal=false modelReady=false");
            Runtime.getRuntime().halt(2);
            return;
        }
        logger.info("STATUS_BAR_EXERCISER_READY"
            + " hostState=ACTIVE documentSignal=verified-modeling-document"
            + " modelId=" + modelId.orElseThrow());
        runMatrix(modelId.orElseThrow());
    }

    private Optional<String> awaitVerifiedModel(final String expectedVersion) {
        final long deadline = System.currentTimeMillis() + DOCUMENT_READY_TIMEOUT_MILLIS;
        String lastFailure = "none";
        while (System.currentTimeMillis() < deadline) {
            try {
                final String modelId = activeModelId();
                if (activeRuntimeReportPresent(expectedVersion)) {
                    logger.info("STATUS_BAR_EXERCISER_HOST_READY"
                        + " hostState=ACTIVE documentSignal=verified-modeling-document"
                        + " modelId=" + modelId);
                    return Optional.of(modelId);
                }
                lastFailure = "active-runtime-report-not-present";
            } catch (RuntimeException unavailable) {
                lastFailure = unavailable.getClass().getSimpleName();
            }
            try {
                Thread.sleep(SETTLE_STEP_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
        logger.warn("STATUS_BAR_EXERCISER_MODEL_TIMEOUT lastFailure=" + lastFailure);
        return Optional.empty();
    }

    /** READY/MATCHED is emitted only from a non-stopped HostSession.State.ACTIVE snapshot. */
    private boolean activeRuntimeReportPresent(final String expectedVersion) {
        final Path report = stateDir.getParent().resolve("runtime/preview-runtime-report.json");
        try {
            final String json = Files.readString(report);
            return json.contains("\"version\":\"" + expectedVersion + "\"")
                && json.contains("\"identityState\":\"MATCHED\"")
                && json.contains("\"adapterState\":\"READY\"")
                && json.contains("\"runtimeState\":\"RUNNING\"");
        } catch (java.io.IOException unavailable) {
            return false;
        }
    }

    private String activeModelId() {
        return context.cubism().model().active().id().value();
    }

    private void runMatrix(final String beforeModelId) {
        final String mode = System.getProperty(MODE_PROPERTY, "manager");
        logger.info("STATUS_BAR_MATRIX_BEGIN"
            + " mode=" + mode
            + " hostState=ACTIVE documentSignal=verified-modeling-document"
            + " beforeModelId=" + beforeModelId
            + " authoringMutation=false undo=NA dirty=NA persistence=NA documentLifecycle=NA_APP_SCOPED");

        final List<String> failures = new ArrayList<>();
        try {
            if ("fail-closed-5203".equals(mode)) {
                runFailClosedSteps(failures);
            } else {
                runMatrixSteps(failures);
            }
        } catch (RuntimeException | Error failure) {
            failures.add("matrix failed safely: " + failure.getClass().getName());
        }

        final String afterModelId = readActiveModelId("after-matrix", failures);
        if (!beforeModelId.equals(afterModelId)) {
            failures.add("modelId changed: " + beforeModelId + " -> " + afterModelId);
        }

        if ("manager".equals(mode)) {
            runManagerUnload(failures);
        } else if ("process-exit".equals(mode)) {
            leaveLiveStatusForProcessExit(failures);
        } else if ("fail-closed-5203".equals(mode)) {
            runFailClosedManagerUnload(failures);
        } else {
            failures.add("unsupported mode: " + mode);
        }

        boolean pass = failures.isEmpty();
        if (!writeResult(pass, mode, beforeModelId, afterModelId, failures)) {
            failures.add("terminal result file could not be written");
            pass = false;
        }
        if (!"manager".equals(mode) && !"fail-closed-5203".equals(mode)) {
            logger.info("STATUS_BAR_MATRIX_RESULT status=" + (pass ? "PASS" : "FAIL")
                + " mode=" + mode
                + " authoringMutation=false undo=NA dirty=NA persistence=NA documentLifecycle=NA_APP_SCOPED"
                + " hostState=ACTIVE documentSignal=verified-modeling-document"
                + " beforeModelId=" + beforeModelId
                + " afterModelId=" + afterModelId
                + " failures=" + failures.size());
            for (String failure : failures) {
                logger.warn("STATUS_BAR_MATRIX_FAILURE " + failure);
            }
        }
        try {
            Thread.sleep(PASS_SETTLE_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        Runtime.getRuntime().exit(pass ? 0 : 2);
    }

    private String readActiveModelId(final String phase, final List<String> failures) {
        try {
            return activeModelId();
        } catch (RuntimeException failure) {
            failures.add(phase + " failed: " + failure.getClass().getSimpleName());
            return "missing";
        }
    }

    private boolean writeResult(
        final boolean pass,
        final String mode,
        final String beforeModelId,
        final String afterModelId,
        final List<String> failures
    ) {
        final StringBuilder result = new StringBuilder()
            .append("status=").append(pass ? "PASS" : "FAIL").append('\n')
            .append("mode=").append(mode).append('\n')
            .append("beforeModelId=").append(beforeModelId).append('\n')
            .append("afterModelId=").append(afterModelId).append('\n')
            .append("failures=").append(failures.size()).append('\n');
        for (int index = 0; index < failures.size(); index++) {
            result.append("failure.").append(index).append('=')
                .append(failures.get(index).replace('\n', ' ')).append('\n');
        }
        try {
            Files.writeString(stateDir.resolve(RESULT), result);
            return true;
        } catch (java.io.IOException failure) {
            return false;
        }
    }


    private void runMatrixSteps(final List<String> failures) {
        // 1. Initial state: no test label exists yet.
        assertNoTokenLabel("initial", failures);

        // 2. INFO insert: a JLabel with "[I] " + token appears.
        final Registration info = notify("INFO", failures);
        final LabelView infoLabel = requireLabel("[I] ", "info-insert", failures);

        // 3. WARNING update: same Swing JLabel identity, text becomes "[!] " + token.
        final Registration warning = notify("WARNING", failures);
        final LabelView warningLabel = requireLabel("[!] ", "warning-update", failures);
        if (!sameIdentity(infoLabel, warningLabel)) {
            failures.add("warning-update expected same JLabel identity");
        }
        if (requireAbsent("[I] ", "warning-update-clears-info-prefix", failures) != null) {
            failures.add("warning-update leaves an [I] label");
        }

        // 4. Closing the stale INFO registration must not remove the current widget.
        close(info, "stale-info-close", failures);
        final LabelView afterStaleClose = requireLabel("[!] ", "stale-close-keeps-widget", failures);
        if (!sameIdentity(warningLabel, afterStaleClose)) {
            failures.add("stale-close changes the current widget identity");
        }

        // 5. Closing the current registration removes the widget.
        close(warning, "current-warning-close", failures);
        assertNoTokenLabel("warning-close-removes", failures);

        // 6. ERROR recreate then close.
        final Registration error = notify("ERROR", failures);
        requireLabel("[X] ", "error-insert", failures);
        close(error, "error-close", failures);
        assertNoTokenLabel("error-close-removes", failures);
    }

    private void runFailClosedSteps(final List<String> failures) {
        assertNoTokenLabel("5203-initial", failures);
        final Registration registration = notify("INFO", failures);
        assertNoTokenLabel("5203-notify-must-not-inject", failures);
        close(registration, "5203-registration-close", failures);
        assertNoTokenLabel("5203-close-remains-absent", failures);
        logger.info("STATUS_BAR_FAIL_CLOSED_5203 visibleHostWidget=false");
    }

    private void runFailClosedManagerUnload(final List<String> failures) {
        try {
            Thread.sleep(BOOTSTRAP_SETTLE_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            failures.add("5203 bootstrap settle interrupted");
            return;
        }
        context.disposableScope().register(this::recordManagerScopeCleanup);
        assertNoTokenLabel("5203-before-manager-unload", failures);
        logger.info("STATUS_BAR_FAIL_CLOSED_5203_MANAGER_UNLOAD_BEGIN hostState=ACTIVE");
        if (!invokeManagerShutdown(failures)) {
            return;
        }
        verifyManagerUnload(failures);
    }

    private void runManagerUnload(final List<String> failures) {
        context.disposableScope().register(this::recordManagerScopeCleanup);
        notify("INFO", failures);
        requireLabel("[I] ", "manager-cleanup-insert", failures);
        logger.info("STATUS_BAR_MANAGER_UNLOAD_BEGIN hostState=ACTIVE");
        if (!invokeManagerShutdown(failures)) {
            return;
        }
        verifyManagerUnload(failures);
    }

    private void recordManagerScopeCleanup() throws java.io.IOException {
        final boolean statusRemoved = scan(null).isEmpty();
        final boolean pass = disableObserved && shutdownObserved && statusRemoved;
        Files.writeString(
            stateDir.resolve(MANAGER_CLEANUP_RESULT),
            "status=" + (pass ? "PASS" : "FAIL") + "\n"
                + "disable=" + (disableObserved ? "PASS" : "FAIL") + "\n"
                + "shutdown=" + (shutdownObserved ? "PASS" : "FAIL") + "\n"
                + "statusRemoved=" + (statusRemoved ? "PASS" : "FAIL") + "\n"
        );
        if (!pass) {
            throw new IllegalStateException("manager scope cleanup matrix failed");
        }
    }

    private boolean invokeManagerShutdown(final List<String> failures) {
        try {
            final Class<?> agent = Class.forName("dev.turboism.bootstrap.TurboismAgent");
            final java.lang.reflect.Method shutdown = agent.getDeclaredMethod("shutdownForTesting");
            shutdown.setAccessible(true);
            if (!Boolean.TRUE.equals(shutdown.invoke(null))) {
                failures.add("manager shutdown reported no active runtime");
                return false;
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            failures.add("manager shutdown failed: " + failure.getClass().getSimpleName());
            return false;
        }
    }

    private void verifyManagerUnload(final List<String> failures) {
        try {
            final String cleanup = Files.readString(stateDir.resolve(MANAGER_CLEANUP_RESULT));
            for (String line : List.of(
                "status=PASS", "disable=PASS", "shutdown=PASS", "statusRemoved=PASS"
            )) {
                if (!cleanup.lines().anyMatch(line::equals)) {
                    failures.add("manager cleanup evidence missing " + line);
                }
            }

            final String runtimeReport = Files.readString(
                stateDir.getParent().resolve("runtime/preview-runtime-report.json")
            );
            if (!runtimeReport.contains("\"runtimeState\":\"STOPPED\"")) {
                failures.add("final runtime report is not STOPPED");
            }
            final String pluginReport = Files.readString(
                stateDir.getParent().resolve("runtime/plugin-load-report.json")
            );
            final String marker = "\"pluginId\":\"" + PLUGIN_ID + "\"";
            final int start = pluginReport.indexOf(marker);
            final int next = start < 0
                ? -1
                : pluginReport.indexOf("\"pluginId\":", start + marker.length());
            if (start < 0) {
                failures.add("final plugin report is missing validation plugin summary");
                return;
            }
            final String plugin = pluginReport.substring(
                start,
                next < 0 ? pluginReport.length() : next
            );
            for (String field : List.of(
                "\"lifecycleState\":\"UNLOADED\"",
                "\"disableState\":\"SUCCEEDED\"",
                "\"shutdownState\":\"SUCCEEDED\"",
                "\"unloadState\":\"SUCCEEDED\"",
                "\"scopeCleanupState\":\"SUCCEEDED\"",
                "\"classloaderCleanupState\":\"SUCCEEDED\""
            )) {
                if (!plugin.contains(field)) {
                    failures.add("final plugin summary is missing " + field);
                }
            }
        } catch (java.io.IOException failure) {
            failures.add("manager unload evidence read failed: " + failure.getClass().getSimpleName());
        }
    }

    private void leaveLiveStatusForProcessExit(final List<String> failures) {
        notify("INFO", failures);
        requireLabel("[I] ", "process-exit-live-status", failures);
    }

    private Registration notify(final String severity, final List<String> failures) {
        try {
            final Registration registration = context.uiHost().notifyStatus(
                new StatusNotification(STATUS_ID, severity, TOKEN)
            );
            if (registration == null) {
                failures.add(severity + "-notify returned a null registration");
            }
            return registration;
        } catch (RuntimeException failure) {
            failures.add(severity + "-notify failed: " + failure.getClass().getSimpleName());
            return () -> { };
        }
    }

    private void close(final Registration registration, final String phase, final List<String> failures) {
        try {
            registration.close();
        } catch (RuntimeException failure) {
            failures.add(phase + " failed: " + failure.getClass().getSimpleName());
        }
    }

    private void assertNoTokenLabel(final String phase, final List<String> failures) {
        final LabelView unexpected = requireAbsent(null, phase, failures);
        if (unexpected != null) {
            failures.add(phase + " found unexpected label text=" + unexpected.text);
        }
    }

    /** Returns the first label with any severity prefix + token, or null when absent. */
    private LabelView requireAbsent(final String prefix, final String phase, final List<String> failures) {
        final List<LabelView> found = scan(prefix);
        if (found.isEmpty()) {
            return null;
        }
        failures.add(phase + " expected no label but found " + found.size());
        return found.get(0);
    }

    /** Requires exactly one label with the given prefix + token; records identity for the matrix. */
    private LabelView requireLabel(final String prefix, final String phase, final List<String> failures) {
        final List<LabelView> found = scan(prefix);
        if (found.size() != 1) {
            failures.add(phase + " expected exactly 1 label, found " + found.size());
            return null;
        }
        final LabelView label = found.get(0);
        if (!label.text.equals(prefix + TOKEN)) {
            failures.add(phase + " expected text=" + prefix + TOKEN + " actual=" + label.text);
        }
        return label;
    }

    private boolean sameIdentity(final LabelView first, final LabelView second) {
        return first != null && second != null && first.label == second.label;
    }

    /** Plain-Swing EDT scan: Window.getWindows() recursive JLabel text search. */
    private List<LabelView> scan(final String prefix) {
        final List<LabelView> result = new ArrayList<>();
        onEdt(() -> {
            for (Window window : Window.getWindows()) {
                walk(window, prefix, result);
            }
            return null;
        });
        return result;
    }

    private void walk(final Component component, final String prefix, final List<LabelView> result) {
        if (component instanceof JLabel label) {
            final String text = label.getText();
            if (text != null && text.contains(TOKEN)
                && (prefix == null || text.startsWith(prefix))) {
                result.add(new LabelView(label, text));
            }
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                walk(child, prefix, result);
            }
        }
    }

    /** Runs one operation on the EDT; the matrix only ever records edt=true. */
    private <T> T onEdt(final java.util.concurrent.Callable<T> operation) {
        final java.util.concurrent.atomic.AtomicReference<T> result = new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicBoolean edt = new java.util.concurrent.atomic.AtomicBoolean();
        final Runnable runnable = () -> {
            edt.set(SwingUtilities.isEventDispatchThread());
            try {
                result.set(operation.call());
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            runnable.run();
        } else {
            try {
                SwingUtilities.invokeAndWait(runnable);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("status-bar scan interrupted", interrupted);
            } catch (java.lang.reflect.InvocationTargetException exception) {
                throw new IllegalStateException("status-bar scan failed on EDT", exception);
            }
        }
        if (!edt.get()) {
            throw new IllegalStateException("status-bar scan did not run on EDT");
        }
        if (failure.get() != null) {
            throw new IllegalStateException("status-bar scan failed safely", failure.get());
        }
        logger.info("STATUS_BAR_MATRIX_SCAN edt=" + edt.get());
        return result.get();
    }

    private record LabelView(JLabel label, String text) {
    }
}
