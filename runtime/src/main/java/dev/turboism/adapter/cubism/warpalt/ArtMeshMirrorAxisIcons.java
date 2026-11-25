package dev.turboism.adapter.cubism.warpalt;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;

/** Builds supersampled icon badges for the ArtMesh mirror axis controls. */
final class ArtMeshMirrorAxisIcons {
    static final int ICON_SIZE_PX = 22;

    private static final int SUPERSAMPLE = 4;
    private static final int RENDER_SIZE = ICON_SIZE_PX * SUPERSAMPLE;

    private static final float BADGE_MARGIN = 4.0f;
    private static final float BADGE_DIAMETER = RENDER_SIZE - BADGE_MARGIN * 2.0f;
    private static final float BADGE_BORDER_WIDTH = 4.0f;

    private static final int DARK_R = 24;
    private static final int DARK_G = 24;
    private static final int DARK_B = 28;
    private static final Color DARK_COLOR = new Color(DARK_R, DARK_G, DARK_B);
    private static final Color LIGHT_COLOR = new Color(255, 255, 255);
    private static final Color MUTED_DARK_COLOR = new Color(96, 96, 104);
    private static final Color MUTED_LIGHT_COLOR = new Color(196, 196, 204);
    private static final Color LOCKED_FILL_COLOR = new Color(229, 142, 26);
    private static final Color DISABLED_FILL_COLOR = new Color(240, 240, 244, 110);
    private static final Color DISABLED_BORDER_COLOR = new Color(110, 110, 118, 125);
    private static final Color DISABLED_GLYPH_COLOR = new Color(110, 110, 118, 125);
    private static final float CENTER_RING_DIAMETER = 64.0f;
    private static final float CENTER_RING_MARGIN = (RENDER_SIZE - CENTER_RING_DIAMETER) * 0.5f;
    private static final float CENTER_RING_DARK_STROKE = 14.0f;
    private static final float CENTER_RING_LIGHT_STROKE = 6.0f;

    private static final float PADLOCK_BODY_X = 27.0f;
    private static final float PADLOCK_BODY_Y = 41.0f;
    private static final float PADLOCK_BODY_W = 34.0f;
    private static final float PADLOCK_BODY_H = 27.0f;
    private static final float PADLOCK_BODY_ARC = 6.0f;
    private static final float KEYHOLE_RADIUS = 3.5f;
    private static final float KEYHOLE_CENTER_X = RENDER_SIZE * 0.5f;
    private static final float KEYHOLE_CENTER_Y = 54.0f;

    private static final float SHACKLE_STROKE_WIDTH = 5.5f;
    private static final float SHACKLE_LEFT_X = 34.0f;
    private static final float SHACKLE_RIGHT_X = 54.0f;
    private static final float SHACKLE_TOP_Y = 20.0f;
    private static final float SHACKLE_ARC_W = SHACKLE_RIGHT_X - SHACKLE_LEFT_X;
    private static final float SHACKLE_ARC_H = 22.0f;
    private static final float SHACKLE_MID_Y = SHACKLE_TOP_Y + SHACKLE_ARC_H * 0.5f;
    private static final float SHACKLE_CLOSED_END_Y = PADLOCK_BODY_Y + 2.0f;
    private static final float SHACKLE_OPEN_RIGHT_END_Y = PADLOCK_BODY_Y - 7.0f;
    private static final float SHACKLE_HALF_TURN_DEGREES = 180.0f;

    private static final float RESET_ARC_X = 24.0f;
    private static final float RESET_ARC_Y = 24.0f;
    private static final float RESET_ARC_SIZE = 40.0f;
    private static final float RESET_ARC_STROKE_WIDTH = 5.5f;
    private static final float RESET_ARC_START_DEGREES = 135.0f;
    private static final float RESET_ARC_EXTENT_DEGREES = -260.0f;
    private static final float ARROW_TIP_X = 20.0f;
    private static final float ARROW_TIP_Y = 37.5f;
    private static final float ARROW_BASE_OUTER_X = 22.0f;
    private static final float ARROW_BASE_OUTER_Y = 17.5f;
    private static final float ARROW_BASE_INNER_X = 39.5f;
    private static final float ARROW_BASE_INNER_Y = 34.5f;

    private ArtMeshMirrorAxisIcons() {}

    /** Renders the unlocked-axis circular badge with an open padlock glyph. */
    static BufferedImage unlockedIcon() {
        return renderPadlock(false);
    }

    /** Renders the locked-axis circular badge with a closed padlock glyph. */
    static BufferedImage lockedIcon() {
        return renderPadlock(true);
    }

