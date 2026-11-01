package dev.turboism.ui.appearance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.runtime.log.RuntimeDiagnostics;
import dev.turboism.sdk.config.ConfigCodecs;
import dev.turboism.sdk.config.ConfigKey;
import dev.turboism.sdk.config.ConfigSchema;
import dev.turboism.sdk.config.PluginConfigRegistry;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.task.RuntimePluginTaskScheduler;
import dev.turboism.test.concurrent.Await;
import java.awt.Color;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import javax.swing.UIDefaults;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Focused tests for the early-theme startup EDT correction: UIManager access
 * in early-theme readiness polling and native off-canvas capture is dispatched
 * to the EDT, while polling sleeps stay on the daemon/background thread.
 *
 * <p>The persisted-selection cases bind the startup reader to the private
 * {@code ui-theme} schema: a document the runtime typed-config writer produced
 * must be read, and any other shape must fail with one bounded diagnostic
 * instead of silently loading the wrong theme.</p>
 */
class EarlyThemeAppearanceBootstrapTest {

    private static final String THEME_PLUGIN_ID = "dev.turboism.plugin.uitheme";
    private static final String SELECTION_PATH = "ui-theme/selection.cfg";
    private static final String SELECTED_THEME_KEY = "selected-theme";

    private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
        final Thread thread = new Thread(r, "early-theme-test-worker");
        thread.setDaemon(true);
        return thread;
    });

    @AfterEach
    void tearDown() throws Exception {
        RuntimeDiagnostics.clear();
        UIManager.getDefaults().remove("CubismCommon.gl.viewArea.background");
        UIManager.getDefaults().remove("Turboism.native.CubismCommon.gl.viewArea.background");
        SwingUtilities.invokeAndWait(() -> {
            try {
                UIManager.setLookAndFeel(new javax.swing.plaf.metal.MetalLookAndFeel());
            } catch (UnsupportedLookAndFeelException exception) {
                throw new IllegalStateException(exception);
            }
        });
        workers.shutdownNow();
    }

    @Test
    void waitForFlatLafDispatchesLookAndFeelPollingToTheEdt() throws Exception {
        SwingUtilities.invokeAndWait(() -> installLookAndFeel(new TestCubismLightTheme()));
        final EarlyThemeAppearanceBootstrap bootstrap = newBootstrap();
        try (EdtHold hold = new EdtHold()) {
            final Future<Boolean> poll = workers.submit(bootstrap::waitForFlatLaf);
            // While the EDT is held, an EDT-dispatched poll cannot observe the
            // installed look-and-feel; a direct UIManager read would return at once.
            // Intentionally assert nothing happens: while EDT is held, poll cannot complete.
            Thread.sleep(400L);
            assertFalse(poll.isDone(), "look-and-feel polling must run UIManager reads on the EDT");
            hold.release();
            assertTrue(poll.get(5, TimeUnit.SECONDS), "poll must find the installed look-and-feel");
        }
    }

    @Test
    void captureNativeOffCanvasBackgroundDispatchesUIManagerAccessToTheEdt() throws Exception {
        final Color nativeBackground = new Color(222, 223, 224);
        SwingUtilities.invokeAndWait(() -> UIManager.put("CubismCommon.gl.viewArea.background", nativeBackground));
        try (EdtHold hold = new EdtHold()) {
            final Future<?> capture =
                    workers.submit(() -> SwingFlatLafHostOperations.captureNativeOffCanvasBackground());
            // Intentionally assert nothing happens: while EDT is held, capture cannot complete.
            Thread.sleep(400L);
            assertFalse(capture.isDone(), "off-canvas capture must run UIManager access on the EDT");
            hold.release();
            capture.get(5, TimeUnit.SECONDS);
        }
        assertEquals(nativeBackground, UIManager.get("Turboism.native.CubismCommon.gl.viewArea.background"));
    }

    @Test
    void runWithoutUsableSelectionCompletesWhileEdtIsHeld(@TempDir Path home) throws Exception {
        final EarlyThemeAppearanceBootstrap bootstrap = new EarlyThemeAppearanceBootstrap(
                home, EarlyThemeAppearanceBootstrapTest.class.getClassLoader(), () -> {});
        try (EdtHold hold = new EdtHold()) {
            final Future<?> completed = workers.submit((java.util.concurrent.Callable<Object>) () -> {
                invokeRun(bootstrap);
                return null;
            });
            // A no-selection home must return before any Swing/EDT/UIManager
            // dependency: with the EDT held, an early waitForFlatLaf() would
            // block the worker in invokeAndWait past the bounded timeout.
            completed.get(5, TimeUnit.SECONDS);
            assertTrue(completed.isDone(), "run must finish while the EDT is held");
        }
    }

    /** Invokes the private bootstrap run path; no production seam is changed. */
    private static void invokeRun(final EarlyThemeAppearanceBootstrap bootstrap) throws Exception {
        final java.lang.reflect.Method run = EarlyThemeAppearanceBootstrap.class.getDeclaredMethod("run");
        run.setAccessible(true);
        run.invoke(bootstrap);
    }

    @Test
    void readsSelectionWrittenByTheRuntimeTypedConfigWriter(@TempDir Path home) throws Exception {
        writeSelectionThroughTypedConfigRegistry(home, List.of("turboism.nord"));

        assertEquals(
                Optional.of("turboism.nord"),
                readSelectionThemeId(new EarlyThemeAppearanceBootstrap(
                        home, EarlyThemeAppearanceBootstrapTest.class.getClassLoader(), () -> {})));
    }

    @Test
    void treatsTheNativeSentinelAsNoSelection(@TempDir Path home) throws Exception {
        writeSelectionThroughTypedConfigRegistry(home, List.of("__native__"));

        assertEquals(
                Optional.empty(),
                readSelectionThemeId(new EarlyThemeAppearanceBootstrap(
                        home, EarlyThemeAppearanceBootstrapTest.class.getClassLoader(), () -> {})));
    }

    @Test
    void treatsAnEmptyPersistedListAsNoSelection(@TempDir Path home) throws Exception {
        // clearSelectedThemeId() persists an empty bounded list; it is a valid state, not a
        // malformed document, so it must not produce a diagnostic.
        writeSelectionThroughTypedConfigRegistry(home, List.of());

        final List<String> diagnostics = captureDiagnostics();
        assertEquals(
                Optional.empty(),
                readSelectionThemeId(new EarlyThemeAppearanceBootstrap(
                        home, EarlyThemeAppearanceBootstrapTest.class.getClassLoader(), () -> {})));
        assertEquals(List.of(), diagnostics);
    }

    @Test
    void rejectsUnsupportedSchemaVersionWithOneBoundedDiagnostic(@TempDir Path home) throws Exception {
        writeRawSelectionDocument(home, document("2", 0, "selected-theme", "[\"turboism.nord\"]"));

        final List<String> diagnostics = captureDiagnostics();
        final EarlyThemeAppearanceBootstrap bootstrap = new EarlyThemeAppearanceBootstrap(
                home, EarlyThemeAppearanceBootstrapTest.class.getClassLoader(), () -> {});

        assertEquals(Optional.empty(), readSelectionThemeId(bootstrap));
        assertEquals(1, diagnostics.size(), diagnostics.toString());
        assertTrue(
                diagnostics.get(0).contains("EARLY_THEME_SELECTION_SCHEMA_UNSUPPORTED"),
                diagnostics.get(0));
    }

    @Test
    void rejectsANonTypedConfigDocumentWithOneBoundedDiagnostic(@TempDir Path home) throws Exception {
        writeRawSelectionDocument(home, SELECTED_THEME_KEY + "=[\"turboism.nord\"]\n");

        final List<String> diagnostics = captureDiagnostics();
        final EarlyThemeAppearanceBootstrap bootstrap = new EarlyThemeAppearanceBootstrap(
                home, EarlyThemeAppearanceBootstrapTest.class.getClassLoader(), () -> {});

        assertEquals(Optional.empty(), readSelectionThemeId(bootstrap));
        assertEquals(1, diagnostics.size(), diagnostics.toString());
        assertTrue(diagnostics.get(0).contains("EARLY_THEME_SELECTION_DOCUMENT_INVALID"), diagnostics.get(0));
    }

    @Test
    void rejectsANonCanonicalEntryCountWithOneBoundedDiagnostic(@TempDir Path home) throws Exception {
        // count=2 but only one entry line: a truncated or hand-edited document must not
        // be half-read into a theme id.
        final String body = entry("selected-theme", "[\"turboism.nord\"]");
        writeRawSelectionDocument(
                home,
                "turboism-typed-config-v1\nschemaVersion=1\nrevision=1\ncount=2\n" + body);

        final List<String> diagnostics = captureDiagnostics();
        final EarlyThemeAppearanceBootstrap bootstrap = new EarlyThemeAppearanceBootstrap(
                home, EarlyThemeAppearanceBootstrapTest.class.getClassLoader(), () -> {});

        assertEquals(Optional.empty(), readSelectionThemeId(bootstrap));
        assertEquals(1, diagnostics.size(), diagnostics.toString());
        assertTrue(diagnostics.get(0).contains("EARLY_THEME_SELECTION_DOCUMENT_INVALID"), diagnostics.get(0));
    }

    /**
     * Drift gate: the startup reader hard-codes one private {@code ui-theme} schema. This
     * asserts the plugin side still declares exactly that path, selector key, schema
     * version and native sentinel, so a plugin-side change fails here instead of silently
     * dropping the early theme at startup.
     */
    @Test
    void pluginThemeSchemaStaysBoundToTheEarlyThemeReader() throws Exception {
        final Path pluginSource = repositoryRoot()
                .resolve("plugins/ui-theme/src/main/java/dev/turboism/plugin/uitheme/service/ThemeSelectionConfig.java");
        assertTrue(Files.isRegularFile(pluginSource), "plugin schema source is missing: " + pluginSource);
        final String source = Files.readString(pluginSource, StandardCharsets.UTF_8);

        assertTrue(
                source.contains("CONFIG_PATH = \"" + SELECTION_PATH + "\""),
                "ui-theme selection path changed; EarlyThemeAppearanceBootstrap reads " + SELECTION_PATH);
        assertTrue(
                source.contains("new ConfigKey<>(CONFIG_ID, \"" + SELECTED_THEME_KEY + "\""),
                "ui-theme selector key changed; EarlyThemeAppearanceBootstrap reads " + SELECTED_THEME_KEY);
        assertTrue(
                source.contains("new ConfigSchema(CONFIG_ID, CONFIG_PATH, "
                        + EarlyThemeAppearanceBootstrap.SUPPORTED_SCHEMA_VERSION + ","),
                "ui-theme schema version changed; EarlyThemeAppearanceBootstrap supports "
                        + EarlyThemeAppearanceBootstrap.SUPPORTED_SCHEMA_VERSION);
        assertTrue(
                source.contains("NATIVE_ID = \"" + EarlyThemeAppearanceBootstrap.NATIVE_THEME_ID + "\""),
                "ui-theme native sentinel changed; EarlyThemeAppearanceBootstrap skips "
                        + EarlyThemeAppearanceBootstrap.NATIVE_THEME_ID);
    }

    private static Optional<String> readSelectionThemeId(final EarlyThemeAppearanceBootstrap bootstrap)
            throws Exception {
        final java.lang.reflect.Method read =
                EarlyThemeAppearanceBootstrap.class.getDeclaredMethod("readSelectionThemeId");
        read.setAccessible(true);
        @SuppressWarnings("unchecked")
        final Optional<String> value = (Optional<String>) read.invoke(bootstrap);
        return value;
    }

    /**
     * Captures only the bootstrap's own diagnostics.
     *
     * <p>{@link RuntimeDiagnostics#install} is process-global, and other tests can
     * still emit diagnostics from background EDT work after their test method
     * returns (for example a deferred plugin UI teardown). Filtering on the
     * {@code appearance} component keeps this test's intent — "reading this document
     * must not produce a diagnostic" — without asserting on that foreign traffic.</p>
     */
    private static List<String> captureDiagnostics() {
        final List<String> diagnostics = new CopyOnWriteArrayList<>();
        RuntimeDiagnostics.install((level, component, message, failure) -> {
            if ("appearance".equals(component)) {
                diagnostics.add(level + " " + component + " " + message);
            }
        });
        return diagnostics;
    }

    private static void writeSelectionThroughTypedConfigRegistry(final Path home, final List<String> selection)
            throws Exception {
        final Path configRoot = home.resolve("config").resolve(THEME_PLUGIN_ID);
        // The anchored typed-config root requires its parent to exist before registration.
        Files.createDirectories(configRoot.getParent());
        final RuntimeScheduler scheduler = new RuntimeScheduler(
                new DefaultWorkBudgetPolicy(),
                new PluginWorkExecutorRegistry(1, 4, ignored -> {}, Clock.systemUTC()),
                SidecarDispatcher.noop(),
                ignored -> {});
        final DisposableScope scope = new DisposableScope();
        try {
            final RuntimePluginTaskScheduler tasks = new RuntimePluginTaskScheduler("early-theme-test", scheduler, scope);
            final ConfigKey<List<String>> key = new ConfigKey<>(
                    "ui-theme.selection", SELECTED_THEME_KEY, List.of(), ConfigCodecs.boundedStringList(1, 128));
            final ConfigSchema schema = new ConfigSchema("ui-theme.selection", SELECTION_PATH, 1, List.of(key));
            try (var registry = new dev.turboism.config.RuntimeTypedPluginConfigRegistry(
                    new NoopLegacyConfigRegistry(),
                    THEME_PLUGIN_ID,
                    configRoot,
                    Set.of(PermissionIds.TURBOISM_CONFIG_PLUGIN_READ, PermissionIds.TURBOISM_CONFIG_PLUGIN_WRITE),
                    tasks,
                    scope)) {
                registry.registerSchema(schema, List.of()).toCompletableFuture().join();
                final var result = registry.write(key, selection, 0L).toCompletableFuture().join();
                assertTrue(result.written(), "typed config write must succeed: " + result);
            }
        } finally {
            scope.close();
            scheduler.shutdown();
        }
    }

    private static void writeRawSelectionDocument(final Path home, final String document) throws Exception {
        final Path file = home.resolve("config").resolve(THEME_PLUGIN_ID).resolve(SELECTION_PATH);
        Files.createDirectories(file.getParent());
        Files.writeString(file, document, StandardCharsets.UTF_8);
    }

    private static String document(
            final String schemaVersion, final long revision, final String key, final String value) {
        return "turboism-typed-config-v1\nschemaVersion=" + schemaVersion + "\nrevision=" + revision
                + "\ncount=2\n" + entry("unrelated-setting", "\"kept\"") + entry(key, value);
    }

    private static String entry(final String key, final String value) {
        final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString(key.getBytes(StandardCharsets.UTF_8)) + ":"
                + encoder.encodeToString(value.getBytes(StandardCharsets.UTF_8)) + "\n";
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("repository root with settings.gradle.kts was not found");
    }

    /** Legacy registry slot the typed facade only delegates unused property calls to. */
    private static final class NoopLegacyConfigRegistry implements PluginConfigRegistry {
        @Override
        public Registration readScope(final String relativePath) {
            return () -> {};
        }

        @Override
        public Registration writeScope(final String relativePath) {
            return () -> {};
        }

        @Override
        public Optional<String> readString(final String relativePath, final String key) {
            return Optional.empty();
        }

        @Override
        public void writeString(final String relativePath, final String key, final String value) {}
    }

    @Test
    void pollingSleepStaysOffTheEdtSoHostInitializationIsNotBlocked() throws Exception {
        final EarlyThemeAppearanceBootstrap bootstrap = newBootstrap();
        final AtomicReference<Thread> edtThread = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> edtThread.set(Thread.currentThread()));
        final AtomicReference<Thread> pollThread = new AtomicReference<>();
        final CountDownLatch pollStarted = new CountDownLatch(1);
        final Future<Boolean> poll = workers.submit(() -> {
            pollThread.set(Thread.currentThread());
            pollStarted.countDown();
            return bootstrap.waitForFlatLaf();
        });
        assertTrue(pollStarted.await(2, TimeUnit.SECONDS), "poll worker must start");
        final Thread worker = pollThread.get();
        assertNotSame(edtThread.get(), worker, "poll must run on a non-EDT worker");
        // Deterministic sleep evidence: sample the exact worker until its state
        // and stack show the active polling sleep (TIMED_WAITING with
        // Thread.sleep under waitForFlatLaf). A sleep moved into the EDT lambda
        // would put the 100 ms sleep on the EDT; this worker would never own it
        // and the bounded observation would expire.
        assertTrue(awaitPollingSleep(worker, 2_000L), "polling sleep must be observed on the poll worker's own stack");
        final CountDownLatch edtProbe = new CountDownLatch(1);
        SwingUtilities.invokeLater(edtProbe::countDown);
        // The EDT stays responsive while the poll sleeps on its worker.
        assertTrue(edtProbe.await(2, TimeUnit.SECONDS), "EDT must remain responsive while the poll is active");
        assertFalse(poll.isDone(), "poll must remain active while the probe fires");
        poll.cancel(true);
    }

    /**
     * Bounded state sampling: waits until the worker is observed sleeping in
     * the early-theme poll (TIMED_WAITING with {@code Thread.sleep} on the
     * {@code waitForFlatLaf} stack). No latency threshold is asserted.
     */
    private static boolean awaitPollingSleep(final Thread worker, final long timeoutMillis) {
        Await.condition(
                "polling sleep observed",
                () -> worker.getState() == Thread.State.TIMED_WAITING && isPollingSleep(worker.getStackTrace()),
                java.time.Duration.ofMillis(timeoutMillis));
        return true;
    }

    private static boolean isPollingSleep(final StackTraceElement[] stack) {
        boolean sleeping = false;
        boolean polling = false;
        for (StackTraceElement element : stack) {
            sleeping |= "java.lang.Thread".equals(element.getClassName()) && "sleep".equals(element.getMethodName());
            polling |= EarlyThemeAppearanceBootstrap.class.getName().equals(element.getClassName())
                    && "waitForFlatLaf".equals(element.getMethodName());
        }
        return sleeping && polling;
    }

    private static EarlyThemeAppearanceBootstrap newBootstrap() {
        return new EarlyThemeAppearanceBootstrap(
                java.nio.file.Path.of("."), EarlyThemeAppearanceBootstrapTest.class.getClassLoader(), () -> {});
    }

    private static void installLookAndFeel(final javax.swing.LookAndFeel lookAndFeel) {
        try {
            UIManager.setLookAndFeel(lookAndFeel);
        } catch (UnsupportedLookAndFeelException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** Occupies the EDT until {@link #release()}, so EDT-dispatched work can be observed. */
    private static final class EdtHold implements AutoCloseable {
        private final CountDownLatch held = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        private EdtHold() throws Exception {
            SwingUtilities.invokeLater(() -> {
                held.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(held.await(2, TimeUnit.SECONDS), "EDT hold did not start");
        }

        void release() {
            release.countDown();
        }

        @Override
        public void close() {
            release.countDown();
        }
    }

    /**
     * Look-and-feel whose class name trips the early-theme FlatLaf detector,
     * so the poll can succeed without a real FlatLaf on the test classpath.
     */
    private static final class TestCubismLightTheme extends javax.swing.LookAndFeel {
        @Override
        public String getName() {
            return "TestCubismLightTheme";
        }

        @Override
        public String getID() {
            return "TestCubismLightTheme";
        }

        @Override
        public String getDescription() {
            return "Stub look and feel for early-theme EDT dispatch tests";
        }

        @Override
        public boolean isNativeLookAndFeel() {
            return false;
        }

        @Override
        public boolean isSupportedLookAndFeel() {
            return true;
        }

        @Override
        public UIDefaults getDefaults() {
            return new UIDefaults();
        }
    }
}
