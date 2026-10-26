package dev.turboism.validation.acp;

import java.awt.image.BufferedImage;
import java.awt.image.PixelGrabber;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import javax.swing.ImageIcon;
import javax.swing.JTextPane;
import javax.swing.text.StyleConstants;

/** Tests the actual Swing document, not the listener or a second renderer of the same bytes. */
final class AcpRenderUiEvidence {
    static final String TOOL_KEY = "d7db8783e602278cfb875e7388b51399cba3fd7543942806fa1478bfea4ee453";

    record Inspection(BufferedImage image, Map<String, Object> details) {}

    static BufferedImage match(JTextPane transcript, Map<String, ?> metadata, byte[] png, BufferedImage expected) {
        return inspect(transcript, metadata, png, expected).image();
    }

    static Inspection inspect(JTextPane transcript, Map<String, ?> metadata, byte[] png, BufferedImage expected) {
        if (!javax.swing.SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("EDT required");
        final String text = transcript.getText();
        final Map<?, ?> state = (Map<?, ?>) metadata.get("state");
        final var details = new java.util.LinkedHashMap<String, Object>();
        details.put("toolLabel", text.contains("Tool image · " + TOOL_KEY + " · reported:"));
        details.put("captureLabel", text.contains("capture=" + metadata.get("captureId")));
        details.put("documentLabel", text.contains("doc=" + state.get("documentId")));
        details.put("modelLabel", text.contains("model=" + state.get("modelId")));
        details.put("revisionLabel", text.contains("revision=" + state.get("historyRevision")));
        details.put("dimensionsLabel", text.contains("256×256"));
        details.put("noBase64Text", !text.contains(Base64.getEncoder().encodeToString(png)));
        details.put("transcriptChars", text.length());
        boolean provenance = details.values().stream().filter(Boolean.class::isInstance).allMatch(Boolean.TRUE::equals);
        if (expected == null || expected.getWidth() != 256 || expected.getHeight() != 256)
            return new Inspection(null, Map.copyOf(details));
        final var document = transcript.getStyledDocument();
        final int[] wanted = expected.getRGB(0, 0, 256, 256, null, 0, 256);
        BufferedImage matched = null;
        int count = 0;
        final var dimensions = new java.util.ArrayList<String>();
        for (int offset = 0; offset < document.getLength();) {
            final var element = document.getCharacterElement(offset);
            final var icon = StyleConstants.getIcon(element.getAttributes());
            if (icon != null) {
                count++;
                dimensions.add(icon.getIconWidth() + "x" + icon.getIconHeight());
                if (icon instanceof ImageIcon image && image.getIconWidth() == 256 && image.getIconHeight() == 256) {
                    final int[] actual = new int[wanted.length];
                    final var pixels = new PixelGrabber(image.getImage(), 0, 0, 256, 256, actual, 0, 256);
                    try {
                        if (pixels.grabPixels(1000) && Arrays.equals(wanted, actual)) {
                            matched = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
                            matched.setRGB(0, 0, 256, 256, actual, 0, 256);
                        }
                    } catch (InterruptedException failure) { Thread.currentThread().interrupt(); return new Inspection(null, Map.copyOf(details)); }
                }
            }
            offset = Math.max(offset + 1, element.getEndOffset());
        }
        details.put("iconCount", count);
        details.put("iconDimensions", dimensions);
        details.put("argbPixelsEqual", matched != null);
        return new Inspection(provenance && count == 1 ? matched : null, Map.copyOf(details));
    }
}
