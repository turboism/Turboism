package dev.turboism.ui.panel;

import dev.turboism.sdk.plugin.Registration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CollapsiblePanel / CollapsibleSectionRegistry 注册接口测试（真实 Swing，paint + dispatchEvent）。 */
class CollapsiblePanelTest {

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

    private static Point centerOf(java.awt.Rectangle bounds) {
        return new Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
    }

    private static CollapsibleSection.CollapsibleTitledBorder borderOf(JPanel section) {
        return (CollapsibleSection.CollapsibleTitledBorder) section.getBorder();
    }

    private static JPanel contentOf(JPanel section) {
        return (JPanel) section.getComponent(0);
    }

    private static JPanel newContent() {
        JPanel content = new JPanel();
        content.add(new JPanel());
        return content;
    }

    private static CollapsibleSectionContribution contribution(String id, boolean expandedByDefault) {
        return new CollapsibleSectionContribution() {
            @Override public String id() { return id; }
            @Override public String title() { return "Section " + id; }
            @Override public boolean expandedByDefault() { return expandedByDefault; }
            @Override public JPanel content() { return newContent(); }
        };
    }

    @Test
    void registerAddsSectionsInInsertionOrder() {
        CollapsiblePanel panel = new CollapsiblePanel();

        panel.register(contribution("a", true));
        panel.register(contribution("b", false));
        panel.register(contribution("c", true));

        assertEquals(List.of("a", "b", "c"), panel.sectionIds());
        assertEquals(3, panel.getComponentCount());
        for (int i = 0; i < panel.getComponentCount(); i++) {
            JPanel child = (JPanel) panel.getComponent(i);
            assertInstanceOf(CollapsibleSection.CollapsibleTitledBorder.class, child.getBorder(),
                "child must be a CollapsibleSection.create product");
        }
        // 子组件顺序 = 注册顺序：第 1/3 个注册为展开态（收起），第 2 个为收起态（展开）。
        assertEquals("收起", borderOf((JPanel) panel.getComponent(0)).actionText());
        assertEquals("展开", borderOf((JPanel) panel.getComponent(1)).actionText());
        assertEquals("收起", borderOf((JPanel) panel.getComponent(2)).actionText());
    }

    @Test
    void duplicateIdRegistrationIsRejected() {
        CollapsiblePanel panel = new CollapsiblePanel();
        panel.register(contribution("a", true));

        assertThrows(IllegalStateException.class, () -> panel.register(contribution("a", false)));
        assertEquals(1, panel.getComponentCount());
    }

    @Test
    void registerNullContributionIsRejected() {
        CollapsiblePanel panel = new CollapsiblePanel();

        assertThrows(NullPointerException.class, () -> panel.register(null));
        assertTrue(panel.sectionIds().isEmpty());
    }

    @Test
    void closeRemovesSectionAndIsIdempotent() {
        CollapsiblePanel panel = new CollapsiblePanel();
        Registration first = panel.register(contribution("a", true));
        panel.register(contribution("b", false));

        first.close();

        assertEquals(List.of("b"), panel.sectionIds());
        assertEquals(1, panel.getComponentCount());

        first.close();
        assertEquals(List.of("b"), panel.sectionIds());
    }

    @Test
    void expandedStateReflectsDefaultAndSetExpandedTogglesContentAndLabel() {
        CollapsiblePanel panel = new CollapsiblePanel();
        panel.register(contribution("open", true));
        panel.register(contribution("closed", false));

        assertTrue(panel.isExpanded("open"));
        assertFalse(panel.isExpanded("closed"));

        panel.setExpanded("open", false);
        JPanel openSection = (JPanel) panel.getComponent(0);
        assertFalse(contentOf(openSection).isVisible());
        assertEquals("展开", borderOf(openSection).actionText());
        assertFalse(panel.isExpanded("open"));

        panel.setExpanded("open", true);
        assertTrue(contentOf(openSection).isVisible());
        assertEquals("收起", borderOf(openSection).actionText());
        assertTrue(panel.isExpanded("open"));
    }

    @Test
    void unknownSectionIdThrowsIllegalArgumentException() {
        CollapsiblePanel panel = new CollapsiblePanel();
        panel.register(contribution("a", true));

        assertThrows(IllegalArgumentException.class, () -> panel.isExpanded("missing"));
        assertThrows(IllegalArgumentException.class, () -> panel.setExpanded("missing", true));
    }

    @Test
    void clickingRegisteredSectionHotspotTogglesExpandedState() {
        CollapsiblePanel panel = new CollapsiblePanel();
        panel.register(contribution("a", true));
        JPanel section = (JPanel) panel.getComponent(0);
        section.setSize(400, 100);
        paint(section);

        Point hotspot = centerOf(borderOf(section).actionBounds());
        section.dispatchEvent(click(section, hotspot));

        assertFalse(contentOf(section).isVisible());
        assertEquals("展开", borderOf(section).actionText());
        assertFalse(panel.isExpanded("a"));

        section.dispatchEvent(click(section, hotspot));

        assertTrue(contentOf(section).isVisible());
        assertEquals("收起", borderOf(section).actionText());
        assertTrue(panel.isExpanded("a"));
    }

    @Test
    void chineseLocaleLabelsAppliedToRegisteredSections() {
        CollapsiblePanel panel = new CollapsiblePanel();
        panel.register(contribution("open", true));
        panel.register(contribution("closed", false));

        assertEquals("收起", borderOf((JPanel) panel.getComponent(0)).actionText());
        assertEquals("展开", borderOf((JPanel) panel.getComponent(1)).actionText());
    }
}
