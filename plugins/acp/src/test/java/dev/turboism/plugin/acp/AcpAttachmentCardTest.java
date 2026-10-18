package dev.turboism.plugin.acp;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;
import java.awt.image.BufferedImage;
import javax.swing.ImageIcon;
import org.junit.jupiter.api.Test;

final class AcpAttachmentCardTest {
    @Test
    void fileNamesPreserveBothEndsAndDisplayTheFileType() {
        final AcpAttachment file = new AcpAttachment(
                "Yao 等 - 2025 - A very long Live2D modeling reference.pdf", "application/pdf", "", "", 0);
        final var card = new AcpAttachmentCard(file, "Remove " + file.name());
        final var metrics = card.getFontMetrics(card.getFont());
        final String label = AcpAttachmentCard.middleEllipsis(file.name(), metrics, 180);
        assertTrue(label.startsWith("Yao"));
        assertTrue(label.endsWith(".pdf"));
        assertTrue(label.contains("…"));
        assertTrue(metrics.stringWidth(label) <= 180);
        assertEquals("PDF", AcpAttachmentCard.fileType(file));
        assertTrue(card.getToolTipText().contains(file.name()));
        assertTrue(card.getAccessibleContext().getAccessibleName().contains("PDF"));
    }

    @Test
    void imageCardsCropToFillWithoutDistortingTheImage() {
        final BufferedImage source = new BufferedImage(200, 50, BufferedImage.TYPE_INT_RGB);
        final var sourceGraphics = source.createGraphics();
        sourceGraphics.setColor(Color.RED);
        sourceGraphics.fillRect(0, 0, 100, 50);
        sourceGraphics.setColor(Color.BLUE);
        sourceGraphics.fillRect(100, 0, 100, 50);
        sourceGraphics.dispose();
        final var card = new AcpAttachmentCard(new AcpAttachment("image.png", "image/png", "", "", 0), "Remove image");
        card.setPreview(new ImageIcon(source));
        card.setSize(card.getPreferredSize());
        final BufferedImage painted = new BufferedImage(72, 72, BufferedImage.TYPE_INT_ARGB);
        final var graphics = painted.createGraphics();
        card.paint(graphics);
        graphics.dispose();
        assertEquals(Color.RED.getRGB(), painted.getRGB(20, 36));
        assertEquals(Color.BLUE.getRGB(), painted.getRGB(52, 36));
    }
}
