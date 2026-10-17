package dev.turboism.ui.appearance.control;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.ui.appearance.PaletteRowAction;
import dev.turboism.sdk.ui.resource.CubismIcon;
import dev.turboism.sdk.ui.resource.UiIconRef;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class NativeRowActionTrackerTest {
    @Test
    void clicksAffectOnlyTheOwningVisibleRowIconAndRestoreNativeDecoration() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var coordinator = coordinator();
            var tracker = new NativeRowActionTracker();
            var first = label(true);
            var second = label(true);
            var hidden = label(false);
            Icon folder = new Square(Color.BLUE, 8);
            first.setIcon(folder);
            first.setToolTipText("native");
            first.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
            first.setHorizontalTextPosition(javax.swing.SwingConstants.RIGHT);
            AtomicInteger a = new AtomicInteger();
            AtomicInteger b = new AtomicInteger();
            first.setIcon(new Square(Color.BLUE, 26));
            var originalTextBounds = textBounds(first);
            first.setIcon(folder);
            tracker.apply(first, Optional.of(action(PaletteRowAction.State.LOCKED, a)), coordinator);
            tracker.apply(second, Optional.of(action(PaletteRowAction.State.UNLOCKED, b)), coordinator);
            tracker.apply(hidden, Optional.of(action(PaletteRowAction.State.LOCKED, a)), coordinator);
            assertEquals(javax.swing.SwingConstants.CENTER, first.getHorizontalAlignment());
            assertEquals(javax.swing.SwingConstants.RIGHT, first.getHorizontalTextPosition());
            assertEquals(originalTextBounds, textBounds(first));
            click(first, 4);
            click(first, 60);
            click(hidden, 4);
            assertEquals(1, a.get());
            assertEquals(0, b.get());
            click(second, 4);
            assertEquals(1, b.get());
            assertEquals(8, first.getIcon().getIconWidth());
            tracker.restoreAll();
            assertSame(folder, first.getIcon());
            assertEquals("native", first.getToolTipText());
            assertEquals(javax.swing.SwingConstants.CENTER, first.getHorizontalAlignment());
            assertEquals(javax.swing.SwingConstants.RIGHT, first.getHorizontalTextPosition());
            assertNull(first.getClientProperty(NativeRowActionTracker.MARKER));
            click(first, 4);
            assertEquals(1, a.get());
        });
    }

    @Test
    void independentLockSlotPreservesExistingNameBoundsAndPreferredSize() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (int alignment : new int[] {JLabel.LEFT, JLabel.CENTER, JLabel.RIGHT}) {
                var tracker = new NativeRowActionTracker();
                var label = label(true);
                label.setHorizontalAlignment(alignment);
                var nativeBounds = textBounds(label);
                label.setIcon(new Square(Color.BLACK, 16));
                var bounds = textBounds(label);
                var preferred = label.getPreferredSize();
                label.setIcon(null);
                int gap = label.getIconTextGap();
                tracker.apply(
                        label, Optional.of(action(PaletteRowAction.State.LOCKED, new AtomicInteger())), coordinator());
                assertEquals(alignment, label.getHorizontalAlignment());
                assertEquals(bounds, textBounds(label));
                assertEquals(preferred, label.getPreferredSize());
                assertEquals(Color.RED.getRGB(), pixel(label));
                tracker.restoreAll();
                assertEquals(nativeBounds, textBounds(label));
                assertEquals(gap, label.getIconTextGap());
            }
        });
    }

    @Test
    void lockedNameUsesThemeDisabledColorAndUnlockRestoresOriginal() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var tracker = new NativeRowActionTracker();
            var label = label(true);
            label.setForeground(Color.BLUE);
            var originalBorder = label.getBorder();
            var count = new AtomicInteger();
            tracker.apply(label, Optional.of(action(PaletteRowAction.State.LOCKED, count)), coordinator());
            var expected = javax.swing.UIManager.getColor("Label.disabledForeground");
            assertEquals(expected == null ? Color.GRAY : expected, label.getForeground());
            assertTrue(label.isEnabled());
            click(label, 4);
            assertEquals(1, count.get());
            tracker.apply(label, Optional.of(action(PaletteRowAction.State.UNLOCKED, count)), coordinator());
            assertEquals(Color.BLUE, label.getForeground());
            tracker.apply(label, Optional.of(action(PaletteRowAction.State.LOCKED, count)), coordinator());
            tracker.restoreAll();
            assertEquals(Color.BLUE, label.getForeground());
            assertSame(originalBorder, label.getBorder());
        });
    }

    @Test
    void longNamesCannotPaintInsideTheLockColumn() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var tracker = new NativeRowActionTracker();
            var label = label(true);
            label.setText("A very long parameter name that must be clipped");
            label.setSize(80, 20);
            for (int alignment : new int[] {JLabel.LEFT, JLabel.CENTER, JLabel.RIGHT}) {
                label.setHorizontalAlignment(alignment);
                tracker.apply(
                        label,
                        Optional.of(action(PaletteRowAction.State.UNLOCKED, new AtomicInteger())),
                        coordinator());
                assertTrue(textBounds(label).x >= 20);
                assertEquals(20, label.getInsets().left);
                assertEquals(Color.GREEN.getRGB(), pixel(label));
            }
            tracker.restoreAll();
            assertEquals(0, label.getInsets().left);
        });
    }

    @Test
    void rebindingDoesNotStackIconsOrListenersAndPreservesNewNativeIcons() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var tracker = new NativeRowActionTracker();
            var coordinator = coordinator();
            var label = label(true);
            var action = Optional.of(action(PaletteRowAction.State.MIXED, new AtomicInteger()));
            label.setToolTipText("native");
            label.setIcon(new Square(Color.BLACK, 16));
            var originalTextBounds = textBounds(label);
            label.setIcon(null);
            int originalListeners = label.getMouseListeners().length;
            for (int i = 0; i < 20; i++) tracker.apply(label, action, coordinator);
            assertEquals(0, label.getIcon().getIconWidth());
            assertEquals(originalTextBounds, textBounds(label));
            assertEquals(originalListeners + 1, label.getMouseListeners().length);
            Icon newNative = new Square(Color.BLUE, 8);
            label.setIcon(newNative);
            tracker.restoreAll();
            assertSame(newNative, label.getIcon());
            assertEquals(originalListeners, label.getMouseListeners().length);
        });
    }

    @Test
    void lockedMixedAndUnlockedHaveDistinctNativeRendering() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var tracker = new NativeRowActionTracker();
            var coordinator = coordinator();
            var label = label(true);
            for (var state : PaletteRowAction.State.values()) {
                tracker.apply(label, Optional.of(action(state, new AtomicInteger())), coordinator);
                int expected =
                        switch (state) {
                            case LOCKED -> Color.RED.getRGB();
                            case MIXED -> Color.GRAY.getRGB();
                            case UNLOCKED -> Color.GREEN.getRGB();
                        };
                assertEquals(expected, pixel(label));
                if (state == PaletteRowAction.State.UNLOCKED) {
                    var event = new MouseEvent(label, MouseEvent.MOUSE_ENTERED, 0, 0, 4, 8, 0, false);
                    for (var listener : label.getMouseListeners()) listener.mouseEntered(event);
                    assertEquals(Color.GREEN.getRGB(), pixel(label));
                }
            }
            tracker.restoreAll();
        });
    }

    private static PaletteAppearanceCoordinator coordinator() {
        var coordinator = new PaletteAppearanceCoordinator();
        coordinator.bindNativeIconResolver((ref, disabled) -> Optional.of(new Square(
                ref.icon() == CubismIcon.LOCK_PLACEHOLDER ? Color.GREEN : disabled ? Color.GRAY : Color.RED, 16)));
        return coordinator;
    }

    private static PaletteRowAction action(PaletteRowAction.State state, AtomicInteger count) {
        return new PaletteRowAction(new UiIconRef(CubismIcon.LOCK), state, "lock", count::incrementAndGet);
    }

    private static JLabel label(boolean showing) {
        JLabel label = new JLabel("Parameter") {
            @Override
            public boolean isShowing() {
                return showing;
            }
        };
        label.setSize(160, 20);
        return label;
    }

    private static void click(JLabel label, int x) {
        var event = new MouseEvent(label, MouseEvent.MOUSE_CLICKED, 0, 0, x, 8, 1, false, MouseEvent.BUTTON1);
        for (var listener : label.getMouseListeners()) listener.mouseClicked(event);
    }

    private static java.awt.Rectangle textBounds(JLabel label) {
        var bounds = new java.awt.Rectangle();
        SwingUtilities.layoutCompoundLabel(
                label,
                label.getFontMetrics(label.getFont()),
                label.getText(),
                label.getIcon(),
                label.getVerticalAlignment(),
                label.getHorizontalAlignment(),
                label.getVerticalTextPosition(),
                label.getHorizontalTextPosition(),
                new java.awt.Rectangle(
                        label.getInsets().left,
                        label.getInsets().top,
                        label.getWidth() - label.getInsets().left - label.getInsets().right,
                        label.getHeight() - label.getInsets().top - label.getInsets().bottom),
                new java.awt.Rectangle(),
                bounds,
                label.getIconTextGap());
        return bounds;
    }

    private static int pixel(JLabel label) {
        var image = new BufferedImage(label.getWidth(), label.getHeight(), BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        label.paint(graphics);
        graphics.dispose();
        return image.getRGB(4, 4);
    }

    private record Square(Color color, int size) implements Icon {
        @Override
        public int getIconWidth() {
            return size;
        }

        @Override
        public int getIconHeight() {
            return size;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            g.setColor(color);
            g.fillRect(x, y, size, size);
        }
    }
}
