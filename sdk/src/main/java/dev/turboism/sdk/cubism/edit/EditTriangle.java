package dev.turboism.sdk.cubism.edit;

/**
 * One mesh triangle expressed as three indices into the vertex list.
 *
 * @param a first vertex index
 * @param b second vertex index
 * @param c third vertex index
 */
public record EditTriangle(int a, int b, int c) {

    public EditTriangle {
        if (a < 0 || b < 0 || c < 0) {
            throw new IllegalArgumentException("triangle indices must be non-negative");
        }
    }
}
