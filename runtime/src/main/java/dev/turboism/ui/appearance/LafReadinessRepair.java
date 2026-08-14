package dev.turboism.ui.appearance;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * One-shot L&F readiness repair for hosts that construct Swing windows before
 * FlatLaf's UI defaults are installed.
 *
 * <p>Cubism 5.3.02 builds its main window and early dialogs on the EDT while
 * the FlatLaf look-and-feel is still being installed, so those components get
 * no ComponentUI ("no ComponentUI class for: javax.swing.JPanel...") and stay
 * blank forever. This repair waits (daemon thread; EDT-dispatched
 * {@code UIManager} reads with sleeps on the daemon thread, same pattern as
 * {@link EarlyThemeAppearanceBootstrap}) until the host's FlatLaf look-and-feel
 * is installed and its UI defaults are populated, then runs
 * {@code FlatLaf.updateUI()} once on the EDT so every already-created component
 * gets its UI reinstalled. No colors or themes are injected. The repair is
 * one-shot (no retry after success or timeout), idempotent and fail-open: any
 * failure is logged and never thrown at the host.</p>
 */
public final class LafReadinessRepair {

    static final long DEFAULT_TIMEOUT_MILLIS = 30_000L;
    private static final long POLL_MILLIS = 100L;
    private static final String PANEL_UI_KEY = "PanelUI";
    private static final String FLAT_LAF_CLASS = "com.formdev.flatlaf.FlatLaf";

    private final ClassLoader hostClassLoader;
    private final Consumer<String> log;
    private final long timeoutMillis;
    private final AtomicBoolean attempted = new AtomicBoolean();

    public LafReadinessRepair(final ClassLoader hostClassLoader, final Consumer<String> log) {
        this(hostClassLoader, log, DEFAULT_TIMEOUT_MILLIS);
    }

    /** Bounded timeout for tests; production uses {@link #DEFAULT_TIMEOUT_MILLIS}. */
    LafReadinessRepair(
        final ClassLoader hostClassLoader,
        final Consumer<String> log,
        final long timeoutMillis
    ) {
        this.hostClassLoader = Objects.requireNonNull(hostClassLoader, "hostClassLoader");
        this.log = Objects.requireNonNull(log, "log");
        this.timeoutMillis = timeoutMillis;
    }

    /** Starts the wait-and-repair loop on a daemon thread; never blocks startup. */
    public void start() {
        final Thread thread = new Thread(this::run, "turboism-laf-readiness-repair");
        thread.setDaemon(true);
        thread.start();
    }

    private void run() {
        if (!attempted.compareAndSet(false, true)) {
            return; // one-shot: no retry after success or timeout
        }
        final long started = System.nanoTime();
        try {
            if (!waitForFlatLaf(timeoutMillis)) {
                log.accept("L&F readiness repair: FlatLaf not ready within "
                    + timeoutMillis + "ms; skipping repair");
                return;
            }
            final boolean repaired = SwingFlatLafHostOperations.onEdt(() -> {
                // UI defaults may still be empty just after the look-and-feel
                // is installed; updateUI on an unprepared tree would rethrow
                // the same "no ComponentUI class" Error it is meant to fix.
                if (javax.swing.UIManager.get(PANEL_UI_KEY) == null) {
                    return false;
                }
                updateComponentUis();
                return true;
            });
            log.accept("L&F readiness repair: "
                + (repaired ? "FlatLaf.updateUI applied to all windows"
                            : "UI defaults not ready; skipped")
                + " in " + (System.nanoTime() - started) / 1_000_000L + "ms");
        } catch (RuntimeException failure) {
            log.accept("L&F readiness repair failed safely: " + failure);
        }
    }

    boolean waitForFlatLaf(final long timeoutMillis) {
        final long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (SwingFlatLafHostOperations.onEdt(() -> {
                    final Object lookAndFeel = javax.swing.UIManager.getLookAndFeel();
                    return lookAndFeel != null
                        && isFlatLaf(lookAndFeel.getClass().getName())
                        && javax.swing.UIManager.get(PANEL_UI_KEY) != null;
                })) {
                    return true;
                }
            } catch (RuntimeException ignored) {
                // UIManager may not be ready while the host boots.
            }
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static boolean isFlatLaf(final String className) {
        return className != null
            && (className.startsWith("com.formdev.flatlaf.")
                || className.contains("CubismLightTheme")
                || className.contains("CubismDarkTheme"));
    }

    private void updateComponentUis() {
        try {
            final Class<?> flatLaf = Class.forName(FLAT_LAF_CLASS, false, hostClassLoader);
            flatLaf.getMethod("updateUI").invoke(null);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("FlatLaf updateUI is unavailable", exception);
        }
    }
}
