package dev.turboism.tests.plugin;

import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.TurboismPlugin;

import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manual-test-only plugin for the palette tab filter validation.
 *
 * <p>Scans the host Swing tree for the four palette tabs (PARAMETER, DEFORMER,
 * SCENE, LOG), verifies the framework-attached filter box is present, and
 * exercises keyword filtering on the SCENE table rows and LOG lines. Machine
 * readable evidence is appended to the file named by
 * {@code turboism.tabFilterProbe.output}.</p>
 */
public final class TabFilterValidationProbe implements TurboismPlugin {

    private static final String FIELD_NAME = "turboismPaletteFilterField";
    private static final String NO_MATCH_KEYWORD = "zzz-no-match-zzz";

    private PluginContext context;
    private Thread validationThread;

    @Override
    public void init(final PluginContext context) {
        this.context = context;
        context.logger().info("TabFilterValidationProbe initialized");
    }

    @Override
    public void enable() {
        validationThread = new Thread(this::runValidation, "turboism-tab-filter-validation");
        validationThread.setDaemon(true);
        validationThread.start();
    }

    @Override
    public void disable() {
        if (validationThread != null) {
            validationThread.interrupt();
        }
    }

    @Override
    public void shutdown() {
        disable();
    }

    private void runValidation() {
        final Path output = Path.of(System.getProperty("turboism.tabFilterProbe.output", "tab-filter-probe.tsv"));
        final StringBuilder report = new StringBuilder();
        try {
            // Early window-tree dump so evidence survives a short-lived session.
            writeEarly(output, "probe=tab-filter status=early-dump\n" + dumpWindows());
            final long start = System.currentTimeMillis();
            final long deadline = start + 300_000;
            List<PaletteProbe> palettes = List.of();
            while (System.currentTimeMillis() < deadline) {
                palettes = snapshotPalettes();
                if (palettes.size() >= 4 && palettes.stream().allMatch(p -> p.filterBoxFound)) {
                    break;
                }
                Thread.sleep(2_000);
            }
            palettes = snapshotPalettes();

            report.append("probe=tab-filter status=started output=").append(output).append('\n');
            report.append(dumpWindows());
            for (PaletteProbe palette : palettes) {
                report.append(palette.describe());
            }

            final PaletteProbe scene = palette(palettes, "scene");
            if (scene != null && scene.filterField != null) {
                final int rowsBefore = scene.tableRowCount;
                setText(scene.filterField, NO_MATCH_KEYWORD);
                Thread.sleep(2_000);
                final int rowsFiltered = sceneTableRowCount(scene);
                setText(scene.filterField, "");
                Thread.sleep(2_000);
                final int rowsRestored = sceneTableRowCount(scene);
                report.append("scene-filter ")
                    .append("rows-before=").append(rowsBefore)
                    .append(" rows-filtered=").append(rowsFiltered)
                    .append(" rows-restored=").append(rowsRestored)
                    .append('\n');
            }

            final PaletteProbe log = palette(palettes, "log");
            if (log != null && log.filterField != null) {
                final int linesBefore = log.lines;
                setText(log.filterField, NO_MATCH_KEYWORD);
                Thread.sleep(2_000);
                final int linesFiltered = logPaneLines(log);
                setText(log.filterField, "");
                Thread.sleep(2_000);
                final int linesRestored = logPaneLines(log);
                report.append("log-filter ")
                    .append("lines-before=").append(linesBefore)
                    .append(" lines-filtered=").append(linesFiltered)
                    .append(" lines-restored=").append(linesRestored)
                    .append('\n');
            }

            report.append("probe=tab-filter status=completed\n");
        } catch (InterruptedException interrupted) {
            report.append("probe=tab-filter status=interrupted\n");
            Thread.currentThread().interrupt();
        } catch (Throwable throwable) {
            report.append("probe=tab-filter status=failed error=")
                .append(throwable.getClass().getSimpleName())
                .append(':').append(throwable.getMessage()).append('\n');
        } finally {
            try {
                Files.writeString(
                    output,
                    report.toString(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE
                );
            } catch (Exception writeFailure) {
                context.logger().error("tab-filter probe evidence write failed", writeFailure);
            }
            context.logger().info("tab-filter probe finished:\n" + report);
        }
    }

    private static void writeEarly(final Path output, final String content) {
        try {
            Files.writeString(
                output,
                content,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE
            );
        } catch (Exception ignored) {
        }
    }

    private static String dumpWindows() {
        final StringBuilder builder = new StringBuilder();
        try {
            SwingUtilities.invokeAndWait(() -> {
                final java.util.Set<String> seen = new java.util.LinkedHashSet<>();
                for (Window window : Window.getWindows()) {
                    builder.append("window class=").append(window.getClass().getName())
                        .append(" visible=").append(window.isVisible())
                        .append(" title=").append(window instanceof java.awt.Frame
                            ? String.valueOf(((java.awt.Frame) window).getTitle()) : "-")
                        .append('\n');
                    dumpTree(window, 0, builder, seen);
                    dumpAnchors(window, builder);
                    dumpButtons(window, builder);
                }
            });
        } catch (Exception ignored) {
        }
        return builder.toString();
    }

    /** Prints every AbstractButton with its action command, tooltip and text for toolbar identification. */
    private static void dumpButtons(final Component component, final StringBuilder builder) {
        if (component instanceof AbstractButton button) {
            builder.append("  button class=").append(button.getClass().getName())
                .append(" action=").append(String.valueOf(button.getActionCommand()))
                .append(" tooltip=").append(String.valueOf(button.getToolTipText()))
                .append(" text=").append(String.valueOf(button.getText()))
                .append(" parent=").append(button.getParent() == null
                    ? "-" : button.getParent().getClass().getName())
                .append('\n');
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                dumpButtons(child, builder);
            }
        }
    }

