package dev.turboism.validation.texture;

import java.awt.*;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.swing.*;

/** Task-local validation agent. Never package with production artifacts.
 * Requests are files inside the explicitly supplied evidence directory.
 * dump: enumerate Swing controls; click<TAB>id<TAB>exact label: invoke a captured button.
 * close<TAB>id: dispatch WINDOW_CLOSING to a captured window (never force-dispose).
 */
public final class HostUiProbe {
    private static final Map<Integer, Component> controls = new LinkedHashMap<>();
    static final boolean UI_TIMING = Boolean.getBoolean("turboism.validation.atlas.uiTiming");
    static volatile long actionStart, progressShown, progressClosed;
    private static Window measuredProgress;
    private static java.awt.event.AWTEventListener progressListener;

    private static void armProgress(AbstractButton button) {
        if (!UI_TIMING || actionStart != 0 || progressListener != null || !button.isShowing()
                || !"OK".equals(button.getText())) throw new IllegalStateException("Invalid UI timing arm");
        Window settings = SwingUtilities.getWindowAncestor(button);
        if (settings == null || !settings.getClass().getName().equals(
                "com.live2d.cubism.doc.modeling.ui.atlasEditor.a.f"))
            throw new IllegalStateException("Not the exact auto-layout settings dialog");
        progressListener = event -> {
            if (actionStart == 0 || !(event instanceof java.awt.event.HierarchyEvent h)
                    || (h.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) == 0
                    || !(h.getComponent() instanceof Window window)
                    || !window.getClass().getName().equals("jp.noids.framework.e.a.f")) return;
            long now = System.nanoTime();
            if (window.isShowing()) {
                if (measuredProgress != null) throw new IllegalStateException("Multiple progress windows");
                measuredProgress = window;
                progressShown = now;
            } else if (window == measuredProgress && progressClosed == 0) {
                progressClosed = now;
                Toolkit.getDefaultToolkit().removeAWTEventListener(progressListener);
            }
        };
        Toolkit.getDefaultToolkit().addAWTEventListener(progressListener, AWTEvent.HIERARCHY_EVENT_MASK);
        // Swing dispatches newly added action listeners first. Excludes doClick's press delay.
        button.addActionListener(event -> {
            if (actionStart != 0) throw new IllegalStateException("Repeated layout action");
            actionStart = System.nanoTime();
        });
    }
    public static void premain(String argument, Instrumentation ignored) {
        Path directory = Path.of(argument).toAbsolutePath();
        Thread worker = new Thread(() -> {
            try {
                Files.createDirectories(directory);
                while (true) {
                    Path request = directory.resolve("ui-request.txt");
                    if (Files.isRegularFile(request)) {
                        String command = Files.readString(request, StandardCharsets.UTF_8).strip();
                        Files.delete(request);
                        SwingUtilities.invokeLater(() -> {
                            try {
                                if (command.equals("dump")) {
                                    controls.clear();
                                    StringBuilder result = new StringBuilder();
                                    Set<Component> seen = Collections.newSetFromMap(new IdentityHashMap<>());
                                    for (Window window : Window.getWindows()) if (window.isShowing()) dump(window, 0, result, seen);
                                    Files.writeString(directory.resolve("ui-tree.tmp"), result.toString());
                                    StringBuilder states = new StringBuilder();
                                    controls.forEach((id, control) -> {
                                        if (control instanceof AbstractButton button)
                                            states.append(id).append('\t').append(button.isSelected()).append('\n');
                                    });
                                    Files.writeString(directory.resolve("ui-selected.txt"), states.toString());
                                    Files.move(directory.resolve("ui-tree.tmp"), directory.resolve("ui-tree.txt"),
                                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                                } else {
                                    String[] parts = command.split("\t", 3);
                                    Component component = controls.get(Integer.parseInt(parts[1]));
                                    if (component == null) throw new IllegalArgumentException("Unknown captured control");
                                    if ((parts[0].equals("click") || parts[0].equals("timed-click")) && component instanceof AbstractButton button
                                            && parts.length == 3 && parts[2].equals(button.getText()) && button.isEnabled()) {
                                        if (parts[0].equals("timed-click")) armProgress(button);
                                        button.doClick();
                                    } else if (parts[0].equals("select") && component instanceof JComboBox<?> combo
                                            && parts.length == 3 && combo.isShowing() && combo.isEnabled()) {
                                        int found = -1;
                                        for (int i = 0; i < combo.getItemCount(); i++)
                                            if (parts[2].equals(String.valueOf(combo.getItemAt(i)))) found = i;
                                        if (found < 0) throw new IllegalArgumentException("Unknown combo option");
                                        combo.setSelectedIndex(found);
                                    } else if (parts[0].equals("check") && component instanceof JCheckBox checkbox
                                            && parts.length == 3 && checkbox.isShowing() && checkbox.isEnabled()
                                            && (parts[2].equals("true") || parts[2].equals("false"))) {
                                        boolean target = Boolean.parseBoolean(parts[2]);
                                        if (checkbox.isSelected() != target) checkbox.doClick();
                                        if (checkbox.isSelected() != target) throw new IllegalStateException("Checkbox did not update");
                                    } else if (parts[0].equals("close") && component instanceof Window window) {
                                        window.dispatchEvent(new java.awt.event.WindowEvent(window, java.awt.event.WindowEvent.WINDOW_CLOSING));
                                    } else throw new IllegalArgumentException("Rejected stale or invalid request");
                                }
                                Files.writeString(directory.resolve("ui-result.txt"), "OK " + command);
                            } catch (Exception error) {
                                try { Files.writeString(directory.resolve("ui-result.txt"), "ERROR " + error); }
                                catch (Exception nested) { nested.printStackTrace(); }
                            }
                        });
                    }
                    Thread.sleep(200);
                }
            } catch (Exception failure) { failure.printStackTrace(); }
        }, "texture-validation-ui-probe");
        worker.setDaemon(true);
        worker.start();
    }
    private static void dump(Component component, int depth, StringBuilder output, Set<Component> seen) {
        if (!seen.add(component)) return;
        int id = controls.size(); controls.put(id, component);
        String text = component instanceof AbstractButton b ? b.getText()
                : component instanceof JLabel l ? l.getText()
                : component instanceof Frame f ? f.getTitle()
                : component instanceof Dialog d ? d.getTitle() : "";
        if (component instanceof JComboBox<?> combo) {
            java.util.List<String> options = new ArrayList<>();
            for (int i=0;i<combo.getItemCount();i++) options.add(String.valueOf(combo.getItemAt(i)));
            text = "selected=" + combo.getSelectedItem() + "; options=" + options;
        }
        output.append(id).append('\t').append(" ".repeat(depth)).append(component.getClass().getName())
                .append('\t').append(component.isShowing()).append('/').append(component.isEnabled())
                .append('\t').append(text == null ? "" : text.replace('\n', ' ')).append('\n');
        if (component instanceof JMenu menu) for (Component child : menu.getMenuComponents()) dump(child, depth + 1, output, seen);
        if (component instanceof Container container) for (Component child : container.getComponents()) dump(child, depth + 1, output, seen);
    }
}
