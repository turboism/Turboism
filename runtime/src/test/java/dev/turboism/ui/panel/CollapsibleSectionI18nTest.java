package dev.turboism.ui.panel;

import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CollapsibleSectionI18nTest {

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

    private static Point centerOf(Rectangle bounds) {
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

    private static void withLocale(Locale locale, Runnable body) {
        Locale saved = Locale.getDefault();
        Locale.setDefault(locale);
        try {
            body.run();
        } finally {
            Locale.setDefault(saved);
        }
    }

    /** 展开态断言 collapse 标签，点击热区收起后断言 expand 标签，再点击恢复。 */
    private static void assertToggleLabels(Locale locale, String collapse, String expand) {
        withLocale(locale, () -> {
            JPanel content = newContent();
            JPanel panel = CollapsibleSection.create("Section", content, true);
            panel.setSize(400, 200);
            paint(panel);

            assertEquals(collapse, borderOf(panel).actionText());

            Point hotspot = centerOf(borderOf(panel).actionBounds());
            panel.dispatchEvent(click(panel, hotspot));
            assertEquals(expand, borderOf(panel).actionText());

            panel.dispatchEvent(click(panel, hotspot));
            assertEquals(collapse, borderOf(panel).actionText());
        });
    }

    @Test
    void englishDefaultLocaleUsesDefaultBundleLabels() {
        assertToggleLabels(Locale.ENGLISH, "Collapse", "Expand");
    }

    @Test
    void simplifiedChineseLocaleUsesZhBundleLabels() {
        assertToggleLabels(Locale.CHINESE, "收起", "展开");
    }

    @Test
    void simplifiedChineseCnLocaleUsesZhBundleLabels() {
        assertToggleLabels(Locale.SIMPLIFIED_CHINESE, "收起", "展开");
    }

    @Test
    void traditionalChineseLocaleUsesZhHantBundleLabels() {
        assertToggleLabels(Locale.forLanguageTag("zh-Hant"), "收起", "展開");
    }

    @Test
    void unsupportedLocaleFallsBackToDefaultBundleLabels() {
        assertToggleLabels(Locale.JAPANESE, "Collapse", "Expand");
    }
}
