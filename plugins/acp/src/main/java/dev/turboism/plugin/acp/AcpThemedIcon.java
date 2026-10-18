package dev.turboism.plugin.acp;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import javax.imageio.ImageIO;
import javax.swing.Icon;

/** Theme-tinted high-resolution alpha mask shared by provider and UI icons. */
final class AcpThemedIcon implements Icon {
    private final BufferedImage mask;
    private Color tint;
    private BufferedImage image;

    private final int size;

    private AcpThemedIcon(final BufferedImage mask, final int size) {
        this.mask = mask;
        this.size = size;
    }

    static Icon load(final String path, final int size) {
        try (var stream = AcpThemedIcon.class.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("Missing icon: " + path);
            final BufferedImage mask = ImageIO.read(stream);
            if (mask == null) throw new IllegalStateException("Invalid icon: " + path);
            return new AcpThemedIcon(mask, size);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not read icon: " + path, failure);
        }
    }

    @Override
    public int getIconWidth() {
        return size;
    }

    @Override
    public int getIconHeight() {
        return size;
    }

    @Override
    public void paintIcon(final Component component, final Graphics graphics, final int x, final int y) {
        final Color color = component.isEnabled() ? component.getForeground() : AcpChatTheme.muted();
        if (!color.equals(tint)) {
            image = new BufferedImage(mask.getWidth(), mask.getHeight(), BufferedImage.TYPE_INT_ARGB);
            final Graphics2D g = image.createGraphics();
            try {
                g.drawImage(mask, 0, 0, null);
                g.setComposite(AlphaComposite.SrcIn);
                g.setColor(color);
                g.fillRect(0, 0, image.getWidth(), image.getHeight());
            } finally {
                g.dispose();
            }
            tint = color;
        }
        final Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(image, x, y, size, size, null);
        } finally {
            g.dispose();
        }
    }
}
