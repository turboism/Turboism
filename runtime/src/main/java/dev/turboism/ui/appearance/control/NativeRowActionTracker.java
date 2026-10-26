package dev.turboism.ui.appearance.control;

import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.sdk.ui.appearance.PaletteRowAction;
import dev.turboism.sdk.ui.resource.CubismIcon;
import dev.turboism.sdk.ui.resource.UiIconRef;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.Border;

/** Restorable native-icon slots on verified parameter labels; owns no model state. */
final class NativeRowActionTracker {
    static final String MARKER = "turboism.paletteRow.leadingAction";
    private final Map<JLabel, Binding> bindings = new WeakHashMap<>();

    void apply(
            final Component component,
            final Optional<PaletteRowAction> action,
            final PaletteAppearanceCoordinator coordinator) {
        if (!(component instanceof JLabel label)) return;
        restore(label);
        if (action.isEmpty()) return;
        final PaletteRowAction value = action.get();
        final Icon normal = coordinator.resolveIcon(value.icon(), false).orElse(null);
        final Icon disabled = coordinator.resolveIcon(value.icon(), true).orElse(normal);
        if (normal == null) return;
        final Icon unlocked = value.icon().icon() == CubismIcon.LOCK
                ? coordinator
                        .resolveIcon(new UiIconRef(CubismIcon.LOCK_PLACEHOLDER), false)
                        .orElse(null)
                : disabled;
        if (unlocked == null) return;
        final Binding binding = new Binding(label, value, normal, disabled, unlocked);
        bindings.put(label, binding);
        // Preserve the existing action slot and native name alignment. Only the
        // lock paint/hit position moves to the left edge beside the chain column.
        if (value.state() == PaletteRowAction.State.LOCKED) label.setForeground(binding.lockedForeground);
        label.setBorder(binding);
        if (binding.originalIcon == null) label.setIconTextGap(0);
        label.setIcon(binding);
        label.setToolTipText(value.tooltip());
        label.putClientProperty(MARKER, value.state());
        label.addMouseListener(binding.listener);
        label.revalidate();
        label.repaint();
    }

    private void restore(final JLabel label) {
        final Binding binding = bindings.remove(label);
        if (binding == null) return;
        label.removeMouseListener(binding.listener);
        if (binding.lockedForeground.equals(label.getForeground())) label.setForeground(binding.originalForeground);
        // Do not overwrite a newer native icon installed since this decoration.
        if (label.getIcon() == binding) label.setIcon(binding.originalIcon);
        if (label.getBorder() == binding) label.setBorder(binding.originalBorder);
        if (binding.originalIcon == null && label.getIconTextGap() == 0)
            label.setIconTextGap(binding.originalIconTextGap);
        if (binding.action.tooltip().equals(label.getToolTipText())) label.setToolTipText(binding.originalTooltip);
        label.putClientProperty(MARKER, null);
        label.revalidate();
        label.repaint();
    }

    void restoreAll() {
        for (JLabel label : new java.util.ArrayList<>(bindings.keySet())) restore(label);
    }

    private static final class Binding implements Icon, Border {
        private final WeakReference<JLabel> label;
        private final PaletteRowAction action;
        private final Icon normal;
        private final Icon disabled;
        private final Icon originalIcon;
        private final String originalTooltip;
        private final Color originalForeground;
        private final Color lockedForeground;
        private final Border originalBorder;
        private final int originalIconTextGap;
        private final int slotWidth;
        private final Icon unlocked;
        private final MouseAdapter listener = new MouseAdapter() {
            // A listener on the label makes Swing stop delivering mouse events to its
            // ancestors, which would swallow the native row popup menu and selection.
            private void forward(final MouseEvent event) {
                final JLabel current = label.get();
                if (current == null || event.isConsumed()) return;
                final Container parent = current.getParent();
                if (parent == null) return;
                parent.dispatchEvent(SwingUtilities.convertMouseEvent(current, event, parent));
            }

            @Override
            public void mousePressed(final MouseEvent event) {
                forward(event);
            }

            @Override
            public void mouseReleased(final MouseEvent event) {
                forward(event);
            }

            @Override
            public void mouseEntered(final MouseEvent event) {
                forward(event);
            }

            @Override
            public void mouseExited(final MouseEvent event) {
                forward(event);
            }

            @Override
            public void mouseClicked(final MouseEvent event) {
                final JLabel current = label.get();
                if (current == null
                        || !current.isShowing()
                        || current.getIcon() != Binding.this
                        || !SwingUtilities.isLeftMouseButton(event)
                        || event.getClickCount() != 1) {
                    forward(event);
                    return;
                }
                final Rectangle icon = new Rectangle(
                        originalInsets(current).left,
                        (current.getHeight() - normal.getIconHeight()) / 2,
                        normal.getIconWidth(),
                        normal.getIconHeight());
                if (!icon.contains(event.getPoint())) {
                    forward(event);
                    return;
                }
                event.consume();
                try {
                    action.onClick().run();
                } catch (Throwable failure) {
                    FatalErrors.rethrowIfFatal(failure);
                }
            }
        };

        Binding(
                final JLabel label,
                final PaletteRowAction action,
                final Icon normal,
                final Icon disabled,
                final Icon unlocked) {
            this.label = new WeakReference<>(label);
            this.action = action;
            this.normal = normal;
            this.disabled = disabled;
            this.unlocked = unlocked;
            this.originalIcon = label.getIcon();
            this.originalTooltip = label.getToolTipText();
            this.originalForeground = label.getForeground();
            final Color themeDisabled = UIManager.getColor("Label.disabledForeground");
            this.lockedForeground = themeDisabled == null ? Color.GRAY : themeDisabled;
            this.originalBorder = label.getBorder();
            this.originalIconTextGap = label.getIconTextGap();
            this.slotWidth = normal.getIconWidth() + (originalIcon == null ? originalIconTextGap : 2);
        }

        @Override
        public int getIconWidth() {
            return originalIcon == null ? 0 : originalIcon.getIconWidth();
        }

        @Override
        public int getIconHeight() {
            return Math.max(normal.getIconHeight(), originalIcon == null ? 0 : originalIcon.getIconHeight());
        }

        private Insets originalInsets(final Component component) {
            return originalBorder == null ? new Insets(0, 0, 0, 0) : originalBorder.getBorderInsets(component);
        }

        @Override
        public Insets getBorderInsets(final Component component) {
            final Insets insets = originalInsets(component);
            return new Insets(insets.top, insets.left + slotWidth, insets.bottom, insets.right);
        }

        @Override
        public boolean isBorderOpaque() {
            return false;
        }

        @Override
        public void paintBorder(
                final Component component,
                final Graphics graphics,
                final int x,
                final int y,
                final int width,
                final int height) {
            if (originalBorder != null) originalBorder.paintBorder(component, graphics, x, y, width, height);
            final Icon visible =
                    switch (action.state()) {
                        case LOCKED -> normal;
                        case MIXED -> disabled;
                        case UNLOCKED -> unlocked;
                    };
            if (visible != null)
                visible.paintIcon(
                        component,
                        graphics,
                        x + originalInsets(component).left,
                        y + (height - visible.getIconHeight()) / 2);
        }

        @Override
        public void paintIcon(final Component component, final Graphics graphics, final int x, final int y) {
            if (originalIcon != null)
                originalIcon.paintIcon(
                        component, graphics, x, y + (getIconHeight() - originalIcon.getIconHeight()) / 2);
        }
    }
}
