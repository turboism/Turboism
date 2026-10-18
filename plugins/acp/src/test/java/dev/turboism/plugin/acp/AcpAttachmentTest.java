package dev.turboism.plugin.acp;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class AcpAttachmentTest {
    @TempDir
    Path directory;

    @Test
    void snapshotsFilesAndUsesCapabilityAppropriateContentBlocks() throws Exception {
        final Path file = directory.resolve("notes.txt");
        Files.writeString(file, "original");
        final AcpAttachment attachment = AcpAttachment.fromPath(file);
        Files.writeString(file, "changed");
        assertEquals(
                "original",
                new String(Base64.getDecoder().decode(attachment.data()), java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("resource", attachment.promptBlock(false, true).get("type"));
        assertEquals("resource_link", attachment.promptBlock(false, false).get("type"));
        assertEquals(
                file.toUri().toString(), attachment.promptBlock(false, false).get("uri"));
        assertThrows(java.io.IOException.class, () -> AcpAttachment.fromPath(directory));
    }

    @Test
    void clipboardImagesRoundTripAndUnsupportedClipboardContentIsNotSilentlyDropped() throws Exception {
        final AcpAttachment image =
                AcpAttachment.fromClipboard(new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB));
        assertEquals("image", image.promptBlock(true, false).get("type"));
        assertEquals("resource", image.promptBlock(false, true).get("type"));
        assertThrows(IllegalArgumentException.class, () -> image.promptBlock(false, false));
        final var received = AcpAttachment.receivedImage(image.mimeType(), image.data());
        assertTrue(received.preview().getIconWidth() <= 560);
        assertTrue(received.preview().getIconHeight() <= 320);
        assertThrows(IllegalArgumentException.class, () -> AcpAttachment.receivedImage("image/png", "invalid!"));
        final Path huge = directory.resolve("huge.bin");
        Files.write(huge, new byte[AcpAttachment.MAX_BYTES + 1]);
        assertThrows(java.io.IOException.class, () -> AcpAttachment.fromPath(huge));
    }
}
