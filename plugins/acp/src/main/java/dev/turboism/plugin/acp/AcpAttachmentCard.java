package dev.turboism.plugin.acp;

import java.awt.BasicStroke;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.util.Locale;
import javax.swing.ImageIcon;
import javax.swing.JButton;

/** Removable image crop or two-line file card in the unsent draft. */
final class AcpAttachmentCard extends JButton {
    private final AcpAttachment attachment;
    private final boolean picture;
    private ImageIcon preview;

    AcpAttachmentCard(final AcpAttachment attachment, final String removeLabel) {
        this.attachment = attachment;
        picture = attachment.mimeType().startsWith("image/");
        setPreferredSize(new Dimension(picture ? 72 : 260, 72));
        setContentAreaFilled(false);
        setForeground(AcpChatTheme.muted());
        setBorderPainted(false);
        setRolloverEnabled(true);
        setToolTipText(removeLabel + " · " + attachment.mimeType());
        getAccessibleContext().setAccessibleName(removeLabel + " · " + fileType(attachment));
    }

    void setPreview(final ImageIcon preview) {
        this.preview = preview;
        repaint();
    }

    @Override
    protected void paintComponent(final Graphics graphics) {
        final Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(AcpChatTheme.surface());
            g.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
            if (picture && preview != null) {
                final Graphics2D crop = (Graphics2D) g.create();
                crop.clip(new RoundRectangle2D.Float(1, 1, getWidth() - 2, getHeight() - 2, 10, 10));
                final double scale = Math.max(
                        (getWidth() - 2.0) / preview.getIconWidth(), (getHeight() - 2.0) / preview.getIconHeight());
                final int width = (int) Math.ceil(preview.getIconWidth() * scale);
                final int height = (int) Math.ceil(preview.getIconHeight() * scale);
                crop.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                crop.drawImage(
                        preview.getImage(), (getWidth() - width) / 2, (getHeight() - height) / 2, width, height, null);
                crop.dispose();
            } else {
                g.setColor(AcpChatTheme.muted());
                g.setStroke(new BasicStroke(1.25F, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                final int x = picture ? 26 : 14;
                AcpIcons.icon(AcpIcons.Kind.FILE).paintIcon(this, g, x, 25);
                if (!picture) {
                    g.setFont(getFont().deriveFont(Font.PLAIN, 13F));
                    g.setColor(AcpChatTheme.foreground());
                    g.drawString(middleEllipsis(attachment.name(), g.getFontMetrics(), getWidth() - 76), 44, 31);
                    g.setFont(getFont().deriveFont(Font.PLAIN, 11F));
                    g.setColor(AcpChatTheme.muted());
                    g.drawString(fileType(attachment), 44, 49);
                }
            }
            g.setColor(hasFocus() ? AcpChatTheme.accent() : AcpChatTheme.border());
            g.setStroke(new BasicStroke(1));
            g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
            if (!picture || getModel().isRollover() || hasFocus()) {
                final int x = getWidth() - 17;
                g.setColor(AcpChatTheme.surface());
                g.fillOval(x - 4, 3, 18, 18);
                g.setColor(AcpChatTheme.muted());
                AcpIcons.icon(AcpIcons.Kind.REMOVE).paintIcon(this, g, x - 3, 2);
            }
        } finally {
            g.dispose();
        }
    }

    static String middleEllipsis(final String text, final FontMetrics metrics, final int width) {
        if (metrics.stringWidth(text) <= width) return text;
        final int[] points = text.codePoints().toArray();
        int left = (points.length + 1) / 2, right = points.length / 2;
        while (left > 0 || right > 0) {
            final String candidate =
                    new String(points, 0, left) + "…" + new String(points, points.length - right, right);
            if (metrics.stringWidth(candidate) <= width) return candidate;
            if (left >= right) left--;
            else right--;
        }
        return "…";
    }

    static String fileType(final AcpAttachment attachment) {
        final int dot = attachment.name().lastIndexOf('.');
        if (dot > 0 && dot < attachment.name().length() - 1 && attachment.name().length() - dot <= 12)
            return attachment.name().substring(dot + 1).toUpperCase(Locale.ROOT);
        return switch (attachment.mimeType()) {
            case "application/pdf" -> "PDF";
            case "text/plain" -> "TXT";
            default -> attachment.mimeType();
        };
    }
}
