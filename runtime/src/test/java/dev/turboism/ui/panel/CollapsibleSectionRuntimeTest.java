package dev.turboism.ui.panel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import javax.swing.JPanel;
import java.awt.Cursor;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.util.Locale;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /** 真实绘制面板，使 border 计算出 actionBounds（paintBorder 内填充）。 */
    private static void paint(JPanel panel) {
        BufferedImage image = new BufferedImage(400, 200, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        panel.paint(graphics);
        graphics.dispose();
    }

    private static MouseEvent click(JPanel panel, Point point) {
        return new MouseEvent(panel, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0,
                point.x, point.y, 1, false, MouseEvent.BUTTON1);
    }

    private static MouseEvent move(JPanel panel, Point point) {
        return new MouseEvent(panel, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0,
                point.x, point.y, 0, false);
    }

    private static Point centerOf(java.awt.Rectangle bounds) {
        return new Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
    }

    private static CollapsibleSection.CollapsibleTitledBorder borderOf(JPanel panel) {
        return (CollapsibleSection.CollapsibleTitledBorder) panel.getBorder();
    }

    private static JPanel newContent() {
        JPanel content = new JPanel();
        content.add(new JPanel());
        return content;
    }

    @Test
    void expandedByDefaultShowsContentAndCollapseLabel() {
        JPanel content = newContent();
        JPanel panel = CollapsibleSection.create("标题", content, true);

        assertTrue(content.isVisible());
        assertEquals("收起", borderOf(panel).actionText());
    }

    @Test
    void collapsedByDefaultHidesContentAndShowsExpandLabel() {
        JPanel content = newContent();
        JPanel panel = CollapsibleSection.create("标题", content, false);

        assertFalse(content.isVisible());
        assertEquals("展开", borderOf(panel).actionText());
    }

    @Test
    void clickingActionHotspotTogglesVisibilityAndLabel() {
        JPanel content = newContent();
        JPanel panel = CollapsibleSection.create("标题", content, true);
        panel.setSize(400, 200);
        paint(panel);
        Point hotspot = centerOf(borderOf(panel).actionBounds());

        panel.dispatchEvent(click(panel, hotspot));

        assertFalse(content.isVisible());
        assertEquals("展开", borderOf(panel).actionText());

        panel.dispatchEvent(click(panel, hotspot));

        assertTrue(content.isVisible());
        assertEquals("收起", borderOf(panel).actionText());
    }

    @Test
    void clickingOutsideActionHotspotDoesNotToggle() {
        JPanel content = newContent();
        JPanel panel = CollapsibleSection.create("标题", content, true);
        panel.setSize(400, 200);
        paint(panel);
        Point outside = new Point(10, 100);
        assertFalse(borderOf(panel).actionBounds().contains(outside));

        panel.dispatchEvent(click(panel, outside));

        assertTrue(content.isVisible());
        assertEquals("收起", borderOf(panel).actionText());
    }

    @Test
    void borderInsetsTopIsAtLeastTwenty() {
        JPanel panel = CollapsibleSection.create("标题", newContent(), true);

        assertTrue(panel.getInsets().top >= 20);
        assertTrue(borderOf(panel).getBorderInsets(panel).top >= 20);
    }

    @Test
    void cursorTurnsToHandOverHotspotAndRestoresOutside() {
        JPanel panel = CollapsibleSection.create("标题", newContent(), true);
        panel.setSize(400, 200);
        paint(panel);
        Point hotspot = centerOf(borderOf(panel).actionBounds());

        panel.dispatchEvent(move(panel, hotspot));
        assertEquals(Cursor.HAND_CURSOR, panel.getCursor().getType());

        panel.dispatchEvent(move(panel, new Point(10, 100)));
        assertEquals(Cursor.DEFAULT_CURSOR, panel.getCursor().getType());
    }
}
