package dev.turboism.validation.modelupdate;

/** One continuous native gesture; callers implement identity checks and repaint barriers. */
final class NativeDragSequence {
    interface Driver {
        void press() throws Exception;
        void drag(int step) throws Exception;
        void release() throws Exception;
    }
    static void execute(int steps, Driver driver) throws Exception {
        if (steps < 1 || steps > 4096) throw new IllegalArgumentException("invalid gesture length");
        Throwable primary = null;
        try {
            driver.press();
            for (int i = 0; i < steps; i++) driver.drag(i);
        } catch (Exception | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            try { driver.release(); }
            catch (Exception | Error cleanup) {
                if (primary != null) primary.addSuppressed(cleanup); else throw cleanup;
            }
        }
    }
    static boolean allVerticesMoved(float[] first, float[] second) {
        if (first.length != second.length || first.length < 6 || (first.length & 1) != 0) return false;
        for (int i = 0; i < first.length; i += 2) {
            if (!Float.isFinite(first[i]) || !Float.isFinite(first[i + 1])
                || !Float.isFinite(second[i]) || !Float.isFinite(second[i + 1])) return false;
            if (first[i] == second[i] && first[i + 1] == second[i + 1]) return false;
        }
        return true;
    }
    static boolean same(float[] first, float[] second) {
        if (first.length != second.length) return false;
        for (int i = 0; i < first.length; i++) {
            if (Float.floatToRawIntBits(first[i]) != Float.floatToRawIntBits(second[i])) return false;
        }
        return true;
    }
}
