package dev.turboism.validation.settingspage;

import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.TurboismPlugin;

import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;

import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.awt.Window;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Task-local exerciser for the Turboism Settings page on an exact 5.3.03 host.
 *
 * <p>It drives the real Swing surface only — Turboism menu, Settings item, Performance tab and the
 * mesh-triangulation toggle — and records the persisted {@code config.json} value after an Apply and
 * after a reopen. It never imports or reflects {@code com.live2d.*} types, never mutates the model,
 * and restores the toggle to its entry state before finishing. The terminal verdict is written to
 * {@code state/dev.turboism.validation.settingspage/settings-result.txt}; the process then exits so
 * the Runner's result protocol owns the lifecycle.</p>
 */
public final class SettingsPageProbePlugin implements TurboismPlugin {

    private static final String RESULT_FILE = "settings-result.txt";
    private static final String PREF_KEY = "meshTriangulationHashFix";
    private static final String REVIEWED_VERSION = "5.3.03";

    private static final long MENU_TIMEOUT_MILLIS = 300_000L;
    private static final long DIALOG_TIMEOUT_MILLIS = 60_000L;
    private static final long MODAL_TIMEOUT_MILLIS = 30_000L;
    private static final long POLL_MILLIS = 500L;
    private static final long EDT_TIMEOUT_MILLIS = 30_000L;
    private static final long SETTLE_MILLIS = 1_000L;

    /** {@code common.turboism}: identical in every shipped catalog. */
    private static final String MENU_ROOT_LABEL = "Turboism";
    /** {@code main-toolbar.settings-menu.label} across all shipped catalogs. */
    private static final Set<String> SETTINGS_ITEM_LABELS =
        Set.of("Settings", "設定", "설정", "设置");
    /** {@code window.settings.title} across all shipped catalogs. */
    private static final Set<String> SETTINGS_TITLES = Set.of(
        "Turboism Settings", "Turboism 設定", "Turboism 설정", "Turboism 设置");
    /** {@code settings.tab.performance} across all shipped catalogs. */
    private static final Set<String> PERFORMANCE_TAB_TITLES =
        Set.of("Performance", "パフォーマンス", "성능", "性能", "效能");
    /** {@code settings.mesh-triangulation.hash-degeneracy} across all shipped catalogs. */
    private static final Set<String> MESH_TOGGLE_LABELS = Set.of(
        "Fix mesh triangulation hash degeneracy",
        "メッシュ三角分割のハッシュ縮退を修正",
        "メ시 삼각분할 해시 퇴화 수정",
        "修复网格三角化的哈希退化",
        "修復網格三角化的雜湊退化");
    /** {@code common.apply} across all shipped catalogs. */
    private static final Set<String> APPLY_LABELS =
        Set.of("Apply", "適用", "적용", "应用", "套用");
    /** {@code common.ok} across all shipped catalogs. */
    private static final Set<String> OK_LABELS =
        Set.of("OK", "確認", "확인", "确定", "確定");
    /** {@code common.cancel} across all shipped catalogs. */
    private static final Set<String> CANCEL_LABELS =
        Set.of("Cancel", "キャンセル", "취소", "取消");

    private PluginLogger logger;
    private Path stateDir;
    private Path turboismHome;
    private final List<String> steps = new ArrayList<>();
    private final List<String> failures = new ArrayList<>();
    private final Map<String, String> evidence = new LinkedHashMap<>();

    @Override
    public void init(final PluginContext context) {
        this.logger = context.logger();
        this.stateDir = context.paths().stateDir();
        this.turboismHome = stateDir.getParent().getParent();
        final Thread probe = new Thread(this::runProbe, "settings-page-probe");
        probe.setDaemon(true);
        probe.start();
    }

    @Override
    public void enable() {
        logger.info("SETTINGS_PAGE_PROBE_ENABLED");
    }

    @Override
    public void shutdown() {
        logger.info("SETTINGS_PAGE_PROBE_SHUTDOWN");
    }

