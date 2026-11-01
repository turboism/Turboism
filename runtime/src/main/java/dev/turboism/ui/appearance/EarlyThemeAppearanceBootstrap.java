package dev.turboism.ui.appearance;

import java.awt.Color;
import java.io.IOException;
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
import javax.swing.UIManager;
import javax.swing.plaf.ColorUIResource;

/**
 * Injects the persisted theme before the Cubism GL scene initializes.
 *
 * <p>Cubism caches the off-canvas background color in a singleton Lazy
 * ({@code SGFramework.h$d}) on first GL scene access. Runtime theme changes update
 * that cache and existing scene materials separately. The legacy hook agent applied themes at startup before
 * the scene existed; this bootstrap does the same: it waits for Cubism to
 * install its FlatLaf look and feel, then puts the persisted theme colors into
 * UIManager and registers them as a FlatLaf custom defaults source so the GL
 * scene picks them up when it initializes.</p>
 */
public final class EarlyThemeAppearanceBootstrap {

    private static final String PLUGIN_ID = "dev.turboism.plugin.uitheme";
    /**
     * The plugin-private selection document this bootstrap reads. This is the closed exception in
     * ARCHITECTURE.md section 5; both modules pin these values in their own tests.
     */
    static final String SELECTION_PATH = "ui-theme/selection.cfg";
    /** The one selector key this bootstrap reads out of that document. */
    static final String SELECTED_THEME_KEY = "selected-theme";
    /** The typed-config schema version the reader understands. */
    static final int SUPPORTED_SCHEMA_VERSION = 1;
    /** The plugin's sentinel for "no theme selected". */
    static final String NATIVE_THEME_ID = "__native__";

    private static final String DOCUMENT_HEADER = "turboism-typed-config-v1";
    private static final String SCHEMA_VERSION_PREFIX = "schemaVersion=";
    private static final String REVISION_PREFIX = "revision=";
    private static final String COUNT_PREFIX = "count=";
    private static final int MAX_DOCUMENT_BYTES = 1024 * 1024;
    private static final int MAX_THEME_ID_LENGTH = 128;
    private static final String DOCUMENT_INVALID = "EARLY_THEME_SELECTION_DOCUMENT_INVALID";
    private static final String SCHEMA_UNSUPPORTED = "EARLY_THEME_SELECTION_SCHEMA_UNSUPPORTED";
    private static final String SELECTION_INVALID = "EARLY_THEME_SELECTION_VALUE_INVALID";
    private static final String DOCUMENT_UNREADABLE = "EARLY_THEME_SELECTION_DOCUMENT_UNREADABLE";
    private static final String THEMES_DIR = "themes";
    private static final long LOOK_AND_FEEL_TIMEOUT_MILLIS = 60_000L;
    private static final long LOOK_AND_FEEL_POLL_MILLIS = 100L;

    private final Path home;
    private final ClassLoader hostClassLoader;
    private final Runnable injected;

