package dev.turboism.adapter.cubism.physics;

import dev.turboism.sdk.cubism.physics.PhysicsEditorContribution;
import org.junit.jupiter.api.Test;

import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import java.awt.event.MouseEvent;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhysicsEditorCoordinatorTest {

    private static final PhysicsEditorHostProfile PROFILE = new PhysicsEditorHostProfile(
        Panel.class.getName().replace('.', '/'),
        "getTableArea", "this$0", "l", "getSources", "getEnable", "setEnable", "getGuid",
        "b", "n", "d"
    );

    @Test
    void headerToggleUsesOneHostTransactionAndRestoresFlagsWhenThePanelReopens() throws Exception {
        final PhysicsEditorCoordinator coordinator = new PhysicsEditorCoordinator();
        coordinator.contribute(new PhysicsEditorContribution(true, true));
        final Outer outer = new Outer(false, true, false);

        final Panel first = onEdt(() -> new Panel(outer));
        onEdt(() -> coordinator.onPanelConstructed(first, PROFILE));
        flushEdt();

        assertEquals("indeterminate", first.table.getTableHeader().getClientProperty("turboism.physics.state"));
        clickEnableHeader(first.table);
        assertEquals(List.of(true, true, true), outer.values());
        assertEquals(List.of("begin", "commit"), outer.transactions);

        outer.sources.forEach(source -> source.setEnable(false));
        final Panel reopened = onEdt(() -> new Panel(outer));
        onEdt(() -> coordinator.onPanelConstructed(reopened, PROFILE));
        flushEdt();

        assertEquals(List.of(true, true, true), outer.values());
        assertEquals(List.of("begin", "commit", "begin", "commit"), outer.transactions);
        assertEquals("selected", reopened.table.getTableHeader().getClientProperty("turboism.physics.state"));
    }

    @Test
    void headerIconReflectsTheCurrentAggregateSelectionState() throws Exception {
        final PhysicsEditorCoordinator coordinator = new PhysicsEditorCoordinator();
        coordinator.contribute(new PhysicsEditorContribution(true, false));
        final Panel panel = onEdt(() -> new Panel(new Outer(false, false)));
        onEdt(() -> coordinator.onPanelConstructed(panel, PROFILE));
        flushEdt();

        final int unselectedPixels = onEdt(() -> headerIconPixels(panel.table, false));
        clickEnableHeader(panel.table);
        final int selectedPixels = onEdt(() -> headerIconPixels(panel.table, false));

        assertNotEquals(unselectedPixels, selectedPixels);
    }

    @Test
    void hoveredButtonHeaderKeepsTheAggregateSelectionState() throws Exception {
        final PhysicsEditorCoordinator coordinator = new PhysicsEditorCoordinator();
        coordinator.contribute(new PhysicsEditorContribution(true, false));
        final Panel panel = onEdt(() -> new Panel(new Outer(false, false)));
        onEdt(() -> panel.table.getTableHeader().setDefaultRenderer(
            (table, value, selected, focused, row, column) -> focused ? new JButton(value.toString()) : new JLabel(value.toString())
        ));
        onEdt(() -> coordinator.onPanelConstructed(panel, PROFILE));
        flushEdt();

        final int unselectedHoverPixels = onEdt(() -> headerIconPixels(panel.table, true));
        clickEnableHeader(panel.table);
        final int selectedHoverPixels = onEdt(() -> headerIconPixels(panel.table, true));

        assertNotEquals(unselectedHoverPixels, selectedHoverPixels);
    }

    @Test
    void hostPublishedRowStateIsNotWrittenIntoTheModelTwice() throws Exception {
        final PhysicsEditorCoordinator coordinator = new PhysicsEditorCoordinator();
        coordinator.contribute(new PhysicsEditorContribution(true, false));
        final Panel panel = onEdt(() -> new Panel(new Outer(false, true, false)));
        onEdt(() -> coordinator.onPanelConstructed(panel, PROFILE));
        flushEdt();
        panel.model.writes = 0;

        clickEnableHeader(panel.table);

        assertEquals(3, panel.model.writes);
    }

    @Test
    void ordinaryRowEditsAreAlsoRetainedWhenThePanelReopens() throws Exception {
        final PhysicsEditorCoordinator coordinator = new PhysicsEditorCoordinator();
        coordinator.contribute(new PhysicsEditorContribution(true, true));
        final Outer outer = new Outer(false, false);
        final Panel first = onEdt(() -> new Panel(outer));
        onEdt(() -> coordinator.onPanelConstructed(first, PROFILE));
        flushEdt();

        onEdt(() -> {
            outer.sources.get(1).setEnable(true);
            first.table.getModel().setValueAt(true, 1, 0);
        });
        flushEdt();
        outer.sources.forEach(source -> source.setEnable(false));

        final Panel reopened = onEdt(() -> new Panel(outer));
        onEdt(() -> coordinator.onPanelConstructed(reopened, PROFILE));
        flushEdt();

        assertEquals(List.of(false, true), outer.values());
        assertEquals(List.of("begin", "commit"), outer.transactions);
    }

    @Test
    void failedBulkMutationRollsBackAndDoesNotReplaceTheRetainedState() throws Exception {
        final PhysicsEditorCoordinator coordinator = new PhysicsEditorCoordinator();
        coordinator.contribute(new PhysicsEditorContribution(true, true));
        final Outer outer = new Outer(false, false);
        final Panel panel = onEdt(() -> new Panel(outer));
        onEdt(() -> coordinator.onPanelConstructed(panel, PROFILE));
        flushEdt();
        outer.failAt = 1;

        clickEnableHeader(panel.table);

        assertEquals(List.of(false, false), outer.values());
        assertEquals(List.of("begin", "rollback"), outer.transactions);
        assertFalse(panel.table.getTableHeader().isEnabled());
    }

    @Test
    void closingTheContributionRemovesTheHeaderBehavior() throws Exception {
        final PhysicsEditorCoordinator coordinator = new PhysicsEditorCoordinator();
        final var registration = coordinator.contribute(new PhysicsEditorContribution(true, true));
        final Outer outer = new Outer(false, false);
        final Panel panel = onEdt(() -> new Panel(outer));
        onEdt(() -> coordinator.onPanelConstructed(panel, PROFILE));
        flushEdt();

        registration.close();
        clickEnableHeader(panel.table);

        assertEquals(List.of(false, false), outer.values());
        assertTrue(outer.transactions.isEmpty());
    }

    private static void clickEnableHeader(final JTable table) throws Exception {
        onEdt(() -> {
            final int x = table.getTableHeader().getHeaderRect(0).x + 2;
            final MouseEvent event = new MouseEvent(
                table.getTableHeader(), MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0,
                x, 2, 1, false, MouseEvent.BUTTON1
            );
            for (var listener : table.getTableHeader().getMouseListeners()) listener.mouseClicked(event);
        });
        flushEdt();
    }

    private static int headerIconPixels(final JTable table, final boolean focused) {
        final var renderer = table.getTableHeader().getDefaultRenderer();
        final Component component = renderer.getTableCellRendererComponent(
            table, table.getColumnModel().getColumn(0).getHeaderValue(), false, focused, -1, 0
        );
        final Icon icon = component instanceof JLabel label ? label.getIcon() : ((AbstractButton) component).getIcon();
        final BufferedImage image = new BufferedImage(icon.getIconWidth(), icon.getIconHeight(), BufferedImage.TYPE_INT_ARGB);
        final Graphics2D graphics = image.createGraphics();
        try { icon.paintIcon(component, graphics, 0, 0); } finally { graphics.dispose(); }
        int pixels = 1;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) pixels = 31 * pixels + image.getRGB(x, y);
        }
        return pixels;
    }

    private static void flushEdt() throws Exception {
        onEdt(() -> { });
    }

    private static <T> T onEdt(final ThrowingSupplier<T> supplier) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return supplier.get();
        final Object[] result = new Object[1];
        final Throwable[] failure = new Throwable[1];
        SwingUtilities.invokeAndWait(() -> {
            try { result[0] = supplier.get(); } catch (Throwable throwable) { failure[0] = throwable; }
        });
        if (failure[0] != null) throw new RuntimeException(failure[0]);
        @SuppressWarnings("unchecked") final T value = (T) result[0];
        return value;
    }

    private static void onEdt(final ThrowingRunnable runnable) throws Exception {
        onEdt(() -> { runnable.run(); return null; });
    }

    @FunctionalInterface private interface ThrowingSupplier<T> { T get() throws Exception; }
    @FunctionalInterface private interface ThrowingRunnable { void run() throws Exception; }

    static final class Panel {
        private final Outer this$0;
        private final CountingTableModel model;
        private final JTable table;

        Panel(final Outer outer) {
            this.this$0 = outer;
            this.model = new CountingTableModel(
                outer.sources.stream().map(source -> new Object[]{source.getEnable(), 1, source.getGuid(), "", "", "", ""}).toArray(Object[][]::new),
                new Object[]{"Enabled", "Priority", "Name", "Input", "Output", "Normalization", "Preview"}
            );
            this.table = new JTable(model);
            outer.model = model;
        }

        public TableArea getTableArea() { return new TableArea(table); }
    }

    record TableArea(JTable table) { public JTable getJTable() { return table; } }

    static final class CountingTableModel extends DefaultTableModel {
        private int writes;

        CountingTableModel(final Object[][] data, final Object[] columns) { super(data, columns); }

        @Override public Class<?> getColumnClass(final int columnIndex) {
            return columnIndex == 0 ? Boolean.class : columnIndex == 1 ? Integer.class : String.class;
        }

        @Override public void setValueAt(final Object value, final int row, final int column) {
            writes++;
            super.setValueAt(value, row, column);
        }
    }

    static final class Outer {
        private final List<Source> sources = new ArrayList<>();
        private final List<String> transactions = new ArrayList<>();
        private List<Boolean> checkpoint;
        private int failAt = -1;
        private int writes;
        private CountingTableModel model;

        Outer(final boolean... values) {
            for (int index = 0; index < values.length; index++) sources.add(new Source(this, "g" + index, values[index]));
        }

        private SourceSet l() { return new SourceSet(sources); }
        private Object b() { transactions.add("begin"); checkpoint = values(); writes = 0; return new Object(); }
        private void n() {
            transactions.add("commit");
            if (model != null) {
                for (int index = 0; index < sources.size(); index++) model.setValueAt(sources.get(index).getEnable(), index, 0);
            }
        }
        private void d() {
            transactions.add("rollback");
            for (int index = 0; index < sources.size(); index++) sources.get(index).enabled = checkpoint.get(index);
        }
        private List<Boolean> values() { return sources.stream().map(Source::getEnable).toList(); }
    }

    record SourceSet(List<Source> sources) { public List<Source> getSources() { return sources; } }

    static final class Source {
        private final Outer owner;
        private final String guid;
        private boolean enabled;

        Source(final Outer owner, final String guid, final boolean enabled) {
            this.owner = owner;
            this.guid = guid;
            this.enabled = enabled;
        }

        public String getGuid() { return guid; }
        public boolean getEnable() { return enabled; }
        public void setEnable(final boolean value) {
            if (owner.writes++ == owner.failAt) throw new IllegalStateException("fixture failure");
            enabled = value;
        }
    }
}