    private void runProbe() {
        boolean pass = false;
        try {
            // Readiness = the Turboism > Settings menu item rendered and enabled.
            // The preview runtime report is deliberately NOT a gate: a known main
            // regression (strict preview-report validator rejects
            // localeSource=STARTUP) can leave the report unwritten until shutdown.
            // Host identity is already pinned by the runner's exact-JAR gate.
            evidence.put("hostVersion", hostVersionLabel());
            final JMenuItem settingsItem = awaitSettingsItem();
            step("readiness", settingsItem != null ? "menu-ready" : "timeout");
            if (settingsItem == null) {
                failures.add("Turboism settings menu item was not found within "
                    + MENU_TIMEOUT_MILLIS + "ms");
                return;
            }
            runScenario(settingsItem);
            pass = failures.isEmpty();
        } catch (Throwable failure) {
            failures.add("probe aborted: " + failure.getClass().getSimpleName()
                + " " + Objects.toString(failure.getMessage(), ""));
        } finally {
            if (!failures.isEmpty()) pass = false;
            writeResult(pass);
            logResult(pass);
            try {
                Thread.sleep(SETTLE_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            Runtime.getRuntime().exit(pass ? 0 : 2);
        }
    }

    private void runScenario(final JMenuItem settingsItem) throws Exception {
        // Phase 1: open the real settings dialog and identify the Performance tab control.
        postEdt(settingsItem::doClick);
        final JDialog dialog = awaitSettingsDialog();
        if (dialog == null) {
            failures.add("settings dialog did not appear after clicking the menu item");
            return;
        }
        evidence.put("settingsDialogTitle", safeTitle(dialog));
        final TargetControl first = locateToggle(dialog);
        if (first == null) {
            dumpTabDetails(dialog);
            failures.add("mesh triangulation toggle was not found in the Performance tab");
            closeDialog(dialog);
            return;
        }
        evidence.put("performanceTabIndex", Integer.toString(first.tabIndex));
        evidence.put("performanceTabTitle", first.tabTitle);
        evidence.put("toggleLabel", first.checkbox.getText());
        evidence.put("performanceTabCheckboxes", first.siblingSummary);

        final Boolean persistedAtStart = persistedValue();
        final boolean expectedInitial = persistedAtStart == null || persistedAtStart;
        evidence.put("persistedAtStart",
            persistedAtStart == null ? "absent" : persistedAtStart.toString());
        final JCheckBox box = first.checkbox;
        final boolean initial = onEdtSettled(box::isSelected);
        evidence.put("initialSelected", Boolean.toString(initial));
        check(initial == expectedInitial,
            "initial checkbox state " + initial + " did not match persisted " + expectedInitial);

        // Phase 2: toggle the preference and persist through the real Apply path.
        final boolean proposed = !initial;
        onEdt(() -> {
            box.doClick();
            return null;
        });
        final boolean afterClick = onEdtSettled(box::isSelected);
        check(afterClick == proposed, "checkbox click did not flip the toggle");
        evidence.put("applyButtonFound", Boolean.toString(hasButton(dialog, APPLY_LABELS)));
        postEdt(() -> clickButton(dialog, APPLY_LABELS));
        final String applyMessage = awaitModalMessage();
        evidence.put("applyDialogMessage", applyMessage);
        final Boolean persistedAfterApply = persistedValue();
        evidence.put("persistedAfterApply",
            persistedAfterApply == null ? "absent" : persistedAfterApply.toString());
        check(persistedAfterApply != null && persistedAfterApply == proposed,
            "Apply did not persist " + proposed + " (observed "
                + (persistedAfterApply == null ? "absent" : persistedAfterApply) + ")");

        // Phase 3: cancel the open dialog, reopen through the menu, and verify the read-back.
        onEdt(() -> clickButton(dialog, CANCEL_LABELS));
        awaitHidden(dialog);
        final JMenuItem settingsItemAgain = awaitSettingsItem();
        if (settingsItemAgain == null) {
            failures.add("settings menu item missing when reopening");
            return;
        }
        postEdt(settingsItemAgain::doClick);
        final JDialog reopened = awaitSettingsDialog();
        if (reopened == null) {
            failures.add("settings dialog did not reappear");
            return;
        }
        final TargetControl second = locateToggle(reopened);
        if (second == null) {
            failures.add("mesh triangulation toggle missing after reopen");
            closeDialog(reopened);
            return;
        }
        final JCheckBox restoreBox = second.checkbox;
        final boolean readBack = onEdtSettled(restoreBox::isSelected);
        evidence.put("reopenedSelected", Boolean.toString(readBack));
        check(readBack == proposed,
            "reopened checkbox state " + readBack + " did not reflect persisted " + proposed);

        // Phase 4: restore the entry value and persist it through the real OK path.
        onEdt(() -> {
            restoreBox.doClick();
            return null;
        });
        evidence.put("okButtonFound", Boolean.toString(hasButton(reopened, OK_LABELS)));
        postEdt(() -> clickButton(reopened, OK_LABELS));
        final String okMessage = awaitModalMessage();
        evidence.put("okDialogMessage", okMessage);
        awaitHidden(reopened);
        final Boolean persistedAtEnd = persistedValue();
        evidence.put("persistedAtEnd",
            persistedAtEnd == null ? "absent" : persistedAtEnd.toString());
        check(persistedAtEnd == null || persistedAtEnd == expectedInitial,
            "restored save did not persist " + expectedInitial + " (observed "
                + (persistedAtEnd == null ? "absent" : persistedAtEnd) + ")");
        step("scenario", "complete");
    }

    // ------------------------------------------------------------------ readiness

    /**
     * Host version label for evidence: the runner-pinned validation version (the
     * same value the exact JAR identity gate was checked against). Recorded for
     * the result file only; never a gate.
     */
    private String hostVersionLabel() {
        return System.getProperty("turboism.validation.hostVersion", REVIEWED_VERSION);
    }

    // ------------------------------------------------------------------ Swing driving

    private JMenuItem awaitSettingsItem() throws Exception {
        final long deadline = System.currentTimeMillis() + MENU_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            final JMenuItem item;
            try {
                item = onEdt(this::findSettingsItem);
            } catch (EdtBusy busy) {
                Thread.sleep(POLL_MILLIS);
                continue;
            }
            if (item != null && item.isEnabled()) return item;
            Thread.sleep(POLL_MILLIS);
        }
        return null;
    }

    /** Finds the Settings item under the Turboism root menu, or anywhere as a fallback. */
    private JMenuItem findSettingsItem() {
        JMenuItem fallback = null;
        for (final Frame frame : Frame.getFrames()) {
            if (!(frame instanceof javax.swing.JFrame swingFrame) || !frame.isVisible()) continue;
            final JMenuBar bar = swingFrame.getJMenuBar();
            if (bar == null) continue;
            for (int index = 0; index < bar.getMenuCount(); index++) {
                final JMenu menu = bar.getMenu(index);
                if (menu == null) continue;
                final JMenuItem found = findItem(menu, SETTINGS_ITEM_LABELS);
                if (found != null && MENU_ROOT_LABEL.equals(menu.getText())) return found;
                if (fallback == null) fallback = found;
            }
        }
        return fallback;
    }

    private static JMenuItem findItem(final JMenu menu, final Set<String> labels) {
        for (int index = 0; index < menu.getMenuComponentCount(); index++) {
            final Component component = menu.getMenuComponent(index);
            if (component instanceof JMenu nested) {
                final JMenuItem found = findItem(nested, labels);
                if (found != null) return found;
            } else if (component instanceof JMenuItem item
                && item.getText() != null && labels.contains(item.getText())) {
                return item;
            }
        }
        return null;
    }

    private JDialog awaitSettingsDialog() throws Exception {
        final long deadline = System.currentTimeMillis() + DIALOG_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            final JDialog dialog;
            try {
                dialog = onEdt(SettingsPageProbePlugin::findSettingsDialog);
            } catch (EdtBusy busy) {
                Thread.sleep(POLL_MILLIS);
                continue;
            }
            if (dialog != null) return dialog;
            Thread.sleep(POLL_MILLIS);
        }
        return null;
    }

