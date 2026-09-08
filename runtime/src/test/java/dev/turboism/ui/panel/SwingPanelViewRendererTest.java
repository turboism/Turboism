package dev.turboism.ui.panel;

import dev.turboism.sdk.action.UiActionEvent;
import dev.turboism.sdk.ui.PanelView;
import dev.turboism.sdk.ui.UiInlineLabel;
import dev.turboism.sdk.ui.resource.CubismIcon;
import dev.turboism.sdk.ui.resource.UiIconRef;
import org.junit.jupiter.api.Test;

import javax.swing.AbstractButton;
import javax.swing.Action;
import javax.swing.JComboBox;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JCheckBox;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Color;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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

    @Test
    void rendersInlineIconAsOneFullRowWithResolverAndStandardActions() throws Exception {
        final List<String> actions = new ArrayList<>();
        final List<Optional<UiActionEvent>> events = new ArrayList<>();
        final List<UiIconRef> references = new ArrayList<>();
        final List<Boolean> disabledPresentation = new ArrayList<>();
        final RecordingIcon icon = new RecordingIcon();
        final UiInlineLabel label = UiInlineLabel.of(
            UiInlineLabel.textRun("移动 "),
            UiInlineLabel.iconRun(new UiIconRef(CubismIcon.ART_MESH), "图形网格"),
            UiInlineLabel.textRun(" 左眼皮")
        );

        SwingUtilities.invokeAndWait(() -> {
            final InlineLabelCheckBox box = assertInstanceOf(
                InlineLabelCheckBox.class,
                SwingPanelViewRenderer.render(
                    PanelView.toggle("entry", label, false, false, "history.move"),
                    (action, event) -> {
                        actions.add(action);
                        events.add(event);
                    },
                    (reference, disabled) -> {
                        references.add(reference);
                        disabledPresentation.add(disabled);
                        return Optional.of(icon);
                    }
                )
            );
            assertEquals("entry", box.getName());
            assertEquals("", box.getText());
            assertFalse(box.isSelected());
            assertTrue(box.isEnabled());
            assertEquals(0, box.getComponentCount(), "label runs must not steal row input");
            assertEquals(label.accessibleText(), box.getAccessibleContext().getAccessibleName());
            assertEquals(List.of(new UiIconRef(CubismIcon.ART_MESH)), references);
            assertEquals(List.of(false), disabledPresentation);

            box.setSize(360, 40);
            paintComponent(box);
            assertTrue(icon.paintCount.get() > 0, "the injected icon should be painted");

            final int beforeMouse = actions.size();
            final int rightEdge = Math.max(1, box.getWidth() - 2);
            box.dispatchEvent(new MouseEvent(
                box, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0,
                rightEdge, box.getHeight() / 2, 1, false, MouseEvent.BUTTON1
            ));
            box.dispatchEvent(new MouseEvent(
                box, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), 0,
                rightEdge, box.getHeight() / 2, 1, false, MouseEvent.BUTTON1
            ));
            assertTrue(actions.size() > beforeMouse, "the whole checkbox row must be clickable");
            assertTrue(box.isSelected());

            final int beforeKeyboard = actions.size();
            invokeSpace(box);
            assertTrue(actions.size() > beforeKeyboard, "Space must use the standard checkbox action");
            assertFalse(box.isSelected());
        });

        assertEquals("history.move", actions.get(0));
        assertTrue(assertInstanceOf(UiActionEvent.ToggleValue.class, events.get(0).orElseThrow().value()).value());
        assertEquals("history.move", actions.get(1));
        assertFalse(assertInstanceOf(UiActionEvent.ToggleValue.class, events.get(1).orElseThrow().value()).value());
    }

    @Test
    void missingInlineIconUsesLiteralFallbackTextAndKeepsAccessibleName() throws Exception {
        final UiInlineLabel label = UiInlineLabel.of(
            UiInlineLabel.textRun("<Move> \"quoted\"\n"),
            UiInlineLabel.iconRun(new UiIconRef(CubismIcon.WARP_DEFORMER), "曲面变形器"),
            UiInlineLabel.textRun(" <target>")
        );

        SwingUtilities.invokeAndWait(() -> {
            final InlineLabelCheckBox box = assertInstanceOf(
                InlineLabelCheckBox.class,
                SwingPanelViewRenderer.render(
                    PanelView.toggle("fallback", label, false, "history.fallback"),
                    (action, event) -> { }
                )
            );
            assertEquals("", box.getText(), "typed labels must not be encoded as plugin HTML");
            assertEquals(label.fallbackText(), box.renderedFallbackText());
            assertEquals(label.accessibleText(), box.getAccessibleContext().getAccessibleName());
            assertTrue(box.renderedFallbackText().contains("曲面变形器"));
            assertTrue(box.renderedFallbackText().contains("<Move> \"quoted\""));

            box.setSize(180, 80);
            paintComponent(box);
            assertTrue(box.getPreferredSize().width <= box.getWidth());
            assertTrue(box.getPreferredSize().height > 25, "the embedded newline should remain visible");
        });
    }

    @Test
    void graySelectedInlineRowRequestsDisabledIconButDoesNotDisableNavigation() throws Exception {
        final List<Boolean> disabledPresentation = new ArrayList<>();
        final RecordingIcon icon = new RecordingIcon();
        final UiInlineLabel label = UiInlineLabel.icon(
            new UiIconRef(CubismIcon.ROTATION_DEFORMER), "旋转变形器"
        );

        SwingUtilities.invokeAndWait(() -> {
            final InlineLabelCheckBox box = assertInstanceOf(
                InlineLabelCheckBox.class,
                SwingPanelViewRenderer.render(
                    PanelView.toggle("redo", label, true, true, "history.redo"),
                    (action, event) -> { },
                    (reference, disabled) -> {
                        disabledPresentation.add(disabled);
                        return Optional.of(icon);
                    }
                )
            );
            assertTrue(box.isSelected());
            assertTrue(box.isEnabled(), "gray redo styling must not disable navigation");
            assertTrue(box.isFocusable());
            assertEquals(List.of(true), disabledPresentation);
            box.setSize(180, 32);
            paintComponent(box);
            assertTrue(icon.paintCount.get() > 0);
        });
    }

    @Test
    void propagatesInlineResolverThroughNestedColumnAndScrollWithoutOverflow() throws Exception {
        final List<UiIconRef> references = new ArrayList<>();
        final UiInlineLabel label = UiInlineLabel.of(
            UiInlineLabel.textRun("移动 "),
            UiInlineLabel.icon(new UiIconRef(CubismIcon.ART_MESH), "图形网格").runs().get(0),
            UiInlineLabel.textRun(" very-long-target-name")
        );

        SwingUtilities.invokeAndWait(() -> {
            final JScrollPane scroll = assertInstanceOf(
                JScrollPane.class,
                SwingPanelViewRenderer.render(
                    PanelView.scroll(PanelView.column(
                        PanelView.toggle("nested", label, false, "history.nested"),
                        PanelView.text("tail")
                    )),
                    (action, event) -> { },
                    (reference, disabled) -> {
                        references.add(reference);
                        return Optional.empty();
                    }
                )
            );
            scroll.setSize(180, 160);
            layoutChain(scroll);
            layoutChain(scroll);

            final InlineLabelCheckBox box = assertInstanceOf(
                InlineLabelCheckBox.class,
                named(scroll, "nested")
            );
            assertEquals(List.of(new UiIconRef(CubismIcon.ART_MESH)), references);
            assertTrue(box.getWidth() <= scroll.getViewport().getWidth());
            assertTrue(box.getX() + box.getWidth() <= scroll.getViewport().getWidth());
            assertTrue(box.getHeight() > 25, "narrow nested rows should wrap rather than clip");
        });
    }

    private static void invokeSpace(final AbstractButton button) {
        final KeyStroke pressedStroke = KeyStroke.getKeyStroke("pressed SPACE");
        final Object pressedKey = button.getInputMap(JComponent.WHEN_FOCUSED).get(pressedStroke);
        assertNotNull(pressedKey, "JCheckBox must retain its standard pressed-Space binding");
        final Action pressed = button.getActionMap().get(pressedKey);
        assertNotNull(pressed, "JCheckBox must retain its standard pressed action");
        pressed.actionPerformed(new java.awt.event.ActionEvent(
            button, java.awt.event.ActionEvent.ACTION_PERFORMED, "pressed SPACE"
        ));

        final KeyStroke releasedStroke = KeyStroke.getKeyStroke("released SPACE");
        final Object releasedKey = button.getInputMap(JComponent.WHEN_FOCUSED).get(releasedStroke);
        assertNotNull(releasedKey, "JCheckBox must retain its standard released-Space binding");
        final Action released = button.getActionMap().get(releasedKey);
        assertNotNull(released, "JCheckBox must retain its standard released action");
        released.actionPerformed(new java.awt.event.ActionEvent(
            button, java.awt.event.ActionEvent.ACTION_PERFORMED, "released SPACE"
        ));
    }

    private static void paintComponent(final JComponent component) {
        final BufferedImage image = new BufferedImage(
            Math.max(1, component.getWidth()),
            Math.max(1, component.getHeight()),
            BufferedImage.TYPE_INT_ARGB
        );
        final Graphics2D graphics = image.createGraphics();
        component.paint(graphics);
        graphics.dispose();
    }

    private static final class RecordingIcon implements Icon {
        private final AtomicInteger paintCount = new AtomicInteger();

        @Override
        public void paintIcon(final Component component, final java.awt.Graphics graphics, final int x, final int y) {
            paintCount.incrementAndGet();
            graphics.setColor(Color.BLUE);
            graphics.fillRect(x, y, getIconWidth(), getIconHeight());
        }

        @Override
        public int getIconWidth() {
            return 32;
        }

        @Override
        public int getIconHeight() {
            return 32;
        }
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
