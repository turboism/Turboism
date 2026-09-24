package dev.turboism.plugin.boundingboxwarpmirror.ui;

import dev.turboism.sdk.cubism.mirror.WarpMirrorDirection;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.ui.window.TurboismWindowFactory;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.plaf.basic.BasicArrowButton;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GridLayout;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Modal direction chooser for the BoundingBox overlay mirror, reusing the legacy
 * visual design: a 3x3 cross of {@link BasicArrowButton}s (button placed on the
 * side the mirror lands on, arrow pointing outward in the copy direction), a
 * default-checked "preserve child objects" box centered above OK/Cancel. OK
 * stays disabled until a direction is picked. Cancelling performs no write.
 */
public final class MirrorDirectionDialog extends JDialog {

    private static final Color WARNING_COLOR = new Color(215, 70, 70);
    private static final int DIRECTION_BUTTON_SIZE = 42;
    private static final int GRID_GAP = 6;

    /** User's confirmed choice: direction plus the preserve-descendants flag. */
    public record Choice(WarpMirrorDirection direction, boolean preserveDescendants) {
        public Choice {
            direction = Objects.requireNonNull(direction, "direction");
        }
    }

    private final JCheckBox preserveChildren;
    private Choice result;

    public MirrorDirectionDialog(final PluginLocalization localization) {
        super((Frame) null, localization.text("dialog.title"), true);
        Objects.requireNonNull(localization, "localization");
        TurboismWindowFactory.style(this);
        setResizable(false);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        final JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        final JButton confirm = new JButton(localization.text("dialog.ok"));
        confirm.setEnabled(false);
        final AtomicReference<WarpMirrorDirection> selected = new AtomicReference<>();
        final AtomicReference<BasicArrowButton> selectedButton = new AtomicReference<>();
        final List<BasicArrowButton> directionButtons = new ArrayList<>(4);

        // Button position marks the side the mirror lands on; the arrow points
        // outward from the center, in the direction the copy travels.
        final JPanel grid = new JPanel(new GridLayout(3, 3, GRID_GAP, GRID_GAP));
        grid.setOpaque(false);
        grid.add(placeholder());
        grid.add(directionButton(
            BasicArrowButton.NORTH, "dialog.direction.bottomToTop",
            localization, WarpMirrorDirection.BOTTOM_TO_TOP,
            selected, selectedButton, directionButtons, confirm));
        grid.add(placeholder());
        grid.add(directionButton(
            BasicArrowButton.WEST, "dialog.direction.rightToLeft",
            localization, WarpMirrorDirection.RIGHT_TO_LEFT,
            selected, selectedButton, directionButtons, confirm));
        grid.add(placeholder());
        grid.add(directionButton(
            BasicArrowButton.EAST, "dialog.direction.leftToRight",
            localization, WarpMirrorDirection.LEFT_TO_RIGHT,
            selected, selectedButton, directionButtons, confirm));
        grid.add(placeholder());
        grid.add(directionButton(
            BasicArrowButton.SOUTH, "dialog.direction.topToBottom",
            localization, WarpMirrorDirection.TOP_TO_BOTTOM,
            selected, selectedButton, directionButtons, confirm));
        grid.add(placeholder());
        // Keep the 3x3 grid at its preferred size so cells stay square; a
        // stretched CENTER cell would widen them into rectangles.
        final JPanel gridHolder = new JPanel(new java.awt.GridBagLayout());
        gridHolder.setOpaque(false);
        gridHolder.add(grid);

        preserveChildren = new JCheckBox(localization.text("dialog.preserveChildren"), true);
        preserveChildren.setToolTipText(localization.text("dialog.preserveChildren.tooltip"));

        confirm.addActionListener(event -> {
            final WarpMirrorDirection direction = selected.get();
            if (direction == null) {
                return;
            }
            result = new Choice(direction, preserveChildren.isSelected());
            dispose();
        });
        final JButton cancel = new JButton(localization.text("dialog.cancel"));
        cancel.addActionListener(event -> dispose());
        final JPanel optionRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        optionRow.add(preserveChildren);
        final JPanel actions = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
        actions.add(confirm);
        actions.add(cancel);
        final JPanel footer = new JPanel();
        footer.setLayout(new javax.swing.BoxLayout(footer, javax.swing.BoxLayout.Y_AXIS));
        footer.add(optionRow);
        footer.add(actions);

        root.add(gridHolder, BorderLayout.CENTER);
        root.add(footer, BorderLayout.SOUTH);
        setContentPane(root);
        getRootPane().setDefaultButton(confirm);
        getRootPane().registerKeyboardAction(
            event -> dispose(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW
        );
        pack();
        setMinimumSize(new Dimension(280, 0));
        setLocationRelativeTo(null);
    }

    private BasicArrowButton directionButton(
        final int arrowDirection,
        final String tooltipKey,
        final PluginLocalization localization,
        final WarpMirrorDirection direction,
        final AtomicReference<WarpMirrorDirection> selected,
        final AtomicReference<BasicArrowButton> selectedButton,
        final List<BasicArrowButton> directionButtons,
        final JButton confirm
    ) {
        final Dimension size = new Dimension(DIRECTION_BUTTON_SIZE, DIRECTION_BUTTON_SIZE);
        final BasicArrowButton button = new BasicArrowButton(arrowDirection) {
            @Override
            public Dimension getPreferredSize() {
                return size;
            }

            @Override
            public Dimension getMinimumSize() {
                return size;
            }

            @Override
            public Dimension getMaximumSize() {
                return size;
            }
        };
        button.setToolTipText(localization.text(tooltipKey));
        button.setFocusable(false);
        button.setFocusPainted(false);
        button.setOpaque(true);
        button.putClientProperty("hovered", Boolean.FALSE);
        directionButtons.add(button);
        button.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseEntered(java.awt.event.MouseEvent event) {
                button.putClientProperty("hovered", Boolean.TRUE);
                restyleDirections(directionButtons, button, selectedButton);
            }

            @Override
            public void mouseExited(java.awt.event.MouseEvent event) {
                button.putClientProperty("hovered", Boolean.FALSE);
                restyleDirections(directionButtons, button, selectedButton);
            }
        });
        button.addActionListener(event -> {
            selected.set(direction);
            selectedButton.set(button);
            confirm.setEnabled(true);
            restyleDirections(directionButtons, button, selectedButton);
        });
        restyle(button, false, false);
        return button;
    }

    private static void restyleDirections(
        final List<BasicArrowButton> buttons,
        final BasicArrowButton hoveredButton,
        final AtomicReference<BasicArrowButton> selectedButton
    ) {
        final BasicArrowButton selected = selectedButton.get();
        for (BasicArrowButton button : buttons) {
            restyle(button, button == selected,
                button == hoveredButton && button.getClientProperty("hovered") == Boolean.TRUE);
        }
    }

    private static void restyle(
        final BasicArrowButton button,
        final boolean selected,
        final boolean hovered
    ) {
        button.setBorder(BorderFactory.createLineBorder(
            selected ? WARNING_COLOR : borderColor(), selected ? 2 : 1));
        if (selected) {
            button.setBackground(new Color(255, 235, 235));
        } else if (hovered) {
            button.setBackground(hoverColor());
        } else {
            button.setBackground(normalButtonColor());
        }
    }

    private static Color normalButtonColor() {
        final Color color = javax.swing.UIManager.getColor("Button.background");
        return color != null ? color : Color.WHITE;
    }

    private static Color hoverColor() {
        final Color base = normalButtonColor();
        return new Color(
            Math.min(255, base.getRed() + 14),
            Math.min(255, base.getGreen() + 14),
            Math.min(255, base.getBlue() + 18));
    }

    private static Component placeholder() {
        final JPanel cell = new JPanel();
        cell.setOpaque(false);
        cell.setPreferredSize(new Dimension(DIRECTION_BUTTON_SIZE, DIRECTION_BUTTON_SIZE));
        cell.setFocusable(false);
        return cell;
    }

    private static Color borderColor() {
        final Color color = javax.swing.UIManager.getColor("Component.borderColor");
        return color != null ? color : new Color(180, 180, 180);
    }

    /** Shows the dialog and returns the confirmed choice, empty when cancelled. */
    public Optional<Choice> showDialog() {
        setVisible(true);
        return Optional.ofNullable(result);
    }
}
