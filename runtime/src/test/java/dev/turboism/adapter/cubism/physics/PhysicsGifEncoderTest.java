package dev.turboism.adapter.cubism.physics;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import javax.imageio.ImageIO;
import javax.imageio.metadata.IIOMetadataNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PhysicsGifEncoderTest {
    @TempDir
    Path temporary;

    @Test
    void streamsExactFrameCountWithCorrectDurationAndInfiniteLoop() throws Exception {
        final Path raw = temporary.resolve("recording.gif");
        writeRecording(raw, 90, 30, false);
        assertGif(raw, 90, 300, 160, 100);
        try (var input = ImageIO.createImageInputStream(raw.toFile())) {
            final var reader = ImageIO.getImageReadersByFormatName("gif").next();
            try {
                reader.setInput(input);
                final var root = (IIOMetadataNode) reader.getImageMetadata(0).getAsTree("javax_imageio_gif_image_1.0");
                final var extension = (IIOMetadataNode)
                        root.getElementsByTagName("ApplicationExtension").item(0);
                assertEquals("NETSCAPE", extension.getAttribute("applicationID"));
                assertArrayEquals(new byte[] {1, 0, 0}, (byte[]) extension.getUserObject());
            } finally {
                reader.dispose();
            }
        }
    }

    @Test
    void realGifsicleHonorsLimitAndPreservesCountAndTiming() throws Exception {
        final String gifsicle = realGifsicle();
        final Path raw = temporary.resolve("input with spaces.gif");
        final Path output = temporary.resolve("output with spaces.gif");
        writeRecording(raw, 12, 30, true);
        PhysicsGifEncoder.optimize(gifsicle, raw, output, 20_000, 160, 100, 12);
        assertTrue(Files.size(output) <= 20_000);
        assertGif(output, 12, 40, -1, -1);
    }

    @Test
    void realGifsiclePreservesIdenticalFramesAndSupportsUnlockedPixelScaling() throws Exception {
        final String gifsicle = realGifsicle();
        final Path raw = temporary.resolve("input.gif");
        final Path output = temporary.resolve("output.gif");
        writeRecording(raw, 6, 30, false);
        PhysicsGifEncoder.optimize(gifsicle, raw, output, Long.MAX_VALUE, 80, 80, 6);
        assertGif(output, 6, 20, 80, 80);
    }

    @Test
    void rejectsImpossibleLimitAndMissingExecutable() throws Exception {
        assertThrows(
                java.io.IOException.class,
                () -> PhysicsGifEncoder.verifyExecutable(
                        temporary.resolve("missing gifsicle").toString()));
        final String gifsicle = realGifsicle();
        final Path raw = temporary.resolve("input.gif");
        writeRecording(raw, 3, 30, false);
        assertThrows(
                java.io.IOException.class,
                () -> PhysicsGifEncoder.optimize(gifsicle, raw, temporary.resolve("output.gif"), 1, 160, 100, 3));
    }

    @Test
    void interruptionStopsOptimizationBeforeStartingAnotherProcess() throws Exception {
        Thread.currentThread().interrupt();
        try {
            assertThrows(
                    InterruptedException.class,
                    () -> PhysicsGifEncoder.optimize(
                            "missing", temporary.resolve("input"), temporary.resolve("output"), 1, 1, 1, 1));
        } finally {
            Thread.interrupted();
        }
    }

    private static String realGifsicle() {
        final String executable = System.getenv("TURBOISM_TEST_GIFSICLE");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                executable != null, "set TURBOISM_TEST_GIFSICLE for real optimizer checks");
        return executable;
    }

    private static void writeRecording(final Path raw, final int frames, final int fps, final boolean noise)
            throws Exception {
        final Random random = new Random(91);
        try (var encoder = new PhysicsGifEncoder(raw, fps)) {
            for (int frame = 0; frame < frames; frame++) {
                final BufferedImage image = new BufferedImage(160, 100, BufferedImage.TYPE_INT_RGB);
                for (int y = 0; y < 100; y++) {
                    for (int x = 0; x < 160; x++) image.setRGB(x, y, noise ? random.nextInt(0x1000000) : 0x336699);
                }
                encoder.append(image);
            }
        }
    }

    private static void assertGif(
            final Path file, final int frames, final int duration, final int width, final int height) throws Exception {
        try (var input = ImageIO.createImageInputStream(file.toFile())) {
            final var reader = ImageIO.getImageReadersByFormatName("gif").next();
            try {
                reader.setInput(input);
                assertEquals(frames, reader.getNumImages(true));
                if (width > 0) {
                    assertEquals(width, reader.getWidth(0));
                    assertEquals(height, reader.getHeight(0));
                }
                int delay = 0;
                for (int frame = 0; frame < frames; frame++) {
                    final var root =
                            (IIOMetadataNode) reader.getImageMetadata(frame).getAsTree("javax_imageio_gif_image_1.0");
                    final var control = (IIOMetadataNode)
                            root.getElementsByTagName("GraphicControlExtension").item(0);
                    delay += Integer.parseInt(control.getAttribute("delayTime"));
                }
                assertEquals(duration, delay);
            } finally {
                reader.dispose();
            }
        }
    }
}
