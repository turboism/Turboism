package com.formdev.flatlaf;

/** Test-only FlatLaf shape observed reflectively through the host classloader. */
public final class FlatLaf {
    private static int updateUiCalls;
    private static boolean throwOnUpdateUi;

    private FlatLaf() {
    }

    public static void updateUI() {
        if (throwOnUpdateUi) {
            throw new IllegalStateException("test-injected updateUI failure");
        }
        updateUiCalls++;
    }

    public static void reset() {
        updateUiCalls = 0;
        throwOnUpdateUi = false;
    }

    public static int updateUiCalls() {
        return updateUiCalls;
    }

    public static void throwOnUpdateUi(final boolean flag) {
        throwOnUpdateUi = flag;
    }
}
