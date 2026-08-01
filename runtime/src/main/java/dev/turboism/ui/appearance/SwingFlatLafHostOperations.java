package dev.turboism.ui.appearance;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.plaf.ColorUIResource;
import java.awt.Color;
import java.lang.reflect.InvocationTargetException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Runtime-owned bridge for the legacy UIManager injection + FlatLaf.updateUI sequence. */
public final class SwingFlatLafHostOperations implements FlatLafAppearanceHostProvider.HostOperations {

    private final ClassLoader hostClassLoader;
    private final java.util.Set<String> ownedKeys;

    public SwingFlatLafHostOperations(final ClassLoader hostClassLoader) {
        this.hostClassLoader = Objects.requireNonNull(hostClassLoader, "hostClassLoader");
        this.ownedKeys = FlatLafAppearanceHostProvider.ownedKeys();
    }

    @Override
    public Map<String, String> capture() {
        return onEdt(() -> {
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
     * Restores the native look: drops owned overrides and deletes the shared
     * custom-defaults file so the next FlatLaf update skips it, exactly like
     * the legacy agent's deleteRuntimeProperties + updateUI sequence.
     */
    @Override
    public void restoreNative() {
        onEdt(() -> {
            removeOwnedKeys();
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

    @Override
    public void refresh() {
        onEdt(() -> {
            try {
                final Class<?> flatLaf = Class.forName(
                    "com.formdev.flatlaf.FlatLaf",
                    false,
                    hostClassLoader
                );
                // Register the shared runtime file as a FlatLaf custom defaults
                // source (the legacy applier's mechanism) so updateUI keeps
                // derived keys (e.g. CubismCommon.gl.viewArea.background =
                // darken(@background,8%)) overridden instead of recomputing
                // them. The file is written by apply() and deleted by
                // restoreNative(); FlatLaf skips missing sources.
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

    private static <T> T onEdt(final java.util.concurrent.Callable<T> task) {
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
