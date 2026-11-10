package dev.turboism.plugin.physicseditor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApngEncoderTest {

    private static final int WIDTH = 8;
    private static final int HEIGHT = 6;
    private static final int DELAY_MILLIS = 33;

    @Test
    void writesAValidAnimatedPngWithEveryFrameAndChunkChecksum(final @TempDir Path directory) throws Exception {
        final Path file = directory.resolve("recording.png");
        try (var encoder = new ApngEncoder(file, WIDTH, HEIGHT)) {
            encoder.append(frame(0x40), DELAY_MILLIS);
            encoder.append(frame(0x80), DELAY_MILLIS);
        }

        final List<Chunk> chunks = chunks(Files.readAllBytes(file));
        assertEquals(
                List.of("IHDR", "acTL", "fcTL", "IDAT", "fcTL", "fdAT", "IEND"),
                chunks.stream().map(Chunk::type).toList(),
                "chunk order defines the animation");
        for (Chunk chunk : chunks) {
            assertEquals(chunk.crc(), chunk.computedCrc(), "checksum of " + chunk.type());
        }
        final Chunk header = chunks.get(0);
        assertEquals(WIDTH, readInt(header.data(), 0), "declared width");
        assertEquals(HEIGHT, readInt(header.data(), 4), "declared height");
        assertEquals(2, readInt(chunks.get(1).data(), 0), "the frame count is patched when the file closes");
        assertEquals(0, readInt(chunks.get(1).data(), 4), "an infinite loop");
    }

    @Test
    void frameControlCarriesTheDelayInThousandths(final @TempDir Path directory) throws Exception {
        final Path file = directory.resolve("timing.png");
        try (var encoder = new ApngEncoder(file, WIDTH, HEIGHT)) {
            encoder.append(frame(0x20), DELAY_MILLIS);
        }
        final Chunk control = chunks(Files.readAllBytes(file)).get(2);
        assertEquals("fcTL", control.type());
        assertEquals(DELAY_MILLIS, ((control.data()[20] & 0xFF) << 8) | (control.data()[21] & 0xFF), "delay numerator");
        assertEquals(1000, ((control.data()[22] & 0xFF) << 8) | (control.data()[23] & 0xFF), "delay denominator");
    }

    @Test
    void rejectsAFrameWhoseSizeDiffersFromTheDeclaredCanvas(final @TempDir Path directory) throws Exception {
        final Path file = directory.resolve("mismatch.png");
        assertThrows(IOException.class, () -> {
            try (var encoder = new ApngEncoder(file, WIDTH, HEIGHT)) {
                encoder.append(new BufferedImage(WIDTH + 1, HEIGHT, BufferedImage.TYPE_INT_ARGB), DELAY_MILLIS);
            }
        });
    }

    private static BufferedImage frame(final int shade) {
        final BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                image.setRGB(x, y, 0xFF000000 | (shade << 16) | (shade << 8) | shade);
            }
        }
        return image;
    }

    private static List<Chunk> chunks(final byte[] png) {
        final List<Chunk> chunks = new ArrayList<>();
        int offset = 8;
        while (offset + 12 <= png.length) {
            final int length = readInt(png, offset);
            final String type = new String(png, offset + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
            final byte[] data = java.util.Arrays.copyOfRange(png, offset + 8, offset + 8 + length);
            final int crc = readInt(png, offset + 8 + length);
            chunks.add(new Chunk(type, data, crc));
            offset += 12 + length;
            if (type.equals("IEND")) break;
        }
        return chunks;
    }

    private static int readInt(final byte[] data, final int offset) {
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    private record Chunk(String type, byte[] data, int crc) {
        int computedCrc() {
            final CRC32 digest = new CRC32();
            digest.update(type.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            digest.update(data);
            return (int) digest.getValue();
        }
    }
}
