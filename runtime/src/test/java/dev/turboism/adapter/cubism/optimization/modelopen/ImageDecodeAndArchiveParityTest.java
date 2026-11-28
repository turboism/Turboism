package dev.turboism.adapter.cubism.optimization.modelopen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

final class ImageDecodeAndArchiveParityTest {

    @Test
    void memoryCacheStreamDecodesPngIdenticallyWithoutChangingGlobalImageIoFlag() throws Exception {
        final boolean initialUseCache = ImageIO.getUseCache();
        final BufferedImage expected = new BufferedImage(37, 29, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int x = 0; x < expected.getWidth(); x++) {
                expected.setRGB(x, y, ((x * 7 + y * 13) & 0xFF) << 24 | (x * 5) << 16 | (y * 9) << 8 | 0xC3);
            }
        }
        final ByteArrayOutputStream pngOut = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(expected, "png", pngOut));
        final byte[] pngBytes = pngOut.toByteArray();

        final ByteArrayInputStream bais = new ByteArrayInputStream(pngBytes);
        final BufferedImage decoded = ImageDecodeBridge.readBufferedImage(bais, bais);
        assertNotNull(decoded);
        assertArrayEquals(expected.getRGB(0, 0, 37, 29, null, 0, 37), decoded.getRGB(0, 0, 37, 29, null, 0, 37));

        final ByteArrayInputStream empty = new ByteArrayInputStream(new byte[] {1, 2, 3, 4});
        assertNull(ImageDecodeBridge.readBufferedImage(empty, empty));
        assertEquals(initialUseCache, ImageIO.getUseCache());
    }

    @Test
    void rawAndSizedZipReadsReturnExactBytesAndHandleShortOrUnknownEntries() throws Exception {
        final byte[] payload =
                "CubismArchivePayload_0123456789ABCDEF".repeat(250).getBytes(StandardCharsets.UTF_8);

        final InputStream rawStream = ArchiveReadBridge.wrapRawBytes(payload);
        final byte[] directClaim = ArchiveReadBridge.readAllBytes(rawStream);
        assertSame(payload, directClaim);
        assertEquals(-1, rawStream.read());

        final InputStream partialRaw = ArchiveReadBridge.wrapRawBytes(payload);
        assertEquals(payload[0] & 0xFF, partialRaw.read());
        final byte[] remainder = ArchiveReadBridge.readAllBytes(partialRaw);
        assertEquals(payload.length - 1, remainder.length);
        assertArrayEquals(java.util.Arrays.copyOfRange(payload, 1, payload.length), remainder);

        final ByteArrayOutputStream zipBuf = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(zipBuf)) {
            final ZipEntry entry = new ZipEntry("model.xml");
            entry.setSize(payload.length);
            zos.putNextEntry(entry);
            zos.write(payload);
            zos.closeEntry();
        }
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBuf.toByteArray()))) {
            final ZipEntry entry = zis.getNextEntry();
            final InputStream wrapped = ArchiveReadBridge.wrapZipEntry(zis, entry);
            assertArrayEquals(payload, ArchiveReadBridge.readAllBytes(wrapped));
        }
    }

    @Test
    void factorySetupTokenConsumesOnlyMatchingUnmutatedModelAndPoolBoundsHold() {
        final Object modelA = new Object();
        final Object modelB = new Object();
        ModelOpenFlowBridge.beginFactoryOpen();
        ModelOpenFlowBridge.markFactorySetupCompleted(modelA);
        assertFalse(ModelOpenFlowBridge.consumeFactorySetupCompleted(modelB));
        assertFalse(ModelOpenFlowBridge.consumeFactorySetupCompleted(modelA));

        ModelOpenFlowBridge.markFactorySetupCompleted(modelA);
        assertTrue(ModelOpenFlowBridge.consumeFactorySetupCompleted(modelA));
        assertFalse(ModelOpenFlowBridge.consumeFactorySetupCompleted(modelA));

        ModelOpenFlowBridge.markFactorySetupCompleted(modelA);
        ModelOpenFlowBridge.beginFactoryOpen();
        assertFalse(ModelOpenFlowBridge.consumeFactorySetupCompleted(modelA));

        assertEquals(2, ImageDecodeBridge.computeWorkerCount(1));
        assertEquals(2, ImageDecodeBridge.computeWorkerCount(3));
        assertEquals(6, ImageDecodeBridge.computeWorkerCount(7));
        assertEquals(6, ImageDecodeBridge.computeWorkerCount(32));

        assertEquals(33_554_432L, ImageDecodeBridge.computeMaxInFlightPixels(0L));
        assertEquals(33_554_432L, ImageDecodeBridge.computeMaxInFlightPixels(512L << 20));
        assertEquals(33_554_432L, ImageDecodeBridge.computeMaxInFlightPixels(1L << 30));
        assertEquals(67_108_864L, ImageDecodeBridge.computeMaxInFlightPixels(2L << 30));
        assertEquals(134_217_728L, ImageDecodeBridge.computeMaxInFlightPixels(4L << 30));
        assertEquals(201_326_592L, ImageDecodeBridge.computeMaxInFlightPixels(6L << 30));
        assertEquals(201_326_592L, ImageDecodeBridge.computeMaxInFlightPixels(Long.MAX_VALUE));
    }

    @Test
    void immutableImageStreamClaimsBackingArrayZeroCopyAndPreservesProgressWrapper() throws Exception {
        final BufferedImage expected = new BufferedImage(23, 19, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int x = 0; x < expected.getWidth(); x++) {
                expected.setRGB(x, y, ((x * 9 + y * 3) & 0xFF) << 24 | (x * 11) << 16 | (y * 7) << 8 | 0x5A);
            }
        }
        final ByteArrayOutputStream pngOut = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(expected, "png", pngOut));
        final byte[] pngBytes = pngOut.toByteArray();
        final byte[] snapshot = pngBytes.clone();

        final ByteArrayInputStream directStream = ImageDecodeBridge.wrapImmutableImageBytes(pngBytes);
        assertSame(pngBytes, ImageDecodeBridge.extractRawBytes(directStream));
        assertEquals(-1, directStream.read());

        final ByteArrayInputStream partialStream = ImageDecodeBridge.wrapImmutableImageBytes(pngBytes);
        assertEquals(pngBytes[0] & 0xFF, partialStream.read());
        assertArrayEquals(
                Arrays.copyOfRange(pngBytes, 1, pngBytes.length), ImageDecodeBridge.extractRawBytes(partialStream));

        final ByteArrayInputStream decodeStream = ImageDecodeBridge.wrapImmutableImageBytes(pngBytes);
        final BufferedImage directDecoded =
                ImageDecodeBridge.readBufferedImage(decodeStream, decodeStream, BufferedImage.TYPE_INT_ARGB);
        assertNotNull(directDecoded);
        assertEquals(BufferedImage.TYPE_INT_ARGB, directDecoded.getType());
        assertArrayEquals(expected.getRGB(0, 0, 23, 19, null, 0, 23), directDecoded.getRGB(0, 0, 23, 19, null, 0, 23));
        assertArrayEquals(snapshot, pngBytes);

        final AtomicInteger bytesThroughWrapper = new AtomicInteger();
        final ByteArrayInputStream originalStream = ImageDecodeBridge.wrapImmutableImageBytes(pngBytes);
        final InputStream progressWrapped = new FilterInputStream(originalStream) {
            @Override
            public int read() throws IOException {
                final int b = super.read();
                if (b >= 0) {
                    bytesThroughWrapper.incrementAndGet();
                }
                return b;
            }

            @Override
            public int read(final byte[] b, final int off, final int len) throws IOException {
                final int n = super.read(b, off, len);
                if (n > 0) {
                    bytesThroughWrapper.addAndGet(n);
                }
                return n;
            }
        };
        final BufferedImage wrappedDecoded =
                ImageDecodeBridge.readBufferedImage(progressWrapped, originalStream, BufferedImage.TYPE_INT_ARGB);
        assertNotNull(wrappedDecoded);
        assertEquals(pngBytes.length, bytesThroughWrapper.get());
        assertArrayEquals(expected.getRGB(0, 0, 23, 19, null, 0, 23), wrappedDecoded.getRGB(0, 0, 23, 19, null, 0, 23));
    }
}
