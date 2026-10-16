package dev.turboism.plugin.frameanimation.slice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.plugin.frameanimation.domain.FrameItem;
import dev.turboism.plugin.frameanimation.domain.SpriteSheetConfig;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class SpriteSheetSlicerTest {

    @Test
    void slicesSyntheticGridCorrectly() {
        final BufferedImage sheet = new BufferedImage(200, 100, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g2 = sheet.createGraphics();
        try {
            g2.setColor(Color.RED);
            g2.fillRect(0, 0, 100, 100);
            g2.setColor(Color.BLUE);
            g2.fillRect(100, 0, 100, 100);
        } finally {
            g2.dispose();
        }

        final SpriteSheetConfig config = SpriteSheetConfig.of(1, 2);
        final List<FrameItem> frames = SpriteSheetSlicer.slice(sheet, config);

        assertEquals(2, frames.size());
        assertEquals(0, frames.get(0).index());
        assertEquals("Frame_0", frames.get(0).name());
        assertEquals(100, frames.get(0).width());
        assertEquals(100, frames.get(0).height());

        assertEquals(1, frames.get(1).index());
        assertEquals("Frame_1", frames.get(1).name());
        assertEquals(100, frames.get(1).width());
        assertEquals(100, frames.get(1).height());
    }

    @Test
    void slicesRealNumbersAssetZipWhenAvailable() throws Exception {
        final File zipFile = resolveAssetZip();
        if (zipFile == null || !zipFile.exists()) {
            return; // Skip if environment does not have the file
        }

        BufferedImage spriteSheet = null;
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().endsWith("sprite_sheet.png")) {
                    spriteSheet = ImageIO.read(zis);
                    break;
                }
            }
        }

        assertNotNull(spriteSheet, "sprite_sheet.png should be present in numbers_1_7_animation_assets.zip");
        assertEquals(1774, spriteSheet.getWidth());
        assertEquals(887, spriteSheet.getHeight());

        // 2 rows, 4 columns, total 8 slots, but frameCount = 7 (excluding empty 8th slot)
        final SpriteSheetConfig config = SpriteSheetConfig.of(2, 4, 7);
        final List<FrameItem> frames = SpriteSheetSlicer.slice(spriteSheet, config);

        assertEquals(7, frames.size(), "should extract exactly 7 frames");
        for (int i = 0; i < 7; i++) {
            final FrameItem frame = frames.get(i);
            assertEquals(i, frame.index());
            assertEquals("Frame_" + i, frame.name());
            assertTrue(frame.width() >= 443 && frame.width() <= 444, "frame width ~443");
            assertTrue(frame.height() >= 443 && frame.height() <= 444, "frame height ~443");
        }
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> SpriteSheetConfig.of(0, 4));
        assertThrows(IllegalArgumentException.class, () -> SpriteSheetConfig.of(2, -1));
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
