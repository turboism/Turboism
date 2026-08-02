package dev.turboism.tests.plugin;

import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.TurboismPlugin;

import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.JLabel;
import javax.swing.JViewport;
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
 * <p>Scans the host Swing tree for requested palette tabs, verifies each
 * framework-attached filter box, and exercises exact parameter/deformer fields
 * plus scene/log filtering. Machine-readable evidence is appended to the file
 * named by {@code turboism.tabFilterProbe.output}. Requested palettes come from
 * {@code turboism.tabFilterProbe.required}; the default modeling pass is
 * {@code parameter,deformer,log}.</p>
 */
public final class TabFilterValidationProbe implements TurboismPlugin {

    private static final String FIELD_NAME = "turboismPaletteFilterField";
    private static final String NO_MATCH_KEYWORD = "zzz-no-match-zzz";
    private static final String REQUIRED_PROPERTY = "turboism.tabFilterProbe.required";

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
        final List<String> required = List.of(
            System.getProperty(REQUIRED_PROPERTY, "parameter,deformer,log").split(",")
        );
        final StringBuilder report = new StringBuilder();
        try {
            // Early window-tree dump so evidence survives a short-lived session.
            writeEarly(output, "probe=tab-filter status=early-dump\n" + dumpWindows());
            final long start = System.currentTimeMillis();
            final long deadline = start + 300_000;
            List<PaletteProbe> palettes = List.of();
            while (System.currentTimeMillis() < deadline) {
                palettes = snapshotPalettes();
                if (filtersReady(palettes, required)) {
                    break;
                }
                Thread.sleep(2_000);
            }
            palettes = snapshotPalettes();

            report.append("probe=tab-filter status=started required=").append(required)
                .append(" output=").append(output).append('\n');
            report.append(dumpWindows());
            for (PaletteProbe palette : palettes) {
                report.append(palette.describe());
            }
            if (!filtersReady(palettes, required)) {
                throw new IllegalStateException("required filter boxes not found: " + required);
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

            final PaletteProbe parameter = palette(palettes, "parameter");
            if (parameter != null && parameter.filterField != null) {
                final int rowsBefore = parameterRowCount(parameter.root);
                final String nameKeyword = firstParameterName(parameter.root);
                setText(parameter.filterField, NO_MATCH_KEYWORD);
                Thread.sleep(2_000);
                final int rowsNoMatch = parameterRowCount(parameter.root);
                setText(parameter.filterField, nameKeyword);
                Thread.sleep(2_000);
                final int rowsByName = parameterRowCount(parameter.root);
                setText(parameter.filterField, "ParamAngleX");
                Thread.sleep(2_000);
                final int rowsById = parameterRowCount(parameter.root);
                setText(parameter.filterField, "");
                Thread.sleep(2_000);
                final int rowsRestored = parameterRowCount(parameter.root);
                report.append("parameter-filter ")
                    .append("rows-before=").append(rowsBefore)
                    .append(" rows-no-match=").append(rowsNoMatch)
                    .append(" name-keyword=").append(nameKeyword)
                    .append(" rows-by-name=").append(rowsByName)
                    .append(" id-keyword=ParamAngleX")
                    .append(" rows-by-id=").append(rowsById)
                    .append(" rows-restored=").append(rowsRestored)
                    .append('\n');
            }

            final PaletteProbe deformer = palette(palettes, "deformer");
            if (deformer != null && deformer.filterField != null) {
                final int rowsBefore = deformer.tableRowCount;
                setText(deformer.filterField, "ArtMesh16");
                Thread.sleep(3_000);
                final int rowsById = treeTableRowCount(deformer);
                final String namesById = visibleRowNames(deformer);
                final String nameKeyword = namesById.substring(namesById.lastIndexOf('|') + 1);
                if (nameKeyword.isBlank() || "no-table".equals(nameKeyword)) {
                    throw new IllegalStateException("deformer display name was unavailable after ID filtering");
                }
                setText(deformer.filterField, nameKeyword);
                Thread.sleep(3_000);
                final int rowsByName = treeTableRowCount(deformer);
                final String namesByName = visibleRowNames(deformer);
                setText(deformer.filterField, "");
                Thread.sleep(3_000);
                final int rowsRestored = treeTableRowCount(deformer);
                report.append("deformer-filter ")
                    .append("rows-before=").append(rowsBefore)
                    .append(" id-keyword=ArtMesh16")
                    .append(" rows-by-id=").append(rowsById)
                    .append(" names-by-id=").append(namesById)
                    .append(" name-keyword=").append(nameKeyword)
                    .append(" rows-by-name=").append(rowsByName)
                    .append(" names-by-name=").append(namesByName)
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
                    dumpToolbarLayouts(window, builder);
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

    /** Captures runtime layout and child order for injected and parameter toolbars. */
    private static void dumpToolbarLayouts(final Component component, final StringBuilder builder) {
        if (component instanceof Container container) {
            if (isParameterToolbar(container) || containsDirectFilterPanel(container)) {
                builder.append("  toolbar-layout kind=")
                    .append(isParameterToolbar(container) ? "parameter" : "filtered")
                    .append(" class=").append(container.getClass().getName())
                    .append(" layout=").append(container.getLayout() == null
                        ? "null" : container.getLayout().getClass().getName())
                    .append(" bounds=").append(container.getBounds())
                    .append(" showing=").append(container.isShowing())
                    .append(" children=").append(container.getComponentCount())
                    .append('\n');
                for (int index = 0; index < container.getComponentCount(); index++) {
                    final Component child = container.getComponent(index);
                    builder.append("    child index=").append(index)
                        .append(" class=").append(child.getClass().getName())
                        .append(" name=").append(child instanceof JComponent
                            ? String.valueOf(((JComponent) child).getName()) : "-")
                        .append(" bounds=").append(child.getBounds())
                        .append(" preferred=").append(child.getPreferredSize())
                        .append(" maximum=").append(child.getMaximumSize())
                        .append('\n');
                }
                Component ancestor = container.getParent();
                int depth = 0;
                while (ancestor != null && depth++ < 6) {
                    builder.append("    ancestor class=").append(ancestor.getClass().getName())
                        .append(" layout=").append(ancestor instanceof Container parent && parent.getLayout() != null
                            ? parent.getLayout().getClass().getName() : "null")
                        .append(" bounds=").append(ancestor.getBounds())
                        .append('\n');
                    ancestor = ancestor.getParent();
                }
            }
            for (Component child : container.getComponents()) {
                dumpToolbarLayouts(child, builder);
            }
        }
    }

    private static boolean containsDirectFilterPanel(final Container container) {
        for (Component child : container.getComponents()) {
            if (child instanceof JComponent component
                && "turboismPaletteFilterPanel".equals(component.getName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isParameterToolbar(final Container container) {
        final List<AbstractButton> buttons = new ArrayList<>();
        collectButtons(container, 2, buttons);
        if (buttons.size() != 3) return false;
        boolean add = false;
        boolean folder = false;
        boolean delete = false;
        for (AbstractButton button : buttons) {
            final String value = (String.valueOf(button.getActionCommand()) + ' '
                + String.valueOf(button.getToolTipText()) + ' ' + String.valueOf(button.getText()))
                .toLowerCase(java.util.Locale.ROOT);
            add |= value.contains("cmd_parameter_palette_add_new_parameter")
                || value.contains("创建新参数") || value.contains("create parameter");
            folder |= value.contains("cmd_parameter_palette_new_folder")
                || value.contains("创建新文件夹") || value.contains("create folder");
            delete |= value.contains("cmd_parameter_palette_delete_object")
                || value.contains("删除选定的元素") || value.contains("delete selected");
        }
        return add && folder && delete;
    }

    private static void collectButtons(
        final Component component,
        final int depth,
        final List<AbstractButton> buttons
    ) {
        if (component == null || depth < 0) return;
        if (component instanceof AbstractButton button) {
            buttons.add(button);
            return;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                collectButtons(child, depth - 1, buttons);
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

    private static boolean filtersReady(
        final List<PaletteProbe> palettes,
        final List<String> required
    ) {
        for (String kind : required) {
            final PaletteProbe probe = palette(palettes, kind.trim());
            if (probe == null || !probe.filterBoxFound) {
                return false;
            }
        }
        return true;
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
                        final JComponent root = "parameter".equals(kind)
                            ? findParameterRoot(window)
                            : findPaletteRoot(window, kind);
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
        final JTextField field = findFilterFieldNear(root);
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

    private static JComponent findParameterRoot(final Component component) {
        if (component instanceof JViewport viewport
            && viewport.getView() instanceof JComponent root
            && containsParameterRow(root)
            && findFilterFieldNear(root) != null) {
            return root;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                final JComponent found = findParameterRoot(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static boolean containsParameterRow(final Component component) {
        final String className = component.getClass().getName();
        if (className.equals("com.live2d.ui.swingImpl.p")
            || className.equals("com.live2d.ui.swingImpl.n")) {
            return true;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                if (containsParameterRow(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Finds the filter box near a palette root: the field is attached to the palette's toolbar,
     *  which sits outside the content subtree, so we search sibling subtrees upward. */
    private static JTextField findFilterFieldNear(final JComponent root) {
        Component current = root;
        int hops = 0;
        while (current != null && current.getParent() != null && hops < 16) {
            final Container parent = current.getParent();
            for (Component sibling : parent.getComponents()) {
                if (sibling == current) {
                    continue;
                }
                final JTextField found = findFilterField(sibling);
                if (found != null) {
                    return found;
                }
            }
            current = parent;
            hops++;
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

    /** Visible row names (column 2) of the deformer tree table. */
    private static String visibleRowNames(final PaletteProbe palette) {
        final StringBuilder builder = new StringBuilder();
        try {
            SwingUtilities.invokeAndWait(() -> {
                final JTable table = findTreeTableIn(palette.root);
                if (table == null) {
                    builder.append("no-table");
                    return;
                }
                for (int row = 0; row < table.getRowCount(); row++) {
                    if (row > 0) {
                        builder.append('|');
                    }
                    final Object value = table.getValueAt(row, Math.min(2, table.getColumnCount() - 1));
                    builder.append(value == null ? "-" : value);
                }
            });
        } catch (Exception ignored) {
        }
        return builder.toString();
    }

    private static int parameterRowCount(final JComponent root) {
        final AtomicReference<Integer> count = new AtomicReference<>(-1);
        try {
            SwingUtilities.invokeAndWait(() -> count.set(countVisibleParameterRows(root)));
        } catch (Exception ignored) {
        }
        return count.get();
    }

    private static int countVisibleParameterRows(final Component component) {
        final String className = component.getClass().getName();
        int count = component.isShowing() && (className.equals("com.live2d.ui.swingImpl.p")
            || className.equals("com.live2d.ui.swingImpl.n")) ? 1 : 0;
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                count += countVisibleParameterRows(child);
            }
        }
        return count;
    }

    private static String firstParameterName(final JComponent root) {
        final AtomicReference<String> name = new AtomicReference<>("");
        try {
            SwingUtilities.invokeAndWait(() -> name.set(findParameterName(root)));
        } catch (Exception ignored) {
        }
        return name.get();
    }

    private static String findParameterName(final Component component) {
        if (component.getClass().getName().equals("com.live2d.ui.swingImpl.p")) {
            final String name = findParameterLabelText(component);
            if (!name.isEmpty()) {
                return name;
            }
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                final String name = findParameterName(child);
                if (!name.isEmpty()) {
                    return name;
                }
            }
        }
        return "";
    }

    private static String findParameterLabelText(final Component component) {
        if (component instanceof JLabel label
            && component.getClass().getName().equals("com.live2d.ui.control.y")
            && label.getText() != null
            && !label.getText().isBlank()) {
            return label.getText();
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                final String name = findParameterLabelText(child);
                if (!name.isEmpty()) {
                    return name;
                }
            }
        }
        return "";
    }

    private static int treeTableRowCount(final PaletteProbe palette) {
        final AtomicReference<Integer> count = new AtomicReference<>(-1);
        try {
            SwingUtilities.invokeAndWait(() -> {
                final JTable table = findTreeTableIn(palette.root);
                count.set(table == null ? -1 : table.getRowCount());
            });
        } catch (Exception ignored) {
        }
        return count.get();
    }

    private static JTable findTreeTableIn(final Component component) {
        if (component instanceof JTable table
            && table.getClass().getName().contains("CDeformerTreeTable")) {
            return table;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                final JTable found = findTreeTableIn(child);
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
