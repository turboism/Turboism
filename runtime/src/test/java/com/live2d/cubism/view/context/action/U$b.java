package com.live2d.cubism.view.context.action;

import dev.turboism.adapter.cubism.warpalt.NativeWarpAltMirrorBridge;

/**
 * Stub of the reviewed single-point drag action {@code U$b}: its drag tick
 * writes the selected point via {@code moveToOnLocal}, which the instrumented
 * host redirects into {@link NativeWarpAltMirrorBridge#mirrorPointMove}. Tests
 * call {@link #writePoint} so the whitelisted caller frame is on the stack.
 */
public final class U$b {

    private U$b() {}

    public static void writePoint(final Object ref, final Object target, final float blendWeight) {
        NativeWarpAltMirrorBridge.mirrorPointMove(ref, target, blendWeight);
    }
}
