package dev.turboism.plugin.frameanimation.slice;

import dev.turboism.plugin.frameanimation.domain.FrameItem;
import dev.turboism.plugin.frameanimation.domain.SpriteSheetConfig;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Slices a sprite sheet texture into a sequence of individual frame images.
 */
public final class SpriteSheetSlicer {

    private SpriteSheetSlicer() {}

    /**
     * Slices the given sprite sheet into an ordered list of {@link FrameItem}s.
     *
     * @param sheet the input sprite sheet image
     * @param config slice configuration (rows, columns, frame count, scan order)
     * @return non-empty list of extracted frames
     */
    public static List<FrameItem> slice(final BufferedImage sheet, final SpriteSheetConfig config) {
        Objects.requireNonNull(sheet, "sheet");
        Objects.requireNonNull(config, "config");

        final int sheetWidth = sheet.getWidth();
        final int sheetHeight = sheet.getHeight();
        if (sheetWidth <= 0 || sheetHeight <= 0) {
            throw new IllegalArgumentException("sheet dimensions must be positive");
        }

        final int rows = config.rows();
        final int columns = config.columns();
        final int targetCount = config.frameCount();
        final double stepX = (double) sheetWidth / columns;
        final double stepY = (double) sheetHeight / rows;

        final List<FrameItem> frames = new ArrayList<>(targetCount);
        int frameIndex = 0;

        if (!config.columnMajor()) {
            for (int r = 0; r < rows && frameIndex < targetCount; r++) {
                for (int c = 0; c < columns && frameIndex < targetCount; c++) {
                    frames.add(extractFrame(sheet, r, c, stepX, stepY, sheetWidth, sheetHeight, frameIndex++));
                }
            }
        } else {
            for (int c = 0; c < columns && frameIndex < targetCount; c++) {
                for (int r = 0; r < rows && frameIndex < targetCount; r++) {
                    frames.add(extractFrame(sheet, r, c, stepX, stepY, sheetWidth, sheetHeight, frameIndex++));
                }
            }
        }

        return List.copyOf(frames);
    }

    private static FrameItem extractFrame(
            final BufferedImage sheet,
            final int r,
            final int c,
            final double stepX,
            final double stepY,
            final int sheetWidth,
            final int sheetHeight,
            final int index) {
        final int x0 = (int) Math.round(c * stepX);
        final int x1 = Math.min((int) Math.round((c + 1) * stepX), sheetWidth);
        final int y0 = (int) Math.round(r * stepY);
        final int y1 = Math.min((int) Math.round((r + 1) * stepY), sheetHeight);

        final int w = Math.max(1, x1 - x0);
        final int h = Math.max(1, y1 - y0);

        final BufferedImage cropped = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g2 = cropped.createGraphics();
        try {
            g2.drawImage(sheet, 0, 0, w, h, x0, y0, x0 + w, y0 + h, null);
        } finally {
            g2.dispose();
        }

        return new FrameItem(index, "Frame_" + index, cropped);
    }
}
