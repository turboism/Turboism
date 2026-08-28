package dev.turboism.tests.plugin;

import dev.turboism.sdk.cubism.CubismPlugin;
import dev.turboism.sdk.cubism.model.ModelObjectDescriptor;
import dev.turboism.sdk.plugin.PluginContext;

import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.awt.Window;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * Exact-host validation probe for the production Turboism with fx UI and ACP process path.
 *
 * <p>The probe opens the production Agent window through its host main-toolbar contribution, fills
 * only named Swing controls, approves the expected fx permission dialog, and verifies the requested
 * rename through the independent public model-object service. It then drives cancellation, verifies
 * settings after both windows are hidden and reopened, and requests Cubism shutdown.</p>
 */
public final class FxHostValidationProbe implements CubismPlugin {

    private static final String RESULT_RELATIVE = "state/fx-host-validation-result.properties";
    private static final String READY_MARKER = "FX_HOST_PROBE_READY";
    private static final String RESULT_MARKER = "FX_HOST_RESULT";
    private static final String FX_PLUGIN_ID = "dev.turboism.plugin.turboism-with-fx";
    private static final String FX_TOOLBAR_BUTTON =
        FX_PLUGIN_ID + ":turboism-with-fx.main-toolbar";
    private static final String TARGET_NAME_PREFIX = "TurboismFxValidated";
    private static final String VALIDATION_INITIAL_PROMPT =
        "Perform only the exact requested Turboism MCP operation and make no extra changes.";
    private static final List<String> CONNECTED_STATUS_PREFIXES = List.of(
        "Connected in compatibility mode",
        "已通过兼容模式连接",
        "已透过相容模式连线",
        "互換モードで接続しました",
        "호환 모드로 연결됨"
    );
    private static final List<String> CONNECTING_STATUS_PREFIXES = List.of(
        "Connecting to fx",
        "正在连接 fx",
        "正在連線 fx",
        "fx に接続しています",
        "fx에 연결 중"
    );
    private static final List<String> PROMPTING_STATUS_PREFIXES = List.of(
        "fx is working",
        "fx 正在工作",
        "fx が処理中です",
        "fx 작업 중"
    );
    private static final List<String> PROMPT_COMPLETE_STATUS_PREFIXES = List.of(
        "Prompt finished:",
        "任务结束：",
        "工作结束：",
        "処理完了：",
        "작업 완료:"
    );
    private static final List<String> PROCESS_TERMINATED_STATUS_PREFIXES = List.of(
        "The fx process terminated",
        "fx 进程已终止",
        "fx 處理程序已終止",
        "fx プロセスが終了しました",
        "fx 프로세스가 종료되었습니다"
    );
    private static final List<String> PROMPT_FAILED_STATUS_PREFIXES = List.of(
        "The fx prompt failed",
        "fx 执行任务失败",
        "fx 執行工作失敗",
        "fx の処理に失敗しました",
        "fx 작업에 실패했습니다"
    );
    private static final Duration UI_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration PROMPT_TIMEOUT = Duration.ofSeconds(240);

    private PluginContext context;
    private Thread validationThread;

    @Override
    public void init(final PluginContext context) {
        this.context = Objects.requireNonNull(context, "context");
        context.logger().info("Turboism with fx host validation probe initialized");
    }

    @Override
    public void enable() {
        validationThread = new Thread(this::runValidation, "turboism-fx-host-validation");
        validationThread.setDaemon(true);
        validationThread.start();
    }

    @Override
    public void disable() {
        if (validationThread != null) validationThread.interrupt();
    }

