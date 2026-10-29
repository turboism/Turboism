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
            int originalMotionListeners = label.getMouseMotionListeners().length;
            for (int i = 0; i < 20; i++) tracker.apply(label, action, coordinator);
            assertEquals(0, label.getIcon().getIconWidth());
            assertEquals(originalTextBounds, textBounds(label));
            assertEquals(originalListeners + 1, label.getMouseListeners().length);
            assertEquals(originalMotionListeners + 1, label.getMouseMotionListeners().length);
            Icon newNative = new Square(Color.BLUE, 8);
            label.setIcon(newNative);
            tracker.restoreAll();
            assertSame(newNative, label.getIcon());
            assertEquals(originalListeners, label.getMouseListeners().length);
            assertEquals(originalMotionListeners, label.getMouseMotionListeners().length);
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

    @Test
    void clickingUnlockedParameterNameForwardsToOuterRowLayoutWhileLockClickDoesNotSelectRow() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var tracker = new NativeRowActionTracker();
            var coordinator = coordinator();
            var rowLayout = new javax.swing.JPanel(null);
            var layeredWrapper = new javax.swing.JLayeredPane();
            var hbox = new javax.swing.JPanel(null);
            var nameVBox = new javax.swing.JPanel(null);
            var label = label(true);

            rowLayout.setBounds(0, 0, 240, 40);
            layeredWrapper.setBounds(0, 8, 240, 26);
            hbox.setBounds(0, 0, 240, 26);
            nameVBox.setBounds(20, 0, 160, 26);
            label.setBounds(0, 3, 160, 20);

            nameVBox.add(label);
            hbox.add(nameVBox);
            layeredWrapper.add(hbox);
            rowLayout.add(layeredWrapper);

            var lockClicks = new AtomicInteger();
            var selectedOnRelease = new AtomicInteger();
            var pressedOnRowLayout = new AtomicInteger();
            var draggedOnRowLayout = new AtomicInteger();
            var movedOnRowLayout = new AtomicInteger();
            var lastPoint = new java.awt.Point();

            var nativeRowListener = new java.awt.event.MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent event) {
                    pressedOnRowLayout.incrementAndGet();
                    lastPoint.setLocation(event.getPoint());
                }

                @Override
                public void mouseReleased(MouseEvent event) {
                    if (SwingUtilities.isLeftMouseButton(event)) {
                        selectedOnRelease.incrementAndGet();
                        lastPoint.setLocation(event.getPoint());
                    }
                }

                @Override
                public void mouseDragged(MouseEvent event) {
                    draggedOnRowLayout.incrementAndGet();
                    lastPoint.setLocation(event.getPoint());
                }

                @Override
                public void mouseMoved(MouseEvent event) {
                    movedOnRowLayout.incrementAndGet();
                    lastPoint.setLocation(event.getPoint());
                }
            };
            rowLayout.addMouseListener(nativeRowListener);
            rowLayout.addMouseMotionListener(nativeRowListener);

            tracker.apply(label, Optional.of(action(PaletteRowAction.State.UNLOCKED, lockClicks)), coordinator);

            // 1. Clicking the parameter name (x=60, outside the 16px leading lock slot) must reach rowLayout
            // across intermediate listener-less containers (nameVBox -> hbox -> layeredWrapper -> rowLayout)
            // and select the parameter on mouseReleased, without toggling the lock.
            dispatchFullClick(label, 60, 8);
            assertEquals(0, lockClicks.get());
            assertEquals(1, pressedOnRowLayout.get());
            assertEquals(1, selectedOnRelease.get());
            assertEquals(new java.awt.Point(80, 19), lastPoint);

            // 2. Mouse move and drag on the parameter name must also reach rowLayout's MouseMotionListener.
            label.dispatchEvent(new MouseEvent(label, MouseEvent.MOUSE_MOVED, 0, 0, 70, 9, 0, false));
            label.dispatchEvent(new MouseEvent(
                    label, MouseEvent.MOUSE_DRAGGED, 0, java.awt.event.InputEvent.BUTTON1_DOWN_MASK, 75, 10, 0, false));
            assertEquals(1, movedOnRowLayout.get());
            assertEquals(1, draggedOnRowLayout.get());
            assertEquals(new java.awt.Point(95, 21), lastPoint);

            // 3. Clicking the leading lock icon (x=4) must toggle the lock without forwarding press/release/drag
            // to rowLayout, preserving existing editor parameter selection.
            label.dispatchEvent(new MouseEvent(
                    label,
                    MouseEvent.MOUSE_PRESSED,
                    0,
                    java.awt.event.InputEvent.BUTTON1_DOWN_MASK,
                    4,
                    8,
                    1,
                    false,
                    MouseEvent.BUTTON1));
            label.dispatchEvent(new MouseEvent(
                    label, MouseEvent.MOUSE_DRAGGED, 0, java.awt.event.InputEvent.BUTTON1_DOWN_MASK, 5, 8, 0, false));
            label.dispatchEvent(new MouseEvent(
                    label,
                    MouseEvent.MOUSE_RELEASED,
                    0,
                    java.awt.event.InputEvent.BUTTON1_DOWN_MASK,
                    4,
                    8,
                    1,
                    false,
                    MouseEvent.BUTTON1));
            label.dispatchEvent(new MouseEvent(
                    label,
                    MouseEvent.MOUSE_CLICKED,
                    0,
                    java.awt.event.InputEvent.BUTTON1_DOWN_MASK,
                    4,
                    8,
                    1,
                    false,
                    MouseEvent.BUTTON1));
            assertEquals(1, lockClicks.get());
            assertEquals(1, pressedOnRowLayout.get());
            assertEquals(1, selectedOnRelease.get());
            assertEquals(1, draggedOnRowLayout.get());

            tracker.restoreAll();
        });
    }

    private static void dispatchFullClick(JLabel label, int x, int y) {
        int mask = java.awt.event.InputEvent.BUTTON1_DOWN_MASK;
        label.dispatchEvent(
                new MouseEvent(label, MouseEvent.MOUSE_PRESSED, 0, mask, x, y, 1, false, MouseEvent.BUTTON1));
        label.dispatchEvent(
                new MouseEvent(label, MouseEvent.MOUSE_RELEASED, 0, mask, x, y, 1, false, MouseEvent.BUTTON1));
        label.dispatchEvent(
                new MouseEvent(label, MouseEvent.MOUSE_CLICKED, 0, mask, x, y, 1, false, MouseEvent.BUTTON1));
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
