package dev.turboism.tests.plugin;

import dev.turboism.sdk.cubism.CubismPlugin;
import dev.turboism.sdk.plugin.PluginContext;

import javax.swing.AbstractButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Exact-host probe for the user-visible Plugin Management -> Install plugin chooser. */
public final class PluginManagementChooserValidationProbe implements CubismPlugin {

    static final String RESULT_RELATIVE = "state/plugin-management-chooser-result.properties";
    static final String READY_MARKER = "PLUGIN_CHOOSER_PROBE_READY";
    private static final Duration UI_TIMEOUT = Duration.ofSeconds(90);
    private static final List<String> MANAGEMENT_LABELS = List.of(
        "Plugin Management", "插件管理", "外掛管理", "プラグイン管理", "플러그인 관리"
    );
    private static final List<String> INSTALL_LABELS = List.of(
        "Install plugin…", "安装插件…", "安裝外掛…", "プラグインをインストール…", "플러그인 설치…"
    );

    private PluginContext context;
    private Thread validationThread;

    @Override
    public void init(final PluginContext context) {
        this.context = context;
        context.logger().info(READY_MARKER);
        if ("verify-load".equals(phase())) {
            context.logger().info("PLUGIN_INSTALL_TARGET_LOADED id=dev.turboism.validation.plugin-management-chooser");
        }
    }

    @Override
    public void enable() {
        if ("verify-load".equals(phase())) return;
        validationThread = new Thread(this::runValidation, "turboism-plugin-chooser-validation");
        validationThread.setDaemon(true);
        validationThread.start();
    }

    @Override
    public void disable() {
        if (validationThread != null) validationThread.interrupt();
    }

