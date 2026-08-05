package dev.turboism.ui.panel;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.CollapsibleSectionContribution;
import dev.turboism.sdk.ui.EmbeddedPanelId;
import dev.turboism.sdk.ui.PanelView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PanelCollapsibleContentCoordinatorTest {

    private static final EmbeddedPanelId PANEL = EmbeddedPanelId.of("turboism.panel.main");
    private static final EmbeddedPanelId OTHER_PANEL = EmbeddedPanelId.of("plugin-a.panel");

    private final PanelCollapsibleContentCoordinator coordinator =
        new PanelCollapsibleContentCoordinator();

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

    private static CollapsibleSectionContribution section(
        final EmbeddedPanelId panel,
        final String sectionId,
        final int order,
        final String text
    ) {
        return new CollapsibleSectionContribution(
            panel, sectionId, "Section " + sectionId, order, true,
            PanelView.column(PanelView.text(text)));
    }

    @Test
    void registerRejectsNull() {
        assertThrows(NullPointerException.class, () -> coordinator.register(null));
    }

    @Test
    void duplicateSectionIdInSamePanelIsRejected() {
        coordinator.register(section(PANEL, "s1", 0, "one"));
        assertThrows(IllegalStateException.class,
            () -> coordinator.register(section(PANEL, "s1", 0, "two")));
    }

    @Test
    void sameSectionIdAcrossPanelsIsAllowed() {
        coordinator.register(section(PANEL, "s1", 0, "one"));
        coordinator.register(section(OTHER_PANEL, "s1", 0, "two"));
        assertEquals(1, coordinator.injectedSections(PANEL).size());
        assertEquals(1, coordinator.injectedSections(OTHER_PANEL).size());
    }

    @Test
    void pendingRegistrationLandsWhenPanelRegisters() {
        Registration registration = coordinator.register(section(PANEL, "s1", 0, "one"));
        assertFalse(coordinator.isRegistered(PANEL));
        assertEquals(1, coordinator.injectedSections(PANEL).size());

        coordinator.onPanelRegistered(PANEL);
        assertTrue(coordinator.isRegistered(PANEL));
        assertEquals(1, coordinator.injectedSections(PANEL).size());

        registration.close();
    }

    @Test
    void panelRemovalReturnsSectionsToPendingWithoutLossOrDuplication() {
        coordinator.register(section(PANEL, "s1", 0, "one"));
        coordinator.onPanelRegistered(PANEL);
        coordinator.onPanelRemoved(PANEL);
        assertFalse(coordinator.isRegistered(PANEL));
        assertEquals(1, coordinator.injectedSections(PANEL).size());

        // 重建后再次注册：同一 sectionId 的注入仍恰好一份。
        coordinator.onPanelRegistered(PANEL);
        assertEquals(1, coordinator.injectedSections(PANEL).size());
    }

    @Test
    void closeIsIdempotentAndOnlyRemovesOwnEntry() {
        Registration first = coordinator.register(section(PANEL, "s1", 0, "one"));
        first.close();
        first.close();
        assertTrue(coordinator.injectedSections(PANEL).isEmpty());

        Registration second = coordinator.register(section(PANEL, "s1", 0, "two"));
        assertEquals(1, coordinator.injectedSections(PANEL).size());
        // 旧句柄再次 close 不得移除新注册的条目。
        first.close();
        assertEquals(1, coordinator.injectedSections(PANEL).size());
        second.close();
        assertTrue(coordinator.injectedSections(PANEL).isEmpty());
    }

    @Test
    void injectedSectionsOrderByOrderThenSectionId() {
        coordinator.register(section(PANEL, "zebra", 5, "z"));
        coordinator.register(section(PANEL, "alpha", 5, "a"));
        coordinator.register(section(PANEL, "mid", 1, "m"));

        List<String> titles = coordinator.injectedSections(PANEL).stream()
            .map(view -> assertInstanceOf(PanelView.CollapsibleSection.class, view).title())
            .toList();
        assertEquals(List.of("Section mid", "Section alpha", "Section zebra"), titles);
    }

    @Test
    void mergeAppendsInjectedSectionsToColumnContent() {
        coordinator.register(section(PANEL, "s1", 0, "one"));
        coordinator.register(section(PANEL, "s2", 1, "two"));
        PanelView.Column content = PanelView.column(PanelView.text("t1"), PanelView.text("t2"));

        PanelView merged = coordinator.merge(PANEL, content);

        PanelView.Column column = assertInstanceOf(PanelView.Column.class, merged);
        assertEquals(4, column.children().size());
        assertSame(content.children().get(0), column.children().get(0));
        assertSame(content.children().get(1), column.children().get(1));
        assertInstanceOf(PanelView.CollapsibleSection.class, column.children().get(2));
        assertInstanceOf(PanelView.CollapsibleSection.class, column.children().get(3));
    }

    @Test
    void mergeWrapsNonColumnContent() {
        coordinator.register(section(PANEL, "s1", 0, "one"));
        PanelView content = PanelView.text("t");

        PanelView merged = coordinator.merge(PANEL, content);

        PanelView.Column column = assertInstanceOf(PanelView.Column.class, merged);
        assertEquals(2, column.children().size());
        assertSame(content, column.children().get(0));
        assertInstanceOf(PanelView.CollapsibleSection.class, column.children().get(1));
    }

    @Test
    void mergeReturnsContentUnchangedWhenNoInjections() {
        PanelView content = PanelView.column(PanelView.text("t"));
        assertSame(content, coordinator.merge(PANEL, content));
        assertSame(content, coordinator.merge(OTHER_PANEL, content));
    }

    @Test
    void mergedRenderKeepsDeclaredSectionsFirstAndInjectedSectionsCollapsible() {
        PanelView content = PanelView.column(
            PanelView.collapsibleSection("A 分区", true, PanelView.text("A-only")),
            PanelView.text("A 尾部"));
        coordinator.register(section(PANEL, "b-two", 10, "B-two"));
        coordinator.register(section(PANEL, "b-one", 5, "B-one"));

        JComponent rendered = SwingPanelViewRenderer.render(
            coordinator.merge(PANEL, content), (id, event) -> { });

        // A 分区（声明位置）在前，B 注入分区按 order 升序追加在后。
        List<JPanel> sections = sectionPanels(rendered);
        assertEquals(3, sections.size());
        assertTrue(findLabel(sections.get(0).getComponent(0), "A-only") != null);
        assertTrue(findLabel(sections.get(1).getComponent(0), "B-one") != null);
        assertTrue(findLabel(sections.get(2).getComponent(0), "B-two") != null);

        // 注入分区是真实可折叠分区：点击热区翻转，i18n 标签（Locale.CHINESE → 收起/展开）。
        JPanel injected = sections.get(1);
        assertTrue(CollapsibleSection.isExpanded(injected));
        assertEquals("收起", borderOf(injected).actionText());

        injected.setSize(400, 200);
        paint(injected);
        Point hotspot = centerOf(borderOf(injected).actionBounds());
        injected.dispatchEvent(click(injected, hotspot));
        assertFalse(CollapsibleSection.isExpanded(injected));
        assertEquals("展开", borderOf(injected).actionText());

        injected.dispatchEvent(click(injected, hotspot));
        assertTrue(CollapsibleSection.isExpanded(injected));
        assertEquals("收起", borderOf(injected).actionText());
    }

    private static List<JPanel> sectionPanels(final Component root) {
        List<JPanel> found = new ArrayList<>();
        collectSections(root, found);
        return found;
    }

    private static void collectSections(final Component component, final List<JPanel> found) {
        if (component instanceof JPanel panel
            && panel.getBorder() instanceof CollapsibleSection.CollapsibleTitledBorder) {
            found.add(panel);
        }
        if (component instanceof java.awt.Container container) {
            for (Component child : container.getComponents()) {
                collectSections(child, found);
            }
        }
    }

    private static JLabel findLabel(final Component root, final String text) {
        if (root instanceof JLabel label && text.equals(label.getText())) {
            return label;
        }
        if (root instanceof java.awt.Container container) {
            for (Component child : container.getComponents()) {
                JLabel found = findLabel(child, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

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

    private static CollapsibleSection.CollapsibleTitledBorder borderOf(JPanel panel) {
        return (CollapsibleSection.CollapsibleTitledBorder) panel.getBorder();
    }
}
