package dev.turboism.ui.appearance;

import javax.swing.UIManager;
import javax.swing.plaf.ColorUIResource;
import java.awt.Color;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Injects the persisted theme before the Cubism GL scene initializes.
 *
 * <p>Cubism caches the off-canvas background color in a singleton Lazy
 * ({@code SGFramework.h$d}) on first GL scene access, so runtime theme applies
 * can never refresh it. The legacy hook agent applied themes at startup before
 * the scene existed; this bootstrap does the same: it waits for Cubism to
 * install its FlatLaf look and feel, then puts the persisted theme colors into
 * UIManager and registers them as a FlatLaf custom defaults source so the GL
 * scene picks them up when it initializes.</p>
 */
public final class EarlyThemeAppearanceBootstrap {

    private static final String PLUGIN_ID = "dev.turboism.plugin.uitheme";
    private static final String SELECTION_PATH = "ui-theme/selection.cfg";
    private static final String THEMES_DIR = "themes";
    private static final long LOOK_AND_FEEL_TIMEOUT_MILLIS = 60_000L;
    private static final long LOOK_AND_FEEL_POLL_MILLIS = 100L;

    private final Path home;
    private final ClassLoader hostClassLoader;
    private final Runnable injected;

    public EarlyThemeAppearanceBootstrap(
        final Path home,
        final ClassLoader hostClassLoader,
        final Runnable injected
    ) {
        this.home = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
        this.hostClassLoader = Objects.requireNonNull(hostClassLoader, "hostClassLoader");
        this.injected = Objects.requireNonNull(injected, "injected");
    }

    /** Starts the wait-and-inject loop on a daemon thread; never blocks startup. */
    public void start() {
        final Thread thread = new Thread(this::run, "turboism-early-theme");
        thread.setDaemon(true);
        thread.start();
    }

    private void run() {
        try {
            if (!waitForFlatLaf()) {
                return;
            }
            SwingFlatLafHostOperations.captureNativeOffCanvasBackground();
            final Optional<Map<String, String>> colors = loadPersistedThemeColors();
            if (colors.isEmpty()) {
                return;
            }
            inject(colors.orElseThrow());
            injected.run();
        } catch (RuntimeException failure) {
            System.err.println("Early theme appearance bootstrap failed safely: " + failure);
        }
    }

