package dev.turboism.ui.panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Cursor;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.util.Locale;
import javax.swing.JPanel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CollapsibleSectionRuntimeTest {

    private Locale savedLocale;

    @BeforeEach
    void fixDefaultLocaleToChinese() {
        savedLocale = Locale.getDefault();
        Locale.setDefault(Locale.CHINESE);
    }

    @AfterEach
    void restoreDefaultLocale() {
        Locale.setDefault(savedLocale);
    }

    private static MouseEvent move(JPanel panel, Point point) {
        return new MouseEvent(panel, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, point.x, point.y, 0, false);
    }

    @Test
    void expandedByDefaultShowsContentAndCollapseLabel() {
        JPanel content = SwingPanelTestSupport.newContent();
        JPanel panel = CollapsibleSection.create("标题", content, true);

        assertTrue(content.isVisible());
        assertEquals("收起", SwingPanelTestSupport.borderOf(panel).actionText());
    }

    @Test
    void collapsedByDefaultHidesContentAndShowsExpandLabel() {
        JPanel content = SwingPanelTestSupport.newContent();
        JPanel panel = CollapsibleSection.create("标题", content, false);

        assertFalse(content.isVisible());
        assertEquals("展开", SwingPanelTestSupport.borderOf(panel).actionText());
    }

    @Test
    void clickingActionHotspotTogglesVisibilityAndLabel() {
        JPanel content = SwingPanelTestSupport.newContent();
        JPanel panel = CollapsibleSection.create("标题", content, true);
        panel.setSize(400, 200);
        SwingPanelTestSupport.paint(panel);
        Point hotspot = SwingPanelTestSupport.centerOf(
                SwingPanelTestSupport.borderOf(panel).actionBounds());

        panel.dispatchEvent(SwingPanelTestSupport.click(panel, hotspot));

        assertFalse(content.isVisible());
        assertEquals("展开", SwingPanelTestSupport.borderOf(panel).actionText());

        panel.dispatchEvent(SwingPanelTestSupport.click(panel, hotspot));

        assertTrue(content.isVisible());
        assertEquals("收起", SwingPanelTestSupport.borderOf(panel).actionText());
    }

    @Test
    void clickingOutsideActionHotspotDoesNotToggle() {
        JPanel content = SwingPanelTestSupport.newContent();
        JPanel panel = CollapsibleSection.create("标题", content, true);
        panel.setSize(400, 200);
        SwingPanelTestSupport.paint(panel);
        Point outside = new Point(10, 100);
        assertFalse(SwingPanelTestSupport.borderOf(panel).actionBounds().contains(outside));

        panel.dispatchEvent(SwingPanelTestSupport.click(panel, outside));

        assertTrue(content.isVisible());
        assertEquals("收起", SwingPanelTestSupport.borderOf(panel).actionText());
    }

    @Test
    void borderInsetsTopIsAtLeastTwenty() {
        JPanel panel = CollapsibleSection.create("标题", SwingPanelTestSupport.newContent(), true);

        assertTrue(panel.getInsets().top >= 20);
        assertTrue(SwingPanelTestSupport.borderOf(panel).getBorderInsets(panel).top >= 20);
    }

    @Test
    void cursorTurnsToHandOverHotspotAndRestoresOutside() {
        JPanel panel = CollapsibleSection.create("标题", SwingPanelTestSupport.newContent(), true);
        panel.setSize(400, 200);
        SwingPanelTestSupport.paint(panel);
        Point hotspot = SwingPanelTestSupport.centerOf(
                SwingPanelTestSupport.borderOf(panel).actionBounds());

        panel.dispatchEvent(move(panel, hotspot));
        assertEquals(Cursor.HAND_CURSOR, panel.getCursor().getType());

        panel.dispatchEvent(move(panel, new Point(10, 100)));
        assertEquals(Cursor.DEFAULT_CURSOR, panel.getCursor().getType());
    }
}
