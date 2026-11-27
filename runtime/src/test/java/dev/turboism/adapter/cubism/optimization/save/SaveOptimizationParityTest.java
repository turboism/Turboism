package dev.turboism.adapter.cubism.optimization.save;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.StringJoiner;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies exact functional parity for primitive array formatting, parallel ZIP construction,
 * zero-copy entry buffering, buffered random-access file writes, and multi-format lossless PNG
 * encoding.
 */
final class SaveOptimizationParityTest {

    @Test
    void floatAndIntFormattingMatchesJavaToStringAcrossEdgeCases() {
        final float[] edgeFloats = {
            0.0f,
            -0.0f,
            1.0f,
            -1.0f,
            Float.NaN,
            Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY,
            Float.MIN_VALUE,
            Float.MIN_NORMAL,
            Float.MAX_VALUE,
            1e-7f,
            12345.678f
        };
        final StringJoiner expectedFloats = new StringJoiner(" ");
        for (final float v : edgeFloats) {
            expectedFloats.add(Float.toString(v));
        }
        assertEquals(expectedFloats.toString(), PrimitiveArraySerializerBridge.formatFloatArray(edgeFloats, " "));
        assertEquals("", PrimitiveArraySerializerBridge.formatFloatArray(new float[0], " "));
        assertEquals("-0.0", PrimitiveArraySerializerBridge.formatFloatArray(new float[] {-0.0f}, " "));

        final Random rng = new Random(0x5A17BEEFL);
        final float[] randomFloats = new float[4_000];
        final StringJoiner expectedRandomFloats = new StringJoiner(" ");
        for (int i = 0; i < randomFloats.length; i++) {
            final float f = Float.intBitsToFloat(rng.nextInt());
            randomFloats[i] = f;
            expectedRandomFloats.add(Float.toString(f));
        }
        assertEquals(
                expectedRandomFloats.toString(), PrimitiveArraySerializerBridge.formatFloatArray(randomFloats, " "));

        final int[] edgeInts = {
            Integer.MIN_VALUE,
            Integer.MIN_VALUE + 1,
            -1_000_000_000,
            -999_999,
            -100,
            -10,
            -9,
            -1,
            0,
            1,
            9,
            10,
            99,
            100,
            999,
            1000,
            99_999_999,
            1_000_000_000,
            Integer.MAX_VALUE
        };
        final StringJoiner expectedInts = new StringJoiner(" ");
        for (final int v : edgeInts) {
            expectedInts.add(Integer.toString(v));
        }
        assertEquals(expectedInts.toString(), PrimitiveArraySerializerBridge.formatIntArray(edgeInts, " "));
        assertEquals("", PrimitiveArraySerializerBridge.formatIntArray(new int[0], " "));
        assertEquals(
                Integer.toString(Integer.MIN_VALUE),
                PrimitiveArraySerializerBridge.formatIntArray(new int[] {Integer.MIN_VALUE}, " "));

        final int[] randomInts = new int[4_000];
        final StringJoiner expectedRandomInts = new StringJoiner(", ");
        for (int i = 0; i < randomInts.length; i++) {
            final int v = rng.nextInt();
            randomInts[i] = v;
            expectedRandomInts.add(Integer.toString(v));
        }
        assertEquals(expectedRandomInts.toString(), PrimitiveArraySerializerBridge.formatIntArray(randomInts, ", "));
    }

