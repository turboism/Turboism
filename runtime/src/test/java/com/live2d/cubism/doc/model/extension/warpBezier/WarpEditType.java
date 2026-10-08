package com.live2d.cubism.doc.model.extension.warpBezier;

/**
 * Test stub of the reviewed host {@code WarpEditType} enum. The bridge only
 * compares constant names ({@code KEEP_RELATION}/{@code TYPE_CUBISM_2_1} vs the
 * SMOOTH_* default branch) and reads {@code getSmoothLevel()}.
 */
public enum WarpEditType {
    SMOOTH_1(1),
    SMOOTH_2(2),
    SMOOTH_3(3),
    SMOOTH_ALL(4),
    TYPE_CUBISM_2_1(0),
    KEEP_RELATION(0);

    private final int smoothLevel;

    WarpEditType(final int smoothLevel) {
        this.smoothLevel = smoothLevel;
    }

    public int getSmoothLevel() {
        return smoothLevel;
    }
}
