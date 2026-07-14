package com.live2d.cubism;

/** Test-only host marker loaded by the preview agent smoke process. */
public final class CEAppCtrl {
    private CEAppCtrl() {
    }

    public static void touch() {
        // Forces this exact class to be loaded without requiring a real Cubism installation.
    }
}
