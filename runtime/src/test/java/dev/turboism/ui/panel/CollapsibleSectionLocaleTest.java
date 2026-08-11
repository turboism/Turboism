package dev.turboism.ui.panel;

import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Runtime-owned panel controls must not fall back to English for the supported
 * ja/ko locales: the CollapsibleSection catalog bundle now has exact ja/ko values.
 */
class CollapsibleSectionLocaleTest {

    @Test
    void japaneseCatalogReachesTheExpandCollapseLabels() {
        JPanel section = CollapsibleSection.create("title", new JPanel(), true, Locale.JAPANESE);
        CollapsibleSection.CollapsibleTitledBorder border = border(section);

        assertEquals("折りたたみ", border.actionText(), "expanded sections show the collapse label");
        CollapsibleSection.setExpanded(section, false);
        assertEquals("展開", border.actionText(), "collapsed sections show the expand label");
    }

    @Test
    void koreanCatalogReachesTheExpandCollapseLabels() {
        JPanel section = CollapsibleSection.create("title", new JPanel(), true, Locale.KOREAN);
        CollapsibleSection.CollapsibleTitledBorder border = border(section);

        assertEquals("접기", border.actionText(), "expanded sections show the collapse label");
        CollapsibleSection.setExpanded(section, false);
        assertEquals("펼치기", border.actionText(), "collapsed sections show the expand label");
    }

    @Test
    void englishFallbackRemainsUnchanged() {
        JPanel section = CollapsibleSection.create("title", new JPanel(), true, Locale.ENGLISH);
        CollapsibleSection.CollapsibleTitledBorder border = border(section);

        assertEquals("Collapse", border.actionText());
        CollapsibleSection.setExpanded(section, false);
        assertEquals("Expand", border.actionText());
    }

    private static CollapsibleSection.CollapsibleTitledBorder border(final JPanel section) {
        return (CollapsibleSection.CollapsibleTitledBorder) section.getBorder();
    }
}