    private static JDialog findSettingsDialog() {
        JDialog fallback = null;
        for (final Window window : Window.getWindows()) {
            if (!(window instanceof JDialog dialog) || !dialog.isShowing()) continue;
            if (dialog.getTitle() != null && SETTINGS_TITLES.contains(dialog.getTitle())) {
                return dialog;
            }
            if (fallback == null && findTabbedPane(dialog) != null) fallback = dialog;
        }
        return fallback;
    }

    /**
     * Selects the Performance tab and returns the mesh toggle plus a summary of every sibling
     * checkbox, so the evidence records the control in its real tab context.
     */
    private TargetControl locateToggle(final JDialog dialog) throws Exception {
        return onEdtSettled(() -> {
            final JTabbedPane tabs = findTabbedPane(dialog);
            if (tabs == null) return null;
            for (int index = 0; index < tabs.getTabCount(); index++) {
                final String title = tabs.getTitleAt(index);
                if (title == null || !PERFORMANCE_TAB_TITLES.contains(title)) continue;
                tabs.setSelectedIndex(index);
                final Component page = tabs.getComponentAt(index);
                if (!(page instanceof Container container)) continue;
                final List<JCheckBox> boxes = new ArrayList<>();
                collect(container, JCheckBox.class, boxes);
                final StringBuilder summary = new StringBuilder();
                for (final JCheckBox box : boxes) {
                    if (summary.length() > 0) summary.append('|');
                    summary.append(Objects.toString(box.getText(), "?"))
                        .append('=').append(box.isSelected());
                }
                for (final JCheckBox box : boxes) {
                    if (box.getText() != null && MESH_TOGGLE_LABELS.contains(box.getText())) {
                        return new TargetControl(index, title, box, summary.toString());
                    }
                }
            }
            return null;
        });
    }

