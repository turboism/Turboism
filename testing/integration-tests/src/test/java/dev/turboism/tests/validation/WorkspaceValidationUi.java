package dev.turboism.tests.validation;

import dev.turboism.sdk.ui.workspace.WorkspaceInfo;
import dev.turboism.sdk.ui.workspace.WorkspaceStatus;
import dev.turboism.ui.workspace.WorkspaceHostProvider;

import javax.swing.AbstractButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

/** Native UI setup confined to the disposable validation JVM; no Cubism reflection. */
final class WorkspaceValidationUi {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private WorkspaceValidationUi() { }

    static void perturbLayout(final Path state) throws Exception {
        final AbstractButton palette = await(() -> {
            final List<Component> toggles = allWindows().stream()
                .filter(component -> component instanceof javax.swing.JCheckBoxMenuItem).toList();
            return button(toggles, "参数", "Parameter", "Parameters");
        });
        final boolean selected = onEdt(palette::isSelected);
        onEdt(() -> { palette.doClick(0); return null; });
        await(() -> palette.isSelected() != selected ? Boolean.TRUE : null);
        append(state.resolve("workspace-native-ui.txt"), "parameterPaletteToggled=" + !selected);
    }

    static void prepare(final WorkspaceHostProvider provider, final Path state) throws Exception {
        try {
            prepareWorkspace(provider, state);
        } catch (Exception failure) {
            append(state.resolve("workspace-native-ui.txt"), "failure=" + failure);
            append(state.resolve("workspace-native-ui.txt"), onEdt(WorkspaceValidationUi::describeWindows));
            append(state.resolve("workspace-native-ui.txt"), "workspaceStatus=" + onEdt(provider::readStatus));
            throw failure;
        }
    }

