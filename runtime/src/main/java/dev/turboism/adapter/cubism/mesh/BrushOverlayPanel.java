package dev.turboism.adapter.cubism.mesh;

import dev.turboism.sdk.cubism.model.Point2;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Container;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JPanel;

/** Enabled transparent topmost overlay that paints only the current brush stroke. */
final class BrushOverlayPanel extends JPanel {
    static final String COMPONENT_NAME = "turboism:selection-brush-overlay";
    static final String PREVIEW_VISIBLE_PROPERTY = "turboism:selection-brush-preview-visible";
    private static final Color TRAIL_COLOR = new Color(0x19, 0x19, 0x96, 0xB4);

    private final List<Point2> trail = new ArrayList<>();
    private float radius;

    BrushOverlayPanel() {
        setOpaque(false);
        setFocusable(false);
        setEnabled(true);
        setName(COMPONENT_NAME);
        putClientProperty(PREVIEW_VISIBLE_PROPERTY, Boolean.FALSE);
    }

    /** Keeps hit-test coordinates valid even inside a native layout pass. */
    @Override
    public void setBounds(final int x, final int y, final int width, final int height) {
        final Container owner = getParent();
        if (owner == null) super.setBounds(x, y, width, height);
        else super.setBounds(0, 0, owner.getWidth(), owner.getHeight());
    }

    void begin(final float x, final float y, final float radius) {
        trail.clear();
        this.radius = radius;
        trail.add(new Point2(x, y));
        putClientProperty(PREVIEW_VISIBLE_PROPERTY, Boolean.TRUE);
        repaint();
    }

    void append(final float x, final float y) {
        if (trail.isEmpty()) return;
        trail.add(new Point2(x, y));
        repaint();
    }

    void clearTrail() {
        trail.clear();
        putClientProperty(PREVIEW_VISIBLE_PROPERTY, Boolean.FALSE);
        repaint();
    }

    boolean previewVisible() {
        return Boolean.TRUE.equals(getClientProperty(PREVIEW_VISIBLE_PROPERTY));
    }

    @Override
    protected void paintComponent(final Graphics graphics) {
        super.paintComponent(graphics);
        if (trail.isEmpty()) return;
        final Graphics2D g2 = (Graphics2D) graphics.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(TRAIL_COLOR);
            final float diameter = radius * 2.0f;
            if (trail.size() == 1) {
                final Point2 point = trail.get(0);
                g2.fill(new Ellipse2D.Float(point.x() - radius, point.y() - radius, diameter, diameter));
                return;
            }
            g2.setStroke(new BasicStroke(diameter, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            final Path2D.Float path = new Path2D.Float();
            path.moveTo(trail.get(0).x(), trail.get(0).y());
            for (int index = 1; index < trail.size(); index++) {
                path.lineTo(trail.get(index).x(), trail.get(index).y());
            }
            g2.draw(path);
        } finally {
            g2.dispose();
        }
    }
}
