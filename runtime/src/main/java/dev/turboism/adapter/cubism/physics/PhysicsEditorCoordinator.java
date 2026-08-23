package dev.turboism.adapter.cubism.physics;

import dev.turboism.core.reflect.MethodHandleCache;
import dev.turboism.sdk.cubism.physics.PhysicsEditorContribution;
import dev.turboism.sdk.cubism.physics.PhysicsEditorService;
import dev.turboism.sdk.plugin.Registration;

import javax.swing.Icon;
import javax.swing.AbstractButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.TableModelEvent;
import javax.swing.event.TableModelListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableModel;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Runtime-owned physics editor lifecycle, native UI attachment, and transactional bulk mutation. */
public final class PhysicsEditorCoordinator implements PhysicsEditorService, AutoCloseable {
    private static final String INSTALL_MARKER = "turboism.physics.controller";
    private static final String STATE_MARKER = "turboism.physics.state";
    private static final int ENABLE_COLUMN = 0;
    private static final int PRIORITY_COLUMN = 1;

    private final Object lock = new Object();
    private PhysicsEditorContribution contribution;
    private final List<Controller> controllers = new ArrayList<>();
    private Map<String, Boolean> retained = Map.of();
    private boolean closed;

    @Override
    public Registration contribute(final PhysicsEditorContribution requested) {
        Objects.requireNonNull(requested, "contribution");
        synchronized (lock) {
            if (closed) throw new IllegalStateException("physics editor coordinator is closed");
            if (contribution != null) throw new IllegalStateException("physics editor contribution is already registered");
            contribution = requested;
        }
        return () -> clearContribution(requested);
    }

    /**
     * Attaches the registered contribution to a newly constructed host Physics Settings panel.
     *
     * <p>Does nothing when the coordinator is closed or no contribution has been registered. The
     * actual installation is marshalled onto the Swing event dispatch thread, so this may be called
     * from whatever host thread built the panel.
     *
     * @param panel the host panel to attach to
     * @param profile reviewed selectors for the running host build
     * @throws NullPointerException if either argument is {@code null}
     */
    public void onPanelConstructed(final Object panel, final PhysicsEditorHostProfile profile) {
        Objects.requireNonNull(panel, "panel");
        Objects.requireNonNull(profile, "profile");
        final PhysicsEditorContribution active;
        synchronized (lock) {
            if (closed || contribution == null) return;
            active = contribution;
        }
        runOnEdt(() -> install(panel, profile, active));
    }

    @Override
    public void close() {
        final List<Controller> stale;
        synchronized (lock) {
            if (closed) return;
            closed = true;
            contribution = null;
            retained = Map.of();
            stale = List.copyOf(controllers);
            controllers.clear();
        }
        runOnEdt(() -> stale.forEach(Controller::close));
    }

    private void clearContribution(final PhysicsEditorContribution expected) {
        final List<Controller> stale;
        synchronized (lock) {
            if (contribution != expected) return;
            contribution = null;
            retained = Map.of();
            stale = List.copyOf(controllers);
            controllers.clear();
        }
        runOnEdt(() -> stale.forEach(Controller::close));
    }

    private void install(
        final Object panel,
        final PhysicsEditorHostProfile profile,
        final PhysicsEditorContribution active
    ) {
        try {
            final Object tableArea = invoke(panel, profile.tableGetter());
            final Object tableValue = tableArea == null ? null : invoke(tableArea, "getJTable");
            final Object outer = field(panel, profile.outerField());
            if (!(tableValue instanceof JTable table) || outer == null || !looksLikePhysicsTable(table)) return;
            if (table.getClientProperty(INSTALL_MARKER) instanceof Controller) return;
            final Controller controller = new Controller(table, outer, profile);
            controller.install(active);
            table.putClientProperty(INSTALL_MARKER, controller);
            synchronized (lock) {
                if (closed || contribution != active) controller.close(); else controllers.add(controller);
            }
            System.err.println("Turboism physics editor header installed rows=" + table.getRowCount());
        } catch (Throwable failure) {
            System.err.println("Turboism physics editor install failed safely: " + failure.getClass().getName());
        }
    }

    private boolean looksLikePhysicsTable(final JTable table) {
        final TableModel model = table.getModel();
        return model != null && model.getColumnCount() > PRIORITY_COLUMN
            && (model.getColumnClass(ENABLE_COLUMN) == Boolean.class || model.getColumnClass(ENABLE_COLUMN) == boolean.class)
            && (model.getColumnClass(PRIORITY_COLUMN) == Integer.class || model.getColumnClass(PRIORITY_COLUMN) == int.class)
            && table.getTableHeader() != null;
    }

    private final class Controller extends MouseAdapter implements TableModelListener, PropertyChangeListener {
        private final JTable table;
        private final JTableHeader header;
        private final Object outer;
        private final PhysicsEditorHostProfile profile;
        private final TableCellRenderer originalRenderer;
        private TableModel model;
        private boolean active = true;
        private boolean retainOnReopen;
        private boolean repaintPending;

