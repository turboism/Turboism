package dev.turboism.validation.acp;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;

/** Offline assertions for the independent real-UI evidence matcher. */
public final class AcpRenderUiEvidenceSelfTest {
    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                final var expected = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
                expected.setRGB(20, 30, 0xff123456);
                final var encoded = new ByteArrayOutputStream();
                ImageIO.write(expected, "png", encoded);
                final byte[] png = encoded.toByteArray();
                final var metadata = Map.<String, Object>of("captureId", "capture-1", "state",
                        Map.of("documentId", "doc-1", "modelId", "model-1", "historyRevision", 4));
                final String label = "Tool image · " + AcpRenderUiEvidence.TOOL_KEY + " · reported: capture=capture-1 · doc=doc-1 · model=model-1 · revision=4 · 256×256";
                final var pane = new JTextPane();
                pane.setText(label);
                check(AcpRenderUiEvidence.match(pane, metadata, png, expected) == null, "missing icon");
                addIcon(pane, expected);
                check(AcpRenderUiEvidence.match(pane, metadata, png, expected) != null, "matching icon/provenance");
                final var wrong = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
                check(AcpRenderUiEvidence.match(pane, metadata, png, wrong) == null, "wrong pixels");
                pane.setText(label.replace("capture-1", "stale-capture")); addIcon(pane, expected);
                check(AcpRenderUiEvidence.match(pane, metadata, png, expected) == null, "stale identity");
                pane.setText(label.replace(AcpRenderUiEvidence.TOOL_KEY, "wrong-tool-key")); addIcon(pane, expected);
                check(AcpRenderUiEvidence.match(pane, metadata, png, expected) == null, "wrong tool identity");
                pane.setText(label + Base64.getEncoder().encodeToString(png)); addIcon(pane, expected);
                check(AcpRenderUiEvidence.match(pane, metadata, png, expected) == null, "base64 in text");
                pane.setText(label); addIcon(pane, expected); addIcon(pane, expected);
                check(AcpRenderUiEvidence.match(pane, metadata, png, expected) == null, "duplicate icons");
                System.out.println("ACP render UI evidence: 7 assertions passed");
            } catch (Exception failure) { throw new RuntimeException(failure); }
        });
    }
    private static void addIcon(JTextPane pane, BufferedImage image) throws Exception {
        final var attributes = new SimpleAttributeSet(); StyleConstants.setIcon(attributes, new ImageIcon(image));
        pane.getStyledDocument().insertString(pane.getDocument().getLength(), " ", attributes);
    }
    private static void check(boolean condition, String label) { if (!condition) throw new AssertionError(label); }
}
