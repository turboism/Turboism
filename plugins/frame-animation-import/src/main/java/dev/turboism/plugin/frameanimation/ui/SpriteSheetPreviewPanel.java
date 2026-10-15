package dev.turboism.plugin.frameanimation.ui;

import dev.turboism.plugin.frameanimation.domain.SpriteSheetConfig;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.swing.BorderFactory;
import javax.swing.JPanel;

/**
 * Preview panel rendering the sprite sheet with interactive grid slice overlays and cell indices.
 */
public final class SpriteSheetPreviewPanel extends JPanel {

    private BufferedImage image;
    private SpriteSheetConfig config;

    public SpriteSheetPreviewPanel() {
        setPreferredSize(new Dimension(340, 240));
        setMinimumSize(new Dimension(240, 180));
        setBackground(new Color(35, 38, 44));
        setBorder(BorderFactory.createLineBorder(new Color(60, 65, 75)));
    }

    /** Updates the displayed sprite sheet image and grid configuration. */
    public void update(final BufferedImage image, final SpriteSheetConfig config) {
        this.image = image;
        this.config = config;
        repaint();
    }

    @Override
    protected void paintComponent(final Graphics g) {
        super.paintComponent(g);
        if (image == null) {
            g.setColor(new Color(130, 135, 145));
            final String text = "No image loaded";
            final FontMetrics fm = g.getFontMetrics();
            final int tx = (getWidth() - fm.stringWidth(text)) / 2;
            final int ty = (getHeight() + fm.getAscent()) / 2;
            g.drawString(text, tx, ty);
            return;
        }

        final Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

            final int panelW = getWidth();
            final int panelH = getHeight();
            final int imgW = image.getWidth();
            final int imgH = image.getHeight();

            // Calculate fitted scale
            final double scale = Math.min((double) (panelW - 16) / imgW, (double) (panelH - 16) / imgH);
            final int drawW = Math.max(1, (int) Math.round(imgW * scale));
            final int drawH = Math.max(1, (int) Math.round(imgH * scale));
            final int drawX = (panelW - drawW) / 2;
            final int drawY = (panelH - drawH) / 2;

            // Draw scaled image
            g2.drawImage(image, drawX, drawY, drawW, drawH, null);

            // Draw bounding border around image
            g2.setColor(new Color(80, 85, 95));
            g2.drawRect(drawX, drawY, drawW, drawH);

            if (config == null) return;

            final int rows = config.rows();
            final int columns = config.columns();
            final int frameCount = config.frameCount();
            final boolean colMajor = config.columnMajor();

            final double cellW = (double) drawW / columns;
            final double cellH = (double) drawH / rows;

            // Draw grid lines
            g2.setColor(new Color(255, 60, 60, 200));
            g2.setStroke(new BasicStroke(1.2f));

            for (int r = 1; r < rows; r++) {
                final int y = drawY + (int) Math.round(r * cellH);
                g2.drawLine(drawX, y, drawX + drawW, y);
            }
            for (int c = 1; c < columns; c++) {
                final int x = drawX + (int) Math.round(c * cellW);
                g2.drawLine(x, drawY, x, drawY + drawH);
            }

            // Draw cell frame numbers and dim excluded frames
            g2.setFont(new Font("SansSerif", Font.BOLD, 11));
            final FontMetrics fm = g2.getFontMetrics();

            int frameIdx = 0;
            if (!colMajor) {
                for (int r = 0; r < rows; r++) {
                    for (int c = 0; c < columns; c++) {
                        drawCellBadge(g2, drawX, drawY, c, r, cellW, cellH, frameIdx++, frameCount, fm);
                    }
                }
            } else {
                for (int c = 0; c < columns; c++) {
                    for (int r = 0; r < rows; r++) {
                        drawCellBadge(g2, drawX, drawY, c, r, cellW, cellH, frameIdx++, frameCount, fm);
                    }
                }
            }
        } finally {
            g2.dispose();
        }
    }

    private void drawCellBadge(
            final Graphics2D g2,
            final int drawX,
            final int drawY,
            final int c,
            final int r,
            final double cellW,
            final double cellH,
            final int index,
            final int maxFrames,
            final FontMetrics fm) {
        final int cx0 = drawX + (int) Math.round(c * cellW);
        final int cy0 = drawY + (int) Math.round(r * cellH);
        final int cw = (int) Math.round(cellW);
        final int ch = (int) Math.round(cellH);

        final boolean active = index < maxFrames;

        if (!active) {
            // Darken excluded frame cell
            g2.setColor(new Color(0, 0, 0, 160));
            g2.fillRect(cx0, cy0, cw, ch);
            g2.setColor(new Color(150, 150, 150, 180));
            g2.drawLine(cx0, cy0, cx0 + cw, cy0 + ch);
            g2.drawLine(cx0, cy0 + ch, cx0 + cw, cy0);
            return;
        }

        // Active frame number badge
        final String label = "#" + (index + 1);
        final int labelW = fm.stringWidth(label);
        final int badgeW = labelW + 6;
        final int badgeH = fm.getHeight() + 2;

        g2.setColor(new Color(0, 0, 0, 180));
        g2.fillRoundRect(cx0 + 2, cy0 + 2, badgeW, badgeH, 4, 4);

        g2.setColor(Color.WHITE);
        g2.drawString(label, cx0 + 5, cy0 + 2 + fm.getAscent());
    }
}
