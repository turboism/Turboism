package dev.turboism.validation.session;

import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.TurboismPlugin;
import dev.turboism.validation.NativeFreeStartup;

import javax.swing.SwingUtilities;
import java.awt.Dialog;
import java.awt.Window;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Multi-probe session coordinator. Loads {@code state/session/plan.json} delivered
 * by the session wrapper, then triggers each step's probe in order, polls its
 * result file, archives per-step evidence and runs a health check (modal dialog
 * scan + active document still present) between steps. The aggregate verdict is
 * written to {@code state/session/session-result.properties}; afterwards the
 * coordinator exits the host process itself — this is the same bounded exit the
 * standalone probes already perform after publishing their terminal evidence.
 *
 * <p>The plugin stays inert unless {@code -Dturboism.validation.session=true} and a
 * session plan exists, so accidental packaging into a standalone job is a no-op.</p>
 */
public final class SessionCoordinatorPlugin implements TurboismPlugin {
    private static final String PLAN = "plan.json";
    private static final String START_FREE_PROPERTY = "turboism.validation.session.startFree";
    private static final long POLL_MILLIS = 1_000L;
    private static final long SETTLE_MILLIS = 3_000L;

    private PluginContext context;
    private PluginLogger logger;
    private Path stateDir;
    private volatile boolean stopped;
    private Thread coordinator;
    private Thread startupHelper;

    @Override
    public void init(final PluginContext context) {
        this.context = context;
        this.logger = context.logger();
        this.stateDir = context.paths().stateDir();
    }

    @Override
    public void enable() {
        if (coordinator != null) return;
        coordinator = new Thread(this::run, "session-coordinator");
        coordinator.setDaemon(true);
        coordinator.start();
    }

    @Override
    public void disable() {
        stopped = true;
        if (coordinator != null) coordinator.interrupt();
        if (startupHelper != null) startupHelper.interrupt();
    }

    @Override
    public void shutdown() {
        disable();
    }

    private void run() {
        // The wrapper polls this marker for launcher readiness; emit it before
        // any gate so a malformed deployment fails fast instead of hanging the
        // readiness budget.
        logger.info("SESSION_BEGIN");
        final Path home = stateDir.getParent() == null ? null : stateDir.getParent().getParent();
        if (home == null) {
            logger.warn("SESSION_INERT reason=no-home-path");
            return;
        }
        final Path planFile = SessionRunner.sessionDir(home).resolve(PLAN);
        if (!Boolean.getBoolean("turboism.validation.session") || !Files.isRegularFile(planFile)) {
            logger.warn("SESSION_INERT reason=no-plan-or-session-disabled");
            return;
        }
        final SessionPlan.Plan plan;
        try {
            plan = SessionPlan.parse(Files.readString(planFile));
        } catch (Exception failure) {
            logger.error("SESSION_PLAN_INVALID", failure);
            SessionRunner.writeFailedAggregate(home, "plan parse failed: " + failure, logger::info);
            finish(2);
            return;
        }
        logger.info("SESSION_PLAN session=" + plan.session()
                + " hostVersion=" + plan.hostVersion() + " steps=" + plan.steps().size());
        if (Boolean.getBoolean(START_FREE_PROPERTY)) {
            startupHelper = new Thread(this::assistStartup, "session-startup-assistance");
            startupHelper.setDaemon(true);
            startupHelper.start();
        }
        if (!awaitHostReady(plan)) {
            logger.warn("SESSION_STARTUP_TIMEOUT");
            logger.info("SESSION_END status=FAIL exitCode=2");
            SessionRunner.abortAll(home, plan,
                    "host never reached a usable document", logger::info);
            finish(2);
            return;
        }
        final SessionRunner.SessionOutcome outcome = SessionRunner.run(home, plan,
                this::healthCheck, Thread::sleep, logger::info);
        final int exitCode = "PASS".equals(outcome.status()) ? 0 : 2;
        logger.info("SESSION_END status=" + outcome.status() + " exitCode=" + exitCode);
        finish(exitCode);
    }

    private void finish(final int exitCode) {
        if (startupHelper != null) startupHelper.interrupt();
        try {
            Thread.sleep(SETTLE_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        Runtime.getRuntime().exit(exitCode);
    }

    /** Waits for the fixture document; startup dialogs may still be dismissed meanwhile. */
    private boolean awaitHostReady(final SessionPlan.Plan plan) {
        final long deadline = System.nanoTime()
                + java.time.Duration.ofSeconds(plan.startupTimeoutSeconds()).toNanos();
        while (!stopped && System.nanoTime() < deadline) {
            final AtomicReference<Boolean> ready = new AtomicReference<>(false);
            try {
                SwingUtilities.invokeAndWait(() -> {
                    try {
                        ready.set(context.cubism().isHostPresent()
                                && context.cubism().activeDocument().isPresent());
                    } catch (IllegalStateException unavailable) {
                        ready.set(false);
                    }
                });
            } catch (Exception failure) {
                logger.warn("SESSION_STARTUP_POLL failed=" + failure);
            }
            if (ready.get()) {
                logger.info("SESSION_HOST_READY");
                return true;
            }
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !stopped && false;
    }

    /** Between steps: no visible modal dialog and the task document still present. */
    private String healthCheck() {
        final AtomicReference<String> verdict = new AtomicReference<>(null);
        try {
            SwingUtilities.invokeAndWait(() -> {
                for (final Window window : Window.getWindows()) {
                    if (window instanceof Dialog dialog && dialog.isModal() && dialog.isVisible()) {
                        verdict.set("modal dialog visible: " + dialog.getTitle());
                        return;
                    }
                }
                try {
                    if (!context.cubism().activeDocument().isPresent()) {
                        verdict.set("active document missing after step");
                    }
                } catch (Exception failure) {
                    verdict.set("activeDocument read failed: " + failure);
                }
            });
        } catch (Exception failure) {
            return "health check dispatch failed: " + failure;
        }
        return verdict.get();
    }

    private void assistStartup() {
        while (!stopped) {
            try {
                SwingUtilities.invokeAndWait(() -> {
                    if (stopped) return;
                    final String action = NativeFreeStartup.select();
                    if (action != null) logger.info("SESSION_" + action);
                });
                Thread.sleep(500L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (java.lang.reflect.InvocationTargetException failure) {
                logger.warn("Session startup assistant failed: " + failure.getCause());
                return;
            }
        }
    }
}
