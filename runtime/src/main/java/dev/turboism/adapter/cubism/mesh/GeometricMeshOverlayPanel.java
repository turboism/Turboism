package dev.turboism.adapter.cubism.mesh;

import dev.turboism.sdk.cubism.model.Point2;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Container;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.util.List;
import java.util.Objects;
import javax.swing.JPanel;

/** Transparent canvas overlay that renders the center anchor and geometric mesh preview. */
final class GeometricMeshOverlayPanel extends JPanel {
    static final String COMPONENT_NAME = "turboism:geometric-mesh-layout-overlay";
    static final String PREVIEW_VISIBLE_PROPERTY = "turboism:geometric-mesh-layout-preview-visible";

    private static final Color SHADOW_COLOR = new Color(0x14, 0x16, 0x1A, 0xD8);
    private static final Color EDGE_COLOR = new Color(0x21, 0xB8, 0xFF, 0xF0);
    private static final Color VERTEX_FILL_COLOR = new Color(0xFF, 0xFF, 0xFF, 0xF5);
    private static final Color ANCHOR_COLOR = new Color(0xFF, 0x9F, 0x1C, 0xFA);

    private final InputRegion inputRegion;
    private Point2 anchorComponent;
    private List<Point2> vertices = List.of();
    private int[] edgePairs = new int[0];

    GeometricMeshOverlayPanel(final InputRegion inputRegion) {
        this.inputRegion = Objects.requireNonNull(inputRegion, "inputRegion");
        setOpaque(false);
        setFocusable(false);
        setEnabled(true);
        setName(COMPONENT_NAME);
        putClientProperty(PREVIEW_VISIBLE_PROPERTY, Boolean.FALSE);
    }

    @Override
    public boolean contains(final int x, final int y) {
        return super.contains(x, y) && inputRegion.contains(x, y);
    }

    @FunctionalInterface
    interface InputRegion {
        boolean contains(int x, int y);
    }

    @Override
    public void setBounds(final int x, final int y, final int width, final int height) {
        final Container owner = getParent();
        if (owner == null) {
            super.setBounds(x, y, width, height);
        } else {
            super.setBounds(0, 0, owner.getWidth(), owner.getHeight());
        }
    }

    void updatePreview(final Point2 centerPoint, final List<Point2> vertexPoints, final int[] edges) {
        this.anchorComponent = Objects.requireNonNull(centerPoint, "centerPoint");
        this.vertices = vertexPoints == null ? List.of() : List.copyOf(vertexPoints);
        this.edgePairs = edges == null ? new int[0] : edges.clone();
        putClientProperty(PREVIEW_VISIBLE_PROPERTY, Boolean.TRUE);
        repaint();
    }

    void clearPreview() {
        this.anchorComponent = null;
        this.vertices = List.of();
        this.edgePairs = new int[0];
        putClientProperty(PREVIEW_VISIBLE_PROPERTY, Boolean.FALSE);
        repaint();
    }

    boolean previewVisible() {
        return Boolean.TRUE.equals(getClientProperty(PREVIEW_VISIBLE_PROPERTY));
    }

    Point2 centerForTests() {
        return anchorComponent;
    }

    List<Point2> verticesForTests() {
        return vertices;
    }

    int[] edgePairsForTests() {
        return edgePairs.clone();
    }

    @Override
    protected void paintComponent(final Graphics graphics) {
        super.paintComponent(graphics);
        final Point2 currentCenter = anchorComponent;
        final List<Point2> currentVertices = vertices;
        final int[] currentEdges = edgePairs;
        if (currentCenter == null) return;
        final Graphics2D g2 = (Graphics2D) graphics.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (currentVertices.size() >= 3) {
                paintEdges(g2, currentVertices, currentEdges);
                paintVertices(g2, currentVertices);
            }
            paintAnchor(g2, currentCenter);
        } finally {
            g2.dispose();
        }
    }

    private static void paintEdges(final Graphics2D g2, final List<Point2> vertexPoints, final int[] edges) {
        if (edges.length < 2) return;
        final Path2D.Float path = new Path2D.Float();
        final int size = vertexPoints.size();
        for (int index = 0; index + 1 < edges.length; index += 2) {
            final int u = edges[index];
            final int v = edges[index + 1];
            if (u >= 0 && u < size && v >= 0 && v < size) {
                final Point2 p1 = vertexPoints.get(u);
                final Point2 p2 = vertexPoints.get(v);
                path.moveTo(p1.x(), p1.y());
                path.lineTo(p2.x(), p2.y());
            }
        }

        g2.setColor(SHADOW_COLOR);
        g2.setStroke(new BasicStroke(3.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.draw(path);

        g2.setColor(EDGE_COLOR);
        g2.setStroke(new BasicStroke(1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.draw(path);
    }

    private static void paintVertices(final Graphics2D g2, final List<Point2> vertexPoints) {
        final float half = 3.5f;
        final float size = half * 2.0f;
        for (Point2 vertex : vertexPoints) {
            final Rectangle2D.Float box = new Rectangle2D.Float(vertex.x() - half, vertex.y() - half, size, size);
            g2.setColor(VERTEX_FILL_COLOR);
            g2.fill(box);
            g2.setColor(SHADOW_COLOR);
            g2.setStroke(new BasicStroke(1.5f));
            g2.draw(box);
        }
    }

    private static void paintAnchor(final Graphics2D g2, final Point2 anchor) {
        final float cx = anchor.x();
        final float cy = anchor.y();
        final float diamondRadius = 9.0f;
        final float crossArm = 13.0f;

        final Path2D.Float diamond = new Path2D.Float();
        diamond.moveTo(cx, cy - diamondRadius);
        diamond.lineTo(cx + diamondRadius, cy);
        diamond.lineTo(cx, cy + diamondRadius);
        diamond.lineTo(cx - diamondRadius, cy);
        diamond.closePath();

        g2.setColor(SHADOW_COLOR);
        g2.setStroke(new BasicStroke(3.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.draw(diamond);
        g2.draw(new Line2D.Float(cx - crossArm, cy, cx + crossArm, cy));
        g2.draw(new Line2D.Float(cx, cy - crossArm, cx, cy + crossArm));

        g2.setColor(ANCHOR_COLOR);
        g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.draw(diamond);
        g2.draw(new Line2D.Float(cx - crossArm, cy, cx + crossArm, cy));
        g2.draw(new Line2D.Float(cx, cy - crossArm, cx, cy + crossArm));
        g2.fill(new Ellipse2D.Float(cx - 2.5f, cy - 2.5f, 5.0f, 5.0f));
    }
}
