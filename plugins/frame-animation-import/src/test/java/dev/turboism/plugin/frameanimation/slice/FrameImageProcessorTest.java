package dev.turboism.plugin.frameanimation.slice;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.cubism.model.AuthorizedImageContent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class FrameImageProcessorTest {

    @Test
    void fitsWithinBudgetForSmallImageWithoutResize() {
        final BufferedImage img = new BufferedImage(300, 300, BufferedImage.TYPE_INT_ARGB);
        final BufferedImage fitted = FrameImageProcessor.fitWithinBudget(img);
        assertEquals(300, fitted.getWidth());
        assertEquals(300, fitted.getHeight());

        final AuthorizedImageContent grant = FrameImageProcessor.toAuthorizedImage(img, "test-small");
        assertNotNull(grant);
        assertEquals(300, grant.width());
        assertEquals(300, grant.height());
    }

    @Test
    void downscalesOversizedImageToFitBudget() {
        // 1254 x 1254 = 1,572,516 pixels, exceeds 1,048,576
        final BufferedImage oversized = new BufferedImage(1254, 1254, BufferedImage.TYPE_INT_ARGB);
        final BufferedImage fitted = FrameImageProcessor.fitWithinBudget(oversized);

        final long pixelCount = (long) fitted.getWidth() * fitted.getHeight();
        assertTrue(
                pixelCount <= FrameImageProcessor.MAX_PIXEL_COUNT,
                "pixel count must be <= MAX_PIXEL_COUNT: " + pixelCount);

        // Verification: AuthorizedImageContent.ofDimensions should NOT throw decoded overflow
        assertDoesNotThrow(() -> {
            final AuthorizedImageContent grant = FrameImageProcessor.toAuthorizedImage(oversized, "test-oversized");
            assertEquals(fitted.getWidth(), grant.width());
            assertEquals(fitted.getHeight(), grant.height());
        });
    }

    @Test
    void processesRealNumbersAssetFrameFromZip() throws Exception {
        final File zipFile = resolveAssetZip();
        if (zipFile == null || !zipFile.exists()) {
            return;
        }

        BufferedImage frame1 = null;
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().endsWith("frame_1.png")) {
                    frame1 = ImageIO.read(zis);
                    break;
                }
            }
        }

        assertNotNull(frame1, "frame_1.png should be present");
        assertEquals(1254, frame1.getWidth());
        assertEquals(1254, frame1.getHeight());

        final AuthorizedImageContent grant = FrameImageProcessor.toAuthorizedImage(frame1, "numbers-frame-1");
        assertNotNull(grant);
        final long totalPixels = (long) grant.width() * grant.height();
        assertTrue(totalPixels <= FrameImageProcessor.MAX_PIXEL_COUNT, "must satisfy SDK budget");
    }

    private static File resolveAssetZip() {
        final String envPath = System.getenv("NUMBERS_ASSET_ZIP");
        if (envPath != null && !envPath.isBlank()) {
            return new File(envPath);
        }
        final File inDocs = new File(System.getProperty("user.home"), "Documents/numbers_1_7_animation_assets.zip");
        return inDocs.exists() ? inDocs : null;
    }
}