    private static void prepareWorkspace(final WorkspaceHostProvider provider, final Path state) throws Exception {
        final Path evidence = state.resolve("workspace-native-ui.txt");
        Files.createDirectories(state);
        final WorkspaceStatus before = onEdt(provider::readStatus);
        final var original = before.current().orElseThrow().id();
        append(evidence, "original=" + before);
        final String name = "Turboism-validation-" + System.getProperty("turboism.validation.runId", "task");
        final AbstractButton settings = await(() -> button(allWindows(), "工作区设置", "Workspace settings", "Workspace Settings"));
        click(settings);
        final JDialog dialog = await(() -> showingDialog("工作区设置", "Workspace settings", "Workspace Settings"));
        append(evidence, "settings=" + onEdt(() -> describe(components(dialog))));
        final AbstractButton add = await(() -> button(components(dialog), "追加", "添加", "新增", "新建", "Add", "New", "+"));
        click(add);
        append(evidence, "afterAdd=" + onEdt(() -> describe(components(dialog))));
        final JTextField field = await(() -> {
            final List<JTextField> fields = components(dialog).stream()
                .filter(component -> component.isShowing() && component instanceof JTextField)
                .map(component -> (JTextField) component).filter(JTextField::isEditable).toList();
            return fields.size() == 1 ? fields.get(0) : null;
        });
        onEdt(() -> { field.setText(name); field.postActionEvent(); return null; });
        append(evidence, "named=" + onEdt(() -> describe(components(dialog))));
        click(await(() -> button(components(dialog), "OK", "确定", "确认")));
        await(() -> !dialog.isShowing() ? Boolean.TRUE : null);
        final WorkspaceInfo custom = await(() -> provider.readStatus().available().stream()
            .filter(info -> info.displayName().equals(name))
            .filter(info -> before.available().stream().noneMatch(old -> old.id().equals(info.id())))
            .findFirst().orElse(null));
        append(evidence, "restoreOutcome=" + onEdt(() -> provider.switchTo(original)));
        append(evidence, "afterRestore=" + onEdt(provider::readStatus));
        await(() -> provider.readStatus().current().filter(info -> info.id().equals(original))
            .map(info -> Boolean.TRUE).orElse(null));
        System.setProperty("turboism.workspaceValidation.customId", custom.id().value());
        System.setProperty("turboism.workspaceValidation.customName", custom.displayName());
        append(evidence, "customId=" + custom.id().value() + "\ncustomName=" + name);
        onEdt(() -> {
            final java.util.Set<Window> observed = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<>()
            );
            final Timer confirmations = new Timer(100, ignored -> {
                if (java.util.Arrays.stream(Window.getWindows()).noneMatch(Window::isShowing)) {
                    ((Timer) ignored.getSource()).stop();
                    return;
                }
                for (Window window : Window.getWindows()) {
                    if (!(window instanceof JDialog) || !window.isShowing()) continue;
                    final List<Component> contents = components(window);
                    final String text = describe(contents);
                    if (observed.add(window)) append(evidence, "nativeDialog=" + text);
                    if (!text.contains(name) || !(text.contains("初始布局") || text.contains("default layout"))) continue;
                    final AbstractButton yes = button(contents, "是", "是(Y)", "Yes", "Yes(Y)", "确定", "OK");
                    if (yes != null) {
                        append(evidence, "confirmedDefaultSave=true");
                        yes.doClick(0);
                    }
                }
            });
            confirmations.start();
            return null;
        });
    }

    private static <T> T await(final Callable<T> read) throws Exception {
        final long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            final T value = onEdt(read);
            if (value != null) return value;
            Thread.sleep(100);
        }
        throw new IllegalStateException("Native workspace UI prerequisite timed out: "
            + onEdt(WorkspaceValidationUi::describeWindows));
    }

    private static <T> T onEdt(final Callable<T> read) throws Exception {
        final FutureTask<T> task = new FutureTask<>(read);
        SwingUtilities.invokeLater(task);
        return task.get(35, java.util.concurrent.TimeUnit.SECONDS);
    }

    private static void click(final AbstractButton button) {
        SwingUtilities.invokeLater(() -> button.doClick(0));
    }

    private static JDialog showingDialog(final String... titles) {
        for (Window window : Window.getWindows()) {
            if (window instanceof JDialog dialog && dialog.isShowing()
                && matches(dialog.getTitle(), titles)) return dialog;
        }
        return null;
    }

    private static AbstractButton button(final List<Component> components, final String... labels) {
        final List<AbstractButton> matches = components.stream()
            .filter(component -> component instanceof AbstractButton && component.isEnabled())
            .map(component -> (AbstractButton) component)
            .filter(button -> matches(button.getText(), labels) || matches(button.getToolTipText(), labels))
            .toList();
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private static boolean matches(final String text, final String... labels) {
        if (text == null) return false;
        final String normalized = text.trim().replace("...", "").replace("…", "");
        return java.util.Arrays.stream(labels).anyMatch(normalized::equalsIgnoreCase);
    }

    private static List<Component> allWindows() {
        final List<Component> result = new ArrayList<>();
        for (Window window : Window.getWindows()) {
            if (window.isShowing()) result.addAll(components(window));
        }
        return result;
    }

    private static List<Component> components(final Component root) {
        final List<Component> result = new ArrayList<>();
        collect(root, result);
        return result;
    }

    private static void collect(final Component component, final List<Component> target) {
        if (target.size() >= 2000) throw new IllegalStateException("UI traversal limit exceeded");
        target.add(component);
        if (component instanceof JMenu menu) {
            for (Component child : menu.getMenuComponents()) collect(child, target);
        } else if (component instanceof Container container) {
            for (Component child : container.getComponents()) collect(child, target);
        }
    }

    private static String describe(final List<Component> components) {
        return components.stream().map(component -> {
            if (component instanceof AbstractButton button) return "button[" + button.getText() + "/" + button.getToolTipText() + "]";
            if (component instanceof JLabel label) return "label[" + label.getText() + "]";
            if (component instanceof JTextField field) return "input[" + field.getText() + "]";
            return "";
        }).filter(text -> !text.isEmpty()).limit(180).collect(java.util.stream.Collectors.joining("; "));
    }

    private static String describeWindows() {
        final StringBuilder description = new StringBuilder();
        for (Window window : Window.getWindows()) {
            if (!(window instanceof JDialog) || !window.isShowing()) continue;
            description.append("window[").append(window.getClass().getName()).append("] ")
                .append(describe(components(window))).append('\n');
        }
        return description.toString();
    }

    private static void append(final Path evidence, final String line) {
        try {
            Files.writeString(evidence, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Cannot record workspace native UI evidence", failure);
        }
    }
}
