package com.live2d.cubism.setting;

import com.live2d.cubism.doc.model.extension.warpBezier.WarpEditType;

/**
 * Test stub of the reviewed host {@code AppSetting} singleton chain
 * ({@code INSTANCE.getDeformer().getWarpDeformer().getCurrentWarpEditType()}).
 */
public final class AppSetting {

    public static final AppSetting INSTANCE = new AppSetting();

    private final Deformer deformer = new Deformer();

    private AppSetting() {}

    public Deformer getDeformer() {
        return deformer;
    }

    /** Stub of the nested deformer settings node. */
    public static final class Deformer {
        private final WarpDeformer warp = new WarpDeformer();

        public WarpDeformer getWarpDeformer() {
            return warp;
        }
    }

    /** Stub of the warp settings node exposing the active edit type. */
    public static final class WarpDeformer {
        private WarpEditType current = WarpEditType.SMOOTH_ALL;

        public WarpEditType getCurrentWarpEditType() {
            return current;
        }

        /** Test hook: switch the active edit type. */
        public void setCurrentWarpEditType(final WarpEditType type) {
            current = type;
        }
    }
}
