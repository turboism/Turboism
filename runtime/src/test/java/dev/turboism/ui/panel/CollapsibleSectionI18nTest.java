package dev.turboism.ui.panel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.Point;
import java.util.Locale;
import javax.swing.JPanel;
import org.junit.jupiter.api.Test;

class CollapsibleSectionI18nTest {

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
            JPanel content = SwingPanelTestSupport.newContent();
            JPanel panel = CollapsibleSection.create("Section", content, true, locale);
            panel.setSize(400, 200);
            SwingPanelTestSupport.paint(panel);

            assertEquals(collapse, SwingPanelTestSupport.borderOf(panel).actionText());

            Point hotspot = SwingPanelTestSupport.centerOf(
                    SwingPanelTestSupport.borderOf(panel).actionBounds());
            panel.dispatchEvent(SwingPanelTestSupport.click(panel, hotspot));
            assertEquals(expand, SwingPanelTestSupport.borderOf(panel).actionText());

            panel.dispatchEvent(SwingPanelTestSupport.click(panel, hotspot));
            assertEquals(collapse, SwingPanelTestSupport.borderOf(panel).actionText());
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
    void japaneseLocaleUsesJaBundleLabels() {
        assertToggleLabels(Locale.JAPANESE, "折りたたみ", "展開");
    }

    @Test
    void koreanLocaleUsesKoBundleLabels() {
        assertToggleLabels(Locale.KOREAN, "접기", "펼치기");
    }

    @Test
    void unsupportedLocaleFallsBackToDefaultBundleLabels() {
        assertToggleLabels(Locale.FRENCH, "Collapse", "Expand");
    }
}