    private void runValidation() {
        final long started = System.nanoTime();
        final Path result = Path.of(requireProperty("turboism.home")).resolve(RESULT_RELATIVE);
        String targetId = "unknown";
        String targetKind = "unknown";
        String targetName = "unknown";
        try {
            final ModelObjectDescriptor target = awaitModelObject();
            targetId = target.reference().id();
            targetKind = target.reference().kind().name();
            targetName = TARGET_NAME_PREFIX + "_" + requireProperty("turboism.validation.runId")
                .replaceAll("[^A-Za-z0-9_-]", "_");
            context.logger().info(READY_MARKER + " targetKind=" + targetKind + " targetId=" + targetId);
            awaitToolbarButton(FX_TOOLBAR_BUTTON, Duration.ofSeconds(120));
            openToolbarButton(FX_TOOLBAR_BUTTON);
            final Window agentWindow = awaitNamedWindow(
                "turboism-with-fx.prompt",
                UI_TIMEOUT
            );
            clickNamed(agentWindow, "turboism-with-fx.open-settings");
            final Window settingsWindow = awaitNamedWindow(
                "turboism-with-fx.executable",
                UI_TIMEOUT
            );
            configureAndConnectIfNeeded(settingsWindow);
            awaitRuntimeStatus(
                settingsWindow,
                text -> startsWithAny(text, CONNECTED_STATUS_PREFIXES),
                CONNECT_TIMEOUT
            );
            final String selectedProvider = selectedValue(
                settingsWindow, "turboism-with-fx.provider"
            );
            final String selectedModel = selectedValue(
                settingsWindow, "turboism-with-fx.model"
            );
            setInitialPrompt(settingsWindow, validationInitialPrompt());
            clickNamed(settingsWindow, "turboism-with-fx.save-settings");
            awaitRuntimeStatus(
                settingsWindow,
                text -> text.toLowerCase(java.util.Locale.ROOT).contains("saved")
                    || text.contains("已保存")
                    || text.contains("已儲存")
                    || text.contains("保存しました")
                    || text.contains("저장"),
                UI_TIMEOUT
            );
            awaitSessionSidebar(agentWindow);
            if (!"codex".equals(selectedProvider) || !"gpt-5.3-codex-spark".equals(selectedModel)) {
                throw new IllegalStateException("fx did not expose the required provider/model selection");
            }

            final String prompt = "Use the Turboism MCP tool turboism_model_object_rename exactly once. "
                + "Rename the " + targetKind + " object whose id is exactly " + targetId
                + " to exactly " + targetName + ". Do not rename any other object.";
            enterPromptAndSend(agentWindow, prompt);
            approvePermissionDialog();
            awaitRename(targetId, targetName);
            awaitStatus(
                agentWindow,
                text -> startsWithAny(text, PROMPT_COMPLETE_STATUS_PREFIXES),
                PROMPT_TIMEOUT
            );

            enterPromptAndSend(
                agentWindow,
                "Wait without using tools and continue working until I cancel this request."
            );
            awaitStatus(
                agentWindow,
                text -> startsWithAny(text, PROMPTING_STATUS_PREFIXES),
                Duration.ofSeconds(30)
            );
            clickNamed(agentWindow, "turboism-with-fx.cancel");
            awaitStatus(
                agentWindow,
                text -> startsWithAny(text, PROMPT_COMPLETE_STATUS_PREFIXES)
                    || startsWithAny(text, PROCESS_TERMINATED_STATUS_PREFIXES)
                    || startsWithAny(text, PROMPT_FAILED_STATUS_PREFIXES),
                Duration.ofSeconds(60)
            );

            hide(agentWindow);
            hide(settingsWindow);
            awaitNoNamedWindow("turboism-with-fx.prompt", Duration.ofSeconds(30));
            awaitNoNamedWindow("turboism-with-fx.executable", Duration.ofSeconds(30));
            awaitPersistedSettings();
            openToolbarButton(FX_TOOLBAR_BUTTON);
            final Window reopenedAgent = awaitNamedWindow(
                "turboism-with-fx.prompt",
                UI_TIMEOUT
            );
            clickNamed(reopenedAgent, "turboism-with-fx.open-settings");
            final Window reopened = awaitNamedWindow(
                "turboism-with-fx.executable",
                UI_TIMEOUT
            );
            if (!readNamedField(reopened, "turboism-with-fx.executable")
                .equals(requireProperty("turboism.validation.fxExecutable"))) {
                throw new IllegalStateException("fx executable setting was not retained after reopening");
            }
            if (!isNamedSelected(reopened, "turboism-with-fx.compatibility")) {
                throw new IllegalStateException("compatibility acknowledgement was not retained");
            }
            if (!readNamedTextArea(reopened, "turboism-with-fx.initial-prompt")
                .equals(validationInitialPrompt())) {
                throw new IllegalStateException("initial instructions were not retained");
            }
            dispose(reopened);
            hide(reopenedAgent);

            closeHost();
            writeResult(result, true, targetKind, targetId, targetName, selectedProvider, selectedModel,
                elapsedMillis(started), "none");
            context.logger().info(RESULT_MARKER + " status=PASS");
        } catch (Exception failure) {
            context.logger().error(RESULT_MARKER + " status=FAIL", failure);
            try {
                writeResult(result, false, targetKind, targetId, targetName, "unknown", "unknown",
                    elapsedMillis(started), failure.getClass().getSimpleName());
            } catch (Exception writeFailure) {
                context.logger().error("fx host validation result could not be written", writeFailure);
            }
        }
    }