    /** Prints every JTable/JTextPane anchor with its parent chain for palette identification. */
    private static void dumpAnchors(final Component component, final StringBuilder builder) {
        if (component instanceof JTable || component instanceof JTextPane) {
            builder.append("  anchor class=").append(component.getClass().getName())
                .append(" kind=").append(component instanceof JTable ? "TABLE" : "TEXTPANE")
                .append(" rows=").append(component instanceof JTable
                    ? ((JTable) component).getRowCount() : lineCount((JTextPane) component))
                .append(" parents=");
            Component parent = component.getParent();
            int depth = 0;
            while (parent != null && depth < 6) {
                builder.append(parent.getClass().getName()).append(" > ");
                parent = parent.getParent();
                depth++;
            }
            builder.append('\n');
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                dumpAnchors(child, builder);
            }
        }
    }

    private static String dumpTree(
        final Component component,
        final int depth,
        final StringBuilder builder,
        final java.util.Set<String> seen
    ) {
        if (component == null || depth > 10 || seen.size() > 400) {
            return builder.toString();
        }
        seen.add(component.getClass().getName());
        builder.append("  tree:").append(depth)
            .append(" class=").append(component.getClass().getName())
            .append(" name=").append(component instanceof JComponent
                ? String.valueOf(((JComponent) component).getName()) : "-")
            .append('\n');
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                dumpTree(child, depth + 1, builder, seen);
            }
        }
        return builder.toString();
    }

    private static PaletteProbe palette(final List<PaletteProbe> palettes, final String kind) {
        for (PaletteProbe palette : palettes) {
            if (kind.equals(palette.kind)) {
                return palette;
            }
        }
        return null;
    }

    private static List<PaletteProbe> snapshotPalettes() {
        final AtomicReference<List<PaletteProbe>> result = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                final List<PaletteProbe> probes = new ArrayList<>();
                for (Window window : Window.getWindows()) {
                    for (String kind : new String[] {"parameter", "deformer", "scene", "log"}) {
                        if (paletteKindFound(probes, kind)) {
                            continue;
                        }
                        final JComponent root = findPaletteRoot(window, kind);
                        if (root != null) {
                            probes.add(probePalette(kind, root));
                        }
                    }
                }
                result.set(probes);
            });
        } catch (Exception ignored) {
            return List.of();
        }
        return result.get();
    }

    private static boolean paletteKindFound(final List<PaletteProbe> probes, final String kind) {
        for (PaletteProbe probe : probes) {
            if (kind.equals(probe.kind)) {
                return true;
            }
        }
        return false;
    }

    private static PaletteProbe probePalette(final String kind, final JComponent root) {
        final JTextField field = findFilterField(root);
        final JTable table = findTable(root);
        final JTextPane pane = findLogPane(root);
        return new PaletteProbe(
            kind,
            field != null,
            field == null ? "" : field.getToolTipText(),
            table == null ? -1 : table.getRowCount(),
            pane == null ? -1 : lineCount(pane),
            field,
            table,
            pane,
            root
        );
    }

    private static JComponent findPaletteRoot(final Component component, final String kind) {
        if (component instanceof JComponent
            && component.getClass().getName().contains("palette." + kind)) {
            return (JComponent) component;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                final JComponent found = findPaletteRoot(child, kind);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static JTextField findFilterField(final Component component) {
        if (component instanceof JTextField field && FIELD_NAME.equals(field.getName())) {
            return field;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                final JTextField found = findFilterField(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static JTable findTable(final Component component) {
        if (component instanceof JTable table) {
            return table;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                final JTable found = findTable(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static JTextPane findLogPane(final Component component) {
        if (component instanceof JTextPane pane
            && !pane.isEditable()
            && !Boolean.TRUE.equals(pane.getClientProperty("turboism.paletteFilter.filteredTextPane"))) {
            return pane;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                final JTextPane found = findLogPane(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static int sceneTableRowCount(final PaletteProbe palette) {
        final AtomicReference<Integer> count = new AtomicReference<>(-1);
        try {
            SwingUtilities.invokeAndWait(() -> count.set(palette.table == null ? -1 : palette.table.getRowCount()));
        } catch (Exception ignored) {
        }
        return count.get();
    }

    private static int logPaneLines(final PaletteProbe palette) {
        final AtomicReference<Integer> count = new AtomicReference<>(-1);
        try {
            SwingUtilities.invokeAndWait(() -> {
                final JTextPane pane = palette.logPane;
                count.set(pane == null ? -1 : lineCount(pane));
            });
        } catch (Exception ignored) {
        }
        return count.get();
    }

    private static void setText(final JTextField field, final String text) {
        try {
            SwingUtilities.invokeAndWait(() -> field.setText(text));
        } catch (Exception ignored) {
        }
    }

    private static int lineCount(final JTextPane pane) {
        return pane.getDocument().getDefaultRootElement().getElementCount();
    }

    private static final class PaletteProbe {
        final String kind;
        final boolean filterBoxFound;
        final String placeholder;
        final int tableRowCount;
        final int lines;
        final JTextField filterField;
        final JTable table;
        final JTextPane logPane;
        final JComponent root;
        final String tree;

        PaletteProbe(
            final String kind,
            final boolean filterBoxFound,
            final String placeholder,
            final int tableRowCount,
            final int lines,
            final JTextField filterField,
            final JTable table,
            final JTextPane logPane,
            final JComponent root
        ) {
            this.kind = kind;
            this.filterBoxFound = filterBoxFound;
            this.placeholder = placeholder;
            this.tableRowCount = tableRowCount;
            this.lines = lines;
            this.filterField = filterField;
            this.table = table;
            this.logPane = logPane;
            this.root = root;
            this.tree = root == null ? "" : dumpTree(root, 0, new StringBuilder(), new java.util.LinkedHashSet<>());
        }

        String describe() {
            return "palette=" + kind
                + " filter-box=" + filterBoxFound
                + " placeholder=" + placeholder
                + " table-rows=" + tableRowCount
                + " log-lines=" + lines
                + " root-class=" + (root == null ? "MISSING" : root.getClass().getName())
                + "\n" + tree;
        }
    }

}
