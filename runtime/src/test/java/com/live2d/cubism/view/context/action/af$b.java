package com.live2d.cubism.view.context.action;

import dev.turboism.adapter.cubism.warpalt.NativeWarpAltMirrorBridge;

/**
 * Stub of the 5.3.04 single-point drag action {@code af$b}: its drag tick
 * writes the selected point via {@code moveToOnLocal}, which the instrumented
 * host redirects into {@link NativeWarpAltMirrorBridge#mirrorPointMove}.
 */
public final class af$b {

    private af$b() {}

    public static void writePoint(final Object ref, final Object target, final float blendWeight) {
        com.live2d.cubism.doc.model.deformer.warp.r.a(ref, target, blendWeight);
    }
}