    private void dumpTabDetails(final JDialog dialog) throws Exception {
        final String detail = onEdtSettled(() -> {
            final JTabbedPane tabs = findTabbedPane(dialog);
            if (tabs == null) return "no-tabbed-pane";
            final StringBuilder out = new StringBuilder();
            for (int index = 0; index < tabs.getTabCount(); index++) {
                if (out.length() > 0) out.append('|');
                out.append(tabs.getTitleAt(index));
            }
            return "tabs=" + out;
        });
        evidence.put("dialogContents", detail);
    }

    private void closeDialog(final JDialog dialog) {
        try {
            onEdt(() -> {
                clickButton(dialog, CANCEL_LABELS);
                if (dialog.isShowing()) dialog.setVisible(false);
                return null;
            });
        } catch (Exception ignored) {
            // Best-effort cleanup; the process exits after the result file anyway.
        }
    }

    private static JTabbedPane findTabbedPane(final Container root) {
        final List<JTabbedPane> found = new ArrayList<>();
        collect(root, JTabbedPane.class, found);
        return found.isEmpty() ? null : found.get(0);
    }

    private static <T extends Component> void collect(
        final Container root, final Class<T> type, final List<T> out
    ) {
        for (final Component component : root.getComponents()) {
            if (type.isInstance(component)) out.add(type.cast(component));
            if (component instanceof Container nested) collect(nested, type, out);
        }
    }

    private boolean hasButton(final Container root, final Set<String> labels) throws Exception {
        final Boolean found = onEdtSettled(() -> {
            final List<AbstractButton> buttons = new ArrayList<>();
            collect(root, AbstractButton.class, buttons);
            for (final AbstractButton button : buttons) {
                if (button.getText() != null && labels.contains(button.getText())) return true;
            }
            return false;
        });
        return Boolean.TRUE.equals(found);
    }

    private static boolean clickButton(final Container root, final Set<String> labels) {
        final List<AbstractButton> buttons = new ArrayList<>();
        collect(root, AbstractButton.class, buttons);
        for (final AbstractButton button : buttons) {
            if (button.getText() != null && labels.contains(button.getText())
                && button.isEnabled() && button.isShowing()) {
                button.doClick();
                return true;
            }
        }
        return false;
    }