        private Controller(final JTable table, final Object outer, final PhysicsEditorHostProfile profile) {
            this.table = table;
            this.header = table.getTableHeader();
            this.outer = outer;
            this.profile = profile;
            this.originalRenderer = header.getDefaultRenderer();
        }

        private void install(final PhysicsEditorContribution contribution) throws Exception {
            retainOnReopen = contribution.retainEnabledGroupsOnReopen();
            if (contribution.headerSelectAll()) {
                header.setDefaultRenderer(new HeaderRenderer(this, originalRenderer));
                header.addMouseListener(this);
                table.addPropertyChangeListener("model", this);
                attach(table.getModel());
            }
            if (retainOnReopen) {
                restoreRetained();
                remember(sources());
            }
            repaint();
        }

        @Override
        public void mouseClicked(final MouseEvent event) {
            if (!active || !SwingUtilities.isLeftMouseButton(event)) return;
            if (header.columnAtPoint(event.getPoint()) != ENABLE_COLUMN) return;
            try {
                final Summary summary = summary();
                if (summary.rows() > 0) applyAll(!summary.allEnabled(), true);
            } catch (Throwable failure) {
                header.setEnabled(false);
                System.err.println("Turboism physics editor bulk update failed safely: " + failure.getClass().getName());
            }
        }

        @Override
        public void tableChanged(final TableModelEvent event) {
            if (retainOnReopen) {
                try { remember(sources()); } catch (Throwable failure) {
                    System.err.println("Turboism physics editor retention failed safely: " + failure.getClass().getName());
                }
            }
            repaint();
        }

        @Override
        public void propertyChange(final PropertyChangeEvent event) {
            if ("model".equals(event.getPropertyName())) attach((TableModel) event.getNewValue());
        }

        private void attach(final TableModel next) {
            if (model != null) model.removeTableModelListener(this);
            model = next;
            if (model != null) model.addTableModelListener(this);
            repaint();
        }

        private void restoreRetained() throws Exception {
            final Map<String, Boolean> snapshot;
            synchronized (lock) { snapshot = retained; }
            if (snapshot.isEmpty()) return;
            final List<Object> sources = sources();
            boolean different = false;
            for (Object source : sources) {
                final Boolean desired = snapshot.get(identity(source));
                if (desired != null && desired != enabled(source)) { different = true; break; }
            }
            if (different) apply(snapshot, false);
        }

        private void applyAll(final boolean enabled, final boolean remember) throws Exception {
            final Map<String, Boolean> desired = new LinkedHashMap<>();
            for (Object source : sources()) desired.put(identity(source), enabled);
            apply(desired, remember);
        }

        private void apply(final Map<String, Boolean> desired, final boolean remember) throws Exception {
            final List<Object> sources = sources();
            boolean changed = false;
            for (Object source : sources) {
                final Boolean value = desired.get(identity(source));
                if (value != null && value != enabled(source)) { changed = true; break; }
            }
            if (!changed) {
                if (remember) remember(sources);
                return;
            }
            final int[] selectedRows = table.getSelectedRows();
            final Object checkpoint = invoke(outer, profile.checkpointMethod());
            try {
                for (Object source : sources) {
                    final Boolean value = desired.get(identity(source));
                    if (value != null && value != enabled(source)) invoke(source, profile.enableSetter(), value);
                }
                invoke(outer, profile.commitMethod());
            } catch (Throwable failure) {
                if (checkpoint != null) {
                    try { invoke(outer, profile.rollbackMethod()); } catch (Throwable rollbackFailure) { failure.addSuppressed(rollbackFailure); }
                }
                throw failure;
            }
            restoreSelection(selectedRows);
            if (remember) remember(sources);
            refreshModel(sources);
            repaint();
        }

        private void remember(final List<Object> sources) throws Exception {
            final Map<String, Boolean> next = new LinkedHashMap<>();
            for (Object source : sources) next.put(identity(source), enabled(source));
            synchronized (lock) { retained = Map.copyOf(next); }
        }

        private void refreshModel(final List<Object> sources) throws Exception {
            if (model == null || model.getRowCount() != sources.size()) return;
            for (int index = 0; index < sources.size(); index++) {
                final Boolean enabled = enabled(sources.get(index));
                if (!enabled.equals(model.getValueAt(index, ENABLE_COLUMN))) {
                    model.setValueAt(enabled, index, ENABLE_COLUMN);
                }
            }
        }

        private List<Object> sources() throws Exception {
            final Object sourceSet = invoke(outer, profile.sourceSetGetter());
            final Object values = sourceSet == null ? null : invoke(sourceSet, profile.sourcesGetter());
            if (!(values instanceof Iterable<?> iterable)) return List.of();
            final List<Object> result = new ArrayList<>();
            iterable.forEach(result::add);
            return result;
        }

        private String identity(final Object source) throws Exception {
            final Object value = invoke(source, profile.identityGetter());
            return value == null ? Integer.toHexString(System.identityHashCode(source)) : value.toString();
        }

