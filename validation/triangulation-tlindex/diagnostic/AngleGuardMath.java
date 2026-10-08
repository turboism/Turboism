/** Owned T056 conservative branch predicate; never an angle replacement API. */
final class AngleGuardMath {
    private AngleGuardMath() { }
    static boolean reject(float cross, float dot) {
        if (!Float.isFinite(cross) || !Float.isFinite(dot)) return false;
        if (Math.abs(cross) < Float.MIN_NORMAL || Math.abs(dot) < Float.MIN_NORMAL) return false;
        if (dot < 0.0f) return true;
        return Math.abs((double) cross) > (double) dot * 2.0e-6;
    }
}
