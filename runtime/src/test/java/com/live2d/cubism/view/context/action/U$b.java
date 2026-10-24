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

    public static void writeArtMesh(final Object ref, final Object target, final float weight) throws Exception {
        if (!dev.turboism.adapter.cubism.warpalt.NativeArtMeshSymmetryBridge.move(ref, target, weight)) {
            ref.getClass().getMethod("a", target.getClass(), float.class).invoke(ref, target, weight);
        }
    }

    public static void writePoint(final Object ref, final Object target, final float blendWeight) {
        NativeWarpAltMirrorBridge.mirrorPointMove(ref, target, blendWeight);
    }
}
