package dev.turboism.ui.table;

import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/** Runtime-private Scene row drag visuals ported from the validated legacy implementation. */
final class SceneTableDragSupport {

    private SceneTableDragSupport() {
    }

    static boolean isNativeSceneRowListener(final Object listener) {
        return listener != null
            && "com.live2d.cubism.view.palette.scene.m".equals(listener.getClass().getName());
    }

    static Rectangle getRowBounds(final JTable table, final int rowIndex) {
        if (table == null || rowIndex < 0 || rowIndex >= table.getRowCount() || table.getColumnCount() <= 0) {
            return null;
        }
        return table.getCellRect(rowIndex, 0, true)
            .union(table.getCellRect(rowIndex, table.getColumnCount() - 1, true));
    }

    static int resolveDropRow(final JTable table, final Point point) {
        if (table == null || table.getRowCount() <= 0) return -1;
        final int row = table.rowAtPoint(point);
        if (row >= 0) return row;
        return point.y < 0 ? 0 : table.getRowCount() - 1;
    }

    static void removeNativeRowListeners(final JTable table) {
        for (java.awt.event.MouseListener listener : table.getMouseListeners()) {
            if (isNativeSceneRowListener(listener)) table.removeMouseListener(listener);
        }
    }

    static void removeConflictingMouseMotionListeners(
        final JTable table,
        final java.awt.event.MouseMotionListener keep
    ) {
        for (java.awt.event.MouseMotionListener listener : table.getMouseMotionListeners()) {
            if (listener != null && listener != keep) table.removeMouseMotionListener(listener);
        }
    }

    static BufferedImage captureRowImage(final JTable table, final int rowIndex) {
        final Rectangle bounds = getRowBounds(table, rowIndex);
        if (table == null || bounds == null || bounds.width <= 0 || bounds.height <= 0) return null;
        final BufferedImage image = new BufferedImage(bounds.width, bounds.height, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(table.getBackground());
            graphics.fillRect(0, 0, bounds.width, bounds.height);
            graphics.translate(-bounds.x, -bounds.y);
            graphics.setClip(bounds.x, bounds.y, bounds.width, bounds.height);
            table.paint(graphics);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    static final class DragOverlay extends JPanel {
        private static final float EASING = 0.35f;

        private final JTable table;
        private final Timer timer = new Timer(16, ignored -> animateStep());
        private BufferedImage ghostImage;
        private boolean active;
        private int sourceRow = -1;
        private int targetRow = -1;
        private float currentGhostY;
        private float targetGhostY;
        private int pointerOffsetY;

        DragOverlay(final JTable table) {
            this.table = table;
            setOpaque(false);
            setFocusable(false);
            setVisible(false);
        }

        @Override
        public boolean contains(final int x, final int y) {
            return false;
        }

        void attach() {
            final JRootPane root = SwingUtilities.getRootPane(table);
            final JLayeredPane layered = root == null ? null : root.getLayeredPane();
            if (layered == null) return;
            if (getParent() != layered) {
                if (getParent() != null) getParent().remove(this);
                layered.add(this, JLayeredPane.DRAG_LAYER);
            }
            setBounds(0, 0, layered.getWidth(), layered.getHeight());
        }

        void startDrag(final int row, final Point pressPoint) {
            final BufferedImage image = captureRowImage(table, row);
            final Rectangle rowBounds = getRowBounds(table, row);
            if (image == null || rowBounds == null) return;
            attach();
            final Rectangle overlayBounds = convert(rowBounds);
            ghostImage = image;
            sourceRow = row;
            targetRow = row;
            pointerOffsetY = Math.max(0, Math.min(pressPoint.y - rowBounds.y, rowBounds.height));
            currentGhostY = overlayBounds.y;
            targetGhostY = overlayBounds.y;
            active = true;
            setVisible(true);
            table.clearSelection();
            if (!timer.isRunning()) timer.start();
            repaint();
        }

        void updateDrag(final Point point) {
            if (!active) return;
            attach();
            final Point overlayPoint = SwingUtilities.convertPoint(table, point, this);
            targetGhostY = overlayPoint.y - pointerOffsetY;
            targetRow = resolveDropRow(table, point);
            if (!timer.isRunning()) timer.start();
            repaint();
        }

        int targetRow() {
            return targetRow;
        }

        void finish() {
            active = false;
            sourceRow = -1;
            targetRow = -1;
            ghostImage = null;
            setVisible(false);
            timer.stop();
            repaint();
        }

        private void animateStep() {
            if (!active) {
                timer.stop();
                return;
            }
            currentGhostY += (targetGhostY - currentGhostY) * EASING;
            if (Math.abs(currentGhostY - targetGhostY) < 0.5f) currentGhostY = targetGhostY;
            repaint();
        }

        private Rectangle convert(final Rectangle rectangle) {
            return getParent() == null ? rectangle : SwingUtilities.convertRectangle(table, rectangle, this);
        }

        @Override
        protected void paintComponent(final Graphics graphics) {
            super.paintComponent(graphics);
            if (!active || ghostImage == null) return;
            final Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                final Rectangle source = convert(getRowBounds(table, sourceRow));
                final Rectangle target = convert(getRowBounds(table, targetRow));
                if (source != null) {
                    g.setColor(new Color(245, 245, 245, 235));
                    g.fillRoundRect(source.x, source.y, source.width, source.height, 10, 10);
                    g.setColor(new Color(220, 220, 220, 255));
                    g.drawRoundRect(source.x, source.y, source.width - 1, source.height - 1, 10, 10);
                }
                if (target != null && targetRow != sourceRow) {
                    g.setColor(new Color(120, 160, 220, 70));
                    g.fillRoundRect(target.x + 3, target.y + 3, target.width - 6, target.height - 6, 10, 10);
                    g.setColor(new Color(120, 160, 220, 180));
                    g.drawRoundRect(target.x + 3, target.y + 3, target.width - 7, target.height - 7, 10, 10);
                }
                final Rectangle ghost = source == null
                    ? new Rectangle(0, Math.round(currentGhostY), ghostImage.getWidth(), ghostImage.getHeight())
                    : new Rectangle(source.x, Math.round(currentGhostY), source.width, source.height);
                g.setColor(new Color(0, 0, 0, 28));
                g.fillRoundRect(ghost.x + 4, ghost.y + 5, ghost.width, ghost.height, 12, 12);
                g.setColor(new Color(255, 255, 255, 245));
                g.fillRoundRect(ghost.x, ghost.y, ghost.width, ghost.height, 12, 12);
                g.drawImage(ghostImage, ghost.x, ghost.y, ghost.width, ghost.height, null);
                g.setColor(new Color(150, 150, 150, 110));
                g.drawRoundRect(ghost.x, ghost.y, ghost.width - 1, ghost.height - 1, 12, 12);
            } finally {
                g.dispose();
            }
        }
    }
}