        private boolean enabled(final Object source) throws Exception {
            return Boolean.TRUE.equals(invoke(source, profile.enableGetter()));
        }

        private Summary summary() {
            if (model == null) return new Summary(0, 0);
            int enabled = 0;
            for (int row = 0; row < model.getRowCount(); row++) if (Boolean.TRUE.equals(model.getValueAt(row, ENABLE_COLUMN))) enabled++;
            return new Summary(model.getRowCount(), enabled);
        }

        private void restoreSelection(final int[] rows) {
            table.clearSelection();
            for (int row : rows) if (row >= 0 && row < table.getRowCount()) table.addRowSelectionInterval(row, row);
        }

        private void repaint() {
            if (repaintPending) return;
            repaintPending = true;
            SwingUtilities.invokeLater(() -> {
                repaintPending = false;
                final Summary summary = summary();
                header.putClientProperty(STATE_MARKER, summary.state());
                header.repaint(header.getHeaderRect(ENABLE_COLUMN));
            });
        }

        private void close() {
            if (!active) return;
            active = false;
            header.removeMouseListener(this);
            table.removePropertyChangeListener("model", this);
            if (model != null) model.removeTableModelListener(this);
            header.setDefaultRenderer(originalRenderer);
            header.putClientProperty(STATE_MARKER, null);
            table.putClientProperty(INSTALL_MARKER, null);
            header.repaint();
        }
    }

    private static final class HeaderRenderer implements TableCellRenderer {
        private final Controller controller;
        private final TableCellRenderer delegate;
        private final DefaultTableCellRenderer fallback = new DefaultTableCellRenderer();
        private final JCheckBox checkbox = new JCheckBox();

        private HeaderRenderer(final Controller controller, final TableCellRenderer delegate) {
            this.controller = controller;
            this.delegate = delegate;
            checkbox.setOpaque(false);
            checkbox.setBorderPainted(false);
            checkbox.setFocusPainted(false);
            checkbox.setHorizontalAlignment(SwingConstants.LEFT);
        }

        @Override
        public Component getTableCellRendererComponent(
            final JTable table,
            final Object value,
            final boolean selected,
            final boolean focused,
            final int row,
            final int column
        ) {
            final TableCellRenderer renderer = delegate == null ? fallback : delegate;
            final Component component = renderer.getTableCellRendererComponent(table, value, selected, focused, row, column);
            if (column != ENABLE_COLUMN) return component;
            final Summary summary = controller.summary();
            checkbox.setEnabled(summary.rows() > 0 && table.isEnabled());
            checkbox.setSelected(summary.allEnabled());
            final Icon baseIcon = javax.swing.UIManager.getIcon("CheckBox.icon");
            final Icon icon = new CheckBoxStateIcon(baseIcon, checkbox, summary.indeterminate());
            if (component instanceof JLabel label) {
                label.setIcon(icon);
                label.setHorizontalTextPosition(SwingConstants.RIGHT);
                label.setIconTextGap(4);
            }
            if (component instanceof AbstractButton button) {
                button.setIcon(icon);
                button.setHorizontalTextPosition(SwingConstants.RIGHT);
                button.setIconTextGap(4);
            }
            return component;
        }
    }

    private record Summary(int rows, int enabled) {
        boolean allEnabled() { return rows > 0 && enabled == rows; }
        boolean indeterminate() { return enabled > 0 && enabled < rows; }
        String state() { return indeterminate() ? "indeterminate" : allEnabled() ? "selected" : "unselected"; }
    }

    private record CheckBoxStateIcon(Icon delegate, JCheckBox source, boolean indeterminate) implements Icon {
        @Override public void paintIcon(final Component component, final Graphics graphics, final int x, final int y) {
            if (delegate != null) delegate.paintIcon(source, graphics, x, y);
            if (indeterminate) {
                graphics.fillRect(x + 4, y + getIconHeight() / 2 - 1, Math.max(3, getIconWidth() - 8), 3);
            }
        }
        @Override public int getIconWidth() { return delegate == null ? 13 : delegate.getIconWidth(); }
        @Override public int getIconHeight() { return delegate == null ? 13 : delegate.getIconHeight(); }
    }

    private static Object invoke(final Object target, final String name, final Object... arguments) throws Exception {
        final Method method = method(target.getClass(), name, arguments.length);
        method.setAccessible(true);
        return method.invoke(target, arguments);
    }

    private static Method method(final Class<?> type, final String name, final int parameterCount) throws NoSuchMethodException {
        // Cached hierarchy walk; the cache applies the non-public access policy once at resolution.
        return MethodHandleCache.declaredByArity(type, name, parameterCount);
    }

    private static Object field(final Object target, final String name) throws Exception {
        for (Class<?> current = target.getClass(); current != null; current = current.getSuperclass()) {
            try {
                final Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(target.getClass().getName() + "#" + name);
    }

    private static void runOnEdt(final Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) action.run(); else SwingUtilities.invokeLater(action);
    }
}