    /** Renders the hollow circle center handle with transparent exterior and interior. */
    static BufferedImage centerHandleIcon(boolean locked) {
        BufferedImage hiRes = new BufferedImage(RENDER_SIZE, RENDER_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = createHiResGraphics(hiRes);
        try {
            Ellipse2D.Float ring = new Ellipse2D.Float(
                    CENTER_RING_MARGIN, CENTER_RING_MARGIN, CENTER_RING_DIAMETER, CENTER_RING_DIAMETER);
            g.setColor(locked ? MUTED_DARK_COLOR : DARK_COLOR);
            g.setStroke(new BasicStroke(CENTER_RING_DARK_STROKE));
            g.draw(ring);
            g.setColor(locked ? MUTED_LIGHT_COLOR : LIGHT_COLOR);
            g.setStroke(new BasicStroke(CENTER_RING_LIGHT_STROKE));
            g.draw(ring);
        } finally {
            g.dispose();
        }
        return downsample(hiRes);
    }

    /** Renders the reset-axis circular badge in active or muted translucent colors. */
    static BufferedImage resetIcon(boolean enabled) {
        BufferedImage hiRes = new BufferedImage(RENDER_SIZE, RENDER_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = createHiResGraphics(hiRes);
        try {
            Color fillColor = enabled ? LIGHT_COLOR : DISABLED_FILL_COLOR;
            Color borderColor = enabled ? DARK_COLOR : DISABLED_BORDER_COLOR;
            Color glyphColor = enabled ? DARK_COLOR : DISABLED_GLYPH_COLOR;
            drawBadge(g, fillColor, borderColor);

            g.setColor(glyphColor);
            g.setStroke(new BasicStroke(RESET_ARC_STROKE_WIDTH, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Arc2D.Float(
                    RESET_ARC_X,
                    RESET_ARC_Y,
                    RESET_ARC_SIZE,
                    RESET_ARC_SIZE,
                    RESET_ARC_START_DEGREES,
                    RESET_ARC_EXTENT_DEGREES,
                    Arc2D.OPEN));

            Path2D.Float arrowHead = new Path2D.Float();
            arrowHead.moveTo(ARROW_TIP_X, ARROW_TIP_Y);
            arrowHead.lineTo(ARROW_BASE_OUTER_X, ARROW_BASE_OUTER_Y);
            arrowHead.lineTo(ARROW_BASE_INNER_X, ARROW_BASE_INNER_Y);
            arrowHead.closePath();
            g.fill(arrowHead);
        } finally {
            g.dispose();
        }
        return downsample(hiRes);
    }

    private static BufferedImage renderPadlock(boolean locked) {
        BufferedImage hiRes = new BufferedImage(RENDER_SIZE, RENDER_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = createHiResGraphics(hiRes);
        try {
            Color fillColor = locked ? LOCKED_FILL_COLOR : LIGHT_COLOR;
            Color glyphColor = locked ? LIGHT_COLOR : DARK_COLOR;
            drawBadge(g, fillColor, DARK_COLOR);

            g.setColor(glyphColor);
            g.setStroke(new BasicStroke(SHACKLE_STROKE_WIDTH, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            Path2D.Float shackle = new Path2D.Float();
            shackle.moveTo(SHACKLE_LEFT_X, SHACKLE_CLOSED_END_Y);
            shackle.lineTo(SHACKLE_LEFT_X, SHACKLE_MID_Y);
            shackle.append(
                    new Arc2D.Float(
                            SHACKLE_LEFT_X,
                            SHACKLE_TOP_Y,
                            SHACKLE_ARC_W,
                            SHACKLE_ARC_H,
                            SHACKLE_HALF_TURN_DEGREES,
                            -SHACKLE_HALF_TURN_DEGREES,
                            Arc2D.OPEN),
                    true);
            shackle.lineTo(SHACKLE_RIGHT_X, locked ? SHACKLE_CLOSED_END_Y : SHACKLE_OPEN_RIGHT_END_Y);
            g.draw(shackle);

            g.fill(new RoundRectangle2D.Float(
                    PADLOCK_BODY_X,
                    PADLOCK_BODY_Y,
                    PADLOCK_BODY_W,
                    PADLOCK_BODY_H,
                    PADLOCK_BODY_ARC,
                    PADLOCK_BODY_ARC));

            g.setColor(fillColor);
            g.fill(new Ellipse2D.Float(
                    KEYHOLE_CENTER_X - KEYHOLE_RADIUS,
                    KEYHOLE_CENTER_Y - KEYHOLE_RADIUS,
                    KEYHOLE_RADIUS * 2.0f,
                    KEYHOLE_RADIUS * 2.0f));
        } finally {
            g.dispose();
        }
        return downsample(hiRes);
    }

    private static Graphics2D createHiResGraphics(BufferedImage image) {
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        return g;
    }

    private static void drawBadge(Graphics2D g, Color fillColor, Color borderColor) {
        Ellipse2D.Float circle = new Ellipse2D.Float(BADGE_MARGIN, BADGE_MARGIN, BADGE_DIAMETER, BADGE_DIAMETER);
        g.setColor(fillColor);
        g.fill(circle);
        g.setColor(borderColor);
        g.setStroke(new BasicStroke(BADGE_BORDER_WIDTH));
        g.draw(circle);
    }

    private static BufferedImage downsample(BufferedImage hiRes) {
        BufferedImage result = new BufferedImage(ICON_SIZE_PX, ICON_SIZE_PX, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = result.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(hiRes, 0, 0, ICON_SIZE_PX, ICON_SIZE_PX, null);
        } finally {
            g.dispose();
        }
        return result;
    }
}
