package dev.turboism.adapter.cubism.optimization.modelopen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

final class FastPngDecoderParityTest {

    @Test
    void decodesAllFivePngFiltersBitIdenticallyAcrossSupportedColorTypes() throws Exception {
        final int width = 37;
        final int height = 29;
        final Random rng = new Random(0x5EED_CAFE);
        final int[] colorTypes = {2, 6};

        for (final int colorType : colorTypes) {
            final int bpp = colorType == 2 ? 3 : 4;
            final byte[][] rawRows = new byte[height][width * bpp];
            for (int y = 0; y < height; y++) {
                rng.nextBytes(rawRows[y]);
            }
            // Exercise every PNG filter mode (0..4) across consecutive rows and split IDAT into multiple chunks.
            final byte[] pngBytes = buildSyntheticPng(width, height, colorType, rawRows, 257);

            final BufferedImage refRaw = ImageIO.read(new ByteArrayInputStream(pngBytes));
            assertNotNull(refRaw);
            final int[] expected = refRaw.getRGB(0, 0, width, height, null, 0, width);

            final BufferedImage fast = FastPngDecoder.tryDecodeToIntArgb(pngBytes);
            assertNotNull(fast, "Expected fast decode for colorType=" + colorType);
            assertEquals(BufferedImage.TYPE_INT_ARGB, fast.getType());
            final int[] actual = ((DataBufferInt) fast.getRaster().getDataBuffer()).getData();
            assertArrayEquals(expected, actual, "Pixel mismatch for colorType=" + colorType);
        }

        // Grayscale color types delegate to ImageIO so JDK CS_GRAY conversion remains identical.
        assertNull(FastPngDecoder.tryDecodeToIntArgb(buildSyntheticPng(8, 8, 0, new byte[8][8], 64)));
        assertNull(FastPngDecoder.tryDecodeToIntArgb(buildSyntheticPng(8, 8, 4, new byte[8][16], 64)));
    }

    @Test
    void decodesImageIoWrittenPngsAndFallsBackSafelyOnUnsupportedInputs() throws Exception {
        final BufferedImage src = new BufferedImage(63, 47, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 47; y++) {
            for (int x = 0; x < 63; x++) {
                src.setRGB(x, y, ((x * 9 + y * 3) & 0xFF) << 24 | (x * 4) << 16 | (y * 5) << 8 | ((x ^ y) & 0xFF));
            }
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(src, "png", out));
        final byte[] bytes = out.toByteArray();

        final BufferedImage decoded = FastPngDecoder.tryDecodeToIntArgb(bytes);
        assertNotNull(decoded);
        assertArrayEquals(
                src.getRGB(0, 0, 63, 47, null, 0, 63),
                ((DataBufferInt) decoded.getRaster().getDataBuffer()).getData());

        // Non-PNG and indexed PNG must safely return null for ImageIO fallback.
        assertNull(FastPngDecoder.tryDecodeToIntArgb(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}));
        final BufferedImage indexed = new BufferedImage(16, 16, BufferedImage.TYPE_BYTE_INDEXED);
        final ByteArrayOutputStream idxOut = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(indexed, "png", idxOut));
        assertNull(FastPngDecoder.tryDecodeToIntArgb(idxOut.toByteArray()));
    }

    private static byte[] buildSyntheticPng(
            final int width, final int height, final int colorType, final byte[][] rawRows, final int maxIdatChunk)
            throws IOException {
        final int bpp = rawRows[0].length / width;
        final ByteArrayOutputStream unfiltered = new ByteArrayOutputStream();
        final byte[] zeroPrev = new byte[rawRows[0].length];
        for (int y = 0; y < height; y++) {
            final int filter = y % 5;
            final byte[] cur = rawRows[y];
            final byte[] prev = y == 0 ? zeroPrev : rawRows[y - 1];
            unfiltered.write(filter);
            for (int i = 0; i < cur.length; i++) {
                final int x = cur[i] & 0xFF;
                final int a = i >= bpp ? (cur[i - bpp] & 0xFF) : 0;
                final int b = prev[i] & 0xFF;
                final int c = i >= bpp ? (prev[i - bpp] & 0xFF) : 0;
                final int outByte =
                        switch (filter) {
                            case 0 -> x;
                            case 1 -> x - a;
                            case 2 -> x - b;
                            case 3 -> x - ((a + b) >>> 1);
                            case 4 -> x - paeth(a, b, c);
                            default -> throw new AssertionError();
                        };
                unfiltered.write(outByte & 0xFF);
            }
        }
        final byte[] rawStream = unfiltered.toByteArray();
        final Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
        deflater.setInput(rawStream);
        deflater.finish();
        final ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        final byte[] tmp = new byte[4096];
        while (!deflater.finished()) {
            final int n = deflater.deflate(tmp);
            if (n > 0) {
                compressed.write(tmp, 0, n);
            }
        }
        deflater.end();
        final byte[] zbytes = compressed.toByteArray();

        final ByteArrayOutputStream png = new ByteArrayOutputStream();
        png.write(new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});
        final ByteArrayOutputStream ihdr = new ByteArrayOutputStream();
        writeIntBe(ihdr, width);
        writeIntBe(ihdr, height);
        ihdr.write(8);
        ihdr.write(colorType);
        ihdr.write(0);
        ihdr.write(0);
        ihdr.write(0);
        writeChunk(png, "IHDR", ihdr.toByteArray());
        for (int off = 0; off < zbytes.length; off += maxIdatChunk) {
            final int chunkLen = Math.min(maxIdatChunk, zbytes.length - off);
            final byte[] slice = new byte[chunkLen];
            System.arraycopy(zbytes, off, slice, 0, chunkLen);
            writeChunk(png, "IDAT", slice);
        }
        writeChunk(png, "IEND", new byte[0]);
        return png.toByteArray();
    }

    private static int paeth(final int a, final int b, final int c) {
        final int p = a + b - c;
        final int pa = Math.abs(p - a);
        final int pb = Math.abs(p - b);
        final int pc = Math.abs(p - c);
        return (pa <= pb && pa <= pc) ? a : (pb <= pc ? b : c);
    }

    private static void writeChunk(final ByteArrayOutputStream out, final String type, final byte[] payload)
            throws IOException {
        writeIntBe(out, payload.length);
        final byte[] typeBytes = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        out.write(typeBytes);
        out.write(payload);
        final CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(payload);
        writeIntBe(out, (int) crc.getValue());
    }

    private static void writeIntBe(final ByteArrayOutputStream out, final int v) {
        out.write((v >>> 24) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }
}
