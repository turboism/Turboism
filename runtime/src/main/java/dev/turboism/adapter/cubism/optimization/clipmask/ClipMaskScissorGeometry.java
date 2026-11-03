package dev.turboism.adapter.cubism.optimization.clipmask;

/**
 * Pure projection and rectangle math for scissor-limited clipping-mask clears.
 *
 * <p>A rectangle is {@code {minX, minY, maxX, maxY}} with an exclusive maximum, in
 * OpenGL window coordinates (origin bottom-left). The projection mirrors the host's
 * shader: {@code projection * worldToCamera * localToWorld * position}. Matrices are
 * the host's column-major {@code GMatrix44.a()} arrays.</p>
 */
final class ClipMaskScissorGeometry {

    static final int RECT_VALUES = 4;

    private ClipMaskScissorGeometry() {}

    /**
     * Reads the local-space bounds of a mesh position array.
     *
     * @param positions packed position array
     * @param step      floats per vertex (2 or 3 on reviewed hosts)
     * @param bounds    output {@code {minX,minY,minZ,maxX,maxY,maxZ}}
     * @return {@code false} for an empty or malformed position array
     */
    static boolean localBounds(final float[] positions, final int step, final float[] bounds) {
        if (positions == null || bounds == null || bounds.length < 6 || step < 2 || positions.length < step) {
            return false;
        }
        final int vertices = positions.length / step;
        if (vertices == 0) return false;

        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        float maxZ = Float.NEGATIVE_INFINITY;
        for (int vertex = 0; vertex < vertices; vertex++) {
            final int base = vertex * step;
            final float x = positions[base];
            final float y = positions[base + 1];
            final float z = step >= 3 ? positions[base + 2] : 0.0f;
            if (!finite(x) || !finite(y) || !finite(z)) return false;
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }
        bounds[0] = minX;
        bounds[1] = minY;
        bounds[2] = minZ;
        bounds[3] = maxX;
        bounds[4] = maxY;
        bounds[5] = maxZ;
        return true;
    }

    /**
     * Projects the eight corners of a local box into window coordinates.
     *
     * @param bounds     local {@code {minX,minY,minZ,maxX,maxY,maxZ}}
     * @param model      column-major local-to-world matrix
     * @param view       column-major world-to-camera matrix
     * @param projection column-major projection matrix
     * @param viewport   {@code {x, y, width, height}}
     * @param screen     output {@code {minX,minY,maxX,maxY}} in window coordinates
     * @return {@code false} when a corner is behind the camera or a matrix/value is unusable
     */
    static boolean projectBox(
            final float[] bounds,
            final float[] model,
            final float[] view,
            final float[] projection,
            final int[] viewport,
            final float[] screen) {
        if (bounds == null
                || bounds.length < 6
                || model == null
                || view == null
                || projection == null
                || model.length < 16
                || view.length < 16
                || projection.length < 16
                || viewport == null
                || viewport.length < 4
                || screen == null
                || screen.length < 4) {
            return false;
        }
        if (viewport[2] <= 0 || viewport[3] <= 0) return false;

        final float minX = bounds[0];
        final float minY = bounds[1];
        final float minZ = bounds[2];
        final float maxX = bounds[3];
        final float maxY = bounds[4];
        final float maxZ = bounds[5];

        float screenMinX = Float.POSITIVE_INFINITY;
        float screenMinY = Float.POSITIVE_INFINITY;
        float screenMaxX = Float.NEGATIVE_INFINITY;
        float screenMaxY = Float.NEGATIVE_INFINITY;
        for (int corner = 0; corner < 8; corner++) {
            final float x = (corner & 1) == 0 ? minX : maxX;
            final float y = (corner & 2) == 0 ? minY : maxY;
            final float z = (corner & 4) == 0 ? minZ : maxZ;

            final float worldX = model[0] * x + model[4] * y + model[8] * z + model[12];
            final float worldY = model[1] * x + model[5] * y + model[9] * z + model[13];
            final float worldZ = model[2] * x + model[6] * y + model[10] * z + model[14];

            final float cameraX = view[0] * worldX + view[4] * worldY + view[8] * worldZ + view[12];
            final float cameraY = view[1] * worldX + view[5] * worldY + view[9] * worldZ + view[13];
            final float cameraZ = view[2] * worldX + view[6] * worldY + view[10] * worldZ + view[14];

            final float clipX =
                    projection[0] * cameraX + projection[4] * cameraY + projection[8] * cameraZ + projection[12];
            final float clipY =
                    projection[1] * cameraX + projection[5] * cameraY + projection[9] * cameraZ + projection[13];
            final float clipW =
                    projection[3] * cameraX + projection[7] * cameraY + projection[11] * cameraZ + projection[15];
            if (!finite(clipX) || !finite(clipY) || !finite(clipW) || clipW <= 1.0e-6f) {
                return false;
            }

            final float ndcX = clipX / clipW;
            final float ndcY = clipY / clipW;
            final float windowX = viewport[0] + (ndcX + 1.0f) * 0.5f * viewport[2];
            final float windowY = viewport[1] + (ndcY + 1.0f) * 0.5f * viewport[3];
            if (!finite(windowX) || !finite(windowY)) return false;
            screenMinX = Math.min(screenMinX, windowX);
            screenMinY = Math.min(screenMinY, windowY);
            screenMaxX = Math.max(screenMaxX, windowX);
            screenMaxY = Math.max(screenMaxY, windowY);
        }
        screen[0] = screenMinX;
        screen[1] = screenMinY;
        screen[2] = screenMaxX;
        screen[3] = screenMaxY;
        return true;
    }

    /**
     * Converts a float screen box to an integer rectangle with a one-pixel guard band.
     *
     * @param screen   {@code {minX,minY,maxX,maxY}}
     * @param output   {@code {minX,minY,maxX,maxY}} exclusive maximum
     * @param viewport {@code {x, y, width, height}} for clamping
     */
    static void screenToRect(final float[] screen, final int[] output, final int[] viewport) {
        final int x0 = (int) Math.floor(screen[0]) - 1;
        final int y0 = (int) Math.floor(screen[1]) - 1;
        final int x1 = (int) Math.ceil(screen[2]) + 1;
        final int y1 = (int) Math.ceil(screen[3]) + 1;
        output[0] = Math.max(viewport[0], x0);
        output[1] = Math.max(viewport[1], y0);
        output[2] = Math.min(viewport[0] + viewport[2], x1);
        output[3] = Math.min(viewport[1] + viewport[3], y1);
    }

    /** Unions two rectangles in place into {@code output}. */
    static void union(final int[] first, final int[] second, final int[] output) {
        output[0] = Math.min(first[0], second[0]);
        output[1] = Math.min(first[1], second[1]);
        output[2] = Math.max(first[2], second[2]);
        output[3] = Math.max(first[3], second[3]);
    }

    /** Copies a rectangle. */
    static void copy(final int[] source, final int[] output) {
        System.arraycopy(source, 0, output, 0, RECT_VALUES);
    }

    /** @return the texel count of a rectangle */
    static long area(final int[] rect) {
        final long width = Math.max(0, rect[2] - rect[0]);
        final long height = Math.max(0, rect[3] - rect[1]);
        return width * height;
    }

    /** @return whether {@code rect} covers the whole viewport */
    static boolean coversViewport(final int[] rect, final int[] viewport) {
        return rect[0] <= viewport[0]
                && rect[1] <= viewport[1]
                && rect[2] >= viewport[0] + viewport[2]
                && rect[3] >= viewport[1] + viewport[3];
    }

    private static boolean finite(final float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }
}
