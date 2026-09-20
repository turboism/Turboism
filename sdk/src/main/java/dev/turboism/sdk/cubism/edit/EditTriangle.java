package dev.turboism.sdk.cubism.edit;

import dev.turboism.sdk.CubismEditor;
/**
 * One mesh triangle expressed as three indices into the vertex list.
 *
 * @param a first vertex index
 * @param b second vertex index
 * @param c third vertex index
 */
@CubismEditor({"5.2.03", "5.3.02", "5.3.03"})
public record EditTriangle(int a, int b, int c) {

    public EditTriangle {
        if (a < 0 || b < 0 || c < 0) {
            throw new IllegalArgumentException("triangle indices must be non-negative");
        }
    }
}