    private ModelObjectDescriptor awaitModelObject() throws Exception {
        for (int attempt = 0; attempt < 120 && !Thread.currentThread().isInterrupted(); attempt++) {
            try {
                final List<ModelObjectDescriptor> objects = context.modelObjects().list();
                if (!objects.isEmpty()) return objects.get(0);
            } catch (RuntimeException ignored) {
                // The exact-host fixture can still be loading.
            }
            Thread.sleep(1000L);
        }
        throw new IllegalStateException("fixture model objects did not become available");
    }

    private void configureAndConnectIfNeeded(final Window window) throws Exception {
        final boolean connectingOrConnected = onEdt(() -> {
            final String status = named(
                window,
                javax.swing.JLabel.class,
                "turboism-with-fx.runtime-status"
            ).getText();
            return startsWithAny(status, CONNECTED_STATUS_PREFIXES)
                || startsWithAny(status, CONNECTING_STATUS_PREFIXES);
        });
        if (connectingOrConnected) return;
        onEdt(() -> {
            named(window, JTextField.class, "turboism-with-fx.executable")
                .setText(requireProperty("turboism.validation.fxExecutable"));
            named(window, JCheckBox.class, "turboism-with-fx.compatibility").setSelected(true);
            named(window, JButton.class, "turboism-with-fx.connect").doClick(0);
            return null;
        });
    }

    private static void enterPromptAndSend(final Window window, final String prompt) throws Exception {
        onEdt(() -> {
            named(window, JTextArea.class, "turboism-with-fx.prompt").setText(prompt);
            named(window, JButton.class, "turboism-with-fx.send").doClick(0);
            return null;
        });
    }