    private void runValidation() {
        final long startedNanos = System.nanoTime();
        final Path home = Path.of(requireProperty("turboism.home"));
        final Path result = home.resolve(RESULT_RELATIVE);
        final String runId = System.getProperty("turboism.validation.runId", "unknown");
        final String hostVersion = requireProperty("turboism.validation.hostVersion");
        ChooserObservation observation = ChooserObservation.failure("chooser-not-observed");
        boolean pending = false;
        try {
            final JMenuItem management = await(
                () -> onEdt(() -> findMenuItem(MANAGEMENT_LABELS)), UI_TIMEOUT, "plugin-management menu item");
            SwingUtilities.invokeLater(management::doClick);

            final JDialog managementDialog = await(
                () -> onEdt(PluginManagementChooserValidationProbe::findManagementDialog),
                UI_TIMEOUT,
                "plugin-management dialog"
            );
            final AbstractButton install = await(
                () -> onEdt(() -> findButton(managementDialog, INSTALL_LABELS)),
                UI_TIMEOUT,
                "install-plugin button"
            );
            SwingUtilities.invokeLater(install::doClick);

            final JFileChooser chooser = await(
                () -> onEdt(PluginManagementChooserValidationProbe::findShowingChooser),
                UI_TIMEOUT,
                "install-plugin file chooser"
            );
            observation = onEdt(() -> observeChooser(chooser));
            final Path selectedJar = Path.of(requireProperty("turboism.validation.selectedJar"));
            context.logger().info("PLUGIN_CHOOSER_SELECTED path=" + selectedJar
                + " regular=" + Files.isRegularFile(selectedJar)
                + " size=" + (Files.isRegularFile(selectedJar) ? Files.size(selectedJar) : -1L));
            if (!Files.isRegularFile(selectedJar)) {
                throw new IllegalStateException("Selected validation JAR is not a regular file: " + selectedJar);
            }
            onEdt(() -> {
                chooser.setSelectedFile(selectedJar.toFile());
                chooser.approveSelection();
                return null;
            });
            Thread.sleep(2_000L);
            context.logger().info("PLUGIN_CHOOSER_AFTER_APPROVE chooserShowing=" + onEdt(chooser::isShowing));
            await(() -> pendingInstallReady(home) ? Boolean.TRUE : null, Duration.ofSeconds(20), "pending direct-JAR install");
            context.logger().info("PLUGIN_CHOOSER_PENDING journal="
                + home.resolve("state/runtime/plugin-management/pending.json"));
            pending = true;
            writeResult(result, runId, hostVersion, observation, pending,
                (System.nanoTime() - startedNanos) / 1_000_000L);
            context.logger().info("PLUGIN_CHOOSER_RESULT status=" + (observation.passed() && pending ? "PASS" : "FAIL")
                + " description=" + observation.description()
                + " acceptAll=" + observation.acceptAllEnabled()
                + " jarAccepted=" + observation.jarAccepted()
                + " tpluginAccepted=" + observation.tpluginAccepted()
                + " pending=" + pending);
            onEdt(() -> {
                managementDialog.dispose();
                return null;
            });
        } catch (Exception failure) {
            context.logger().error("PLUGIN_CHOOSER_RESULT status=FAIL", failure);
            try {
                writeResult(result, runId, hostVersion, observation, pending,
                    (System.nanoTime() - startedNanos) / 1_000_000L);
            } catch (Exception writeFailure) {
                context.logger().error("Plugin chooser result file could not be written", writeFailure);
            }
        } finally {
            try {
                Thread.sleep(2_000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            Runtime.getRuntime().exit(0);
        }
    }

    static ChooserObservation observeChooser(final JFileChooser chooser) {
        final boolean acceptAll = chooser.isAcceptAllFileFilterUsed();
        final javax.swing.filechooser.FileFilter active = chooser.getFileFilter();
        final String description = active == null ? "none" : String.valueOf(active.getDescription());
        final boolean jarAccepted = active != null && active.accept(Path.of("validation-plugin.jar").toFile());
        final boolean tpluginAccepted = active != null && active.accept(Path.of("validation-plugin.tplugin").toFile());
        final boolean mentionsJar = description.toLowerCase(Locale.ROOT).contains("*.jar");
        final boolean mentionsLegacy = description.toLowerCase(Locale.ROOT).contains("tplugin");
        final boolean passed = !acceptAll && jarAccepted && !tpluginAccepted && mentionsJar && !mentionsLegacy;
        return new ChooserObservation(
            acceptAll, jarAccepted, tpluginAccepted, description.replace('\n', ' '), passed
        );
    }

    static void writeResult(
        final Path result,
        final String runId,
        final String hostVersion,
        final ChooserObservation observation,
        final boolean pending,
        final long durationMillis
    ) throws Exception {
        Files.createDirectories(result.getParent());
        Files.writeString(
            result,
            "schemaVersion=1\n"
                + "runId=" + singleLine(runId) + "\n"
                + "hostVersion=" + singleLine(hostVersion) + "\n"
                + "acceptAllEnabled=" + observation.acceptAllEnabled() + "\n"
                + "jarAccepted=" + observation.jarAccepted() + "\n"
                + "tpluginAccepted=" + observation.tpluginAccepted() + "\n"
                + "description=" + singleLine(observation.description()) + "\n"
                + "pending=" + pending + "\n"
                + "durationMillis=" + durationMillis + "\n"
                + "status=" + (observation.passed() && pending ? "PASS" : "FAIL") + "\n",
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING
        );
    }

    private static boolean pendingInstallReady(final Path home) {
        final Path pending = home.resolve("state/runtime/plugin-management/pending.json");
        final Path packages = home.resolve("state/runtime/plugin-management/packages");
        try {
            if (!Files.isRegularFile(pending) || !Files.isDirectory(packages)) return false;
            final String journal = Files.readString(pending);
            final String expectedPluginId = requireProperty("turboism.validation.expectedPluginId");
            if (!journal.contains(expectedPluginId)
                || !journal.matches("(?s).*\\\"type\\\"\\s*:\\s*\\\"INSTALL\\\".*")) return false;
            try (var files = Files.list(packages)) {
                return files.anyMatch(path -> Files.isRegularFile(path)
                    && path.getFileName().toString().endsWith(".jar"));
            }
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String phase() {
        return System.getProperty("turboism.validation.phase", "stage");
    }

    private static JMenuItem findMenuItem(final List<String> labels) {
        for (Window window : Window.getWindows()) {
            final JMenuItem found = findComponent(window, JMenuItem.class, item -> labels.contains(item.getText()));
            if (found != null) return found;
        }
        return null;
    }

    private static JDialog findManagementDialog() {
        for (Window window : Window.getWindows()) {
            if (window instanceof JDialog dialog && dialog.isShowing()
                && MANAGEMENT_LABELS.contains(dialog.getTitle())) return dialog;
        }
        return null;
    }

    private static AbstractButton findButton(final Container root, final List<String> labels) {
        return findComponent(root, AbstractButton.class, button -> labels.contains(button.getText()));
    }

    private static JFileChooser findShowingChooser() {
        for (Window window : Window.getWindows()) {
            if (!window.isShowing()) continue;
            final JFileChooser chooser = findComponent(window, JFileChooser.class, ignored -> true);
            if (chooser != null && chooser.isShowing()) return chooser;
        }
        return null;
    }
    private static <T extends Component> T findComponent(
        final Component component,
        final Class<T> type,
        final java.util.function.Predicate<T> predicate
    ) {
        if (type.isInstance(component)) {
            final T candidate = type.cast(component);
            if (predicate.test(candidate)) return candidate;
        }
        if (component instanceof JMenu menu) {
            for (Component child : menu.getMenuComponents()) {
                final T found = findComponent(child, type, predicate);
                if (found != null) return found;
            }
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                final T found = findComponent(child, type, predicate);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static <T> T await(
        final Callable<T> query,
        final Duration timeout,
        final String description
    ) throws Exception {
        final long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted()) {
            final T value = query.call();
            if (value != null) return value;
            Thread.sleep(250L);
        }
        throw new IllegalStateException("Timed out waiting for " + description);
    }

    private static <T> T onEdt(final Callable<T> call) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return call.call();
        final AtomicReference<T> result = new AtomicReference<>();
        final AtomicReference<Exception> failure = new AtomicReference<>();
        final CountDownLatch completed = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            try {
                result.set(call.call());
            } catch (Exception exception) {
                failure.set(exception);
            } finally {
                completed.countDown();
            }
        });
        if (!completed.await(60L, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Cubism EDT did not accept the probe within 60 seconds");
        }
        if (failure.get() != null) throw failure.get();
        return result.get();
    }

    private static String requireProperty(final String name) {
        final String value = System.getProperty(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be set");
        return value;
    }

    private static String singleLine(final String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
    }

    record ChooserObservation(
        boolean acceptAllEnabled,
        boolean jarAccepted,
        boolean tpluginAccepted,
        String description,
        boolean passed
    ) {
        static ChooserObservation failure(final String description) {
            return new ChooserObservation(true, false, true, description, false);
        }
    }
}