    /**
     * Waits for the modal save/error message that Apply/OK raise, reads the JOptionPane text, and
     * clicks its button so the blocked EDT action can finish. Returns the observed message, or a
     * timeout marker.
     */
    private String awaitModalMessage() throws Exception {
        final long deadline = System.currentTimeMillis() + MODAL_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            final AtomicReference<JDialog> modal = new AtomicReference<>();
            final AtomicReference<String> message = new AtomicReference<>();
            try {
                onEdt(() -> {
                    for (final Window window : Window.getWindows()) {
                        if (!(window instanceof JDialog dialog) || !dialog.isShowing()) continue;
                        final List<JOptionPane> panes = new ArrayList<>();
                        collect(dialog, JOptionPane.class, panes);
                        if (panes.isEmpty()) continue;
                        modal.set(dialog);
                        message.set(Objects.toString(panes.get(0).getMessage(), ""));
                        return null;
                    }
                    return null;
                });
            } catch (EdtBusy busy) {
                Thread.sleep(POLL_MILLIS);
                continue;
            }
            if (modal.get() != null) {
                final JDialog dialog = modal.get();
                postEdt(() -> {
                    final List<JButton> buttons = new ArrayList<>();
                    collect(dialog, JButton.class, buttons);
                    for (final JButton button : buttons) {
                        if (button.isEnabled() && button.isShowing()) {
                            button.doClick();
                            break;
                        }
                    }
                });
                awaitHidden(dialog);
                return message.get();
            }
            Thread.sleep(POLL_MILLIS);
        }
        return "modal-timeout";
    }

    private void awaitHidden(final Window window) throws Exception {
        final long deadline = System.currentTimeMillis() + DIALOG_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            final Boolean showing;
            try {
                showing = onEdt(window::isShowing);
            } catch (EdtBusy busy) {
                Thread.sleep(POLL_MILLIS);
                continue;
            }
            if (showing == null || !showing) return;
            Thread.sleep(POLL_MILLIS);
        }
        failures.add("window did not hide: " + safeTitle(window));
    }

    private static String safeTitle(final Window window) {
        return window instanceof JDialog dialog
            ? Objects.toString(dialog.getTitle(), "") : window.getClass().getSimpleName();
    }

    // ------------------------------------------------------------------ persisted state

    /**
     * Reads the preference from the task home's {@code config.json} as the host sees it.
     *
     * @return {@code Boolean.TRUE}/{@code FALSE} for an explicit value, {@code null} when the key
     *     is absent or the file cannot be parsed as containing it
     */
    private Boolean persistedValue() {
        final Path config = turboismHome.resolve("config.json");
        try {
            final String json = Files.readString(config, StandardCharsets.UTF_8);
            final java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"" + PREF_KEY + "\"\\s*:\\s*(true|false)")
                .matcher(json);
            if (!matcher.find()) return null;
            return Boolean.valueOf(matcher.group(1));
        } catch (java.io.IOException | RuntimeException unreadable) {
            return null;
        }
    }

    // ------------------------------------------------------------------ reporting

    private void check(final boolean condition, final String failure) {
        if (!condition) failures.add(failure);
    }

    private void step(final String name, final String detail) {
        steps.add(name + "=" + detail);
        logger.info("SETTINGS_PAGE_PROBE_STEP " + name + " " + detail);
    }

    private void writeResult(final boolean pass) {
        final StringBuilder body = new StringBuilder();
        body.append("schemaVersion=1\n");
        body.append("plugin=dev.turboism.validation.settingspage\n");
        body.append("reviewedHostVersion=").append(REVIEWED_VERSION).append('\n');
        for (final Map.Entry<String, String> entry : evidence.entrySet()) {
            body.append(entry.getKey()).append('=')
                .append(entry.getValue().replace('\n', ' ')).append('\n');
        }
        body.append("steps=").append(String.join(",", steps)).append('\n');
        body.append("failures=").append(Integer.toString(failures.size())).append('\n');
        for (int index = 0; index < failures.size(); index++) {
            body.append("failure.").append(index).append('=')
                .append(failures.get(index).replace('\n', ' ')).append('\n');
        }
        body.append("status=").append(pass ? "PASS" : "FAIL").append('\n');
        try {
            Files.createDirectories(stateDir);
            Files.writeString(stateDir.resolve(RESULT_FILE), body.toString(), StandardCharsets.UTF_8);
        } catch (java.io.IOException failure) {
            logger.error("SETTINGS_PAGE_PROBE_RESULT_WRITE_FAILED " + failure.getMessage());
        }
    }

    private void logResult(final boolean pass) {
        logger.info("SETTINGS_PAGE_PROBE_RESULT status=" + (pass ? "PASS" : "FAIL")
            + " failures=" + failures.size());
        for (final String failure : failures) {
            logger.warn("SETTINGS_PAGE_PROBE_FAILURE " + failure);
        }
    }

    // ------------------------------------------------------------------ EDT bridge

    private interface EdtCall<T> {
        T run();
    }

    /**
     * The EDT stayed busy past the per-call bound. Poll loops treat this as "not ready yet" and
     * keep waiting inside their own deadline; action calls let it abort the probe.
     */
    private static final class EdtBusy extends RuntimeException {
        EdtBusy(final String message) {
            super(message);
        }
    }

    /**
     * Read-only EDT call that tolerates a busy event queue: retries inside a bounded attempt count.
     * Never use this for mutations — a timed-out runnable may still execute later, so retrying a
     * click could apply it twice.
     */
    private static <T> T onEdtSettled(final EdtCall<T> operation) throws Exception {
        for (int attempt = 0; ; attempt++) {
            try {
                return onEdt(operation);
            } catch (EdtBusy busy) {
                if (attempt >= 9) throw busy;
                Thread.sleep(POLL_MILLIS);
            }
        }
    }

    private static <T> T onEdt(final EdtCall<T> operation) throws Exception {
        Objects.requireNonNull(operation, "operation");
        if (SwingUtilities.isEventDispatchThread()) return operation.run();
        final CountDownLatch completed = new CountDownLatch(1);
        final AtomicReference<T> value = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeLater(() -> {
            try {
                value.set(operation.run());
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                completed.countDown();
            }
        });
        try {
            if (!completed.await(EDT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                throw new EdtBusy("EDT operation timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        }
        if (failure.get() != null) throw new InvocationTargetException(failure.get());
        return value.get();
    }

    /** Fire-and-forget EDT post for actions that may open a nested modal loop. */
    private static void postEdt(final Runnable operation) {
        SwingUtilities.invokeLater(operation);
    }

    private record TargetControl(int tabIndex, String tabTitle, JCheckBox checkbox,
                                 String siblingSummary) {
    }
}
