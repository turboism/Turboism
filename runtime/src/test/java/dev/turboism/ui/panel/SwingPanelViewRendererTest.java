package dev.turboism.ui.panel;

import dev.turboism.sdk.action.UiActionEvent;
import dev.turboism.sdk.ui.PanelView;
import org.junit.jupiter.api.Test;

import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JCheckBox;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
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
            final JLabel note = assertInstanceOf(JLabel.class, content.getComponent(2));
            assertTrue(note.getText().startsWith("<html>") && note.getText().endsWith("</html>"), note.getText());
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

    @Test
    void singleChartSectionSuppressesTheDuplicatedInnerTitle() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JComponent rendered = SwingPanelViewRenderer.render(
                PanelView.collapsibleSection(
                    "CPU",
                    true,
                    PanelView.chart("cpu", "CPU",
                        PanelView.series("CPU %", 120, "%", "0.0"))
                ),
                (action, event) -> { }
            );
            JPanel section = assertInstanceOf(JPanel.class, rendered);
            assertTrue(CollapsibleSection.isExpanded(section));
            JPanel content = (JPanel) section.getComponent(0);
            ChartComponent chart = assertInstanceOf(ChartComponent.class, content.getComponent(0));
            assertEquals("cpu", chart.getName());
            assertTrue(!chart.showsTitle(),
                "the section border title replaces the chart's own title");
        });
    }

    @Test
    void bareChartKeepsItsOwnTitleEvenInsideAMultiChildSection() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JComponent rendered = SwingPanelViewRenderer.render(
                PanelView.collapsibleSection(
                    "Stats",
                    true,
                    PanelView.chart("fps", "Viewport Render FPS",
                        PanelView.series("FPS", 120, "fps", "0.0")),
                    PanelView.text("note")
                ),
                (action, event) -> { }
            );
            JPanel section = assertInstanceOf(JPanel.class, rendered);
            JPanel content = (JPanel) section.getComponent(0);
            ChartComponent chart = assertInstanceOf(ChartComponent.class, content.getComponent(0));
            assertTrue(chart.showsTitle(),
                "only a single-chart section defers the chart title to the border");
        });
    }

