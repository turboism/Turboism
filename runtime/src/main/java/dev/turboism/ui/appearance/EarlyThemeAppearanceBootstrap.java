package dev.turboism.ui.appearance;

import dev.turboism.persistence.config.StoredDocument;
import dev.turboism.persistence.config.TypedConfigDocumentCodec;
import java.awt.Color;
import java.beans.PropertyChangeListener;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
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

    private static final String CONFIG_DIR = "config";
    private static final String DATA_DIR = "data";
    private static final String THEMES_DIR = "themes";
    private static final String ARCHIVE_EXTENSION = ".zip";
    private static final String COLORS_PROPERTIES_FILE = "colors.properties";
    private static final String THEME_PROPERTIES_FILE = "theme.properties";
    private static final String BUILTIN_PREFIX = "turboism.";
    private static final String THEME_ID_PROPERTY = "id";
    private static final String EMPTY_LIST_JSON = "[]";
    private static final String LIST_PREFIX = "[\"";
    private static final String LIST_SUFFIX = "\"]";
    private static final String PARENT_SEGMENT = "..";
    private static final String TRAILING_DOT = ".";
    private static final String APPEARANCE_COMPONENT = "appearance";
    private static final int MAX_DOCUMENT_BYTES = 1024 * 1024;
    private static final long MAX_ARCHIVE_BYTES = 1024L * 1024L;
    private static final int MAX_COLORS_PROPERTIES_BYTES = 64 * 1024;
    private static final int MAX_ARCHIVE_ENTRIES = 64;
    private static final int MAX_THEME_ID_LENGTH = 128;
    private static final Pattern VALID_THEME_ID = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9._-]*");
    private static final String DOCUMENT_INVALID = "EARLY_THEME_SELECTION_DOCUMENT_INVALID";
    private static final String SCHEMA_UNSUPPORTED = "EARLY_THEME_SELECTION_SCHEMA_UNSUPPORTED";
    private static final String SELECTION_INVALID = "EARLY_THEME_SELECTION_VALUE_INVALID";
    private static final String DOCUMENT_UNREADABLE = "EARLY_THEME_SELECTION_DOCUMENT_UNREADABLE";
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

    /**
     * Waits for the host to install a FlatLaf look-and-feel. The property-change listener wakes
     * this wait as soon as {@link UIManager#setLookAndFeel} publishes its change, while the bounded
     * latch wait still polls for hosts that install the look-and-feel without firing that event.
     */
    boolean waitForFlatLaf() {
        final CountDownLatch flatLafInstalled = new CountDownLatch(1);
        final PropertyChangeListener listener = event -> {
            if (!"lookAndFeel".equals(event.getPropertyName())) {
                return;
            }
            final Object newValue = event.getNewValue();
            if (isFlatLafValue(newValue) || isFlatLafValue(UIManager.getLookAndFeel())) {
                flatLafInstalled.countDown();
            }
        };
        UIManager.addPropertyChangeListener(listener);
        try {
            final long deadline = System.currentTimeMillis() + LOOK_AND_FEEL_TIMEOUT_MILLIS;
            while (System.currentTimeMillis() < deadline) {
                try {
                    if (SwingFlatLafHostOperations.onEdt(() -> {
                        final Object lookAndFeel = UIManager.getLookAndFeel();
                        return lookAndFeel != null
                                && FlatLafClasses.isFlatLaf(
                                        lookAndFeel.getClass().getName());
                    })) {
                        return true;
                    }
                } catch (RuntimeException ignored) {
                    // UIManager may not be ready while the host boots.
                }
                final long remainingMillis = deadline - System.currentTimeMillis();
                if (remainingMillis <= 0L) {
                    return false;
                }
                try {
                    if (flatLafInstalled.await(
                            Math.min(LOOK_AND_FEEL_POLL_MILLIS, remainingMillis), TimeUnit.MILLISECONDS)) {
                        return true;
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return false;
        } finally {
            UIManager.removePropertyChangeListener(listener);
        }
    }

    private static boolean isFlatLafValue(final Object lookAndFeel) {
        return lookAndFeel != null
                && FlatLafClasses.isFlatLaf(lookAndFeel.getClass().getName());
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
        final Path document = home.resolve(CONFIG_DIR).resolve(PLUGIN_ID).resolve(SELECTION_PATH);
        if (!Files.exists(document, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(document)) {
            return Optional.empty();
        }
        final String text;
        try {
            rejectSymlinkChain(document);
            if (!Files.isRegularFile(document, LinkOption.NOFOLLOW_LINKS)) {
                return diagnosed(DOCUMENT_UNREADABLE, "selection document is not a regular file");
            }
            if (Files.size(document) > MAX_DOCUMENT_BYTES) {
                return diagnosed(DOCUMENT_INVALID, "document exceeds the size limit");
            }
            final byte[] bytes = Files.readAllBytes(document);
            if (bytes.length > MAX_DOCUMENT_BYTES) {
                return diagnosed(DOCUMENT_INVALID, "document exceeds the size limit");
            }
            text = decodeUtf8(bytes);
        } catch (IOException unreadable) {
            return diagnosed(DOCUMENT_UNREADABLE, unreadable.getClass().getSimpleName());
        }
        final StoredDocument stored;
        try {
            stored = TypedConfigDocumentCodec.decode(text);
        } catch (IOException malformed) {
            return diagnosed(DOCUMENT_INVALID, malformed.getMessage());
        }
        if (stored.schemaVersion() != SUPPORTED_SCHEMA_VERSION) {
            return diagnosed(
                    SCHEMA_UNSUPPORTED,
                    "schemaVersion " + stored.schemaVersion() + " is not " + SUPPORTED_SCHEMA_VERSION);
        }
        final String encoded = stored.encodedValues().get(SELECTED_THEME_KEY);
        if (encoded == null) {
            // The plugin persists schema defaults for keys it never wrote.
            return Optional.empty();
        }
        final Optional<String> themeId = decodeSelectedTheme(encoded);
        if (themeId.isEmpty() && (encoded.isEmpty() || EMPTY_LIST_JSON.equals(encoded))) {
            // The plugin persists an empty bounded list when no theme is selected.
            return Optional.empty();
        }
        if (themeId.isEmpty()) {
            return diagnosed(SELECTION_INVALID, "selected-theme is not a bounded single-element list");
        }
        return NATIVE_THEME_ID.equals(themeId.orElseThrow()) ? Optional.empty() : themeId;
    }

    private static String decodeUtf8(final byte[] bytes) throws IOException {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new IOException("selection document is not valid UTF-8", exception);
        }
    }

    private static Optional<String> decodeSelectedTheme(final String encoded) {
        if (encoded.isEmpty() || !encoded.startsWith(LIST_PREFIX) || !encoded.endsWith(LIST_SUFFIX)) {
            return Optional.empty();
        }
        if (encoded.length() <= LIST_PREFIX.length() + LIST_SUFFIX.length()) {
            return Optional.empty();
        }
        final String themeId = encoded.substring(LIST_PREFIX.length(), encoded.length() - LIST_SUFFIX.length());
        // Rejects separators, parent traversals, control characters, and escapes before path resolution.
        if (themeId.length() > MAX_THEME_ID_LENGTH
                || !VALID_THEME_ID.matcher(themeId).matches()
                || themeId.contains(PARENT_SEGMENT)
                || themeId.endsWith(TRAILING_DOT)) {
            return Optional.empty();
        }
        return Optional.of(themeId);
    }

    /** Rejects symbolic links along the target path and its ancestors up to the home root. */
    private void rejectSymlinkChain(final Path path) throws IOException {
        Path current = path.toAbsolutePath().normalize();
        while (current != null && current.startsWith(home)) {
            if (Files.isSymbolicLink(current)) {
                throw new IOException("symbolic link rejected");
            }
            if (current.equals(home)) {
                break;
            }
            current = current.getParent();
        }
        if (current == null || !current.equals(home)) {
            throw new IOException("path escaped Turboism home");
        }
        if (Files.isSymbolicLink(home)) {
            throw new IOException("Turboism home symbolic link rejected");
        }
    }

    private static Optional<String> diagnosed(final String reason, final String detail) {
        dev.turboism.runtime.log.RuntimeDiagnostics.warn(
                APPEARANCE_COMPONENT,
                reason + " " + CONFIG_DIR + "/" + PLUGIN_ID + "/" + SELECTION_PATH + ": " + detail
                        + "; early theme injection skipped");
        return Optional.empty();
    }

    private Optional<Map<String, String>> loadBuiltinColors(final String themeId) {
        // Legacy built-in themes ship under /themes/<directory>/; keep lookup fail-open.
        final String slug = themeId.startsWith(BUILTIN_PREFIX) ? themeId.substring(BUILTIN_PREFIX.length()) : themeId;
        final String[] slugs = {slug, themeId.replace('.', '-')};
        for (String candidate : slugs) {
            final String base = "/" + THEMES_DIR + "/" + candidate + "/";
            try (InputStream input =
                    EarlyThemeAppearanceBootstrap.class.getResourceAsStream(base + THEME_PROPERTIES_FILE)) {
                if (input == null) {
                    continue;
                }
                final Properties meta = new Properties();
                meta.load(input);
                if (!themeId.equals(meta.getProperty(THEME_ID_PROPERTY))) {
                    continue;
                }
                try (InputStream colors =
                        EarlyThemeAppearanceBootstrap.class.getResourceAsStream(base + COLORS_PROPERTIES_FILE)) {
                    if (colors != null) {
                        return Optional.of(loadProperties(colors));
                    }
                }
            } catch (IOException | IllegalArgumentException ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private Optional<Map<String, String>> loadImportedColors(final String themeId) {
        final Path themesRoot = home.resolve(DATA_DIR)
                .resolve(PLUGIN_ID)
                .resolve(THEMES_DIR)
                .toAbsolutePath()
                .normalize();
        final Path archive = themesRoot.resolve(themeId + ARCHIVE_EXTENSION).toAbsolutePath().normalize();
        if (!archive.startsWith(themesRoot) || !themesRoot.equals(archive.getParent())) {
            return Optional.empty();
        }
        if (!Files.exists(archive, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(archive)) {
            return Optional.empty();
        }
        try {
            rejectSymlinkChain(archive);
            if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS) || Files.size(archive) > MAX_ARCHIVE_BYTES) {
                return Optional.empty();
            }
            try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive, LinkOption.NOFOLLOW_LINKS))) {
                int entryCount = 0;
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    entryCount++;
                    if (entryCount > MAX_ARCHIVE_ENTRIES) {
                        return Optional.empty();
                    }
                    if (!entry.isDirectory() && entry.getName().endsWith(COLORS_PROPERTIES_FILE)) {
                        final byte[] bytes = zip.readNBytes(MAX_COLORS_PROPERTIES_BYTES + 1);
                        if (bytes.length > MAX_COLORS_PROPERTIES_BYTES) {
                            return Optional.empty();
                        }
                        return Optional.of(loadProperties(new ByteArrayInputStream(bytes)));
                    }
                }
            }
        } catch (IOException | IllegalArgumentException ignored) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private static Map<String, String> loadProperties(final InputStream input) throws IOException {
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
