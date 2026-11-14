package dev.turboism.ui.panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.action.UiActionEvent;
import dev.turboism.sdk.ui.PanelView;
import dev.turboism.sdk.ui.UiInlineLabel;
import dev.turboism.sdk.ui.resource.CubismIcon;
import dev.turboism.sdk.ui.resource.UiIconRef;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.awt.image.MultiResolutionImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.Action;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

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
                        "profile.mode.changed"),
                PanelView.toggle("enabled", "Enabled", false, "profile.enabled.changed"));

        SwingUtilities.invokeAndWait(() -> {
            JComponent rendered = SwingPanelViewRenderer.render(view, (action, event) -> {
                actions.add(action);
                events.add(event);
            });
            assertInstanceOf(AbstractButton.class, named(rendered, "run")).doClick();
            JTextField text = assertInstanceOf(JTextField.class, named(rendered, "name"));
            text.setText("Bob");
            text.postActionEvent();
            @SuppressWarnings("unchecked")
            JComboBox<PanelView.Option> select =
                    (JComboBox<PanelView.Option>) assertInstanceOf(JComboBox.class, named(rendered, "mode"));
            select.setSelectedIndex(1);
            assertInstanceOf(AbstractButton.class, named(rendered, "enabled")).doClick();
        });

        assertEquals(
                List.of("profile.run", "profile.name.changed", "profile.mode.changed", "profile.enabled.changed"),
                actions);
        assertEquals(Optional.empty(), events.get(0));
        assertEquals(
                "Bob",
                assertInstanceOf(
                                UiActionEvent.TextValue.class,
                                events.get(1).orElseThrow().value())
                        .value());
        assertEquals(
                "safe",
                assertInstanceOf(
                                UiActionEvent.SelectionValue.class,
                                events.get(2).orElseThrow().value())
                        .value());
        assertEquals(
                true,
                assertInstanceOf(
                                UiActionEvent.ToggleValue.class,
                                events.get(3).orElseThrow().value())
                        .value());
    }

    @Test
    void rendersCollapsibleSectionWithBorderChildrenAndToggleClick() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JComponent rendered = SwingPanelViewRenderer.render(
                    PanelView.collapsibleSection(
                            "标题", true, PanelView.button("run", "Run", "profile.run"), PanelView.text("note")),
                    (action, event) -> {});

            JPanel section = assertInstanceOf(JPanel.class, rendered);
            CollapsibleSection.CollapsibleTitledBorder border =
                    assertInstanceOf(CollapsibleSection.CollapsibleTitledBorder.class, section.getBorder());

            JPanel content = (JPanel) section.getComponent(0);
            assertInstanceOf(AbstractButton.class, content.getComponent(0));
            assertEquals("run", content.getComponent(0).getName());
            final JLabel note = assertInstanceOf(JLabel.class, content.getComponent(2));
            assertTrue(note.getText().startsWith("<html>") && note.getText().endsWith("</html>"), note.getText());
            assertTrue(CollapsibleSection.isExpanded(section));

            section.setSize(400, 200);
            SwingPanelTestSupport.paint(section);
            Point hotspot = SwingPanelTestSupport.centerOf(border.actionBounds());
            section.dispatchEvent(SwingPanelTestSupport.click(section, hotspot));
            assertFalse(CollapsibleSection.isExpanded(section));

            section.dispatchEvent(SwingPanelTestSupport.click(section, hotspot));
            assertTrue(CollapsibleSection.isExpanded(section));
        });
    }

    @Test
    void rendersCollapsibleSectionCollapsedByDefault() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JComponent rendered = SwingPanelViewRenderer.render(
                    PanelView.collapsibleSection("标题", false, PanelView.text("x")), (action, event) -> {});
            JPanel section = assertInstanceOf(JPanel.class, rendered);
            assertInstanceOf(CollapsibleSection.CollapsibleTitledBorder.class, section.getBorder());
            assertFalse(CollapsibleSection.isExpanded(section));
        });
    }

    @Test
    void singleChartSectionSuppressesTheDuplicatedInnerTitle() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JComponent rendered = SwingPanelViewRenderer.render(
                    PanelView.collapsibleSection(
                            "CPU", true, PanelView.chart("cpu", "CPU", PanelView.series("CPU %", 120, "%", "0.0"))),
                    (action, event) -> {});
            JPanel section = assertInstanceOf(JPanel.class, rendered);
            assertTrue(CollapsibleSection.isExpanded(section));
            JPanel content = (JPanel) section.getComponent(0);
            ChartComponent chart = assertInstanceOf(ChartComponent.class, content.getComponent(0));
            assertEquals("cpu", chart.getName());
            assertTrue(!chart.showsTitle(), "the section border title replaces the chart's own title");
        });
    }

    @Test
    void bareChartKeepsItsOwnTitleEvenInsideAMultiChildSection() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JComponent rendered = SwingPanelViewRenderer.render(
                    PanelView.collapsibleSection(
                            "Stats",
                            true,
                            PanelView.chart("fps", "Viewport Render FPS", PanelView.series("FPS", 120, "fps", "0.0")),
                            PanelView.text("note")),
                    (action, event) -> {});
            JPanel section = assertInstanceOf(JPanel.class, rendered);
            JPanel content = (JPanel) section.getComponent(0);
            ChartComponent chart = assertInstanceOf(ChartComponent.class, content.getComponent(0));
            assertTrue(chart.showsTitle(), "only a single-chart section defers the chart title to the border");
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
                                    PanelView.toggle("t1", "entry 1", false, true, "a1")))),
                    (action, event) -> {});
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
            assertEquals(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER, scroll.getHorizontalScrollBarPolicy());
            assertEquals(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, scroll.getVerticalScrollBarPolicy());
        });
    }

    @Test
    void centeredCountTextRendersAsCenteredHtmlLabel() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JComponent rendered =
                    SwingPanelViewRenderer.render(PanelView.textCentered("3 entries"), (action, event) -> {});
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
                            PanelView.toggle("t1", "entry 1", false, true, "a1")),
                    (action, event) -> {});
            JPanel column = assertInstanceOf(JPanel.class, rendered);
            // Toggle labels are HTML so long text wraps at the available
            // width; the height is not capped at a fixed 18px so wrapped
            // multi-line entries keep their full height.
            JCheckBox first = assertInstanceOf(JCheckBox.class, column.getComponent(0));
            assertTrue(first.getText().startsWith("<html>"), first.getText());
            assertTrue(
                    first.getMaximumSize().height > 18,
                    "fixed height cap must not clip wrapped entries, max=" + first.getMaximumSize().height);
            assertTrue(
                    first.getMaximumSize().height < Integer.MAX_VALUE / 2,
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
                                    "history.entry.move.0"))),
                    (action, event) -> {});
            final JScrollPane scroll = assertInstanceOf(JScrollPane.class, rendered);
            scroll.setSize(180, 300);
            // Drive the real layout chain like a window's validate cascade
            // (scrollpane → viewport → view), twice so the view height
            // converges to the wrapped content height.
            layoutChain(scroll);
            layoutChain(scroll);
            final JComponent view =
                    assertInstanceOf(JComponent.class, scroll.getViewport().getView());
            // The view conforms to the 180px viewport width: no horizontal overflow.
            assertTrue(view.getWidth() <= 180, "view width " + view.getWidth());
            // The entry stays within the available bounds.
            final JCheckBox toggle = assertInstanceOf(JCheckBox.class, named(view, "history.entry.toggle.0"));
            assertTrue(
                    toggle.getX() + toggle.getWidth() <= 180,
                    "entry overflows viewport: x=" + toggle.getX() + " width=" + toggle.getWidth());
            // Wrapped content receives multi-line height (a single line is ~23px).
            assertTrue(toggle.getHeight() > 25, "entry must wrap to multiple lines, height=" + toggle.getHeight());
            // The view height follows the wrapped content, so vertical
            // scrolling reaches the whole entry.
            assertTrue(
                    view.getHeight() >= toggle.getY() + toggle.getHeight(),
                    "view height " + view.getHeight() + " clips entry ending at "
                            + (toggle.getY() + toggle.getHeight()));
            // The laid-out checkbox preferred width (including Swing checkbox
            // chrome: icon, insets, gap) fits its actual width — the HTML text
            // is budgeted for the component width minus that overhead, so the
            // right edge cannot clip.
            assertTrue(
                    toggle.getPreferredSize().width <= toggle.getWidth(),
                    "checkbox preferred width " + toggle.getPreferredSize().width + " exceeds actual width "
                            + toggle.getWidth());
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
                UiInlineLabel.textRun(" 左眼皮"));

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
                            }));
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
                    box,
                    MouseEvent.MOUSE_PRESSED,
                    System.currentTimeMillis(),
                    0,
                    rightEdge,
                    box.getHeight() / 2,
                    1,
                    false,
                    MouseEvent.BUTTON1));
            box.dispatchEvent(new MouseEvent(
                    box,
                    MouseEvent.MOUSE_RELEASED,
                    System.currentTimeMillis(),
                    0,
                    rightEdge,
                    box.getHeight() / 2,
                    1,
                    false,
                    MouseEvent.BUTTON1));
            assertTrue(actions.size() > beforeMouse, "the whole checkbox row must be clickable");
            assertTrue(box.isSelected());

            final int beforeKeyboard = actions.size();
            invokeSpace(box);
            assertTrue(actions.size() > beforeKeyboard, "Space must use the standard checkbox action");
            assertFalse(box.isSelected());
        });

        assertEquals("history.move", actions.get(0));
        assertTrue(assertInstanceOf(
                        UiActionEvent.ToggleValue.class,
                        events.get(0).orElseThrow().value())
                .value());
        assertEquals("history.move", actions.get(1));
        assertFalse(assertInstanceOf(
                        UiActionEvent.ToggleValue.class,
                        events.get(1).orElseThrow().value())
                .value());
    }

    @Test
    void missingInlineIconUsesLiteralFallbackTextAndKeepsAccessibleName() throws Exception {
        final UiInlineLabel label = UiInlineLabel.of(
                UiInlineLabel.textRun("<Move> \"quoted\"\n"),
                UiInlineLabel.iconRun(new UiIconRef(CubismIcon.WARP_DEFORMER), "曲面变形器"),
                UiInlineLabel.textRun(" <target>"));

        SwingUtilities.invokeAndWait(() -> {
            final InlineLabelCheckBox box = assertInstanceOf(
                    InlineLabelCheckBox.class,
                    SwingPanelViewRenderer.render(
                            PanelView.toggle("fallback", label, false, "history.fallback"), (action, event) -> {}));
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
        final UiInlineLabel label = UiInlineLabel.icon(new UiIconRef(CubismIcon.ROTATION_DEFORMER), "旋转变形器");

        SwingUtilities.invokeAndWait(() -> {
            final InlineLabelCheckBox box = assertInstanceOf(
                    InlineLabelCheckBox.class,
                    SwingPanelViewRenderer.render(
                            PanelView.toggle("redo", label, true, true, "history.redo"),
                            (action, event) -> {},
                            (reference, disabled) -> {
                                disabledPresentation.add(disabled);
                                return Optional.of(icon);
                            }));
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
                UiInlineLabel.iconRun(new UiIconRef(CubismIcon.ART_MESH), "图形网格"),
                UiInlineLabel.textRun(" very-long-target-name"));

        SwingUtilities.invokeAndWait(() -> {
            final JScrollPane scroll = assertInstanceOf(
                    JScrollPane.class,
                    SwingPanelViewRenderer.render(
                            PanelView.scroll(PanelView.column(
                                    PanelView.toggle("nested", label, false, "history.nested"),
                                    PanelView.text("tail"))),
                            (action, event) -> {},
                            (reference, disabled) -> {
                                references.add(reference);
                                return Optional.empty();
                            }));
            scroll.setSize(180, 160);
            layoutChain(scroll);
            layoutChain(scroll);

            final InlineLabelCheckBox box = assertInstanceOf(InlineLabelCheckBox.class, named(scroll, "nested"));
            assertEquals(List.of(new UiIconRef(CubismIcon.ART_MESH)), references);
            assertTrue(box.getWidth() <= scroll.getViewport().getWidth());
            assertTrue(box.getX() + box.getWidth() <= scroll.getViewport().getWidth());
            assertTrue(box.getHeight() > 25, "narrow nested rows should wrap rather than clip");
        });
    }

    @Test
    void shapesCombiningEmojiAndMixedScriptTextWithoutSplittingLabelRuns() throws Exception {
        final UiIconRef iconReference = new UiIconRef(CubismIcon.ART_MESH);
        final RecordingIcon icon = new RecordingIcon();
        final UiInlineLabel label = UiInlineLabel.of(
                UiInlineLabel.textRun("Move e\u0301 "),
                UiInlineLabel.iconRun(iconReference, "图标"),
                UiInlineLabel.textRun(" 👩‍🎨 سلام 中文\nNext e\u0301 👩‍🎨"));

        SwingUtilities.invokeAndWait(() -> {
            final InlineLabelCheckBox box = assertInstanceOf(
                    InlineLabelCheckBox.class,
                    SwingPanelViewRenderer.render(
                            PanelView.toggle("shaped", label, false, "history.shaped"),
                            (action, event) -> {},
                            (reference, disabled) -> Optional.of(icon)));
            assertEquals(label.fallbackText(), box.accessibleLabel());
            assertEquals(1, box.resolvedIconCount());
            box.setSize(160, 160);
            assertTrue(box.getPreferredSize().height > 25, "mixed text and newline must wrap safely");
            paintComponent(box);
            assertTrue(icon.paintCount.get() > 0, "the shaped line must retain the inline icon");
        });
    }

    @Test
    void refreshesIconPresentationAfterResolverBecomesUnavailableAndKeepsAction() throws Exception {
        final UiIconRef iconReference = new UiIconRef(CubismIcon.ART_MESH);
        final RecordingIcon icon = new RecordingIcon();
        final AtomicReference<Optional<Icon>> presentation = new AtomicReference<>(Optional.of(icon));
        final AtomicInteger resolverCalls = new AtomicInteger();
        final List<String> actions = new ArrayList<>();
        final UiInlineLabel label = UiInlineLabel.icon(iconReference, "图形网格");

        SwingUtilities.invokeAndWait(() -> {
            final InlineLabelCheckBox box = assertInstanceOf(
                    InlineLabelCheckBox.class,
                    SwingPanelViewRenderer.render(
                            PanelView.toggle("refresh", label, false, "history.refresh"),
                            (action, event) -> actions.add(action),
                            (reference, disabled) -> {
                                resolverCalls.incrementAndGet();
                                return presentation.get();
                            }));
            assertEquals(1, box.resolvedIconCount());
            box.setSize(180, 40);
            paintComponent(box);
            assertTrue(icon.paintCount.get() > 0, "the initial presentation should paint the resolved icon");
            final int iconPaintsBeforeFallback = icon.paintCount.get();
            presentation.set(Optional.empty());
            SwingPanelViewRenderer.refreshInlineLabelPresentation(box);
            assertEquals(0, box.resolvedIconCount());
            assertTrue(box.renderedFallbackText().contains("图形网格"));
            paintComponent(box);
            assertEquals(
                    iconPaintsBeforeFallback, icon.paintCount.get(), "stale icons must not be painted after refresh");
            box.doClick();
        });

        assertTrue(resolverCalls.get() >= 2, "refresh must query the current presentation state");
        assertEquals(List.of("history.refresh"), actions);
    }

    @Test
    void paintsExplicitFullRowFocusIndicatorUnderTheSupportedCheckboxLaf() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final InlineLabelCheckBox box = assertInstanceOf(
                    InlineLabelCheckBox.class,
                    SwingPanelViewRenderer.render(
                            PanelView.toggle("focus", UiInlineLabel.text("Focus target"), false, "history.focus"),
                            (action, event) -> {}));
            assertNotNull(box.getUI());
            assertTrue(box.isFocusPainted());
            box.setSize(180, 36);
            final BufferedImage image = new BufferedImage(180, 36, BufferedImage.TYPE_INT_ARGB);
            final Graphics2D graphics = image.createGraphics();
            box.paintFocusIndicatorForTest(graphics);
            graphics.dispose();

            boolean painted = false;
            for (int x = 0; x < image.getWidth() && !painted; x++) {
                for (int y = 0; y < image.getHeight(); y++) {
                    if ((image.getRGB(x, y) >>> 24) != 0) {
                        painted = true;
                        break;
                    }
                }
            }
            assertTrue(painted, "focused inline label must paint a visible row indicator");
        });
    }

    private static void invokeSpace(final AbstractButton button) {
        final KeyStroke pressedStroke = KeyStroke.getKeyStroke("pressed SPACE");
        final Object pressedKey = button.getInputMap(JComponent.WHEN_FOCUSED).get(pressedStroke);
        assertNotNull(pressedKey, "JCheckBox must retain its standard pressed-Space binding");
        final Action pressed = button.getActionMap().get(pressedKey);
        assertNotNull(pressed, "JCheckBox must retain its standard pressed action");
        pressed.actionPerformed(
                new java.awt.event.ActionEvent(button, java.awt.event.ActionEvent.ACTION_PERFORMED, "pressed SPACE"));

        final KeyStroke releasedStroke = KeyStroke.getKeyStroke("released SPACE");
        final Object releasedKey = button.getInputMap(JComponent.WHEN_FOCUSED).get(releasedStroke);
        assertNotNull(releasedKey, "JCheckBox must retain its standard released-Space binding");
        final Action released = button.getActionMap().get(releasedKey);
        assertNotNull(released, "JCheckBox must retain its standard released action");
        released.actionPerformed(
                new java.awt.event.ActionEvent(button, java.awt.event.ActionEvent.ACTION_PERFORMED, "released SPACE"));
    }

    private static void paintComponent(final JComponent component) {
        final BufferedImage image = new BufferedImage(
                Math.max(1, component.getWidth()), Math.max(1, component.getHeight()), BufferedImage.TYPE_INT_ARGB);
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

    /**
     * The strip needs a control that signals meaning by colour, so a coloured node must keep its own
     * pixels. A disabled control keeps the same silhouette and paints the glyph muted, which keeps
     * the row height steady when the control becomes available.
     */
    @Test
    void colorIconButtonKeepsItsOwnColoursAndMutesWhenDisabled() throws Exception {
        final BufferedImage source = new BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                source.setRGB(x, y, 0xFFE5484D);
            }
        }
        final ByteArrayOutputStream png = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(source, "png", png));
        final byte[] glyph = png.toByteArray();

        SwingUtilities.invokeAndWait(() -> {
            final JComponent enabled = SwingPanelViewRenderer.render(
                    PanelView.colorIconButton("stop", glyph, "Stop", "stop", true), (action, event) -> {});
            final JButton enabledButton = assertInstanceOf(JButton.class, named(enabled, "stop"));
            assertTrue(enabledButton.isEnabled(), "an enabled control accepts input");
            final int activeAlpha = paintedAlpha(enabledButton);
            assertEquals(255, activeAlpha, "an enabled control paints the glyph at full opacity");

            final JComponent disabled = SwingPanelViewRenderer.render(
                    PanelView.colorIconButton("stop", glyph, "Stop", "stop", false), (action, event) -> {});
            final JButton disabledButton = assertInstanceOf(JButton.class, named(disabled, "stop"));
            assertFalse(disabledButton.isEnabled(), "a disabled control rejects input");
            assertTrue(paintedAlpha(disabledButton) < activeAlpha, "a disabled control paints the glyph muted");
            assertEquals(
                    enabledButton.getPreferredSize(),
                    disabledButton.getPreferredSize(),
                    "the disabled control keeps the same size");
        });
    }

    private static int paintedAlpha(final JButton button) {
        final BufferedImage target = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        final java.awt.Graphics2D graphics = target.createGraphics();
        try {
            button.getIcon().paintIcon(button, graphics, 0, 0);
        } finally {
            graphics.dispose();
        }
        return (target.getRGB(10, 10) >>> 24) & 0xFF;
    }

    @Test
    void scaledImageKeepsPopupSizeAndCarriesHighResolutionVariant() throws Exception {
        final BufferedImage source = new BufferedImage(213, 300, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                source.setRGB(x, y, x < source.getWidth() / 2 ? 0xFFCC4444 : 0xFF4444CC);
            }
        }
        final ByteArrayOutputStream png = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(source, "png", png));
        final PanelView view = PanelView.column(PanelView.scaledImage(png.toByteArray(), "thumbnail", 150, 150));

        SwingUtilities.invokeAndWait(() -> {
            final JComponent rendered = SwingPanelViewRenderer.render(view, (action, event) -> {});
            final JLabel label = assertInstanceOf(JLabel.class, named(rendered, "panel-image"));
            assertEquals(107, label.getPreferredSize().width);
            assertEquals(150, label.getPreferredSize().height);
            final ImageIcon icon = assertInstanceOf(ImageIcon.class, label.getIcon());
            assertEquals(107, icon.getIconWidth());
            assertEquals(150, icon.getIconHeight());
            final MultiResolutionImage multi = assertInstanceOf(MultiResolutionImage.class, icon.getImage());
            assertEquals(2, multi.getResolutionVariants().size());
            assertTrue(multi.getResolutionVariants().stream()
                    .anyMatch(image -> image.getWidth(null) == 213 && image.getHeight(null) == 300));
        });
    }

    @Test
    void cardDispatchesDoubleClickAndGridRendersColumns() throws Exception {
        final List<String> actions = new ArrayList<>();
        final List<Optional<UiActionEvent>> events = new ArrayList<>();
        final PanelView view = PanelView.grid(
                2,
                PanelView.card("card-1", "open", PanelView.text("One")),
                PanelView.card("card-2", "open", PanelView.text("Two")));

        SwingUtilities.invokeAndWait(() -> {
            final JComponent rendered = SwingPanelViewRenderer.render(view, (action, event) -> {
                actions.add(action);
                events.add(event);
            });
            assertInstanceOf(
                    java.awt.GridLayout.class,
                    assertInstanceOf(JPanel.class, rendered).getLayout());
            final JPanel card = assertInstanceOf(JPanel.class, named(rendered, "card-1"));
            card.dispatchEvent(new MouseEvent(
                    card, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 1, 1, 2, false, MouseEvent.BUTTON1));
            card.dispatchEvent(new MouseEvent(
                    card, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 1, 1, 1, false, MouseEvent.BUTTON1));
        });

        assertEquals(List.of("open"), actions);
        assertEquals("card-1", events.get(0).orElseThrow().sourceId());
        assertEquals(
                "card-1",
                assertInstanceOf(
                                UiActionEvent.TextValue.class,
                                events.get(0).orElseThrow().value())
                        .value());
    }

    @Test
    void centeredBoxUsesItsFixedPreferredSize() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final JComponent rendered = SwingPanelViewRenderer.render(
                    PanelView.centered(PanelView.text("placeholder"), 220, 180), (action, event) -> {});
            final JPanel centered = assertInstanceOf(JPanel.class, rendered);
            assertEquals(220, centered.getPreferredSize().width);
            assertEquals(180, centered.getPreferredSize().height);
            assertEquals(220, centered.getMinimumSize().width);
            assertEquals(180, centered.getMinimumSize().height);
        });
    }

    @Test
    void centeredBoxKeepsALongChildInsideItsFixedCell() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final String status = "Saved " + "x".repeat(400);
            final JComponent rendered = SwingPanelViewRenderer.render(
                    PanelView.centered(PanelView.text(status), 120, 18), (action, event) -> {});
            final JPanel cell = assertInstanceOf(JPanel.class, rendered);
            cell.setSize(120, 18);
            cell.doLayout();
            final Component child = cell.getComponent(0);
            // The reserved cell height, not the platform line height, is the contract here.
            assertTrue(
                    child.getHeight() <= 18,
                    "a long status must not paint outside its reserved cell: " + child.getHeight());
            assertTrue(child.getWidth() <= 120, "the status stays inside the reserved width");
        });
    }

    @Test
    void centeredLinkDispatchesOnLeftClick() throws Exception {
        final List<String> actions = new ArrayList<>();
        final List<Optional<UiActionEvent>> events = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            final JComponent rendered = SwingPanelViewRenderer.render(
                    PanelView.centered(PanelView.column(
                            PanelView.textCentered("No recent projects"),
                            PanelView.link("create", "Create New Project", "board.create"))),
                    (action, event) -> {
                        actions.add(action);
                        events.add(event);
                    });
            assertInstanceOf(
                    java.awt.GridBagLayout.class,
                    assertInstanceOf(JPanel.class, rendered).getLayout());
            final JLabel link = assertInstanceOf(JLabel.class, named(rendered, "create"));
            link.dispatchEvent(new MouseEvent(
                    link, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 1, 1, 1, false, MouseEvent.BUTTON1));
        });

        assertEquals(List.of("board.create"), actions);
        assertEquals("create", events.get(0).orElseThrow().sourceId());
    }

    @Test
    void leftRowKeepsTheRightBlockAfterTheLeftPreferredWidth() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final JComponent rendered = SwingPanelViewRenderer.render(
                    PanelView.leftRow(PanelView.centered(PanelView.text("thumb"), 110, 96), PanelView.text("name")),
                    (action, event) -> {});
            final JPanel row = assertInstanceOf(JPanel.class, rendered);
            row.setSize(400, 96);
            row.doLayout();
            assertEquals(0, row.getComponent(0).getX());
            final JPanel right = assertInstanceOf(JPanel.class, row.getComponent(1));
            assertTrue(right.getX() >= 110);
            right.doLayout();
            final Component text = right.getComponent(0);
            assertTrue(text.getY() > 0);
            assertTrue(text.getY() + text.getHeight() < 96);
        });
    }

    /**
     * A fixed centered cell must center a short child vertically. Filling both axes stretched the
     * child to the cell, and the vertical stack then pinned its content to the top, so the board's
     * "No preview" placeholder rendered at the top of its preview box.
     */
    @Test
    void fixedCenteredCellCentersAShortChildVertically() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final JComponent rendered = SwingPanelViewRenderer.render(
                    PanelView.centered(PanelView.column(PanelView.textCentered("No preview")), 220, 220),
                    (action, event) -> {});
            final JPanel cell = assertInstanceOf(JPanel.class, rendered);
            cell.setSize(220, 220);
            cell.doLayout();
            final Component child = cell.getComponent(0);
            // The natural height comes from the platform font, so compare against the child's own
            // preferred height instead of a hardcoded line height.
            assertEquals(child.getPreferredSize().height, child.getHeight(), "a short child keeps its natural height");
            assertTrue(
                    child.getY() > 80,
                    "the child sits in the vertical middle of the cell, not at its top: y=" + child.getY());
        });
    }

    @Test
    void fixedCenteredCellStillClipsAChildTallerThanTheCell() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final JComponent rendered = SwingPanelViewRenderer.render(
                    PanelView.centered(
                            PanelView.column(PanelView.text("one"), PanelView.text("two"), PanelView.text("three")),
                            120,
                            18),
                    (action, event) -> {});
            final JPanel cell = assertInstanceOf(JPanel.class, rendered);
            cell.setSize(120, 18);
            cell.doLayout();
            final Component child = cell.getComponent(0);
            assertTrue(
                    child.getPreferredSize().height > 18,
                    "the fixture must overflow the cell for this test to mean anything: " + child.getPreferredSize());
            // The contract is that the overflow never grows the row, not that the child fills it
            // exactly: a platform font can leave the child shorter than the reserved cell.
            assertTrue(
                    child.getHeight() <= 18,
                    "an overflowing child must not grow the surrounding row: " + child.getHeight());
            assertTrue(child.getWidth() <= 120, "the overflowing child stays inside the reserved width");
        });
    }

    @Test
    void centeredBoxPinsTheWidthAndLetsTheHeightFollowTheContent() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final JComponent rendered = SwingPanelViewRenderer.render(
                    PanelView.centered(PanelView.column(PanelView.text("name"), PanelView.text("date")), 320, 0),
                    (action, event) -> {});
            final JPanel centered = assertInstanceOf(JPanel.class, rendered);
            assertEquals(320, centered.getPreferredSize().width, "the pinned width survives");
            assertTrue(
                    centered.getPreferredSize().height < 96,
                    "a zero height must follow the content, not inherit the row: " + centered.getPreferredSize());
        });
    }

    @Test
    void listRowCentersThePinnedTextBlockAgainstItsPreview() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final JComponent rendered = SwingPanelViewRenderer.render(
                    PanelView.leftRow(
                            PanelView.centered(PanelView.text("thumb"), 110, 96),
                            PanelView.centered(
                                    PanelView.column(PanelView.text("name"), PanelView.text("date")), 320, 0)),
                    (action, event) -> {});
            final JPanel row = assertInstanceOf(JPanel.class, rendered);
            row.setSize(500, 96);
            row.doLayout();
            final JPanel right = assertInstanceOf(JPanel.class, row.getComponent(1));
            right.doLayout();
            final JPanel block = assertInstanceOf(JPanel.class, right.getComponent(0));
            assertEquals(320, block.getWidth(), "the text block keeps its pinned width");
            assertTrue(block.getY() > 0, "the text block is centered, not top-aligned: y=" + block.getY());
            assertTrue(block.getY() + block.getHeight() < 96, "the text block stays inside the row");
        });
    }

    /**
     * A card is covered edge to edge by its own children, and a container only receives a
     * mouse-entered event for pixels no child covers. Hover was installed on the card panel alone,
     * so the board showed no feedback anywhere except its one-pixel outline.
     */
    @Test
    void cardHoverReachesTheCardThroughItsChildren() throws Exception {
        final java.util.concurrent.atomic.AtomicReference<JComponent> rendered =
                new java.util.concurrent.atomic.AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> rendered.set(SwingPanelViewRenderer.render(
                PanelView.card("card", "open", PanelView.text("recent-project")), (action, event) -> {})));
        final JComponent card = rendered.get();
        SwingUtilities.invokeAndWait(() -> {
            card.setSize(240, 80);
            card.doLayout();
        });
        final BufferedImage before = SwingPanelTestSupport.paintComponent(card);

        SwingUtilities.invokeAndWait(() -> {
            final Component child = deepest(card);
            child.dispatchEvent(
                    new MouseEvent(child, MouseEvent.MOUSE_ENTERED, System.currentTimeMillis(), 0, 1, 1, 0, false));
        });
        // The highlight is coalesced onto the next dispatch cycle, so flush it first.
        SwingUtilities.invokeAndWait(() -> {});
        final BufferedImage hovered = SwingPanelTestSupport.paintComponent(card);

        assertFalse(
                java.util.Arrays.equals(pixels(before), pixels(hovered)),
                "a hovered card must paint a visible state change");
    }

    private static Component deepest(final Component component) {
        if (component instanceof Container container && container.getComponentCount() > 0) {
            return deepest(container.getComponent(container.getComponentCount() - 1));
        }
        return component;
    }

    private static int[] pixels(final BufferedImage image) {
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }

    @Test
    void scaledTextUsesALargerFont() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final JComponent rendered = SwingPanelViewRenderer.render(
                    PanelView.column(
                            PanelView.text("normal"),
                            PanelView.text("large", false, 1.25f),
                            PanelView.textCentered("centered", true, 1.5f)),
                    (action, event) -> {});
            final JPanel column = assertInstanceOf(JPanel.class, rendered);
            final JLabel normal = assertInstanceOf(JLabel.class, column.getComponent(0));
            final JLabel large = assertInstanceOf(JLabel.class, column.getComponent(2));
            final JLabel centered = assertInstanceOf(JLabel.class, column.getComponent(4));
            assertTrue(large.getFont().getSize2D() > normal.getFont().getSize2D());
            assertTrue(centered.getFont().getSize2D() > normal.getFont().getSize2D());
        });
    }

    @Test
    void spaceBetweenPinsTheButtonToTheRightEdge() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final JComponent rendered = SwingPanelViewRenderer.render(
                    PanelView.spaceBetween(
                            PanelView.text("Recent Files"), PanelView.button("layout", "Layout: 6", "board.layout")),
                    (action, event) -> {});
            final JPanel between = assertInstanceOf(JPanel.class, rendered);
            between.setSize(400, 60);
            between.doLayout();
            assertEquals(0, between.getComponent(0).getX());
            final AbstractButton button = assertInstanceOf(AbstractButton.class, named(rendered, "layout"));
            assertEquals(400, button.getX() + button.getWidth());
        });
    }

    /**
     * A header inside the scroll region shifts whenever the vertical scrollbar appears: the
     * viewport narrows by the scrollbar width and the right-aligned control moves left. A board
     * keeps its header outside the scroll view, so the control must not move.
     */
    @Test
    void headerOutsideTheScrollRegionKeepsTheControlInPlace() throws Exception {
        final byte[] png = java.util.Base64.getDecoder()
                .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
        final List<Integer> rightEdges = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            for (int count : new int[] {1, 60}) {
                final PanelView[] cards = new PanelView[count];
                for (int index = 0; index < count; index++) {
                    cards[index] = PanelView.card("c" + index, "open", PanelView.text("card " + index));
                }
                final JComponent root = SwingPanelViewRenderer.render(
                        PanelView.column(
                                PanelView.spaceBetween(
                                        PanelView.text("Recent Files"),
                                        PanelView.iconButton("layout", png, "Layout", "board.layout")),
                                PanelView.scroll(PanelView.grid(6, cards))),
                        (action, event) -> {});
                root.setSize(900, 400);
                layoutDeep(root);
                final Component button = named(root, "layout");
                final java.awt.Point origin =
                        SwingUtilities.convertPoint(button.getParent(), button.getX(), button.getY(), root);
                rightEdges.add(origin.x + button.getWidth());
            }
        });
        assertEquals(2, rightEdges.size());
        assertEquals(rightEdges.get(0), rightEdges.get(1), "the control must not shift when the scrollbar appears");
    }

    private static void layoutDeep(final Component component) {
        if (component instanceof Container container) {
            container.doLayout();
            for (Component child : container.getComponents()) {
                layoutDeep(child);
            }
        }
    }

    @Test
    void iconButtonRendersAnIconWithoutVisibleTextAndDispatchesSourceIdentity() throws Exception {
        final byte[] png = java.util.Base64.getDecoder()
                .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
        final List<String> actions = new ArrayList<>();
        final List<Optional<UiActionEvent>> events = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            final JComponent rendered = SwingPanelViewRenderer.render(
                    PanelView.card(
                            "recent-1",
                            "board.open",
                            PanelView.iconButton("recent-1", png, "Delete preview", "board.delete")),
                    (action, event) -> {
                        actions.add(action);
                        events.add(event);
                    });
            final AbstractButton button =
                    assertInstanceOf(AbstractButton.class, ((Container) rendered).getComponent(0));
            assertTrue(button.getText() == null || button.getText().isEmpty());
            assertNotNull(button.getIcon());
            assertEquals("Delete preview", button.getToolTipText());
            button.doClick();
            button.dispatchEvent(new MouseEvent(
                    button,
                    MouseEvent.MOUSE_CLICKED,
                    System.currentTimeMillis(),
                    0,
                    4,
                    4,
                    2,
                    false,
                    MouseEvent.BUTTON1));
        });
        assertEquals(List.of("board.delete"), actions);
        assertEquals("recent-1", events.get(0).orElseThrow().sourceId());
    }

    @Test
    void paddedAddsAnEmptyBorderAroundTheChild() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final JComponent rendered = SwingPanelViewRenderer.render(
                    PanelView.padded(PanelView.text("Title"), 2, 12, 4, 6), (action, event) -> {});
            final java.awt.Insets insets = rendered.getInsets();
            assertEquals(2, insets.top);
            assertEquals(12, insets.left);
            assertEquals(4, insets.bottom);
            assertEquals(6, insets.right);
        });
    }

    @Test
    void layoutChangesRetainTheHeaderAndItsActionControl() throws Exception {
        final byte[] png = java.util.Base64.getDecoder()
                .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
        final List<String> actions = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            final PanelView header = PanelView.spaceBetween(
                    PanelView.text("Recent Files"), PanelView.iconButton("layout", png, "Six columns", "board.layout"));
            final JComponent root = SwingPanelViewRenderer.render(
                    PanelView.scroll(PanelView.column(header, PanelView.grid(6, PanelView.text("Project")))),
                    (action, event) -> actions.add(action));
            final JComponent column =
                    (JComponent) ((JScrollPane) root).getViewport().getView();
            final Component oldHeader = column.getComponent(0);
            final AbstractButton button = (AbstractButton) named(root, "layout");
            final PanelView updatedHeader = PanelView.spaceBetween(
                    PanelView.text("Recent Files"),
                    PanelView.iconButton("layout", png, "Three columns", "board.layout"));
            final JComponent updated = SwingPanelViewRenderer.update(
                    root,
                    PanelView.scroll(PanelView.column(updatedHeader, PanelView.grid(3, PanelView.text("Project")))),
                    (action, event) -> actions.add(action),
                    java.util.Locale.ENGLISH);
            assertSame(root, updated);
            assertSame(column, ((JScrollPane) updated).getViewport().getView());
            assertSame(oldHeader, column.getComponent(0));
            assertSame(button, named(updated, "layout"));
            assertEquals("Three columns", button.getToolTipText());
            button.doClick();
        });
        assertEquals(List.of("board.layout"), actions);
    }

    @Test
    void viewportOverlayFollowsTheNativeLayoutBeforeReturningToTheEventQueue() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final JPanel frame = new JPanel(null);
            final javax.swing.JLayeredPane layers = new javax.swing.JLayeredPane();
            final JPanel nativeParent = new JPanel(new java.awt.BorderLayout());
            final JPanel viewport = new JPanel();
            frame.add(layers);
            layers.setBounds(0, 0, 900, 700);
            layers.add(nativeParent);
            nativeParent.setBounds(20, 30, 700, 500);
            nativeParent.add(viewport);
            nativeParent.doLayout();
            final EmbeddedPanelHostOperations.PanelHandle handle = ViewportPanelInstaller.install(
                    frame, viewport, layers, boardDescriptor(), () -> false, descriptor -> new JPanel());
            try {
                final Component overlay = layers.getComponent(0);
                nativeParent.setBounds(40, 50, 500, 320);
                nativeParent.doLayout();
                assertEquals(new java.awt.Rectangle(40, 50, 500, 320), overlay.getBounds());
            } finally {
                handle.close();
            }
        });
    }

    /**
     * The GL surface does not reach the container edges: the host keeps native rails beside it, and
     * those rails paint their own grey that no UI default reaches. The overlay must cover the whole
     * container so the board has no untinted seam.
     */
    @Test
    void viewportOverlayCoversTheNativeRailsBesideTheGlSurface() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final JPanel frame = new JPanel(null);
            final javax.swing.JLayeredPane layers = new javax.swing.JLayeredPane();
            final JPanel container = new JPanel(null);
            final JPanel rail = new JPanel();
            final JPanel viewport = new JPanel();
            frame.add(layers);
            layers.setBounds(0, 0, 900, 700);
            layers.add(container);
            container.setBounds(100, 100, 700, 500);
            rail.setBounds(0, 0, 20, 500);
            viewport.setBounds(20, 0, 680, 500);
            container.add(rail);
            container.add(viewport);
            final EmbeddedPanelHostOperations.PanelHandle handle = ViewportPanelInstaller.install(
                    frame, viewport, layers, boardDescriptor(), () -> false, descriptor -> new JPanel());
            try {
                final Component overlay = layers.getComponent(0);
                assertEquals(
                        new java.awt.Rectangle(100, 100, 700, 500),
                        overlay.getBounds(),
                        "the overlay must reach the rail, not stop at the GL surface");
            } finally {
                handle.close();
            }
        });
    }

    @Test
    void viewportBackgroundAndLinkFollowThemeRefresh() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final Object originalBackground = javax.swing.UIManager.get("Panel.background");
            final Object originalAccent = javax.swing.UIManager.get("Component.accentColor");
            final JPanel frame = new JPanel();
            final javax.swing.JLayeredPane layers = new javax.swing.JLayeredPane();
            final JPanel parent = new JPanel();
            final JPanel viewport = new JPanel();
            parent.add(viewport);
            layers.add(parent);
            final EmbeddedPanelHostOperations.PanelHandle handle = ViewportPanelInstaller.install(
                    frame,
                    viewport,
                    layers,
                    boardDescriptor(),
                    () -> false,
                    descriptor -> SwingPanelViewRenderer.render(
                            PanelView.link("create", "Create", "board.create"), (action, event) -> {}));
            try {
                final JComponent overlay = (JComponent) layers.getComponent(0);
                final Color background = new Color(0x182432);
                final Color accent = new Color(0xAABBCC);
                javax.swing.UIManager.put("Panel.background", background);
                javax.swing.UIManager.put("Component.accentColor", accent);
                SwingUtilities.updateComponentTreeUI(overlay);
                assertEquals(background, overlay.getBackground());
                final JLabel link = (JLabel) named(overlay, "create");
                assertFalse(link.getText().contains("#3B82F6"));
                assertEquals(accent, link.getForeground());
            } finally {
                handle.close();
                javax.swing.UIManager.put("Panel.background", originalBackground);
                javax.swing.UIManager.put("Component.accentColor", originalAccent);
            }
        });
    }

    @Test
    void supersededPanelRegistrationsDoNotRemoveTheCurrentSurface() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final ReplacingPanelRegistry registry = new ReplacingPanelRegistry();
            final AtomicInteger installs = new AtomicInteger();
            final AtomicInteger updates = new AtomicInteger();
            final AtomicInteger closes = new AtomicInteger();
            final java.util.function.Supplier<EmbeddedPanelHostOperations.PanelHandle> installer = () -> {
                installs.incrementAndGet();
                return new EmbeddedPanelHostOperations.PanelHandle() {
                    @Override
                    public void activate() {}

                    @Override
                    public void updateContent(EmbeddedPanelContributionDescriptor descriptor) {
                        updates.incrementAndGet();
                    }

                    @Override
                    public void close() {
                        closes.incrementAndGet();
                    }
                };
            };
            final dev.turboism.sdk.plugin.Registration first = registry.replace(boardDescriptor(), installer);
            final dev.turboism.sdk.plugin.Registration second = registry.replace(boardDescriptor(), installer);
            first.close();
            assertEquals(1, installs.get());
            assertEquals(1, updates.get());
            assertEquals(0, closes.get());
            second.close();
            second.close();
            assertEquals(1, closes.get());
        });
    }

    @Test
    void spaceBetweenLeftRowExpandsUnpinnedTextBlockToAvailableWidth() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final JComponent rendered = SwingPanelViewRenderer.render(
                    PanelView.flatCard(
                            "cube",
                            "board.open",
                            PanelView.spaceBetween(
                                    PanelView.leftRow(
                                            PanelView.centered(PanelView.text("thumb"), 110, 96),
                                            PanelView.padded(
                                                    PanelView.column(
                                                            PanelView.text("cube.cmo3", false, 1.15f),
                                                            PanelView.text("2025-01-22 17:01", true, 1.15f)),
                                                    0,
                                                    16,
                                                    0,
                                                    0)),
                                    PanelView.button("delete", "Delete", "board.delete"))),
                    (action, event) -> {});
            final JPanel card = assertInstanceOf(JPanel.class, rendered);
            final JLabel timestamp = findLabel(card, "2025-01-22 17:01");
            final int naturalWidth = timestamp.getPreferredSize().width;
            final int naturalHeight = timestamp.getPreferredSize().height;
            card.setSize(600, 120);
            layoutDeep(card);
            assertTrue(
                    timestamp.getWidth() > naturalWidth,
                    "timestamp label must expand into the available row width instead of squeezing to preferred width: "
                            + timestamp.getWidth() + " vs " + naturalWidth);
            assertEquals(
                    naturalHeight,
                    timestamp.getHeight(),
                    "timestamp must remain single-line without wrapping or clipping");
        });
    }

    private static JLabel findLabel(final Component component, final String text) {
        if (component instanceof JLabel label
                && label.getText() != null
                && label.getText().contains(text)) {
            return label;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                final JLabel match = findLabelOrNull(child, text);
                if (match != null) {
                    return match;
                }
            }
        }
        throw new AssertionError("label not found: " + text);
    }

    private static JLabel findLabelOrNull(final Component component, final String text) {
        if (component instanceof JLabel label
                && label.getText() != null
                && label.getText().contains(text)) {
            return label;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                final JLabel match = findLabelOrNull(child, text);
                if (match != null) {
                    return match;
                }
            }
        }
        return null;
    }

    private static EmbeddedPanelContributionDescriptor boardDescriptor() {
        return new EmbeddedPanelContributionDescriptor(
                "board-plugin", "board", "Recent", "viewport", 20, PanelView.text("Recent"), false);
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
