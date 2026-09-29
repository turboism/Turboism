package dev.turboism.validation.triprobe;

/** Init-failure stub: the class initializer throws, so the woven invokestatic fails with
 * ExceptionInInitializerError/NoClassDefFoundError and must be absorbed by the weave catch. */
public final class Probe {
    static {
        if (System.currentTimeMillis() >= 0) {
            throw new IllegalStateException("stub clinit failure");
        }
    }
    private Probe() {}
    public static void record(Object owner, Object set) {}
}