    public EarlyThemeAppearanceBootstrap(final Path home, final ClassLoader hostClassLoader, final Runnable injected) {
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
            // Validate the persisted selection before any Swing/EDT/UIManager
            // dependency: an empty, stale, invalid or missing selection must
            // return without waiting for FlatLaf or touching the native
            // off-canvas background.
            final Optional<Map<String, String>> colors = loadPersistedThemeColors();
            if (colors.isEmpty()) {
                return;
            }
            if (!waitForFlatLaf()) {
                return;
            }
            SwingFlatLafHostOperations.captureNativeOffCanvasBackground();
            inject(colors.orElseThrow());
            injected.run();
        } catch (RuntimeException failure) {
            dev.turboism.runtime.log.RuntimeDiagnostics.error(
                    "appearance", "Early theme appearance bootstrap failed safely", failure);
        }
    }

    boolean waitForFlatLaf() {
        final long deadline = System.currentTimeMillis() + LOOK_AND_FEEL_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (SwingFlatLafHostOperations.onEdt(() -> {
                    final Object lookAndFeel = UIManager.getLookAndFeel();
                    return lookAndFeel != null
                            && isFlatLaf(lookAndFeel.getClass().getName());
                })) {
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
            dev.turboism.runtime.log.RuntimeDiagnostics.warn("appearance", "Persisted theme was not found");
        }
        return colors;
    }

    private Optional<String> readSelectionThemeId() {
        final Path document = home.resolve("config").resolve(PLUGIN_ID).resolve(SELECTION_PATH);
        if (!Files.isRegularFile(document)) {
            return Optional.empty();
        }
        final Map<String, String> entries;
        try {
            if (Files.size(document) > MAX_DOCUMENT_BYTES) {
                return diagnosed(DOCUMENT_INVALID, "document exceeds the size limit");
            }
            entries = parseTypedConfigDocument(Files.readString(document, StandardCharsets.UTF_8));
        } catch (SelectionDocumentException malformed) {
            return diagnosed(malformed.reason, malformed.getMessage());
        } catch (IOException unreadable) {
            return diagnosed(DOCUMENT_UNREADABLE, unreadable.getClass().getSimpleName());
        }
        final String encoded = entries.get(SELECTED_THEME_KEY);
        if (encoded == null) {
            // The plugin persists the schema defaults for keys it never wrote.
            return Optional.empty();
        }
        final Optional<String> themeId = decodeSelectedTheme(encoded);
        if (themeId.isEmpty() && (encoded.isEmpty() || "[]".equals(encoded))) {
            // The plugin persists an empty bounded list when no theme is selected.
            return Optional.empty();
        }
        if (themeId.isEmpty()) {
            return diagnosed(SELECTION_INVALID, "selected-theme is not a bounded single-element list");
        }
        return NATIVE_THEME_ID.equals(themeId.orElseThrow()) ? Optional.empty() : themeId;
    }

    /**
     * Validates the private {@code ui-theme} selection document against the exact typed-config
     * document schema the runtime writer emits. A document that does not match is never
     * half-read: the reader fails with one bounded diagnostic instead of silently loading a
     * different theme or none at all.
     */
    private static Map<String, String> parseTypedConfigDocument(final String text)
            throws SelectionDocumentException {
        final String[] lines = text.split("\\n", -1);
        if (lines.length < 5 || !lines[lines.length - 1].isEmpty()) {
            throw new SelectionDocumentException(DOCUMENT_INVALID, "document framing is incomplete");
        }
        if (!DOCUMENT_HEADER.equals(lines[0])) {
            throw new SelectionDocumentException(DOCUMENT_INVALID, "document header is not " + DOCUMENT_HEADER);
        }
        final int schemaVersion = parseMetadata(lines[1], SCHEMA_VERSION_PREFIX, DOCUMENT_INVALID);
        if (schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            throw new SelectionDocumentException(
                    SCHEMA_UNSUPPORTED, "schemaVersion " + schemaVersion + " is not " + SUPPORTED_SCHEMA_VERSION);
        }
        if (parseRevision(lines[2]) < 0) {
            throw new SelectionDocumentException(DOCUMENT_INVALID, "revision is negative");
        }
        final int count = parseMetadata(lines[3], COUNT_PREFIX, DOCUMENT_INVALID);
        if (count < 0 || lines.length != count + 5) {
            throw new SelectionDocumentException(
                    DOCUMENT_INVALID, "count does not match the persisted entry lines");
        }
        final Base64.Decoder decoder = Base64.getUrlDecoder();
        final Map<String, String> entries = new LinkedHashMap<>();
        for (int index = 0; index < count; index++) {
            final String line = lines[index + 4];
            final int separator = line.indexOf(':');
            if (separator <= 0 || separator != line.lastIndexOf(':')) {
                throw new SelectionDocumentException(DOCUMENT_INVALID, "entry framing is invalid");
            }
            try {
                final String key = new String(decoder.decode(line.substring(0, separator)), StandardCharsets.UTF_8);
                final String value = new String(decoder.decode(line.substring(separator + 1)), StandardCharsets.UTF_8);
                if (key.isBlank() || entries.putIfAbsent(key, value) != null) {
                    throw new SelectionDocumentException(DOCUMENT_INVALID, "entry key is blank or duplicated");
                }
            } catch (IllegalArgumentException malformedEntry) {
                throw new SelectionDocumentException(DOCUMENT_INVALID, "entry encoding is invalid");
            }
        }
        return entries;
    }

    private static int parseMetadata(final String line, final String prefix, final String reason)
            throws SelectionDocumentException {
        if (!line.startsWith(prefix)) {
            throw new SelectionDocumentException(reason, "metadata line is not " + prefix + "<value>");
        }
        try {
            return Integer.parseInt(line.substring(prefix.length()));
        } catch (NumberFormatException outOfRange) {
            throw new SelectionDocumentException(reason, "metadata " + prefix + "<value> is not an int");
        }
    }

    /** The writer's revision is a {@code long}; parse it as one so a valid document is never rejected. */
    private static long parseRevision(final String line) throws SelectionDocumentException {
        if (!line.startsWith(REVISION_PREFIX)) {
            throw new SelectionDocumentException(DOCUMENT_INVALID, "metadata line is not " + REVISION_PREFIX + "<value>");
        }
        try {
            return Long.parseLong(line.substring(REVISION_PREFIX.length()));
        } catch (NumberFormatException outOfRange) {
            throw new SelectionDocumentException(DOCUMENT_INVALID, "revision is not a long");
        }
    }

    private static Optional<String> decodeSelectedTheme(final String encoded) {
        if (encoded.isEmpty()) {
            return Optional.empty();
        }
        if (!encoded.startsWith("[\"") || !encoded.endsWith("\"]")) {
            return Optional.empty();
        }
        final String themeId = encoded.substring(2, encoded.length() - 2);
        // The plugin codec emits plain JSON strings; the reader accepts no escapes, so a value
        // it cannot read unambiguously is rejected rather than guessed.
        if (themeId.isBlank()
                || themeId.length() > MAX_THEME_ID_LENGTH
                || themeId.indexOf('"') >= 0
                || themeId.indexOf('\\') >= 0) {
            return Optional.empty();
        }
        return Optional.of(themeId);
    }

    private static Optional<String> diagnosed(final String reason, final String detail) {
        dev.turboism.runtime.log.RuntimeDiagnostics.warn(
                "appearance",
                reason + " config/" + PLUGIN_ID + "/" + SELECTION_PATH + ": " + detail
                        + "; early theme injection skipped");
        return Optional.empty();
    }

    /** One bounded, non-retryable reason the persisted selection could not be read. */
    private static final class SelectionDocumentException extends Exception {
        private final String reason;

        private SelectionDocumentException(final String reason, final String detail) {
            super(detail);
            this.reason = reason;
        }
    }

    private Optional<Map<String, String>> loadBuiltinColors(final String themeId) {
        // Built-in themes used to ship inside the agent JAR under /themes/<directory>/;
        // the lookup stays fail-open so a classpath that still provides them keeps working.
        final String slug = themeId.startsWith("turboism.") ? themeId.substring("turboism.".length()) : themeId;
        final String[] slugs = {slug, themeId.replace('.', '-')};
        for (String candidate : slugs) {
            final String base = "/" + THEMES_DIR + "/" + candidate + "/";
            try (InputStream input =
                    EarlyThemeAppearanceBootstrap.class.getResourceAsStream(base + "theme.properties")) {
                if (input == null) {
                    continue;
                }
                final Properties meta = new Properties();
                meta.load(input);
                if (!themeId.equals(meta.getProperty("id"))) {
                    continue;
                }
                try (InputStream colors =
                        EarlyThemeAppearanceBootstrap.class.getResourceAsStream(base + "colors.properties")) {
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
        final Path archive =
                home.resolve("data").resolve(PLUGIN_ID).resolve("themes").resolve(themeId + ".zip");
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
        dev.turboism.ui.host.EdtDispatch.call("early theme injection", () -> {
            onEdt.run();
            return null;
        });
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
