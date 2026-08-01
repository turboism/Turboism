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
    private java.nio.file.Path customDefaultsSource;

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
            for (String key : ownedKeys) {
                UIManager.getDefaults().remove(key);
            }
            defaults.forEach((key, value) -> UIManager.put(key, uiValue(value)));
            writeCustomDefaultsSource(defaults);
            return null;
        });
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
                // Register the injected values as a FlatLaf custom defaults source
                // (the legacy applier's mechanism) so updateUI keeps derived keys
                // (e.g. CubismCommon.gl.viewArea.background = darken(@background,8%))
                // overridden instead of recomputing them. Later registrations win,
                // so restoring registers the baseline values over the theme without
                // clearing the host's own sources.
                if (customDefaultsSource != null) {
                    flatLaf.getMethod("registerCustomDefaultsSource", java.io.File.class)
                        .invoke(null, customDefaultsSource.toFile());
                }
                flatLaf.getMethod("updateUI").invoke(null);
                return null;
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("FlatLaf updateUI is unavailable", exception);
            }
        });
    }

    private void writeCustomDefaultsSource(final Map<String, String> defaults) {
        try {
            final java.nio.file.Path temp = java.nio.file.Files.createTempFile(
                "turboism-theme-", ".properties"
            );
            final StringBuilder content = new StringBuilder();
            for (Map.Entry<String, String> entry : defaults.entrySet()) {
                content.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
            }
            java.nio.file.Files.writeString(temp, content.toString());
            customDefaultsSource = temp;
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Could not write FlatLaf custom defaults source", exception);
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
