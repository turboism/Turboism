package dev.turboism.ui.panel;

import dev.turboism.sdk.action.UiActionEvent;
import dev.turboism.sdk.ui.PanelView;
import org.junit.jupiter.api.Test;

import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SwingPanelViewRendererTest {

    @Test
    void rendersControlsAndEmitsTypedEventsWithoutCallingPluginCodeDirectly() throws Exception {
        List<String> actions = new ArrayList<>();
        List<Optional<UiActionEvent>> events = new ArrayList<>();
        PanelView view = PanelView.column(
            PanelView.button("run", "Run", "profile.run"),
            PanelView.textInput("name", "Name", "Alice", "profile.name.changed"),
            PanelView.select(
                "mode",
                "Mode",
                List.of(PanelView.option("fast", "Fast"), PanelView.option("safe", "Safe")),
                "fast",
                "profile.mode.changed"
            ),
            PanelView.toggle("enabled", "Enabled", false, "profile.enabled.changed")
        );

        SwingUtilities.invokeAndWait(() -> {
            JComponent rendered = SwingPanelViewRenderer.render(view, (action, event) -> {
                actions.add(action);
                events.add(event);
            });
            assertInstanceOf(AbstractButton.class, named(rendered, "run")).doClick();
            JTextField text = assertInstanceOf(JTextField.class, named(rendered, "name"));
            text.setText("Bob");
            text.postActionEvent();
            @SuppressWarnings("unchecked") JComboBox<PanelView.Option> select =
                (JComboBox<PanelView.Option>) assertInstanceOf(JComboBox.class, named(rendered, "mode"));
            select.setSelectedIndex(1);
            assertInstanceOf(AbstractButton.class, named(rendered, "enabled")).doClick();
        });

        assertEquals(
            List.of(
                "profile.run",
                "profile.name.changed",
                "profile.mode.changed",
                "profile.enabled.changed"
            ),
            actions
        );
        assertEquals(Optional.empty(), events.get(0));
        assertEquals(
            "Bob",
            assertInstanceOf(UiActionEvent.TextValue.class, events.get(1).orElseThrow().value()).value()
        );
        assertEquals(
            "safe",
            assertInstanceOf(UiActionEvent.SelectionValue.class, events.get(2).orElseThrow().value()).value()
        );
        assertEquals(
            true,
            assertInstanceOf(UiActionEvent.ToggleValue.class, events.get(3).orElseThrow().value()).value()
        );
    }

    @Test
    void rendersCollapsibleSectionWithBorderChildrenAndToggleClick() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JComponent rendered = SwingPanelViewRenderer.render(
                PanelView.collapsibleSection(
                    "标题",
                    true,
                    PanelView.button("run", "Run", "profile.run"),
                    PanelView.text("note")
                ),
                (action, event) -> { }
            );

            JPanel section = assertInstanceOf(JPanel.class, rendered);
            CollapsibleSection.CollapsibleTitledBorder border = assertInstanceOf(
                CollapsibleSection.CollapsibleTitledBorder.class, section.getBorder());

            JPanel content = (JPanel) section.getComponent(0);
            assertInstanceOf(AbstractButton.class, content.getComponent(0));
            assertEquals("run", content.getComponent(0).getName());
            assertEquals("note", assertInstanceOf(JLabel.class, content.getComponent(2)).getText());
            assertTrue(CollapsibleSection.isExpanded(section));

            section.setSize(400, 200);
            paint(section);
            Point hotspot = centerOf(border.actionBounds());
            section.dispatchEvent(click(section, hotspot));
            assertFalse(CollapsibleSection.isExpanded(section));

            section.dispatchEvent(click(section, hotspot));
            assertTrue(CollapsibleSection.isExpanded(section));
        });
    }

    @Test
    void rendersCollapsibleSectionCollapsedByDefault() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JComponent rendered = SwingPanelViewRenderer.render(
                PanelView.collapsibleSection("标题", false, PanelView.text("x")),
                (action, event) -> { }
            );
            JPanel section = assertInstanceOf(JPanel.class, rendered);
            assertInstanceOf(
                CollapsibleSection.CollapsibleTitledBorder.class, section.getBorder());
            assertFalse(CollapsibleSection.isExpanded(section));
        });
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

    private static Component named(final Component component, final String name) {
        if (name.equals(component.getName())) {
            return component;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                Component match = namedOrNull(child, name);
                if (match != null) {
                    return match;
                }
            }
        }
        throw new AssertionError("component not found: " + name);
    }

    private static Component namedOrNull(final Component component, final String name) {
        if (name.equals(component.getName())) {
            return component;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                Component match = namedOrNull(child, name);
                if (match != null) {
                    return match;
                }
            }
        }
        return null;
    }
}
