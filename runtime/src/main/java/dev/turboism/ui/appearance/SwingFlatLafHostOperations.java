package dev.turboism.ui.appearance;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.plaf.ColorUIResource;
import java.awt.Color;
import java.lang.reflect.InvocationTargetException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Runtime-owned bridge for UIManager injection, native fallback capture and FlatLaf refresh. */
public final class SwingFlatLafHostOperations implements FlatLafAppearanceHostProvider.HostOperations {

    private static final String OFF_CANVAS_BACKGROUND =
        "CubismCommon.gl.viewArea.background";
    private static final String NATIVE_OFF_CANVAS_BACKGROUND =
        "Turboism.native.CubismCommon.gl.viewArea.background";

    private final ClassLoader hostClassLoader;
    private final java.util.Set<String> ownedKeys;

    public SwingFlatLafHostOperations(final ClassLoader hostClassLoader) {
        this.hostClassLoader = Objects.requireNonNull(hostClassLoader, "hostClassLoader");
        this.ownedKeys = FlatLafAppearanceHostProvider.ownedKeys();
    }

    @Override
    public Map<String, String> capture() {
        // Deferred native off-canvas capture: the constructor stays free of
        // Swing/EDT/UIManager access, so the one-shot capture runs at the first
        // restore-point capture (where Cubism has settled) instead of during
        // provider connection.
        return onEdt(() -> {
            captureNativeOffCanvasBackgroundNow();
            final LinkedHashMap<String, String> captured = new LinkedHashMap<>();
            for (String key : ownedKeys) {
                final Object value = UIManager.getDefaults().get(key);
                if (value instanceof Color color) {
                    captured.put(key, hex(color));
                } else if (value != null) {
                    captured.put(key, value.toString());
                }
            }
            return Map.copyOf(captured);
        });
    }

    private static final long NATIVE_CAPTURE_TIMEOUT_MILLIS = 60_000L;
    private static final long NATIVE_CAPTURE_POLL_MILLIS = 100L;

    /**
     * Captures Cubism's resolved derived GL background before any persisted
     * theme is injected (bootstrap path). Cubism registers its classpath
     * custom-defaults source while the main window initializes, later than
     * FlatLaf's look-and-feel is set, so poll until the derived key resolves
     * (or the timeout elapses) before the theme is injected.
     */
    static void captureNativeOffCanvasBackground() {
        captureNativeOffCanvasBackground(true);
    }

    /**
     * One-shot capture for the runtime provider path, where Cubism has settled:
     * exactly one source read (unless the native value is already captured),
     * stored as a {@code ColorUIResource} without overwriting an existing value.
     */
    static void captureNativeOffCanvasBackgroundNow() {
        captureNativeOffCanvasBackground(false);
    }

