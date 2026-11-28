package dev.turboism.adapter.cubism.optimization.image;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

final class FastPngEncoderParityTest {

    @Test
    void decodedArgbPixelsMatchImageIoAcrossEdgeCases() throws IOException {
        final int[][] dimensions = {
            {1, 1},
            {2, 3},
            {7, 5},
            {64, 31},
            {257, 129},
            {512, 256}
        };
        final Random rng = new Random(0x5EED1234L);
        for (final int[] dim : dimensions) {
            final int w = dim[0];
            final int h = dim[1];
            final BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            final int[] expected = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    final int idx = y * w + x;
                    if ((x + y) % 5 == 0) {
                        // Preserve exact RGB even under zero alpha.
                        expected[idx] = rng.nextInt() & 0x00FFFFFF;
                    } else if ((x ^ y) % 7 == 0) {
                        expected[idx] = 0xFF000000 | (rng.nextInt() & 0x00FFFFFF);
                    } else {
                        expected[idx] = rng.nextInt();
                    }
                }
            }

            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            assertTrue(FastPngEncoder.writeIfSupported(img, out));

            final BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(out.toByteArray()));
            assertNotNull(decoded);
            final int[] actual = decoded.getRGB(0, 0, w, h, null, 0, w);
            assertArrayEquals(expected, actual, "Mismatch for " + w + "x" + h);

            final byte[] directBytes = FastPngEncoder.encodeArgbToBytes(expected, w, h);
            assertArrayEquals(out.toByteArray(), directBytes);
        }
    }

    @Test
    void unsupportedBufferedImageTypesReturnFalseForFallback() throws IOException {
        final BufferedImage abgr = new BufferedImage(8, 8, BufferedImage.TYPE_4BYTE_ABGR);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertFalse(FastPngEncoder.writeIfSupported(abgr, out));
        assertFalse(FastPngEncoder.writeIfSupported(null, out));
    }
}
