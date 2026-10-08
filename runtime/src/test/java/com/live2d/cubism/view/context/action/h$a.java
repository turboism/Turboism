package com.live2d.cubism.view.context.action;

import dev.turboism.adapter.cubism.warpalt.NativeWarpAltMirrorBridge;

/**
 * Stub of the bounding-box transform action {@code h$a}: it iterates over the
 * transformable refs and calls {@code moveToOnLocal} on each. The point-move
 * mirror must refuse to mirror writes originating from such bulk actions, so
 * tests call {@link #writePoint} with a non-whitelisted caller frame.
 */
public final class h$a {

    private h$a() {}

    public static void writePoint(final Object ref, final Object target, final float blendWeight) {
        NativeWarpAltMirrorBridge.mirrorPointMove(ref, target, blendWeight);
    }
}