    private static void captureNativeOffCanvasBackground(final boolean poll) {
        // Never overwrite a native value the early-theme bootstrap already captured.
        if (onEdt(() -> UIManager.get(NATIVE_OFF_CANVAS_BACKGROUND) != null)) {
            return;
        }
        final java.util.function.Supplier<Boolean> readOnce = () -> onEdt(() -> {
            final Object value = UIManager.get(OFF_CANVAS_BACKGROUND);
            if (value instanceof Color color) {
                UIManager.put(NATIVE_OFF_CANVAS_BACKGROUND, new ColorUIResource(color));
                return true;
            }
            return false;
        });
        if (!poll) {
            // One-shot capture: exactly one source read, no sleeping.
            readOnce.get();
            return;
        }
        final long deadline = System.currentTimeMillis() + NATIVE_CAPTURE_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (readOnce.get()) {
                return;
            }
            try {
                Thread.sleep(NATIVE_CAPTURE_POLL_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Override
    public void replace(final Map<String, String> defaults) {
        Objects.requireNonNull(defaults, "defaults");
        onEdt(() -> {
            removeOwnedKeys();
            defaults.forEach((key, value) -> UIManager.put(key, uiValue(value)));
            try {
                ThemeRuntimeProperties.write(defaults);
            } catch (java.io.IOException exception) {
                throw new IllegalStateException("Could not write FlatLaf custom defaults source", exception);
            }
            return null;
        });
    }

    /**
     * Restores the native look: drops owned overrides, reinstates Cubism's
     * captured derived GL background, deletes the shared custom-defaults file,
     * and refreshes Swing components.
     */
    @Override
    public void restoreNative() {
        onEdt(() -> {
            removeOwnedKeys();
            restoreNativeOffCanvasBackground();
            ThemeRuntimeProperties.delete();
            refresh();
            return null;
        });
    }

    private void removeOwnedKeys() {
        for (String key : ownedKeys) {
            UIManager.getDefaults().remove(key);
        }
    }

    /**
     * True once the host FlatLaf look-and-feel is installed and its UI
     * defaults are populated. All reads are EDT-dispatched (S1 pattern);
     * startup paths that must not touch {@code UIManager} while FlatLaf
     * installs poll this from a daemon thread.
     */
    public static boolean isHostLafReady() {
        return onEdt(() -> {
            final Object lookAndFeel = javax.swing.UIManager.getLookAndFeel();
            return lookAndFeel != null
                && isFlatLaf(lookAndFeel.getClass().getName())
                && javax.swing.UIManager.get("PanelUI") != null;
        });
    }

    private static boolean isFlatLaf(final String className) {
        return className != null
            && (className.startsWith("com.formdev.flatlaf.")
                || className.contains("CubismLightTheme")
                || className.contains("CubismDarkTheme"));
    }

    private static void restoreNativeOffCanvasBackground() {
        final Object value = UIManager.get(NATIVE_OFF_CANVAS_BACKGROUND);
        if (value instanceof Color color) {
            UIManager.put(OFF_CANVAS_BACKGROUND, new ColorUIResource(color));
        }
    }

    @Override
    public void refresh() {
        onEdt(() -> {
            try {
                final Class<?> flatLaf = Class.forName(
                    "com.formdev.flatlaf.FlatLaf",
                    false,
                    hostClassLoader
                );
                // Keep the fixed file registered for look-and-feel reinstalls.
                // Runtime apply/restore update UIManager directly; FlatLaf.updateUI()
                // only refreshes component trees and does not rebuild UIDefaults.
                flatLaf.getMethod("registerCustomDefaultsSource", java.io.File.class)
                    .invoke(null, ThemeRuntimeProperties.path().toFile());
                flatLaf.getMethod("updateUI").invoke(null);
                repaintGlViewports();
                return null;
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("FlatLaf updateUI is unavailable", exception);
            }
        });
    }

    /** Best-effort repaint of JOGL viewports so off-canvas colors are re-read. */
    private static void repaintGlViewports() {
        try {
            for (java.awt.Window window : java.awt.Window.getWindows()) {
                repaintGlChildren(window);
            }
        } catch (RuntimeException ignored) {
            // Repaint is best-effort and must never fail the appearance apply.
        }
    }

    private static void repaintGlChildren(final java.awt.Container container) {
        for (java.awt.Component component : container.getComponents()) {
            if (component.getClass().getName().startsWith("com.jogamp.opengl.awt.GLJPanel")) {
                component.repaint();
            } else if (component instanceof java.awt.Container child) {
                repaintGlChildren(child);
            }
        }
    }


    private static Object uiValue(final String value) {
        if (value.matches("#[0-9A-Fa-f]{6}")) {
            return new ColorUIResource(Color.decode(value));
        }
        return value;
    }

    private static String hex(final Color color) {
        return String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
    }

    static <T> T onEdt(final java.util.concurrent.Callable<T> task) {
        if (SwingUtilities.isEventDispatchThread()) {
            return call(task);
        }
        final java.util.concurrent.atomic.AtomicReference<T> result = new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicReference<RuntimeException> failure = new java.util.concurrent.atomic.AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    result.set(call(task));
                } catch (RuntimeException exception) {
                    failure.set(exception);
                }
            });
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while updating host appearance", exception);
        } catch (InvocationTargetException exception) {
            throw new IllegalStateException("Host appearance dispatch failed", exception.getCause());
        }
        if (failure.get() != null) {
            throw failure.get();
        }
        return result.get();
    }

    private static <T> T call(final java.util.concurrent.Callable<T> task) {
        try {
            return task.call();
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