@Test
    void verticalColumnStretchesChildrenToWidthAndFillsScrollHeight() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JComponent rendered = SwingPanelViewRenderer.render(
                PanelView.column(
                    PanelView.textCentered("3 entries"),
                    PanelView.separator(),
                    PanelView.scroll(PanelView.column(
                        PanelView.toggle("t0", "entry 0", true, false, "a0"),
                        PanelView.toggle("t1", "entry 1", false, true, "a1")
                    ))
                ),
                (action, event) -> { }
            );
            JPanel column = assertInstanceOf(JPanel.class, rendered);
            assertInstanceOf(VerticalFillLayout.class, column.getLayout());
            column.setSize(320, 240);
            column.doLayout();

            // Full-width header: the count label stretches to the panel width so
            // the centered text aligns across the whole panel, not its preferred
            // (narrow) width.
            JLabel header = assertInstanceOf(JLabel.class, column.getComponent(0));
            assertTrue(header.getText().contains("text-align:center"), header.getText());
            assertEquals(320, header.getWidth());

            // Width-filling children: every non-scroll row spans the panel width.
            JSeparator separator = assertInstanceOf(JSeparator.class, column.getComponent(2));
            assertEquals(320, separator.getWidth());

            // Scroll fill: the trailing scroll pane receives the remaining height
            // instead of growing to its preferred (tall) height, so long lists
            // scroll inside the panel.
            JScrollPane scroll = assertInstanceOf(JScrollPane.class, column.getComponent(4));
            assertEquals(320, scroll.getWidth());
            assertEquals(240, scroll.getY() + scroll.getHeight());
            assertEquals(Integer.MAX_VALUE, scroll.getMaximumSize().height);
            assertEquals(
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER,
                scroll.getHorizontalScrollBarPolicy()
            );
            assertEquals(
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                scroll.getVerticalScrollBarPolicy()
            );
        });
    }

    @Test
    void centeredCountTextRendersAsCenteredHtmlLabel() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JComponent rendered = SwingPanelViewRenderer.render(
                PanelView.textCentered("3 entries"),
                (action, event) -> { }
            );
            JLabel label = assertInstanceOf(JLabel.class, rendered);
            assertTrue(label.getText().startsWith("<html>"), label.getText());
            assertTrue(label.getText().contains("text-align:center"), label.getText());
            assertTrue(label.getText().contains("3 entries"), label.getText());
        });
    }

    @Test
    void toggleRowsRenderAsHtmlLabelsWithoutFixedHeightCap() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JComponent rendered = SwingPanelViewRenderer.render(
                PanelView.column(
                    PanelView.toggle("t0", "entry 0", true, false, "a0"),
                    PanelView.toggle("t1", "entry 1", false, true, "a1")
                ),
                (action, event) -> { }
            );
            JPanel column = assertInstanceOf(JPanel.class, rendered);
            // Toggle labels are HTML so long text wraps at the available
            // width; the height is not capped at a fixed 18px so wrapped
            // multi-line entries keep their full height.
            JCheckBox first = assertInstanceOf(JCheckBox.class, column.getComponent(0));
            assertTrue(first.getText().startsWith("<html>"), first.getText());
            assertTrue(first.getMaximumSize().height > 18,
                "fixed height cap must not clip wrapped entries, max=" + first.getMaximumSize().height);
            assertTrue(first.getMaximumSize().height < Integer.MAX_VALUE / 2,
                "natural single-line max, max=" + first.getMaximumSize().height);
            JCheckBox second = assertInstanceOf(JCheckBox.class, column.getComponent(2));
            assertTrue(second.getText().startsWith("<html>"), second.getText());
        });
    }

    @Test
    void historyEntryLongLabelAndDetailWrapInsideNarrowViewport() throws Exception {
        // FR-003 regression: a history entry whose label and structured detail
        // together exceed the floating panel width must wrap inside the
        // viewport instead of clipping (no horizontal scrollbar exists).
        SwingUtilities.invokeAndWait(() -> {
            final String longLabel =
                "12 Set Parameter Value on a very long parameter name that cannot fit on one 180px line";
            final String longDetail =
                "ParamAngleX value: -19.8 → -4.199999 (SET_PARAMETER_VALUE, FULL) — structured detail "
                + "that also needs wrapping inside the same 180px viewport";
            final JComponent rendered = SwingPanelViewRenderer.render(
                PanelView.scroll(PanelView.column(
                    PanelView.textCentered("3 entries"),
                    PanelView.separator(),
                    PanelView.toggle(
                        "history.entry.toggle.0",
                        longLabel + "  —  " + longDetail,
                        true,
                        false,
                        "history.entry.move.0"
                    )
                )),
                (action, event) -> { }
            );
            final JScrollPane scroll = assertInstanceOf(JScrollPane.class, rendered);
            scroll.setSize(180, 300);
            // Drive the real layout chain like a window's validate cascade
            // (scrollpane → viewport → view), twice so the view height
            // converges to the wrapped content height.
            layoutChain(scroll);
            layoutChain(scroll);
            final JComponent view = assertInstanceOf(JComponent.class, scroll.getViewport().getView());
            // The view conforms to the 180px viewport width: no horizontal overflow.
            assertTrue(view.getWidth() <= 180, "view width " + view.getWidth());
            // The entry stays within the available bounds.
            final JCheckBox toggle = assertInstanceOf(JCheckBox.class, named(view, "history.entry.toggle.0"));
            assertTrue(toggle.getX() + toggle.getWidth() <= 180,
                "entry overflows viewport: x=" + toggle.getX() + " width=" + toggle.getWidth());
            // Wrapped content receives multi-line height (a single line is ~23px).
            assertTrue(toggle.getHeight() > 25,
                "entry must wrap to multiple lines, height=" + toggle.getHeight());
            // The view height follows the wrapped content, so vertical
            // scrolling reaches the whole entry.
            assertTrue(view.getHeight() >= toggle.getY() + toggle.getHeight(),
                "view height " + view.getHeight() + " clips entry ending at "
                + (toggle.getY() + toggle.getHeight()));
            // The laid-out checkbox preferred width (including Swing checkbox
            // chrome: icon, insets, gap) fits its actual width — the HTML text
            // is budgeted for the component width minus that overhead, so the
            // right edge cannot clip.
            assertTrue(toggle.getPreferredSize().width <= toggle.getWidth(),
                "checkbox preferred width " + toggle.getPreferredSize().width
                + " exceeds actual width " + toggle.getWidth());
        });
    }

    private static void layoutChain(final JScrollPane scroll) {
        scroll.doLayout();
        scroll.getViewport().doLayout();
        JComponent view = (JComponent) scroll.getViewport().getView();
        view.doLayout();
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