    private static void awaitSessionSidebar(final Window window) throws Exception {
        final long deadline = System.nanoTime() + UI_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted()) {
            final boolean populated = onEdt(() -> {
                final JList<?> sessions = named(window, JList.class, "turboism-with-fx.sessions");
                return sessions.getModel().getSize() > 0
                    && sessions.getSelectedIndex() >= 0;
            });
            if (populated) return;
            Thread.sleep(250L);
        }
        throw new IllegalStateException("fx active-session sidebar was not populated");
    }

    private static void approvePermissionDialog() throws Exception {
        final Window dialog = awaitNamedWindow(
            "turboism-with-fx.permission.allow-once",
            PROMPT_TIMEOUT
        );
        onEdt(() -> {
            if (dialog.getHeight() < 360 || dialog.getWidth() < 620) {
                throw new IllegalStateException("fx permission dialog is smaller than its contract");
            }
            named(
                dialog,
                JButton.class,
                "turboism-with-fx.permission.allow-once"
            ).doClick(0);
            return null;
        });
    }

    private void awaitRename(final String id, final String expected) throws Exception {
        for (int attempt = 0; attempt < 240 && !Thread.currentThread().isInterrupted(); attempt++) {
            final ModelObjectDescriptor found = context.modelObjects().list().stream()
                .filter(object -> object.reference().id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("renamed object disappeared"));
            if (expected.equals(found.name())) return;
            Thread.sleep(1000L);
        }
        throw new IllegalStateException("fx did not complete the requested MCP rename");
    }

    private static void awaitStatus(
        final Window window,
        final Predicate<String> accepted,
        final Duration timeout
    ) throws Exception {
        awaitNamedStatus(window, "turboism-with-fx.status", accepted, timeout);
    }

    private static void awaitRuntimeStatus(
        final Window window,
        final Predicate<String> accepted,
        final Duration timeout
    ) throws Exception {
        awaitNamedStatus(window, "turboism-with-fx.runtime-status", accepted, timeout);
    }

    private static void awaitNamedStatus(
        final Window window,
        final String name,
        final Predicate<String> accepted,
        final Duration timeout
    ) throws Exception {
        final long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted()) {
            final String value = onEdt(() -> named(
                window, javax.swing.JLabel.class, name
            ).getText());
            if (accepted.test(value)) return;
            Thread.sleep(250L);
        }
        throw new IllegalStateException("fx window status did not reach the expected state");
    }

    private static String selectedValue(final Window window, final String name) throws Exception {
        return onEdt(() -> {
            final Object value = named(window, JComboBox.class, name).getSelectedItem();
            if (value == null) return "";
            try {
                final java.lang.reflect.Method accessor = value.getClass().getDeclaredMethod("value");
                accessor.setAccessible(true);
                return Objects.toString(accessor.invoke(value), "");
            } catch (ReflectiveOperationException | RuntimeException failure) {
                throw new IllegalStateException("fx option value is unavailable", failure);
            }
        });
    }

    private static void clickNamed(final Window window, final String name) throws Exception {
        onEdt(() -> {
            named(window, AbstractButton.class, name).doClick(0);
            return null;
        });
    }

    private static void setInitialPrompt(final Window window, final String value) throws Exception {
        onEdt(() -> {
            named(window, JTextArea.class, "turboism-with-fx.initial-prompt").setText(value);
            return null;
        });
    }

    private static String readNamedField(final Window window, final String name) throws Exception {
        return onEdt(() -> named(window, JTextField.class, name).getText().strip());
    }

    private static String readNamedTextArea(final Window window, final String name) throws Exception {
        return onEdt(() -> named(window, JTextArea.class, name).getText());
    }

    private static String validationInitialPrompt() {
        return VALIDATION_INITIAL_PROMPT;
    }

    private static boolean startsWithAny(
        final String value,
        final List<String> prefixes
    ) {
        return prefixes.stream().anyMatch(value::startsWith);
    }

    private static boolean isNamedSelected(final Window window, final String name) throws Exception {
        return onEdt(() -> named(window, JCheckBox.class, name).isSelected());
    }

    private static void awaitPersistedSettings() throws Exception {
        final String executable = requireProperty("turboism.validation.fxExecutable");
        final Path configRoot = Path.of(requireProperty("turboism.home"))
            .resolve("config")
            .resolve(FX_PLUGIN_ID);
        final long deadline = System.nanoTime() + UI_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted()) {
            if (Files.isDirectory(configRoot)) {
                try (var files = Files.walk(configRoot)) {
                    final boolean found = files
                        .filter(path -> path.getFileName().toString().equals("settings.properties"))
                        .anyMatch(path -> persistedSettingsMatch(path, executable));
                    if (found) return;
                }
            }
            Thread.sleep(250L);
        }
        throw new IllegalStateException("fx launch settings were not persisted under the plugin config root");
    }

    private static boolean persistedSettingsMatch(final Path path, final String executable) {
        try {
            final java.util.Properties properties = new java.util.Properties();
            try (java.io.Reader reader = Files.newBufferedReader(
                path,
                java.nio.charset.StandardCharsets.UTF_8
            )) {
                properties.load(reader);
            }
            return executable.equals(properties.getProperty("fxExecutable"))
                && "true".equals(properties.getProperty("allowFxNativeTools"))
                && validationInitialPrompt().equals(properties.getProperty("initialPrompt"));
        } catch (java.io.IOException ignored) {
            return false;
        }
    }

    private static void awaitToolbarButton(final String name, final Duration timeout) throws Exception {
        final long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted()) {
            final boolean available = onEdt(() -> findVisibleButton(name) != null);
            if (available) return;
            Thread.sleep(500L);
        }
        throw new IllegalStateException("host toolbar button did not become available: " + name);
    }

    private static AbstractButton findVisibleButton(final String name) {
        for (Window window : Window.getWindows()) {
            if (!window.isShowing()) continue;
            final AbstractButton found = descendants(window, AbstractButton.class).stream()
                .filter(button -> name.equals(button.getName()))
                .filter(AbstractButton::isEnabled)
                .findFirst()
                .orElse(null);
            if (found != null) return found;
        }
        return null;
    }

    private static void openToolbarButton(final String name) throws Exception {
        final long deadline = System.nanoTime() + UI_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted()) {
            final boolean opened = onEdt(() -> {
                final AbstractButton found = findVisibleButton(name);
                if (found == null) return false;
                found.doClick(0);
                return true;
            });
            if (opened) return;
            Thread.sleep(250L);
        }
        throw new IllegalStateException("host toolbar button is unavailable: " + name);
    }

    private static Window awaitNamedWindow(final String componentName, final Duration timeout)
        throws Exception {
        final long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted()) {
            final Window found = onEdt(() -> findShowingWindow(componentName));
            if (found != null) return found;
            Thread.sleep(250L);
        }
        throw new IllegalStateException("window did not appear with component: " + componentName);
    }

    private static void awaitNoNamedWindow(final String componentName, final Duration timeout)
        throws Exception {
        final long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            final boolean absent = onEdt(() -> findShowingWindow(componentName) == null);
            if (absent) return;
            Thread.sleep(250L);
        }
        throw new IllegalStateException("window remained visible with component: " + componentName);
    }

    private static Window findShowingWindow(final String componentName) {
        for (Window window : Window.getWindows()) {
            if (window.isShowing() && hasNamedComponent(window, componentName)) return window;
        }
        return null;
    }

    private static boolean hasNamedComponent(final Container root, final String componentName) {
        return descendants(root, JComponent.class).stream()
            .anyMatch(component -> componentName.equals(component.getName()));
    }

    private static String title(final Window window) {
        if (window instanceof java.awt.Dialog dialog) return dialog.getTitle();
        if (window instanceof Frame frame) return frame.getTitle();
        return "";
    }

    private static void hide(final Window window) throws Exception {
        onEdt(() -> {
            window.setVisible(false);
            return null;
        });
    }

    private static void dispose(final Window window) throws Exception {
        onEdt(() -> {
            window.dispose();
            return null;
        });
    }

    private static void closeHost() throws Exception {
        final Window host = onEdt(() -> selectHostWindow(Window.getWindows()));
        final HostCloseRoute route = hostCloseRoute(
            requireProperty("turboism.validation.hostVersion")
        );
        if (route == HostCloseRoute.ROBOT_ALT_F4) {
            onEdt(() -> {
                host.toFront();
                host.requestFocus();
                return null;
            });
            Thread.sleep(800L);
            final java.awt.Robot robot = new java.awt.Robot();
            robot.keyPress(java.awt.event.KeyEvent.VK_ALT);
            try {
                robot.keyPress(java.awt.event.KeyEvent.VK_F4);
            } finally {
                robot.keyRelease(java.awt.event.KeyEvent.VK_F4);
                robot.keyRelease(java.awt.event.KeyEvent.VK_ALT);
            }
        } else {
            // Cubism 5.2 handles WINDOW_CLOSING synchronously and can enter a modal save
            // dialog before dispatchEvent returns. Queue the event without awaiting
            // that dispatch, then drive the dialog through its nested EDT event loop.
            SwingUtilities.invokeLater(() -> host.dispatchEvent(
                new WindowEvent(host, WindowEvent.WINDOW_CLOSING)
            ));
        }
        final long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted()) {
            final boolean settling = onEdt(() -> {
                if (!host.isShowing()) return true;
                for (Window window : Window.getWindows()) {
                    if (!window.isShowing() || !(window instanceof javax.swing.JDialog)) continue;
                    final JButton discard = descendants(window, JButton.class).stream()
                        .filter(button -> isDiscardButton(button.getText()))
                        .findFirst()
                        .orElse(null);
                    if (discard != null) {
                        discard.doClick(0);
                        return true;
                    }
                }
                return false;
            });
            if (settling) return;
            Thread.sleep(250L);
        }
        throw new IllegalStateException("Cubism shutdown did not begin");
    }

    enum HostCloseRoute {
        SYNTHETIC_WINDOW_CLOSING,
        ROBOT_ALT_F4
    }

    static HostCloseRoute hostCloseRoute(final String hostVersion) {
        if (hostVersion == null) {
            throw new IllegalArgumentException(
                "turboism.validation.hostVersion must be 5203 or 5302"
            );
        }
        return switch (hostVersion) {
            case "5203" -> HostCloseRoute.SYNTHETIC_WINDOW_CLOSING;
            case "5302" -> HostCloseRoute.ROBOT_ALT_F4;
            default -> throw new IllegalArgumentException(
                "turboism.validation.hostVersion must be 5203 or 5302: " + hostVersion
            );
        };
    }

    static Window selectHostWindow(final Window[] windows) {
        Window target = null;
        long largestArea = -1L;
        for (Window window : windows) {
            if (window instanceof java.awt.Dialog
                || !window.isDisplayable()
                || !window.isVisible()) {
                continue;
            }
            final long area = (long) window.getWidth() * window.getHeight();
            if (area > largestArea) {
                target = window;
                largestArea = area;
            }
        }
        if (target == null) {
            throw new IllegalStateException(
                "No visible, displayable non-dialog host window found."
            );
        }
        return target;
    }

    private static boolean isDiscardButton(final String text) {
        final String value = Objects.toString(text, "")
            .replace("&", "")
            .strip()
            .toLowerCase(java.util.Locale.ROOT);
        return List.of(
            "no", "don't save", "discard",
            "否", "不保存", "不儲存", "放弃", "放棄",
            "いいえ", "保存しない", "破棄",
            "아니요", "저장 안 함", "버리기"
        ).stream().anyMatch(value::startsWith);
    }

    private static <T extends Component> T named(
        final Container root,
        final Class<T> type,
        final String name
    ) {
        return descendants(root, type).stream()
            .filter(component -> component instanceof JComponent swing && name.equals(swing.getName()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("named component is unavailable: " + name));
    }

    private static <T extends Component> List<T> descendants(
        final Container root,
        final Class<T> type
    ) {
        final ArrayList<T> found = new ArrayList<>();
        collect(root, type, found);
        return found;
    }

    private static <T extends Component> void collect(
        final Component component,
        final Class<T> type,
        final List<T> found
    ) {
        if (type.isInstance(component)) found.add(type.cast(component));
        if (component instanceof JMenu menu) {
            for (Component child : menu.getMenuComponents()) collect(child, type, found);
        } else if (component instanceof Container container) {
            for (Component child : container.getComponents()) collect(child, type, found);
        }
        if (component instanceof JScrollPane pane && pane.getViewport().getView() != null) {
            collect(pane.getViewport().getView(), type, found);
        }
    }

    private static <T> T onEdt(final Callable<T> call) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return call.call();
        final AtomicReference<T> result = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final CountDownLatch completed = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            try {
                result.set(call.call());
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                completed.countDown();
            }
        });
        if (!completed.await(120, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Cubism EDT did not complete fx validation work");
        }
        final Throwable throwable = failure.get();
        if (throwable instanceof Exception exception) throw exception;
        if (throwable instanceof Error error) throw error;
        return result.get();
    }

    private static void writeResult(
        final Path result,
        final boolean passed,
        final String targetKind,
        final String targetId,
        final String targetName,
        final String provider,
        final String model,
        final long durationMillis,
        final String failure
    ) throws Exception {
        Files.createDirectories(result.getParent());
        Files.writeString(
            result,
            "schemaVersion=1\n"
                + "runId=" + requireProperty("turboism.validation.runId") + "\n"
                + "targetKind=" + propertyValue(targetKind) + "\n"
                + "targetId=" + propertyValue(targetId) + "\n"
                + "targetName=" + propertyValue(targetName) + "\n"
                + "provider=" + propertyValue(provider) + "\n"
                + "model=" + propertyValue(model) + "\n"
                + "permission=ALLOW_ONCE\n"
                + "sessionSidebar=POPULATED\n"
                + "mutation=RENAMED\n"
                + "cancellation=EXERCISED\n"
                + "persistence=FILE_AND_REOPEN_VERIFIED\n"
                + "durationMillis=" + durationMillis + "\n"
                + "failure=" + propertyValue(failure) + "\n"
                + "status=" + (passed ? "PASS" : "FAIL") + "\n",
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING
        );
    }

    private static String propertyValue(final String value) {
        return Objects.toString(value, "").replaceAll("[\\r\\n=]", "_");
    }

    private static long elapsedMillis(final long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }

    private static String requireProperty(final String name) {
        final String value = System.getProperty(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be set");
        return value;
    }
}