    @Test
    void parallelZipEntryBuilderProducesStandardReadableZipAcrossAllSizes(@TempDir final Path tempDir)
            throws IOException {
        final Random rng = new Random(0xCAFEBABEL);
        final int[] sizes = {0, 17, 100 * 1024, 512 * 1024, 512 * 1024 + 1, 1_300_000, 2_700_000};
        for (int sIdx = 0; sIdx < sizes.length; sIdx++) {
            final int size = sizes[sIdx];
            final byte[] payload = new byte[size];
            for (int i = 0; i < size; i++) {
                payload[i] = (i & 3) == 0 ? (byte) rng.nextInt(96) : (byte) ('0' + (i % 10));
            }

            final int level = (sIdx & 1) == 0 ? 1 : 5;
            final byte[] zipBytes;
            try (ParallelZipEntryBuilder builder = new ParallelZipEntryBuilder(level)) {
                int pos = 0;
                while (pos < payload.length) {
                    final int step = Math.min(payload.length - pos, 13_337);
                    builder.write(payload, pos, step);
                    pos += step;
                }
                zipBytes = builder.toZipBytes();
            }

            try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
                final ZipEntry entry = zis.getNextEntry();
                assertNotNull(entry, "Missing contents entry for size=" + size);
                assertEquals("contents", entry.getName());
                final byte[] actual = zis.readAllBytes();
                assertArrayEquals(payload, actual, "Payload mismatch for size=" + size);
                assertEquals(null, zis.getNextEntry());
            }

            final Path zipFilePath = tempDir.resolve("test-" + size + ".zip");
            Files.write(zipFilePath, zipBytes);
            try (ZipFile zipFile = new ZipFile(zipFilePath.toFile())) {
                final ZipEntry entry = zipFile.getEntry("contents");
                assertNotNull(entry);
                final CRC32 expectedCrc = new CRC32();
                expectedCrc.update(payload);
                assertEquals(expectedCrc.getValue(), entry.getCrc());
                assertArrayEquals(payload, zipFile.getInputStream(entry).readAllBytes());
            }
        }
    }

    @Test
    void zeroCopyRawEntryStreamReusesSingleArrayAndFallsBackOnMultipleWrites() throws IOException {
        final byte[] owned = "png-payload-0123456789".getBytes(StandardCharsets.UTF_8);
        try (ByteArrayOutputStream rawStream = ArchiveWriterSaveBridge.newEntryBufferStream("RAW")) {
            rawStream.write(owned);
            assertSame(owned, rawStream.toByteArray());
        }

        try (ByteArrayOutputStream multiStream = ArchiveWriterSaveBridge.newEntryBufferStream("RAW")) {
            multiStream.write(owned);
            multiStream.write('-');
            multiStream.write(owned, 4, 7);
            final byte[] actual = multiStream.toByteArray();
            assertEquals("png-payload-0123456789-payload", new String(actual, StandardCharsets.UTF_8));
        }

        try (ByteArrayOutputStream fastStream = ArchiveWriterSaveBridge.newEntryBufferStream("FAST")) {
            fastStream.write(owned);
            assertArrayEquals(owned, fastStream.toByteArray());
        }
    }

    @Test
    void bufferedRandomAccessWriterMatchesUnbufferedFileLayoutAndLeavesPayloadImmutable(@TempDir final Path tempDir)
            throws IOException {
        final Path bufferedFile = tempDir.resolve("buffered.dat");
        final Path stockFile = tempDir.resolve("stock.dat");

        final byte[] largePayload = new byte[300_000];
        new Random(0x11223344L).nextBytes(largePayload);
        final byte[] snapshotBefore = largePayload.clone();
        final int negativeKey = 0x80123456;

        final long bufferedOffsetSlot;
        try (RandomAccessFile raf = new RandomAccessFile(bufferedFile.toFile(), "rw")) {
            final BufferedRandomAccessWriter writer = new BufferedRandomAccessWriter(raf);
            writer.writeByte((byte) 0x5A, 0);
            writer.writeShort((short) 0x1234, negativeKey);
            writer.writeInt(0xDEADBEEF, negativeKey);
            writer.writeZeros(7);
            bufferedOffsetSlot = writer.getFilePointer();
            writer.writeLong(0L, negativeKey);
            final long payloadStart = writer.getFilePointer();
            writer.writeBytes(largePayload, 0, largePayload.length, negativeKey, false);
            assertArrayEquals(snapshotBefore, largePayload, "Input array must stay immutable when obfuscated");
            writer.writeBytes(largePayload, 100, 50_000, 0, false);
            writer.writeByte((byte) 98, 0);
            writer.writeByte((byte) 99, 0);
            writer.seek(bufferedOffsetSlot);
            writer.writeLong(payloadStart, negativeKey);
            writer.flushAndGetFile().getChannel().force(true);
            writer.close();
        }

        final long stockOffsetSlot;
        try (RandomAccessFile raf = new RandomAccessFile(stockFile.toFile(), "rw")) {
            raf.writeByte((byte) (0x5A ^ 0));
            raf.writeShort((short) (0x1234 ^ negativeKey));
            raf.writeInt(0xDEADBEEF ^ negativeKey);
            for (int i = 0; i < 7; i++) {
                raf.writeByte(0);
            }
            stockOffsetSlot = raf.getFilePointer();
            final long mask = (((long) negativeKey) << 32) | ((long) negativeKey);
            raf.writeLong(0L ^ mask);
            final long payloadStart = raf.getFilePointer();
            final byte[] tmp = largePayload.clone();
            for (int i = 0; i < tmp.length; i++) {
                tmp[i] = (byte) (tmp[i] ^ negativeKey);
            }
            raf.write(tmp);
            raf.write(largePayload, 100, 50_000);
            raf.writeByte(98);
            raf.writeByte(99);
            raf.seek(stockOffsetSlot);
            raf.writeLong(payloadStart ^ mask);
            raf.getChannel().force(true);
        }

        assertEquals(stockOffsetSlot, bufferedOffsetSlot);
        assertArrayEquals(Files.readAllBytes(stockFile), Files.readAllBytes(bufferedFile));
    }

    @Test
    void losslessPngEncoderMatchesImageIoAcrossSupportedBufferedImageTypes(@TempDir final Path tempDir)
            throws IOException {
        final Random rng = new Random(0x98765432L);
        final int[] types = {
            BufferedImage.TYPE_INT_ARGB,
            BufferedImage.TYPE_4BYTE_ABGR,
            BufferedImage.TYPE_INT_RGB,
            BufferedImage.TYPE_3BYTE_BGR
        };
        for (final int imageType : types) {
            final int w = 73;
            final int h = 41;
            final BufferedImage img = new BufferedImage(w, h, imageType);
            final int[] expected = new int[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    final int argb =
                            imageType == BufferedImage.TYPE_INT_RGB || imageType == BufferedImage.TYPE_3BYTE_BGR
                                    ? (0xFF000000 | rng.nextInt(0x01000000))
                                    : rng.nextInt();
                    img.setRGB(x, y, argb);
                    expected[y * w + x] = img.getRGB(x, y);
                }
            }

            final ByteArrayOutputStream streamOut = new ByteArrayOutputStream();
            assertTrue(ImageSaveFastPngSupport.trySavePng(img, streamOut, "PNG"));
            final BufferedImage fromStream = ImageIO.read(new ByteArrayInputStream(streamOut.toByteArray()));
            assertNotNull(fromStream);
            assertArrayEquals(expected, fromStream.getRGB(0, 0, w, h, null, 0, w), "Stream mismatch for " + imageType);

            final Path fileOut = tempDir.resolve("img-" + imageType + ".png");
            assertTrue(ImageSaveFastPngSupport.trySavePng(img, fileOut.toFile(), "png"));
            final BufferedImage fromFile = ImageIO.read(fileOut.toFile());
            assertNotNull(fromFile);
            assertArrayEquals(expected, fromFile.getRGB(0, 0, w, h, null, 0, w), "File mismatch for " + imageType);

            assertArrayEquals(streamOut.toByteArray(), ImageSaveFastPngSupport.encodePngIfSupported(img));
        }

        final BufferedImage premul = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB_PRE);
        final Path untouched = tempDir.resolve("premul.png");
        assertFalse(ImageSaveFastPngSupport.trySavePng(premul, untouched.toFile(), "png"));
        assertFalse(Files.exists(untouched));
    }
}