    private boolean waitForFlatLaf() {
        final long deadline = System.currentTimeMillis() + LOOK_AND_FEEL_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            try {
                final Object lookAndFeel = UIManager.getLookAndFeel();
                if (lookAndFeel != null && isFlatLaf(lookAndFeel.getClass().getName())) {
                    return true;
                }
            } catch (RuntimeException ignored) {
                // UIManager may not be ready while the host boots.
            }
            try {
                Thread.sleep(LOOK_AND_FEEL_POLL_MILLIS);
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

    private Optional<Map<String, String>> loadPersistedThemeColors() {
        final Optional<String> themeId = readSelectionThemeId();
        if (themeId.isEmpty()) {
            return Optional.empty();
        }
        final String id = themeId.orElseThrow();
        // A saved package (user-edited copy) overrides the immutable built-in.
        Optional<Map<String, String>> colors = loadImportedColors(id);
        if (colors.isEmpty()) {
            colors = loadBuiltinColors(id);
        }
        if (colors.isEmpty()) {
            System.err.println("Early theme bootstrap: theme not found id=" + id);
        }
        return colors;
    }

    private Optional<String> readSelectionThemeId() {
        final Path document = home.resolve("config").resolve(PLUGIN_ID).resolve(SELECTION_PATH);
        if (!Files.isRegularFile(document)) {
            return Optional.empty();
        }
        try {
            final String text = Files.readString(document, StandardCharsets.UTF_8);
            final Base64.Decoder decoder = Base64.getUrlDecoder();
            for (String line : text.split("\\n", -1)) {
                final int separator = line.indexOf(':');
                if (separator <= 0 || separator != line.lastIndexOf(':')) {
                    continue;
                }
                final String key = new String(decoder.decode(line.substring(0, separator)), StandardCharsets.UTF_8);
                if (!"selected-theme".equals(key)) {
                    continue;
                }
                final String value = new String(decoder.decode(line.substring(separator + 1)), StandardCharsets.UTF_8);
                // Encoded as a bounded string list: ["theme-id"]
                if (value.startsWith("[\"") && value.endsWith("\"]")
                    && value.indexOf('"', 2) == value.length() - 2) {
                    return Optional.of(value.substring(2, value.length() - 2));
                }
            }
        } catch (Exception ignored) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private Optional<Map<String, String>> loadBuiltinColors(final String themeId) {
        // The agent bundles built-in themes under /themes/<directory>/.
        final String slug = themeId.startsWith("turboism.") ? themeId.substring("turboism.".length()) : themeId;
        final String[] slugs = { slug, themeId.replace('.', '-') };
        for (String candidate : slugs) {
            final String base = "/" + THEMES_DIR + "/" + candidate + "/";
            try (InputStream input = EarlyThemeAppearanceBootstrap.class.getResourceAsStream(base + "theme.properties")) {
                if (input == null) {
                    continue;
                }
                final Properties meta = new Properties();
                meta.load(input);
                if (!themeId.equals(meta.getProperty("id"))) {
                    continue;
                }
                try (InputStream colors = EarlyThemeAppearanceBootstrap.class.getResourceAsStream(base + "colors.properties")) {
                    if (colors != null) {
                        return Optional.of(loadProperties(colors));
                    }
                }
            } catch (Exception ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private Optional<Map<String, String>> loadImportedColors(final String themeId) {
        final Path archive = home.resolve("data").resolve(PLUGIN_ID).resolve("themes").resolve(themeId + ".zip");
        if (!Files.isRegularFile(archive)) {
            return Optional.empty();
        }
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory() && entry.getName().endsWith("colors.properties")) {
                    return Optional.of(loadProperties(zip));
                }
            }
        } catch (Exception ignored) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private static Map<String, String> loadProperties(final Path path) throws Exception {
        try (InputStream input = Files.newInputStream(path)) {
            return loadProperties(input);
        }
    }

    private static Map<String, String> loadProperties(final InputStream input) throws Exception {
        final Properties properties = new Properties();
        properties.load(input);
        final LinkedHashMap<String, String> values = new LinkedHashMap<>();
        for (String name : properties.stringPropertyNames()) {
            values.put(name, properties.getProperty(name).trim());
        }
        return Map.copyOf(values);
    }

    private void inject(final Map<String, String> colors) {
        final Runnable onEdt = () -> {
            try {
                for (Map.Entry<String, String> entry : colors.entrySet()) {
                    final String value = entry.getValue();
                    if (value.startsWith("#")) {
                        try {
                            UIManager.put(entry.getKey(), new ColorUIResource(Color.decode(value)));
                            continue;
                        } catch (NumberFormatException ignored) {
                            // fall through to raw value
                        }
                    }
                    UIManager.put(entry.getKey(), value);
                }
                registerCustomDefaultsSource(colors);
                final Class<?> flatLaf = Class.forName("com.formdev.flatlaf.FlatLaf", false, hostClassLoader);
                flatLaf.getMethod("updateUI").invoke(null);
            } catch (Exception exception) {
                throw new IllegalStateException("Early theme injection failed", exception);
            }
        };
        if (javax.swing.SwingUtilities.isEventDispatchThread()) {
            onEdt.run();
        } else {
            try {
                javax.swing.SwingUtilities.invokeAndWait(onEdt);
            } catch (Exception exception) {
                throw new IllegalStateException("Early theme injection dispatch failed", exception);
            }
        }
    }

    private void registerCustomDefaultsSource(final Map<String, String> colors) throws Exception {
        // Write the shared runtime properties file so the appearance provider
        // can delete it (restoreNative) and FlatLaf skips it on the next
        // updateUI, restoring the native look exactly like the legacy agent.
        ThemeRuntimeProperties.write(colors);
        final Class<?> flatLaf = Class.forName("com.formdev.flatlaf.FlatLaf", false, hostClassLoader);
        flatLaf.getMethod("registerCustomDefaultsSource", java.io.File.class)
            .invoke(null, ThemeRuntimeProperties.path().toFile());
    }
}
