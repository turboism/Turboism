package com.live2d.cubism.doc.model.deformer.warp;

import dev.turboism.adapter.cubism.warpalt.NativeWarpAltMirrorBridge;

/** The native canvas-to-local wrapper remains on the stack at the point-write hook. */
public final class r {
    private r() {}

    public static void a(Object ref, Object target, float weight) {
        NativeWarpAltMirrorBridge.mirrorPointMove(ref, target, weight);
    }
}
