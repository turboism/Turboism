package dev.turboism.ui.table;

import org.junit.jupiter.api.Test;

import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;
import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class SceneTableDragSupportTest {

    @Test
    void resolvesDropRowsInsideAndOutsideTable() {
        final JTable table = table();
        assertEquals(0, SceneTableDragSupport.resolveDropRow(table, new Point(5, 5)));
        assertEquals(0, SceneTableDragSupport.resolveDropRow(table, new Point(5, -5)));
        assertEquals(2, SceneTableDragSupport.resolveDropRow(table, new Point(5, table.getHeight() + 5)));
    }

    @Test
    void capturesWholeRowAndTracksOverlayTarget() {
        final JTable table = table();
        assertNotNull(SceneTableDragSupport.getRowBounds(table, 1));
        assertNotNull(SceneTableDragSupport.captureRowImage(table, 1));

        final SceneTableDragSupport.DragOverlay overlay = new SceneTableDragSupport.DragOverlay(table);
        overlay.startDrag(0, new Point(5, 5));
        overlay.updateDrag(new Point(5, table.getRowHeight() * 2 + 5));
        assertEquals(2, overlay.targetRow());
        assertFalse(overlay.contains(5, 5));
        overlay.finish();
    }

    private static JTable table() {
        final JTable table = new JTable(new DefaultTableModel(
            new Object[][] {{"a", "1"}, {"b", "2"}, {"c", "3"}},
            new Object[] {"Name", "Duration"}
        ));
        table.setSize(240, table.getRowHeight() * 3);
        return table;
    }
}
