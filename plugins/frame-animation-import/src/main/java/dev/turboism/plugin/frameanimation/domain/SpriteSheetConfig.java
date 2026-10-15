package dev.turboism.plugin.frameanimation.domain;

/**
 * Configuration for slicing a sprite sheet texture.
 *
 * @param rows number of grid rows (>= 1)
 * @param columns number of grid columns (>= 1)
 * @param frameCount max frames to extract (0 or negative means rows * columns)
 * @param columnMajor true to scan columns top-to-bottom then left-to-right; false for left-to-right then top-to-bottom
 */
public record SpriteSheetConfig(int rows, int columns, int frameCount, boolean columnMajor) {

    public SpriteSheetConfig {
        if (rows <= 0) {
            throw new IllegalArgumentException("rows must be positive: " + rows);
        }
        if (columns <= 0) {
            throw new IllegalArgumentException("columns must be positive: " + columns);
        }
        final int maxFrames = rows * columns;
        if (frameCount <= 0 || frameCount > maxFrames) {
            frameCount = maxFrames;
        }
    }

    /** Creates a sprite sheet config for all grid cells in row-major order. */
    public static SpriteSheetConfig of(final int rows, final int columns) {
        return new SpriteSheetConfig(rows, columns, rows * columns, false);
    }

    /** Creates a sprite sheet config with an explicit frame count in row-major order. */
    public static SpriteSheetConfig of(final int rows, final int columns, final int frameCount) {
        return new SpriteSheetConfig(rows, columns, frameCount, false);
    }
}
