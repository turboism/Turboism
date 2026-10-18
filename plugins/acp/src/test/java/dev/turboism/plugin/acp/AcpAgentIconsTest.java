package dev.turboism.plugin.acp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.Set;
import javax.swing.JLabel;
import org.junit.jupiter.api.Test;

final class AcpAgentIconsTest {
    @Test
    void allFunctionalIconsLoadTheirBundledMasksAtTheIntendedSize() {
        final JLabel component = new JLabel();
        component.setForeground(Color.BLUE);
        for (AcpIcons.Kind kind : AcpIcons.Kind.values()) {
            final var icon = AcpIcons.icon(kind);
            final int size = kind == AcpIcons.Kind.FAST || kind == AcpIcons.Kind.PLAN ? 16 : 20;
            assertEquals(size, icon.getIconWidth());
            assertEquals(size, icon.getIconHeight());
            final BufferedImage image = new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB);
            final var graphics = image.createGraphics();
            icon.paintIcon(component, graphics, 0, 0);
            graphics.dispose();
            int painted = 0;
            for (int y = 0; y < 20; y++)
                for (int x = 0; x < 20; x++) {
                    if ((image.getRGB(x, y) >>> 24) > 128) painted++;
                }
            assertTrue(painted > 8, kind.name());
        }
    }

    @Test
    void bundledAgentMarksAreDistinctAndFollowTheComponentThemeColor() {
        final Set<Integer> shapes = new HashSet<>();
        final JLabel component = new JLabel();
        for (AgentProfile profile : AgentCatalog.profiles()) {
            final var icon = AcpAgentIcons.icon(profile.id());
            for (Color foreground : new Color[] {Color.WHITE, Color.BLUE}) {
                component.setForeground(foreground);
                final BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
                final var graphics = image.createGraphics();
                icon.paintIcon(component, graphics, 0, 0);
                graphics.dispose();
                int painted = 0;
                int shape = 1;
                for (int y = 0; y < 16; y++) {
                    for (int x = 0; x < 16; x++) {
                        final int pixel = image.getRGB(x, y);
                        shape = shape * 31 + (pixel >>> 24);
                        if ((pixel >>> 24) > 128) {
                            painted++;
                            assertEquals(foreground.getRGB() & 0xFFFFFF, pixel & 0xFFFFFF);
                        }
                    }
                }
                assertTrue(painted > 8, profile.id());
                if (foreground == Color.WHITE) shapes.add(shape);
            }
        }
        assertEquals(AgentCatalog.profiles().size(), shapes.size());
    }
}
